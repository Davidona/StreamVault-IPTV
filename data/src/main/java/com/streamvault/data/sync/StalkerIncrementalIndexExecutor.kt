package com.streamvault.data.sync

import com.streamvault.data.local.entity.CategoryEntity
import com.streamvault.data.local.entity.StalkerIndexJobEntity
import com.streamvault.data.remote.stalker.StalkerPagedResult
import com.streamvault.data.remote.stalker.StalkerProvider
import com.streamvault.data.remote.stalker.StalkerTelemetry
import com.streamvault.data.remote.stalker.StalkerTrafficCoordinator
import com.streamvault.data.remote.stalker.StalkerVodCatalogItem
import com.streamvault.domain.model.CatalogLayout
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.LegacyProvider as Provider
import com.streamvault.domain.model.Movie
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.Series
import com.streamvault.domain.model.VodCatalogItem
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.min

private const val STALKER_INCREMENTAL_WILDCARD_PAGE_SLICE_SIZE = 192
private const val STALKER_INCREMENTAL_MAX_PARALLEL_CATEGORY_FETCHES = 2
private const val STALKER_INCREMENTAL_MAX_SECTION_RUN_MILLIS = 240_000L
private const val TAG = "StalkerIncrementalIndexExecutor"

/**
 * Narrow port used by [StalkerIncrementalIndexExecutor]. The executor owns the incremental
 * summary/wildcard state machine; the port keeps persistence, transport recovery, and catalog
 * writes in the existing data-layer collaborators until those concerns can be split further.
 */
internal interface StalkerIncrementalIndexOperations {
    val runtimeProfile: CatalogSyncRuntimeProfile

    suspend fun allVisibleCategories(providerId: Long, contentType: ContentType): List<CategoryEntity>

    suspend fun visibleCategories(
        contentType: ContentType,
        categories: List<CategoryEntity>,
        api: StalkerProvider
    ): List<CategoryEntity>

    suspend fun getJob(providerId: Long, contentType: ContentType): StalkerIndexJobEntity?

    suspend fun getHydration(
        providerId: Long,
        contentType: ContentType,
        categoryId: Long
    ): StalkerHydrationSnapshot?

    suspend fun currentIndexedRowCount(providerId: Long, contentType: ContentType): Int

    suspend fun pruneStaleRows(providerId: Long, contentType: ContentType): Int

    suspend fun updateSummaryMetadata(
        providerId: Long,
        contentType: ContentType,
        indexedRows: Int,
        finalState: String,
        now: Long
    )

    suspend fun fetchSummaryPage(
        provider: Provider,
        api: StalkerProvider,
        contentType: ContentType,
        categoryId: Long,
        page: Int
    ): Result<StalkerPagedResult<out Any>>

    suspend fun fetchWildcardPage(
        provider: Provider,
        api: StalkerProvider,
        contentType: ContentType,
        categoryId: Long,
        page: Int
    ): Result<StalkerPagedResult<out Any>>

    suspend fun markAttemptStarted(
        providerId: Long,
        contentType: ContentType,
        categoryId: Long,
        hydration: StalkerHydrationSnapshot?,
        attemptedPage: Int,
        now: Long
    )

    suspend fun markAttemptSucceeded(
        providerId: Long,
        contentType: ContentType,
        categoryId: Long,
        hydration: StalkerHydrationSnapshot?,
        attemptedPage: Int,
        now: Long,
        itemCount: Int,
        totalPages: Int,
        pageSize: Int,
        advertisedTotalItems: Int?,
        advertisedTotalPages: Int?,
        pageComplete: Boolean,
        truncated: Boolean,
        terminationReason: String?,
        pageFingerprint: String?
    )

    suspend fun markAttemptFailed(
        providerId: Long,
        contentType: ContentType,
        categoryId: Long,
        hydration: StalkerHydrationSnapshot?,
        attemptedPage: Int,
        now: Long,
        message: String,
        retryable: Boolean,
        pageFingerprint: String?
    )

    suspend fun upsertMovieSummaryBatch(
        providerId: Long,
        movies: List<Movie>,
        indexedAt: Long
    )

    suspend fun upsertSeriesSummaryBatch(
        providerId: Long,
        series: List<Series>,
        indexedAt: Long
    )

    suspend fun upsertVodDerivedSeriesSummaryBatch(
        providerId: Long,
        series: List<Series>,
        indexedAt: Long
    )

    suspend fun recordRequestFailure(providerId: Long, error: Throwable?)

    fun failureState(error: Throwable): String

    fun progress(providerId: Long, callback: ((String) -> Unit)?, message: String)

    suspend fun restoreMovieWatchProgress(providerId: Long)

    suspend fun upsertJob(update: CatalogIndexJobUpdate)

    fun log(message: String)
}

/**
 * Runs the durable, incremental Stalker movie/series index sections.
 *
 * This is intentionally separate from [StalkerIndexContinuationCoordinator]: that collaborator
 * chooses and schedules sections, while this class owns page fetching, retry decisions, anomaly
 * handling, and page-level catalog commits.
 */
internal class StalkerIncrementalIndexExecutor(
    private val operations: StalkerIncrementalIndexOperations
) {
    suspend fun processSummary(
        provider: Provider,
        api: StalkerProvider,
        contentType: ContentType,
        maxCategories: Int,
        onProgress: ((String) -> Unit)?
    ) {
        require(contentType == ContentType.MOVIE || contentType == ContentType.SERIES) {
            "Unsupported Stalker summary index section: $contentType"
        }
        val now = System.currentTimeMillis()
        val allVisibleCategories = operations.allVisibleCategories(provider.id, contentType)
        val visibleCategories = operations.visibleCategories(contentType, allVisibleCategories, api)
        val wildcardCategory = allVisibleCategories.firstOrNull { category ->
            api.isWildcardCategory(category.type, category.categoryId)
        }
        if (visibleCategories.isEmpty()) {
            val deletedRows = operations.pruneStaleRows(provider.id, contentType)
            val indexedRows = operations.currentIndexedRowCount(provider.id, contentType)
            upsertIndexJob(
                providerId = provider.id,
                section = contentType.name,
                state = "SUCCESS",
                now = now,
                totalCategories = 0,
                completedCategories = 0,
                nextCategoryIndex = 0,
                failedCategories = 0,
                indexedRows = indexedRows,
                deletedPrunedRows = deletedRows,
                clearPriority = true,
                lastAttemptAt = now,
                lastSuccessAt = now,
                lastError = null
            )
            operations.updateSummaryMetadata(provider.id, contentType, indexedRows, "SUCCESS", now)
            return
        }

        val initialJob = operations.getJob(provider.id, contentType)
        val learnedWildcardSupport = api.validatedPortalState()?.let { state ->
            when (contentType) {
                ContentType.MOVIE -> state.movieWildcardSupported
                ContentType.SERIES -> state.seriesWildcardSupported
                else -> null
            }
        }
        if (
            wildcardCategory != null &&
            learnedWildcardSupport != false &&
            visibleCategories.size == 1 &&
            visibleCategories.first().categoryId == wildcardCategory.categoryId
        ) {
            StalkerTelemetry.strategySelected(
                provider.id,
                "${contentType.name}_WILDCARD",
                if (learnedWildcardSupport == true) "VALIDATED_CACHE" else "CAPABILITY_PROBE"
            )
            if (processWildcard(
                    provider = provider,
                    api = api,
                    contentType = contentType,
                    wildcardCategory = wildcardCategory,
                    visibleCategories = allVisibleCategories,
                    maxPages = STALKER_INCREMENTAL_WILDCARD_PAGE_SLICE_SIZE,
                    initialJob = initialJob,
                    onProgress = onProgress
                )) {
                api.recordWildcardCapability(contentType, supported = true)
                return
            }
        } else if (wildcardCategory != null && learnedWildcardSupport == false) {
            StalkerTelemetry.strategySelected(provider.id, "${contentType.name}_CATEGORY", "WILDCARD_KNOWN_UNSUPPORTED")
        }

        val hydrationByCategory = visibleCategories.associate { category ->
            category.categoryId to operations.getHydration(provider.id, contentType, category.categoryId)
        }
        val completedBefore = hydrationByCategory.values.count { it?.isComplete == true }
        val failedBefore = hydrationByCategory.values.count { it?.isTerminalFailure == true }
        val indexedBefore = operations.currentIndexedRowCount(provider.id, contentType)
        upsertIndexJob(
            providerId = provider.id,
            section = contentType.name,
            state = "RUNNING",
            now = now,
            totalCategories = visibleCategories.size,
            completedCategories = completedBefore,
            failedCategories = failedBefore,
            indexedRows = indexedBefore,
            lastAttemptAt = now,
            lastError = null
        )

        var restoreMovieWatchProgressPending = false
        try {
            val pending = buildList {
                visibleCategories.forEach { category ->
                    if (size >= maxCategories) return@forEach
                    val hydration = hydrationByCategory[category.categoryId]
                    if (!canAttempt(hydration, now)) return@forEach
                    add(category)
                }
            }

            data class CategoryFetchAttempt(
                val category: CategoryEntity,
                val hydration: StalkerHydrationSnapshot?,
                val nextPage: Int
            )

            data class CategoryFetchResult(
                val attempt: CategoryFetchAttempt,
                val result: Result<StalkerPagedResult<out Any>>
            )

            data class SuccessfulPage(
                val category: CategoryEntity,
                val hydration: StalkerHydrationSnapshot?,
                val requestedPage: Int,
                val items: List<Any>,
                val totalPages: Int,
                val pageSize: Int,
                val advertisedTotalItems: Int?,
                val advertisedTotalPages: Int?,
                val isComplete: Boolean,
                val isTruncated: Boolean,
                val terminationReason: String?,
                val pageFingerprint: String?
            )

            var clearPriority = false
            var skippedMalformedRows = initialJob?.skippedMalformedRows ?: 0
            var pagesCommitted = 0
            var rowsCommitted = 0
            val sectionStartedAt = System.currentTimeMillis()
            val parallelFetchLimit = categoryFetchConcurrencyLimit(provider, maxCategories)
            val runDeadlineAt = System.currentTimeMillis() + STALKER_INCREMENTAL_MAX_SECTION_RUN_MILLIS
            val pendingQueue = ArrayDeque(pending)
            var forceSequential = parallelFetchLimit <= 1
            val retriedImmediately = mutableSetOf<Long>()
            while (pendingQueue.isNotEmpty() && System.currentTimeMillis() < runDeadlineAt) {
                if (pagesCommitted > 0) {
                    delay(StalkerTrafficCoordinator.backgroundInterPageDelayMillis(provider.id))
                }
                val windowSize = minOf(
                    if (forceSequential) 1 else parallelFetchLimit,
                    pendingQueue.size
                )
                val window = buildList {
                    repeat(windowSize) { add(pendingQueue.removeFirst()) }
                }
                val attempts = window.mapNotNull { category ->
                    val hydration = operations.getHydration(provider.id, contentType, category.categoryId)
                    if (!canAttempt(hydration, System.currentTimeMillis())) return@mapNotNull null
                    val nextPage = nextAttemptPage(hydration)
                    operations.progress(
                        provider.id,
                        onProgress,
                        "Indexing ${indexSectionLabel(contentType)}: ${category.name} page $nextPage"
                    )
                    operations.markAttemptStarted(
                        providerId = provider.id,
                        contentType = contentType,
                        categoryId = category.categoryId,
                        hydration = hydration,
                        attemptedPage = nextPage,
                        now = System.currentTimeMillis()
                    )
                    CategoryFetchAttempt(category, hydration, nextPage)
                }
                if (attempts.isEmpty()) continue
                val fetchedResults = coroutineScope {
                    attempts.map { attempt ->
                        async {
                            CategoryFetchResult(
                                attempt = attempt,
                                result = operations.fetchSummaryPage(
                                    provider = provider,
                                    api = api,
                                    contentType = contentType,
                                    categoryId = attempt.category.categoryId,
                                    page = attempt.nextPage
                                )
                            )
                        }
                    }.awaitAll()
                }

                var windowRetryableFailures = 0
                val successfulPages = mutableListOf<SuccessfulPage>()
                for (fetched in fetchedResults) {
                    val category = fetched.attempt.category
                    val hydration = fetched.attempt.hydration
                    val nextPage = fetched.attempt.nextPage
                    if (fetched.result is Result.Error) {
                        operations.recordRequestFailure(provider.id, fetched.result.exception)
                    }
                    when (val result = fetched.result) {
                        is Result.Success -> {
                            val pageFingerprint = StalkerIndexPolicy.pageFingerprint(result.data.items, contentType)
                            val anomaly = StalkerIndexPolicy.detectPageAnomaly(
                                hydration = hydration,
                                requestedPage = nextPage,
                                pagedResult = result.data,
                                pageFingerprint = pageFingerprint
                            )
                            if (anomaly != null) {
                                val indexedAt = System.currentTimeMillis()
                                skippedMalformedRows += result.data.items.size
                                val shouldRetryImmediately = category.categoryId !in retriedImmediately &&
                                    ((hydration?.failureCount ?: 0) == 0)
                                operations.markAttemptFailed(
                                    providerId = provider.id,
                                    contentType = contentType,
                                    categoryId = category.categoryId,
                                    hydration = hydration,
                                    attemptedPage = nextPage,
                                    now = indexedAt,
                                    message = anomaly,
                                    retryable = true,
                                    pageFingerprint = pageFingerprint
                                )
                                if (shouldRetryImmediately) {
                                    retriedImmediately += category.categoryId
                                    pendingQueue.addLast(category)
                                }
                                upsertIndexJob(
                                    providerId = provider.id,
                                    section = contentType.name,
                                    state = "RUNNING",
                                    now = indexedAt,
                                    skippedMalformedRows = skippedMalformedRows,
                                    lastAttemptAt = indexedAt,
                                    lastError = anomaly
                                )
                                continue
                            }
                            val dedupedItems = StalkerIndexPolicy.dedupePageItems(result.data.items, contentType)
                            skippedMalformedRows += (result.data.items.size - dedupedItems.size).coerceAtLeast(0)
                            successfulPages += SuccessfulPage(
                                category = category,
                                hydration = hydration,
                                requestedPage = nextPage,
                                items = dedupedItems,
                                totalPages = result.data.totalPages,
                                pageSize = result.data.pageSize,
                                advertisedTotalItems = result.data.advertisedTotalItems,
                                advertisedTotalPages = result.data.advertisedTotalPages,
                                isComplete = result.data.isComplete,
                                isTruncated = result.data.isTruncated,
                                terminationReason = result.data.terminationReason,
                                pageFingerprint = pageFingerprint
                            )
                        }
                        is Result.Error -> {
                            val failedAt = System.currentTimeMillis()
                            val retryable = operations.failureState(
                                result.exception ?: IllegalStateException(result.message)
                            ) != "FAILED_PERMANENT"
                            if (retryable) windowRetryableFailures += 1
                            val shouldRetryImmediately = retryable &&
                                category.categoryId !in retriedImmediately &&
                                ((hydration?.failureCount ?: 0) == 0)
                            operations.markAttemptFailed(
                                providerId = provider.id,
                                contentType = contentType,
                                categoryId = category.categoryId,
                                hydration = hydration,
                                attemptedPage = nextPage,
                                now = failedAt,
                                message = result.message,
                                retryable = retryable,
                                pageFingerprint = hydration?.lastPageFingerprint
                            )
                            if (shouldRetryImmediately) {
                                retriedImmediately += category.categoryId
                                pendingQueue.addLast(category)
                            }
                            if (!retryable) throw IllegalStateException(result.message, result.exception)
                            upsertIndexJob(
                                providerId = provider.id,
                                section = contentType.name,
                                state = "RUNNING",
                                now = failedAt,
                                skippedMalformedRows = skippedMalformedRows,
                                lastAttemptAt = now,
                                lastError = result.message
                            )
                        }
                        is Result.Loading -> Unit
                    }
                }
                if (successfulPages.isNotEmpty()) {
                    val indexedAt = System.currentTimeMillis()
                    when (contentType) {
                        ContentType.MOVIE -> {
                            val rawItems = successfulPages.flatMap { page -> page.items }
                            val classified = rawItems.filterIsInstance<StalkerVodCatalogItem>()
                            val movies = if (classified.isEmpty()) {
                                rawItems.filterIsInstance<Movie>()
                            } else {
                                classified.mapNotNull { (it.item as? VodCatalogItem.MovieItem)?.movie }
                            }
                            operations.upsertMovieSummaryBatch(provider.id, movies, indexedAt)
                            if (classified.isNotEmpty()) {
                                operations.upsertVodDerivedSeriesSummaryBatch(
                                    provider.id,
                                    classified.mapNotNull { (it.item as? VodCatalogItem.SeriesItem)?.series },
                                    indexedAt
                                )
                            }
                        }
                        ContentType.SERIES -> operations.upsertSeriesSummaryBatch(
                            provider.id,
                            successfulPages.flatMap { page -> page.items.filterIsInstance<Series>() },
                            indexedAt
                        )
                        else -> Unit
                    }
                    if (contentType == ContentType.MOVIE) restoreMovieWatchProgressPending = true
                    for (page in successfulPages) {
                        pagesCommitted += 1
                        rowsCommitted += page.items.size
                        val pageComplete = !page.isTruncated && (
                            page.isComplete || (page.items.isEmpty() && page.totalPages in 1..page.requestedPage)
                            )
                        val categoryCount = (page.hydration?.itemCount ?: 0) + page.items.size
                        operations.markAttemptSucceeded(
                            providerId = provider.id,
                            contentType = contentType,
                            categoryId = page.category.categoryId,
                            hydration = page.hydration,
                            attemptedPage = page.requestedPage,
                            now = indexedAt,
                            itemCount = categoryCount,
                            totalPages = page.totalPages,
                            pageSize = page.pageSize,
                            advertisedTotalItems = page.advertisedTotalItems,
                            advertisedTotalPages = page.advertisedTotalPages,
                            pageComplete = pageComplete,
                            truncated = page.isTruncated,
                            terminationReason = page.terminationReason,
                            pageFingerprint = page.pageFingerprint
                        )
                        if (!pageComplete && !page.isTruncated && System.currentTimeMillis() < runDeadlineAt) {
                            pendingQueue.addLast(page.category)
                        }
                    }
                }
                if (!forceSequential && windowRetryableFailures >= minOf(2, attempts.size)) {
                    forceSequential = true
                    operations.log(
                        "Stalker ${contentType.name} category fetch downgraded to sequential mode for provider ${provider.id} after retryable parallel failures."
                    )
                }
            }

            val refreshedHydration = visibleCategories.associate { category ->
                category.categoryId to operations.getHydration(provider.id, contentType, category.categoryId)
            }
            val finishedAt = System.currentTimeMillis()
            val completedCategories = refreshedHydration.values.count { it?.isComplete == true }
            val failedCategories = refreshedHydration.values.count { it?.isTerminalFailure == true }
            val truncatedCategories = refreshedHydration.values.count { it?.isTruncated == true }
            val hasMoreCategories = refreshedHydration.values.any { hydration -> canAttempt(hydration, finishedAt) }
            val retryDelaySeconds = StalkerIndexPolicy.nextRetryDelaySeconds(refreshedHydration.values, finishedAt)
            val indexedRows = operations.currentIndexedRowCount(provider.id, contentType)
            val pruneSuppressed = refreshedHydration.values.any { hydration ->
                hydration?.hasPruneSuppressionRisk == true
            }
            val deletedRows = if (!hasMoreCategories && failedCategories == 0 && !pruneSuppressed) {
                operations.pruneStaleRows(provider.id, contentType)
            } else {
                0
            }
            val finalState = when {
                hasMoreCategories -> "QUEUED"
                truncatedCategories > 0 -> "TRUNCATED"
                failedCategories > 0 -> "PARTIAL"
                pruneSuppressed -> "PARTIAL"
                else -> "SUCCESS"
            }
            upsertIndexJob(
                providerId = provider.id,
                section = contentType.name,
                state = finalState,
                now = finishedAt,
                totalCategories = visibleCategories.size,
                completedCategories = completedCategories,
                nextCategoryIndex = completedCategories,
                failedCategories = failedCategories,
                indexedRows = indexedRows,
                skippedMalformedRows = skippedMalformedRows,
                deletedPrunedRows = deletedRows,
                clearPriority = clearPriority || finalState in setOf("SUCCESS", "TRUNCATED"),
                lastAttemptAt = now,
                lastSuccessAt = finishedAt.takeIf { finalState == "SUCCESS" },
                lastError = refreshedHydration.values.firstOrNull {
                    it?.lastStatus in setOf("ERROR", "TRUNCATED")
                }?.lastError
            )
            operations.updateSummaryMetadata(provider.id, contentType, indexedRows, finalState, finishedAt)
            operations.log(
                "Stalker ${contentType.name} indexing finished for provider ${provider.id}: state=$finalState completed=$completedCategories failed=$failedCategories rows=$indexedRows committedPages=$pagesCommitted committedRows=$rowsCommitted throughput=${throughputSummary(sectionStartedAt, pagesCommitted, rowsCommitted)} retryDelay=${retryDelaySeconds}s"
            )
        } finally {
            if (contentType == ContentType.MOVIE && restoreMovieWatchProgressPending) {
                operations.restoreMovieWatchProgress(provider.id)
            }
        }
    }

    private suspend fun processWildcard(
        provider: Provider,
        api: StalkerProvider,
        contentType: ContentType,
        wildcardCategory: CategoryEntity,
        visibleCategories: List<CategoryEntity>,
        maxPages: Int,
        initialJob: StalkerIndexJobEntity?,
        onProgress: ((String) -> Unit)?
    ): Boolean {
        val now = System.currentTimeMillis()
        val normalVisibleCategoryIds = mutableSetOf<Long>()
        visibleCategories.forEach { category ->
            if (!api.isWildcardCategory(category.type, category.categoryId)) {
                normalVisibleCategoryIds += category.categoryId
            }
        }
        val visibleCategoryIds = normalVisibleCategoryIds.takeIf { it.isNotEmpty() }
        val hydration = operations.getHydration(provider.id, contentType, wildcardCategory.categoryId)
        if (!canAttempt(hydration, now)) return false

        upsertIndexJob(
            providerId = provider.id,
            section = contentType.name,
            state = "RUNNING",
            now = now,
            totalCategories = visibleCategories.size,
            completedCategories = 0,
            failedCategories = 0,
            indexedRows = operations.currentIndexedRowCount(provider.id, contentType),
            lastAttemptAt = now,
            lastError = null
        )

        var skippedMalformedRows = initialJob?.skippedMalformedRows ?: 0
        val seenPageFingerprints = mutableSetOf<String>()
        var pagesProcessed = 0
        var rowsCommitted = 0
        var lastError: String? = null
        var restoreMovieWatchProgressPending = false
        var indexedRowsEstimate = operations.currentIndexedRowCount(provider.id, contentType)
        val sectionStartedAt = System.currentTimeMillis()

        try {
            while (pagesProcessed < maxPages) {
                if (pagesProcessed > 0) {
                    delay(StalkerTrafficCoordinator.backgroundInterPageDelayMillis(provider.id))
                }
                val currentHydration = operations.getHydration(provider.id, contentType, wildcardCategory.categoryId)
                if (!canAttempt(currentHydration, System.currentTimeMillis())) break
                val nextPage = nextAttemptPage(currentHydration)
                operations.progress(provider.id, onProgress, "Indexing ${indexSectionLabel(contentType)}: All page $nextPage")
                operations.markAttemptStarted(
                    providerId = provider.id,
                    contentType = contentType,
                    categoryId = wildcardCategory.categoryId,
                    hydration = currentHydration,
                    attemptedPage = nextPage,
                    now = System.currentTimeMillis()
                )
                val coordinatedResult = operations.fetchWildcardPage(
                    provider = provider,
                    api = api,
                    contentType = contentType,
                    categoryId = wildcardCategory.categoryId,
                    page = nextPage
                )
                if (coordinatedResult is Result.Error) {
                    operations.recordRequestFailure(provider.id, coordinatedResult.exception)
                }
                when (val result = coordinatedResult) {
                    is Result.Success -> {
                        val indexedAt = System.currentTimeMillis()
                        val dedupedItems = StalkerIndexPolicy.dedupePageItems(result.data.items, contentType)
                        val visibleItems = StalkerIndexPolicy.filterToVisibleCategories(
                            dedupedItems,
                            contentType,
                            visibleCategoryIds
                        )
                        skippedMalformedRows += (result.data.items.size - visibleItems.size).coerceAtLeast(0)
                        if (nextPage == 1 && visibleItems.isEmpty()) {
                            val message = "Stalker wildcard catalog did not return usable visible ${indexSectionLabel(contentType)} rows."
                            operations.markAttemptFailed(
                                providerId = provider.id,
                                contentType = contentType,
                                categoryId = wildcardCategory.categoryId,
                                hydration = currentHydration,
                                attemptedPage = nextPage,
                                now = indexedAt,
                                message = message,
                                retryable = false,
                                pageFingerprint = null
                            )
                            operations.log("$message Falling back to per-category indexing for provider ${provider.id}.")
                            api.recordWildcardCapability(contentType, supported = false)
                            return false
                        }

                        val pageFingerprint = StalkerIndexPolicy.pageFingerprint(visibleItems, contentType)
                        val anomaly = StalkerIndexPolicy.detectPageAnomaly(
                            hydration = currentHydration,
                            requestedPage = nextPage,
                            pagedResult = StalkerPagedResult(
                                items = visibleItems,
                                page = result.data.page,
                                totalPages = result.data.totalPages,
                                pageSize = result.data.pageSize,
                                advertisedTotalItems = result.data.advertisedTotalItems,
                                advertisedTotalPages = result.data.advertisedTotalPages,
                                hasAdvertisedTotal = result.data.hasAdvertisedTotal,
                                isTruncated = result.data.isTruncated,
                                terminationReason = result.data.terminationReason
                            ),
                            pageFingerprint = pageFingerprint
                        ) ?: pageFingerprint
                            ?.takeIf { !seenPageFingerprints.add(it) }
                            ?.let { "Portal repeated a wildcard page payload." }

                        if (anomaly != null) {
                            operations.markAttemptFailed(
                                providerId = provider.id,
                                contentType = contentType,
                                categoryId = wildcardCategory.categoryId,
                                hydration = currentHydration,
                                attemptedPage = nextPage,
                                now = indexedAt,
                                message = anomaly,
                                retryable = false,
                                pageFingerprint = pageFingerprint
                            )
                            operations.log("Stalker wildcard ${contentType.name} indexing disabled for provider ${provider.id}: $anomaly")
                            api.recordWildcardCapability(contentType, supported = false)
                            return false
                        }

                        when (contentType) {
                            ContentType.MOVIE -> operations.upsertMovieSummaryBatch(
                                provider.id,
                                visibleItems.filterIsInstance<Movie>(),
                                indexedAt
                            )
                            ContentType.SERIES -> operations.upsertSeriesSummaryBatch(
                                provider.id,
                                visibleItems.filterIsInstance<Series>(),
                                indexedAt
                            )
                            else -> Unit
                        }
                        if (contentType == ContentType.MOVIE && visibleItems.isNotEmpty()) {
                            restoreMovieWatchProgressPending = true
                        }
                        indexedRowsEstimate += visibleItems.size
                        rowsCommitted += visibleItems.size
                        val pageComplete = !result.data.isTruncated && (
                            result.data.isComplete ||
                                (result.data.items.isEmpty() && result.data.totalPages in 1..nextPage)
                            )
                        operations.markAttemptSucceeded(
                            providerId = provider.id,
                            contentType = contentType,
                            categoryId = wildcardCategory.categoryId,
                            hydration = currentHydration,
                            attemptedPage = nextPage,
                            now = indexedAt,
                            itemCount = indexedRowsEstimate,
                            totalPages = result.data.totalPages,
                            pageSize = result.data.pageSize,
                            advertisedTotalItems = result.data.advertisedTotalItems,
                            advertisedTotalPages = result.data.advertisedTotalPages,
                            pageComplete = pageComplete,
                            truncated = result.data.isTruncated,
                            terminationReason = result.data.terminationReason,
                            pageFingerprint = pageFingerprint
                        )
                        pagesProcessed += 1
                        if (pageComplete || result.data.isTruncated) break
                    }
                    is Result.Error -> {
                        val failedAt = System.currentTimeMillis()
                        lastError = result.message
                        val retryable = operations.failureState(
                            result.exception ?: IllegalStateException(result.message)
                        ) != "FAILED_PERMANENT"
                        operations.markAttemptFailed(
                            providerId = provider.id,
                            contentType = contentType,
                            categoryId = wildcardCategory.categoryId,
                            hydration = currentHydration,
                            attemptedPage = nextPage,
                            now = failedAt,
                            message = result.message,
                            retryable = retryable,
                            pageFingerprint = currentHydration?.lastPageFingerprint
                        )
                        api.recordWildcardCapability(contentType, supported = false)
                        return false
                    }
                    is Result.Loading -> Unit
                }
            }

            val finishedAt = System.currentTimeMillis()
            val refreshedHydration = operations.getHydration(provider.id, contentType, wildcardCategory.categoryId)
            val hasMorePages = canAttempt(refreshedHydration, finishedAt)
            val indexedRows = operations.currentIndexedRowCount(provider.id, contentType)
            val finalState = when {
                hasMorePages -> "QUEUED"
                refreshedHydration?.isTruncated == true -> "TRUNCATED"
                refreshedHydration?.isTerminalFailure == true -> "PARTIAL"
                refreshedHydration?.hasPruneSuppressionRisk == true -> "PARTIAL"
                else -> "SUCCESS"
            }
            val deletedRows = if (finalState == "SUCCESS") {
                operations.pruneStaleRows(provider.id, contentType)
            } else {
                0
            }
            upsertIndexJob(
                providerId = provider.id,
                section = contentType.name,
                state = finalState,
                now = finishedAt,
                totalCategories = visibleCategories.size,
                completedCategories = if (finalState == "SUCCESS") visibleCategories.size else 0,
                nextCategoryIndex = if (finalState in setOf("SUCCESS", "TRUNCATED")) visibleCategories.size else 0,
                failedCategories = if (finalState == "PARTIAL") 1 else 0,
                indexedRows = indexedRows,
                skippedMalformedRows = skippedMalformedRows,
                deletedPrunedRows = deletedRows,
                clearPriority = finalState in setOf("SUCCESS", "TRUNCATED"),
                lastAttemptAt = now,
                lastSuccessAt = finishedAt.takeIf { finalState == "SUCCESS" },
                lastError = lastError ?: refreshedHydration?.lastError
            )
            operations.updateSummaryMetadata(provider.id, contentType, indexedRows, finalState, finishedAt)
            operations.log(
                "Stalker wildcard ${contentType.name} indexing finished for provider ${provider.id}: state=$finalState pages=$pagesProcessed rows=$rowsCommitted totalRows=$indexedRows throughput=${throughputSummary(sectionStartedAt, pagesProcessed, rowsCommitted)}"
            )
            return true
        } finally {
            if (contentType == ContentType.MOVIE && restoreMovieWatchProgressPending) {
                operations.restoreMovieWatchProgress(provider.id)
            }
        }
    }

    private fun canAttempt(hydration: StalkerHydrationSnapshot?, now: Long): Boolean =
        StalkerIndexPolicy.canAttempt(hydration, now)

    private fun nextAttemptPage(hydration: StalkerHydrationSnapshot?): Int =
        StalkerIndexPolicy.nextAttemptPage(hydration)

    private fun categoryFetchConcurrencyLimit(provider: Provider, maxCategories: Int): Int =
        minOf(
            maxCategories.coerceAtLeast(1),
            operations.runtimeProfile.maxCategoryConcurrency.coerceAtLeast(1),
            STALKER_INCREMENTAL_MAX_PARALLEL_CATEGORY_FETCHES
        ).coerceAtLeast(1)

    private fun throughputSummary(startedAt: Long, pages: Int, rows: Int): String {
        val elapsedSeconds = ((System.currentTimeMillis() - startedAt).coerceAtLeast(1L)) / 1000.0
        return "%.2f pages/s, %.2f rows/s".format(
            Locale.US,
            pages / elapsedSeconds,
            rows / elapsedSeconds
        )
    }

    private suspend fun upsertIndexJob(
        providerId: Long,
        section: String,
        state: String,
        now: Long,
        totalCategories: Int? = null,
        completedCategories: Int? = null,
        nextCategoryIndex: Int? = null,
        failedCategories: Int? = null,
        indexedRows: Int? = null,
        skippedMalformedRows: Int? = null,
        deletedPrunedRows: Int? = null,
        priorityCategoryId: Long? = null,
        priorityRequestedAt: Long? = null,
        clearPriority: Boolean = false,
        lastAttemptAt: Long? = null,
        lastSuccessAt: Long? = null,
        lastError: String? = null
    ) {
        operations.upsertJob(
            CatalogIndexJobUpdate(
                providerId = providerId,
                section = section,
                state = state,
                now = now,
                totalCategories = totalCategories,
                completedCategories = completedCategories,
                nextCategoryIndex = nextCategoryIndex,
                failedCategories = failedCategories,
                indexedRows = indexedRows,
                skippedMalformedRows = skippedMalformedRows,
                deletedPrunedRows = deletedPrunedRows,
                priorityCategoryId = priorityCategoryId,
                priorityRequestedAt = priorityRequestedAt,
                clearPriority = clearPriority,
                lastAttemptAt = lastAttemptAt,
                lastSuccessAt = lastSuccessAt,
                lastError = lastError
            )
        )
    }

    private fun indexSectionLabel(contentType: ContentType): String = when (contentType) {
        ContentType.MOVIE -> "movies"
        ContentType.SERIES -> "series"
        else -> contentType.name.lowercase()
    }
}
