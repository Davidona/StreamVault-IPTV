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
import com.streamvault.data.sync.SyncManager
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.Movie
import com.streamvault.domain.model.ProviderSnapshot
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.StalkerConfig
import com.streamvault.domain.model.StalkerDeviceIdentity
import com.streamvault.domain.model.VodCatalogItem
import com.streamvault.domain.model.VodCategoryHydrationRequest
import com.streamvault.domain.model.VodCategoryLoadMode
import com.streamvault.domain.model.Provider as LegacyProvider
import com.streamvault.domain.provider.CapabilityResolution
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
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

    private fun stalkerSnapshot(providerId: Long): ProviderSnapshot {
        val legacy = LegacyProvider(
            id = providerId,
            name = "Portal",
            type = ProviderType.STALKER_PORTAL
        )
        return ProviderSnapshot(
            provider = legacy,
            configuration = StalkerConfig(
                portalUrl = "https://portal.example.com/stalker_portal/server/load.php",
                device = StalkerDeviceIdentity(macAddress = "00:11:22:33:44:55")
            ),
            configurationGeneration = 1L
        )
    }

    @Test
    fun searchVod_blankQuery_returnsEmptyWithoutTouchingProvider() = runTest {
        val result = repository().searchVod(1L, "   ", 1)

        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat((result as Result.Success).data.items).isEmpty()
        verify(capabilityResolver, never()).snapshot(any())
    }

    @Test
    fun searchVod_queriesPortal_forStalkerProvider() = runTest {
        val snapshot = stalkerSnapshot(1L)
        val stalkerProvider = mock<StalkerProvider>()
        whenever(capabilityResolver.snapshot(1L)).thenReturn(snapshot)
        whenever(typedProviderFactory.stalker(snapshot))
            .thenReturn(CapabilityResolution.Available(stalkerProvider))
        // Portal payloads are transient movies (id == 0); the repository must persist them
        // against their stream id and hand back the DB row so items carry real ids.
        val transientMovie = Movie(
            id = 0L,
            name = "Damadol",
            categoryId = 20L,
            providerId = 1L,
            streamId = 17160L
        )
        whenever(stalkerProvider.searchVodPage("damadol", 1)).thenReturn(
            Result.success(
                StalkerPagedResult(
                    items = listOf(transientMovie),
                    page = 1,
                    totalPages = 1,
                    pageSize = 14,
                    advertisedTotalItems = 1
                )
            )
        )
        whenever(movieDao.getByStreamIds(1L, listOf(17160L))).thenReturn(
            listOf(
                MovieEntity(
                    id = 17160L,
                    streamId = 17160L,
                    name = "Damadol",
                    categoryId = 20L,
                    providerId = 1L
                )
            )
        )

        val result = repository().searchVod(1L, "damadol", 1)

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val data = (result as Result.Success).data
        assertThat(data.totalCount).isEqualTo(1)
        assertThat(data.pageSize).isEqualTo(14)
        assertThat(data.items).hasSize(1)
        val item = data.items.single()
        assertThat(item).isInstanceOf(VodCatalogItem.MovieItem::class.java)
        assertThat((item as VodCatalogItem.MovieItem).movie.id).isEqualTo(17160L)
        assertThat(item.movie.name).isEqualTo("Damadol")
        verify(stalkerProvider).searchVodPage("damadol", 1)
        verify(movieDao).upsertCategoryPage(eq(1L), any<List<MovieEntity>>())
    }

    @Test
    fun searchVod_pageOfTransientResults_returnsDistinctDbBackedIds() = runTest {
        val snapshot = stalkerSnapshot(1L)
        val stalkerProvider = mock<StalkerProvider>()
        whenever(capabilityResolver.snapshot(1L)).thenReturn(snapshot)
        whenever(typedProviderFactory.stalker(snapshot))
            .thenReturn(CapabilityResolution.Available(stalkerProvider))
        // Two transient movies both have id == 0. Only persisting them and reading back the
        // DB rows gives unique movie ids; returning them raw would make every grid item
        // share stableId "movie:0" and crash the VOD grid with duplicate keys.
        val transientMovies = listOf(
            Movie(id = 0L, name = "Wrath", providerId = 1L, streamId = 1001L),
            Movie(id = 0L, name = "Wonder", providerId = 1L, streamId = 1002L)
        )
        whenever(stalkerProvider.searchVodPage("w", 1)).thenReturn(
            Result.success(
                StalkerPagedResult(
                    items = transientMovies,
                    page = 1,
                    totalPages = 1,
                    pageSize = 14,
                    advertisedTotalItems = 2
                )
            )
        )
        whenever(movieDao.getByStreamIds(1L, listOf(1001L, 1002L))).thenReturn(
            listOf(
                MovieEntity(id = 7L, streamId = 1001L, name = "Wrath", providerId = 1L),
                MovieEntity(id = 9L, streamId = 1002L, name = "Wonder", providerId = 1L)
            )
        )

        val result = repository().searchVod(1L, "w", 1)

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val items = (result as Result.Success).data.items
        val movies = items.map { (it as VodCatalogItem.MovieItem).movie }
        assertThat(movies.map { it.id }).containsExactly(7L, 9L)
        assertThat(movies.map { it.streamId }).containsExactly(1001L, 1002L)
        assertThat(movies.map { it.name }).containsExactly("Wrath", "Wonder").inOrder()
        val stableIds = items.map(VodCatalogItem::stableId)
        assertThat(stableIds).containsNoDuplicates()
        verify(movieDao).upsertCategoryPage(eq(1L), any<List<MovieEntity>>())
    }

    @Test
    fun searchVod_rejectsProviderWithoutStalkerConfiguration() = runTest {
        whenever(capabilityResolver.snapshot(1L)).thenReturn(null)

        val result = repository().searchVod(1L, "damadol", 1)

        assertThat(result).isInstanceOf(Result.Error::class.java)
        verifyNoInteractions(typedProviderFactory)
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

    @Test
    fun getCategories_fallsBackToMovieRowsWhenUnifiedCatalogHasNoVodRows() = runTest {
        whenever(preferences.parentalControlLevel).thenReturn(flowOf(2))
        whenever(preferences.getHiddenCategoryIds(4, ContentType.VOD)).thenReturn(flowOf(emptySet()))
        whenever(preferences.getHiddenCategoryIds(4, ContentType.MOVIE)).thenReturn(flowOf(emptySet()))
        whenever(categoryDao.getByProviderAndType(4, ContentType.VOD.name)).thenReturn(flowOf(emptyList()))
        whenever(categoryDao.getByProviderAndTypeSync(4, ContentType.MOVIE.name)).thenReturn(
            listOf(
                CategoryEntity(providerId = 4, categoryId = 1, name = "ENGLISH | NEW RELEASE", type = ContentType.MOVIE),
                CategoryEntity(providerId = 4, categoryId = 104, name = "ENGLISH | 4K UHD MOVIES", type = ContentType.MOVIE)
            )
        )

        val ids = repository().getCategories(4).first().map { it.id }

        assertThat(ids).containsExactly(1L, 104L).inOrder()
    }

    @Test
    fun getCategories_doesNotFallBackWhenVodRowsExist() = runTest {
        whenever(preferences.parentalControlLevel).thenReturn(flowOf(2))
        whenever(preferences.getHiddenCategoryIds(4, ContentType.VOD)).thenReturn(flowOf(emptySet()))
        whenever(categoryDao.getByProviderAndType(4, ContentType.VOD.name)).thenReturn(
            flowOf(
                listOf(CategoryEntity(providerId = 4, categoryId = 101, name = "VOD Cat", type = ContentType.VOD))
            )
        )

        assertThat(repository().getCategories(4).first().map { it.id }).containsExactly(101L)
        verify(categoryDao, org.mockito.kotlin.never()).getByProviderAndTypeSync(
            org.mockito.kotlin.eq(4),
            org.mockito.kotlin.eq(ContentType.MOVIE.name)
        )
    }
}
