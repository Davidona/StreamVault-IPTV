package com.streamvault.data.sync

import android.content.Context
import android.database.sqlite.SQLiteException
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.gson.Gson
import com.streamvault.data.local.dao.ProviderDao
import com.streamvault.data.local.dao.ProviderConfigRevisionDao
import com.streamvault.data.local.dao.ChannelDao
import com.streamvault.data.local.dao.CategoryDao
import com.streamvault.data.local.dao.XtreamIndexJobDao
import com.streamvault.data.local.dao.XtreamLiveOnboardingDao
import com.streamvault.data.local.DatabaseTransactionRunner
import com.streamvault.data.local.entity.ProviderConfigRevisionState
import com.streamvault.data.local.entity.ProviderEntity
import com.streamvault.data.local.entity.ProviderConfigRevisionEntity
import com.streamvault.data.local.entity.ProviderWorkflowPhase
import com.streamvault.data.local.entity.ProviderWorkflowReason
import com.streamvault.data.local.entity.XtreamIndexJobEntity
import com.streamvault.data.mapper.toDomain
import com.streamvault.data.security.CredentialCrypto
import com.streamvault.data.provider.ProviderConfigurationCodec
import com.streamvault.data.provider.ProviderConfigRevisionCodec
import com.streamvault.data.provider.toAccountRuntime
import com.streamvault.data.provider.toTypedConfiguration
import com.streamvault.data.provider.guidePolicy
import com.streamvault.data.provider.logoPolicy
import com.streamvault.data.local.dao.ProviderSnapshotDao
import com.streamvault.data.local.entity.ProviderConfigEntity
import com.streamvault.data.local.entity.ProviderAccountRuntimeEntity
import com.streamvault.domain.model.ProviderStatus
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.ProviderEpgSyncMode
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.SyncState
import com.streamvault.domain.repository.SyncMetadataRepository
import com.streamvault.domain.util.PersistedTimestampPolicy
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit

internal suspend fun reconcileTargetedProviderStatus(
    providerDao: ProviderDao,
    channelDao: ChannelDao,
    categoryDao: CategoryDao,
    syncMetadataRepository: SyncMetadataRepository,
    syncManager: ProviderSyncCommands,
    provider: com.streamvault.data.local.entity.ProviderEntity,
    result: com.streamvault.domain.model.Result<Unit>,
    activateOnSuccess: Boolean = true,
    currentTimeMillis: Long = System.currentTimeMillis()
) {
    when (result) {
        is com.streamvault.domain.model.Result.Success -> {
            val finalStatus = if (syncManager.currentSyncState(provider.id) is SyncState.Partial) {
                ProviderStatus.PARTIAL
            } else {
                ProviderStatus.ACTIVE
            }
            if (!hasUsableLiveCatalogForActivation(
                    provider.id,
                    provider.type,
                    channelDao,
                    categoryDao,
                    syncMetadataRepository
                )) {
                providerDao.update(
                    provider.copy(
                        isActive = false,
                        status = ProviderStatus.PARTIAL,
                        lastSyncedAt = currentTimeMillis
                    )
                )
                return
            }
            val currentProvider = providerDao.getById(provider.id) ?: provider
            providerDao.update(
                provider.copy(
                    // A periodic sync must never change the user's selected provider. The
                    // explicit targeted-resume path may activate its provider, while all other
                    // successful syncs retain the current persisted selection.
                    isActive = activateOnSuccess || currentProvider.isActive,
                    status = finalStatus,
                    lastSyncedAt = currentTimeMillis
                )
            )
        }
        is com.streamvault.domain.model.Result.Error -> {
            if (provider.status != ProviderStatus.PARTIAL) {
                providerDao.update(provider.copy(isActive = false, status = ProviderStatus.ERROR))
            }
        }
        is com.streamvault.domain.model.Result.Loading -> Unit
    }
}

internal fun isFreshRunningIndexJob(
    updatedAt: Long,
    now: Long,
    staleAfterMillis: Long
): Boolean = PersistedTimestampPolicy.isFresh(updatedAt, now, staleAfterMillis)

internal fun shouldRunPersistedIndexJob(
    job: XtreamIndexJobEntity?,
    now: Long,
    staleRunningAfterMillis: Long,
    successTtlMillis: Long
): Boolean {
    if (job == null) return true
    if (job.state in setOf("QUEUED", "PARTIAL", "STALE", "FAILED_RETRYABLE")) return true
    if (job.state == "RUNNING") {
        return !isFreshRunningIndexJob(job.updatedAt, now, staleRunningAfterMillis)
    }
    return ContentCachePolicy.shouldRefresh(job.lastSuccessAt, successTtlMillis, now)
}

internal suspend fun shouldTrackInitialLiveOnboarding(
    provider: com.streamvault.data.local.entity.ProviderEntity,
    onboardingDao: XtreamLiveOnboardingDao
): Boolean = provider.type == ProviderType.XTREAM_CODES &&
    onboardingDao.getIncompleteByProvider(provider.id) != null

internal fun isObsoleteProviderConfigRevision(
    revision: ProviderConfigRevisionEntity?,
    providerExists: Boolean
): Boolean = revision == null ||
    !providerExists ||
    revision.state == ProviderConfigRevisionState.COMMITTED ||
    revision.state == ProviderConfigRevisionState.SUPERSEDED

class ProviderSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ProviderSyncWorkerEntryPoint {
        fun providerDao(): ProviderDao
        fun channelDao(): ChannelDao
        fun categoryDao(): CategoryDao
        fun syncCommands(): ProviderSyncCommands
        fun syncMetadataRepository(): SyncMetadataRepository
        fun providerConfigRevisionDao(): ProviderConfigRevisionDao
        fun credentialCrypto(): CredentialCrypto
        fun providerConfigurationCodec(): ProviderConfigurationCodec
        fun providerSnapshotDao(): ProviderSnapshotDao
        fun gson(): Gson
        fun xtreamIndexJobDao(): XtreamIndexJobDao
        fun xtreamLiveOnboardingDao(): XtreamLiveOnboardingDao
        fun providerWorkflowRunner(): ProviderWorkflowRunner
        fun providerWorkflowCommitFence(): ProviderWorkflowCommitFence
        fun databaseTransactionRunner(): DatabaseTransactionRunner
    }

    override suspend fun doWork(): Result {
        return try {
            val entryPoint = EntryPointAccessors.fromApplication(
                applicationContext,
                ProviderSyncWorkerEntryPoint::class.java
            )
            val requestedProviderId = inputData.getLong(KEY_PROVIDER_ID, INVALID_PROVIDER_ID)
            val requestedRevision = inputData.getLong(KEY_PROVIDER_CONFIG_REVISION, INVALID_REVISION)
            if (requestedRevision != INVALID_REVISION) {
                return runConfigRevisionWorkflow(
                    entryPoint,
                    requestedProviderId,
                    requestedRevision
                ).toWorkResult()
            }

            var sawRetryableFailure = false
            val recoveryNow = System.currentTimeMillis()
            val staleSyncingBefore = recoveryNow - STALE_CONFIG_SYNC_MILLIS
            val revisionDao = entryPoint.providerConfigRevisionDao()
            // Never run the committed configuration while a newer edit is being recovered.
            // That would turn a recovery attempt into an implicit fallback to the old settings.
            val providerIdsWithPendingEdits = revisionDao.getRecoverable()
                .mapTo(mutableSetOf()) { it.providerId }
            revisionDao
                .getRecoveryCandidates(recoveryNow, staleSyncingBefore)
                .forEach { revision ->
                    when (
                        runConfigRevisionWorkflow(
                            entryPoint,
                            revision.providerId,
                            revision.revision
                        )
                    ) {
                        ProviderWorkflowDisposition.RETRY,
                        ProviderWorkflowDisposition.BUSY -> sawRetryableFailure = true
                        else -> Unit
                    }
                }

            val providers = if (requestedProviderId != INVALID_PROVIDER_ID) {
                entryPoint.providerDao().getById(requestedProviderId)?.let(::listOf).orEmpty()
            } else {
                entryPoint.providerDao().getAllSync()
            }
            if (providers.isEmpty()) {
                return if (sawRetryableFailure) Result.retry() else Result.success()
            }

            var sawPermanentFailure = false
            providers.filterNot { it.id in providerIdsWithPendingEdits }.forEach { provider ->
                val disposition = entryPoint.providerWorkflowRunner().execute(
                    providerId = provider.id,
                    phase = ProviderWorkflowPhase.PRIMARY_CATALOG,
                    reason = if (requestedProviderId == provider.id) {
                        ProviderWorkflowReason.RECOVERY
                    } else {
                        ProviderWorkflowReason.PERIODIC
                    }
                ) {
                    val trackInitialLiveOnboarding = shouldTrackInitialLiveOnboarding(
                        provider = provider,
                        onboardingDao = entryPoint.xtreamLiveOnboardingDao()
                    )
                    val result = if (requestedProviderId == provider.id) {
                        entryPoint.syncCommands().sync(
                            provider.id,
                            force = false,
                            trackInitialLiveOnboarding = trackInitialLiveOnboarding
                        )
                    } else if (provider.type == ProviderType.XTREAM_CODES) {
                        syncXtreamProviderIfStale(entryPoint, provider)
                    } else if (provider.type == ProviderType.STALKER_PORTAL) {
                        syncStalkerProviderIfStale(entryPoint, provider)
                    } else {
                        entryPoint.syncCommands().sync(provider.id, force = false)
                    }
                    if (requestedProviderId == provider.id || !provider.isActive) {
                        reconcileTargetedProviderStatusFenced(
                            entryPoint = entryPoint,
                            provider = provider,
                            result = result,
                            activateOnSuccess = requestedProviderId == provider.id
                        )
                    }
                    when (result) {
                        is com.streamvault.domain.model.Result.Success ->
                            ProviderWorkflowOutcome.Success(
                                partial = entryPoint.syncCommands().currentSyncState(provider.id) is SyncState.Partial
                            )
                        is com.streamvault.domain.model.Result.Error -> {
                            Log.w(TAG, "Provider sync worker failed for provider ${provider.id}: ${result.message}")
                            ProviderWorkflowOutcome.Failure(
                                code = "PROVIDER_SYNC",
                                message = result.message,
                                cause = result.exception
                            )
                        }
                        com.streamvault.domain.model.Result.Loading ->
                            ProviderWorkflowOutcome.Failure(
                                code = "PROVIDER_SYNC_LOADING",
                                message = "Provider sync did not reach a terminal state.",
                                retryable = true
                            )
                    }
                }
                when (disposition) {
                    ProviderWorkflowDisposition.RETRY,
                    ProviderWorkflowDisposition.BUSY -> sawRetryableFailure = true
                    ProviderWorkflowDisposition.FAILED -> sawPermanentFailure = true
                    else -> Unit
                }
            }

            when {
                sawRetryableFailure -> Result.retry()
                sawPermanentFailure -> Result.failure()
                else -> Result.success()
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.e(TAG, "Provider sync worker failed", e)
            if (shouldRetry(e)) Result.retry() else Result.failure()
        }
    }

    private fun shouldRetry(error: Throwable?): Boolean {
        return ProviderWorkFailureClassifier.isRetryable(error)
    }

    private fun ProviderWorkflowDisposition.toWorkResult(): Result = when (this) {
        ProviderWorkflowDisposition.SUCCEEDED,
        ProviderWorkflowDisposition.SUPERSEDED -> Result.success()
        ProviderWorkflowDisposition.RETRY,
        ProviderWorkflowDisposition.BUSY -> Result.retry()
        ProviderWorkflowDisposition.FAILED -> Result.failure()
    }

    private suspend fun runConfigRevisionWorkflow(
        entryPoint: ProviderSyncWorkerEntryPoint,
        providerId: Long,
        revisionNumber: Long
    ): ProviderWorkflowDisposition {
        if (providerId == INVALID_PROVIDER_ID) {
            return ProviderWorkflowDisposition.FAILED
        }
        // WorkManager may deliver an old revision after the provider was deleted or after a
        // newer edit superseded it. Do this check before creating a workflow row: the workflow
        // tables are foreign-keyed to providers, so a deleted provider must be a successful
        // no-op rather than a retrying database error.
        val revision = entryPoint.providerConfigRevisionDao().get(providerId, revisionNumber)
        if (isObsoleteProviderConfigRevision(revision, entryPoint.providerDao().getById(providerId) != null)) {
            return ProviderWorkflowDisposition.SUCCEEDED
        }
        return entryPoint.providerWorkflowRunner().execute(
            providerId = providerId,
            phase = ProviderWorkflowPhase.PREPARE,
            reason = ProviderWorkflowReason.CONFIG_CHANGE,
            force = true,
            supersede = true,
            priority = CONFIG_CHANGE_PRIORITY
        ) {
            when (recoverProviderConfigRevision(entryPoint, providerId, revisionNumber)) {
                RevisionRecoveryOutcome.SUCCESS -> ProviderWorkflowOutcome.Success()
                RevisionRecoveryOutcome.RETRY -> ProviderWorkflowOutcome.Failure(
                    code = "CONFIG_REVISION_RETRY",
                    message = "Provider configuration recovery needs retry.",
                    retryable = true
                )
                RevisionRecoveryOutcome.FAILURE -> ProviderWorkflowOutcome.Failure(
                    code = "CONFIG_REVISION_FAILED",
                    message = "Provider configuration recovery failed.",
                    retryable = false
                )
            }
        }
    }

    private suspend fun reconcileTargetedProviderStatusFenced(
        entryPoint: ProviderSyncWorkerEntryPoint,
        provider: ProviderEntity,
        result: com.streamvault.domain.model.Result<Unit>,
        activateOnSuccess: Boolean = true
    ) {
        entryPoint.databaseTransactionRunner().inTransaction {
            entryPoint.providerWorkflowCommitFence().assertCanCommit(provider.id)
            val activeProviderId = entryPoint.providerDao().getAllSync()
                .firstOrNull { it.isActive }
                ?.id
            reconcileTargetedProviderStatus(
                providerDao = entryPoint.providerDao(),
                channelDao = entryPoint.channelDao(),
                categoryDao = entryPoint.categoryDao(),
                syncMetadataRepository = entryPoint.syncMetadataRepository(),
                syncManager = entryPoint.syncCommands(),
                provider = provider,
                result = result,
                activateOnSuccess = activateOnSuccess &&
                    (activeProviderId == null || activeProviderId == provider.id)
            )
        }
    }

    /**
     * Restarts only the durable candidate configuration. It never feeds the committed provider
     * row back into the sync pipeline, so a process restart cannot silently discard an edit.
     */
    private suspend fun recoverProviderConfigRevision(
        entryPoint: ProviderSyncWorkerEntryPoint,
        providerId: Long,
        revisionNumber: Long
    ): RevisionRecoveryOutcome {
        if (providerId == INVALID_PROVIDER_ID) return RevisionRecoveryOutcome.SUCCESS
        val revisionDao = entryPoint.providerConfigRevisionDao()
        val revision = revisionDao.get(providerId, revisionNumber) ?: return RevisionRecoveryOutcome.SUCCESS
        if (revision.state == ProviderConfigRevisionState.COMMITTED ||
            revision.state == ProviderConfigRevisionState.SUPERSEDED
        ) {
            return RevisionRecoveryOutcome.SUCCESS
        }

        val now = System.currentTimeMillis()
        if (revision.state == ProviderConfigRevisionState.SYNCING) {
            revisionDao.releaseForRetry(providerId, revisionNumber, now)
        }
        if (revisionDao.claimForSync(providerId, revisionNumber, now) != 1) {
            return RevisionRecoveryOutcome.SUCCESS
        }

        val decodedRevision = try {
            ProviderConfigRevisionCodec(
                entryPoint.gson(),
                entryPoint.providerConfigurationCodec(),
                entryPoint.credentialCrypto()
            ).decode(revision.configJson)
        } catch (error: Exception) {
            revisionDao.markFailed(providerId, revisionNumber, "Saved provider edit is invalid.", now)
            return RevisionRecoveryOutcome.FAILURE
        }
        val secureCandidate = decodedRevision.secureEntity
        if (secureCandidate.id != providerId) {
            revisionDao.markFailed(providerId, revisionNumber, "Saved provider edit did not match its provider.", now)
            return RevisionRecoveryOutcome.FAILURE
        }

        val candidate = decodedRevision.candidate
        val currentConfigGeneration = entryPoint.providerSnapshotDao()
            .getConfig(providerId)?.configurationGeneration ?: 0L
        val commitGeneration = if (decodedRevision.wasLegacy) {
            currentConfigGeneration + 1L
        } else {
            decodedRevision.configurationGeneration
        }

        val result = entryPoint.syncCommands().syncWithProviderOverride(
            providerId = providerId,
            // Recovery must revalidate the staged configuration instead of accepting a fresh
            // cache belonging to the previously committed configuration.
            force = true,
            providerOverride = candidate,
            afterCatalogApply = {
                val committedAt = System.currentTimeMillis()
                val configuration = candidate.toTypedConfiguration()
                check(entryPoint.providerSnapshotDao().commitConfiguration(
                    ProviderConfigEntity(
                        providerId = providerId,
                        type = configuration.type,
                        schemaVersion = configuration.schemaVersion,
                        configurationGeneration = commitGeneration,
                        identityKey = entryPoint.providerConfigurationCodec().identityKey(configuration),
                        encryptedConfigJson = entryPoint.providerConfigurationCodec().encode(configuration),
                        guideSourcePolicy = configuration.guidePolicy(),
                        channelLogoSourcePolicy = configuration.logoPolicy(),
                        updatedAt = committedAt
                    )
                )) { "Provider edit configuration was superseded before commit." }
                val runtime = candidate.toAccountRuntime()
                entryPoint.providerSnapshotDao().upsertRuntime(
                    ProviderAccountRuntimeEntity(
                        providerId = providerId,
                        maxConnections = runtime.maxConnections,
                        expirationDate = runtime.expirationDate,
                        apiVersion = runtime.apiVersion,
                        allowedOutputFormatsJson = entryPoint.gson().toJson(runtime.allowedOutputFormats),
                        catalogLayout = runtime.catalogLayout,
                        catalogLayoutDetectionVersion = runtime.catalogLayoutDetectionVersion,
                        observedAt = runtime.observedAt
                    )
                )
                check(revisionDao.markCommitted(providerId, revisionNumber, committedAt) == 1) {
                    "Provider edit was superseded before recovery could commit it."
                }
                entryPoint.providerDao().update(
                    secureCandidate.copy(
                        isActive = true,
                        status = ProviderStatus.PARTIAL,
                        lastSyncedAt = committedAt
                    )
                )
            }
        )
        val finalState = revisionDao.getState(providerId, revisionNumber)
        return when (result) {
            is com.streamvault.domain.model.Result.Success -> {
                if (finalState != ProviderConfigRevisionState.COMMITTED) {
                    revisionDao.markFailed(
                        providerId,
                        revisionNumber,
                        "Recovery sync completed without committing catalog content.",
                        System.currentTimeMillis()
                    )
                    RevisionRecoveryOutcome.FAILURE
                } else {
                    entryPoint.providerDao().getById(providerId)?.let { committedProvider ->
                        reconcileTargetedProviderStatusFenced(entryPoint, committedProvider, result)
                    }
                    RevisionRecoveryOutcome.SUCCESS
                }
            }
            is com.streamvault.domain.model.Result.Error -> {
                if (finalState == ProviderConfigRevisionState.COMMITTED) {
                    entryPoint.providerDao().getById(providerId)?.let { committedProvider ->
                        entryPoint.databaseTransactionRunner().inTransaction {
                            entryPoint.providerWorkflowCommitFence().assertCanCommit(providerId)
                            entryPoint.providerDao().update(
                                committedProvider.copy(isActive = true, status = ProviderStatus.PARTIAL)
                            )
                        }
                    }
                } else {
                    revisionDao.markFailed(
                        providerId,
                        revisionNumber,
                        result.message,
                        System.currentTimeMillis()
                    )
                }
                if (shouldRetry(result.exception)) {
                    RevisionRecoveryOutcome.RETRY
                } else {
                    RevisionRecoveryOutcome.FAILURE
                }
            }
            is com.streamvault.domain.model.Result.Loading -> RevisionRecoveryOutcome.RETRY
        }
    }

    private enum class RevisionRecoveryOutcome {
        SUCCESS,
        RETRY,
        FAILURE;

        fun toWorkResult(): Result = when (this) {
            SUCCESS -> Result.success()
            RETRY -> Result.retry()
            FAILURE -> Result.failure()
        }
    }

    companion object {
        private const val TAG = "ProviderSyncWorker"
        private const val STALE_RUNNING_JOB_MILLIS = 15 * 60 * 1000L
        private const val UNIQUE_WORK_NAME = "provider-sync-worker"
        private const val UNIQUE_LAUNCH_STALE_WORK_NAME = "provider-sync-launch-stale-check"
        private const val KEY_PROVIDER_ID = "provider_id"
        private const val KEY_PROVIDER_CONFIG_REVISION = "provider_config_revision"
        private const val INVALID_PROVIDER_ID = -1L
        private const val INVALID_REVISION = -1L
        private const val STALE_CONFIG_SYNC_MILLIS = 60_000L
        private const val CONFIG_REVISION_RECOVERY_GRACE_MILLIS = 5 * 60 * 1000L
        private const val CONFIG_CHANGE_PRIORITY = 100

        fun enqueuePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<ProviderSyncWorker>(6, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    10,
                    TimeUnit.MINUTES
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun enqueueLaunchStaleCheck(context: Context) {
            val request = OneTimeWorkRequestBuilder<ProviderSyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .setInitialDelay(10, TimeUnit.SECONDS)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_LAUNCH_STALE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request
            )
        }

        fun enqueueProvider(context: Context, providerId: Long) {
            val request = OneTimeWorkRequestBuilder<ProviderSyncWorker>()
                .setInputData(workDataOf(KEY_PROVIDER_ID to providerId))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                providerWorkUniqueName(providerId),
                providerWorkExistingPolicy(supersede = false),
                request
            )
        }

        fun enqueueProviderConfigRevision(
            context: Context,
            providerId: Long,
            revision: Long,
            immediate: Boolean = true
        ) {
            val request = OneTimeWorkRequestBuilder<ProviderSyncWorker>()
                .setInputData(
                    workDataOf(
                        KEY_PROVIDER_ID to providerId,
                        KEY_PROVIDER_CONFIG_REVISION to revision
                    )
                )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .setInitialDelay(
                    if (immediate) 0 else CONFIG_REVISION_RECOVERY_GRACE_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                providerWorkUniqueName(providerId),
                providerWorkExistingPolicy(supersede = true),
                request
            )
        }
    }

    private suspend fun syncXtreamProviderIfStale(
        entryPoint: ProviderSyncWorkerEntryPoint,
        provider: com.streamvault.data.local.entity.ProviderEntity
    ): com.streamvault.domain.model.Result<Unit> {
        val now = System.currentTimeMillis()
        if (shouldTrackInitialLiveOnboarding(provider, entryPoint.xtreamLiveOnboardingDao())) {
            return entryPoint.syncCommands().sync(
                provider.id,
                force = false,
                trackInitialLiveOnboarding = true
            )
        }
        val metadata = entryPoint.syncMetadataRepository().getMetadata(provider.id)
        val liveStale = ContentCachePolicy.shouldRefresh(
            metadata?.lastLiveSuccess ?: 0L,
            ContentCachePolicy.CATALOG_TTL_MILLIS,
            now
        )
        val epgStale = providerEpgSyncMode(entryPoint, provider.id) != ProviderEpgSyncMode.SKIP &&
            ContentCachePolicy.shouldRefresh(
                metadata?.lastEpgSuccess ?: 0L,
                ContentCachePolicy.EPG_TTL_MILLIS,
                now
            )
        val movieIndexDue = shouldRunIndexJob(entryPoint, provider.id, ContentType.MOVIE, now)
        val seriesIndexDue = shouldRunIndexJob(entryPoint, provider.id, ContentType.SERIES, now)

        if (!provider.isActive) {
            return com.streamvault.domain.model.Result.success(Unit)
        }

        if (liveStale) {
            when (val liveResult = entryPoint.syncCommands().retrySection(
                provider.id,
                SyncRepairSection.LIVE,
                syncReason = XtreamLiveSyncReason.BACKGROUND_STALE
            )) {
                is com.streamvault.domain.model.Result.Error -> return liveResult
                else -> Unit
            }
        }
        if (epgStale) {
            when (val epgResult = entryPoint.syncCommands().syncEpg(provider.id, force = false)) {
                is com.streamvault.domain.model.Result.Error -> return epgResult
                else -> Unit
            }
        }
        if (movieIndexDue) {
            entryPoint.syncCommands().scheduleXtreamIndexSync(provider.id, ContentType.MOVIE)
        }
        if (seriesIndexDue) {
            entryPoint.syncCommands().scheduleXtreamIndexSync(provider.id, ContentType.SERIES)
        }
        return com.streamvault.domain.model.Result.success(Unit)
    }

    private suspend fun shouldRunIndexJob(
        entryPoint: ProviderSyncWorkerEntryPoint,
        providerId: Long,
        section: ContentType,
        now: Long
    ): Boolean {
        return shouldRunPersistedIndexJob(
            job = entryPoint.xtreamIndexJobDao().get(providerId, section.name),
            now = now,
            staleRunningAfterMillis = STALE_RUNNING_JOB_MILLIS,
            successTtlMillis = ContentCachePolicy.CATALOG_TTL_MILLIS
        )
    }

    private suspend fun syncStalkerProviderIfStale(
        entryPoint: ProviderSyncWorkerEntryPoint,
        provider: com.streamvault.data.local.entity.ProviderEntity
    ): com.streamvault.domain.model.Result<Unit> {
        val now = System.currentTimeMillis()
        val metadata = entryPoint.syncMetadataRepository().getMetadata(provider.id)
        val liveStale = ContentCachePolicy.shouldRefresh(
            metadata?.lastLiveSuccess ?: 0L,
            ContentCachePolicy.CATALOG_TTL_MILLIS,
            now
        )
        val epgStale = providerEpgSyncMode(entryPoint, provider.id) != ProviderEpgSyncMode.SKIP &&
            ContentCachePolicy.shouldRefresh(
                metadata?.lastEpgSuccess ?: 0L,
                ContentCachePolicy.EPG_TTL_MILLIS,
                now
            )
        val movieIndexDue = shouldRunIndexJob(entryPoint, provider.id, ContentType.MOVIE, now)
        val seriesIndexDue = shouldRunIndexJob(entryPoint, provider.id, ContentType.SERIES, now)

        if (!provider.isActive) {
            return com.streamvault.domain.model.Result.success(Unit)
        }

        if (liveStale) {
            when (val liveResult = entryPoint.syncCommands().retrySection(provider.id, SyncRepairSection.LIVE)) {
                is com.streamvault.domain.model.Result.Error -> return liveResult
                else -> Unit
            }
        }
        if (movieIndexDue) {
            entryPoint.syncCommands().scheduleStalkerIndexSync(provider.id, ContentType.MOVIE)
        }
        if (seriesIndexDue) {
            entryPoint.syncCommands().scheduleStalkerIndexSync(provider.id, ContentType.SERIES)
        }
        if (epgStale) {
            entryPoint.syncCommands().scheduleBackgroundEpgSync(provider.id)
        }
        return com.streamvault.domain.model.Result.success(Unit)
    }

    private suspend fun providerEpgSyncMode(
        entryPoint: ProviderSyncWorkerEntryPoint,
        providerId: Long
    ): ProviderEpgSyncMode {
        val stored = entryPoint.providerSnapshotDao().getConfig(providerId)
            ?: return ProviderEpgSyncMode.SKIP
        return when (val configuration = entryPoint.providerConfigurationCodec().decode(
            stored.type,
            stored.encryptedConfigJson
        )) {
            is com.streamvault.domain.model.XtreamConfig -> configuration.epgSyncMode
            is com.streamvault.domain.model.M3uConfig -> configuration.epgSyncMode
            is com.streamvault.domain.model.StalkerConfig -> configuration.epgSyncMode
            is com.streamvault.domain.model.JellyfinConfig -> ProviderEpgSyncMode.SKIP
        }
    }

    private suspend fun reconcileTargetedProviderStatus(
        entryPoint: ProviderSyncWorkerEntryPoint,
        provider: com.streamvault.data.local.entity.ProviderEntity,
        result: com.streamvault.domain.model.Result<Unit>
    ) {
        reconcileTargetedProviderStatus(
            providerDao = entryPoint.providerDao(),
            channelDao = entryPoint.channelDao(),
            categoryDao = entryPoint.categoryDao(),
            syncMetadataRepository = entryPoint.syncMetadataRepository(),
            syncManager = entryPoint.syncCommands(),
            provider = provider,
            result = result
        )
    }
}
