package com.streamvault.data.repository

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.dao.CategoryDao
import com.streamvault.data.local.dao.MovieDao
import com.streamvault.data.local.dao.SeriesDao
import com.streamvault.data.local.dao.VodCatalogEntryDao
import com.streamvault.data.local.dao.VodCategoryHydrationDao
import com.streamvault.data.local.entity.CategoryEntity
import com.streamvault.data.local.entity.MovieEntity
import com.streamvault.data.local.entity.SeriesEntity
import com.streamvault.data.local.entity.VodCatalogEntryEntity
import com.streamvault.data.local.entity.VodCategoryHydrationEntity
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.data.provider.ProviderCapabilityResolver
import com.streamvault.data.provider.TypedProviderClientFactory
import com.streamvault.data.remote.stalker.StalkerPagedResult
import com.streamvault.data.remote.stalker.StalkerProvider
import com.streamvault.data.remote.stalker.StalkerVodCatalogItem
import com.streamvault.data.sync.SyncManager
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.Movie
import com.streamvault.domain.model.Provider
import com.streamvault.domain.model.ProviderSnapshot
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.Series
import com.streamvault.domain.model.StalkerConfig
import com.streamvault.domain.model.StalkerDeviceIdentity
import com.streamvault.domain.model.VodSearchResult
import com.streamvault.domain.model.VodCatalogItem
import com.streamvault.domain.model.VodCategoryHydrationRequest
import com.streamvault.domain.model.VodCategoryLoadMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.mockito.kotlin.timeout

class VodRepositoryImplTest {
    private val movieDao = mock<MovieDao>()
    private val seriesDao = mock<SeriesDao>()
    private val hydrationDao = mock<VodCategoryHydrationDao>()
    private val entryDao = mock<VodCatalogEntryDao>()
    private val categoryDao = mock<CategoryDao>()
    private val preferences = mock<PreferencesRepository>()
    private val syncManager = mock<SyncManager>()
    private val capabilityResolver = mock<ProviderCapabilityResolver>()
    private val typedProviderFactory = mock<TypedProviderClientFactory>()

    private fun repository() = VodRepositoryImpl(
        movieDao,
        seriesDao,
        hydrationDao,
        entryDao,
        categoryDao,
        preferences,
        syncManager,
        capabilityResolver,
        typedProviderFactory
    )

    private fun stalkerSnapshot(providerId: Long): ProviderSnapshot = ProviderSnapshot(
        provider = Provider(
            id = providerId,
            name = "Portal",
            type = ProviderType.STALKER_PORTAL
        ),
        configuration = StalkerConfig(
            portalUrl = "https://portal.example.com/stalker_portal/server/load.php",
            device = StalkerDeviceIdentity(macAddress = "00:11:22:33:44:55")
        ),
        configurationGeneration = 1L
    )

    @Test
    fun searchVod_returnsMixedPortalResultsWithStablePersistedIds() = runTest {
        val snapshot = stalkerSnapshot(1L)
        val stalkerProvider = mock<StalkerProvider>()
        whenever(capabilityResolver.snapshot(1L)).thenReturn(snapshot)
        whenever(typedProviderFactory.stalker(snapshot))
            .thenReturn(com.streamvault.domain.provider.CapabilityResolution.Available(stalkerProvider))
        whenever(preferences.parentalControlLevel).thenReturn(flowOf(2))
        whenever(preferences.getHiddenCategoryIds(1L, ContentType.VOD)).thenReturn(flowOf(emptySet()))
        val movie = Movie(id = 0L, name = "Movie", providerId = 1L, streamId = 1001L)
        val series = Series(
            id = 0L,
            name = "Series",
            providerId = 1L,
            seriesId = 2001L,
            providerSeriesId = "2001"
        )
        whenever(stalkerProvider.searchVodPage("mix", 1)).thenReturn(
            Result.success(
                StalkerPagedResult(
                    items = listOf(
                        StalkerVodCatalogItem("1001", VodCatalogItem.MovieItem(movie)),
                        StalkerVodCatalogItem("2001", VodCatalogItem.SeriesItem(series))
                    ),
                    page = 1,
                    totalPages = 2,
                    pageSize = 14,
                    advertisedTotalItems = 2
                )
            )
        )
        whenever(movieDao.getByStreamIds(1L, listOf(1001L))).thenReturn(
            listOf(MovieEntity(id = 7L, streamId = 1001L, name = "Movie", providerId = 1L))
        )
        whenever(seriesDao.getBySeriesIds(1L, listOf(2001L))).thenReturn(
            listOf(SeriesEntity(id = 8L, seriesId = 2001L, providerId = 1L, name = "Series"))
        )

        val result = repository().searchVod(1L, "mix", 1)

        assertThat(result.getOrNull()?.items).hasSize(2)
        assertThat((result.getOrNull()?.items?.get(0) as VodCatalogItem.MovieItem).movie.id)
            .isEqualTo(7L)
        assertThat((result.getOrNull()?.items?.get(1) as VodCatalogItem.SeriesItem).series.id)
            .isEqualTo(8L)
        verify(movieDao).upsertCategoryPage(eq(1L), any<List<MovieEntity>>())
        verify(seriesDao).upsertCategoryPage(eq(1L), any<List<SeriesEntity>>())
    }

    @Test
    fun searchVod_providerConfigurationFailure_returnsError() = runTest {
        val snapshot = stalkerSnapshot(1L)
        whenever(capabilityResolver.snapshot(1L)).thenReturn(snapshot)
        whenever(typedProviderFactory.stalker(snapshot)).thenThrow(
            IllegalStateException("invalid provider configuration")
        )

        val result = repository().searchVod(1L, "movie", 1)

        assertThat(result).isInstanceOf(Result.Error::class.java)
        assertThat((result as Result.Error).message).contains("invalid provider configuration")
    }

    @Test
    fun categoryItems_followPersistedProviderOrderAcrossTypes() = runTest {
        whenever(entryDao.observeByCategory(3, 100)).thenReturn(
            flowOf(
                listOf(
                    VodCatalogEntryEntity(3, 100, "series-9", ContentType.SERIES, 9, 1, 0),
                    VodCatalogEntryEntity(3, 100, "movie-7", ContentType.MOVIE, 7, 1, 1),
                    VodCatalogEntryEntity(3, 100, "series-8", ContentType.SERIES, 8, 1, 2)
                )
            )
        )
        whenever(movieDao.observeByStreamIds(3, listOf(7L))).thenReturn(
            flowOf(listOf(MovieEntity(streamId = 7, providerId = 3, categoryId = 100, name = "Movie")))
        )
        whenever(seriesDao.observeBySeriesIds(3, listOf(9L, 8L))).thenReturn(
            flowOf(
                listOf(
                    SeriesEntity(seriesId = 8, providerId = 3, categoryId = 100, name = "Second Series"),
                    SeriesEntity(seriesId = 9, providerId = 3, categoryId = 100, name = "First Series")
                )
            )
        )

        val items = repository().getCategoryItems(3, 100).first()

        assertThat(items.map {
            when (it) {
                is VodCatalogItem.MovieItem -> it.movie.name
                is VodCatalogItem.SeriesItem -> it.series.name
            }
        }).containsExactly("First Series", "Movie", "Second Series").inOrder()
        verifyNoInteractions(syncManager)
    }

    @Test
    fun openInPagedMode_fetchesOnePageThenPrefetchesExactlyOnePage() = runTest {
        whenever(preferences.vodCategoryLoadMode).thenReturn(flowOf(VodCategoryLoadMode.PAGED))
        whenever(syncManager.hydrateUnifiedVodCategory(8, 201, VodCategoryHydrationRequest.OPEN))
            .thenReturn(Result.success(Unit))
        whenever(syncManager.hydrateUnifiedVodCategory(8, 201, VodCategoryHydrationRequest.NEXT_PAGE))
            .thenReturn(Result.success(Unit))

        repository().requestCategoryHydration(8, 201, VodCategoryHydrationRequest.OPEN)

        verify(syncManager).hydrateUnifiedVodCategory(8, 201, VodCategoryHydrationRequest.OPEN)
        verify(syncManager, timeout(1_000)).hydrateUnifiedVodCategory(
            8,
            201,
            VodCategoryHydrationRequest.NEXT_PAGE
        )
    }

    @Test
    fun hydration_exposesProviderPageSizeForAheadOfScrollPrefetch() = runTest {
        whenever(hydrationDao.observe(8, 201)).thenReturn(
            flowOf(
                VodCategoryHydrationEntity(
                    providerId = 8,
                    categoryId = 201,
                    lastSuccessfulPage = 2,
                    totalPages = 9,
                    pageSize = 36,
                    itemCount = 72
                )
            )
        )

        assertThat(repository().observeHydration(8, 201).first()?.pageSize).isEqualTo(36)
    }

    @Test
    fun completeOnOpenMode_usesCompleteRequestWithoutPagedOpen() = runTest {
        whenever(preferences.vodCategoryLoadMode).thenReturn(flowOf(VodCategoryLoadMode.COMPLETE_ON_OPEN))
        whenever(syncManager.hydrateUnifiedVodCategory(8, 202, VodCategoryHydrationRequest.COMPLETE))
            .thenReturn(Result.success(Unit))

        repository().requestCategoryHydration(8, 202, VodCategoryHydrationRequest.OPEN)

        verify(syncManager).hydrateUnifiedVodCategory(8, 202, VodCategoryHydrationRequest.COMPLETE)
    }

    @Test
    fun previewHydratesOneRawPage_andAppliesVodVisibility() = runTest {
        whenever(syncManager.hydrateUnifiedVodCategory(4, 101, VodCategoryHydrationRequest.OPEN))
            .thenReturn(Result.success(Unit))
        whenever(entryDao.observeByCategory(4, 101)).thenReturn(flowOf(emptyList()))
        repository().getCategoryPreview(4, 101, 10).first()
        verify(syncManager).hydrateUnifiedVodCategory(4, 101, VodCategoryHydrationRequest.OPEN)

        whenever(categoryDao.getByProviderAndType(4, ContentType.VOD.name)).thenReturn(
            flowOf(
                listOf(
                    CategoryEntity(providerId = 4, categoryId = 101, name = "Visible", type = ContentType.VOD),
                    CategoryEntity(providerId = 4, categoryId = 102, name = "Hidden", type = ContentType.VOD)
                )
            )
        )
        whenever(preferences.parentalControlLevel).thenReturn(flowOf(2))
        whenever(preferences.getHiddenCategoryIds(4, ContentType.VOD)).thenReturn(flowOf(setOf(102L)))

        assertThat(repository().getCategories(4).first().map { it.id }).containsExactly(101L)
    }
}
