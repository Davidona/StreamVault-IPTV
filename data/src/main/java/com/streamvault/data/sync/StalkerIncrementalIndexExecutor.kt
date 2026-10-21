package com.streamvault.data.sync

import com.streamvault.data.BuildConfig
import com.streamvault.data.local.entity.CategoryEntity
import com.streamvault.data.local.entity.StalkerIndexJobEntity
import com.streamvault.data.remote.stalker.StalkerPagedResult
import com.streamvault.data.remote.stalker.StalkerProvider
import com.streamvault.data.remote.stalker.StalkerVodCatalogItem
import com.streamvault.domain.model.CatalogLayout
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.LegacyProvider as Provider
import com.streamvault.domain.model.Movie
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.Series
import com.streamvault.domain.model.VodCatalogItem

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

    /** Local indexed row count per category id; used to find categories the wildcard walk did not cover. */
    suspend fun categoriesWithLocalRows(providerId: Long, contentType: ContentType): Map<Long, Int>

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

    suspend fun commitCatalogPage(provider: Provider, categoryId: Long, page: StalkerPagedResult<out Any>) {}
    suspend fun preserveCategoryRows(providerId: Long, type: ContentType, categoryId: Long) {}

    suspend fun recordRequestFailure(providerId: Long, error: Throwable?)

    fun failureState(error: Throwable): String

    fun progress(providerId: Long, callback: ((String) -> Unit)?, message: String)

    suspend fun restoreMovieWatchProgress(providerId: Long)

    suspend fun upsertJob(update: CatalogIndexJobUpdate)

    fun log(message: String)
}

/** Durable category paging used by setup, refresh, and background continuation. */
internal class StalkerIncrementalIndexExecutor(
    operations: StalkerIncrementalIndexOperations,
    configuredWildcardPageConcurrency: Int = BuildConfig.STALKER_INDEX_PAGE_CONCURRENCY
) {
    private val importer = StalkerPagedCatalogImporter(operations, configuredWildcardPageConcurrency)

    suspend fun prepareCatalog(provider: Provider, api: StalkerProvider, contentType: ContentType,
        freshSource: Boolean, onProgress: ((String) -> Unit)?) =
        importer.prepare(provider, api, contentType, freshSource, onProgress)

    suspend fun processCategoryHeads(provider: Provider, api: StalkerProvider, contentType: ContentType,
        onProgress: ((String) -> Unit)?) =
        importer.run(provider, api, contentType, headsOnly = true, onProgress = onProgress)

    suspend fun processSummary(provider: Provider, api: StalkerProvider, contentType: ContentType,
        maxCategories: Int, onProgress: ((String) -> Unit)?, maxPages: Int? = null) =
        importer.run(provider, api, contentType, maxPages = maxPages, onProgress = onProgress)
}
