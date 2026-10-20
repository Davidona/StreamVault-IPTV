package com.streamvault.data.sync

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.entity.CategoryEntity
import com.streamvault.data.local.entity.StalkerIndexJobEntity
import com.streamvault.data.remote.stalker.StalkerPagedResult
import com.streamvault.data.remote.stalker.StalkerProvider
import com.streamvault.domain.model.CatalogLayout
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.LegacyProvider
import com.streamvault.domain.model.Movie
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.Result
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.junit.Test

class StalkerIncrementalIndexExecutorTest {
    @Test
    fun wildcardWindowFetchesConcurrentlyAndCommitsCheckpointPagesInOrder() = runTest {
        val wildcard = CategoryEntity(categoryId = -9L, name = "All", type = ContentType.MOVIE, providerId = 91L)
        val operations = WildcardIndexOperations(wildcard)
        val api = mock<StalkerProvider>()
        whenever(api.isWildcardCategory(org.mockito.kotlin.any(), org.mockito.kotlin.any())).thenReturn(false)
        whenever(api.isWildcardCategory(ContentType.MOVIE, wildcard.categoryId)).thenReturn(true)

        StalkerIncrementalIndexExecutor(operations, configuredWildcardPageConcurrency = 2, adaptiveCategoryPaging = false)
            .processSummary(
                provider = LegacyProvider(
                    id = 91L,
                    name = "Portal",
                    type = ProviderType.STALKER_PORTAL,
                    serverUrl = "https://portal.invalid",
                    catalogLayout = CatalogLayout.SPLIT
                ),
                api = api,
                contentType = ContentType.MOVIE,
                maxCategories = 2,
                onProgress = null,
                maxPages = 2
            )

        assertThat(operations.maxConcurrentFetches).isEqualTo(2)
        assertThat(operations.completedFetchPages).containsExactly(2, 1).inOrder()
        assertThat(operations.committedPages).containsExactly(1, 2).inOrder()
        assertThat(operations.hydration?.lastSuccessfulPage).isEqualTo(2)
        assertThat(operations.indexedMovies.map(Movie::id)).containsExactly(1L, 2L).inOrder()
    }

    @Test
    fun failedPageDoesNotCommitLaterPageAndRetryResumesFromTheGap() = runTest {
        val wildcard = CategoryEntity(categoryId = -9L, name = "All", type = ContentType.MOVIE, providerId = 91L)
        val operations = WildcardIndexOperations(wildcard, failPageOneOnce = true)
        val api = mock<StalkerProvider>()
        whenever(api.isWildcardCategory(org.mockito.kotlin.any(), org.mockito.kotlin.any())).thenReturn(false)
        whenever(api.isWildcardCategory(ContentType.MOVIE, wildcard.categoryId)).thenReturn(true)
        val executor = StalkerIncrementalIndexExecutor(operations, configuredWildcardPageConcurrency = 2, adaptiveCategoryPaging = false)
        val provider = LegacyProvider(
            id = 91L,
            name = "Portal",
            type = ProviderType.STALKER_PORTAL,
            serverUrl = "https://portal.invalid",
            catalogLayout = CatalogLayout.SPLIT
        )

        executor.processSummary(provider, api, ContentType.MOVIE, maxCategories = 2, onProgress = null, maxPages = 2)

        assertThat(operations.committedPages).isEmpty()
        assertThat(operations.failedPages).containsExactly(1)
        assertThat(operations.indexedMovies).isEmpty()
        assertThat(operations.hydration?.lastSuccessfulPage ?: 0).isEqualTo(0)

        executor.processSummary(provider, api, ContentType.MOVIE, maxCategories = 2, onProgress = null, maxPages = 2)

        assertThat(operations.committedPages).containsExactly(1, 2).inOrder()
        assertThat(operations.hydration?.lastSuccessfulPage).isEqualTo(2)
        assertThat(operations.indexedMovies.map(Movie::id)).containsExactly(1L, 2L).inOrder()
    }

    @Test
    fun categoryHeadSweepFetchesPageOneForEveryVisibleCategoryWithoutWildcardWalk() = runTest {
        val wildcard = CategoryEntity(categoryId = -9L, name = "All", type = ContentType.MOVIE, providerId = 91L)
        val action = CategoryEntity(categoryId = 10L, name = "Action", type = ContentType.MOVIE, providerId = 91L)
        val drama = CategoryEntity(categoryId = 20L, name = "Drama", type = ContentType.MOVIE, providerId = 91L)
        val operations = WildcardIndexOperations(
            wildcard,
            categories = listOf(wildcard, action, drama),
            categoryConcurrency = 1
        )
        val api = mock<StalkerProvider>()
        whenever(api.isWildcardCategory(org.mockito.kotlin.any(), org.mockito.kotlin.any())).thenReturn(false)
        whenever(api.isWildcardCategory(ContentType.MOVIE, wildcard.categoryId)).thenReturn(true)

        StalkerIncrementalIndexExecutor(operations, configuredWildcardPageConcurrency = 2, adaptiveCategoryPaging = false)
            .processCategoryHeads(
                provider = LegacyProvider(
                    id = 91L,
                    name = "Portal",
                    type = ProviderType.STALKER_PORTAL,
                    serverUrl = "https://portal.invalid",
                    catalogLayout = CatalogLayout.SPLIT
                ),
                api = api,
                contentType = ContentType.MOVIE,
                onProgress = null
            )

        assertThat(operations.summaryFetches).containsExactly(10L to 1, 20L to 1).inOrder()
        assertThat(operations.maxConcurrentFetches).isEqualTo(2)
        assertThat(operations.completedFetchPages).isEmpty()
        assertThat(operations.categoryHydration(10L)?.lastSuccessfulPage).isEqualTo(1)
        assertThat(operations.categoryHydration(20L)?.lastSuccessfulPage).isEqualTo(1)
        assertThat(operations.indexedMovies).hasSize(2)
    }

    @Test
    fun adaptiveHeadsProbeOnceThenFetchEveryCategoryHeadAndResumeAtPageTwo() = runTest {
        val wildcard = CategoryEntity(categoryId = -9L, name = "All", type = ContentType.MOVIE, providerId = 91L)
        val categories = (1L..24L).map { CategoryEntity(categoryId = it, name = "Category $it", type = ContentType.MOVIE, providerId = 91L) }
        val operations = WildcardIndexOperations(wildcard, categories = listOf(wildcard) + categories,
            singlePageProbe = true, summaryPageCount = 9)
        val api = mock<StalkerProvider>()
        whenever(api.isWildcardCategory(org.mockito.kotlin.any(), org.mockito.kotlin.any())).thenReturn(false)
        whenever(api.isWildcardCategory(ContentType.MOVIE, wildcard.categoryId)).thenReturn(true)
        val executor = StalkerIncrementalIndexExecutor(operations, 8, adaptiveCategoryPaging = true)
        val provider = LegacyProvider(91L, "Portal", ProviderType.STALKER_PORTAL,
            serverUrl = "https://portal.invalid", catalogLayout = CatalogLayout.SPLIT)

        executor.processCategoryHeads(provider, api, ContentType.MOVIE, null)

        assertThat(operations.completedFetchPages).containsExactly(1)
        assertThat(operations.summaryFetches).containsExactlyElementsIn(categories.map { it.categoryId to 1 })
        assertThat(operations.summaryFetchCountAtFirstCommit).isEqualTo(8)
        assertThat(operations.maxConcurrentFetches).isEqualTo(8)
        assertThat(operations.metadataPublications.any { (state, rows) -> state == "RUNNING" && rows in 2..24 }).isTrue()
        categories.forEach { assertThat(operations.categoryHydration(it.categoryId)?.lastSuccessfulPage).isEqualTo(1) }
        operations.summaryFetches.clear()
        executor.processCategoryHeads(provider, api, ContentType.MOVIE, null)
        assertThat(operations.summaryFetches).isEmpty()
        assertThat(operations.completedFetchPages).containsExactly(1)
    }

    @Test
    fun adaptiveSingleCategoryFillsEightPageWindowAndCommitsOutOfOrderRepliesInOrder() = runTest {
        val category = CategoryEntity(categoryId = 10L, name = "Action", type = ContentType.MOVIE, providerId = 91L)
        val operations = WildcardIndexOperations(category, summaryPageCount = 9)
        val api = mock<StalkerProvider>()
        whenever(api.isWildcardCategory(org.mockito.kotlin.any(), org.mockito.kotlin.any())).thenReturn(false)
        val executor = StalkerIncrementalIndexExecutor(operations, 8, adaptiveCategoryPaging = true)
        val provider = LegacyProvider(91L, "Portal", ProviderType.STALKER_PORTAL, serverUrl = "https://portal.invalid")
        executor.processCategoryHeads(provider, api, ContentType.MOVIE, null)
        operations.committedPages.clear()

        executor.processSummary(provider, api, ContentType.MOVIE, 1, null, maxPages = 8)

        assertThat(operations.maxConcurrentFetches).isEqualTo(8)
        assertThat(operations.summaryCompletions.takeLast(8).first()).isNotEqualTo(2)
        assertThat(operations.committedPages).containsExactlyElementsIn(2..9).inOrder()
        assertThat(operations.categoryHydration(10L)?.itemCount).isEqualTo(9)
        assertThat(operations.categoryHydration(10L)?.isComplete).isTrue()
    }

    @Test
    fun adaptiveCategoryWindowNeverCheckpointsPastAFailedPage() = runTest {
        val category = CategoryEntity(categoryId = 10L, name = "Action", type = ContentType.MOVIE, providerId = 91L)
        val operations = WildcardIndexOperations(category, summaryPageCount = 9,
            summaryFailurePageOnce = 4, summaryFailuresRemaining = 2)
        val api = mock<StalkerProvider>()
        whenever(api.isWildcardCategory(org.mockito.kotlin.any(), org.mockito.kotlin.any())).thenReturn(false)
        val executor = StalkerIncrementalIndexExecutor(operations, 8, adaptiveCategoryPaging = true)
        val provider = LegacyProvider(91L, "Portal", ProviderType.STALKER_PORTAL, serverUrl = "https://portal.invalid")
        executor.processCategoryHeads(provider, api, ContentType.MOVIE, null)
        operations.committedPages.clear()
        executor.processSummary(provider, api, ContentType.MOVIE, 1, null, maxPages = 8)
        assertThat(operations.committedPages).containsExactly(2, 3).inOrder()
        operations.committedPages.clear()
        assertThat(operations.categoryHydration(10L)?.lastSuccessfulPage).isEqualTo(3)
        executor.processSummary(provider, api, ContentType.MOVIE, 1, null, maxPages = 8)
        assertThat(operations.committedPages).containsExactlyElementsIn(4..9).inOrder()
        assertThat(operations.categoryHydration(10L)?.isComplete).isTrue()
    }

    private class WildcardIndexOperations(
        private val wildcard: CategoryEntity,
        private var failPageOneOnce: Boolean = false,
        private val categories: List<CategoryEntity> = listOf(wildcard),
        categoryConcurrency: Int = 2,
        private val singlePageProbe: Boolean = false,
        private val summaryPageCount: Int = 2,
        private var summaryFailurePageOnce: Int? = null,
        private var summaryFailuresRemaining: Int = 1
    ) : StalkerIncrementalIndexOperations {
        override val runtimeProfile = CatalogSyncRuntimeProfile(
            tier = DeviceSyncTier.HIGH,
            stageBatchSize = 100,
            maxCategoryConcurrency = categoryConcurrency,
            preferSegmentedLiveOnboarding = false,
            deferBackgroundWorkOnLowMemory = false,
            snapshot = SyncDeviceSnapshot(
                memoryClassMb = 512,
                largeMemoryClassMb = null,
                isLowRamDevice = false,
                isCurrentlyLowOnMemory = false,
                availableMemMb = 512,
                maxHeapMb = 512,
                isTelevision = false,
                manufacturer = "test",
                model = "test",
                sdkInt = 35
            )
        )

        var hydration: StalkerHydrationSnapshot? = null
            private set
        private val categoryHydrations = mutableMapOf<Long, StalkerHydrationSnapshot>()
        val committedPages = mutableListOf<Int>()
        val failedPages = mutableListOf<Int>()
        val completedFetchPages = mutableListOf<Int>()
        val summaryFetches = mutableListOf<Pair<Long, Int>>()
        var summaryFetchCountAtFirstCommit: Int? = null
        val summaryCompletions = mutableListOf<Int>()
        val indexedMovies = mutableListOf<Movie>()
        val metadataPublications = mutableListOf<Pair<String, Int>>()
        var activeFetches = 0
            private set
        var maxConcurrentFetches = 0
            private set
        private val pageOneStarted = CompletableDeferred<Unit>()
        private val pageTwoStarted = CompletableDeferred<Unit>()

        override suspend fun allVisibleCategories(providerId: Long, contentType: ContentType) = categories

        override suspend fun visibleCategories(
            contentType: ContentType,
            categories: List<CategoryEntity>,
            api: StalkerProvider
        ) = categories.filterNot { category ->
            category.categoryId == wildcard.categoryId && categories.any { it.categoryId != wildcard.categoryId }
        }

        override suspend fun getJob(providerId: Long, contentType: ContentType): StalkerIndexJobEntity? = null

        override suspend fun getHydration(
            providerId: Long,
            contentType: ContentType,
            categoryId: Long
        ): StalkerHydrationSnapshot? = categoryHydrations[categoryId]
            ?: hydration.takeIf { categoryId == wildcard.categoryId }

        override suspend fun currentIndexedRowCount(providerId: Long, contentType: ContentType): Int = indexedMovies.size

        override suspend fun categoriesWithLocalRows(providerId: Long, contentType: ContentType): Map<Long, Int> = emptyMap()

        override suspend fun pruneStaleRows(providerId: Long, contentType: ContentType): Int = 0

        override suspend fun updateSummaryMetadata(
            providerId: Long,
            contentType: ContentType,
            indexedRows: Int,
            finalState: String,
            now: Long
        ) { metadataPublications += finalState to indexedRows }

        override suspend fun fetchSummaryPage(
            provider: LegacyProvider,
            api: StalkerProvider,
            contentType: ContentType,
            categoryId: Long,
            page: Int
        ): Result<StalkerPagedResult<out Any>> {
            summaryFetches += categoryId to page
            activeFetches += 1
            maxConcurrentFetches = maxOf(maxConcurrentFetches, activeFetches)
            return try {
                delay(if (page == 2) 200 else 20)
                summaryCompletions += page
                if (summaryFailurePageOnce == page && summaryFailuresRemaining-- > 0) {
                    return Result.error("Temporary page failure", java.io.IOException("reset"))
                }
                Result.success(
                    StalkerPagedResult(
                        items = listOf(Movie(id = categoryId * 100 + page, name = "Category $categoryId", streamId = categoryId * 100 + page)),
                        page = page,
                        totalPages = summaryPageCount,
                        pageSize = 1,
                        advertisedTotalItems = summaryPageCount,
                        advertisedTotalPages = summaryPageCount
                    )
                )
            } finally {
                activeFetches -= 1
            }
        }

        override suspend fun fetchWildcardPage(
            provider: LegacyProvider,
            api: StalkerProvider,
            contentType: ContentType,
            categoryId: Long,
            page: Int
        ): Result<StalkerPagedResult<out Any>> {
            activeFetches += 1
            maxConcurrentFetches = maxOf(maxConcurrentFetches, activeFetches)
            try {
                if (singlePageProbe) {
                    delay(20)
                } else if (page == 1) {
                    pageOneStarted.complete(Unit)
                    pageTwoStarted.await()
                    delay(200)
                } else {
                    pageTwoStarted.complete(Unit)
                    pageOneStarted.await()
                    delay(20)
                }
                completedFetchPages += page
                if (page == 1 && failPageOneOnce) {
                    failPageOneOnce = false
                    return Result.error("temporary portal stress", IllegalStateException("temporary portal stress"))
                }
                val movie = Movie(id = page.toLong(), name = "Page $page", streamId = page.toLong())
                return Result.success(
                    StalkerPagedResult(
                        items = listOf(movie),
                        page = page,
                        totalPages = 2,
                        pageSize = 1,
                        advertisedTotalItems = 2,
                        advertisedTotalPages = 2
                    )
                )
            } finally {
                activeFetches -= 1
            }
        }

        override suspend fun markAttemptStarted(
            providerId: Long,
            contentType: ContentType,
            categoryId: Long,
            hydration: StalkerHydrationSnapshot?,
            attemptedPage: Int,
            now: Long
        ) = Unit

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
        ) {
            committedPages += attemptedPage
            val snapshot = StalkerHydrationSnapshot(
                lastHydratedAt = now,
                itemCount = itemCount,
                lastStatus = if (truncated) "TRUNCATED" else "SUCCESS",
                lastError = terminationReason,
                lastLoadedPage = attemptedPage,
                lastAttemptedPage = attemptedPage,
                lastSuccessfulPage = attemptedPage,
                totalPages = totalPages,
                advertisedTotalItems = advertisedTotalItems,
                advertisedTotalPages = advertisedTotalPages,
                isComplete = pageComplete && !truncated,
                pageSize = pageSize,
                retryAfterMs = 0L,
                failureCount = 0,
                retryBudgetRemaining = 3,
                lastPageFingerprint = pageFingerprint
            )
            categoryHydrations[categoryId] = snapshot
            if (categoryId == wildcard.categoryId) this.hydration = snapshot
        }

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
        ) {
            failedPages += attemptedPage
            val previous = hydration
            val snapshot = (previous ?: StalkerHydrationSnapshot(
                lastHydratedAt = 0L,
                itemCount = 0,
                lastStatus = "RUNNING",
                lastError = null,
                lastLoadedPage = 0,
                lastAttemptedPage = attemptedPage,
                lastSuccessfulPage = 0,
                totalPages = 0,
                advertisedTotalItems = null,
                advertisedTotalPages = null,
                isComplete = false,
                pageSize = 0,
                retryAfterMs = 0L,
                failureCount = 0,
                retryBudgetRemaining = 3,
                lastPageFingerprint = null
            )).copy(
                lastStatus = if (retryable) "FAILED_RETRYABLE" else "FAILED_PERMANENT",
                lastError = message,
                lastAttemptedPage = attemptedPage,
                retryAfterMs = 0L,
                failureCount = (previous?.failureCount ?: 0) + 1,
                retryBudgetRemaining = ((previous?.retryBudgetRemaining ?: 3) - 1).coerceAtLeast(0),
                lastPageFingerprint = pageFingerprint
            )
            categoryHydrations[categoryId] = snapshot
            if (categoryId == wildcard.categoryId) this.hydration = snapshot
        }

        override suspend fun upsertMovieSummaryBatch(providerId: Long, movies: List<Movie>, indexedAt: Long) {
            if (summaryFetchCountAtFirstCommit == null && summaryFetches.isNotEmpty()) {
                summaryFetchCountAtFirstCommit = summaryFetches.size
            }
            indexedMovies += movies
        }

        override suspend fun upsertSeriesSummaryBatch(providerId: Long, series: List<com.streamvault.domain.model.Series>, indexedAt: Long) = Unit

        override suspend fun upsertVodDerivedSeriesSummaryBatch(providerId: Long, series: List<com.streamvault.domain.model.Series>, indexedAt: Long) = Unit

        override suspend fun recordRequestFailure(providerId: Long, error: Throwable?) = Unit

        override fun failureState(error: Throwable): String = "FAILED_RETRYABLE"

        override fun progress(providerId: Long, callback: ((String) -> Unit)?, message: String) = Unit

        override suspend fun restoreMovieWatchProgress(providerId: Long) = Unit

        override suspend fun upsertJob(update: CatalogIndexJobUpdate) = Unit

        fun categoryHydration(categoryId: Long): StalkerHydrationSnapshot? = categoryHydrations[categoryId]

        override fun log(message: String) = Unit
    }
}
