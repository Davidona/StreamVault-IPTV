package com.streamvault.data.sync

import com.streamvault.data.local.entity.CategoryEntity
import com.streamvault.data.local.entity.XtreamIndexJobEntity
import com.streamvault.data.remote.xtream.XtreamProvider
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.LegacyProvider as Provider
import com.streamvault.domain.model.Movie
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.Series

internal data class XtreamIncrementalIndexCallbacks(
    val beginSnapshot: suspend (Long, ContentType) -> Unit,
    val finishSnapshot: suspend (Long, ContentType) -> Unit,
    val getCategories: suspend (Long, ContentType) -> List<CategoryEntity>,
    val ensureCategoryShell: suspend (Provider, XtreamProvider, ContentType, Long, ((String) -> Unit)?) -> Unit,
    val getJob: suspend (Long, ContentType) -> XtreamIndexJobEntity?,
    val shouldRunSummary: (XtreamIndexJobEntity?) -> Boolean,
    val fetchMovieCategory: suspend (Provider, XtreamProvider, CategoryEntity) -> TimedCategoryOutcome<Movie>,
    val fetchSeriesCategory: suspend (Provider, XtreamProvider, CategoryEntity) -> TimedCategoryOutcome<Series>,
    val upsertMovieSummaryBatch: suspend (Long, List<Movie>, Long) -> Int,
    val upsertSeriesSummaryBatch: suspend (Long, List<Series>, Long) -> Int,
    val streamMovies: suspend (Provider, XtreamProvider, Set<Long>, suspend (List<Movie>) -> Unit) -> Result<Int>,
    val streamSeries: suspend (Provider, XtreamProvider, Set<Long>, suspend (List<Series>) -> Unit) -> Result<Int>,
    val upsertJob: suspend (CatalogIndexJobUpdate) -> Unit,
    val updateSummaryMetadata: suspend (Long, ContentType, Int, String, Long) -> Unit,
    val scheduleIndex: (Long, ContentType) -> Unit,
    val progress: (Long, ((String) -> Unit)?, String) -> Unit,
    val restoreMovieWatchProgress: suspend (Long) -> Unit,
    val sanitize: (Throwable?) -> String,
    val log: (String) -> Unit
)

internal class CallbackXtreamIncrementalIndexOperations(
    private val callbacks: XtreamIncrementalIndexCallbacks
) : XtreamIncrementalIndexOperations {
    override suspend fun beginSnapshot(providerId: Long, contentType: ContentType) = callbacks.beginSnapshot(providerId, contentType)
    override suspend fun finishSnapshot(providerId: Long, contentType: ContentType) = callbacks.finishSnapshot(providerId, contentType)

    override suspend fun getCategories(providerId: Long, contentType: ContentType): List<CategoryEntity> =
        callbacks.getCategories(providerId, contentType)

    override suspend fun ensureCategoryShell(
        provider: Provider,
        api: XtreamProvider,
        contentType: ContentType,
        now: Long,
        onProgress: ((String) -> Unit)?
    ) = callbacks.ensureCategoryShell(provider, api, contentType, now, onProgress)

    override suspend fun getJob(providerId: Long, contentType: ContentType): XtreamIndexJobEntity? =
        callbacks.getJob(providerId, contentType)

    override fun shouldRunSummary(job: XtreamIndexJobEntity?): Boolean = callbacks.shouldRunSummary(job)

    override suspend fun fetchMovieCategory(
        provider: Provider,
        api: XtreamProvider,
        category: CategoryEntity
    ): TimedCategoryOutcome<Movie> = callbacks.fetchMovieCategory(provider, api, category)

    override suspend fun fetchSeriesCategory(
        provider: Provider,
        api: XtreamProvider,
        category: CategoryEntity
    ): TimedCategoryOutcome<Series> = callbacks.fetchSeriesCategory(provider, api, category)

    override suspend fun upsertMovieSummaryBatch(
        providerId: Long,
        movies: List<Movie>,
        indexedAt: Long
    ): Int = callbacks.upsertMovieSummaryBatch(providerId, movies, indexedAt)

    override suspend fun upsertSeriesSummaryBatch(
        providerId: Long,
        series: List<Series>,
        indexedAt: Long
    ): Int = callbacks.upsertSeriesSummaryBatch(providerId, series, indexedAt)

    override suspend fun streamMovies(
        provider: Provider,
        api: XtreamProvider,
        adultCategoryIds: Set<Long>,
        onBatch: suspend (List<Movie>) -> Unit
    ): Result<Int> = callbacks.streamMovies(provider, api, adultCategoryIds, onBatch)

    override suspend fun streamSeries(
        provider: Provider,
        api: XtreamProvider,
        adultCategoryIds: Set<Long>,
        onBatch: suspend (List<Series>) -> Unit
    ): Result<Int> = callbacks.streamSeries(provider, api, adultCategoryIds, onBatch)

    override suspend fun upsertJob(update: CatalogIndexJobUpdate) = callbacks.upsertJob(update)

    override suspend fun updateSummaryMetadata(
        providerId: Long,
        contentType: ContentType,
        indexedRows: Int,
        state: String,
        now: Long
    ) = callbacks.updateSummaryMetadata(providerId, contentType, indexedRows, state, now)

    override fun scheduleIndex(providerId: Long, contentType: ContentType) =
        callbacks.scheduleIndex(providerId, contentType)

    override fun progress(providerId: Long, callback: ((String) -> Unit)?, message: String) =
        callbacks.progress(providerId, callback, message)

    override suspend fun restoreMovieWatchProgress(providerId: Long) =
        callbacks.restoreMovieWatchProgress(providerId)

    override fun sanitize(error: Throwable?): String = callbacks.sanitize(error)

    override fun log(message: String) = callbacks.log(message)
}
