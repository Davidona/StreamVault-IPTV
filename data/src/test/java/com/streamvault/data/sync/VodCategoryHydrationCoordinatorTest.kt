package com.streamvault.data.sync

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.DatabaseTransactionRunner
import com.streamvault.data.local.dao.CategoryDao
import com.streamvault.data.local.dao.MovieCategoryHydrationDao
import com.streamvault.data.local.dao.MovieDao
import com.streamvault.data.local.dao.SeriesCategoryHydrationDao
import com.streamvault.data.local.dao.SeriesDao
import com.streamvault.data.local.dao.VodCategoryHydrationDao
import com.streamvault.data.local.dao.VodCatalogEntryDao
import com.streamvault.data.local.entity.CategoryEntity
import com.streamvault.data.local.entity.VodCategoryHydrationEntity
import com.streamvault.data.remote.stalker.StalkerPagedResult
import com.streamvault.data.remote.stalker.StalkerProvider
import com.streamvault.data.remote.stalker.StalkerVodCatalogItem
import com.streamvault.domain.model.LegacyProvider
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.Movie
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.VodCatalogItem
import com.streamvault.domain.model.VodCategoryHydrationRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class VodCategoryHydrationCoordinatorTest {
    @Test
    fun `cancellation before catalog transaction does not commit`() = runTest {
        val transactionCalls = mutableListOf<Unit>()
        val api = mock<StalkerProvider>()
        whenever(api.getUnifiedVodPage(11L, 1)).thenThrow(CancellationException("before commit"))
        val coordinator = coordinator(api) { block ->
            transactionCalls += Unit
            block()
        }

        val cancelled = runCatching {
            coordinator.hydrateUnifiedVodCategory(7L, 11L, VodCategoryHydrationRequest.OPEN)
        }.exceptionOrNull()

        assertThat(cancelled).isInstanceOf(CancellationException::class.java)
        assertThat(transactionCalls).isEmpty()
    }

    @Test
    fun `cancellation while entering catalog transaction does not publish a completed page`() = runTest {
        var transactionEntered = false
        val api = successfulEmptyPageApi()
        val coordinator = coordinator(api) {
            transactionEntered = true
            throw CancellationException("during commit")
        }

        val cancelled = runCatching {
            coordinator.hydrateUnifiedVodCategory(7L, 11L, VodCategoryHydrationRequest.OPEN)
        }.exceptionOrNull()

        assertThat(cancelled).isInstanceOf(CancellationException::class.java)
        assertThat(transactionEntered).isTrue()
    }

    @Test
    fun `cancellation after transaction body preserves the completed transaction boundary`() = runTest {
        var catalogBodyCompleted = false
        val api = successfulEmptyPageApi()
        val coordinator = coordinator(api) { block ->
            block()
            catalogBodyCompleted = true
            throw CancellationException("after commit")
        }

        val cancelled = runCatching {
            coordinator.hydrateUnifiedVodCategory(7L, 11L, VodCategoryHydrationRequest.OPEN)
        }.exceptionOrNull()

        assertThat(cancelled).isInstanceOf(CancellationException::class.java)
        assertThat(catalogBodyCompleted).isTrue()
    }

    @Test
    fun `unified page reads current category protection inside its commit transaction`() = runTest {
        val categoryDao = mock<CategoryDao>()
        val movieDao = mock<MovieDao>()
        var transactionActive = false
        whenever(categoryDao.getByProviderTypeAndCategoryId(7L, ContentType.VOD.name, 11L)).thenAnswer {
            assertThat(transactionActive).isTrue()
            CategoryEntity(
                providerId = 7L,
                categoryId = 11L,
                name = "Protected",
                type = ContentType.VOD,
                isUserProtected = true
            )
        }
        val api = mock<StalkerProvider>()
        whenever(api.getUnifiedVodPage(11L, 1)).thenReturn(
            Result.success(
                StalkerPagedResult(
                    items = listOf(
                        StalkerVodCatalogItem(
                            rawItemId = "movie-1",
                            item = VodCatalogItem.MovieItem(
                                Movie(id = 101L, streamId = 101L, name = "Movie")
                            )
                        )
                    ),
                    page = 1,
                    totalPages = 1,
                    pageSize = 1
                )
            )
        )
        val coordinator = coordinator(
            api = api,
            categoryDao = categoryDao,
            movieDao = movieDao
        ) { block ->
            transactionActive = true
            try {
                block()
            } finally {
                transactionActive = false
            }
        }

        val result = coordinator.hydrateUnifiedVodCategory(7L, 11L, VodCategoryHydrationRequest.OPEN)

        assertThat(result).isInstanceOf(Result.Success::class.java)
        verify(categoryDao).getByProviderTypeAndCategoryId(7L, ContentType.VOD.name, 11L)
        verify(categoryDao, org.mockito.kotlin.never()).getByProviderAndTypeSync(7L, ContentType.VOD.name)
        verify(movieDao).upsertCategoryPage(
            eq(7L),
            org.mockito.kotlin.check { movies ->
                assertThat(movies).hasSize(1)
                assertThat(movies.single().isUserProtected).isTrue()
            }
        )
    }

    @Test
    fun `failed next page resumes from last commit and repeated page does not advance coverage`() = runTest {
        val hydrationDao = mock<VodCategoryHydrationDao>()
        var savedHydration: VodCategoryHydrationEntity? = null
        whenever(hydrationDao.get(7L, 11L)).thenAnswer { savedHydration }
        whenever(hydrationDao.upsert(any())).thenAnswer { invocation ->
            savedHydration = invocation.getArgument(0)
            Unit
        }
        val movieDao = mock<MovieDao>()
        val catalogEntryDao = mock<VodCatalogEntryDao>()
        whenever(catalogEntryDao.countByCategory(7L, 11L)).thenReturn(1)
        val api = mock<StalkerProvider>()
        whenever(api.getUnifiedVodPage(11L, 1)).thenReturn(Result.success(moviePage(1L, "movie-1", 1, 3)))
        whenever(api.getUnifiedVodPage(11L, 2)).thenReturn(
            Result.error("temporary page failure"),
            Result.success(moviePage(2L, "movie-2", 2, 3))
        )
        whenever(api.getUnifiedVodPage(11L, 3)).thenReturn(Result.success(moviePage(2L, "movie-2", 3, 3)))
        val coordinator = coordinator(
            api = api,
            movieDao = movieDao,
            hydrationDao = hydrationDao,
            catalogEntryDao = catalogEntryDao
        ) { block -> block() }

        val opened = coordinator.hydrateUnifiedVodCategory(7L, 11L, VodCategoryHydrationRequest.OPEN)
        assertThat(opened.isSuccess).isTrue()
        assertThat(savedHydration?.lastSuccessfulPage).isEqualTo(1)

        val failedNextPage = coordinator.hydrateUnifiedVodCategory(
            7L,
            11L,
            VodCategoryHydrationRequest.NEXT_PAGE
        )
        assertThat(failedNextPage).isInstanceOf(Result.Error::class.java)
        assertThat(savedHydration?.lastSuccessfulPage).isEqualTo(1)
        assertThat(savedHydration?.lastAttemptedPage).isEqualTo(2)
        assertThat(savedHydration?.lastStatus).isEqualTo("FAILED_RETRYABLE")

        val retriedNextPage = coordinator.hydrateUnifiedVodCategory(
            7L,
            11L,
            VodCategoryHydrationRequest.NEXT_PAGE
        )
        assertThat(retriedNextPage.isSuccess).isTrue()
        assertThat(savedHydration?.lastSuccessfulPage).isEqualTo(2)

        val repeatedPage = coordinator.hydrateUnifiedVodCategory(
            7L,
            11L,
            VodCategoryHydrationRequest.NEXT_PAGE
        )
        assertThat(repeatedPage).isInstanceOf(Result.Error::class.java)
        assertThat(savedHydration?.lastSuccessfulPage).isEqualTo(2)
        assertThat(savedHydration?.lastStatus).isEqualTo("ANOMALY")
        assertThat(savedHydration?.lastError).contains("repeated")
        verify(api, times(2)).getUnifiedVodPage(11L, 2)
        verify(api).getUnifiedVodPage(11L, 3)
        verify(movieDao, times(2)).upsertCategoryPage(eq(7L), any())
        verify(catalogEntryDao, times(2)).replacePage(eq(7L), eq(11L), any(), any())
    }

    @Test
    fun `complete request fetches pages sequentially through the advertised terminal page`() = runTest {
        val hydrationDao = mock<VodCategoryHydrationDao>()
        var savedHydration: VodCategoryHydrationEntity? = null
        whenever(hydrationDao.get(7L, 11L)).thenAnswer { savedHydration }
        whenever(hydrationDao.upsert(any())).thenAnswer { invocation ->
            savedHydration = invocation.getArgument(0)
            Unit
        }
        val requestedPages = mutableListOf<Int>()
        val api = mock<StalkerProvider>()
        whenever(api.getUnifiedVodPage(eq(11L), any())).thenAnswer { invocation ->
            val page = invocation.getArgument<Int>(1)
            requestedPages += page
            val response: StalkerPagedResult<StalkerVodCatalogItem> = if (page == 1) {
                moviePage(1L, "movie-1", 1, 2)
            } else {
                StalkerPagedResult(
                    items = emptyList(),
                    page = page,
                    totalPages = 2,
                    pageSize = 1,
                    advertisedTotalPages = 2
                )
            }
            Result.success(response)
        }
        val coordinator = coordinator(api, hydrationDao = hydrationDao) { block -> block() }

        val result = coordinator.hydrateUnifiedVodCategory(7L, 11L, VodCategoryHydrationRequest.COMPLETE)

        assertThat(result.isSuccess).isTrue()
        assertThat(requestedPages).containsExactly(1, 2).inOrder()
        assertThat(savedHydration?.lastSuccessfulPage).isEqualTo(2)
        assertThat(savedHydration?.isComplete).isTrue()
    }

    @Test
    fun `changing advertised page total does not replace the last successful receipt`() = runTest {
        val hydrationDao = mock<VodCategoryHydrationDao>()
        var savedHydration: VodCategoryHydrationEntity? = null
        whenever(hydrationDao.get(7L, 11L)).thenAnswer { savedHydration }
        whenever(hydrationDao.upsert(any())).thenAnswer { invocation ->
            savedHydration = invocation.getArgument(0)
            Unit
        }
        val api = mock<StalkerProvider>()
        whenever(api.getUnifiedVodPage(11L, 1)).thenReturn(Result.success(moviePage(1L, "movie-1", 1, 3)))
        whenever(api.getUnifiedVodPage(11L, 2)).thenReturn(Result.success(moviePage(2L, "movie-2", 2, 4)))
        val movieDao = mock<MovieDao>()
        val coordinator = coordinator(api, movieDao = movieDao, hydrationDao = hydrationDao) { block -> block() }

        assertThat(coordinator.hydrateUnifiedVodCategory(7L, 11L, VodCategoryHydrationRequest.OPEN).isSuccess).isTrue()
        val changedTotal = coordinator.hydrateUnifiedVodCategory(
            7L,
            11L,
            VodCategoryHydrationRequest.NEXT_PAGE
        )

        assertThat(changedTotal).isInstanceOf(Result.Error::class.java)
        assertThat(savedHydration?.lastSuccessfulPage).isEqualTo(1)
        assertThat(savedHydration?.lastStatus).isEqualTo("ANOMALY")
        verify(movieDao).upsertCategoryPage(eq(7L), any())
    }

    @Test
    fun `complete request marks the page batch limit as truncated coverage`() = runTest {
        val hydrationDao = mock<VodCategoryHydrationDao>()
        var savedHydration: VodCategoryHydrationEntity? = null
        whenever(hydrationDao.get(7L, 11L)).thenAnswer { savedHydration }
        whenever(hydrationDao.upsert(any())).thenAnswer { invocation ->
            savedHydration = invocation.getArgument(0)
            Unit
        }
        val api = mock<StalkerProvider>()
        whenever(api.getUnifiedVodPage(eq(11L), any())).thenAnswer { invocation ->
            val page = invocation.getArgument<Int>(1)
            Result.success(
                StalkerPagedResult<StalkerVodCatalogItem>(
                    items = emptyList(),
                    page = page,
                    totalPages = 201,
                    pageSize = 100,
                    advertisedTotalPages = 201
                )
            )
        }
        val coordinator = coordinator(api, hydrationDao = hydrationDao) { block -> block() }

        val result = coordinator.hydrateUnifiedVodCategory(7L, 11L, VodCategoryHydrationRequest.COMPLETE)

        assertThat(result).isInstanceOf(Result.Error::class.java)
        assertThat(savedHydration?.lastSuccessfulPage).isEqualTo(200)
        assertThat(savedHydration?.isComplete).isFalse()
        assertThat(savedHydration?.lastStatus).isEqualTo("TRUNCATED")
        assertThat(savedHydration?.lastError).isEqualTo("page_limit")
        verify(api, times(200)).getUnifiedVodPage(eq(11L), any())
    }

    private suspend fun successfulEmptyPageApi(): StalkerProvider = mock<StalkerProvider>().also { api ->
        whenever(api.getUnifiedVodPage(11L, 1)).thenReturn(
            Result.success(
                StalkerPagedResult(
                    items = emptyList(),
                    page = 1,
                    totalPages = 1,
                    pageSize = 100
                )
            )
        )
    }

    private fun moviePage(
        movieId: Long,
        rawItemId: String,
        page: Int,
        totalPages: Int,
        isTruncated: Boolean = false
    ) = StalkerPagedResult(
        items = listOf(
            StalkerVodCatalogItem(
                rawItemId = rawItemId,
                item = VodCatalogItem.MovieItem(
                    Movie(id = movieId, streamId = movieId, name = "Movie $movieId")
                )
            )
        ),
        page = page,
        totalPages = totalPages,
        pageSize = 1,
        advertisedTotalPages = totalPages,
        isTruncated = isTruncated
    )


    private suspend fun coordinator(
        api: StalkerProvider,
        categoryDao: CategoryDao? = null,
        movieDao: MovieDao = mock(),
        hydrationDao: VodCategoryHydrationDao = mock(),
        catalogEntryDao: VodCatalogEntryDao? = null,
        transaction: suspend (suspend () -> Unit) -> Unit
    ): VodCategoryHydrationCoordinator {
        val resolvedCategoryDao = categoryDao ?: mock<CategoryDao>().also {
            whenever(it.getByProviderAndTypeSync(7L, "VOD")).thenReturn(emptyList())
        }
        val provider = LegacyProvider(
            id = 7L,
            name = "test",
            type = ProviderType.STALKER_PORTAL,
            serverUrl = "http://portal.test"
        )
        val resolvedCatalogEntryDao = catalogEntryDao ?: mock<VodCatalogEntryDao>().also {
            whenever(it.countByCategory(7L, 11L)).thenReturn(0)
        }
        return VodCategoryHydrationCoordinator(
            providerSyncLocks = ProviderSyncLockRegistry(),
            categoryDao = resolvedCategoryDao,
            movieCategoryHydrationDao = mock<MovieCategoryHydrationDao>(),
            seriesCategoryHydrationDao = mock<SeriesCategoryHydrationDao>(),
            vodCategoryHydrationDao = hydrationDao,
            movieDao = movieDao,
            seriesDao = mock<SeriesDao>(),
            vodCatalogEntryDao = resolvedCatalogEntryDao,
            transactionRunner = object : DatabaseTransactionRunner {
                override suspend fun <T> inTransaction(block: suspend () -> T): T {
                    var result: T? = null
                    transaction({ result = block() })
                    @Suppress("UNCHECKED_CAST")
                    return result as T
                }
            },
            loadProvider = { provider },
            createProvider = { api }
        ).also {
            // The transaction callback above is supplied by each test through the wrapper.
        }
    }
}
