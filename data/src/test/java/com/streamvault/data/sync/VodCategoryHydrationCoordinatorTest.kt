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
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
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

    private suspend fun coordinator(
        api: StalkerProvider,
        categoryDao: CategoryDao? = null,
        movieDao: MovieDao = mock(),
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
        return VodCategoryHydrationCoordinator(
            providerSyncLocks = ProviderSyncLockRegistry(),
            categoryDao = resolvedCategoryDao,
            movieCategoryHydrationDao = mock<MovieCategoryHydrationDao>(),
            seriesCategoryHydrationDao = mock<SeriesCategoryHydrationDao>(),
            vodCategoryHydrationDao = mock<VodCategoryHydrationDao>(),
            movieDao = movieDao,
            seriesDao = mock<SeriesDao>(),
            vodCatalogEntryDao = mock<VodCatalogEntryDao>().also {
                whenever(it.countByCategory(7L, 11L)).thenReturn(0)
            },
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
