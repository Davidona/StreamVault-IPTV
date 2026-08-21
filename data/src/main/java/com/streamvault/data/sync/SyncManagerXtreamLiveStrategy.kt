package com.streamvault.data.sync

import android.util.Log
import com.streamvault.data.mapper.toEntity
import com.streamvault.data.remote.dto.XtreamCategory
import com.streamvault.data.remote.dto.XtreamLiveStreamRow
import com.streamvault.data.remote.dto.XtreamStream
import com.streamvault.data.remote.xtream.OkHttpXtreamApiService
import com.streamvault.data.remote.xtream.XtreamApiService
import com.streamvault.data.remote.xtream.XtreamProvider
import com.streamvault.data.remote.xtream.XtreamUrlFactory
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.LegacyProvider as Provider
import com.streamvault.domain.model.SyncMetadata
import com.streamvault.domain.sync.Section
import com.streamvault.domain.sync.SyncProgress
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.system.measureTimeMillis

private const val XTREAM_LIVE_STRATEGY_TAG = "SyncManager"

private enum class XtreamLiveDecodeMode {
    THIN_WITH_LEGACY_FALLBACK,
    LEGACY_ONLY
}

internal class LowMemoryCatalogAbortException(message: String) : IllegalStateException(message)

internal class SyncManagerXtreamLiveStrategy(
    private val xtreamCatalogApiService: XtreamApiService,
    private val xtreamCatalogHttpService: OkHttpXtreamApiService,
    private val xtreamAdaptiveSyncPolicy: XtreamAdaptiveSyncPolicy,
    private val xtreamSupport: SyncManagerXtreamSupport,
    private val xtreamFetcher: SyncManagerXtreamFetcher,
    private val catalogStrategySupport: SyncManagerCatalogStrategySupport,
    private val syncCatalogStore: SyncCatalogStore,
    private val progress: (Long, ((String) -> Unit)?, String) -> Unit,
    private val sanitizeThrowableMessage: (Throwable?) -> String,
    private val fullCatalogFallbackWarning: (String, Throwable?) -> String,
    private val categoryFailureWarning: (String, String, Throwable) -> String,
    private val liveCategorySequentialModeWarning: String,
    private val isCurrentlyLowOnMemory: () -> Boolean = { false },
    private val stageChannelItems: suspend (Long, List<Channel>, MutableSet<Long>, FallbackCategoryCollector, Long?) -> StagedCatalogSnapshot,
    private val emitProgress: (Long, SyncProgress) -> Unit
) {
    suspend fun syncXtreamLiveCatalog(
        provider: Provider,
        api: XtreamProvider,
        existingMetadata: SyncMetadata,
        hiddenLiveCategoryIds: Set<Long>,
        onProgress: ((String) -> Unit)?,
        runtimeProfile: CatalogSyncRuntimeProfile,
        trackInitialLiveOnboarding: Boolean,
        effectiveLiveSyncMethod: EffectiveXtreamLiveSyncMethod = EffectiveXtreamLiveSyncMethod.STREAM_ALL,
        requiredHiddenLiveCategoryIds: Set<Long> = emptySet()
    ): CatalogSyncPayload<Channel> {
        Log.i(XTREAM_LIVE_STRATEGY_TAG, "Xtream live strategy start for provider ${provider.id}.")
        val rawLiveCategories = when (val attempt = xtreamSupport.attemptNonCancellation {
            xtreamSupport.retryXtreamCatalogTransient(provider.id) {
                xtreamSupport.executeXtreamRequest(provider.id, XtreamAdaptiveSyncPolicy.Stage.LIGHTWEIGHT) {
                    xtreamCatalogApiService.getLiveCategories(
                        XtreamUrlFactory.buildPlayerApiUrl(
                            serverUrl = provider.serverUrl,
                            username = provider.username,
                            password = provider.password,
                            action = "get_live_categories"
                        )
                    )
                }
            }
        }) {
            is Attempt.Success -> attempt.value
            is Attempt.Failure -> {
                Log.w(
                    XTREAM_LIVE_STRATEGY_TAG,
                    "Xtream live categories request failed for provider ${provider.id}: ${sanitizeThrowableMessage(attempt.error)}"
                )
                null
            }
        }
        val resolvedCategories = rawLiveCategories
            ?.let { categories -> api.mapCategories(ContentType.LIVE, categories) }
            ?.map { category -> category.toEntity(provider.id) }
            ?.takeIf { it.isNotEmpty() }
        val filteredRawLiveCategories = rawLiveCategories.orEmpty().filterNot { category ->
            val id = category.categoryId.toLongOrNull()
            id in hiddenLiveCategoryIds && id !in requiredHiddenLiveCategoryIds
        }
        // Hidden categories still need a catalog shell after a provider is restored. The
        // channel fetch intentionally skips them, but filtering them out of the category
        // snapshot as well means a provider whose old rows were deleted can never recreate
        // the hidden category itself.
        val resolvedCatalogCategories = resolvedCategories?.takeIf { it.isNotEmpty() }

        var fullPayload = CatalogSyncPayload<Channel>(
            catalogResult = CatalogStrategyResult.EmptyValid("full"),
            categories = null
        )
        val shouldAttemptFullCatalog = effectiveLiveSyncMethod == EffectiveXtreamLiveSyncMethod.STREAM_ALL &&
            hiddenLiveCategoryIds.isEmpty() &&
            runtimeProfile.shouldAttemptFullLiveCatalog(trackInitialLiveOnboarding)
        if (shouldAttemptFullCatalog) {
            progress(provider.id, onProgress, "Downloading Live TV...")
            fullPayload = loadXtreamLiveFull(provider, api, runtimeProfile)
            when (val fullResult = fullPayload.catalogResult) {
                is CatalogStrategyResult.Success -> return fullPayload.copy(
                    categories = catalogStrategySupport.mergePreferredAndFallbackCategories(
                        resolvedCatalogCategories,
                        fullPayload.categories ?: catalogStrategySupport.buildFallbackLiveCategories(provider.id, fullResult.items)
                    ),
                    warnings = emptyList(),
                    strategyFeedback = XtreamStrategyFeedback(
                        attemptedFullCatalog = true,
                        fullCatalogUnsafe = false
                    )
                )
                is CatalogStrategyResult.Partial -> return fullPayload.copy(
                    categories = catalogStrategySupport.mergePreferredAndFallbackCategories(
                        resolvedCatalogCategories,
                        fullPayload.categories ?: catalogStrategySupport.buildFallbackLiveCategories(provider.id, fullResult.items)
                    ),
                    warnings = emptyList(),
                    strategyFeedback = XtreamStrategyFeedback(
                        attemptedFullCatalog = true,
                        fullCatalogUnsafe = false
                    )
                )
                else -> Unit
            }
        } else {
            Log.i(
                XTREAM_LIVE_STRATEGY_TAG,
                "Xtream live full catalog skipped for provider ${provider.id}: hiddenCategories=${hiddenLiveCategoryIds.size} profile=${runtimeProfile.diagnosticsLabel} initial=$trackInitialLiveOnboarding."
            )
        }

        progress(provider.id, onProgress, "Downloading Live TV by category...")
        val categoryPayload = loadXtreamLiveByCategory(
            provider = provider,
            api = api,
            rawCategories = filteredRawLiveCategories,
            onProgress = onProgress,
            preferSequential = existingMetadata.liveSequentialFailuresRemembered || runtimeProfile.maxCategoryConcurrency <= 1,
            runtimeProfile = runtimeProfile
        )
        return CatalogSyncPayload(
            catalogResult = categoryPayload.catalogResult,
            categories = catalogStrategySupport.mergePreferredAndFallbackCategories(
                resolvedCatalogCategories,
                categoryPayload.categories
            ),
            warnings = (categoryPayload.warnings + catalogStrategySupport.strategyWarnings(fullPayload.catalogResult)).distinct(),
            stagedSessionId = categoryPayload.stagedSessionId,
            stagedAcceptedCount = categoryPayload.stagedAcceptedCount,
            strategyFeedback = XtreamStrategyFeedback(
                attemptedFullCatalog = shouldAttemptFullCatalog,
                preferredSegmentedFirst = effectiveLiveSyncMethod == EffectiveXtreamLiveSyncMethod.CATEGORY_BY_CATEGORY || !shouldAttemptFullCatalog,
                fullCatalogUnsafe = (fullPayload.catalogResult as? CatalogStrategyResult.Failure)?.error?.let(catalogStrategySupport::shouldAvoidFullCatalogStrategy) == true,
                segmentedStressDetected = catalogStrategySupport.sawSegmentedStress(
                    warnings = catalogStrategySupport.strategyWarnings(fullPayload.catalogResult),
                    result = categoryPayload.catalogResult,
                    sequentialWarnings = setOf(liveCategorySequentialModeWarning)
                )
            )
        )
    }

    suspend fun loadXtreamLiveFull(
        provider: Provider,
        api: XtreamProvider,
        runtimeProfile: CatalogSyncRuntimeProfile
    ): CatalogSyncPayload<Channel> {
        val endpoint = XtreamUrlFactory.buildPlayerApiUrl(
            serverUrl = provider.serverUrl,
            username = provider.username,
            password = provider.password,
            action = "get_live_streams"
        )
        val decodeMode = XtreamLiveDecodeMode.THIN_WITH_LEGACY_FALLBACK
        if (decodeMode == XtreamLiveDecodeMode.LEGACY_ONLY) {
            return loadXtreamLiveFullStreaming(
                provider = provider,
                decoderLabel = "legacy",
                failureNextStep = "category-bulk",
                streamItems = { item -> xtreamCatalogHttpService.streamLiveStreams(endpoint, onItem = item) },
                mapRawBatch = { batch -> api.mapLiveStreamsSequence(batch) },
                runtimeProfile = runtimeProfile
            )
        }

        val thinPayload = loadXtreamLiveFullStreaming(
            provider = provider,
            decoderLabel = "thin",
            failureNextStep = "legacy-full",
            streamItems = { item -> xtreamCatalogHttpService.streamLiveStreamRows(endpoint, onItem = item) },
            mapRawBatch = { batch -> api.mapLiveStreamRowsSequence(batch) },
            runtimeProfile = runtimeProfile
        )
        if (!thinPayload.shouldRetryLegacyFullDecode()) {
            return thinPayload
        }

        Log.w(
            XTREAM_LIVE_STRATEGY_TAG,
            "Xtream live thin full-catalog decode did not produce a usable result for provider ${provider.id}; retrying legacy decode."
        )
        return loadXtreamLiveFullStreaming(
            provider = provider,
            decoderLabel = "legacy",
            failureNextStep = "category-bulk",
            streamItems = { item -> xtreamCatalogHttpService.streamLiveStreams(endpoint, onItem = item) },
            mapRawBatch = { batch -> api.mapLiveStreamsSequence(batch) },
            runtimeProfile = runtimeProfile
        )
    }

    private fun CatalogSyncPayload<Channel>.shouldRetryLegacyFullDecode(): Boolean {
        val result = catalogResult
        if ((result as? CatalogStrategyResult.Failure)?.error is LowMemoryCatalogAbortException) return false
        return result is CatalogStrategyResult.Failure ||
            (result is CatalogStrategyResult.EmptyValid && result.warnings.any { warning ->
                warning.contains("raw items but none were usable", ignoreCase = true)
            })
    }

    private suspend fun <RawItem> loadXtreamLiveFullStreaming(
        provider: Provider,
        decoderLabel: String,
        failureNextStep: String,
        streamItems: suspend (suspend (RawItem) -> Unit) -> Int,
        mapRawBatch: suspend (Sequence<RawItem>) -> Sequence<Channel>,
        runtimeProfile: CatalogSyncRuntimeProfile
    ): CatalogSyncPayload<Channel> {
        val fallbackCollector = FallbackCategoryCollector(provider.id, ContentType.LIVE)
        val seenStreamIds = HashSet<Long>()
        val rawBatch = ArrayList<RawItem>(runtimeProfile.stageBatchSize)
        var stagedSessionId: Long? = null
        var acceptedCount = 0
        var streamedRawCount = 0
        var fullChannelsFailure: Throwable? = null
        var flushCount = 0
        var mappingElapsedMs = 0L
        var stagingElapsedMs = 0L

        fun abortIfLowMemory() {
            if (isCurrentlyLowOnMemory()) {
                // D12 — emission finale avant l'avortement low-memory : reflete l'etat
                // au moment de l'echec (section LIVE, mode indetermine, nombre d'items
                // deja acceptes). Le `reset()` du finally cote SyncManager (T3) viendra
                // ensuite ramener le flow a null — c'est volontaire (D7) pour eviter que
                // l'ecran suivant n'herite d'un etat partiel.
                emitProgress(provider.id,
                    SyncProgress(
                        section = Section.LIVE,
                        current = 0,
                        total = 0,
                        currentLabel = "",
                        itemsIndexed = acceptedCount
                    )
                )
                throw LowMemoryCatalogAbortException(
                    "Device entered low-memory state while streaming Live catalog; falling back to category sync."
                )
            }
        }

        suspend fun flushRawBatch() {
            if (rawBatch.isEmpty()) return
            lateinit var mappedChannels: List<Channel>
            mappingElapsedMs += measureTimeMillis {
                mappedChannels = mapRawBatch(rawBatch.asSequence()).toList()
            }
            lateinit var staged: StagedCatalogSnapshot
            stagingElapsedMs += measureTimeMillis {
                staged = stageChannelItems(
                    provider.id,
                    mappedChannels,
                    seenStreamIds,
                    fallbackCollector,
                    stagedSessionId
                )
            }
            stagedSessionId = staged.sessionId
            acceptedCount += staged.acceptedCount
            flushCount++
            rawBatch.clear()
            // D10 — mode HIGH (full catalog) : pas de count par categorie disponible,
            // on emet en indetermine (`total = 0`) une fois par flush de batch (cadence
            // <= 1/s en pratique, jamais par item). Le label reste vide car aucune
            // categorie ne correspond a la fenetre courante.
            emitProgress(provider.id,
                SyncProgress(
                    section = Section.LIVE,
                    current = 0,
                    total = 0,
                    currentLabel = "",
                    itemsIndexed = acceptedCount
                )
            )
            abortIfLowMemory()
        }

        val fullChannelsElapsedMs = measureTimeMillis {
            when (val attempt = xtreamSupport.attemptNonCancellation {
                xtreamSupport.retryXtreamCatalogTransient(provider.id) {
                    xtreamSupport.executeXtreamRequest(provider.id, XtreamAdaptiveSyncPolicy.Stage.HEAVY) {
                        streamItems { item ->
                            rawBatch += item
                            streamedRawCount++
                            if (rawBatch.size >= runtimeProfile.stageBatchSize) {
                                flushRawBatch()
                            }
                        }.also {
                            flushRawBatch()
                        }
                    }
                }
            }) {
                is Attempt.Success -> Unit
                is Attempt.Failure -> {
                    fullChannelsFailure = attempt.error
                    stagedSessionId?.let { sessionId ->
                        syncCatalogStore.discardStagedImport(provider.id, sessionId)
                        stagedSessionId = null
                    }
                }
            }
        }

        if (fullChannelsFailure != null) {
            xtreamSupport.logXtreamCatalogFallback(
                provider = provider,
                section = "live",
                stage = "full catalog ($decoderLabel)",
                elapsedMs = fullChannelsElapsedMs,
                itemCount = streamedRawCount,
                error = fullChannelsFailure,
                nextStep = failureNextStep
            )
            return CatalogSyncPayload(
                catalogResult = CatalogStrategyResult.Failure(
                    strategyName = "full",
                    error = fullChannelsFailure!!,
                    warnings = listOf(fullCatalogFallbackWarning("Live TV", fullChannelsFailure))
                ),
                categories = null
            )
        }

        if (streamedRawCount > 0) {
            Log.i(
                XTREAM_LIVE_STRATEGY_TAG,
                "Xtream live full catalog succeeded for provider ${provider.id} using $decoderLabel decoder in ${fullChannelsElapsedMs}ms " +
                    "with $acceptedCount accepted items from $streamedRawCount raw items " +
                    "across $flushCount flushes; mapping=${mappingElapsedMs}ms staging=${stagingElapsedMs}ms profile=${runtimeProfile.diagnosticsLabel}."
            )
            if (acceptedCount == 0) {
                return CatalogSyncPayload(
                    catalogResult = CatalogStrategyResult.EmptyValid(
                        strategyName = "full",
                        warnings = listOf("Live full catalog returned raw items but none were usable after mapping.")
                    ),
                    categories = null
                )
            }
            return CatalogSyncPayload(
                catalogResult = CatalogStrategyResult.Success(
                    strategyName = "full",
                    items = emptyList()
                ),
                categories = fallbackCollector.entities().takeIf { it.isNotEmpty() },
                stagedSessionId = stagedSessionId,
                stagedAcceptedCount = acceptedCount
            )
        }
        xtreamSupport.logXtreamCatalogFallback(
            provider = provider,
            section = "live",
            stage = "full catalog ($decoderLabel)",
            elapsedMs = fullChannelsElapsedMs,
            itemCount = streamedRawCount,
            error = null,
            nextStep = failureNextStep
        )
        return CatalogSyncPayload(
            catalogResult = CatalogStrategyResult.EmptyValid(
                strategyName = "full",
                warnings = listOf("Live full catalog returned an empty valid result.")
            ),
            categories = null
        )
    }

    suspend fun loadXtreamLiveByCategory(
        provider: Provider,
        api: XtreamProvider,
        rawCategories: List<XtreamCategory>,
        onProgress: ((String) -> Unit)?,
        preferSequential: Boolean,
        runtimeProfile: CatalogSyncRuntimeProfile
    ): CatalogSyncPayload<Channel> {
        val categories = rawCategories.filter { it.categoryId.isNotBlank() }
        if (categories.isEmpty()) {
            return CatalogSyncPayload(
                catalogResult = CatalogStrategyResult.Failure(
                    strategyName = "category_bulk",
                    error = IllegalStateException("No live categories available"),
                    warnings = listOf("Live category-bulk strategy was unavailable because no categories were returned.")
                ),
                categories = null
            )
        }

        val fallbackCollector = FallbackCategoryCollector(provider.id, ContentType.LIVE)
        val seenStreamIds = HashSet<Long>()
        val stageMutex = Mutex()
        var stagedSessionId: Long? = null
        var stagedAcceptedCount = 0

        suspend fun stageMappedBatch(channels: List<Channel>) {
            if (channels.isEmpty()) return
            stageMutex.withLock {
                val staged = stageChannelItems(
                    provider.id,
                    channels,
                    seenStreamIds,
                    fallbackCollector,
                    stagedSessionId
                )
                stagedSessionId = staged.sessionId
                stagedAcceptedCount += staged.acceptedCount
            }
        }

        val adaptiveConcurrency = xtreamAdaptiveSyncPolicy.concurrencyFor(
            providerId = provider.id,
            workloadSize = categories.size,
            preferSequential = preferSequential,
            stage = XtreamAdaptiveSyncPolicy.Stage.CATEGORY
        )
        val concurrency = adaptiveConcurrency.coerceAtMost(runtimeProfile.maxCategoryConcurrency)
        progress(provider.id, onProgress, "Downloading Live TV by category 0/${categories.size}...")

        // D11 — le total de catégories LIVE est fige une seule fois ici : la liste
        // `categories` ne mute plus pendant la recuperation, donc la lambda
        // `onCategoryCompleted` recoit le meme denominateur a chaque fenetre.
        val executionPlan = xtreamSupport.executeCategoryRecoveryPlan(
            provider = provider,
            categories = categories,
            initialConcurrency = concurrency,
            sectionLabel = "Live TV",
            sequentialModeWarning = liveCategorySequentialModeWarning,
            onProgress = onProgress,
            fetch = { category ->
                xtreamFetcher.fetchLiveCategoryOutcome(
                    provider = provider,
                    api = api,
                    category = category,
                    stageBatchSize = runtimeProfile.stageBatchSize,
                    onMappedBatch = ::stageMappedBatch
                )
            },
            onCategoryCompleted = { completed, total, currentLabel ->
                // D6 — emission structuree en parallele du callback string deja emis par
                // `executeCategoryRecoveryPlan` via `progress(...)`. Le compteur cumulatif
                // `stagedAcceptedCount` est mis a jour de maniere thread-safe sous le
                // `stageMutex` (cf `stageMappedBatch`), la lecture ici est best-effort
                // (snapshot UX, pas une metrique business).
                emitProgress(provider.id,
                    SyncProgress(
                        section = Section.LIVE,
                        current = completed,
                        total = total,
                        currentLabel = currentLabel,
                        itemsIndexed = stagedAcceptedCount
                    )
                )
            }
        )
        var timedOutcomes = executionPlan.outcomes

        val categoryOutcomes = timedOutcomes.map { it.outcome }
        val failureCount = timedOutcomes.count { it.outcome is CategoryFetchOutcome.Failure }
        val fastFailureCount = timedOutcomes.count {
            it.elapsedMs <= 5_000L && it.outcome is CategoryFetchOutcome.Failure
        }
        val downgradeRecommended = catalogStrategySupport.shouldDowngradeCategorySync(
            categories.size,
            failureCount,
            fastFailureCount,
            categoryOutcomes
        )
        var fallbackWarnings = executionPlan.warnings
        if (concurrency > 1 && catalogStrategySupport.shouldRetryFailedCategories(
                categories.size,
                failureCount,
                downgradeRecommended,
                categoryOutcomes
            )
        ) {
            val failedRetryTotal = timedOutcomes.count { it.outcome is CategoryFetchOutcome.Failure }
            Log.w(
                XTREAM_LIVE_STRATEGY_TAG,
                "Xtream live category sync is continuing in sequential mode for failed categories on provider ${provider.id}."
            )
            progress(provider.id, onProgress, "Retrying failed Live TV categories 0/$failedRetryTotal...")
            timedOutcomes = xtreamSupport.continueFailedCategoryOutcomes(
                provider = provider,
                timedOutcomes = timedOutcomes,
                fetchSequentially = { category ->
                    xtreamFetcher.fetchLiveCategoryOutcome(
                        provider = provider,
                        api = api,
                        category = category,
                        stageBatchSize = runtimeProfile.stageBatchSize,
                        onMappedBatch = ::stageMappedBatch
                    )
                },
                onCategoryRetried = { completed, total, _ ->
                    progress(provider.id, onProgress, "Retrying failed Live TV categories $completed/$total...")
                }
            )
            fallbackWarnings = (fallbackWarnings + if (downgradeRecommended) listOf(liveCategorySequentialModeWarning) else emptyList()).distinct()
        }

        val finalOutcomes = timedOutcomes.map { it.outcome }
        val warnings = finalOutcomes
            .filterIsInstance<CategoryFetchOutcome.Failure>()
            .map { failure -> categoryFailureWarning("Live TV", failure.categoryName, failure.error) } +
            fallbackWarnings

        val failedCategories = finalOutcomes.count { it is CategoryFetchOutcome.Failure }
        val emptyCategories = finalOutcomes.count { it is CategoryFetchOutcome.Empty }
        val successfulCategories = finalOutcomes.count { it is CategoryFetchOutcome.Success }
        Log.i(
            XTREAM_LIVE_STRATEGY_TAG,
            "Xtream live category strategy summary for provider ${provider.id}: successful=$successfulCategories empty=$emptyCategories failed=$failedCategories stagedChannels=$stagedAcceptedCount concurrency=$concurrency"
        )

        return when {
            stagedAcceptedCount > 0 && failedCategories == 0 -> CatalogSyncPayload(
                catalogResult = CatalogStrategyResult.Success("category_bulk", emptyList(), warnings.toList()),
                categories = fallbackCollector.entities().takeIf { it.isNotEmpty() },
                stagedSessionId = stagedSessionId,
                stagedAcceptedCount = stagedAcceptedCount
            )
            stagedAcceptedCount > 0 -> CatalogSyncPayload(
                catalogResult = CatalogStrategyResult.Partial("category_bulk", emptyList(), warnings.toList()),
                categories = fallbackCollector.entities().takeIf { it.isNotEmpty() },
                stagedSessionId = stagedSessionId,
                stagedAcceptedCount = stagedAcceptedCount
            )
            failedCategories > 0 -> CatalogSyncPayload(
                catalogResult = CatalogStrategyResult.Failure(
                    strategyName = "category_bulk",
                    error = IllegalStateException("Live category-bulk sync failed for all usable categories"),
                    warnings = warnings.toList()
                ),
                categories = null
            )
            else -> CatalogSyncPayload(
                catalogResult = CatalogStrategyResult.EmptyValid(
                    strategyName = "category_bulk",
                    warnings = listOf("All live categories returned valid empty results.")
                ),
                categories = null
            )
        }
    }
}
