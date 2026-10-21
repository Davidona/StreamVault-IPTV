package com.streamvault.data.sync

import com.streamvault.data.local.entity.CategoryEntity
import com.streamvault.data.local.entity.StalkerIndexJobEntity
import com.streamvault.data.remote.stalker.StalkerPagedResult
import com.streamvault.data.remote.stalker.StalkerProvider
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.LegacyProvider as Provider
import com.streamvault.domain.model.Movie
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.Series

internal data class StalkerIncrementalIndexCallbacks(
    val runtimeProfile: () -> CatalogSyncRuntimeProfile,
    val allVisibleCategories: suspend (Long, ContentType) -> List<CategoryEntity>,
    val visibleCategories: suspend (ContentType, List<CategoryEntity>, StalkerProvider) -> List<CategoryEntity>,
    val getJob: suspend (Long, ContentType) -> StalkerIndexJobEntity?,
    val getHydration: suspend (Long, ContentType, Long) -> StalkerHydrationSnapshot?,
    val currentIndexedRowCount: suspend (Long, ContentType) -> Int,
    val categoriesWithLocalRows: suspend (Long, ContentType) -> Map<Long, Int>,
    val pruneStaleRows: suspend (Long, ContentType) -> Int,
    val updateSummaryMetadata: suspend (Long, ContentType, Int, String, Long) -> Unit,
    val fetchSummaryPage: suspend (Provider, StalkerProvider, ContentType, Long, Int) -> Result<StalkerPagedResult<out Any>>,
    val fetchWildcardPage: suspend (Provider, StalkerProvider, ContentType, Long, Int) -> Result<StalkerPagedResult<out Any>>,
    val markAttemptStarted: suspend (Long, ContentType, Long, StalkerHydrationSnapshot?, Int, Long) -> Unit,
    val markAttemptSucceeded: suspend (
        Long,
        ContentType,
        Long,
        StalkerHydrationSnapshot?,
        Int,
        Long,
        Int,
        Int,
        Int,
        Int?,
        Int?,
        Boolean,
        Boolean,
        String?,
        String?
    ) -> Unit,
    val markAttemptFailed: suspend (
        Long,
        ContentType,
        Long,
        StalkerHydrationSnapshot?,
        Int,
        Long,
        String,
        Boolean,
        String?
    ) -> Unit,
    val upsertMovieSummaryBatch: suspend (Long, List<Movie>, Long) -> Unit,
    val upsertSeriesSummaryBatch: suspend (Long, List<Series>, Long) -> Unit,
    val upsertVodDerivedSeriesSummaryBatch: suspend (Long, List<Series>, Long) -> Unit,
    val recordRequestFailure: suspend (Long, Throwable?) -> Unit,
    val failureState: (Throwable) -> String,
    val progress: (Long, ((String) -> Unit)?, String) -> Unit,
    val restoreMovieWatchProgress: suspend (Long) -> Unit,
    val upsertJob: suspend (CatalogIndexJobUpdate) -> Unit,
    val log: (String) -> Unit,
    val commitCatalogPage: suspend (Provider, Long, StalkerPagedResult<out Any>) -> Unit = { _, _, _ -> },
    val preserveCategoryRows: suspend (Long, ContentType, Long) -> Unit = { _, _, _ -> }
)

internal class CallbackStalkerIncrementalIndexOperations(
    private val callbacks: StalkerIncrementalIndexCallbacks
) : StalkerIncrementalIndexOperations {
    override suspend fun commitCatalogPage(provider: Provider, categoryId: Long, page: StalkerPagedResult<out Any>) =
        callbacks.commitCatalogPage(provider, categoryId, page)
    override suspend fun preserveCategoryRows(providerId: Long, type: ContentType, categoryId: Long) =
        callbacks.preserveCategoryRows(providerId, type, categoryId)
    override val runtimeProfile: CatalogSyncRuntimeProfile
        get() = callbacks.runtimeProfile()

    override suspend fun allVisibleCategories(providerId: Long, contentType: ContentType): List<CategoryEntity> =
        callbacks.allVisibleCategories(providerId, contentType)

    override suspend fun visibleCategories(
        contentType: ContentType,
        categories: List<CategoryEntity>,
        api: StalkerProvider
    ): List<CategoryEntity> = callbacks.visibleCategories(contentType, categories, api)

    override suspend fun getJob(providerId: Long, contentType: ContentType): StalkerIndexJobEntity? =
        callbacks.getJob(providerId, contentType)

    override suspend fun getHydration(
        providerId: Long,
        contentType: ContentType,
        categoryId: Long
    ): StalkerHydrationSnapshot? = callbacks.getHydration(providerId, contentType, categoryId)

    override suspend fun currentIndexedRowCount(providerId: Long, contentType: ContentType): Int =
        callbacks.currentIndexedRowCount(providerId, contentType)

    override suspend fun categoriesWithLocalRows(
        providerId: Long,
        contentType: ContentType
    ): Map<Long, Int> = callbacks.categoriesWithLocalRows(providerId, contentType)

    override suspend fun pruneStaleRows(providerId: Long, contentType: ContentType): Int =
        callbacks.pruneStaleRows(providerId, contentType)

    override suspend fun updateSummaryMetadata(
        providerId: Long,
        contentType: ContentType,
        indexedRows: Int,
        finalState: String,
        now: Long
    ) = callbacks.updateSummaryMetadata(providerId, contentType, indexedRows, finalState, now)

    override suspend fun fetchSummaryPage(
        provider: Provider,
        api: StalkerProvider,
        contentType: ContentType,
        categoryId: Long,
        page: Int
    ): Result<StalkerPagedResult<out Any>> = callbacks.fetchSummaryPage(provider, api, contentType, categoryId, page)

    override suspend fun fetchWildcardPage(
        provider: Provider,
        api: StalkerProvider,
        contentType: ContentType,
        categoryId: Long,
        page: Int
    ): Result<StalkerPagedResult<out Any>> = callbacks.fetchWildcardPage(provider, api, contentType, categoryId, page)

    override suspend fun markAttemptStarted(
        providerId: Long,
        contentType: ContentType,
        categoryId: Long,
        hydration: StalkerHydrationSnapshot?,
        attemptedPage: Int,
        now: Long
    ) = callbacks.markAttemptStarted(providerId, contentType, categoryId, hydration, attemptedPage, now)

    override suspend fun markAttemptSucceeded(
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
    ) = callbacks.markAttemptSucceeded(
        providerId,
        contentType,
        categoryId,
        hydration,
        attemptedPage,
        now,
        itemCount,
        totalPages,
        pageSize,
        advertisedTotalItems,
        advertisedTotalPages,
        pageComplete,
        truncated,
        terminationReason,
        pageFingerprint
    )

    override suspend fun markAttemptFailed(
        providerId: Long,
        contentType: ContentType,
        categoryId: Long,
        hydration: StalkerHydrationSnapshot?,
        attemptedPage: Int,
        now: Long,
        message: String,
        retryable: Boolean,
        pageFingerprint: String?
    ) = callbacks.markAttemptFailed(
        providerId,
        contentType,
        categoryId,
        hydration,
        attemptedPage,
        now,
        message,
        retryable,
        pageFingerprint
    )

    override suspend fun upsertMovieSummaryBatch(providerId: Long, movies: List<Movie>, indexedAt: Long) =
        callbacks.upsertMovieSummaryBatch(providerId, movies, indexedAt)

    override suspend fun upsertSeriesSummaryBatch(providerId: Long, series: List<Series>, indexedAt: Long) =
        callbacks.upsertSeriesSummaryBatch(providerId, series, indexedAt)

    override suspend fun upsertVodDerivedSeriesSummaryBatch(providerId: Long, series: List<Series>, indexedAt: Long) =
        callbacks.upsertVodDerivedSeriesSummaryBatch(providerId, series, indexedAt)

    override suspend fun recordRequestFailure(providerId: Long, error: Throwable?) =
        callbacks.recordRequestFailure(providerId, error)

    override fun failureState(error: Throwable): String = callbacks.failureState(error)

    override fun progress(providerId: Long, callback: ((String) -> Unit)?, message: String) =
        callbacks.progress(providerId, callback, message)

    override suspend fun restoreMovieWatchProgress(providerId: Long) =
        callbacks.restoreMovieWatchProgress(providerId)

    override suspend fun upsertJob(update: CatalogIndexJobUpdate) = callbacks.upsertJob(update)

    override fun log(message: String) = callbacks.log(message)
}
