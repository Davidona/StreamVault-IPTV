package com.streamvault.data.sync

import android.content.Context
import android.util.Log
import com.streamvault.data.local.dao.CategoryDao
import com.streamvault.data.local.dao.ChannelDao
import com.streamvault.data.local.dao.XtreamLiveOnboardingDao
import com.streamvault.data.local.entity.CategoryEntity
import com.streamvault.data.local.entity.ChannelEntity
import com.streamvault.data.local.entity.XtreamLiveOnboardingStateEntity
import com.streamvault.data.mapper.toEntity
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.data.remote.xtream.XtreamProvider
import com.streamvault.data.util.UrlSecurityPolicy
import com.streamvault.data.util.runSuspendCatching
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.LegacyProvider as Provider
import com.streamvault.domain.model.ProviderEpgSyncMode
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.SyncMetadata
import com.streamvault.domain.model.VodSyncMode
import com.streamvault.domain.repository.SyncMetadataRepository
import com.streamvault.domain.sync.Section
import com.streamvault.domain.sync.SyncProgress
import kotlinx.coroutines.flow.first

/** The durable update emitted by Xtream catalog orchestration for an index job. */
internal data class XtreamIndexJobUpdate(
    val providerId: Long,
    val section: String,
    val state: String,
    val now: Long,
    val totalCategories: Int? = null,
    val completedCategories: Int? = null,
    val indexedRows: Int? = null,
    val lastAttemptAt: Long? = null,
    val lastError: String? = null
)

/**
 * Owns Xtream full-sync and Live repair orchestration.
 *
 * The live strategy and section executor remain separate collaborators. This class owns the
 * workflow around them: onboarding recovery, staged activation, metadata, durable job state,
 * and continuation scheduling.
 */
internal class XtreamCatalogSyncExecutor(
    private val applicationContext: Context,
    private val preferencesRepository: PreferencesRepository,
    private val syncMetadataRepository: SyncMetadataRepository,
    private val channelDao: ChannelDao,
    private val categoryDao: CategoryDao,
    private val xtreamLiveOnboardingDao: XtreamLiveOnboardingDao,
    private val syncCatalogStore: SyncCatalogStore,
    private val sectionExecutor: XtreamCatalogSectionExecutor,
    private val liveStrategy: SyncManagerXtreamLiveStrategy,
    private val catalogStrategySupport: SyncManagerCatalogStrategySupport,
    private val createProvider: suspend (Provider, Boolean, Boolean) -> XtreamProvider,
    private val updateIndexJob: suspend (XtreamIndexJobUpdate) -> Unit,
    private val indexFailureState: (Throwable) -> String,
    private val shouldRememberSequentialPreference: (Throwable) -> Boolean,
    private val progress: (Long, ((String) -> Unit)?, String) -> Unit,
    private val emitProgress: (Long, SyncProgress) -> Unit,
    private val sanitizeThrowableMessage: (Throwable?) -> String,
    private val userMessage: (Throwable, String) -> String,
    private val requiredHiddenCategoryIds: suspend (Long, ContentType) -> Set<Long> = { _, _ -> emptySet() }
) {
    suspend fun syncFull(
        provider: Provider,
        force: Boolean,
        onProgress: ((String) -> Unit)?,
        trackInitialLiveOnboarding: Boolean = false,
        syncReason: XtreamLiveSyncReason = XtreamLiveSyncReason.FOREGROUND,
        afterCatalogApply: suspend () -> Unit = {}
    ): SyncOutcome {
        val warnings = mutableListOf<String>()
        val continuationWork = mutableListOf<SyncContinuation>()
        var activatedLiveMutations = 0
        var catalogActivated = false
        var preservedActiveCatalog = false
        UrlSecurityPolicy.validateXtreamServerUrl(provider.serverUrl)?.let { message ->
            throw IllegalStateException(message)
        }

        progress(provider.id, onProgress, "Connecting to server...")
        val useTextClassification = preferencesRepository.useXtreamTextClassification.first()
        val enableBase64TextCompatibility = preferencesRepository.xtreamBase64TextCompatibility.first()
        val hiddenLiveCategoryIds = preferencesRepository.getHiddenCategoryIds(provider.id, ContentType.LIVE).first()
        val requiredHiddenLiveCategoryIds = requiredHiddenCategoryIds(provider.id, ContentType.LIVE)
        val api = createProvider(provider, useTextClassification, enableBase64TextCompatibility)
        val runtimeProfile = CatalogSyncRuntimeProfile.from(applicationContext)
        val now = System.currentTimeMillis()
        var metadata = syncMetadataRepository.getMetadata(provider.id) ?: SyncMetadata(provider.id)

        if (trackInitialLiveOnboarding) {
            recordOnboardingState(
                provider = provider,
                phase = XTREAM_ONBOARDING_PHASE_STARTING,
                now = now,
                clearError = true,
                runtimeProfile = runtimeProfile
            )
        }

        updateIndexJob(
            XtreamIndexJobUpdate(
                providerId = provider.id,
                section = ContentType.LIVE.name,
                state = "RUNNING",
                now = now,
                lastAttemptAt = now
            )
        )
        val liveOutcome = runSuspendCatching {
            val recoveredLiveCommit = if (trackInitialLiveOnboarding) {
                recoverOnboardingSession(provider, hiddenLiveCategoryIds, afterCatalogApply)
            } else {
                null
            }
            if (recoveredLiveCommit != null) {
                warnings += recoveredLiveCommit.warnings
                val acceptedCount = recoveredLiveCommit.acceptedCount
                val completedAt = System.currentTimeMillis()
                recordOnboardingState(
                    provider = provider,
                    phase = XTREAM_ONBOARDING_PHASE_COMPLETED,
                    now = completedAt,
                    acceptedRowCount = acceptedCount,
                    stagedFlushCount = stagedFlushCountFor(acceptedCount),
                    clearError = true,
                    completedAt = completedAt,
                    clearStagedSession = true,
                    runtimeProfile = runtimeProfile
                )
                metadata = metadata.copy(
                    lastLiveSync = now,
                    lastLiveSuccess = now,
                    liveCount = acceptedCount
                )
                syncMetadataRepository.updateMetadata(metadata)
                activatedLiveMutations = acceptedCount
                catalogActivated = true
                return@runSuspendCatching acceptedCount
            }

            if (trackInitialLiveOnboarding) {
                recordOnboardingState(
                    provider = provider,
                    phase = XTREAM_ONBOARDING_PHASE_FETCHING,
                    now = System.currentTimeMillis(),
                    clearError = true,
                    runtimeProfile = runtimeProfile
                )
            }
            progress(provider.id, onProgress, "Downloading Live TV...")
            val liveSyncResult = syncLiveCatalog(
                provider = provider,
                api = api,
                existingMetadata = metadata,
                hiddenLiveCategoryIds = hiddenLiveCategoryIds,
                requiredHiddenLiveCategoryIds = requiredHiddenLiveCategoryIds,
                onProgress = onProgress,
                runtimeProfile = runtimeProfile,
                trackInitialLiveOnboarding = trackInitialLiveOnboarding,
                syncReason = syncReason
            )
            if (trackInitialLiveOnboarding) {
                val stagedAcceptedCount = liveSyncResult.stagedAcceptedCount
                recordOnboardingState(
                    provider = provider,
                    phase = if (liveSyncResult.stagedSessionId != null) {
                        XTREAM_ONBOARDING_PHASE_STAGED
                    } else {
                        XTREAM_ONBOARDING_PHASE_COMMITTING
                    },
                    now = System.currentTimeMillis(),
                    stagedSessionId = liveSyncResult.stagedSessionId,
                    importStrategy = liveSyncResult.catalogResult.strategyNameOrNull(),
                    acceptedRowCount = stagedAcceptedCount,
                    stagedFlushCount = stagedFlushCountFor(stagedAcceptedCount),
                    clearError = true,
                    runtimeProfile = runtimeProfile,
                    syncProfileStrategy = liveSyncResult.profileStrategyName(runtimeProfile, trackInitialLiveOnboarding)
                )
            }
            val liveSequentialStress = liveSyncResult.strategyFeedback.segmentedStressDetected
            val liveProviderAdaptation = updateSequentialProviderAdaptation(
                previousRemembered = metadata.liveSequentialFailuresRemembered,
                previousHealthyStreak = metadata.liveHealthySyncStreak,
                sawSequentialStress = liveSequentialStress
            )
            val liveAvoidFullUntil = catalogStrategySupport.updateAvoidFullUntil(
                previousAvoidFullUntil = metadata.liveAvoidFullUntil,
                now = now,
                feedback = liveSyncResult.strategyFeedback
            )

            val acceptedCount = when (val liveResult = liveSyncResult.catalogResult) {
                is CatalogStrategyResult.Success -> {
                    recordCommitPhaseIfNeeded(
                        provider,
                        liveSyncResult,
                        liveResult.strategyName,
                        runtimeProfile,
                        trackInitialLiveOnboarding
                    )
                    finalizeLiveCatalog(
                        providerId = provider.id,
                        liveSyncResult = liveSyncResult,
                        hiddenLiveCategoryIds = hiddenLiveCategoryIds,
                        onProgress = onProgress,
                        afterCatalogApply = afterCatalogApply
                    ).also {
                        warnings += it.warnings
                        activatedLiveMutations = it.acceptedCount
                        catalogActivated = true
                    }.acceptedCount
                }
                is CatalogStrategyResult.Partial -> {
                    recordCommitPhaseIfNeeded(
                        provider,
                        liveSyncResult,
                        liveResult.strategyName,
                        runtimeProfile,
                        trackInitialLiveOnboarding
                    )
                    finalizeLiveCatalog(
                        providerId = provider.id,
                        liveSyncResult = liveSyncResult,
                        hiddenLiveCategoryIds = hiddenLiveCategoryIds,
                        onProgress = onProgress,
                        partialCompletionWarning = "Live TV sync completed partially.",
                        afterCatalogApply = afterCatalogApply
                    ).also {
                        warnings += it.warnings
                        activatedLiveMutations = it.acceptedCount
                        catalogActivated = true
                    }.acceptedCount
                }
                is CatalogStrategyResult.EmptyValid -> {
                    preservedActiveCatalog = true
                    val existingChannelCount = channelDao.getCount(provider.id).first()
                    warnings += liveSyncResult.warnings + liveResult.warnings + if (existingChannelCount == 0) {
                        "Live TV provider exposed no live channels; continuing with VOD and series only."
                    } else {
                        "Live TV refresh returned an empty valid catalog; keeping previous channel library."
                    }
                    existingChannelCount
                }
                is CatalogStrategyResult.Failure -> {
                    preservedActiveCatalog = true
                    val existingChannelCount = channelDao.getCount(provider.id).first()
                    warnings += liveSyncResult.warnings + liveResult.warnings + if (existingChannelCount == 0) {
                        "Live TV could not be fetched; continuing with VOD and series only."
                    } else {
                        "Live TV sync degraded; keeping previous channel library."
                    }
                    existingChannelCount
                }
            }

            metadata = metadata.copy(
                lastLiveSync = now,
                lastLiveSuccess = now,
                liveCount = acceptedCount,
                liveAvoidFullUntil = liveAvoidFullUntil,
                liveSequentialFailuresRemembered = liveProviderAdaptation.rememberSequential,
                liveHealthySyncStreak = liveProviderAdaptation.healthyStreak
            )
            syncMetadataRepository.updateMetadata(metadata)
            acceptedCount
        }
        val liveCount = liveOutcome.getOrElse { error ->
            if (trackInitialLiveOnboarding) {
                recordOnboardingState(
                    provider = provider,
                    phase = XTREAM_ONBOARDING_PHASE_FAILED,
                    lastError = sanitizeThrowableMessage(error),
                    runtimeProfile = runtimeProfile
                )
            }
            updateIndexJob(
                XtreamIndexJobUpdate(
                    providerId = provider.id,
                    section = ContentType.LIVE.name,
                    state = indexFailureState(error),
                    now = now,
                    lastAttemptAt = now,
                    lastError = sanitizeThrowableMessage(error)
                )
            )
            throw error
        }
        updateIndexJob(
            XtreamIndexJobUpdate(
                providerId = provider.id,
                section = ContentType.LIVE.name,
                state = "QUEUED",
                now = now,
                totalCategories = 1,
                completedCategories = 0,
                indexedRows = liveCount,
                lastAttemptAt = now,
                lastError = null
            )
        )
        continuationWork += SyncContinuation(
            operation = SyncContinuationOperation.INDEX_CATALOG,
            section = ContentType.LIVE,
            reason = "activated live catalog requires durable search-index backfill"
        )
        emitProgress(
            provider.id,
            SyncProgress(
                section = Section.VOD,
                current = 0,
                total = 0,
                currentLabel = "",
                itemsIndexed = liveCount
            )
        )
        val movieCategoryCount = sectionExecutor.syncCategoryShell(
            provider = provider,
            api = api,
            contentType = ContentType.MOVIE,
            label = "Movies",
            now = now,
            onProgress = onProgress
        ).getOrElse { error ->
            warnings += "Movies categories could not be loaded; movie indexing will retry later."
            updateIndexJob(
                XtreamIndexJobUpdate(
                    providerId = provider.id,
                    section = ContentType.MOVIE.name,
                    state = indexFailureState(error),
                    now = now,
                    lastAttemptAt = now,
                    lastError = sanitizeThrowableMessage(error)
                )
            )
            0
        }
        if (movieCategoryCount > 0) {
            catalogActivated = true
            continuationWork += SyncContinuation(
                operation = SyncContinuationOperation.INDEX_CATALOG,
                section = ContentType.MOVIE,
                reason = "movie category shell is committed; durable item indexing is queued"
            )
        }
        emitProgress(
            provider.id,
            SyncProgress(
                section = Section.SERIES,
                current = 0,
                total = 0,
                currentLabel = "",
                itemsIndexed = liveCount
            )
        )
        val seriesCategoryCount = sectionExecutor.syncCategoryShell(
            provider = provider,
            api = api,
            contentType = ContentType.SERIES,
            label = "Series",
            now = now,
            onProgress = onProgress
        ).getOrElse { error ->
            warnings += "Series categories could not be loaded; series indexing will retry later."
            updateIndexJob(
                XtreamIndexJobUpdate(
                    providerId = provider.id,
                    section = ContentType.SERIES.name,
                    state = indexFailureState(error),
                    now = now,
                    lastAttemptAt = now,
                    lastError = sanitizeThrowableMessage(error)
                )
            )
            0
        }
        if (seriesCategoryCount > 0) {
            catalogActivated = true
            continuationWork += SyncContinuation(
                operation = SyncContinuationOperation.INDEX_CATALOG,
                section = ContentType.SERIES,
                reason = "series category shell is committed; durable item indexing is queued"
            )
        }

        if (trackInitialLiveOnboarding) {
            val completedAt = System.currentTimeMillis()
            if (liveCount > 0 || movieCategoryCount > 0 || seriesCategoryCount > 0) {
                recordOnboardingState(
                    provider = provider,
                    phase = XTREAM_ONBOARDING_PHASE_COMPLETED,
                    now = completedAt,
                    acceptedRowCount = liveCount,
                    stagedFlushCount = stagedFlushCountFor(liveCount),
                    clearError = true,
                    completedAt = completedAt,
                    clearStagedSession = true,
                    runtimeProfile = runtimeProfile
                )
            } else {
                recordOnboardingState(
                    provider = provider,
                    phase = XTREAM_ONBOARDING_PHASE_FAILED,
                    acceptedRowCount = liveCount,
                    lastError = "Live TV did not finish with any committed channels.",
                    clearStagedSession = true,
                    runtimeProfile = runtimeProfile
                )
            }
        }

        metadata = metadata.copy(
            lastMovieAttempt = if (movieCategoryCount > 0) now else metadata.lastMovieAttempt,
            movieCatalogStale = true,
            movieSyncMode = VodSyncMode.UNKNOWN
        )
        syncMetadataRepository.updateMetadata(metadata)
        val epgState = if (provider.epgSyncMode == ProviderEpgSyncMode.SKIP) "IDLE" else "QUEUED"
        updateIndexJob(
            XtreamIndexJobUpdate(
                providerId = provider.id,
                section = "EPG",
                state = epgState,
                now = now,
                lastAttemptAt = if (epgState == "QUEUED") now else 0L
            )
        )
        if (provider.epgSyncMode != ProviderEpgSyncMode.SKIP) {
            continuationWork += SyncContinuation(
                operation = SyncContinuationOperation.REFRESH_GUIDE,
                reason = "guide refresh must be handed off to background work",
                force = force
            )
        }
        if (force) {
            Log.i(TAG, "Xtream index-first sync completed for provider ${provider.id}; VOD and series index jobs are queued.")
        }
        return SyncOutcome(
            partial = warnings.isNotEmpty(),
            warnings = warnings.distinct(),
            stagedMutations = activatedLiveMutations,
            continuationWork = continuationWork,
            activation = if (catalogActivated) {
                SyncActivation.ACTIVATED_CATALOG
            } else if (preservedActiveCatalog) {
                SyncActivation.PRESERVED_ACTIVE_CATALOG
            } else {
                SyncActivation.NO_CATALOG_CHANGE
            }
        )
    }

    suspend fun markInitialOnboardingFailure(provider: Provider, error: Throwable) {
        recordOnboardingState(
            provider = provider,
            phase = XTREAM_ONBOARDING_PHASE_FAILED,
            lastError = sanitizeThrowableMessage(error)
        )
    }

    suspend fun syncLive(
        provider: Provider,
        syncReason: XtreamLiveSyncReason,
        onProgress: ((String) -> Unit)?
    ): SyncOutcome {
        val now = System.currentTimeMillis()
        val sectionWarnings = mutableListOf<String>()
        progress(provider.id, onProgress, "Retrying Live TV...")
        val useTextClassification = preferencesRepository.useXtreamTextClassification.first()
        val enableBase64TextCompatibility = preferencesRepository.xtreamBase64TextCompatibility.first()
        val hiddenLiveCategoryIds = preferencesRepository.getHiddenCategoryIds(provider.id, ContentType.LIVE).first()
        val requiredHiddenLiveCategoryIds = requiredHiddenCategoryIds(provider.id, ContentType.LIVE)
        val api = createProvider(provider, useTextClassification, enableBase64TextCompatibility)
        val currentMetadata = syncMetadataRepository.getMetadata(provider.id) ?: SyncMetadata(provider.id)
        val runtimeProfile = CatalogSyncRuntimeProfile.from(applicationContext)
        var activatedMutations = 0
        val liveSyncResult = syncLiveCatalog(
            provider,
            api,
            currentMetadata,
            hiddenLiveCategoryIds,
            requiredHiddenLiveCategoryIds,
            onProgress,
            runtimeProfile,
            trackInitialLiveOnboarding = false,
            syncReason = syncReason
        )
        val liveSequentialStress = liveSyncResult.strategyFeedback.segmentedStressDetected
        val liveProviderAdaptation = updateSequentialProviderAdaptation(
            previousRemembered = currentMetadata.liveSequentialFailuresRemembered,
            previousHealthyStreak = currentMetadata.liveHealthySyncStreak,
            sawSequentialStress = liveSequentialStress
        )
        val liveAvoidFullUntil = catalogStrategySupport.updateAvoidFullUntil(
            previousAvoidFullUntil = currentMetadata.liveAvoidFullUntil,
            now = now,
            feedback = liveSyncResult.strategyFeedback
        )
        when (val liveResult = liveSyncResult.catalogResult) {
            is CatalogStrategyResult.Success -> {
                val acceptedCount = finalizeLiveCatalog(
                    provider.id,
                    liveSyncResult,
                    hiddenLiveCategoryIds,
                    onProgress
                ).acceptedCount
                activatedMutations = acceptedCount
                syncMetadataRepository.updateMetadata(
                    currentMetadata.copy(
                        lastLiveSync = now,
                        lastLiveSuccess = now,
                        liveCount = acceptedCount,
                        liveAvoidFullUntil = liveAvoidFullUntil,
                        liveSequentialFailuresRemembered = liveProviderAdaptation.rememberSequential,
                        liveHealthySyncStreak = liveProviderAdaptation.healthyStreak
                    )
                )
            }
            is CatalogStrategyResult.Partial -> {
                val commitResult = finalizeLiveCatalog(
                    provider.id,
                    liveSyncResult,
                    hiddenLiveCategoryIds,
                    onProgress,
                    partialCompletionWarning = "Live TV retry completed partially."
                )
                activatedMutations = commitResult.acceptedCount
                syncMetadataRepository.updateMetadata(
                    currentMetadata.copy(
                        lastLiveSync = now,
                        liveCount = commitResult.acceptedCount,
                        liveAvoidFullUntil = liveAvoidFullUntil,
                        liveSequentialFailuresRemembered = currentMetadata.liveSequentialFailuresRemembered || liveSequentialStress,
                        liveHealthySyncStreak = 0
                    )
                )
                sectionWarnings += commitResult.warnings
            }
            is CatalogStrategyResult.EmptyValid -> {
                val existingLiveCount = channelDao.getCount(provider.id).first()
                syncMetadataRepository.updateMetadata(
                    currentMetadata.copy(
                        liveAvoidFullUntil = liveAvoidFullUntil,
                        liveSequentialFailuresRemembered = currentMetadata.liveSequentialFailuresRemembered || liveSequentialStress,
                        liveHealthySyncStreak = 0
                    )
                )
                throw IllegalStateException(
                    if (existingLiveCount > 0) {
                        "Live TV refresh returned an empty catalog; existing library was preserved."
                    } else {
                        "Live TV catalog was empty."
                    }
                )
            }
            is CatalogStrategyResult.Failure -> {
                syncMetadataRepository.updateMetadata(
                    currentMetadata.copy(
                        liveAvoidFullUntil = liveAvoidFullUntil,
                        liveSequentialFailuresRemembered = currentMetadata.liveSequentialFailuresRemembered ||
                            liveSequentialStress || shouldRememberSequentialPreference(liveResult.error),
                        liveHealthySyncStreak = 0
                    )
                )
                throw IllegalStateException(
                    userMessage(liveResult.error, "Failed to fetch live channels"),
                    liveResult.error
                )
            }
        }
        updateIndexJob(
            XtreamIndexJobUpdate(
                providerId = provider.id,
                section = ContentType.LIVE.name,
                state = "QUEUED",
                now = now,
                totalCategories = 1,
                completedCategories = 0,
                indexedRows = activatedMutations,
                lastAttemptAt = now,
                lastError = null
            )
        )
        return SyncOutcome(
            partial = sectionWarnings.isNotEmpty(),
            warnings = sectionWarnings,
            stagedMutations = activatedMutations,
            continuationWork = listOf(
                SyncContinuation(
                    operation = SyncContinuationOperation.INDEX_CATALOG,
                    section = ContentType.LIVE,
                    reason = "activated live repair requires durable search-index backfill"
                )
            ),
            activation = SyncActivation.ACTIVATED_CATALOG
        )
    }

    private suspend fun syncLiveCatalog(
        provider: Provider,
        api: XtreamProvider,
        existingMetadata: SyncMetadata,
        hiddenLiveCategoryIds: Set<Long>,
        requiredHiddenLiveCategoryIds: Set<Long>,
        onProgress: ((String) -> Unit)?,
        runtimeProfile: CatalogSyncRuntimeProfile,
        trackInitialLiveOnboarding: Boolean,
        syncReason: XtreamLiveSyncReason
    ): CatalogSyncPayload<Channel> {
        emitProgress(
            provider.id,
            SyncProgress(Section.LIVE, current = 0, total = 0, currentLabel = "", itemsIndexed = 0)
        )
        val effectiveMethod = XtreamLiveSyncPolicy.resolve(
            userMode = provider.xtreamLiveSyncMode,
            runtimeProfile = runtimeProfile,
            syncReason = syncReason,
            metadata = existingMetadata,
            now = System.currentTimeMillis(),
            hiddenLiveCategoryIds = hiddenLiveCategoryIds
        )
        Log.i(
            TAG,
            "Xtream live sync method for provider ${provider.id}: user=${provider.xtreamLiveSyncMode} " +
                "effective=$effectiveMethod reason=$syncReason profile=${runtimeProfile.diagnosticsLabel}."
        )
        return liveStrategy.syncXtreamLiveCatalog(
            provider,
            api,
            existingMetadata,
            hiddenLiveCategoryIds,
            onProgress,
            runtimeProfile,
            trackInitialLiveOnboarding,
            effectiveMethod,
            requiredHiddenLiveCategoryIds
        )
    }

    private suspend fun recordCommitPhaseIfNeeded(
        provider: Provider,
        payload: CatalogSyncPayload<Channel>,
        strategyName: String,
        runtimeProfile: CatalogSyncRuntimeProfile,
        trackInitialLiveOnboarding: Boolean
    ) {
        if (!trackInitialLiveOnboarding) return
        recordOnboardingState(
            provider = provider,
            phase = XTREAM_ONBOARDING_PHASE_COMMITTING,
            now = System.currentTimeMillis(),
            stagedSessionId = payload.stagedSessionId,
            importStrategy = strategyName,
            acceptedRowCount = payload.stagedAcceptedCount,
            stagedFlushCount = stagedFlushCountFor(payload.stagedAcceptedCount),
            clearError = true,
            runtimeProfile = runtimeProfile,
            syncProfileStrategy = payload.profileStrategyName(runtimeProfile, trackInitialLiveOnboarding)
        )
    }

    private suspend fun recoverOnboardingSession(
        provider: Provider,
        hiddenLiveCategoryIds: Set<Long>,
        afterCatalogApply: suspend () -> Unit
    ): XtreamLiveCommitResult? {
        val state = xtreamLiveOnboardingDao.getIncompleteByProvider(provider.id) ?: return null
        val sessionId = state.stagedSessionId ?: return null
        if (state.providerType != ProviderType.XTREAM_CODES.name || state.contentType != ContentType.LIVE.name) {
            discardOnboardingSession(provider, sessionId, "Saved Live TV import did not match this provider.")
            return null
        }
        if (state.importStrategy != null && state.importStrategy != "full") {
            discardOnboardingSession(provider, sessionId, "Saved Live TV import strategy could not be resumed.")
            return null
        }
        val stagedState = syncCatalogStore.stagedLiveImportState(provider.id, sessionId)
        val discardReason = when {
            stagedState.channelCount <= 0 -> "Saved Live TV import was missing staged channels."
            stagedState.movieCount > 0 || stagedState.seriesCount > 0 -> "Saved Live TV import contained rows for another catalog type."
            else -> null
        }
        if (discardReason != null) {
            discardOnboardingSession(provider, sessionId, discardReason)
            return null
        }
        recordOnboardingState(
            provider,
            XTREAM_ONBOARDING_PHASE_RECOVERING,
            stagedSessionId = sessionId,
            importStrategy = state.importStrategy ?: "full",
            acceptedRowCount = stagedState.channelCount,
            stagedFlushCount = stagedFlushCountFor(stagedState.channelCount),
            clearError = true
        )
        if (hiddenLiveCategoryIds.isNotEmpty()) {
            mergeHiddenChannelsIntoStaging(provider.id, sessionId, hiddenLiveCategoryIds)
        }
        val commitState = if (hiddenLiveCategoryIds.isNotEmpty()) {
            syncCatalogStore.stagedLiveImportState(provider.id, sessionId)
        } else {
            stagedState
        }
        recordOnboardingState(
            provider,
            XTREAM_ONBOARDING_PHASE_COMMITTING,
            stagedSessionId = sessionId,
            importStrategy = state.importStrategy ?: "full",
            acceptedRowCount = stagedState.channelCount,
            stagedFlushCount = stagedFlushCountFor(stagedState.channelCount),
            clearError = true
        )
        syncCatalogStore.applyStagedLiveCatalog(
            providerId = provider.id,
            sessionId = sessionId,
            categories = commitState.categories.takeIf { it.isNotEmpty() },
            afterCatalogApply = afterCatalogApply
        )
        return XtreamLiveCommitResult(stagedState.channelCount, listOf("Live TV import resumed from saved staged session."))
    }

    private suspend fun discardOnboardingSession(provider: Provider, sessionId: Long, reason: String) {
        Log.w(TAG, "Discarding saved Xtream Live onboarding session for provider ${provider.id}: $reason")
        syncCatalogStore.discardStagedImport(provider.id, sessionId)
        recordOnboardingState(
            provider = provider,
            phase = XTREAM_ONBOARDING_PHASE_STARTING,
            lastError = reason,
            clearStagedSession = true
        )
    }

    private suspend fun recordOnboardingState(
        provider: Provider,
        phase: String,
        now: Long = System.currentTimeMillis(),
        stagedSessionId: Long? = null,
        importStrategy: String? = null,
        nextCategoryIndex: Int? = null,
        acceptedRowCount: Int? = null,
        stagedFlushCount: Int? = null,
        lastError: String? = null,
        clearError: Boolean = false,
        completedAt: Long? = null,
        clearStagedSession: Boolean = false,
        runtimeProfile: CatalogSyncRuntimeProfile? = null,
        syncProfileStrategy: String? = null
    ) {
        val existing = xtreamLiveOnboardingDao.getByProvider(provider.id)
        xtreamLiveOnboardingDao.upsert(
            XtreamLiveOnboardingStateEntity(
                providerId = provider.id,
                providerType = provider.type.name,
                contentType = ContentType.LIVE.name,
                phase = phase,
                stagedSessionId = if (clearStagedSession) null else stagedSessionId ?: existing?.stagedSessionId,
                importStrategy = importStrategy ?: existing?.importStrategy,
                nextCategoryIndex = nextCategoryIndex ?: existing?.nextCategoryIndex ?: 0,
                acceptedRowCount = acceptedRowCount ?: existing?.acceptedRowCount ?: 0,
                stagedFlushCount = stagedFlushCount ?: existing?.stagedFlushCount ?: 0,
                syncProfileTier = runtimeProfile?.tier?.name ?: existing?.syncProfileTier,
                syncProfileBatchSize = runtimeProfile?.stageBatchSize ?: existing?.syncProfileBatchSize ?: 0,
                syncProfileStrategy = syncProfileStrategy ?: existing?.syncProfileStrategy,
                syncProfileLowMemory = runtimeProfile?.snapshot?.isCurrentlyLowOnMemory ?: existing?.syncProfileLowMemory ?: false,
                syncProfileMemoryClassMb = runtimeProfile?.snapshot?.memoryClassMb ?: existing?.syncProfileMemoryClassMb ?: 0,
                syncProfileAvailableMemMb = runtimeProfile?.snapshot?.availableMemMb ?: existing?.syncProfileAvailableMemMb ?: 0L,
                lastError = when {
                    clearError -> null
                    lastError != null -> lastError
                    else -> existing?.lastError
                },
                createdAt = existing?.createdAt?.takeIf { it > 0L } ?: now,
                updatedAt = now,
                completedAt = completedAt
            )
        )
    }

    private suspend fun finalizeLiveCatalog(
        providerId: Long,
        liveSyncResult: CatalogSyncPayload<Channel>,
        hiddenLiveCategoryIds: Set<Long>,
        onProgress: ((String) -> Unit)?,
        partialCompletionWarning: String? = null,
        afterCatalogApply: suspend () -> Unit = {}
    ): XtreamLiveCommitResult {
        progress(providerId, onProgress, "Saving Live TV channels...")
        val warnings = buildList {
            addAll(liveSyncResult.warnings)
            addAll(catalogStrategySupport.strategyWarnings(liveSyncResult.catalogResult))
            partialCompletionWarning?.let(::add)
        }
        val acceptedCount = when (val liveResult = liveSyncResult.catalogResult) {
            is CatalogStrategyResult.Success -> {
                liveSyncResult.stagedSessionId?.let { sessionId ->
                    val mergedCategories = mergeVisibleLiveCategoriesWithHiddenStoredContent(
                        providerId,
                        liveSyncResult.categories,
                        hiddenLiveCategoryIds
                    )
                    if (hiddenLiveCategoryIds.isNotEmpty()) mergeHiddenChannelsIntoStaging(providerId, sessionId, hiddenLiveCategoryIds)
                    syncCatalogStore.applyStagedLiveCatalog(providerId, sessionId, mergedCategories, afterCatalogApply)
                    liveSyncResult.stagedAcceptedCount
                } ?: run {
                    val liveCatalog = mergeVisibleLiveSyncWithHiddenStoredContent(
                        providerId,
                        liveSyncResult.categories,
                        liveResult.items.map { it.toEntity() },
                        hiddenLiveCategoryIds
                    )
                    syncCatalogStore.replaceLiveCatalog(providerId, liveCatalog.categories, liveCatalog.channels, afterCatalogApply)
                }
            }
            is CatalogStrategyResult.Partial -> {
                liveSyncResult.stagedSessionId?.let { sessionId ->
                    val mergedCategories = mergeVisibleLiveCategoriesWithHiddenStoredContent(
                        providerId,
                        liveSyncResult.categories,
                        hiddenLiveCategoryIds
                    )
                    if (hiddenLiveCategoryIds.isNotEmpty()) mergeHiddenChannelsIntoStaging(providerId, sessionId, hiddenLiveCategoryIds)
                    syncCatalogStore.applyStagedLiveCatalogUpsertOnly(providerId, sessionId, mergedCategories, afterCatalogApply)
                    liveSyncResult.stagedAcceptedCount
                } ?: run {
                    val liveCatalog = mergeVisibleLiveSyncWithHiddenStoredContent(
                        providerId,
                        liveSyncResult.categories,
                        liveResult.items.map { it.toEntity() },
                        hiddenLiveCategoryIds
                    )
                    syncCatalogStore.upsertLiveCatalog(providerId, liveCatalog.categories, liveCatalog.channels, afterCatalogApply)
                }
            }
            is CatalogStrategyResult.EmptyValid,
            is CatalogStrategyResult.Failure -> throw IllegalArgumentException(
                "finalizeLiveCatalog only supports success or partial results"
            )
        }
        return XtreamLiveCommitResult(acceptedCount, warnings)
    }

    private suspend fun mergeHiddenChannelsIntoStaging(
        providerId: Long,
        sessionId: Long,
        hiddenLiveCategoryIds: Set<Long>
    ) {
        val hiddenChannels = channelDao.getByProviderSync(providerId)
            .filter { channel -> channel.categoryId != null && channel.categoryId in hiddenLiveCategoryIds }
        if (hiddenChannels.isNotEmpty()) syncCatalogStore.stageChannelBatch(providerId, sessionId, hiddenChannels)
    }

    private suspend fun mergeVisibleLiveCategoriesWithHiddenStoredContent(
        providerId: Long,
        visibleCategories: List<CategoryEntity>?,
        hiddenLiveCategoryIds: Set<Long>
    ): List<CategoryEntity>? {
        if (hiddenLiveCategoryIds.isEmpty()) return visibleCategories
        return ((visibleCategories ?: emptyList()) + categoryDao.getByProviderAndTypeSync(providerId, ContentType.LIVE.name)
            .filter { it.categoryId in hiddenLiveCategoryIds })
            .distinctBy { it.categoryId to it.type }
            .sortedBy { it.categoryId }
            .takeIf { it.isNotEmpty() }
    }

    private suspend fun mergeVisibleLiveSyncWithHiddenStoredContent(
        providerId: Long,
        visibleCategories: List<CategoryEntity>?,
        visibleChannels: List<ChannelEntity>,
        hiddenLiveCategoryIds: Set<Long>
    ): LiveCatalogSnapshot {
        if (hiddenLiveCategoryIds.isEmpty()) return LiveCatalogSnapshot(visibleCategories, visibleChannels)
        val hiddenCategories = categoryDao.getByProviderAndTypeSync(providerId, ContentType.LIVE.name)
            .filter { it.categoryId in hiddenLiveCategoryIds }
        val hiddenChannels = channelDao.getByProviderSync(providerId)
            .filter { it.categoryId != null && it.categoryId in hiddenLiveCategoryIds }
        return LiveCatalogSnapshot(
            categories = ((visibleCategories ?: emptyList()) + hiddenCategories)
                .distinctBy { it.categoryId to it.type }
                .sortedBy { it.categoryId }
                .takeIf { it.isNotEmpty() },
            channels = (visibleChannels + hiddenChannels).distinctBy { it.streamId }.sortedBy { it.number }
        )
    }

    private fun updateSequentialProviderAdaptation(
        previousRemembered: Boolean,
        previousHealthyStreak: Int,
        sawSequentialStress: Boolean
    ): SequentialProviderAdaptation {
        if (sawSequentialStress) return SequentialProviderAdaptation(true, 0)
        if (!previousRemembered) return SequentialProviderAdaptation(false, 0)
        val nextHealthyStreak = (previousHealthyStreak + 1).coerceAtMost(2)
        return if (nextHealthyStreak >= 2) SequentialProviderAdaptation(false, 0)
        else SequentialProviderAdaptation(true, nextHealthyStreak)
    }

    private fun stagedFlushCountFor(acceptedCount: Int): Int =
        if (acceptedCount <= 0) 0 else (acceptedCount + XTREAM_FALLBACK_STAGE_BATCH_SIZE - 1) / XTREAM_FALLBACK_STAGE_BATCH_SIZE

    private fun CatalogStrategyResult<*>.strategyNameOrNull(): String? = strategyName

    private fun CatalogSyncPayload<Channel>.profileStrategyName(
        runtimeProfile: CatalogSyncRuntimeProfile,
        trackInitialLiveOnboarding: Boolean
    ): String {
        val fullPolicy = if (strategyFeedback.preferredSegmentedFirst) "segmented_first"
        else if (runtimeProfile.shouldAttemptFullLiveCatalog(trackInitialLiveOnboarding)) "full_allowed"
        else "full_blocked"
        return "${catalogResult.strategyNameOrNull() ?: "unknown"};$fullPolicy"
    }

    private data class XtreamLiveCommitResult(
        val acceptedCount: Int,
        val warnings: List<String>
    )

    private companion object {
        const val TAG = "XtreamCatalogSync"
        const val XTREAM_FALLBACK_STAGE_BATCH_SIZE = 500
        const val XTREAM_ONBOARDING_PHASE_STARTING = "STARTING"
        const val XTREAM_ONBOARDING_PHASE_FETCHING = "FETCHING"
        const val XTREAM_ONBOARDING_PHASE_RECOVERING = "RECOVERING"
        const val XTREAM_ONBOARDING_PHASE_STAGED = "STAGED"
        const val XTREAM_ONBOARDING_PHASE_COMMITTING = "COMMITTING"
        const val XTREAM_ONBOARDING_PHASE_COMPLETED = "COMPLETED"
        const val XTREAM_ONBOARDING_PHASE_FAILED = "FAILED"
    }
}
