package com.streamvault.data.sync

import com.streamvault.data.local.entity.CategoryEntity
import com.streamvault.data.local.entity.XtreamIndexJobEntity
import com.streamvault.data.remote.xtream.XtreamProvider
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.LegacyProvider as Provider
import com.streamvault.domain.model.Movie
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.Series

/**
 * Owns incremental Xtream movie/series index orchestration.
 *
 * The executor controls cursor progression, priority-category ordering, full-stream fallback,
 * durable state publication, and continuation scheduling. Network, DAO, and metadata details are
 * supplied through one narrow operations port so the coordinator does not regain those seams.
 */
internal interface XtreamIncrementalIndexOperations {
    fun monotonicTimeMillis(): Long = System.nanoTime() / 1_000_000L

    suspend fun getCategories(providerId: Long, contentType: ContentType): List<CategoryEntity>

    suspend fun ensureCategoryShell(
        provider: Provider,
        api: XtreamProvider,
        contentType: ContentType,
        now: Long,
        onProgress: ((String) -> Unit)?
    )

    suspend fun getJob(providerId: Long, contentType: ContentType): XtreamIndexJobEntity?

    fun shouldRunSummary(job: XtreamIndexJobEntity?): Boolean

    suspend fun fetchMovieCategory(
        provider: Provider,
        api: XtreamProvider,
        category: CategoryEntity
    ): TimedCategoryOutcome<Movie>

    suspend fun fetchSeriesCategory(
        provider: Provider,
        api: XtreamProvider,
        category: CategoryEntity
    ): TimedCategoryOutcome<Series>

    suspend fun upsertMovieSummaryBatch(
        providerId: Long,
        movies: List<Movie>,
        indexedAt: Long
    ): Int

    suspend fun upsertSeriesSummaryBatch(
        providerId: Long,
        series: List<Series>,
        indexedAt: Long
    ): Int

    suspend fun streamMovies(
        provider: Provider,
        api: XtreamProvider,
        adultCategoryIds: Set<Long>,
        onBatch: suspend (List<Movie>) -> Unit
    ): Result<Int>

    suspend fun streamSeries(
        provider: Provider,
        api: XtreamProvider,
        adultCategoryIds: Set<Long>,
        onBatch: suspend (List<Series>) -> Unit
    ): Result<Int>

    suspend fun upsertJob(update: CatalogIndexJobUpdate)

    suspend fun updateSummaryMetadata(
        providerId: Long,
        contentType: ContentType,
        indexedRows: Int,
        state: String,
        now: Long
    )

    fun scheduleIndex(providerId: Long, contentType: ContentType)

    fun progress(providerId: Long, callback: ((String) -> Unit)?, message: String)

    suspend fun restoreMovieWatchProgress(providerId: Long)

    fun sanitize(error: Throwable?): String

    fun log(message: String)
}

internal class XtreamIncrementalIndexExecutor(
    private val operations: XtreamIncrementalIndexOperations
) {
    suspend fun processSummary(
        provider: Provider,
        api: XtreamProvider,
        contentType: ContentType,
        maxCategories: Int?,
        maxRowsPerSlice: Int? = null,
        maxDurationMillis: Long? = null,
        onProgress: ((String) -> Unit)?
    ) {
        require(contentType == ContentType.MOVIE || contentType == ContentType.SERIES) {
            "Unsupported Xtream summary index section: $contentType"
        }
        val now = System.currentTimeMillis()
        val categories = operations.getCategories(provider.id, contentType)
        if (categories.isEmpty()) {
            operations.ensureCategoryShell(provider, api, contentType, now, onProgress)
        }
        val initialJob = operations.getJob(provider.id, contentType)
        val priorityCategoryId = initialJob?.priorityCategoryId
        val indexedCategories = operations.getCategories(provider.id, contentType)
        val resumeJob = initialJob?.takeIf { it.state in setOf("QUEUED", "RUNNING", "PARTIAL") }
        val categoryLimit = maxCategories?.coerceAtLeast(1) ?: Int.MAX_VALUE
        val restartIncompleteFailedSweep = resumeJob?.let { job ->
            job.state == "PARTIAL" &&
                job.failedCategories > 0 &&
                job.nextCategoryIndex >= indexedCategories.size
        } == true
        var nextCategoryIndex = if (restartIncompleteFailedSweep) {
            0
        } else {
            resumeJob?.nextCategoryIndex?.coerceIn(0, indexedCategories.size) ?: 0
        }
        var restoreMovieWatchProgressPending = false
        try {
            if (shouldAttemptFullStream(resumeJob, priorityCategoryId)) {
                when (val streamed = streamFullSummary(
                    provider = provider,
                    api = api,
                    contentType = contentType,
                    categories = indexedCategories,
                    totalCategories = indexedCategories.size,
                    now = now,
                    onProgress = onProgress
                )) {
                    is Result.Success -> return
                    is Result.Error -> {
                        streamed.exception?.let { error ->
                            if (XtreamIndexRecoveryPolicy.isAuthenticationFailure(error)) {
                                throw error
                            }
                        }
                        operations.log(
                            "Full ${contentType.name} stream index failed for provider ${provider.id}; falling back to category slices: ${streamed.message}"
                        )
                        upsertJob(
                            providerId = provider.id,
                            section = contentType.name,
                            state = "QUEUED",
                            now = System.currentTimeMillis(),
                            totalCategories = indexedCategories.size,
                            completedCategories = 0,
                            nextCategoryIndex = 0,
                            failedCategories = 0,
                            lastAttemptAt = now,
                            lastError = streamed.message
                        )
                        nextCategoryIndex = 0
                    }
                    Result.Loading -> Unit
                }
            }

            upsertJob(
                providerId = provider.id,
                section = contentType.name,
                state = "RUNNING",
                now = now,
                totalCategories = indexedCategories.size,
                completedCategories = if (restartIncompleteFailedSweep) 0 else resumeJob?.completedCategories ?: 0,
                nextCategoryIndex = nextCategoryIndex,
                failedCategories = 0,
                indexedRows = if (restartIncompleteFailedSweep) 0 else resumeJob?.indexedRows ?: 0,
                skippedMalformedRows = if (restartIncompleteFailedSweep) 0 else resumeJob?.skippedMalformedRows ?: 0,
                lastAttemptAt = now,
                lastError = null
            )

            var completedCategories = if (restartIncompleteFailedSweep) 0 else resumeJob?.completedCategories ?: 0
            var failedCategories = 0
            var indexedRows = if (restartIncompleteFailedSweep) 0 else resumeJob?.indexedRows ?: 0
            var skippedMalformedRows = if (restartIncompleteFailedSweep) 0 else resumeJob?.skippedMalformedRows ?: 0
            var lastError: Throwable? = null
            var priorityHandled = false

            data class CategoryIndexWork(
                val category: CategoryEntity,
                val advancesCursor: Boolean,
                val cursorAfterSuccess: Int? = null
            )

            val workItems = buildList {
                var selectionCursor = nextCategoryIndex
                val priorityIndex = priorityCategoryId?.let { id ->
                    indexedCategories.indexOfFirst { it.categoryId == id }
                        .takeIf { index -> index >= 0 }
                }
                if (priorityIndex != null && priorityIndex != nextCategoryIndex && size < categoryLimit) {
                    add(CategoryIndexWork(indexedCategories[priorityIndex], advancesCursor = false))
                }
                while (selectionCursor < indexedCategories.size && size < categoryLimit) {
                    val category = indexedCategories[selectionCursor]
                    selectionCursor++
                    if (
                        priorityCategoryId != null &&
                        category.categoryId == priorityCategoryId &&
                        any { it.category.categoryId == priorityCategoryId }
                    ) {
                        continue
                    }
                    add(
                        CategoryIndexWork(
                            category = category,
                            advancesCursor = true,
                            cursorAfterSuccess = selectionCursor
                        )
                    )
                }
            }

            var rowsProcessedThisSlice = 0
            val sliceStartedAt = operations.monotonicTimeMillis()
            workLoop@ for (workItem in workItems) {
                val category = workItem.category
                operations.progress(
                    provider.id,
                    onProgress,
                    "Indexing ${sectionLabel(contentType)}: ${category.name}"
                )
                val outcome = when (contentType) {
                    ContentType.MOVIE -> operations.fetchMovieCategory(provider, api, category)
                    ContentType.SERIES -> operations.fetchSeriesCategory(provider, api, category)
                    else -> error("Unsupported section")
                }
                when (val categoryOutcome = outcome.outcome) {
                    is CategoryFetchOutcome.Success -> {
                        rowsProcessedThisSlice += categoryOutcome.rawCount
                        val accepted = when (contentType) {
                            ContentType.MOVIE -> operations.upsertMovieSummaryBatch(
                                providerId = provider.id,
                                movies = categoryOutcome.items.filterIsInstance<Movie>(),
                                indexedAt = System.currentTimeMillis()
                            )
                            ContentType.SERIES -> operations.upsertSeriesSummaryBatch(
                                providerId = provider.id,
                                series = categoryOutcome.items.filterIsInstance<Series>(),
                                indexedAt = System.currentTimeMillis()
                            )
                            else -> 0
                        }
                        if (workItem.advancesCursor) {
                            nextCategoryIndex = requireNotNull(workItem.cursorAfterSuccess)
                            indexedRows += accepted
                            skippedMalformedRows +=
                                (categoryOutcome.rawCount - categoryOutcome.items.size).coerceAtLeast(0)
                            completedCategories++
                        }
                        if (contentType == ContentType.MOVIE && accepted > 0) {
                            restoreMovieWatchProgressPending = true
                        }
                        if (category.categoryId == priorityCategoryId) {
                            priorityHandled = true
                            upsertJob(
                                providerId = provider.id,
                                section = contentType.name,
                                state = "RUNNING",
                                now = System.currentTimeMillis(),
                                clearPriority = true
                            )
                        }
                    }
                    is CategoryFetchOutcome.Empty -> {
                        if (workItem.advancesCursor) {
                            nextCategoryIndex = requireNotNull(workItem.cursorAfterSuccess)
                            completedCategories++
                        }
                        if (category.categoryId == priorityCategoryId) {
                            priorityHandled = true
                            upsertJob(
                                providerId = provider.id,
                                section = contentType.name,
                                state = "RUNNING",
                                now = System.currentTimeMillis(),
                                clearPriority = true
                            )
                        }
                    }
                    is CategoryFetchOutcome.Failure -> {
                        if (XtreamIndexRecoveryPolicy.failureState(categoryOutcome.error) == "FAILED_PERMANENT") {
                            throw categoryOutcome.error
                        }
                        failedCategories++
                        lastError = categoryOutcome.error
                    }
                }
                upsertJob(
                    providerId = provider.id,
                    section = contentType.name,
                    state = if (failedCategories > 0) "PARTIAL" else "RUNNING",
                    now = System.currentTimeMillis(),
                    totalCategories = indexedCategories.size,
                    completedCategories = completedCategories,
                    nextCategoryIndex = nextCategoryIndex,
                    failedCategories = failedCategories,
                    indexedRows = indexedRows,
                    skippedMalformedRows = skippedMalformedRows,
                    lastAttemptAt = now,
                    lastError = lastError?.let(operations::sanitize)
                )
                if (outcome.outcome is CategoryFetchOutcome.Failure) {
                    break@workLoop
                }
                val rowBudgetReached = maxRowsPerSlice
                    ?.takeIf { it > 0 }
                    ?.let { rowsProcessedThisSlice >= it } == true
                val durationBudgetReached = maxDurationMillis
                    ?.takeIf { it > 0L }
                    ?.let { operations.monotonicTimeMillis() - sliceStartedAt >= it } == true
                if (rowBudgetReached || durationBudgetReached) break@workLoop
            }

            val finishedAt = System.currentTimeMillis()
            val hasMoreCategories = nextCategoryIndex < indexedCategories.size
            val finalState = when {
                hasMoreCategories -> "QUEUED"
                failedCategories > 0 -> "PARTIAL"
                else -> "SUCCESS"
            }
            val priorityWasNotPresent = priorityCategoryId != null &&
                indexedCategories.none { it.categoryId == priorityCategoryId }
            upsertJob(
                providerId = provider.id,
                section = contentType.name,
                state = finalState,
                now = finishedAt,
                totalCategories = indexedCategories.size,
                completedCategories = completedCategories,
                nextCategoryIndex = nextCategoryIndex,
                failedCategories = failedCategories,
                indexedRows = indexedRows,
                skippedMalformedRows = skippedMalformedRows,
                clearPriority = finalState == "SUCCESS" || priorityWasNotPresent || priorityHandled,
                lastAttemptAt = now,
                lastSuccessAt = finishedAt.takeIf { finalState == "SUCCESS" },
                lastError = lastError?.let(operations::sanitize)
            )
            operations.updateSummaryMetadata(provider.id, contentType, indexedRows, finalState, finishedAt)
            if (hasMoreCategories) {
                operations.scheduleIndex(provider.id, contentType)
            }
            if (!hasMoreCategories && failedCategories > 0) {
                throw IllegalStateException(
                    "${sectionLabel(contentType)} indexing completed partially.",
                    lastError
                )
            }
        } finally {
            if (contentType == ContentType.MOVIE && restoreMovieWatchProgressPending) {
                operations.restoreMovieWatchProgress(provider.id)
            }
        }
    }

    private suspend fun streamFullSummary(
        provider: Provider,
        api: XtreamProvider,
        contentType: ContentType,
        categories: List<CategoryEntity>,
        totalCategories: Int,
        now: Long,
        onProgress: ((String) -> Unit)?
    ): Result<Int> {
        operations.progress(provider.id, onProgress, "Indexing ${sectionLabel(contentType)}...")
        upsertJob(
            providerId = provider.id,
            section = contentType.name,
            state = "RUNNING",
            now = now,
            totalCategories = totalCategories,
            completedCategories = 0,
            nextCategoryIndex = 0,
            failedCategories = 0,
            indexedRows = 0,
            skippedMalformedRows = 0,
            lastAttemptAt = now,
            lastError = null
        )

        var indexedRows = 0
        var restoreMovieWatchProgressPending = false
        val categoryNamesById = categories.associate { it.categoryId to it.name }
        val adultCategoryIds = categories.filter { it.isAdult }.mapTo(mutableSetOf()) { it.categoryId }
        try {
            val streamResult = when (contentType) {
                ContentType.MOVIE -> operations.streamMovies(provider, api, adultCategoryIds) { batch ->
                    val namedBatch = batch.withMovieCategoryNames(categoryNamesById)
                    val accepted = operations.upsertMovieSummaryBatch(provider.id, namedBatch, System.currentTimeMillis())
                    indexedRows += accepted
                    if (accepted > 0) restoreMovieWatchProgressPending = true
                    upsertJob(
                        providerId = provider.id,
                        section = contentType.name,
                        state = "RUNNING",
                        now = System.currentTimeMillis(),
                        totalCategories = totalCategories,
                        completedCategories = 0,
                        nextCategoryIndex = 0,
                        indexedRows = indexedRows,
                        lastAttemptAt = now,
                        lastError = null
                    )
                }
                ContentType.SERIES -> operations.streamSeries(provider, api, adultCategoryIds) { batch ->
                    val namedBatch = batch.withSeriesCategoryNames(categoryNamesById)
                    val accepted = operations.upsertSeriesSummaryBatch(provider.id, namedBatch, System.currentTimeMillis())
                    indexedRows += accepted
                    upsertJob(
                        providerId = provider.id,
                        section = contentType.name,
                        state = "RUNNING",
                        now = System.currentTimeMillis(),
                        totalCategories = totalCategories,
                        completedCategories = 0,
                        nextCategoryIndex = 0,
                        indexedRows = indexedRows,
                        lastAttemptAt = now,
                        lastError = null
                    )
                }
                else -> Result.error("Unsupported Xtream summary stream section: $contentType")
            }

            return when (streamResult) {
                is Result.Success -> {
                    val finishedAt = System.currentTimeMillis()
                    val acceptedCount = indexedRows.coerceAtLeast(streamResult.data)
                    upsertJob(
                        providerId = provider.id,
                        section = contentType.name,
                        state = "SUCCESS",
                        now = finishedAt,
                        totalCategories = totalCategories,
                        completedCategories = totalCategories,
                        nextCategoryIndex = totalCategories,
                        failedCategories = 0,
                        indexedRows = acceptedCount,
                        skippedMalformedRows = 0,
                        clearPriority = true,
                        lastAttemptAt = now,
                        lastSuccessAt = finishedAt,
                        lastError = null
                    )
                    operations.updateSummaryMetadata(provider.id, contentType, acceptedCount, "SUCCESS", finishedAt)
                    Result.success(acceptedCount)
                }
                is Result.Error -> streamResult
                Result.Loading -> Result.error("Xtream summary stream did not complete")
            }
        } finally {
            if (contentType == ContentType.MOVIE && restoreMovieWatchProgressPending) {
                operations.restoreMovieWatchProgress(provider.id)
            }
        }
    }

    private fun shouldAttemptFullStream(
        resumeJob: XtreamIndexJobEntity?,
        priorityCategoryId: Long?
    ): Boolean {
        if (priorityCategoryId != null && resumeJob?.nextCategoryIndex != 0) return false
        if (resumeJob == null) return true
        return resumeJob.nextCategoryIndex == 0 &&
            resumeJob.completedCategories == 0 &&
            resumeJob.failedCategories == 0 &&
            resumeJob.indexedRows == 0
    }

    private fun List<Movie>.withMovieCategoryNames(categoryNamesById: Map<Long, String>): List<Movie> = map { movie ->
        val categoryName = movie.categoryId?.let(categoryNamesById::get)
        if (categoryName == null || categoryName == movie.categoryName) movie
        else movie.copy(categoryName = categoryName)
    }

    private fun List<Series>.withSeriesCategoryNames(categoryNamesById: Map<Long, String>): List<Series> = map { series ->
        val categoryName = series.categoryId?.let(categoryNamesById::get)
        if (categoryName == null || categoryName == series.categoryName) series
        else series.copy(categoryName = categoryName)
    }

    private suspend fun upsertJob(
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

    private fun sectionLabel(contentType: ContentType): String = when (contentType) {
        ContentType.MOVIE -> "Movies"
        ContentType.SERIES -> "Series"
        ContentType.LIVE -> "Live TV"
        ContentType.VOD -> "VOD"
        ContentType.SERIES_EPISODE -> "Episodes"
    }
}
