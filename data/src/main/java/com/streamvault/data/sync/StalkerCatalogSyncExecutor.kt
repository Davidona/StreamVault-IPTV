package com.streamvault.data.sync

import android.content.Context
import android.util.Log
import com.streamvault.data.local.DatabaseTransactionRunner
import com.streamvault.data.local.dao.CategoryDao
import com.streamvault.data.local.dao.ChannelDao
import com.streamvault.data.local.dao.MovieCategoryHydrationDao
import com.streamvault.data.local.dao.MovieDao
import com.streamvault.data.local.dao.SeriesCategoryHydrationDao
import com.streamvault.data.local.dao.SeriesDao
import com.streamvault.data.local.dao.VodCategoryHydrationDao
import com.streamvault.data.local.dao.VodCatalogEntryDao
import com.streamvault.data.local.entity.CategoryEntity
import com.streamvault.data.mapper.toEntity
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.data.remote.dto.XtreamCategory
import com.streamvault.data.remote.stalker.StalkerApiError
import com.streamvault.data.remote.stalker.StalkerProvider
import com.streamvault.data.remote.stalker.StalkerProviderProfile
import com.streamvault.data.remote.stalker.StalkerTelemetry
import com.streamvault.data.provider.toProviderSnapshot
import com.streamvault.data.util.UrlSecurityPolicy
import com.streamvault.domain.model.CatalogLayout
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.LegacyProvider as Provider
import com.streamvault.domain.model.ProviderEpgSyncMode
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.StalkerCatalogMode
import com.streamvault.domain.model.SyncMetadata
import com.streamvault.domain.model.VodSyncMode
import com.streamvault.domain.repository.ProviderSnapshotRepository
import com.streamvault.domain.repository.SyncMetadataRepository
import com.streamvault.domain.sync.Section
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

private const val STALKER_EXECUTOR_TAG = "StalkerCatalogSync"
private const val STALKER_MAX_PARALLEL_CATEGORY_FETCHES = 2
private const val STALKER_BULK_LIVE_STALL_TIMEOUT_MILLIS = 30_000L
private const val STALKER_BULK_LIVE_STALL_CHECK_INTERVAL_MILLIS = 1_000L
private const val STALKER_BULK_LIVE_UNSUPPORTED_TTL_MILLIS = 6 * 60 * 60 * 1000L
private const val LIVE_CATEGORY_SEQUENTIAL_MODE_WARNING =
    "Live category sync downgraded to sequential mode after provider stress signals."
private const val FALLBACK_STAGE_BATCH_SIZE = 500

/** Owns Stalker authentication, full catalog orchestration, and Live repair execution. */
internal class StalkerCatalogSyncExecutor(
    private val applicationContext: Context,
    private val preferencesRepository: PreferencesRepository,
    private val syncMetadataRepository: SyncMetadataRepository,
    private val providerSnapshotRepository: ProviderSnapshotRepository?,
    private val transactionRunner: DatabaseTransactionRunner,
    private val categoryDao: CategoryDao,
    private val channelDao: ChannelDao,
    private val movieCategoryHydrationDao: MovieCategoryHydrationDao,
    private val seriesCategoryHydrationDao: SeriesCategoryHydrationDao,
    private val vodCategoryHydrationDao: VodCategoryHydrationDao,
    private val vodCatalogEntryDao: VodCatalogEntryDao,
    private val movieDao: MovieDao,
    private val seriesDao: SeriesDao,
    private val syncCatalogStore: SyncCatalogStore,
    private val catalogStager: SyncManagerCatalogStager,
    private val catalogStrategySupport: SyncManagerCatalogStrategySupport,
    private val categoryRecoverySupport: SyncManagerXtreamSupport,
    private val sectionExecutor: StalkerCatalogSectionExecutor,
    private val readinessTracker: StalkerReadinessTracker,
    private val createProvider: (Provider) -> StalkerProvider,
    private val syncProviderEpg: suspend (Provider, SyncMetadata, Long, Boolean, ((String) -> Unit)?) -> ProviderGuideSyncResult,
    private val progress: (Long, ((String) -> Unit)?, String) -> Unit,
    private val emitCatalogProgress: (Long, Section, Int?, Int?) -> Unit,
    private val categoryFailureWarning: (String, String, Throwable) -> String,
    private val sanitizeThrowableMessage: (Throwable?) -> String,
    private val requiredHiddenCategoryIds: suspend (Long, ContentType) -> Set<Long> = { _, _ -> emptySet() }
) {
    suspend fun syncFull(
        provider: Provider,
        force: Boolean,
        onProgress: ((String) -> Unit)?,
        afterCatalogApply: suspend () -> Unit = {},
        deferProviderStateUntilCatalogCommit: Boolean = false
    ): SyncOutcome {
        val warnings = mutableListOf<String>()
        val continuationWork = mutableListOf<SyncContinuation>()
        var activatedLiveMutations = 0
        var catalogActivated = false
        var preservedActiveCatalog = false
        readinessTracker.start(provider.id)
        UrlSecurityPolicy.validateStalkerPortalUrl(provider.serverUrl)?.let { message ->
            throw IllegalStateException(message)
        }
        emitCatalogProgress(provider.id, Section.LIVE, null, null)
        progress(provider.id, onProgress, "Connecting to portal...")
        val api = createProvider(provider)
        val authenticatedProvider = requireResult(api.authenticate(), "Failed to authenticate with portal")
        val effectiveCatalogLayout = authenticatedProvider.catalogLayout
            .takeUnless { it == CatalogLayout.UNKNOWN }
            ?: provider.catalogLayout
        val catalogLayoutChanged =
            authenticatedProvider.catalogLayout != CatalogLayout.UNKNOWN &&
                authenticatedProvider.catalogLayout != provider.catalogLayout
        val catalogLayoutDetectionChanged =
            catalogLayoutChanged ||
                authenticatedProvider.catalogLayoutDetectionVersion != provider.catalogLayoutDetectionVersion
        var catalogCommitCallback = afterCatalogApply
        if (catalogLayoutDetectionChanged) {
            suspend fun clearIncompatibleVodCatalog() {
                if (catalogLayoutChanged) {
                    categoryDao.deleteByProviderAndType(provider.id, ContentType.MOVIE.name)
                    categoryDao.deleteByProviderAndType(provider.id, ContentType.SERIES.name)
                    categoryDao.deleteByProviderAndType(provider.id, ContentType.VOD.name)
                    movieCategoryHydrationDao.deleteByProvider(provider.id)
                    seriesCategoryHydrationDao.deleteByProvider(provider.id)
                    vodCategoryHydrationDao.deleteByProvider(provider.id)
                    vodCatalogEntryDao.deleteByProvider(provider.id)
                }
            }

            if (deferProviderStateUntilCatalogCommit) {
                val originalCallback = catalogCommitCallback
                catalogCommitCallback = {
                    originalCallback()
                    clearIncompatibleVodCatalog()
                }
            } else {
                transactionRunner.inTransaction {
                    if (authenticatedProvider.catalogLayout != CatalogLayout.UNKNOWN) {
                        providerSnapshotRepository?.updateCatalogLayout(
                            provider.id,
                            authenticatedProvider.catalogLayout,
                            authenticatedProvider.catalogLayoutDetectionVersion
                        )
                    }
                    clearIncompatibleVodCatalog()
                }
            }
        }
        readinessTracker.authenticated(provider.id)

        var metadata = syncMetadataRepository.getMetadata(provider.id) ?: SyncMetadata(provider.id)
        val now = System.currentTimeMillis()
        var queuedMovieIndex = false
        var queuedSeriesIndex = false
        var liveCount = metadata.liveCount
        var movieCategoryCount = 0
        var seriesCategoryCount = 0

        if (force || ContentCachePolicy.shouldRefresh(metadata.lastLiveSuccess, ContentCachePolicy.CATALOG_TTL_MILLIS, now)) {
            progress(provider.id, onProgress, "Downloading Live TV...")
            val hiddenLiveCategoryIds = preferencesRepository.getHiddenCategoryIds(provider.id, ContentType.LIVE).first()
            val requiredHiddenLiveCategoryIds = requiredHiddenCategoryIds(provider.id, ContentType.LIVE)
            val liveCatalogResult = syncLiveCatalogStaged(
                api = api,
                provider = provider,
                hiddenLiveCategoryIds = hiddenLiveCategoryIds,
                requiredHiddenLiveCategoryIds = requiredHiddenLiveCategoryIds,
                onProgress = onProgress,
                afterCatalogApply = catalogCommitCallback
            )
            metadata = metadata.copy(
                lastLiveSync = now,
                lastLiveSuccess = now,
                liveCount = liveCatalogResult.acceptedCount
            )
            liveCount = liveCatalogResult.acceptedCount
            activatedLiveMutations = liveCatalogResult.acceptedCount
            if (liveCatalogResult.acceptedCount > 0) {
                catalogActivated = true
            } else {
                preservedActiveCatalog = true
            }
            syncMetadataRepository.updateMetadata(metadata)
            emitCatalogProgress(provider.id, Section.LIVE, null, liveCatalogResult.acceptedCount)
            warnings += liveCatalogResult.warnings
        }
        readinessTracker.liveReady(provider.id)

        if (force || catalogLayoutChanged || ContentCachePolicy.shouldRefresh(metadata.lastMovieSuccess, ContentCachePolicy.CATALOG_TTL_MILLIS, now)) {
            progress(provider.id, onProgress, "Preparing Movies...")
            val categories = when (val categoriesResult = if (effectiveCatalogLayout == CatalogLayout.UNIFIED_VOD) {
                api.getUnifiedVodCategories()
            } else {
                api.getVodCategories()
            }) {
                is Result.Success -> categoriesResult.data
                is Result.Error -> {
                    val diagnostic = if (liveCount == 0 && warnings.any(::isStalkerEmptyResponse)) {
                        stalkerCatalogAccessDiagnostic(api, categoriesResult.message, "empty response")
                    } else null
                    throw IllegalStateException(
                        diagnostic ?: "Failed to load movie categories: ${categoriesResult.message}",
                        categoriesResult.exception
                    )
                }
                is Result.Loading -> throw IllegalStateException("Unexpected loading state")
            }
            emitCatalogProgress(provider.id, Section.VOD, categories.size, metadata.liveCount)
            val storedCategoryType = if (effectiveCatalogLayout == CatalogLayout.UNIFIED_VOD) ContentType.VOD else ContentType.MOVIE
            syncCatalogStore.replaceCategories(
                providerId = provider.id,
                type = storedCategoryType.name,
                categories = categories.mapIndexed { index, category ->
                    CategoryEntity(
                        providerId = provider.id,
                        categoryId = category.id,
                        name = category.name,
                        parentId = category.parentId,
                        type = storedCategoryType,
                        providerOrder = index,
                        isAdult = category.isAdult
                    )
                }
            )
            catalogActivated = true
            if (effectiveCatalogLayout == CatalogLayout.SPLIT) {
                movieCategoryHydrationDao.deleteByProvider(provider.id)
            }
            categoryDao.deleteByProviderAndType(
                provider.id,
                if (storedCategoryType == ContentType.VOD) ContentType.MOVIE.name else ContentType.VOD.name
            )
            movieCategoryCount = categories.size
            if (effectiveCatalogLayout != CatalogLayout.UNIFIED_VOD && provider.stalkerCatalogMode == StalkerCatalogMode.BACKGROUND_INDEX) {
                sectionExecutor.queueIndexSection(provider.id, ContentType.MOVIE, categories.size, now)
            }
            metadata = metadata.copy(
                lastMovieSync = now,
                lastMovieAttempt = now,
                movieCount = movieDao.getCount(provider.id).first(),
                movieSyncMode = VodSyncMode.PAGED,
                movieWarningsCount = 0,
                movieCatalogStale = true
            )
            syncMetadataRepository.updateMetadata(metadata)
            queuedMovieIndex = provider.stalkerCatalogMode == StalkerCatalogMode.BACKGROUND_INDEX
        }

        if (effectiveCatalogLayout == CatalogLayout.UNIFIED_VOD) {
            categoryDao.deleteByProviderAndType(provider.id, ContentType.SERIES.name)
            seriesCategoryHydrationDao.deleteByProvider(provider.id)
        } else if (force || catalogLayoutChanged || ContentCachePolicy.shouldRefresh(metadata.lastSeriesSuccess, ContentCachePolicy.CATALOG_TTL_MILLIS, now)) {
            progress(provider.id, onProgress, "Preparing Series...")
            val categories = when (val categoriesResult = api.getSeriesCategories()) {
                is Result.Success -> categoriesResult.data
                is Result.Error -> {
                    val diagnostic = if (liveCount == 0 && warnings.any(::isStalkerEmptyResponse)) {
                        stalkerCatalogAccessDiagnostic(api, categoriesResult.message, "empty response")
                    } else null
                    throw IllegalStateException(
                        diagnostic ?: "Failed to load series categories: ${categoriesResult.message}",
                        categoriesResult.exception
                    )
                }
                is Result.Loading -> throw IllegalStateException("Unexpected loading state")
            }
            emitCatalogProgress(provider.id, Section.SERIES, categories.size, metadata.liveCount)
            syncCatalogStore.replaceCategories(
                providerId = provider.id,
                type = ContentType.SERIES.name,
                categories = categories.mapIndexed { index, category ->
                    CategoryEntity(
                        providerId = provider.id,
                        categoryId = category.id,
                        name = category.name,
                        parentId = category.parentId,
                        type = ContentType.SERIES,
                        providerOrder = index,
                        isAdult = category.isAdult
                    )
                }
            )
            catalogActivated = true
            seriesCategoryCount = categories.size
            if (provider.stalkerCatalogMode == StalkerCatalogMode.BACKGROUND_INDEX) {
                sectionExecutor.queueIndexSection(provider.id, ContentType.SERIES, categories.size, now)
            }
            metadata = metadata.copy(
                lastSeriesSync = now,
                seriesCount = seriesDao.getCount(provider.id).first()
            )
            syncMetadataRepository.updateMetadata(metadata)
            queuedSeriesIndex = provider.stalkerCatalogMode == StalkerCatalogMode.BACKGROUND_INDEX
        }

        if (liveCount == 0 && movieCategoryCount == 0 && seriesCategoryCount == 0) {
            val diagnostic = warnings.firstOrNull { it.contains("no accessible catalog data", ignoreCase = true) }
                ?: stalkerCatalogAccessDiagnostic(api, "empty response", "empty response")
            diagnostic?.let { throw IllegalStateException(it) }
        }
        readinessTracker.categoriesReady(provider.id)

        if (queuedMovieIndex) {
            continuationWork += SyncContinuation(
                operation = SyncContinuationOperation.INDEX_CATALOG,
                section = ContentType.MOVIE,
                reason = "movie category shell is committed; durable item indexing is queued",
                force = force
            )
        }
        if (queuedSeriesIndex) {
            continuationWork += SyncContinuation(
                operation = SyncContinuationOperation.INDEX_CATALOG,
                section = ContentType.SERIES,
                reason = "series category shell is committed; durable item indexing is queued",
                force = force
            )
        }
        when (provider.epgSyncMode) {
            ProviderEpgSyncMode.UPFRONT -> warnings += syncProviderEpg(
                provider,
                metadata,
                now,
                force,
                onProgress
            ).warnings
            ProviderEpgSyncMode.BACKGROUND -> {
                continuationWork += SyncContinuation(
                    operation = SyncContinuationOperation.REFRESH_GUIDE,
                    reason = "guide refresh was handed off to background work",
                    force = force
                )
            }
            ProviderEpgSyncMode.SKIP -> Unit
        }
        readinessTracker.ready(provider.id, warnings.size)
        return SyncOutcome(
            partial = warnings.isNotEmpty(),
            warnings = warnings.distinct(),
            stagedMutations = activatedLiveMutations,
            continuationWork = continuationWork,
            activation = when {
                catalogActivated -> SyncActivation.ACTIVATED_CATALOG
                preservedActiveCatalog -> SyncActivation.PRESERVED_ACTIVE_CATALOG
                else -> SyncActivation.NO_CATALOG_CHANGE
            }
        )
    }

    suspend fun syncLive(
        provider: Provider,
        onProgress: ((String) -> Unit)?
    ): SyncOutcome {
        val now = System.currentTimeMillis()
        val warnings = mutableListOf<String>()
        emitCatalogProgress(provider.id, Section.LIVE, null, null)
        progress(provider.id, onProgress, "Retrying Live TV...")
        val api = createProvider(provider)
        val hiddenLiveCategoryIds = preferencesRepository.getHiddenCategoryIds(provider.id, ContentType.LIVE).first()
        val requiredHiddenLiveCategoryIds = requiredHiddenCategoryIds(provider.id, ContentType.LIVE)
        val liveCatalogResult = syncLiveCatalogStaged(
            api,
            provider,
            hiddenLiveCategoryIds,
            requiredHiddenLiveCategoryIds,
            onProgress
        )
        val metadata = (syncMetadataRepository.getMetadata(provider.id) ?: SyncMetadata(provider.id)).copy(
            lastLiveSync = now,
            lastLiveSuccess = now,
            liveCount = liveCatalogResult.acceptedCount
        )
        syncMetadataRepository.updateMetadata(metadata)
        emitCatalogProgress(provider.id, Section.LIVE, null, liveCatalogResult.acceptedCount)
        warnings += liveCatalogResult.warnings
        return SyncOutcome(
            partial = warnings.isNotEmpty(),
            warnings = warnings.distinct(),
            stagedMutations = liveCatalogResult.acceptedCount,
            activation = if (liveCatalogResult.acceptedCount > 0) {
                SyncActivation.ACTIVATED_CATALOG
            } else {
                SyncActivation.PRESERVED_ACTIVE_CATALOG
            }
        )
    }

    private suspend fun syncLiveCatalogStaged(
        api: StalkerProvider,
        provider: Provider,
        hiddenLiveCategoryIds: Set<Long>,
        requiredHiddenLiveCategoryIds: Set<Long>,
        onProgress: ((String) -> Unit)?,
        afterCatalogApply: suspend () -> Unit = {}
    ): StagedStalkerLiveCatalogResult {
        val warnings = mutableListOf<String>()
        var categoriesErrorMessage: String? = null
        val preferredCategories = when (val categoriesResult = api.getLiveCategories()) {
            is Result.Success -> categoriesResult.data
                .map { it.toEntity(provider.id) }
                .filterNot { it.categoryId in hiddenLiveCategoryIds && it.categoryId !in requiredHiddenLiveCategoryIds }
            is Result.Error -> {
                Log.w(
                    STALKER_EXECUTOR_TAG,
                    "Stalker live categories failed for provider ${provider.id}; streaming bulk live channels with fallback categories: ${categoriesResult.message}",
                    categoriesResult.exception
                )
                warnings += "Live categories failed; recovered using bulk live channels."
                categoriesErrorMessage = categoriesResult.message
                null
            }
            is Result.Loading -> throw IllegalStateException("Unexpected loading state")
        }

        progress(provider.id, onProgress, "Loading live channels...")
        val fallbackCollector = FallbackCategoryCollector(provider.id, ContentType.LIVE)
        val seenStreamIds = HashSet<Long>()
        val batch = ArrayList<Channel>(FALLBACK_STAGE_BATCH_SIZE)
        var stagedSessionId: Long? = null
        var acceptedCount = 0
        var bulkRowsWithResolvedCategories = 0

        suspend fun flushBatch() {
            if (batch.isEmpty()) return
            val staged = catalogStager.stageChannelItems(
                providerId = provider.id,
                items = batch,
                seenStreamIds = seenStreamIds,
                fallbackCollector = fallbackCollector,
                sessionId = stagedSessionId
            )
            stagedSessionId = staged.sessionId
            acceptedCount += staged.acceptedCount
            batch.clear()
        }

        suspend fun finalizeStagedImport(): StagedStalkerLiveCatalogResult {
            stagedSessionId?.let { sessionId ->
                if (hiddenLiveCategoryIds.isNotEmpty()) {
                    mergeHiddenChannelsIntoStaging(provider.id, sessionId, hiddenLiveCategoryIds)
                }
            }
            val hiddenCategories = if (hiddenLiveCategoryIds.isNotEmpty()) {
                categoryDao.getByProviderAndTypeSync(provider.id, ContentType.LIVE.name)
                    .filter { it.categoryId in hiddenLiveCategoryIds }
            } else emptyList()
            val categories = (
                catalogStrategySupport.mergePreferredAndFallbackCategories(
                    preferredCategories,
                    fallbackCollector.entities().takeIf { it.isNotEmpty() }
                ).orEmpty() + hiddenCategories
            ).distinctBy { it.categoryId to it.type }.takeIf { it.isNotEmpty() }
            val sessionId = stagedSessionId
            if (acceptedCount == 0 || sessionId == null) {
                sessionId?.let { syncCatalogStore.discardStagedImport(provider.id, it) }
                return StagedStalkerLiveCatalogResult(
                    acceptedCount = 0,
                    warnings = (warnings + "Live TV returned no usable channels; keeping the last verified Live catalog.").distinct()
                )
            }
            syncCatalogStore.applyStagedLiveCatalog(provider.id, sessionId, categories, afterCatalogApply)
            return StagedStalkerLiveCatalogResult(acceptedCount, warnings)
        }

        try {
            var bulkFailure: Exception? = null
            val learnedState = api.validatedPortalState()
            val bulkUnsupportedVerdictFresh = learnedState?.bulkLiveSupported == false &&
                learnedState.validatedAt > 0L &&
                System.currentTimeMillis() - learnedState.validatedAt <= STALKER_BULK_LIVE_UNSUPPORTED_TTL_MILLIS
            val shouldTryBulk = (!bulkUnsupportedVerdictFresh && learnedState?.bulkLiveCategoryFidelity != false) ||
                preferredCategories.isNullOrEmpty()
            StalkerTelemetry.strategySelected(
                provider.id,
                if (shouldTryBulk) "BULK_LIVE" else "CATEGORY_LIVE",
                when {
                    shouldTryBulk && learnedState?.bulkLiveSupported == true -> "VALIDATED_CACHE"
                    shouldTryBulk && learnedState?.bulkLiveSupported == false -> "STALE_UNSUPPORTED_REPROBE"
                    !shouldTryBulk && learnedState?.bulkLiveCategoryFidelity == false -> "BULK_CATEGORY_FIDELITY_FAILED"
                    !shouldTryBulk -> "BULK_KNOWN_UNSUPPORTED"
                    else -> "CAPABILITY_PROBE"
                }
            )
            val streamResult = if (shouldTryBulk) {
                withBulkLiveStallTimeout { markBulkProgress ->
                    api.streamLiveStreams { channel ->
                        markBulkProgress()
                        if (channel.categoryId != null && channel.categoryId in hiddenLiveCategoryIds && channel.categoryId !in requiredHiddenLiveCategoryIds) return@streamLiveStreams
                        if (channel.categoryId != null) bulkRowsWithResolvedCategories++
                        batch += channel
                        if (batch.size >= FALLBACK_STAGE_BATCH_SIZE) {
                            flushBatch()
                            progress(provider.id, onProgress, "Loading live channels... $acceptedCount imported")
                        }
                    }
                }
            } else null
            when (streamResult) {
                is Result.Success -> {
                    flushBatch()
                    api.recordBulkLiveCapability(true, acceptedCount == 0 || bulkRowsWithResolvedCategories > 0)
                    return finalizeStagedImport()
                }
                is Result.Error -> {
                    if (isDefinitiveBulkLiveFailure(streamResult.exception)) api.recordBulkLiveCapability(false)
                    val diagnostic = stalkerCatalogAccessDiagnostic(api, categoriesErrorMessage.orEmpty(), streamResult.message)
                    bulkFailure = IllegalStateException(
                        buildString {
                            append(streamResult.message.ifBlank { "Failed to load live channels" })
                            diagnostic?.let { append(' ').append(it) }
                        },
                        streamResult.exception
                    )
                }
                is Result.Loading -> throw IllegalStateException("Unexpected loading state")
                null -> {
                    bulkFailure = IOException("Bulk live request timed out; switching to category requests.")
                    progress(provider.id, onProgress, "Bulk live request is slow; loading categories instead...")
                }
            }

            stagedSessionId?.let { syncCatalogStore.discardStagedImport(provider.id, it) }
            stagedSessionId = null
            acceptedCount = 0
            batch.clear()
            seenStreamIds.clear()
            val fallbackCategories = preferredCategories.orEmpty()
            if (fallbackCategories.isEmpty()) {
                return StagedStalkerLiveCatalogResult(
                    acceptedCount = 0,
                    warnings = (warnings + listOfNotNull(
                        "Live TV provider exposed no live channels; continuing with VOD and series only.",
                        bulkFailure?.let { error -> sanitizeThrowableMessage(error) }?.takeIf { it.isNotBlank() }
                            ?.let { "Live TV fetch returned no usable channels: $it" }
                    )).distinct()
                )
            }
            Log.w(STALKER_EXECUTOR_TAG, "Stalker bulk live sync failed for provider ${provider.id}; falling back to category live fetches.", bulkFailure)
            warnings += "Bulk live request failed; recovered using per-category live fetches."
            val fallbackResult = fetchStalkerLiveChannelsByCategory(
                provider,
                api,
                fallbackCategories.map { XtreamCategory(it.categoryId.toString(), it.name, isAdult = it.isAdult) },
                onProgress
            )
            warnings += fallbackResult.warnings
            fallbackResult.channels.forEach { channel ->
                if (channel.categoryId != null && channel.categoryId in hiddenLiveCategoryIds && channel.categoryId !in requiredHiddenLiveCategoryIds) return@forEach
                batch += channel
                if (batch.size >= FALLBACK_STAGE_BATCH_SIZE) flushBatch()
            }
            flushBatch()
            return finalizeStagedImport()
        } catch (error: CancellationException) {
            stagedSessionId?.let { syncCatalogStore.discardStagedImport(provider.id, it) }
            throw error
        } catch (error: Exception) {
            stagedSessionId?.let { syncCatalogStore.discardStagedImport(provider.id, it) }
            throw error
        }
    }

    private suspend fun mergeHiddenChannelsIntoStaging(
        providerId: Long,
        sessionId: Long,
        hiddenLiveCategoryIds: Set<Long>
    ) {
        val hiddenChannels = channelDao.getByProviderSync(providerId)
            .filter { it.categoryId != null && it.categoryId in hiddenLiveCategoryIds }
        if (hiddenChannels.isNotEmpty()) syncCatalogStore.stageChannelBatch(providerId, sessionId, hiddenChannels)
    }

    private suspend fun fetchStalkerLiveChannelsByCategory(
        provider: Provider,
        api: StalkerProvider,
        categories: List<XtreamCategory>,
        onProgress: ((String) -> Unit)?
    ): StalkerLiveCategoryLoadResult {
        if (categories.isEmpty()) return StalkerLiveCategoryLoadResult(emptyList())
        val runtimeProfile = CatalogSyncRuntimeProfile.from(applicationContext)
        val concurrency = minOf(categories.size, runtimeProfile.maxCategoryConcurrency, STALKER_MAX_PARALLEL_CATEGORY_FETCHES).coerceAtLeast(1)
        val executionPlan = categoryRecoverySupport.executeCategoryRecoveryPlan(
            provider = provider,
            categories = categories,
            initialConcurrency = concurrency,
            sectionLabel = "Live TV",
            sequentialModeWarning = LIVE_CATEGORY_SEQUENTIAL_MODE_WARNING,
            onProgress = onProgress,
            fetch = { category -> fetchStalkerLiveCategoryOutcome(api, category) }
        )
        var timedOutcomes = executionPlan.outcomes
        val categoryOutcomes = timedOutcomes.map { it.outcome }
        val failureCount = timedOutcomes.count { it.outcome is CategoryFetchOutcome.Failure }
        val fastFailureCount = timedOutcomes.count { it.elapsedMs <= 5_000L && it.outcome is CategoryFetchOutcome.Failure }
        val downgradeRecommended = catalogStrategySupport.shouldDowngradeCategorySync(categories.size, failureCount, fastFailureCount, categoryOutcomes)
        var warnings = executionPlan.warnings.toMutableList()
        if (concurrency > 1 && catalogStrategySupport.shouldRetryFailedCategories(
                categories.size,
                failureCount,
                downgradeRecommended,
                categoryOutcomes
            )
        ) {
            val failedRetryTotal = timedOutcomes.count { it.outcome is CategoryFetchOutcome.Failure }
            progress(provider.id, onProgress, "Retrying failed Live TV categories 0/$failedRetryTotal...")
            timedOutcomes = categoryRecoverySupport.continueFailedCategoryOutcomes(
                provider,
                timedOutcomes,
                fetchSequentially = { category -> fetchStalkerLiveCategoryOutcome(api, category) },
                onCategoryRetried = { completed, total, _ ->
                    progress(provider.id, onProgress, "Retrying failed Live TV categories $completed/$total...")
                }
            )
            if (downgradeRecommended) warnings += LIVE_CATEGORY_SEQUENTIAL_MODE_WARNING
        }
        val finalOutcomes = timedOutcomes.map { it.outcome }
        val channels = finalOutcomes.filterIsInstance<CategoryFetchOutcome.Success<Channel>>()
            .flatMap { it.items }.distinctBy { it.streamId }
        val outcomeWarnings = finalOutcomes.filterIsInstance<CategoryFetchOutcome.Failure>()
            .map { categoryFailureWarning("Live TV", it.categoryName, it.error) }
        return StalkerLiveCategoryLoadResult(channels, (outcomeWarnings + warnings).distinct())
    }

    private suspend fun fetchStalkerLiveCategoryOutcome(
        api: StalkerProvider,
        category: XtreamCategory
    ): TimedCategoryOutcome<Channel> {
        val startedAt = System.currentTimeMillis()
        val outcome = try {
            val categoryId = category.categoryId.toLongOrNull()
                ?: throw IllegalArgumentException("Invalid Stalker live category id '${category.categoryId}'")
            val channels = requireResult(api.getLiveStreams(categoryId), "Failed to load live channels for ${category.categoryName}")
                .distinctBy { it.streamId }
            if (channels.isEmpty()) CategoryFetchOutcome.Empty(category.categoryName)
            else CategoryFetchOutcome.Success(category.categoryName, channels, channels.size)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            CategoryFetchOutcome.Failure(category.categoryName, error)
        }
        return TimedCategoryOutcome(category, outcome, System.currentTimeMillis() - startedAt)
    }

    private suspend fun <T> withBulkLiveStallTimeout(block: suspend (() -> Unit) -> T): T? = coroutineScope {
        val lastProgressAt = AtomicLong(System.currentTimeMillis())
        val streamDeferred = async { block { lastProgressAt.set(System.currentTimeMillis()) } }
        val watchdog = launch {
            while (isActive) {
                delay(STALKER_BULK_LIVE_STALL_CHECK_INTERVAL_MILLIS)
                if (System.currentTimeMillis() - lastProgressAt.get() >= STALKER_BULK_LIVE_STALL_TIMEOUT_MILLIS) {
                    streamDeferred.cancel()
                    break
                }
            }
        }
        try {
            try {
                streamDeferred.await()
            } catch (cancelled: CancellationException) {
                if (!isActive) throw cancelled
                null
            }
        } finally {
            watchdog.cancel()
            streamDeferred.cancel()
        }
    }

    private fun isDefinitiveBulkLiveFailure(error: Throwable?): Boolean = when (error) {
        is StalkerApiError.Authorization,
        is StalkerApiError.ModelRejected,
        is StalkerApiError.AccountBlocked,
        is StalkerApiError.Malformed,
        is StalkerApiError.ResponseTooLarge,
        is StalkerApiError.CatalogTruncated,
        is StalkerApiError.BlockedOrConfiguration -> true
        else -> false
    }

    private suspend fun stalkerCatalogAccessDiagnostic(
        api: StalkerProvider,
        primaryMessage: String,
        fallbackMessage: String?
    ): String? {
        if (!isStalkerEmptyResponse(primaryMessage) || !isStalkerEmptyResponse(fallbackMessage)) return null
        val profile = when (val result = api.getAccountProfile()) {
            is Result.Success -> result.data
            else -> return null
        }
        if (profile.ambiguousState) {
            return "Portal profile is ambiguous; playback/session validation failed. Check that the MAC is activated and that this portal supports MAG-style playback for the assigned account."
        }
        if (profile.authAccess == false &&
            (profile.accountId?.trim().orEmpty().isBlank() || profile.accountId?.trim() == "0") &&
            (profile.accountName?.trim().orEmpty().isBlank() || profile.accountName?.trim() == "0")
        ) {
            return "Portal authenticated, but the returned Stalker profile indicates this account has no accessible catalog data. Check that the MAC is activated and assigned a live/VOD package on the provider side."
        }
        return null
    }

    private fun isStalkerEmptyResponse(message: String?): Boolean =
        !message.isNullOrBlank() && message.contains("empty response", ignoreCase = true)

    private fun <T> requireResult(result: Result<T>, fallbackMessage: String): T = when (result) {
        is Result.Success -> result.data
        is Result.Error -> throw IllegalStateException(result.message.ifBlank { fallbackMessage }, result.exception)
        is Result.Loading -> throw IllegalStateException("Unexpected loading state")
    }

    private data class StalkerLiveCategoryLoadResult(
        val channels: List<Channel>,
        val warnings: List<String> = emptyList()
    )

    private data class StagedStalkerLiveCatalogResult(
        val acceptedCount: Int,
        val warnings: List<String> = emptyList()
    )
}
