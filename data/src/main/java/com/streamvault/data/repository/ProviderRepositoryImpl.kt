package com.streamvault.data.repository

import com.streamvault.domain.model.Provider as StableProvider

import android.content.Context
import com.google.gson.Gson
import com.streamvault.data.local.dao.ProviderDeletionCleanupDao
import com.streamvault.data.local.dao.ProviderConfigRevisionDao
import com.streamvault.data.local.entity.ProviderDeletionCleanupEntity
import com.streamvault.data.local.entity.ProviderConfigRevisionEntity
import com.streamvault.data.local.entity.ProviderConfigRevisionState
import com.streamvault.data.local.DatabaseTransactionRunner
import com.streamvault.data.local.dao.*
import com.streamvault.data.local.entity.ProviderEntity
import com.streamvault.data.local.entity.ProviderConfigEntity
import com.streamvault.data.local.entity.ProviderAccountRuntimeEntity
import com.streamvault.data.local.entity.StalkerDiscoveryStageEntity
import com.streamvault.data.local.entity.StalkerPortalStateEntity
import com.streamvault.data.local.entity.StalkerIndexJobEntity
import com.streamvault.data.manager.recording.RecordingAlarmScheduler
import com.streamvault.data.manager.reminder.ProgramReminderAlarmScheduler
import com.streamvault.data.mapper.*
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.data.provider.ProviderConfigurationCodec
import com.streamvault.data.provider.ProviderConfigRevisionCodec
import com.streamvault.data.provider.ProviderCapabilityResolver
import com.streamvault.data.provider.StalkerClientOptions
import com.streamvault.data.provider.TypedProviderClientFactory
import com.streamvault.data.provider.toAccountRuntime
import com.streamvault.data.provider.toTypedConfiguration
import com.streamvault.data.provider.toLegacyProvider
import com.streamvault.data.provider.toGenerationValidLearning
import com.streamvault.data.provider.guidePolicy
import com.streamvault.data.provider.logoPolicy
import com.streamvault.data.remote.jellyfin.JellyfinProvider
import com.streamvault.data.remote.stalker.StalkerApiService
import com.streamvault.data.remote.stalker.StalkerPlaybackMode
import com.streamvault.data.remote.stalker.StalkerProvider
import com.streamvault.data.remote.stalker.StalkerPortalStateStore
import com.streamvault.data.remote.stalker.StalkerCompatibilityRegistry
import com.streamvault.data.remote.stalker.StalkerApiError
import com.streamvault.data.remote.xtream.XtreamProvider
import com.streamvault.data.security.CredentialCrypto
import com.streamvault.data.security.CredentialDecryptionException
import com.streamvault.data.sync.ProviderSyncCommands
import com.streamvault.data.sync.ProviderSyncWorker
import com.streamvault.data.sync.ProviderWorkflowDisposition
import com.streamvault.data.sync.ProviderWorkflowOutcome
import com.streamvault.data.sync.ProviderWorkflowRunner
import com.streamvault.data.sync.ProviderWorkflowCommitFence
import com.streamvault.data.sync.hasUsableLiveCatalogForActivation
import com.streamvault.data.local.entity.ProviderWorkflowPhase
import com.streamvault.data.local.entity.ProviderWorkflowReason
import com.streamvault.data.util.ProviderInputSanitizer
import com.streamvault.data.util.UrlSecurityPolicy
import com.streamvault.domain.manager.ProviderCredentials
import com.streamvault.domain.model.*
import com.streamvault.domain.model.LegacyProvider as Provider
import com.streamvault.domain.repository.LiveStreamProgramRequest
import com.streamvault.domain.repository.ProviderDeleteOutcome
import com.streamvault.domain.repository.ProviderDeleteProgress
import com.streamvault.domain.repository.ProviderRepository
import com.streamvault.domain.repository.ProviderSetupRequest
import com.streamvault.domain.repository.SyncMetadataRepository
import com.streamvault.domain.provider.CapabilityResolution
import com.streamvault.domain.provider.CatchUpRequest
import com.streamvault.domain.provider.GuideRequest
import com.streamvault.domain.provider.GuideSource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.URI
import java.util.UUID
import java.util.logging.Logger
import javax.inject.Inject
import javax.inject.Singleton
import dagger.hilt.android.qualifiers.ApplicationContext

@Singleton
class ProviderRepositoryImpl @Inject constructor(
    private val providerDao: ProviderDao,
    private val providerSnapshotDao: ProviderSnapshotDao,
    private val categoryDao: CategoryDao,
    private val channelDao: ChannelDao,
    private val movieDao: MovieDao,
    private val seriesDao: SeriesDao,
    private val programDao: ProgramDao,
    private val recordingRunDao: RecordingRunDao,
    private val programReminderDao: ProgramReminderDao,
    private val stalkerApiService: StalkerApiService,
    private val credentialCrypto: CredentialCrypto,
    private val providerConfigurationCodec: ProviderConfigurationCodec,
    private val preferencesRepository: PreferencesRepository,
    private val syncManager: ProviderSyncCommands,
    private val syncMetadataRepository: SyncMetadataRepository,
    private val transactionRunner: DatabaseTransactionRunner,
    private val recordingAlarmScheduler: RecordingAlarmScheduler,
    private val programReminderAlarmScheduler: ProgramReminderAlarmScheduler,
    private val jellyfinProvider: JellyfinProvider,
    private val stalkerIndexJobDao: StalkerIndexJobDao,
    private val stalkerPortalStateStore: StalkerPortalStateStore,
    private val movieCategoryHydrationDao: MovieCategoryHydrationDao,
    private val seriesCategoryHydrationDao: SeriesCategoryHydrationDao,
    private val stalkerDiscoveryStageDao: StalkerDiscoveryStageDao? = null,
    private val providerDeletionCleanupDao: ProviderDeletionCleanupDao,
    private val providerDeletionCleanupEnqueuer: ProviderDeletionCleanupEnqueuer,
    private val providerConfigRevisionDao: ProviderConfigRevisionDao,
    private val gson: Gson,
    private val providerWorkflowRunner: ProviderWorkflowRunner,
    private val providerWorkflowCommitFence: ProviderWorkflowCommitFence,
    private val providerCapabilityResolver: ProviderCapabilityResolver,
    private val typedProviderClientFactory: TypedProviderClientFactory,
    @param:ApplicationContext private val appContext: Context
) : ProviderRepository {
    private companion object {
        const val XTREAM_GUIDE_BATCH_CONCURRENCY = 4
        const val BACKGROUND_EPG_START_DELAY_MS = 15_000L
        // Row-equivalent weights for non-row delete steps so the progress bar still moves
        // meaningfully on providers with tiny (or empty) catalogs.
        const val ALARM_STEP_WEIGHT = 5
        const val PROVIDER_ROW_STEP_WEIGHT = 200
        const val FINALIZE_STEP_WEIGHT = 200
        const val STALKER_DISCOVERY_STAGE_TTL_MILLIS = 60L * 60L * 1000L
        const val MANUAL_REFRESH_PRIORITY = 50
        val logger: Logger = Logger.getLogger(ProviderRepositoryImpl::class.java.name)
        val STALKER_URL_SCHEME_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")
    }

    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private data class PendingProviderEdit(
        val revision: Long,
        val candidate: Provider,
        val secureCandidate: ProviderEntity
    )

    private data class InitialOnboardingTarget(
        val providerData: Provider,
        val providerForSync: Provider,
        val pendingEdit: PendingProviderEdit? = null
    )

    override fun getProviders(): Flow<List<Provider>> = combine(
        providerDao.getAll(),
        providerSnapshotDao.observeConfigs(),
        providerSnapshotDao.observeRuntimes(),
        providerSnapshotDao.observeStalkerPortalStates()
    ) { identities, configs, runtimes, portalStates ->
        val configsByProvider = configs.associateBy { it.providerId }
        val runtimesByProvider = runtimes.associateBy { it.providerId }
        val portalStatesByProvider = portalStates.associateBy { it.providerId }
        identities.map { identity ->
            val stored = configsByProvider[identity.id]
            if (stored == null) {
                identity.toPublicDomain()
            } else {
                val stableIdentity = StableProvider(
                    id = identity.id,
                    name = identity.name,
                    type = identity.type,
                    isActive = identity.isActive,
                    status = identity.status,
                    lastSyncedAt = identity.lastSyncedAt,
                    createdAt = identity.createdAt
                )
                val runtime = runtimesByProvider[identity.id]?.toDomainRuntime()
                    ?: ProviderAccountRuntime()
                val learning = if (identity.type == ProviderType.STALKER_PORTAL) {
                    portalStatesByProvider[identity.id]?.toGenerationValidLearning(
                        gson,
                        stored.configurationGeneration
                    )
                } else {
                    null
                }
                ProviderSnapshot(
                    provider = stableIdentity,
                    configuration = providerConfigurationCodec.decode(
                        stored.type,
                        stored.encryptedConfigJson
                    ),
                    configurationGeneration = stored.configurationGeneration,
                    accountRuntime = runtime,
                    stalkerLearning = learning
                ).toLegacyProvider().redactedCredentials()
            }
        }
    }

    override fun getActiveProvider(): Flow<Provider?> =
        getProviders().map { providers -> providers.firstOrNull { it.isActive } }

    override suspend fun getProvider(id: Long): Provider? =
        loadLegacyProvider(id)?.redactedCredentials()

    override suspend fun addProvider(provider: Provider): Result<Long> = try {
        val id = transactionRunner.inTransaction {
            val insertedId = providerDao.insert(provider.toSecureEntity())
            persistTypedSnapshot(insertedId, provider, currentGeneration = null)
            insertedId
        }
        Result.success(id)
    } catch (e: Exception) {
        Result.error("Failed to add provider: ${e.message}", e)
    }

    override suspend fun updateProvider(provider: Provider): Result<Unit> = try {
        transactionRunner.inTransaction {
            val current = providerSnapshotDao.getConfig(provider.id)
            val currentConfiguration = current?.let { stored ->
                providerConfigurationCodec.decode(stored.type, stored.encryptedConfigJson)
            }
            val persistedProvider = provider.withPersistedCredential(currentConfiguration)
            providerDao.update(persistedProvider.toSecureEntity())
            persistTypedSnapshot(provider.id, persistedProvider, current?.configurationGeneration)
        }
        if (provider.type == ProviderType.STALKER_PORTAL) {
            stalkerPortalStateStore.invalidate(provider.id)
        }
        if (provider.type == ProviderType.STALKER_PORTAL &&
            provider.stalkerCatalogMode == StalkerCatalogMode.ON_DEMAND
        ) {
            stalkerIndexJobDao.disableForProvider(provider.id, System.currentTimeMillis())
            syncManager.cancelStalkerIndexSync(provider.id)
        }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.error("Failed to update provider: ${e.message}", e)
    }

    override suspend fun buildStalkerSearchIndexOnce(providerId: Long): Result<Unit> {
        return try {
            val provider = providerDao.getById(providerId)
                ?: return Result.error("Provider not found")
            if (provider.type != ProviderType.STALKER_PORTAL) {
                return Result.error("Complete search indexing is only available for Stalker providers")
            }
            val now = System.currentTimeMillis()
            transactionRunner.inTransaction {
                // A manual rebuild is an explicit retry of every category, including rows
                // previously marked COMPLETE, TRUNCATED, or terminally failed. Keep the
                // current searchable content visible while resetting only crawl progress.
                movieCategoryHydrationDao.deleteByProvider(providerId)
                seriesCategoryHydrationDao.deleteByProvider(providerId)
                listOf(ContentType.MOVIE, ContentType.SERIES).forEach { section ->
                    stalkerIndexJobDao.upsert(
                        StalkerIndexJobEntity(
                            providerId = providerId,
                            section = section,
                            state = StalkerIndexState.QUEUED,
                            updatedAt = now
                        )
                    )
                }
            }
            syncManager.scheduleStalkerIndexSync(providerId, force = false)
            Result.success(Unit)
        } catch (error: Exception) {
            Result.error("Failed to queue the complete Stalker search index", error)
        }
    }

    override suspend fun getAllProviderCredentials(): List<ProviderCredentials> {
        return providerDao.getAllSync()
            .mapNotNull { entity -> loadLegacyProvider(entity.id) }
            .map { provider ->
                ProviderCredentials(
                    serverUrl = provider.serverUrl,
                    username = provider.username,
                    password = provider.password,
                )
            }
            .filter { it.username.isNotBlank() && it.password.isNotBlank() }
    }

    override suspend fun updateProviderPassword(
        serverUrl: String,
        username: String,
        cleartextPassword: String,
    ): Boolean {
        var matchedSnapshot: ProviderSnapshot? = null
        for (identity in providerDao.getAllSync()) {
            val candidate = providerCapabilityResolver.snapshot(identity.id) ?: continue
            val matches =
                when (val config = candidate.configuration) {
                    is XtreamConfig -> config.serverUrl == serverUrl && config.username == username
                    is StalkerConfig -> config.portalUrl == serverUrl && config.username == username
                    is JellyfinConfig -> config.serverUrl == serverUrl && config.username == username
                    is M3uConfig -> false
                }
            if (matches) {
                matchedSnapshot = candidate
                break
            }
        }
        val snapshot = matchedSnapshot ?: return false
        val updatedConfiguration = when (val config = snapshot.configuration) {
            is XtreamConfig -> config.copy(password = cleartextPassword)
            is StalkerConfig -> config.copy(password = cleartextPassword)
            is JellyfinConfig -> config.copy(credential = cleartextPassword)
            is M3uConfig -> return false
        }
        val committed = providerSnapshotDao.commitConfiguration(
            ProviderConfigEntity(
                providerId = snapshot.provider.id,
                type = updatedConfiguration.type,
                schemaVersion = updatedConfiguration.schemaVersion,
                configurationGeneration = snapshot.configurationGeneration + 1L,
                identityKey = providerConfigurationCodec.identityKey(updatedConfiguration),
                encryptedConfigJson = providerConfigurationCodec.encode(updatedConfiguration),
                guideSourcePolicy = updatedConfiguration.guidePolicy(),
                channelLogoSourcePolicy = updatedConfiguration.logoPolicy(),
                updatedAt = System.currentTimeMillis()
            )
        )
        if (committed && snapshot.provider.type == ProviderType.STALKER_PORTAL) {
            stalkerPortalStateStore.invalidate(snapshot.provider.id)
        }
        return committed
    }

    override suspend fun deleteProvider(
        id: Long,
        onProgress: ((ProviderDeleteProgress) -> Unit)?
    ): Result<ProviderDeleteOutcome> = try {
        val estimatedRecordingRunIds = recordingRunDao.getIdsByProvider(id)
        val estimatedReminderIds = programReminderDao.getIdsByProvider(id)

        // Weight progress by the real row counts of the large child tables so the bar
        // advances proportionally to the work being done instead of in two big jumps.
        val programCount = programDao.countByProvider(id)
        val channelCount = channelDao.countByProvider(id)
        val movieCount = movieDao.countByProvider(id)
        val seriesCount = seriesDao.countByProvider(id)

        val totalWeight = (
                programCount + channelCount + movieCount + seriesCount +
                (estimatedRecordingRunIds.size + estimatedReminderIds.size) * ALARM_STEP_WEIGHT +
                PROVIDER_ROW_STEP_WEIGHT + FINALIZE_STEP_WEIGHT
            ).coerceAtLeast(1)
        var completedWeight = 0
        var pendingCleanupActions = 0

        fun reportProgress(message: String) {
            onProgress?.invoke(
                ProviderDeleteProgress(
                    message = message,
                    fraction = (completedWeight.toFloat() / totalWeight.toFloat()).coerceIn(0f, 1f)
                )
            )
        }

        reportProgress("Preparing to remove provider...")
        transactionRunner.inTransaction {
            // Re-read alarm identities under the same Room transaction that removes
            // their rows. This closes the window where a newly committed alarm could
            // be cascade-deleted without receiving a durable cancellation tombstone.
            val recordingRunIds = recordingRunDao.getIdsByProvider(id)
            val reminderIds = programReminderDao.getIdsByProvider(id)
            providerDeletionCleanupDao.insertAll(
                recordingRunIds.map { ProviderDeletionCleanupEntity(id = 0, providerId = id, action = ProviderDeletionCleanupWorker.RECORDING_ALARM, targetId = it) } +
                    reminderIds.map { ProviderDeletionCleanupEntity(id = 0, providerId = id, action = ProviderDeletionCleanupWorker.REMINDER_ALARM, targetId = it.toString()) } +
                    ProviderDeletionCleanupEntity(id = 0, providerId = id, action = ProviderDeletionCleanupWorker.SYNC_RUNTIME)
            )
            // ProgramEntity still has no provider FK, so it requires explicit cleanup.
            if (programCount > 0) reportProgress("Removing $programCount guide entries...")
            programDao.deleteByProvider(id)
            completedWeight += programCount

            if (channelCount > 0) reportProgress("Removing $channelCount channels...")
            channelDao.deleteByProvider(id)
            completedWeight += channelCount

            if (movieCount > 0) reportProgress("Removing $movieCount movies...")
            movieDao.deleteByProvider(id)
            completedWeight += movieCount

            if (seriesCount > 0) reportProgress("Removing $seriesCount series...")
            seriesDao.deleteByProvider(id)
            completedWeight += seriesCount

            reportProgress("Removing provider record...")
            providerDao.delete(id)
            completedWeight += PROVIDER_ROW_STEP_WEIGHT
            pendingCleanupActions = providerDeletionCleanupDao.countByProvider(id)
        }
        stalkerApiService.invalidateSessionScopes(id)
        reportProgress("Provider library removed.")
        reportProgress("Finalizing provider cleanup...")
        val reconciliationRequested = runCatching {
            providerDeletionCleanupEnqueuer.enqueue()
            true
        }.getOrElse {
            logger.warning("Provider cleanup is pending but WorkManager enqueue failed: ${it.message}")
            false
        }
        completedWeight = totalWeight
        reportProgress(
            if (reconciliationRequested) {
                "Provider library deleted; final cleanup continues."
            } else {
                "Provider library deleted; cleanup is pending and will retry on startup."
            }
        )
        Result.success(
            ProviderDeleteOutcome(
                providerId = id,
                pendingCleanupActions = pendingCleanupActions,
                reconciliationRequested = reconciliationRequested
            )
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (e: Exception) {
        Result.error("Failed to delete provider: ${e.message}", e)
    }

    override suspend fun setActiveProvider(id: Long): Result<Unit> {
        return try {
            val provider = providerDao.getById(id)
                ?: return Result.error("Provider not found")
            if (!hasUsableLiveCatalogForActivation(id, provider.type, channelDao, categoryDao, syncMetadataRepository)) {
                syncManager.scheduleProviderSyncResume(id)
                return Result.error("Provider is saved but no content has been committed yet. Sync will resume in background.")
            }
            providerDao.setActive(id)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.error("Failed to set active provider: ${e.message}", e)
        }
    }

    override suspend fun setupProvider(
        request: ProviderSetupRequest,
        onProgress: ((String) -> Unit)?,
        onCode: ((String) -> Unit)?
    ): Result<Provider> = when (request) {
        is ProviderSetupRequest.Configured -> when (val config = request.configuration) {
            is XtreamConfig -> loginXtream(
                serverUrl = config.serverUrl,
                username = config.username,
                password = config.password,
                name = request.name,
                httpUserAgent = config.httpUserAgent,
                httpHeaders = config.httpHeaders,
                xtreamFastSyncEnabled = config.fastSyncEnabled,
                epgSyncMode = config.epgSyncMode,
                xtreamLiveSyncMode = config.liveSyncMode,
                guideSourcePolicy = config.guideSourcePolicy,
                channelLogoSourcePolicy = config.channelLogoSourcePolicy,
                onProgress = onProgress,
                id = request.existingProviderId
            )
            is M3uConfig -> validateM3u(
                url = config.playlistUrl,
                name = request.name,
                httpUserAgent = config.httpUserAgent,
                httpHeaders = config.httpHeaders,
                epgSyncMode = config.epgSyncMode,
                m3uVodClassificationEnabled = config.vodClassificationEnabled,
                guideSourcePolicy = config.guideSourcePolicy,
                channelLogoSourcePolicy = config.channelLogoSourcePolicy,
                onProgress = onProgress,
                id = request.existingProviderId
            )
            is StalkerConfig -> loginStalker(
                portalUrl = config.portalUrl,
                macAddress = config.device.macAddress,
                name = request.name,
                authMode = config.authMode,
                username = config.username,
                password = config.password,
                httpUserAgent = config.httpUserAgent,
                httpHeaders = config.httpHeaders,
                deviceProfile = config.device.deviceProfile,
                timezone = config.device.timezone,
                locale = config.device.locale,
                serialNumber = config.device.serialNumber,
                deviceId = config.device.deviceId,
                deviceId2 = config.device.deviceId2,
                signature = config.device.signature,
                stalkerAdvancedOptionsJson = config.advancedOptionsJson,
                protocolPreference = config.protocolPreference,
                transportGrant = config.transportGrant,
                saveWithoutVerification = request.saveWithoutVerification,
                repairConnection = request.repairConnection,
                requestedProfileId = config.requestedProfileId,
                epgSyncMode = config.epgSyncMode,
                catalogMode = config.catalogMode,
                guideSourcePolicy = config.guideSourcePolicy,
                channelLogoSourcePolicy = config.channelLogoSourcePolicy,
                onProgress = onProgress,
                id = request.existingProviderId
            )
            is JellyfinConfig -> loginJellyfin(
                serverUrl = config.serverUrl,
                username = config.username,
                password = config.credential,
                name = request.name,
                onProgress = onProgress,
                id = request.existingProviderId
            )
        }
        is ProviderSetupRequest.JellyfinQuickConnect -> loginJellyfinQuickConnect(
            serverUrl = request.serverUrl,
            name = request.name,
            onCode = onCode,
            onProgress = onProgress,
            id = request.existingProviderId
        )
    }

    internal suspend fun loginXtream(
        serverUrl: String,
        username: String,
        password: String,
        name: String,
        httpUserAgent: String = "",
        httpHeaders: String = "",
        xtreamFastSyncEnabled: Boolean,
        epgSyncMode: ProviderEpgSyncMode = ProviderEpgSyncMode.BACKGROUND,
        xtreamLiveSyncMode: com.streamvault.domain.model.ProviderXtreamLiveSyncMode = ProviderXtreamLiveSyncMode.AUTO,
        guideSourcePolicy: GuideSourcePolicy = GuideSourcePolicy.AUTO,
        channelLogoSourcePolicy: ChannelLogoSourcePolicy = ChannelLogoSourcePolicy.SUPPLIER_PREFERRED,
        onProgress: ((String) -> Unit)? = null,
        id: Long? = null
    ): Result<Provider> {
        val normalizedServerUrl = ProviderInputSanitizer.normalizeUrl(serverUrl)
        val normalizedUsername = ProviderInputSanitizer.normalizeUsername(username)
        val normalizedName = ProviderInputSanitizer.normalizeProviderName(name)
        val resolvedServerUrl = ProviderInputSanitizer.resolveUrlProtocol(normalizedServerUrl)

        ProviderInputSanitizer.validateUrl(resolvedServerUrl)?.let { message ->
            return Result.error(message)
        }
        UrlSecurityPolicy.validateXtreamServerUrl(resolvedServerUrl)?.let { message ->
            return Result.error(message)
        }
        onProgress?.invoke("Authenticating...")
        val existingProvider = if (id != null) {
            // Edit path: check that the new normalized identity does not collide with a
            // different provider before we commit the update.
            val collision = findProviderByConfigurationIdentity(
                XtreamConfig(resolvedServerUrl, normalizedUsername, "")
            )
            if (collision != null && collision.id != id) {
                return Result.error("A provider with this server URL and username already exists.")
            }
            loadLegacyProvider(id)
        } else {
            findProviderByConfigurationIdentity(XtreamConfig(resolvedServerUrl, normalizedUsername, ""))
                ?.let { loadLegacyProvider(it.id) }
        }
        val effectivePassword = try {
            password.takeIf { it.isNotBlank() }
                ?: existingProvider?.password
                ?: ""
        } catch (e: CredentialDecryptionException) {
            return Result.error(e.message ?: CredentialDecryptionException.MESSAGE, e)
        }
        val candidateGeneration = existingProvider
            ?.let { providerCapabilityResolver.snapshot(it.id)?.configurationGeneration }
            ?.plus(1L)
            ?: 1L
        val provider = when (val resolution = typedProviderClientFactory.xtream(
            ProviderSnapshot(
                provider = StableProvider(
                    id = 0L,
                    name = normalizedName.ifBlank { "Xtream" },
                    type = ProviderType.XTREAM_CODES
                ),
                configuration = XtreamConfig(
                    serverUrl = resolvedServerUrl,
                    username = normalizedUsername,
                    password = effectivePassword,
                    httpUserAgent = httpUserAgent,
                    httpHeaders = httpHeaders,
                    epgSyncMode = epgSyncMode,
                    guideSourcePolicy = guideSourcePolicy,
                    channelLogoSourcePolicy = channelLogoSourcePolicy,
                    fastSyncEnabled = xtreamFastSyncEnabled,
                    liveSyncMode = xtreamLiveSyncMode
                ),
                configurationGeneration = candidateGeneration
            )
        )) {
            is CapabilityResolution.Available -> resolution.capability
            is CapabilityResolution.ConfigurationError -> return Result.error(resolution.reason)
            is CapabilityResolution.Restricted -> return Result.error(resolution.reason)
            is CapabilityResolution.Unsupported -> return Result.error(resolution.reason)
        }
        return when (val authResult = provider.authenticate()) {
            is Result.Success -> {
                onProgress?.invoke("Profile accepted; catalog validated")
                val onboardingTarget = if (existingProvider != null) {
                    onProgress?.invoke("Updating existing provider...")
                    val updated = authResult.data.copy(
                        id = existingProvider.id,
                        name = normalizedName.ifBlank { existingProvider.name },
                        serverUrl = resolvedServerUrl,
                        username = normalizedUsername,
                        password = effectivePassword,
                        httpUserAgent = httpUserAgent,
                        httpHeaders = httpHeaders,
                        epgSyncMode = epgSyncMode,
                        xtreamLiveSyncMode = xtreamLiveSyncMode,
                        guideSourcePolicy = guideSourcePolicy,
                        channelLogoSourcePolicy = channelLogoSourcePolicy,
                        xtreamFastSyncEnabled = false,
                        isActive = false,
                        status = ProviderStatus.PARTIAL,
                        lastSyncedAt = 0,
                        createdAt = existingProvider.createdAt
                    )
                    InitialOnboardingTarget(
                        providerData = updated.copy(password = ""),
                        providerForSync = updated,
                        pendingEdit = stageProviderEdit(updated)
                    )
                } else {
                    val newData = authResult.data.copy(
                        name = normalizedName.ifBlank { authResult.data.name },
                        httpUserAgent = httpUserAgent,
                        httpHeaders = httpHeaders,
                        epgSyncMode = epgSyncMode,
                        xtreamLiveSyncMode = xtreamLiveSyncMode,
                        guideSourcePolicy = guideSourcePolicy,
                        channelLogoSourcePolicy = channelLogoSourcePolicy,
                        xtreamFastSyncEnabled = false,
                        isActive = false,
                        status = ProviderStatus.PARTIAL
                    )
                    val newId = insertConfiguredProvider(newData)
                    InitialOnboardingTarget(
                        providerData = newData.copy(id = newId, password = ""),
                        providerForSync = newData.copy(id = newId)
                    )
                }

                if (onboardingTarget.providerData.stalkerCatalogMode == StalkerCatalogMode.ON_DEMAND) {
                    stalkerIndexJobDao.disableForProvider(onboardingTarget.providerData.id, System.currentTimeMillis())
                    syncManager.cancelStalkerIndexSync(onboardingTarget.providerData.id)
                }

                handleInitialOnboardingSync(
                    providerData = onboardingTarget.providerData,
                    syncResult = syncInitialOnboarding(
                        onboardingTarget,
                        onProgress,
                        trackInitialLiveOnboarding = true
                    ),
                    syncFailurePrefix = "Provider login succeeded, but initial sync failed. The provider was saved and can be retried from Settings",
                    pendingEdit = onboardingTarget.pendingEdit
                )
            }
            is Result.Error -> Result.error(authResult.message, authResult.exception)
            is Result.Loading -> Result.error("Unexpected loading state")
        }
    }

    internal suspend fun validateM3u(
        url: String,
        name: String,
        httpUserAgent: String = "",
        httpHeaders: String = "",
        epgSyncMode: ProviderEpgSyncMode = ProviderEpgSyncMode.BACKGROUND,
        m3uVodClassificationEnabled: Boolean = false,
        guideSourcePolicy: GuideSourcePolicy = GuideSourcePolicy.AUTO,
        channelLogoSourcePolicy: ChannelLogoSourcePolicy = ChannelLogoSourcePolicy.SUPPLIER_PREFERRED,
        onProgress: ((String) -> Unit)? = null,
        id: Long? = null
    ): Result<Provider> = try {
        val normalizedUrl = ProviderInputSanitizer.resolveUrlProtocol(
            ProviderInputSanitizer.normalizeUrl(url)
        )
        val normalizedName = ProviderInputSanitizer.normalizeProviderName(name)

        ProviderInputSanitizer.validateUrl(normalizedUrl)?.let { message ->
            return Result.error(message)
        }
        UrlSecurityPolicy.validatePlaylistSourceUrl(normalizedUrl)?.let { message ->
            return Result.error(message)
        }
        onProgress?.invoke("Validating playlist URL...")
        val providerName = normalizedName.ifBlank {
            normalizedUrl.substringAfterLast("/").substringBefore("?").ifBlank { "M3U Playlist" }
        }

        val existingProvider = if (id != null) {
            // Edit path: check that the new normalized URL does not collide with a different
            // provider before we commit the update.
            val collision = findProviderByConfigurationIdentity(M3uConfig(normalizedUrl))
            if (collision != null && collision.id != id) {
                return Result.error("A playlist provider with this URL already exists.")
            }
            loadLegacyProvider(id)
        } else {
            findProviderByConfigurationIdentity(M3uConfig(normalizedUrl))
                ?.let { loadLegacyProvider(it.id) }
        }

        val onboardingTarget = if (existingProvider != null) {
            val updated = existingProvider.copy(
                name = if (normalizedName.isNotBlank()) normalizedName else existingProvider.name,
                serverUrl = normalizedUrl,
                m3uUrl = normalizedUrl,
                httpUserAgent = httpUserAgent,
                httpHeaders = httpHeaders,
                epgSyncMode = epgSyncMode,
                m3uVodClassificationEnabled = m3uVodClassificationEnabled,
                guideSourcePolicy = guideSourcePolicy,
                channelLogoSourcePolicy = channelLogoSourcePolicy,
                isActive = false,
                status = ProviderStatus.PARTIAL,
                lastSyncedAt = 0
            )
            InitialOnboardingTarget(
                providerData = updated.copy(password = ""),
                providerForSync = updated,
                pendingEdit = stageProviderEdit(updated)
            )
        } else {
            val provider = Provider(
                name = providerName,
                type = ProviderType.M3U,
                serverUrl = normalizedUrl,
                m3uUrl = normalizedUrl,
                httpUserAgent = httpUserAgent,
                httpHeaders = httpHeaders,
                epgSyncMode = epgSyncMode,
                m3uVodClassificationEnabled = m3uVodClassificationEnabled,
                guideSourcePolicy = guideSourcePolicy,
                channelLogoSourcePolicy = channelLogoSourcePolicy,
                isActive = false,
                status = ProviderStatus.PARTIAL
            )
            val newId = insertConfiguredProvider(provider)
            InitialOnboardingTarget(
                providerData = provider.copy(id = newId, password = ""),
                providerForSync = provider.copy(id = newId)
            )
        }

        handleInitialOnboardingSync(
            providerData = onboardingTarget.providerData,
            syncResult = syncInitialOnboarding(onboardingTarget, onProgress),
            syncFailurePrefix = "Playlist saved, but initial sync failed. The provider was saved and can be retried from Settings",
            pendingEdit = onboardingTarget.pendingEdit
        )
    } catch (e: Exception) {
        Result.error("Failed to add M3U provider: ${e.message}", e)
    }

    internal suspend fun loginJellyfin(
        serverUrl: String,
        username: String,
        password: String,
        name: String,
        onProgress: ((String) -> Unit)? = null,
        id: Long? = null
    ): Result<Provider> {
        return try {
            val normalizedServerUrl = ProviderInputSanitizer.resolveUrlProtocol(
                ProviderInputSanitizer.normalizeUrl(serverUrl)
            )
            val normalizedUsername = ProviderInputSanitizer.normalizeUsername(username)
            val normalizedPassword = ProviderInputSanitizer.normalizePassword(password)
            val normalizedName = ProviderInputSanitizer.normalizeProviderName(name)
            ProviderInputSanitizer.validateUrl(normalizedServerUrl)?.let { return Result.error(it) }
            if (normalizedUsername.isBlank()) return Result.error("Please enter Jellyfin username")
            val providerName = normalizedName.ifBlank {
                normalizedServerUrl.substringAfter("//").substringBefore("/").ifBlank { "Jellyfin" }
            }
            val existingProviderEntity = if (id != null) {
                val collision = findProviderByConfigurationIdentity(
                    JellyfinConfig(normalizedServerUrl, normalizedUsername, "")
                )
                if (collision != null && collision.id != id) return Result.error("A Jellyfin provider with this server URL and username already exists.")
                loadLegacyProvider(id)
            } else {
                findProviderByConfigurationIdentity(
                    JellyfinConfig(normalizedServerUrl, normalizedUsername, "")
                )?.let { loadLegacyProvider(it.id) }
            }
            val existingProvider = existingProviderEntity
            val authResult = when {
                normalizedPassword.isNotBlank() -> {
                    onProgress?.invoke("Signing in to Jellyfin...")
                    when (val loginResult = jellyfinProvider.authenticate(normalizedServerUrl, normalizedUsername, normalizedPassword)) {
                        is Result.Success -> loginResult.data
                        is Result.Error -> return Result.error(loginResult.message, loginResult.exception)
                        is Result.Loading -> return Result.error("Unexpected loading state")
                    }
                }
                existingProvider != null -> existingProvider.password
                else -> return Result.error("Please enter Jellyfin password")
            }
            val onboardingTarget = if (existingProvider != null) {
                val updated = existingProvider.copy(
                    name = providerName.ifBlank { existingProvider.name }, type = ProviderType.JELLYFIN,
                    serverUrl = normalizedServerUrl, username = normalizedUsername, password = authResult,
                    m3uUrl = "", epgUrl = "", httpUserAgent = "", httpHeaders = "",
                    isActive = false, status = ProviderStatus.PARTIAL, lastSyncedAt = 0
                )
                InitialOnboardingTarget(
                    providerData = updated.copy(password = ""),
                    providerForSync = updated,
                    pendingEdit = stageProviderEdit(updated)
                )
            } else {
                val provider = Provider(name = providerName, type = ProviderType.JELLYFIN,
                    serverUrl = normalizedServerUrl, username = normalizedUsername, password = authResult,
                    isActive = false, status = ProviderStatus.PARTIAL)
                val newId = insertConfiguredProvider(provider)
                InitialOnboardingTarget(
                    providerData = provider.copy(id = newId, password = ""),
                    providerForSync = provider.copy(id = newId)
                )
            }
            handleInitialOnboardingSync(
                providerData = onboardingTarget.providerData,
                syncResult = syncInitialOnboarding(onboardingTarget, onProgress),
                syncFailurePrefix = "Jellyfin provider saved, but initial sync failed. The provider was saved and can be retried from Settings",
                pendingEdit = onboardingTarget.pendingEdit
            )
        } catch (e: Exception) {
            Result.error("Failed to add Jellyfin provider: ${e.message}", e)
        }
    }

    internal suspend fun loginJellyfinQuickConnect(
        serverUrl: String,
        name: String,
        onCode: ((String) -> Unit)? = null,
        onProgress: ((String) -> Unit)? = null,
        id: Long? = null
    ): Result<Provider> {
        return try {
            val normalizedServerUrl = ProviderInputSanitizer.resolveUrlProtocol(
                ProviderInputSanitizer.normalizeUrl(serverUrl)
            )
            val normalizedName = ProviderInputSanitizer.normalizeProviderName(name)
            ProviderInputSanitizer.validateUrl(normalizedServerUrl)?.let { return Result.error(it) }
            val providerName = normalizedName.ifBlank {
                normalizedServerUrl.substringAfter("//").substringBefore("/").ifBlank { "Jellyfin" }
            }
            val existingProvider = id?.let { loadLegacyProvider(it) }
            onProgress?.invoke("Requesting Quick Connect code...")
            val quickConnect = when (val quickConnectResult = jellyfinProvider.authenticateQuickConnect(
                serverUrl = normalizedServerUrl, onCode = onCode, onProgress = onProgress
            )) {
                is Result.Success -> quickConnectResult.data
                is Result.Error -> return Result.error(quickConnectResult.message, quickConnectResult.exception)
                is Result.Loading -> return Result.error("Unexpected loading state")
            }
            val onboardingTarget = saveJellyfinProvider(providerName = providerName,
                serverUrl = normalizedServerUrl, username = quickConnect.userName.ifBlank { providerName },
                password = quickConnect.accessToken, existingProvider = existingProvider)
            handleInitialOnboardingSync(
                providerData = onboardingTarget.providerData,
                syncResult = syncInitialOnboarding(onboardingTarget, onProgress),
                syncFailurePrefix = "Jellyfin provider saved, but initial sync failed. The provider was saved and can be retried from Settings",
                pendingEdit = onboardingTarget.pendingEdit
            )
        } catch (e: Exception) {
            Result.error("Failed to add Jellyfin provider: ${e.message}", e)
        }
    }

    private suspend fun saveJellyfinProvider(
        providerName: String, serverUrl: String, username: String, password: String, existingProvider: Provider?
    ): InitialOnboardingTarget {
        return if (existingProvider != null) {
            val updated = existingProvider.copy(
                name = providerName.ifBlank { existingProvider.name }, type = ProviderType.JELLYFIN,
                serverUrl = serverUrl, username = username, password = password,
                m3uUrl = "", epgUrl = "", httpUserAgent = "", httpHeaders = "",
                isActive = false, status = ProviderStatus.PARTIAL, lastSyncedAt = 0
            )
            InitialOnboardingTarget(
                providerData = updated.copy(password = ""),
                providerForSync = updated,
                pendingEdit = stageProviderEdit(updated)
            )
        } else {
            val provider = Provider(name = providerName, type = ProviderType.JELLYFIN,
                serverUrl = serverUrl, username = username, password = password,
                isActive = false, status = ProviderStatus.PARTIAL)
            val newId = insertConfiguredProvider(provider)
            InitialOnboardingTarget(
                providerData = provider.copy(id = newId, password = ""),
                providerForSync = provider.copy(id = newId)
            )
        }
    }


    internal suspend fun loginStalker(
        portalUrl: String,
        macAddress: String,
        name: String,
        authMode: StalkerAuthMode = StalkerAuthMode.AUTO,
        username: String = "",
        password: String = "",
        httpUserAgent: String = "",
        httpHeaders: String = "",
        deviceProfile: String = "",
        timezone: String = "",
        locale: String = "",
        serialNumber: String = "",
        deviceId: String = "",
        deviceId2: String = "",
        signature: String = "",
        stalkerAdvancedOptionsJson: String = "",
        protocolPreference: StalkerProtocolPreference = StalkerProtocolPreference.AUTO,
        transportGrant: StalkerTransportGrant? = null,
        saveWithoutVerification: Boolean = false,
        repairConnection: Boolean = false,
        requestedProfileId: String = StalkerCompatibilityProfileIds.AUTO,
        epgSyncMode: ProviderEpgSyncMode = ProviderEpgSyncMode.BACKGROUND,
        catalogMode: StalkerCatalogMode = StalkerCatalogMode.ON_DEMAND,
        guideSourcePolicy: GuideSourcePolicy = GuideSourcePolicy.AUTO,
        channelLogoSourcePolicy: ChannelLogoSourcePolicy = ChannelLogoSourcePolicy.SUPPLIER_PREFERRED,
        onProgress: ((String) -> Unit)? = null,
        id: Long? = null
    ): Result<Provider> {
        val normalizedPortalUrl = ProviderInputSanitizer.normalizeUrl(portalUrl)
        val normalizedMacAddress = ProviderInputSanitizer.normalizeMacAddress(macAddress)
        val normalizedName = ProviderInputSanitizer.normalizeProviderName(name)
        val normalizedUsername = ProviderInputSanitizer.normalizeUsername(username)
        val inputHasScheme = STALKER_URL_SCHEME_REGEX.containsMatchIn(normalizedPortalUrl)
        val resolvedPortalUrl = when {
            inputHasScheme -> normalizedPortalUrl
            transportGrant?.mode == StalkerTransportMode.USER_ACCEPTED_HTTP ->
                "http://$normalizedPortalUrl"
            else -> "https://$normalizedPortalUrl"
        }
        val normalizedDeviceProfile = ProviderInputSanitizer.normalizeDeviceProfile(deviceProfile)
        val normalizedTimezone = ProviderInputSanitizer.normalizeTimezone(timezone)
        val normalizedLocale = ProviderInputSanitizer.normalizeLocale(locale)
        val normalizedSerialNumber = ProviderInputSanitizer.normalizeStalkerSerial(serialNumber)
        val normalizedDeviceId = ProviderInputSanitizer.normalizeStalkerDeviceId(deviceId)
        val normalizedDeviceId2 = ProviderInputSanitizer.normalizeStalkerDeviceId(deviceId2)
        val normalizedSignature = ProviderInputSanitizer.normalizeStalkerSignature(signature)
        val normalizedAdvancedOptionsJson = stalkerAdvancedOptionsJson.trim()

        if (protocolPreference == StalkerProtocolPreference.MINISTRA_API_V3) {
            return Result.error(
                "This portal requires the licensed Ministra API-v3 flow. API-v3 activation is isolated from classic MAG and needs provider-issued OAuth/license configuration."
            )
        }
        val requestedCompatibility = StalkerCompatibilityRegistry.find(requestedProfileId)
        if (requestedCompatibility?.identityStrategy ==
            com.streamvault.data.remote.stalker.StalkerIdentityStrategy.MANUAL_FIELDS_REQUIRED &&
            normalizedSerialNumber.isBlank() && normalizedDeviceId.isBlank() && normalizedSignature.isBlank()
        ) {
            return Result.error(
                "${requestedCompatibility.displayName} is experimental and has no verified hardware UID algorithm. Enter captured serial/device identity fields or use Automatic."
            )
        }

        ProviderInputSanitizer.validateUrl(resolvedPortalUrl)?.let { message ->
            return Result.error(message)
        }
        UrlSecurityPolicy.validateStalkerPortalUrl(resolvedPortalUrl)?.let { message ->
            return Result.error(message)
        }
        if (normalizedMacAddress.isNotBlank()) {
            ProviderInputSanitizer.validateMacAddress(normalizedMacAddress)?.let { message ->
                return Result.error(message)
            }
        }

        val requestedProfileLabel = StalkerCompatibilityRegistry.find(requestedProfileId)?.displayName
            ?: "Automatic MAG compatibility"
        onProgress?.invoke("Trying $requestedProfileLabel")
        val existingProvider = if (id != null) {
            // Edit path: check that the new normalized identity does not collide with a
            // different provider before we commit the update.
            val collision = findProviderByConfigurationIdentity(
                StalkerConfig(
                    portalUrl = resolvedPortalUrl,
                    device = StalkerDeviceIdentity(normalizedMacAddress),
                    username = normalizedUsername
                )
            )
            if (collision != null && collision.id != id) {
                return Result.error("A Stalker provider with this portal URL and identity already exists.")
            }
            loadLegacyProvider(id)
        } else {
            findProviderByConfigurationIdentity(
                StalkerConfig(
                    portalUrl = resolvedPortalUrl,
                    device = StalkerDeviceIdentity(normalizedMacAddress),
                    username = normalizedUsername
                )
            )?.let { loadLegacyProvider(it.id) }
        }
        val effectivePassword = try {
            password.takeIf { it.isNotBlank() }
                ?: existingProvider?.password
                ?: ""
        } catch (e: CredentialDecryptionException) {
            return Result.error(e.message ?: CredentialDecryptionException.MESSAGE, e)
        }
        val effectiveTransportGrant = transportGrant
            ?: existingProvider?.toStalkerTransportGrant()
            ?: resolvedPortalUrl.toStalkerOrigin()
                ?.takeIf { it.scheme == "https" }
                ?.let { origin ->
                    StalkerTransportGrant(
                        mode = StalkerTransportMode.VERIFIED_HTTPS,
                        origin = origin,
                        consentedAt = 0L
                    )
                }
        val previousPortalState = existingProvider?.let {
            stalkerPortalStateStore.get(it.id)
        }

        val candidateGeneration = existingProvider
            ?.let { providerCapabilityResolver.snapshot(it.id)?.configurationGeneration }
            ?.plus(1L)
            ?: 1L
        fun discoveryProvider(requireLiveReadiness: Boolean): StalkerProvider {
            val providerId = if (existingProvider == null || repairConnection) 0L else existingProvider.id
            val snapshot = ProviderSnapshot(
                provider = StableProvider(
                    id = providerId,
                    name = normalizedName.ifBlank { "Stalker" },
                    type = ProviderType.STALKER_PORTAL
                ),
                configuration = StalkerConfig(
                    portalUrl = resolvedPortalUrl,
                    device = StalkerDeviceIdentity(
                        macAddress = normalizedMacAddress,
                        deviceProfile = normalizedDeviceProfile,
                        timezone = normalizedTimezone,
                        locale = normalizedLocale,
                        serialNumber = normalizedSerialNumber,
                        deviceId = normalizedDeviceId,
                        deviceId2 = normalizedDeviceId2,
                        signature = normalizedSignature
                    ),
                    username = normalizedUsername,
                    password = effectivePassword,
                    httpUserAgent = httpUserAgent,
                    httpHeaders = httpHeaders,
                    advancedOptionsJson = normalizedAdvancedOptionsJson,
                    authMode = authMode,
                    requestedProfileId = requestedProfileId,
                    protocolPreference = protocolPreference,
                    transportGrant = effectiveTransportGrant,
                    epgSyncMode = epgSyncMode,
                    catalogMode = catalogMode,
                    guideSourcePolicy = guideSourcePolicy,
                    channelLogoSourcePolicy = channelLogoSourcePolicy
                ),
                configurationGeneration = candidateGeneration,
                accountRuntime = ProviderAccountRuntime(
                    catalogLayout = existingProvider?.catalogLayout ?: CatalogLayout.UNKNOWN
                )
            )
            return when (val resolution = typedProviderClientFactory.stalker(
                snapshot,
                StalkerClientOptions(
                    requireCatalogValidation = requireLiveReadiness,
                    onProgress = onProgress
                )
            )) {
                is CapabilityResolution.Available -> resolution.capability
                is CapabilityResolution.ConfigurationError -> throw IllegalArgumentException(resolution.reason)
                is CapabilityResolution.Restricted -> throw IllegalArgumentException(resolution.reason)
                is CapabilityResolution.Unsupported -> throw IllegalArgumentException(resolution.reason)
            }
        }

        var authenticatedProvider = discoveryProvider(requireLiveReadiness = true)
        var authResult = authenticatedProvider.authenticate()
        var acceptedWithoutVerification = false
        val readinessWasInconclusive = (authResult as? Result.Error)?.exception
            ?.let { failure ->
                generateSequence(failure) { it.cause }
                    .filterIsInstance<StalkerApiError.ReadinessInconclusive>()
                    .firstOrNull()
            }
        if (saveWithoutVerification && readinessWasInconclusive != null) {
            onProgress?.invoke("Authentication confirmed; saving with Live TV verification pending")
            // The failed readiness attempt is cached per portal/API identity. Clear that failure
            // before the deliberately weaker authentication retry, otherwise the retry returns
            // the cached inconclusive result without issuing the confirmation request.
            authenticatedProvider.invalidateAuthentication()
            authenticatedProvider = discoveryProvider(requireLiveReadiness = false)
            authResult = authenticatedProvider.authenticate()
            acceptedWithoutVerification = authResult is Result.Success
        }

        return when (val finalAuthResult = authResult) {
            is Result.Success -> {
                val validatedSnapshot = authenticatedProvider.validatedAuthenticationSnapshot()
                val discoverySummary = validatedSnapshot?.toSanitizedDiscoverySummary().orEmpty()
                val capabilitySummary = validatedSnapshot?.second
                    ?.toSanitizedCapabilitySummary()
                    .orEmpty()
                val discoveryId = UUID.randomUUID().toString()
                val stagedAt = System.currentTimeMillis()
                stalkerDiscoveryStageDao?.deleteOlderThan(
                    stagedAt - STALKER_DISCOVERY_STAGE_TTL_MILLIS
                )
                stalkerDiscoveryStageDao?.upsert(
                    StalkerDiscoveryStageEntity(
                        discoveryId = discoveryId,
                        providerId = existingProvider?.id,
                        configurationGeneration = candidateGeneration,
                        sanitizedSummary = discoverySummary,
                        createdAt = stagedAt
                    )
                )
                val onboardingTarget = if (existingProvider != null) {
                    onProgress?.invoke("Updating existing provider...")
                    val updated = finalAuthResult.data.copy(
                        id = existingProvider.id,
                        name = normalizedName.ifBlank { existingProvider.name },
                        serverUrl = resolvedPortalUrl,
                        username = normalizedUsername,
                        password = effectivePassword,
                        httpUserAgent = httpUserAgent,
                        httpHeaders = httpHeaders,
                        stalkerMacAddress = normalizedMacAddress,
                        stalkerDeviceProfile = finalAuthResult.data.stalkerDeviceProfile,
                        stalkerDeviceTimezone = normalizedTimezone,
                        stalkerDeviceLocale = normalizedLocale,
                        stalkerSerialNumber = normalizedSerialNumber,
                        stalkerDeviceId = normalizedDeviceId,
                        stalkerDeviceId2 = normalizedDeviceId2,
                        stalkerSignature = normalizedSignature,
                        stalkerAdvancedOptionsJson = normalizedAdvancedOptionsJson,
                        stalkerProtocolPreference = protocolPreference,
                        stalkerTransportMode = effectiveTransportGrant?.mode
                            ?: StalkerTransportMode.VERIFIED_HTTPS,
                        stalkerTransportOrigin = effectiveTransportGrant?.origin
                            ?.toPersistenceValue()
                            ?: resolvedPortalUrl.toStalkerOrigin()?.toPersistenceValue().orEmpty(),
                        stalkerTlsSpkiSha256 = effectiveTransportGrant?.spkiSha256.orEmpty(),
                        stalkerTransportConsentAt = effectiveTransportGrant?.consentedAt ?: 0L,
                        stalkerConfigurationGeneration = candidateGeneration,
                        stalkerDiscoverySummary = discoverySummary,
                        stalkerCapabilitiesJson = capabilitySummary,
                        stalkerRequestedProfileId = requestedProfileId,
                        epgUrl = existingProvider.epgUrl,
                        epgSyncMode = epgSyncMode,
                        stalkerCatalogMode = catalogMode,
                        guideSourcePolicy = guideSourcePolicy,
                        channelLogoSourcePolicy = channelLogoSourcePolicy,
                        xtreamFastSyncEnabled = false,
                        m3uVodClassificationEnabled = false,
                        isActive = false,
                        status = ProviderStatus.PARTIAL,
                        lastSyncedAt = 0L,
                        createdAt = existingProvider.createdAt
                    )
                    stalkerDiscoveryStageDao?.delete(discoveryId)
                    InitialOnboardingTarget(
                        providerData = updated.copy(password = ""),
                        providerForSync = updated,
                        pendingEdit = stageProviderEdit(updated)
                    )
                } else {
                    val newData = finalAuthResult.data.copy(
                        name = normalizedName.ifBlank { finalAuthResult.data.name },
                        serverUrl = resolvedPortalUrl,
                        username = normalizedUsername,
                        password = effectivePassword,
                        httpUserAgent = httpUserAgent,
                        httpHeaders = httpHeaders,
                        stalkerMacAddress = normalizedMacAddress,
                        stalkerDeviceProfile = finalAuthResult.data.stalkerDeviceProfile,
                        stalkerDeviceTimezone = normalizedTimezone,
                        stalkerDeviceLocale = normalizedLocale,
                        stalkerSerialNumber = normalizedSerialNumber,
                        stalkerDeviceId = normalizedDeviceId,
                        stalkerDeviceId2 = normalizedDeviceId2,
                        stalkerSignature = normalizedSignature,
                        stalkerAdvancedOptionsJson = normalizedAdvancedOptionsJson,
                        stalkerProtocolPreference = protocolPreference,
                        stalkerTransportMode = effectiveTransportGrant?.mode
                            ?: StalkerTransportMode.VERIFIED_HTTPS,
                        stalkerTransportOrigin = effectiveTransportGrant?.origin
                            ?.toPersistenceValue()
                            ?: resolvedPortalUrl.toStalkerOrigin()?.toPersistenceValue().orEmpty(),
                        stalkerTlsSpkiSha256 = effectiveTransportGrant?.spkiSha256.orEmpty(),
                        stalkerTransportConsentAt = effectiveTransportGrant?.consentedAt ?: 0L,
                        stalkerConfigurationGeneration = 1L,
                        stalkerDiscoverySummary = discoverySummary,
                        stalkerCapabilitiesJson = capabilitySummary,
                        stalkerRequestedProfileId = requestedProfileId,
                        epgSyncMode = epgSyncMode,
                        stalkerCatalogMode = catalogMode,
                        guideSourcePolicy = guideSourcePolicy,
                        channelLogoSourcePolicy = channelLogoSourcePolicy,
                        xtreamFastSyncEnabled = false,
                        m3uVodClassificationEnabled = false,
                        isActive = false,
                        status = ProviderStatus.PARTIAL
                    )
                    val newId = transactionRunner.inTransaction {
                        val insertedId = insertConfiguredProvider(newData)
                        validatedSnapshot?.let { (session, validatedProfile) ->
                            stalkerPortalStateStore.recordAuthentication(
                                providerId = insertedId,
                                session = session,
                                profile = validatedProfile,
                                configurationGeneration = 1L
                            )
                        }
                        stalkerDiscoveryStageDao?.delete(discoveryId)
                        insertedId
                    }
                    InitialOnboardingTarget(
                        providerData = newData.copy(id = newId, password = ""),
                        providerForSync = newData.copy(id = newId)
                    )
                }

                val onboardingResult = try {
                    if (acceptedWithoutVerification) {
                        syncManager.scheduleProviderSyncResume(
                            onboardingTarget.providerData.id,
                            onboardingTarget.providerData.stalkerConfigurationGeneration
                        )
                        val message =
                            "Provider saved with Live TV verification pending. It needs a successful sync before activation."
                        Result.error(
                            message,
                            ProviderSavedWithSyncErrorException(
                                provider = onboardingTarget.providerData.copy(
                                    status = ProviderStatus.PARTIAL,
                                    isActive = false
                                ),
                                message = message
                            )
                        )
                    } else {
                        handleInitialOnboardingSync(
                            providerData = onboardingTarget.providerData,
                            syncResult = syncInitialOnboarding(onboardingTarget, onProgress),
                            syncFailurePrefix = "Provider login succeeded, but initial sync failed. The provider was saved and can be retried from Settings",
                            pendingEdit = onboardingTarget.pendingEdit
                        )
                    }
                } catch (cancelled: CancellationException) {
                    // Authentication state is provider-scoped. A cancelled edit must not leave
                    // the old active configuration using the replacement session/recipe.
                    restoreStalkerEditIfStillPending(
                        existingProvider = existingProvider,
                        pendingEdit = onboardingTarget.pendingEdit,
                        previousPortalState = previousPortalState
                    )
                    throw cancelled
                }
                // Existing edits are staged until the first catalog transaction promotes the
                // candidate. Persisting authentication learning before that point would fail the
                // generation CAS (or, without it, attach the new observations to the old config).
                if (existingProvider != null &&
                    !acceptedWithoutVerification &&
                    validatedSnapshot != null &&
                    onboardingTarget.pendingEdit?.let { pending ->
                        providerConfigRevisionDao.getState(existingProvider.id, pending.revision) ==
                            ProviderConfigRevisionState.COMMITTED
                    } == true &&
                    providerSnapshotDao.getConfig(existingProvider.id)?.configurationGeneration == candidateGeneration
                ) {
                    val (session, validatedProfile) = validatedSnapshot
                    stalkerPortalStateStore.recordAuthentication(
                        providerId = existingProvider.id,
                        session = session,
                        profile = validatedProfile,
                        configurationGeneration = candidateGeneration
                    )
                }
                if (existingProvider != null && onboardingResult is Result.Error && !acceptedWithoutVerification) {
                    val restored = restoreStalkerEditIfStillPending(
                            existingProvider = existingProvider,
                            pendingEdit = onboardingTarget.pendingEdit,
                            previousPortalState = previousPortalState
                        )
                    if (restored) {
                        Result.error(
                            "The new Stalker settings were not saved because initial readiness failed. The previous provider configuration is still active.",
                            onboardingResult.exception?.cause ?: onboardingResult.exception
                        )
                    } else {
                        // The first catalog transaction already promoted this edit. Keep the
                        // candidate row aligned with the committed replacement catalog even if
                        // a later optional phase reported an error.
                        onboardingResult
                    }
                } else {
                    onboardingResult
                }
            }
            is Result.Error -> {
                val consent = (finalAuthResult.exception as? StalkerApiError.TransportConsentRequired)
                    ?.let { StalkerTransportConsentRequiredException(it.challenge) }
                if (consent != null) {
                    Result.error(consent.message.orEmpty(), consent)
                } else if (finalAuthResult.exception is StalkerApiError.ReadinessInconclusive) {
                    val inconclusive = finalAuthResult.exception as StalkerApiError.ReadinessInconclusive
                    val required = StalkerReadinessInconclusiveException(
                        evidenceCode = inconclusive.evidenceCode,
                        message = "Authentication succeeded, but Live TV could not be verified. You can go back or save this provider with verification pending.",
                        cause = inconclusive
                    )
                    Result.error(required.message.orEmpty(), required)
                } else if (!inputHasScheme && finalAuthResult.exception.isNonTlsTransportFailure()) {
                    val httpOrigin = "http://$normalizedPortalUrl".toStalkerOrigin()
                    if (httpOrigin != null) {
                        val challenge = StalkerTransportChallenge(
                            reason = StalkerTransportChallengeReason.CLEARTEXT_HTTP,
                            origin = httpOrigin,
                            displayHost = if (httpOrigin.port == 80) {
                                httpOrigin.host
                            } else {
                                "${httpOrigin.host}:${httpOrigin.port}"
                            },
                            detailCode = "HTTPS_UNAVAILABLE_HTTP_FALLBACK"
                        )
                        val required = StalkerTransportConsentRequiredException(challenge)
                        Result.error(required.message.orEmpty(), required)
                    } else {
                        Result.error(finalAuthResult.message, finalAuthResult.exception)
                    }
                } else {
                    Result.error(finalAuthResult.message, finalAuthResult.exception)
                }

            }
            is Result.Loading -> Result.error("Unexpected loading state")
        }
    }

    /**
     * Records an edit without touching the committed provider row. The password is encrypted
     * before serialization, so recovery state never introduces a new plaintext credential copy.
     */
    private suspend fun stageProviderEdit(candidate: Provider): PendingProviderEdit {
        require(candidate.id != 0L) { "A provider edit requires an existing provider ID." }
        val secureCandidate = candidate.toSecureEntity()
        val now = System.currentTimeMillis()
        val revision = transactionRunner.inTransaction {
            val nextRevision = providerConfigRevisionDao.latestRevision(candidate.id) + 1L
            val configurationGeneration =
                (providerSnapshotDao.getConfig(candidate.id)?.configurationGeneration ?: 0L) + 1L
            providerConfigRevisionDao.supersedeOlder(candidate.id, nextRevision, now)
            providerConfigRevisionDao.upsert(
                ProviderConfigRevisionEntity(
                    providerId = candidate.id,
                    revision = nextRevision,
                    configJson = ProviderConfigRevisionCodec(
                        gson,
                        providerConfigurationCodec,
                        credentialCrypto
                    ).encode(candidate, configurationGeneration),
                    state = ProviderConfigRevisionState.PENDING,
                    createdAt = now,
                    updatedAt = now
                )
            )
            check(providerConfigRevisionDao.claimForSync(candidate.id, nextRevision, now) == 1) {
                "Pending provider edit could not be claimed for synchronization."
            }
            nextRevision
        }
        return PendingProviderEdit(revision, candidate, secureCandidate).also { pendingEdit ->
            // If the process dies during the foreground sync, WorkManager retains this delayed
            // hand-off and resumes the staged candidate rather than the committed configuration.
            scheduleProviderEditRecovery(pendingEdit, immediate = false)
        }
    }

    /** Runs a candidate configuration without exposing it through the committed provider row. */
    private suspend fun syncInitialOnboarding(
        target: InitialOnboardingTarget,
        onProgress: ((String) -> Unit)?,
        trackInitialLiveOnboarding: Boolean = false
    ): Result<Unit> {
        return try {
        val pendingEdit = target.pendingEdit
        if (pendingEdit == null) {
            // Fresh setup: bootstrap the catalog (capped live channels, category shells,
            // EPG deferred) so onboarding completes fast; a background resume finishes the rest.
            syncManager.sync(
                providerId = target.providerData.id,
                force = false,
                onProgress = onProgress,
                trackInitialLiveOnboarding = trackInitialLiveOnboarding,
                bootstrap = true
            )
        } else {
            // Provider edits keep the full synchronous sync: the replacement catalog must be
            // complete before the staged configuration is promoted.
            syncManager.syncWithProviderOverride(
                providerId = target.providerData.id,
                // A staged edit must validate and publish the replacement catalog even when the
                // committed provider's cache is still fresh. Otherwise a successful edit can
                // finish without invoking the promotion callback and remain forever pending.
                force = true,
                onProgress = onProgress,
                trackInitialLiveOnboarding = trackInitialLiveOnboarding,
                providerOverride = pendingEdit.candidate,
                afterCatalogApply = { promoteProviderEdit(pendingEdit) }
            )
        }
    } catch (error: kotlinx.coroutines.CancellationException) {
        target.pendingEdit?.let { pendingEdit ->
            withContext(NonCancellable) {
                providerConfigRevisionDao.releaseForRetry(
                    pendingEdit.candidate.id,
                    pendingEdit.revision,
                    System.currentTimeMillis()
                )
            }
        }
        throw error
        }
    }

    private suspend fun persistTypedSnapshot(
        providerId: Long,
        provider: Provider,
        currentGeneration: Long?
    ) {
        val configuration = provider.toTypedConfiguration()
        val currentConfiguration = providerSnapshotDao.getConfig(providerId)?.let { stored ->
            runCatching { providerConfigurationCodec.decode(stored.type, stored.encryptedConfigJson) }.getOrNull()
        }
        val generation = when {
            currentGeneration == null -> maxOf(1L, provider.stalkerConfigurationGeneration)
            currentConfiguration == configuration -> currentGeneration
            else -> currentGeneration + 1L
        }
        if (currentGeneration == null || currentConfiguration != configuration) {
            val committed = providerSnapshotDao.commitConfiguration(
                ProviderConfigEntity(
                    providerId = providerId,
                    type = provider.type,
                    schemaVersion = configuration.schemaVersion,
                    configurationGeneration = generation,
                    identityKey = providerConfigurationCodec.identityKey(configuration),
                    encryptedConfigJson = providerConfigurationCodec.encode(configuration),
                    guideSourcePolicy = configuration.guidePolicy(),
                    channelLogoSourcePolicy = configuration.logoPolicy(),
                    updatedAt = System.currentTimeMillis()
                )
            )
            check(committed) { "Provider configuration generation was superseded" }
        }
        val runtime = provider.toAccountRuntime()
        providerSnapshotDao.upsertRuntime(
            ProviderAccountRuntimeEntity(
                providerId = providerId,
                maxConnections = runtime.maxConnections,
                expirationDate = runtime.expirationDate,
                apiVersion = runtime.apiVersion,
                allowedOutputFormatsJson = gson.toJson(runtime.allowedOutputFormats),
                catalogLayout = runtime.catalogLayout,
                catalogLayoutDetectionVersion = runtime.catalogLayoutDetectionVersion,
                observedAt = runtime.observedAt
            )
        )
    }

    private suspend fun insertConfiguredProvider(provider: Provider): Long =
        transactionRunner.inTransaction {
            val providerId = providerDao.insert(provider.toEntity())
            persistTypedSnapshot(providerId, provider, currentGeneration = null)
            providerId
        }

    private suspend fun findProviderByConfigurationIdentity(
        configuration: ProviderConfiguration
    ): ProviderEntity? = providerSnapshotDao
        .findProviderIdByIdentityKey(providerConfigurationCodec.identityKey(configuration))
        ?.let { providerDao.getById(it) }

    private suspend fun loadLegacyProvider(providerId: Long): Provider? =
        providerCapabilityResolver.snapshot(providerId)?.toLegacyProvider()

    private fun ProviderAccountRuntimeEntity.toDomainRuntime() = ProviderAccountRuntime(
        maxConnections = maxConnections,
        expirationDate = expirationDate,
        apiVersion = apiVersion,
        allowedOutputFormats = runCatching {
            gson.fromJson(allowedOutputFormatsJson, Array<String>::class.java).toList()
        }.getOrDefault(emptyList()),
        catalogLayout = catalogLayout,
        catalogLayoutDetectionVersion = catalogLayoutDetectionVersion,
        observedAt = observedAt
    )

    private suspend fun restoreStalkerEditIfStillPending(
        existingProvider: Provider?,
        pendingEdit: PendingProviderEdit?,
        previousPortalState: StalkerPortalStateEntity?
    ): Boolean {
        if (existingProvider == null || pendingEdit == null) return false
        return withContext(NonCancellable) {
            transactionRunner.inTransaction {
                // Read and restore in one Room transaction so a newer edit cannot be
                // overwritten by cleanup from an older cancelled/erroring edit.
                val state = providerConfigRevisionDao.getState(
                    pendingEdit.candidate.id,
                    pendingEdit.revision
                )
                val isCurrentUncommitted = state in setOf(
                    ProviderConfigRevisionState.PENDING,
                    ProviderConfigRevisionState.SYNCING,
                    ProviderConfigRevisionState.FAILED
                ) && providerConfigRevisionDao.latestRevision(pendingEdit.candidate.id) == pendingEdit.revision
                if (!isCurrentUncommitted) return@inTransaction false
                providerDao.update(existingProvider.toEntity())
                stalkerPortalStateStore.restore(existingProvider.id, previousPortalState)
                true
            }
        }
    }

    /**
     * Called while the catalog transaction is open. A stale revision is
     * rejected before it can replace the provider row, making a late sync completion harmless.
     */
    private suspend fun promoteProviderEdit(pendingEdit: PendingProviderEdit) {
        val now = System.currentTimeMillis()
        check(
            providerConfigRevisionDao.markCommitted(
                pendingEdit.candidate.id,
                pendingEdit.revision,
                now
            ) == 1
        ) { "Provider edit ${pendingEdit.revision} was superseded before catalog commit." }
        persistTypedSnapshot(
            pendingEdit.candidate.id,
            pendingEdit.candidate,
            providerSnapshotDao.getConfig(pendingEdit.candidate.id)?.configurationGeneration
        )
        providerDao.update(
            pendingEdit.secureCandidate.copy(
                isActive = true,
                status = ProviderStatus.PARTIAL,
                lastSyncedAt = now
            )
        )
    }

    private suspend fun handleInitialOnboardingSync(
        providerData: Provider,
        syncResult: Result<Unit>,
        syncFailurePrefix: String,
        pendingEdit: PendingProviderEdit? = null
    ): Result<Provider> {
        return when (syncResult) {
        is Result.Success -> {
            if (
                pendingEdit != null &&
                providerConfigRevisionDao.getState(providerData.id, pendingEdit.revision) != ProviderConfigRevisionState.COMMITTED
            ) {
                providerConfigRevisionDao.markFailed(
                    providerData.id,
                    pendingEdit.revision,
                    "Initial sync completed without committing catalog content.",
                    System.currentTimeMillis()
                )
                scheduleProviderEditRecovery(pendingEdit)
                val message = "$syncFailurePrefix: Sync did not finish with any committed content."
                return Result.error(
                    message,
                    ProviderSavedWithSyncErrorException(
                        provider = providerData.copy(status = ProviderStatus.PARTIAL, isActive = false),
                        message = message
                    )
                )
            }
            val finalStatus = if (syncManager.currentSyncState(providerData.id) is SyncState.Partial) {
                ProviderStatus.PARTIAL
            } else {
                ProviderStatus.ACTIVE
            }
                if (!hasUsableLiveCatalogForActivation(
                    providerData.id,
                    providerData.type,
                    channelDao,
                    categoryDao,
                    syncMetadataRepository
                )) {
                updateProviderSyncStatus(
                    providerData.id,
                    ProviderStatus.PARTIAL,
                    lastSyncedAt = System.currentTimeMillis(),
                    isActive = false
                )
                syncManager.scheduleProviderSyncResume(
                    providerData.id,
                    providerData.stalkerConfigurationGeneration.takeIf {
                        providerData.type == ProviderType.STALKER_PORTAL
                    }
                )
                val message = "$syncFailurePrefix: Sync did not finish with any committed content."
                Result.error(
                    message,
                    ProviderSavedWithSyncErrorException(
                        provider = providerData.copy(status = ProviderStatus.PARTIAL, isActive = false),
                        message = message
                    )
                )
            } else {
                providerDao.setActive(providerData.id)
                updateProviderSyncStatus(
                    providerData.id,
                    finalStatus,
                    lastSyncedAt = System.currentTimeMillis()
                )
                maybeScheduleBackgroundEpgSync(providerData.id)
                Result.success(providerData.copy(status = finalStatus, isActive = true))
            }
        }
        is Result.Error -> {
            if (pendingEdit != null) {
                val state = providerConfigRevisionDao.getState(providerData.id, pendingEdit.revision)
                if (state != ProviderConfigRevisionState.COMMITTED) {
                    providerConfigRevisionDao.markFailed(
                        providerData.id,
                        pendingEdit.revision,
                        syncResult.message,
                        System.currentTimeMillis()
                    )
                    scheduleProviderEditRecovery(pendingEdit)
                    val message = "$syncFailurePrefix: ${syncResult.message}"
                    return Result.error(
                        message,
                        ProviderSavedWithSyncErrorException(
                            provider = providerData.copy(status = ProviderStatus.PARTIAL, isActive = false),
                            message = message,
                            cause = syncResult.exception
                        )
                    )
                }
            }
            updateProviderSyncStatus(
                providerData.id,
                ProviderStatus.PARTIAL,
                isActive = pendingEdit != null
            )
            syncManager.scheduleProviderSyncResume(providerData.id)
            val message = "$syncFailurePrefix: ${syncResult.message}"
            Result.error(
                message,
                ProviderSavedWithSyncErrorException(
                    provider = providerData.copy(status = ProviderStatus.PARTIAL, isActive = pendingEdit != null),
                    message = message,
                    cause = syncResult.exception
                )
            )
        }
        is Result.Loading -> Result.error("Unexpected loading state")
        }
    }

    private fun scheduleProviderEditRecovery(
        pendingEdit: PendingProviderEdit,
        immediate: Boolean = true
    ) {
        runCatching {
            ProviderSyncWorker.enqueueProviderConfigRevision(
                appContext,
                pendingEdit.candidate.id,
                pendingEdit.revision,
                immediate = immediate
            )
        }.onFailure { error ->
            logger.warning("Failed to schedule pending provider edit recovery: ${error.message}")
        }
    }

    /**
     * Delegates to [SyncManager] — the single source of truth for the full sync pipeline.
     */
    override suspend fun refreshProviderData(
        providerId: Long,
        force: Boolean,
        movieFastSyncOverride: Boolean?,
        epgSyncModeOverride: ProviderEpgSyncMode?,
        onProgress: ((String) -> Unit)?
    ): Result<Unit> {
        if (force && providerDao.getById(providerId)?.type == ProviderType.STALKER_PORTAL) {
            stalkerPortalStateStore.invalidateCapabilities(providerId)
            providerSnapshotDao.invalidateCatalogLayoutDetection(providerId)
        }
        var syncResult: Result<Unit>? = null
        val disposition = providerWorkflowRunner.execute(
            providerId = providerId,
            phase = ProviderWorkflowPhase.PRIMARY_CATALOG,
            reason = ProviderWorkflowReason.MANUAL,
            force = force,
            supersede = force,
            priority = MANUAL_REFRESH_PRIORITY
        ) {
            val result = syncManager.sync(
                providerId,
                force = force,
                movieFastSyncOverride = movieFastSyncOverride,
                epgSyncModeOverride = epgSyncModeOverride,
                onProgress = onProgress
            )
            syncResult = result
            when (result) {
                is Result.Success -> {
                    finalizeSuccessfulManualSync(providerId)
                    ProviderWorkflowOutcome.Success(
                        partial = syncManager.currentSyncState(providerId) is SyncState.Partial
                    )
                }
                is Result.Error -> {
                    transactionRunner.inTransaction {
                        providerWorkflowCommitFence.assertCanCommit(providerId)
                        updateProviderSyncStatus(providerId, ProviderStatus.ERROR)
                    }
                    ProviderWorkflowOutcome.Failure(
                        code = "MANUAL_PROVIDER_SYNC",
                        message = result.message,
                        cause = result.exception
                    )
                }
                is Result.Loading -> ProviderWorkflowOutcome.Failure(
                    code = "MANUAL_PROVIDER_SYNC_LOADING",
                    message = "Provider refresh did not reach a terminal state.",
                    retryable = true
                )
            }
        }

        if (disposition == ProviderWorkflowDisposition.BUSY) {
            return Result.error("Provider refresh is already in progress.")
        }
        if (disposition == ProviderWorkflowDisposition.SUPERSEDED) {
            return Result.error("Provider refresh was superseded by a newer request.")
        }
        val terminalResult = syncResult ?: return when (disposition) {
            ProviderWorkflowDisposition.RETRY ->
                Result.error("Provider refresh was interrupted and will need to be retried.")
            ProviderWorkflowDisposition.FAILED ->
                Result.error("Provider refresh failed before synchronization started.")
            else -> Result.error("Provider refresh did not produce a result.")
        }

        return when (terminalResult) {
            is Result.Success -> terminalResult
            is Result.Error -> terminalResult
            is Result.Loading -> Result.error("Unexpected loading state")
        }
    }

    private suspend fun finalizeSuccessfulManualSync(providerId: Long) {
        var shouldResume = false
        var shouldScheduleEpg = false
        transactionRunner.inTransaction {
            providerWorkflowCommitFence.assertCanCommit(providerId)
            val finalStatus = if (syncManager.currentSyncState(providerId) is SyncState.Partial) {
                ProviderStatus.PARTIAL
            } else {
                ProviderStatus.ACTIVE
            }
            val provider = providerDao.getById(providerId)
            if (
                provider != null &&
                !hasUsableLiveCatalogForActivation(
                    providerId,
                    provider.type,
                    channelDao,
                    categoryDao,
                    syncMetadataRepository
                )
            ) {
                updateProviderSyncStatus(
                    providerId,
                    ProviderStatus.PARTIAL,
                    lastSyncedAt = System.currentTimeMillis(),
                    isActive = false
                )
                shouldResume = true
            } else {
                updateProviderSyncStatus(providerId, finalStatus, System.currentTimeMillis())
                shouldScheduleEpg = true
            }
        }
        if (shouldResume) {
            syncManager.scheduleProviderSyncResume(providerId)
        } else if (shouldScheduleEpg) {
            maybeScheduleBackgroundEpgSync(providerId)
        }
    }

    override suspend fun getProgramsForLiveStream(
        providerId: Long,
        streamId: Long,
        epgChannelId: String?,
        limit: Int
    ): Result<List<Program>> {
        val providerEntity = loadLegacyProvider(providerId)
            ?: return Result.error("Provider $providerId not found")
        if (!allowsOnDemandGuide(providerEntity)) {
            return Result.error("On-demand guide lookup is disabled for this provider.")
        }
        val guide = when (val resolution = resolveGuideCapability(providerId)) {
            is CapabilityResolution.Available -> resolution.capability
            is CapabilityResolution.ConfigurationError -> return Result.error(resolution.reason)
            is CapabilityResolution.Restricted -> return Result.error(resolution.reason)
            is CapabilityResolution.Unsupported -> return Result.error(resolution.reason)
        }
        val result = fetchProgramsForLiveStream(
            providerId = providerId,
            streamId = streamId,
            epgChannelId = epgChannelId,
            limit = limit,
            guide = guide
        )
        if (result is Result.Success && result.data.isNotEmpty()) {
            cacheProgramsForChannel(providerId, result.data)
            refreshCachedEpgMetadata(providerId)
        }
        return result
    }

    override suspend fun getProgramsForLiveStreams(
        providerId: Long,
        requests: List<LiveStreamProgramRequest>,
        limit: Int
    ): Map<LiveStreamProgramRequest, Result<List<Program>>> {
        val normalizedRequests = requests
            .filter { it.streamId > 0L }
            .distinct()
        if (normalizedRequests.isEmpty()) {
            return emptyMap()
        }

        val providerEntity = loadLegacyProvider(providerId)
            ?: return normalizedRequests.associateWith { Result.error("Provider $providerId not found") }
        if (!allowsOnDemandGuide(providerEntity)) {
            return normalizedRequests.associateWith {
                Result.error("On-demand guide lookup is disabled for this provider.")
            }
        }

        val guide = when (val resolution = resolveGuideCapability(providerId)) {
            is CapabilityResolution.Available -> resolution.capability
            is CapabilityResolution.ConfigurationError -> return normalizedRequests.associateWith { Result.error(resolution.reason) }
            is CapabilityResolution.Restricted -> return normalizedRequests.associateWith { Result.error(resolution.reason) }
            is CapabilityResolution.Unsupported -> return normalizedRequests.associateWith { Result.error(resolution.reason) }
        }
        return coroutineScope {
            val requestDispatcher = Dispatchers.IO.limitedParallelism(XTREAM_GUIDE_BATCH_CONCURRENCY)
            normalizedRequests
                .map { request ->
                    async(requestDispatcher) {
                        request to fetchProgramsForLiveStream(
                            providerId = providerId,
                            streamId = request.streamId,
                            epgChannelId = request.epgChannelId,
                            limit = limit,
                            guide = guide
                        )
                    }
                }
                .awaitAll()
                .also { results ->
                    val cachedPrograms = results
                        .mapNotNull { (_, result) -> (result as? Result.Success)?.data }
                        .flatten()
                    if (cachedPrograms.isNotEmpty()) {
                        cacheProgramsForChannels(providerId, cachedPrograms)
                        refreshCachedEpgMetadata(providerId)
                    }
                }
                .toMap()
        }
    }

    override suspend fun buildCatchUpUrl(providerId: Long, streamId: Long, start: Long, end: Long): String? {
        return buildCatchUpUrls(providerId, streamId, start, end).firstOrNull()
    }

    override suspend fun buildCatchUpUrls(providerId: Long, streamId: Long, start: Long, end: Long): List<String> {
        providerDao.getById(providerId) ?: return emptyList()
        val channel = channelDao.getById(streamId)
        val resolvedStreamId = channel?.streamId?.takeIf { it > 0 } ?: streamId
        val capabilitySet = when (val resolution = providerCapabilityResolver.resolve(providerId)) {
            is CapabilityResolution.Available -> resolution.capability
            else -> return emptyList()
        }
        val catchUp = when (val resolution = capabilitySet.catchUp()) {
            is CapabilityResolution.Available -> resolution.capability
            else -> return emptyList()
        }
        return catchUp.buildCatchUpUrls(
            CatchUpRequest(
                streamId = resolvedStreamId,
                start = start,
                end = end,
                sourceStreamUrl = channel?.streamUrl,
                sourceCatchUpTemplate = channel?.catchUpSource
            )
        )
    }

    suspend fun createXtreamProvider(
        providerId: Long,
        serverUrl: String,
        username: String,
        password: String,
        allowedOutputFormats: List<String> = emptyList(),
        httpUserAgent: String = "",
        httpHeaders: String = ""
    ): XtreamProvider {
        val snapshot = ProviderSnapshot(
            provider = StableProvider(providerId, "Xtream", ProviderType.XTREAM_CODES),
            configuration = XtreamConfig(
                serverUrl = serverUrl,
                username = username,
                password = password,
                httpUserAgent = httpUserAgent,
                httpHeaders = httpHeaders
            ),
            configurationGeneration = 1L,
            accountRuntime = ProviderAccountRuntime(allowedOutputFormats = allowedOutputFormats)
        )
        return when (val resolution = typedProviderClientFactory.xtream(snapshot)) {
            is CapabilityResolution.Available -> resolution.capability
            is CapabilityResolution.ConfigurationError -> throw IllegalArgumentException(resolution.reason)
            is CapabilityResolution.Restricted -> throw IllegalArgumentException(resolution.reason)
            is CapabilityResolution.Unsupported -> throw IllegalArgumentException(resolution.reason)
        }
    }

    private fun createStalkerProvider(
        providerId: Long,
        portalUrl: String,
        macAddress: String,
        authMode: StalkerAuthMode,
        username: String,
        password: String,
        httpUserAgent: String = "",
        httpHeaders: String = "",
        portalFingerprintHint: StalkerPortalFingerprint = StalkerPortalFingerprint.BASIC_MAC,
        magPresetHint: StalkerMagPreset = StalkerMagPreset.GENERIC_SAFE,
        bootstrapRecipeHint: StalkerBootstrapRecipe = StalkerBootstrapRecipe.GENERIC_SAFE,
        endpointPreferenceHint: StalkerEndpointPreference = StalkerEndpointPreference.AUTO,
        cookieModeHint: StalkerCookieMode = StalkerCookieMode.NONE,
        playbackBackendHint: StalkerPlaybackBackendHint = StalkerPlaybackBackendHint.AUTO,
        portalProfileHint: StalkerPortalProfile = StalkerPortalProfile.MAG_BASIC,
        preferredPlaybackMode: StalkerPlaybackMode? = null,
        deviceProfile: String,
        timezone: String,
        locale: String,
        serialNumber: String = "",
        deviceId: String = "",
        deviceId2: String = "",
        signature: String = "",
        stalkerAdvancedOptionsJson: String = "",
        protocolPreference: StalkerProtocolPreference = StalkerProtocolPreference.AUTO,
        transportGrant: StalkerTransportGrant? = null,
        requestedProfileId: String = StalkerCompatibilityProfileIds.AUTO,
        learnedProfileId: String = "",
        configurationGeneration: Long = 0L,
        requireCatalogValidation: Boolean = true,
        catalogLayoutHint: com.streamvault.domain.model.CatalogLayout = com.streamvault.domain.model.CatalogLayout.UNKNOWN,
        catalogLayoutDetectionVersionHint: Int = 0,
        onProgress: ((String) -> Unit)? = null
    ): StalkerProvider {
        val generation = configurationGeneration.coerceAtLeast(1L)
        val learning = StalkerPortalLearning(
            configurationGeneration = generation,
            profileId = learnedProfileId.takeIf(String::isNotBlank)?.let {
                StalkerObservation(it, generation, StalkerObservationSource.DISCOVERY, 0L)
            },
            portalProfile = StalkerObservation(portalProfileHint, generation, StalkerObservationSource.DISCOVERY, 0L),
            portalFingerprint = StalkerObservation(portalFingerprintHint, generation, StalkerObservationSource.DISCOVERY, 0L),
            magPreset = StalkerObservation(magPresetHint, generation, StalkerObservationSource.DISCOVERY, 0L),
            bootstrapRecipe = StalkerObservation(bootstrapRecipeHint, generation, StalkerObservationSource.DISCOVERY, 0L),
            endpointPreference = StalkerObservation(endpointPreferenceHint, generation, StalkerObservationSource.DISCOVERY, 0L),
            cookieMode = StalkerObservation(cookieModeHint, generation, StalkerObservationSource.DISCOVERY, 0L),
            playbackBackendHint = StalkerObservation(playbackBackendHint, generation, StalkerObservationSource.DISCOVERY, 0L),
            lastPlaybackMode = preferredPlaybackMode?.name?.let {
                StalkerObservation(it, generation, StalkerObservationSource.DISCOVERY, 0L)
            }
        )
        val snapshot = ProviderSnapshot(
            provider = StableProvider(providerId, "Stalker", ProviderType.STALKER_PORTAL),
            configuration = StalkerConfig(
                portalUrl = portalUrl,
                device = StalkerDeviceIdentity(
                    macAddress,
                    deviceProfile,
                    timezone,
                    locale,
                    serialNumber,
                    deviceId,
                    deviceId2,
                    signature
                ),
                username = username,
                password = password,
                httpUserAgent = httpUserAgent,
                httpHeaders = httpHeaders,
                advancedOptionsJson = stalkerAdvancedOptionsJson,
                authMode = authMode,
                requestedProfileId = requestedProfileId,
                protocolPreference = protocolPreference,
                transportGrant = transportGrant
            ),
            configurationGeneration = generation,
            accountRuntime = ProviderAccountRuntime(
                catalogLayout = catalogLayoutHint,
                catalogLayoutDetectionVersion = catalogLayoutDetectionVersionHint
            ),
            stalkerLearning = learning
        )
        return when (val resolution = typedProviderClientFactory.stalker(
            snapshot,
            StalkerClientOptions(requireCatalogValidation, onProgress)
        )) {
            is CapabilityResolution.Available -> resolution.capability
            is CapabilityResolution.ConfigurationError -> throw IllegalArgumentException(resolution.reason)
            is CapabilityResolution.Restricted -> throw IllegalArgumentException(resolution.reason)
            is CapabilityResolution.Unsupported -> throw IllegalArgumentException(resolution.reason)
        }
    }

    private fun ProviderEntity.toPublicDomain(): Provider {
        return toDomain().copy(password = "")
    }

    /** Public provider projections must never expose decrypted account credentials. */
    private fun Provider.redactedCredentials(): Provider = copy(password = "")

    /**
     * Settings edit a redacted public projection. Do not turn those non-secret edits into a
     * credential deletion; explicit setup/password changes still pass a nonblank value through.
     */
    private fun Provider.withPersistedCredential(
        currentConfiguration: ProviderConfiguration?
    ): Provider {
        if (password.isNotBlank()) return this
        val existingPassword = when (currentConfiguration) {
            is XtreamConfig -> currentConfiguration.password
            is StalkerConfig -> currentConfiguration.password
            is JellyfinConfig -> currentConfiguration.credential
            is M3uConfig, null -> ""
        }
        return if (existingPassword.isBlank()) this else copy(password = existingPassword)
    }

    private suspend fun resolveGuideCapability(providerId: Long): CapabilityResolution<GuideSource> =
        when (val providerResolution = providerCapabilityResolver.resolve(providerId)) {
            is CapabilityResolution.Available -> providerResolution.capability.guide()
            is CapabilityResolution.ConfigurationError -> providerResolution
            is CapabilityResolution.Restricted -> providerResolution
            is CapabilityResolution.Unsupported -> providerResolution
        }

    private suspend fun fetchProgramsForLiveStream(
        providerId: Long,
        streamId: Long,
        epgChannelId: String?,
        limit: Int,
        guide: GuideSource
    ): Result<List<Program>> {
        if (providerId <= 0L || streamId <= 0L) {
            return Result.error("Live stream context is unavailable.")
        }
        val request = GuideRequest(
            streamId = streamId,
            epgChannelId = epgChannelId,
            limit = limit.coerceAtLeast(1)
        )
        val shortResult = guide.getShortEpg(request)
        val shortPrograms = (shortResult as? Result.Success)?.data
            ?.sortedBy(Program::startTime)
            .orEmpty()
        if (shortPrograms.isNotEmpty()) {
            return Result.success(
                normalizeXtreamPrograms(providerId, epgChannelId ?: streamId.toString(), shortPrograms)
            )
        }
        return when (val fullResult = guide.getEpg(request)) {
            is Result.Success -> Result.success(
                normalizeXtreamPrograms(
                    providerId,
                    epgChannelId ?: streamId.toString(),
                    fullResult.data.sortedBy(Program::startTime)
                )
            )
            is Result.Error -> {
                val shortError = shortResult as? Result.Error
                Result.error(
                    listOfNotNull(shortError?.message, fullResult.message)
                        .filter(String::isNotBlank)
                        .distinct()
                        .joinToString(" / ")
                        .ifBlank { "Failed to load on-demand guide" },
                    fullResult.exception ?: shortError?.exception
                )
            }
            is Result.Loading -> Result.error("Unexpected loading state")
        }
    }

    private suspend fun fetchXtreamProgramsForLiveStream(
        providerId: Long,
        streamId: Long,
        epgChannelId: String?,
        limit: Int,
        xtreamProvider: XtreamProvider
    ): Result<List<Program>> {
        if (providerId <= 0L || streamId <= 0L) {
            return Result.error("Live stream context is unavailable.")
        }

        val shortProgramsResult = xtreamProvider.getShortEpg(
            channelId = streamId.toString(),
            limit = limit.coerceAtLeast(1)
        )
        val shortPrograms = (shortProgramsResult as? Result.Success)?.data
            ?.sortedBy { it.startTime }
            .orEmpty()
        if (shortPrograms.isNotEmpty()) {
            return Result.success(
                normalizeXtreamPrograms(
                    providerId = providerId,
                    channelId = epgChannelId ?: streamId.toString(),
                    programs = shortPrograms
                )
            )
        }

        return when (val fullProgramsResult = xtreamProvider.getEpg(streamId.toString())) {
            is Result.Success -> {
                val normalizedPrograms = normalizeXtreamPrograms(
                    providerId = providerId,
                    channelId = epgChannelId ?: streamId.toString(),
                    programs = fullProgramsResult.data.sortedBy { it.startTime }
                )
                Result.success(normalizedPrograms)
            }
            is Result.Error -> {
                val shortError = shortProgramsResult as? Result.Error
                val combinedMessage = listOfNotNull(
                    shortError?.message?.takeIf { it.isNotBlank() },
                    fullProgramsResult.message.takeIf { it.isNotBlank() }
                )
                    .distinct()
                    .joinToString(separator = " / ")
                    .ifBlank { "Failed to load on-demand guide" }
                Result.error(combinedMessage, fullProgramsResult.exception ?: shortError?.exception)
            }
            is Result.Loading -> Result.error("Unexpected loading state")
        }
    }

    private fun Provider.toSecureEntity(): ProviderEntity {
        val encryptedPassword = credentialCrypto.encryptIfNeeded(password)
        return copy(password = encryptedPassword).toEntity()
    }

    private suspend fun updateProviderSyncStatus(
        providerId: Long,
        status: ProviderStatus,
        lastSyncedAt: Long? = null,
        isActive: Boolean? = null
    ) {
        val current = providerDao.getById(providerId) ?: return
        val updated = current.copy(
            status = status,
            lastSyncedAt = lastSyncedAt ?: current.lastSyncedAt,
            isActive = isActive ?: current.isActive
        )
        providerDao.update(updated)
    }

    private suspend fun maybeScheduleBackgroundEpgSync(providerId: Long) {
        val provider = loadLegacyProvider(providerId) ?: return
        if (provider.epgSyncMode != ProviderEpgSyncMode.BACKGROUND) {
            return
        }
        // The previous implementation launched a coroutine that slept for 15s and then
        // scheduled the worker. That kept a coroutine alive (and held onto its captures)
        // even when the user immediately backed out of the screen. WorkManager's own
        // initialDelay is the right place for that wait — it's persisted, cancellable,
        // and doesn't pin any process state.
        syncManager.scheduleBackgroundEpgSync(providerId)
    }

    private fun normalizeXtreamPrograms(
        providerId: Long,
        channelId: String,
        programs: List<Program>
    ): List<Program> {
        return programs.map { program ->
            program.copy(
                providerId = providerId,
                channelId = channelId
            )
        }
    }

    private suspend fun cacheProgramsForChannel(providerId: Long, programs: List<Program>) {
        val channelId = programs.firstOrNull()?.channelId ?: return
        transactionRunner.inTransaction {
            programDao.deleteForChannel(providerId, channelId)
            programDao.insertAll(programs.map { it.toEntity().copy(providerId = providerId) })
        }
    }

    private suspend fun cacheProgramsForChannels(providerId: Long, programs: List<Program>) {
        if (programs.isEmpty()) return
        val programsByChannel = programs.groupBy { it.channelId }
        transactionRunner.inTransaction {
            programsByChannel.forEach { (channelId, channelPrograms) ->
                programDao.deleteForChannel(providerId, channelId)
                programDao.insertAll(channelPrograms.map { it.toEntity().copy(providerId = providerId) })
            }
        }
    }

    private suspend fun refreshCachedEpgMetadata(providerId: Long) {
        val now = System.currentTimeMillis()
        val metadata = (syncMetadataRepository.getMetadata(providerId) ?: SyncMetadata(providerId)).copy(
            lastEpgSync = now,
            lastEpgSuccess = now,
            epgCount = programDao.countByProvider(providerId)
        )
        syncMetadataRepository.updateMetadata(metadata)
    }

    private fun allowsOnDemandGuide(provider: Provider): Boolean = when (provider.guideSourcePolicy) {
        GuideSourcePolicy.AUTO,
        GuideSourcePolicy.PROVIDER_ONLY -> true
        GuideSourcePolicy.EXTERNAL_ONLY,
        GuideSourcePolicy.DISABLED -> provider.type != ProviderType.XTREAM_CODES && provider.type != ProviderType.STALKER_PORTAL
    }
}

private fun Provider.toStalkerTransportGrant(): StalkerTransportGrant? {
    if (stalkerTransportMode != StalkerTransportMode.USER_ACCEPTED_HTTP &&
        stalkerTransportMode != StalkerTransportMode.USER_ACCEPTED_UNVERIFIED_HTTPS &&
        stalkerTransportMode != StalkerTransportMode.VERIFIED_HTTPS
    ) {
        return null
    }
    val origin = stalkerTransportOrigin.toStalkerOrigin()
        ?: serverUrl.toStalkerOrigin()
        ?: return null
    val pin = stalkerTlsSpkiSha256.takeIf(String::isNotBlank)
    if (stalkerTransportMode == StalkerTransportMode.USER_ACCEPTED_UNVERIFIED_HTTPS && pin == null) {
        return null
    }
    return StalkerTransportGrant(
        mode = stalkerTransportMode,
        origin = origin,
        spkiSha256 = pin,
        consentedAt = stalkerTransportConsentAt
    )
}

private fun String.toStalkerOrigin(): StalkerTransportOrigin? {
    val uri = runCatching { URI(trim()) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase()?.takeIf { it == "http" || it == "https" } ?: return null
    val host = uri.host?.lowercase()?.takeIf(String::isNotBlank) ?: return null
    val port = when {
        uri.port != -1 -> uri.port
        scheme == "https" -> 443
        else -> 80
    }
    return StalkerTransportOrigin(scheme, host, port)
}

private fun StalkerTransportOrigin.toPersistenceValue(): String =
    authority

private fun Throwable?.isNonTlsTransportFailure(): Boolean {
    val chain = generateSequence(this) { it.cause }.toList()
    if (chain.any {
            it is StalkerApiError &&
                it !is StalkerApiError.Transport &&
                it !is StalkerApiError.TransportConsentRequired
        }
    ) {
        return false
    }
    if (chain.any { it is StalkerApiError.TransportConsentRequired }) return false
    return chain.any { it is StalkerApiError.Transport || it is IOException }
}

private fun Pair<
    com.streamvault.data.remote.stalker.StalkerSession,
    com.streamvault.data.remote.stalker.StalkerProviderProfile
>.toSanitizedDiscoverySummary(): String {
    val session = first
    val endpoint = when {
        session.loadUrl.endsWith("/portal.php", ignoreCase = true) -> "PORTAL_PHP"
        session.loadUrl.endsWith("/server/load.php", ignoreCase = true) -> "SERVER_LOAD"
        else -> "CUSTOM_RPC"
    }
    val profile = session.compatibilityProfileId
        .uppercase()
        .filter { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }
        .take(64)
        .ifBlank { "UNKNOWN" }
    return "AUTHENTICATED;LIVE=SUPPORTED;ENDPOINT=$endpoint;PROFILE=$profile"
}

private fun com.streamvault.data.remote.stalker.StalkerProviderProfile
    .toSanitizedCapabilitySummary(): String {
    val capabilities = portalCapabilities
    val archive = if (capabilities.archiveAvailable) "SUPPORTED" else "NOT_PROBED"
    val modules = if (capabilities.moduleRestricted) "RESTRICTED" else "SUPPORTED"
    return buildString {
        append('{')
        append("\"LIVE\":\"SUPPORTED\",")
        append("\"VOD\":\"NOT_PROBED\",")
        append("\"SERIES\":\"NOT_PROBED\",")
        append("\"ARCHIVE\":\"").append(archive).append("\",")
        append("\"EPG\":\"NOT_PROBED\",")
        append("\"ACCOUNT_MODULES\":\"").append(modules).append("\"")
        append('}')
    }
}

internal fun buildM3uCatchUpUrls(source: String, start: Long, end: Long): List<String> {
    val trimmedSource = source.trim()
    if (trimmedSource.isBlank()) return emptyList()

    val durationSeconds = (end - start).coerceAtLeast(0L)
    val durationMinutes = (durationSeconds / 60L).coerceAtLeast(1L)
    val startDate = java.time.Instant.ofEpochSecond(start).atZone(java.time.ZoneOffset.UTC)
    val endDate = java.time.Instant.ofEpochSecond(end).atZone(java.time.ZoneOffset.UTC)
    val compactStart = startDate.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
    val compactEnd = endDate.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))

    val replacements = linkedMapOf(
        "{start}" to start.toString(),
        "{end}" to end.toString(),
        "{duration}" to durationSeconds.toString(),
        "{duration_seconds}" to durationSeconds.toString(),
        "{duration_minutes}" to durationMinutes.toString(),
        "{utc}" to start.toString(),
        "{utcend}" to end.toString(),
        "{lutc}" to end.toString(),
        "{timestamp}" to start.toString(),
        "{Y}" to startDate.format(java.time.format.DateTimeFormatter.ofPattern("yyyy")),
        "{m}" to startDate.format(java.time.format.DateTimeFormatter.ofPattern("MM")),
        "{d}" to startDate.format(java.time.format.DateTimeFormatter.ofPattern("dd")),
        "{H}" to startDate.format(java.time.format.DateTimeFormatter.ofPattern("HH")),
        "{M}" to startDate.format(java.time.format.DateTimeFormatter.ofPattern("mm")),
        "{S}" to startDate.format(java.time.format.DateTimeFormatter.ofPattern("ss")),
        "{Ymd}" to startDate.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd")),
        "{YmdHis}" to compactStart,
        "{utc:yyyyMMddHHmmss}" to compactStart,
        "{utcend:yyyyMMddHHmmss}" to compactEnd
    )

    val expanded = replacements.entries.fold(trimmedSource) { current, (placeholder, value) ->
        current.replace(placeholder, value)
    }

    return listOf(expanded).distinct()
}
