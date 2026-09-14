package com.streamvault.data.repository

import android.database.sqlite.SQLiteException
import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.dao.CategoryDao
import com.streamvault.data.local.dao.EpisodeDao
import com.streamvault.data.local.dao.FavoriteDao
import com.streamvault.data.local.dao.PlaybackHistoryDao
import com.streamvault.data.local.dao.ProviderDao
import com.streamvault.data.local.dao.SeriesDao
import com.streamvault.data.local.dao.SeriesCategoryHydrationDao
import com.streamvault.data.local.dao.XtreamContentIndexDao
import com.streamvault.data.local.dao.XtreamIndexJobDao
import com.streamvault.data.local.entity.EpisodeBrowseEntity
import com.streamvault.data.local.entity.EpisodeEntity
import com.streamvault.data.local.entity.CategoryEntity
import com.streamvault.data.local.entity.SeriesEntity
import com.streamvault.data.local.entity.SeriesBrowseEntity
import com.streamvault.data.local.entity.SeriesCategoryHydrationEntity
import com.streamvault.data.local.entity.ProviderEntity
import com.streamvault.data.local.entity.XtreamIndexJobEntity
import com.streamvault.data.mapper.toEntity
import com.streamvault.data.provider.toProviderSnapshot
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.data.provider.ProviderCapabilityResolver
import com.streamvault.data.provider.TypedProviderClientFactory
import com.streamvault.data.provider.ProviderCapabilityTimeoutException
import com.streamvault.data.remote.jellyfin.JellyfinProvider
import com.streamvault.data.remote.dto.XtreamSeason
import com.streamvault.data.remote.dto.XtreamSeriesInfoResponse
import com.streamvault.data.remote.stalker.StalkerCategoryRecord
import com.streamvault.data.remote.stalker.StalkerEpisodeRecord
import com.streamvault.data.remote.stalker.StalkerItemRecord
import com.streamvault.data.remote.stalker.StalkerPagedItems
import com.streamvault.data.remote.stalker.StalkerProviderProfile
import com.streamvault.data.remote.stalker.StalkerProvider
import com.streamvault.data.remote.stalker.StalkerRemoteIdentityResolver
import com.streamvault.data.remote.stalker.stalkerStableHashId
import com.streamvault.data.remote.stalker.StalkerSeasonRecord
import com.streamvault.data.remote.stalker.StalkerSeriesDetails
import com.streamvault.data.remote.stalker.StalkerSession
import com.streamvault.data.remote.dto.XtreamCategory
import com.streamvault.data.remote.dto.XtreamSeriesItem
import com.streamvault.data.remote.stalker.StalkerApiService
import com.streamvault.data.remote.xtream.XtreamParsingException
import com.streamvault.data.remote.xtream.XtreamApiService
import com.streamvault.data.remote.xtream.XtreamStreamUrlResolver
import com.streamvault.data.security.CredentialCrypto
import com.streamvault.data.sync.SyncManager
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.LibraryBrowseQuery
import com.streamvault.domain.model.LibraryFilterBy
import com.streamvault.domain.model.LibraryFilterType
import com.streamvault.domain.model.LibrarySortBy
import com.streamvault.domain.model.PlaybackHistory
import com.streamvault.domain.model.ProviderStatus
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.LegacyProvider as Provider
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.SeriesDetailPresentationHint
import com.streamvault.domain.model.Episode
import com.streamvault.domain.model.Season
import com.streamvault.domain.model.Series
import com.streamvault.domain.model.VodDuplicateConfidence
import com.streamvault.domain.model.VodDuplicateHandlingMode
import com.streamvault.domain.model.VodSeriesVariant
import com.streamvault.domain.model.VodVariantObservation
import com.streamvault.domain.model.VodVariantPreferenceMode
import com.streamvault.domain.repository.PlaybackHistoryRepository
import com.streamvault.domain.provider.CapabilityResolution
import com.streamvault.domain.provider.ProviderCapabilitySet
import com.streamvault.domain.provider.ProviderContentReference
import com.streamvault.domain.provider.SeriesCatalogSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

class SeriesRepositoryImplTest {

    private val seriesDao: SeriesDao = mock()
    private val episodeDao: EpisodeDao = mock()
    private val categoryDao: CategoryDao = mock()
    private val favoriteDao: FavoriteDao = mock()
    private val playbackHistoryDao: PlaybackHistoryDao = mock()
    private val playbackHistoryRepository: PlaybackHistoryRepository = mock()
    private val providerDao: ProviderDao = mock()
    private val stalkerApiService: StalkerApiService = mock()
    private val xtreamApiService: XtreamApiService = mock()
    private val preferencesRepository: PreferencesRepository = mock()
    private val xtreamStreamUrlResolver: XtreamStreamUrlResolver = mock()
    private val seriesCategoryHydrationDao: SeriesCategoryHydrationDao = mock()

    @Before
    fun clearStalkerCaches() {
        StalkerProvider.clearSharedAuthCacheForTests()
        StalkerProvider.clearResolvedStreamUrlCacheForTests()
    }
    private val jellyfinProvider: JellyfinProvider = mock()
    private val xtreamContentIndexDao: XtreamContentIndexDao = mock()
    private val xtreamIndexJobDao: XtreamIndexJobDao = mock()
    private val syncManager: SyncManager = mock()
    private val providerCapabilityResolver: ProviderCapabilityResolver = mock()
    private val typedProviderClientFactory: TypedProviderClientFactory = mock()
    private val stalkerRemoteIdentityResolver: StalkerRemoteIdentityResolver = mock()
    private val providerCapabilitySet: ProviderCapabilitySet = mock()
    private val seriesCatalogSource: SeriesCatalogSource = mock()
    private val credentialCrypto = object : CredentialCrypto {
        override fun encryptIfNeeded(value: String): String = value
        override fun decryptIfNeeded(value: String): String = value
    }

    init {
        whenever(providerCapabilitySet.seriesCatalog()).thenReturn(CapabilityResolution.Available(seriesCatalogSource))
        runBlocking {
            whenever(providerCapabilityResolver.resolve(any())).thenReturn(
                CapabilityResolution.Available(providerCapabilitySet)
            )
            whenever(seriesCatalogSource.hydrateSeries(any(), any())).doSuspendableAnswer { invocation ->
                Result.success(invocation.getArgument<Series>(1))
            }
        }
    }

    @Test
    fun `getSeriesByCategory does not hydrate xtream category when local cache is empty`() = runTest {
        whenever(preferencesRepository.parentalControlLevel).thenReturn(flowOf(0))
        whenever(preferencesRepository.xtreamBase64TextCompatibility).thenReturn(flowOf(false))
        whenever(seriesDao.getCountByCategory(7L, 77L)).thenReturn(flowOf(0))
        whenever(seriesDao.getByCategory(7L, 77L)).thenReturn(flowOf(emptyList()))
        stubProvider(
            Provider(
                id = 7L,
                name = "Xtream",
                type = ProviderType.XTREAM_CODES,
                serverUrl = "http://example.com",
                username = "user",
                password = "pass",
                status = ProviderStatus.ACTIVE
            )
        )
        whenever(seriesCategoryHydrationDao.get(7L, 77L)).thenReturn(null)

        val repository = createRepository()

        val result = repository.getSeriesByCategory(7L, 77L).first()

        assertThat(result).isEmpty()
        verify(syncManager).prioritizeXtreamIndexCategory(7L, ContentType.SERIES, 77L)
        verify(seriesDao, never()).replaceCategory(eq(7L), eq(77L), any())
        verify(xtreamApiService, never()).getSeriesList(any(), any())
        verify(episodeDao, never()).deleteOrphans()
    }

    @Test
    fun `getSeriesByCategory directly hydrates stalker category even when background fetch is running`() = runTest {
        whenever(preferencesRepository.parentalControlLevel).thenReturn(flowOf(0))
        whenever(preferencesRepository.xtreamBase64TextCompatibility).thenReturn(flowOf(false))
        whenever(seriesDao.getCountByCategory(7L, 77L)).thenReturn(flowOf(0))
        whenever(seriesDao.getByCategory(7L, 77L)).thenReturn(flowOf(emptyList()))
        whenever(seriesCategoryHydrationDao.get(7L, 77L)).thenReturn(null)
        whenever(xtreamIndexJobDao.get(7L, ContentType.SERIES.name)).thenReturn(
            XtreamIndexJobEntity(providerId = 7L, section = ContentType.SERIES.name, state = "RUNNING")
        )
        stubProvider(
            Provider(
                id = 7L,
                name = "Stalker",
                type = ProviderType.STALKER_PORTAL,
                serverUrl = "http://example.com",
                username = "",
                password = "pass",
                status = ProviderStatus.ACTIVE
            )
        )
        whenever(stalkerApiService.authenticate(any())).thenReturn(
            Result.success(StalkerSession("http://example.com/portal.php", "http://example.com/", "token") to StalkerProviderProfile())
        )
        whenever(stalkerApiService.getSeriesCategories(any(), any())).thenReturn(
            Result.success(listOf(StalkerCategoryRecord("77", "Drama")))
        )
        whenever(stalkerApiService.getSeriesPage(any(), any(), anyOrNull(), any())).thenReturn(
            Result.success(StalkerPagedItems(emptyList(), page = 1, totalPages = 1, pageSize = 20))
        )
        val repository = createRepository()

        val result = repository.getSeriesByCategory(7L, 77L).first()

        assertThat(result).isEmpty()
        verify(syncManager, never()).prioritizeStalkerIndexCategory(any(), any(), any())
        verify(stalkerApiService).getSeriesPage(any(), any(), anyOrNull(), eq(1))
        verify(episodeDao, never()).deleteOrphans()
    }

    @Test
    fun `getSeriesDetails uses fresh xtream hydrated cache without refetching`() = runTest {
        val hydratedAt = System.currentTimeMillis()
        val seriesEntity = SeriesEntity(
            id = 99L,
            seriesId = 301L,
            name = "Cached Series",
            providerId = 7L,
            cacheState = "DETAIL_HYDRATED",
            detailHydratedAt = hydratedAt
        )
        whenever(seriesDao.getById(99L)).thenReturn(seriesEntity)
        stubProvider(
            Provider(
                id = 7L,
                name = "Xtream",
                type = ProviderType.XTREAM_CODES,
                serverUrl = "http://example.com",
                username = "user",
                password = "pass",
                status = ProviderStatus.ACTIVE
            )
        )
        whenever(episodeDao.getBySeriesSync(99L)).thenReturn(
            listOf(
                EpisodeBrowseEntity(
                    id = 1L,
                    episodeId = 301L,
                    title = "Episode 1",
                    episodeNumber = 1,
                    seasonNumber = 1,
                    seriesId = 99L,
                    providerId = 7L
                )
            )
        )

        val result = createRepository().getSeriesDetails(7L, 99L)

        assertThat(result.getOrNull()?.name).isEqualTo("Cached Series")
        verify(xtreamApiService, never()).getSeriesInfo(any(), any())
        verify(xtreamContentIndexDao, never()).markDetailHydrated(any(), any(), any(), any(), anyOrNull(), any())
    }

    @Test
    fun `getSeriesDetails rehydrates fresh xtream series that lost its episodes`() = runTest {
        val hydratedAt = System.currentTimeMillis()
        val seriesEntity = SeriesEntity(
            id = 99L,
            seriesId = 301L,
            name = "Cached Series",
            providerId = 7L,
            cacheState = "DETAIL_HYDRATED",
            detailHydratedAt = hydratedAt
        )
        whenever(seriesDao.getById(99L)).thenReturn(seriesEntity)
        stubProvider(
            Provider(
                id = 7L,
                name = "Xtream",
                type = ProviderType.XTREAM_CODES,
                serverUrl = "http://example.com",
                username = "user",
                password = "pass",
                status = ProviderStatus.ACTIVE
            )
        )
        whenever(episodeDao.getBySeriesSync(99L)).thenReturn(emptyList())

        createRepository().getSeriesDetails(7L, 99L)

        verify(seriesCatalogSource).hydrateSeries(any(), any())
        verify(xtreamContentIndexDao).markDetailHydrated(any(), any(), any(), any(), anyOrNull(), any())
    }

    @Test
    fun `getSeriesDetails with duplicate handling uses narrow tmdb candidates instead of full provider scan`() = runTest {
        val hydratedAt = System.currentTimeMillis()
        val rawSeries = SeriesEntity(
            id = 99L,
            seriesId = 301L,
            providerSeriesId = "301",
            name = "Drama HD",
            providerId = 7L,
            releaseDate = "2024-01-01",
            rating = 7.1f,
            tmdbId = 777L,
            cacheState = "DETAIL_HYDRATED",
            detailHydratedAt = hydratedAt
        )
        val alternateSeries = rawSeries.copy(
            id = 100L,
            seriesId = 302L,
            providerSeriesId = "302",
            name = "Drama 4K",
            lastModified = 50L
        )
        whenever(seriesDao.getById(99L)).thenReturn(rawSeries)
        whenever(seriesDao.getByProviderAndTmdbIdSync(7L, 777L)).thenReturn(listOf(rawSeries, alternateSeries))
        stubProvider(
            Provider(
                id = 7L,
                name = "Xtream",
                type = ProviderType.XTREAM_CODES,
                serverUrl = "http://example.com",
                username = "user",
                password = "pass",
                status = ProviderStatus.ACTIVE
            )
        )
        whenever(episodeDao.getBySeriesSync(99L)).thenReturn(emptyList())

        val result = createRepository(
            duplicateHandlingMode = VodDuplicateHandlingMode.SMART,
            variantPreferenceMode = VodVariantPreferenceMode.BEST_QUALITY
        ).getSeriesDetails(7L, 99L)

        assertThat(result.getOrNull()?.selectedVariantId).isEqualTo(99L)
        assertThat(result.getOrNull()?.variants?.map { it.rawSeriesId }).containsExactly(100L, 99L).inOrder()
        verify(seriesDao).getByProviderAndTmdbIdSync(7L, 777L)
        verify(seriesDao, never()).getByProviderSync(7L)
    }

    @Test
    fun `getSeriesDetails with known presentation reuses handed off variants without candidate lookup`() = runTest {
        val hydratedAt = System.currentTimeMillis()
        val rawSeries = SeriesEntity(
            id = 99L,
            seriesId = 301L,
            providerSeriesId = "301",
            name = "Drama HD",
            providerId = 7L,
            releaseDate = "2024-01-01",
            rating = 7.1f,
            tmdbId = 777L,
            cacheState = "DETAIL_HYDRATED",
            detailHydratedAt = hydratedAt
        )
        whenever(seriesDao.getById(99L)).thenReturn(rawSeries)
        stubProvider(
            Provider(
                id = 7L,
                name = "Xtream",
                type = ProviderType.XTREAM_CODES,
                serverUrl = "http://example.com",
                username = "user",
                password = "pass",
                status = ProviderStatus.ACTIVE
            )
        )
        whenever(episodeDao.getBySeriesSync(99L)).thenReturn(emptyList())

        val knownPresentation = SeriesDetailPresentationHint(
            providerId = 7L,
            logicalGroupId = "series:777",
            variants = listOf(
                VodSeriesVariant(
                    rawSeriesId = 100L,
                    name = "Drama 4K",
                    seriesId = 302L,
                    providerSeriesId = "302",
                    releaseDate = "2024-01-01",
                    tmdbId = 777L,
                    episodeRunTime = null,
                    rating = 7.1f,
                    lastModified = 50L,
                    qualityScore = 4,
                    recencyScore = 50L,
                    reliabilityScore = 0,
                    label = "4K"
                ),
                VodSeriesVariant(
                    rawSeriesId = 99L,
                    name = "Drama HD",
                    seriesId = 301L,
                    providerSeriesId = "301",
                    releaseDate = "2024-01-01",
                    tmdbId = 777L,
                    episodeRunTime = null,
                    rating = 7.1f,
                    lastModified = 0L,
                    qualityScore = 2,
                    recencyScore = 0L,
                    reliabilityScore = 0,
                    label = "HD"
                )
            ),
            duplicateConfidence = VodDuplicateConfidence.EXACT
        )

        val result = createRepository(
            duplicateHandlingMode = VodDuplicateHandlingMode.SMART,
            variantPreferenceMode = VodVariantPreferenceMode.BEST_QUALITY
        ).getSeriesDetails(7L, 99L, knownPresentation)

        assertThat(result.getOrNull()?.selectedVariantId).isEqualTo(99L)
        assertThat(result.getOrNull()?.logicalGroupId).isEqualTo("series:777")
        assertThat(result.getOrNull()?.variants?.map { it.rawSeriesId }).containsExactly(100L, 99L).inOrder()
        verify(seriesDao, never()).getByProviderAndTmdbIdSync(any(), any())
        verify(seriesDao, never()).getByProviderAndReleaseYearPrefixSync(any(), any())
        verify(seriesDao, never()).getByProviderSync(any())
    }

    @Test
    fun `getSeriesByCategory lazily hydrates stalker category when local cache is empty`() = runTest {
        whenever(preferencesRepository.parentalControlLevel).thenReturn(flowOf(0))
        whenever(seriesDao.getCountByCategory(7L, 77L)).thenReturn(flowOf(0))
        whenever(seriesDao.getByCategory(7L, 77L)).thenReturn(flowOf(emptyList()))
        stubProvider(
            Provider(
                id = 7L,
                name = "Stalker",
                type = ProviderType.STALKER_PORTAL,
                serverUrl = "http://example.com",
                stalkerMacAddress = "00:11:22:33:44:55",
                status = ProviderStatus.ACTIVE
            )
        )
        whenever(stalkerApiService.authenticate(any())).thenReturn(
            Result.success(
                StalkerSession(
                    loadUrl = "http://example.com/stalker_portal/server/load.php",
                    portalReferer = "http://example.com/stalker_portal/c/",
                    token = "token"
                ) to StalkerProviderProfile(accountName = "Stalker")
            )
        )
        whenever(stalkerApiService.getSeriesCategories(any(), any())).thenReturn(
            Result.success(listOf(StalkerCategoryRecord(id = "77", name = "Drama")))
        )
        whenever(stalkerApiService.getSeriesPage(any(), any(), anyOrNull(), eq(1))).thenReturn(
            Result.success(
                StalkerPagedItems(
                    items = listOf(
                        StalkerItemRecord(
                            id = "301",
                            name = "Series",
                            categoryId = "77",
                            isSeries = true
                        )
                    ),
                    page = 1,
                    totalPages = 1,
                    pageSize = 1
                )
            )
        )
        whenever(seriesCategoryHydrationDao.get(7L, 77L)).thenReturn(null)
        whenever(episodeDao.deleteOrphans()).thenReturn(0)

        val repository = createRepository()

        val result = repository.getSeriesByCategory(7L, 77L).first()

        assertThat(result).isEmpty()
        verify(seriesDao).upsertCategoryPage(eq(7L), any())
        verify(seriesDao, never()).replaceCategory(eq(7L), eq(77L), any())
        verifyNoInteractions(episodeDao)
    }

    @Test
    fun `stalker series preview loads only first page`() = runTest {
        whenever(preferencesRepository.parentalControlLevel).thenReturn(flowOf(0))
        whenever(seriesDao.getCountByCategory(7L, 77L)).thenReturn(flowOf(0), flowOf(18))
        whenever(seriesDao.getByCategoryPreview(7L, 77L, 18)).thenReturn(flowOf(emptyList()))
        whenever(seriesCategoryHydrationDao.get(7L, 77L)).thenReturn(null)
        whenever(categoryDao.getByProviderAndType(7L, ContentType.SERIES.name)).thenReturn(
            flowOf(listOf(com.streamvault.data.local.entity.CategoryEntity(providerId = 7L, categoryId = 77L, name = "Drama", type = ContentType.SERIES)))
        )
        stubProvider(
            Provider(
                id = 7L,
                name = "Stalker",
                type = ProviderType.STALKER_PORTAL,
                serverUrl = "http://example.com",
                stalkerMacAddress = "00:11:22:33:44:55",
                status = ProviderStatus.ACTIVE
            )
        )
        whenever(stalkerApiService.authenticate(any())).thenReturn(
            Result.success(
                StalkerSession(
                    loadUrl = "http://example.com/stalker_portal/server/load.php",
                    portalReferer = "http://example.com/stalker_portal/c/",
                    token = "token"
                ) to StalkerProviderProfile(accountName = "Stalker")
            )
        )
        whenever(stalkerApiService.getSeriesCategories(any(), any())).thenReturn(
            Result.success(listOf(StalkerCategoryRecord(id = "77", name = "Drama")))
        )
        whenever(stalkerApiService.getSeriesPage(any(), any(), anyOrNull(), eq(1))).thenReturn(
            Result.success(
                StalkerPagedItems(
                    items = (1..18).map { index ->
                        StalkerItemRecord(id = "30$index", name = "Series $index", categoryId = "77", isSeries = true)
                    },
                    page = 1,
                    totalPages = 2,
                    pageSize = 50
                )
            )
        )

        val repository = createRepository()

        repository.getCategoryPreviewRows(7L, listOf(77L), 18).first()

        verify(stalkerApiService).getSeriesPage(any(), any(), anyOrNull(), eq(1))
        verify(stalkerApiService, never()).getSeriesPage(any(), any(), anyOrNull(), eq(2))
    }

    @Test
    fun `stalker preview keeps wildcard-only series category`() = runTest {
        val wildcardId = stalkerStableHashId(7L, ContentType.SERIES, "*")
        whenever(preferencesRepository.parentalControlLevel).thenReturn(flowOf(0))
        whenever(categoryDao.getByProviderAndType(7L, ContentType.SERIES.name)).thenReturn(
            flowOf(
                listOf(
                    CategoryEntity(
                        providerId = 7L,
                        categoryId = wildcardId,
                        name = "*",
                        type = ContentType.SERIES
                    )
                )
            )
        )
        whenever(seriesDao.getCountByCategory(7L, wildcardId)).thenReturn(flowOf(18))
        whenever(seriesCategoryHydrationDao.get(7L, wildcardId)).thenReturn(null)
        whenever(seriesDao.getByCategoryPreview(7L, wildcardId, 18)).thenReturn(
            flowOf(
                listOf(
                    SeriesBrowseEntity(
                        id = 1L,
                        seriesId = 1L,
                        providerSeriesId = "1",
                        name = "Series",
                        categoryId = wildcardId,
                        providerId = 7L
                    )
                )
            )
        )
        stubProvider(stalkerProvider())

        val result = createRepository().getCategoryPreviewRows(7L, listOf(wildcardId), 18).first()

        assertThat(result[wildcardId]).hasSize(1)
    }

    @Test
    fun `opening stalker series category fills at least forty items across natural pages`() = runTest {
        whenever(preferencesRepository.parentalControlLevel).thenReturn(flowOf(0))
        whenever(seriesDao.getCountByCategory(7L, 77L)).thenReturn(flowOf(0), flowOf(14), flowOf(28), flowOf(42))
        whenever(seriesDao.getByCategoryPage(7L, 77L, 60, 0)).thenReturn(flowOf(emptyList()))
        whenever(seriesCategoryHydrationDao.get(7L, 77L)).thenReturn(null)
        stubProvider(stalkerProvider())
        whenever(stalkerApiService.authenticate(any())).thenReturn(stalkerSessionResult())
        whenever(stalkerApiService.getSeriesCategories(any(), any())).thenReturn(
            Result.success(listOf(StalkerCategoryRecord(id = "77", name = "Drama")))
        )
        (1..3).forEach { page ->
            whenever(stalkerApiService.getSeriesPage(any(), any(), anyOrNull(), eq(page))).thenReturn(
                Result.success(stalkerSeriesPage(page = page, itemCount = 14))
            )
        }

        createRepository().getSeriesByCategoryPage(7L, 77L, limit = 60, offset = 0).first()

        (1..3).forEach { page ->
            verify(stalkerApiService).getSeriesPage(any(), any(), anyOrNull(), eq(page))
        }
        verify(stalkerApiService, never()).getSeriesPage(any(), any(), anyOrNull(), eq(4))
    }

    @Test
    fun `scrolling cached stalker series category appends exactly one natural page`() = runTest {
        whenever(preferencesRepository.parentalControlLevel).thenReturn(flowOf(0))
        whenever(seriesDao.getCountByCategory(7L, 77L)).thenReturn(flowOf(42), flowOf(56))
        whenever(seriesDao.getByCategoryPage(7L, 77L, 60, 60)).thenReturn(flowOf(emptyList()))
        whenever(seriesCategoryHydrationDao.get(7L, 77L)).thenReturn(
            SeriesCategoryHydrationEntity(
                providerId = 7L,
                categoryId = 77L,
                itemCount = 42,
                lastStatus = "SUCCESS",
                lastLoadedPage = 3,
                lastAttemptedPage = 3,
                lastSuccessfulPage = 3,
                totalPages = 10,
                pageSize = 14
            )
        )
        stubProvider(stalkerProvider())
        whenever(stalkerApiService.authenticate(any())).thenReturn(stalkerSessionResult())
        whenever(stalkerApiService.getSeriesCategories(any(), any())).thenReturn(
            Result.success(listOf(StalkerCategoryRecord(id = "77", name = "Drama")))
        )
        whenever(stalkerApiService.getSeriesPage(any(), any(), anyOrNull(), eq(4))).thenReturn(
            Result.success(stalkerSeriesPage(page = 4, itemCount = 14))
        )

        createRepository().getSeriesByCategoryPage(7L, 77L, limit = 60, offset = 60).first()

        verify(stalkerApiService).getSeriesPage(any(), any(), anyOrNull(), eq(4))
        verify(stalkerApiService, never()).getSeriesPage(any(), any(), anyOrNull(), eq(5))
    }

    @Test
    fun `browseSeries recently updated excludes stale entries and uses filtered total count`() = runTest {
        whenever(preferencesRepository.parentalControlLevel).thenReturn(flowOf(0))
        whenever(preferencesRepository.xtreamBase64TextCompatibility).thenReturn(flowOf(false))
        whenever(seriesDao.getFreshCountByProvider(7L)).thenReturn(flowOf(1))
        whenever(seriesDao.getFreshPreview(7L, 100)).thenReturn(
            flowOf(
                listOf(
                    SeriesBrowseEntity(id = 1L, seriesId = 101L, name = "Fresh Series", providerId = 7L, lastModified = 10_000L),
                    SeriesBrowseEntity(id = 2L, seriesId = 102L, name = "Stale Series", providerId = 7L, lastModified = 0L, releaseDate = "2025-01-01")
                )
            )
        )
        whenever(favoriteDao.getAllByType(7L, ContentType.SERIES.name)).thenReturn(flowOf(emptyList()))
        whenever(playbackHistoryDao.getByProvider(7L)).thenReturn(flowOf(emptyList()))

        val repository = createRepository()

        val result = repository.browseSeries(
            LibraryBrowseQuery(
                providerId = 7L,
                sortBy = LibrarySortBy.UPDATED,
                filterBy = LibraryFilterBy(LibraryFilterType.RECENTLY_UPDATED),
                offset = 0,
                limit = 20
            )
        ).first()

        assertThat(result.totalCount).isEqualTo(1)
        assertThat(result.items.map { it.name }).containsExactly("Fresh Series")
    }

    @Test
    fun `browseSeries release uses release ordering while updated remains last modified`() = runTest {
        whenever(preferencesRepository.parentalControlLevel).thenReturn(flowOf(0))
        whenever(preferencesRepository.xtreamBase64TextCompatibility).thenReturn(flowOf(false))
        whenever(seriesDao.getCount(7L)).thenReturn(flowOf(2))
        whenever(seriesDao.getReleasedPreview(7L, 100)).thenReturn(
            flowOf(
                listOf(
                    SeriesBrowseEntity(
                        id = 1L,
                        seriesId = 101L,
                        name = "Updated Later",
                        providerId = 7L,
                        releaseDate = "2020-01-01",
                        lastModified = 20_000L
                    ),
                    SeriesBrowseEntity(
                        id = 2L,
                        seriesId = 102L,
                        name = "Released Later",
                        providerId = 7L,
                        releaseDate = "2024-01-01",
                        lastModified = 10_000L
                    )
                )
            )
        )
        whenever(favoriteDao.getAllByType(7L, ContentType.SERIES.name)).thenReturn(flowOf(emptyList()))
        whenever(playbackHistoryDao.getByProvider(7L)).thenReturn(flowOf(emptyList()))

        val repository = createRepository()

        val result = repository.browseSeries(
            LibraryBrowseQuery(
                providerId = 7L,
                sortBy = LibrarySortBy.RELEASE,
                offset = 0,
                limit = 20
            )
        ).first()

        assertThat(result.totalCount).isEqualTo(2)
        assertThat(result.items.map { it.name }).containsExactly("Released Later", "Updated Later").inOrder()
        verify(seriesDao).getReleasedPreview(7L, 100)
        verify(seriesDao, never()).getFreshPreview(7L, 100)
    }

    @Test
    fun `browseSeries library order uses last modified freshness instead of title order`() = runTest {
        whenever(preferencesRepository.parentalControlLevel).thenReturn(flowOf(0))
        whenever(preferencesRepository.xtreamBase64TextCompatibility).thenReturn(flowOf(false))
        whenever(seriesDao.getCount(7L)).thenReturn(flowOf(2))
        whenever(seriesDao.getFreshCursorPage(7L, 40)).thenReturn(
            listOf(
                SeriesBrowseEntity(id = 2L, seriesId = 102L, name = "Zulu Series", providerId = 7L, lastModified = 20_000L),
                SeriesBrowseEntity(id = 1L, seriesId = 101L, name = "Alpha Series", providerId = 7L, lastModified = 10_000L)
            )
        )
        whenever(favoriteDao.getAllByType(7L, ContentType.SERIES.name)).thenReturn(flowOf(emptyList()))

        val repository = createRepository()

        val result = repository.browseSeries(
            LibraryBrowseQuery(
                providerId = 7L,
                sortBy = LibrarySortBy.LIBRARY,
                offset = 0,
                limit = 20
            )
        ).first()

        assertThat(result.totalCount).isEqualTo(2)
        assertThat(result.items.map { it.name }).containsExactly("Zulu Series", "Alpha Series").inOrder()
        verify(seriesDao).getFreshCursorPage(7L, 40)
        verify(seriesDao, never()).getByProviderCursorPage(any(), any())
    }

    @Test
    fun `getSeriesByCategory skips legacy xtream empty retry hydration`() = runTest {
        whenever(preferencesRepository.parentalControlLevel).thenReturn(flowOf(0))
        whenever(preferencesRepository.xtreamBase64TextCompatibility).thenReturn(flowOf(false))
        whenever(seriesDao.getCountByCategory(7L, 77L)).thenReturn(flowOf(0))
        whenever(seriesDao.getByCategory(7L, 77L)).thenReturn(flowOf(emptyList()))
        whenever(seriesCategoryHydrationDao.get(7L, 77L)).thenReturn(null)
        stubProvider(
            Provider(
                id = 7L,
                name = "Xtream",
                type = ProviderType.XTREAM_CODES,
                serverUrl = "http://example.com",
                username = "user",
                password = "pass",
                xtreamFastSyncEnabled = true,
                status = ProviderStatus.ACTIVE
            )
        )
        val repository = createRepository()

        repository.getSeriesByCategory(7L, 77L).first()

        verify(syncManager).prioritizeXtreamIndexCategory(7L, ContentType.SERIES, 77L)
        verify(seriesDao, never()).replaceCategory(eq(7L), eq(77L), any())
        verify(seriesCategoryHydrationDao, never()).upsert(any())
        verify(xtreamApiService, never()).getSeriesList(any(), any())
    }

    @Test
    fun `getSeriesDetails falls back to remote series id lookup`() = runTest {
        whenever(preferencesRepository.xtreamBase64TextCompatibility).thenReturn(flowOf(false))
        val seriesEntity = SeriesEntity(
            id = 15L,
            seriesId = 301L,
            name = "Series",
            providerId = 7L
        )
        whenever(seriesDao.getById(301L)).thenReturn(null)
        whenever(seriesDao.getBySeriesId(7L, 301L)).thenReturn(seriesEntity)
        stubProvider(
            Provider(
                id = 7L,
                name = "Playlist",
                type = ProviderType.M3U,
                serverUrl = "http://example.com",
                username = "user",
                password = "pass",
                status = ProviderStatus.ACTIVE
            )
        )
        whenever(episodeDao.getBySeriesSync(15L)).thenReturn(emptyList())

        val repository = createRepository()

        val result = repository.getSeriesDetails(7L, 301L)

        assertThat(result).isInstanceOf(com.streamvault.domain.model.Result.Success::class.java)
        val series = (result as com.streamvault.domain.model.Result.Success).data
        assertThat(series.id).isEqualTo(15L)
        assertThat(series.seriesId).isEqualTo(301L)
    }

    @Test
    fun `getSeriesDetails returns local series when xtream details fail`() = runTest {
        whenever(preferencesRepository.xtreamBase64TextCompatibility).thenReturn(flowOf(false))
        val seriesEntity = SeriesEntity(
            id = 15L,
            seriesId = 301L,
            name = "Stored Series",
            posterUrl = "https://img.example.test/poster.jpg",
            providerId = 7L
        )
        whenever(seriesDao.getById(301L)).thenReturn(null)
        whenever(seriesDao.getBySeriesId(7L, 301L)).thenReturn(seriesEntity)
        stubProvider(
            Provider(
                id = 7L,
                name = "Xtream",
                type = ProviderType.XTREAM_CODES,
                serverUrl = "http://example.com",
                username = "user",
                password = "pass",
                status = ProviderStatus.ACTIVE
            )
        )
        whenever(episodeDao.getBySeriesSync(15L)).thenReturn(emptyList())
        whenever(xtreamApiService.getSeriesInfo(any(), any())).thenThrow(RuntimeException("bad response"))

        val repository = createRepository()

        val result = repository.getSeriesDetails(7L, 301L)

        assertThat(result).isInstanceOf(com.streamvault.domain.model.Result.Success::class.java)
        val series = (result as com.streamvault.domain.model.Result.Success).data
        assertThat(series.id).isEqualTo(15L)
        assertThat(series.name).isEqualTo("Stored Series")
        assertThat(series.posterUrl).isEqualTo("https://img.example.test/poster.jpg")
    }

    @Test
    fun `getSeriesDetails returns local series when xtream details hang`() = runTest {
        whenever(preferencesRepository.xtreamBase64TextCompatibility).thenReturn(flowOf(false))
        val seriesEntity = SeriesEntity(
            id = 15L,
            seriesId = 301L,
            name = "Stored Series",
            posterUrl = "https://img.example.test/poster.jpg",
            providerId = 7L
        )
        whenever(seriesDao.getById(301L)).thenReturn(null)
        whenever(seriesDao.getBySeriesId(7L, 301L)).thenReturn(seriesEntity)
        stubProvider(
            Provider(
                id = 7L,
                name = "Xtream",
                type = ProviderType.XTREAM_CODES,
                serverUrl = "http://example.com",
                username = "user",
                password = "pass",
                status = ProviderStatus.ACTIVE
            )
        )
        whenever(episodeDao.getBySeriesSync(15L)).thenReturn(emptyList())
        whenever(seriesCatalogSource.hydrateSeries(any(), any())).thenReturn(
            Result.error(
                "Xtream series detail hydration timed out",
                ProviderCapabilityTimeoutException("Xtream series detail hydration timed out")
            )
        )

        val result = createRepository().getSeriesDetails(7L, 301L)

        assertThat(result).isInstanceOf(com.streamvault.domain.model.Result.Success::class.java)
        val series = (result as com.streamvault.domain.model.Result.Success).data
        assertThat(series.id).isEqualTo(15L)
        assertThat(series.name).isEqualTo("Stored Series")
        assertThat(series.posterUrl).isEqualTo("https://img.example.test/poster.jpg")
        verify(xtreamContentIndexDao).markDetailHydrationError(
            providerId = eq(7L),
            contentType = eq(ContentType.SERIES.name),
            remoteId = eq("301"),
            errorState = eq("DETAIL_FAILED_TIMEOUT")
        )
    }

    @Test
    fun `getSeriesDetails keeps persisted episodes when remote payload only has season metadata`() = runTest {
        whenever(preferencesRepository.xtreamBase64TextCompatibility).thenReturn(flowOf(false))
        val seriesEntity = SeriesEntity(
            id = 15L,
            seriesId = 301L,
            name = "Stored Series",
            providerId = 7L
        )
        whenever(seriesDao.getById(15L)).thenReturn(seriesEntity)
        whenever(seriesDao.getById(15L)).thenReturn(seriesEntity)
        stubProvider(
            Provider(
                id = 7L,
                name = "Xtream",
                type = ProviderType.XTREAM_CODES,
                serverUrl = "http://example.com",
                username = "user",
                password = "pass",
                status = ProviderStatus.ACTIVE
            )
        )
        whenever(xtreamApiService.getSeriesInfo(any(), any())).thenReturn(
            XtreamSeriesInfoResponse(
                info = XtreamSeriesItem(name = "Stored Series"),
                seasons = listOf(
                    XtreamSeason(
                        seasonNumber = 1,
                        name = "Season 1",
                        episodeCount = 12
                    )
                )
            )
        )
        whenever(episodeDao.getBySeriesSync(15L)).thenReturn(
            listOf(
                EpisodeBrowseEntity(
                    id = 1L,
                    episodeId = 7001L,
                    title = "Pilot",
                    episodeNumber = 1,
                    seasonNumber = 1,
                    streamUrl = "internal://episode/7001",
                    seriesId = 15L,
                    providerId = 7L
                )
            )
        )

        val result = createRepository().getSeriesDetails(7L, 15L)

        assertThat(result).isInstanceOf(com.streamvault.domain.model.Result.Success::class.java)
        val series = (result as com.streamvault.domain.model.Result.Success).data
        assertThat(series.seasons).hasSize(1)
        assertThat(series.seasons.first().name).isEqualTo("Season 1")
        assertThat(series.seasons.first().episodes.map { it.title }).containsExactly("Pilot")
    }

    @Test
    fun `getSeriesDetails keeps persisted episodes when remote returns a partial set`() = runTest {
        whenever(preferencesRepository.xtreamBase64TextCompatibility).thenReturn(flowOf(false))
        val seriesEntity = SeriesEntity(
            id = 15L,
            seriesId = 301L,
            name = "Stored Series",
            providerId = 7L
        )
        whenever(seriesDao.getById(15L)).thenReturn(seriesEntity)
        stubProvider(
            Provider(
                id = 7L,
                name = "Xtream",
                type = ProviderType.XTREAM_CODES,
                serverUrl = "http://example.com",
                username = "user",
                password = "pass",
                status = ProviderStatus.ACTIVE
            )
        )
        whenever(episodeDao.getBySeriesSync(15L)).thenReturn(
            listOf(
                EpisodeBrowseEntity(
                    id = 1L,
                    episodeId = 7001L,
                    title = "Episode 1",
                    episodeNumber = 1,
                    seasonNumber = 1,
                    seriesId = 15L,
                    providerId = 7L
                ),
                EpisodeBrowseEntity(
                    id = 2L,
                    episodeId = 7002L,
                    title = "Episode 2",
                    episodeNumber = 2,
                    seasonNumber = 1,
                    seriesId = 15L,
                    providerId = 7L
                )
            )
        )
        whenever(seriesCatalogSource.hydrateSeries(any(), any())).thenReturn(
            Result.success(
                Series(
                    id = 15L,
                    name = "Stored Series",
                    providerId = 7L,
                    seriesId = 301L,
                    seasons = listOf(
                        Season(
                            seasonNumber = 1,
                            episodes = listOf(
                                Episode(
                                    id = 0L,
                                    episodeId = 7001L,
                                    title = "Episode 1",
                                    episodeNumber = 1,
                                    seasonNumber = 1,
                                    seriesId = 15L,
                                    providerId = 7L
                                )
                            ),
                            episodeCount = 1
                        )
                    )
                )
            )
        )

        val result = createRepository().getSeriesDetails(7L, 15L)

        assertThat(result).isInstanceOf(com.streamvault.domain.model.Result.Success::class.java)
        val series = (result as com.streamvault.domain.model.Result.Success).data
        assertThat(series.seasons.flatMap { it.episodes }.map { it.title }).containsExactly("Episode 1", "Episode 2")
        verify(episodeDao, never()).replaceAll(any(), any(), any())
    }

    @Test
    fun `getSeriesDetails uses provider series id for stalker details`() = runTest {
        val seriesEntity = SeriesEntity(
            id = 15L,
            seriesId = 256103980L,
            providerSeriesId = "55000:55000",
            name = "Stored Series",
            providerId = 7L
        )
        whenever(seriesDao.getById(15L)).thenReturn(seriesEntity)
        stubProvider(
            Provider(
                id = 7L,
                name = "Stalker",
                type = ProviderType.STALKER_PORTAL,
                serverUrl = "http://example.com",
                stalkerMacAddress = "00:11:22:33:44:55",
                status = ProviderStatus.ACTIVE
            )
        )
        whenever(seriesCatalogSource.hydrateSeries(any(), any())).thenReturn(
            Result.success(
                Series(
                    id = 15L,
                    name = "",
                    providerId = 7L,
                    seriesId = 256103980L,
                    providerSeriesId = "55000:55000",
                    seasons = listOf(
                        Season(
                            seasonNumber = 1,
                            episodes = listOf(
                                Episode(
                                    id = 1L,
                                    title = "Episode 1",
                                    episodeNumber = 1,
                                    seasonNumber = 1,
                                    streamUrl = "cmd",
                                    seriesId = 15L,
                                    providerId = 7L
                                )
                            ),
                            episodeCount = 1
                        )
                    )
                )
            )
        )
        whenever(episodeDao.getBySeriesSync(15L)).thenReturn(emptyList())

        val repository = createRepository()

        val result = repository.getSeriesDetails(7L, 15L)

        assertThat(result).isInstanceOf(com.streamvault.domain.model.Result.Success::class.java)
        val series = (result as com.streamvault.domain.model.Result.Success).data
        assertThat(series.name).isEqualTo("Stored Series")
        val referenceCaptor = argumentCaptor<ProviderContentReference>()
        verify(seriesCatalogSource).hydrateSeries(referenceCaptor.capture(), any())
        assertThat(referenceCaptor.firstValue.remoteId).isEqualTo("55000:55000")
        verifyNoInteractions(xtreamApiService)
        verify(xtreamContentIndexDao, never()).markDetailHydrated(any(), any(), any(), any(), anyOrNull(), any())
    }

    @Test
    fun `searchSeries returns empty list when sqlite throws for malformed fts query`() = runTest {
        whenever(preferencesRepository.parentalControlLevel).thenReturn(flowOf(0))
        whenever(seriesDao.search(eq(7L), any(), any())).thenReturn(
            flow { throw SQLiteException("malformed MATCH expression") }
        )
        whenever(seriesDao.searchFallback(eq(7L), any(), any())).thenReturn(
            flowOf(
                listOf(
                    SeriesBrowseEntity(
                        id = 15L,
                        seriesId = 1500L,
                        name = "Drama House",
                        providerId = 7L
                    )
                )
            )
        )
        whenever(favoriteDao.getAllByType(7L, ContentType.SERIES.name)).thenReturn(flowOf(emptyList()))

        val repository = createRepository()

        val result = repository.searchSeries(7L, "drama").first()

        assertThat(result.map { it.name }).containsExactly("Drama House")
    }

    @Test
    fun `searchSeries returns empty without like fallback when fts has no rows`() = runTest {
        whenever(preferencesRepository.parentalControlLevel).thenReturn(flowOf(0))
        whenever(seriesDao.search(eq(7L), any(), any())).thenReturn(flowOf(emptyList()))
        whenever(favoriteDao.getAllByType(7L, ContentType.SERIES.name)).thenReturn(flowOf(emptyList()))

        val repository = createRepository()

        val result = repository.searchSeries(7L, "drama").first()

        assertThat(result).isEmpty()
        verify(seriesDao, never()).searchFallback(eq(7L), any(), any())
    }

    @Test
    fun `searchSeries does not run like fallback when fts returns rows`() = runTest {
        whenever(preferencesRepository.parentalControlLevel).thenReturn(flowOf(0))
        whenever(seriesDao.search(eq(7L), any(), any())).thenReturn(
            flowOf(
                listOf(
                    SeriesBrowseEntity(
                        id = 17L,
                        seriesId = 1700L,
                        name = "Drama",
                        providerId = 7L
                    )
                )
            )
        )
        whenever(favoriteDao.getAllByType(7L, ContentType.SERIES.name)).thenReturn(flowOf(emptyList()))

        val repository = createRepository()

        val result = repository.searchSeries(7L, "drama").first()

        assertThat(result.map { it.name }).containsExactly("Drama")
        verify(seriesDao, never()).searchFallback(eq(7L), any(), any())
    }

    @Test
    fun `browseSeries search uses bounded page query`() = runTest {
        whenever(preferencesRepository.parentalControlLevel).thenReturn(flowOf(0))
        whenever(seriesDao.searchPage(eq(7L), any(), any(), any(), any(), any(), any())).thenReturn(
            listOf(
                SeriesBrowseEntity(id = 17L, seriesId = 1700L, name = "Drama", providerId = 7L),
                SeriesBrowseEntity(id = 18L, seriesId = 1800L, name = "Drama Island", providerId = 7L)
            )
        )
        whenever(favoriteDao.getAllByType(7L, ContentType.SERIES.name)).thenReturn(flowOf(emptyList()))

        val result = createRepository().browseSeries(
            LibraryBrowseQuery(
                providerId = 7L,
                searchQuery = "drama",
                offset = 0,
                limit = 1
            )
        ).first()

        assertThat(result.totalCount).isEqualTo(2)
        assertThat(result.items.map { it.name }).containsExactly("Drama")
        verify(seriesDao, times(1)).searchPage(eq(7L), any(), any(), any(), any(), eq(2), eq(0))
        verify(seriesDao, never()).search(eq(7L), any(), any())
        verify(seriesDao, never()).searchFallback(eq(7L), any(), any())
    }

    @Test
    fun `browseSeries groups duplicate series when duplicate handling is enabled`() = runTest {
        whenever(preferencesRepository.parentalControlLevel).thenReturn(flowOf(0))
        whenever(seriesDao.getCount(7L)).thenReturn(flowOf(2))
        whenever(seriesDao.getFreshCursorPage(7L, 40)).thenReturn(
            listOf(
                SeriesBrowseEntity(
                    id = 17L,
                    seriesId = 1700L,
                    providerSeriesId = "1700",
                    name = "Drama HD",
                    providerId = 7L,
                    releaseDate = "2024-01-01",
                    tmdbId = 5000L,
                    lastModified = 10L
                ),
                SeriesBrowseEntity(
                    id = 18L,
                    seriesId = 1800L,
                    providerSeriesId = "1800",
                    name = "Drama 4K",
                    providerId = 7L,
                    releaseDate = "2024-02-01",
                    tmdbId = 5000L,
                    lastModified = 20L
                )
            )
        )
        whenever(favoriteDao.getAllByType(7L, ContentType.SERIES.name)).thenReturn(flowOf(emptyList()))
        whenever(playbackHistoryDao.getByProvider(7L)).thenReturn(flowOf(emptyList()))

        val result = createRepository(
            duplicateHandlingMode = VodDuplicateHandlingMode.SMART,
            variantPreferenceMode = VodVariantPreferenceMode.BEST_QUALITY
        ).browseSeries(
            LibraryBrowseQuery(
                providerId = 7L,
                sortBy = LibrarySortBy.LIBRARY,
                offset = 0,
                limit = 20
            )
        ).first()

        assertThat(result.totalCount).isEqualTo(1)
        assertThat(result.items).hasSize(1)
        assertThat(result.items.single().selectedVariantId).isEqualTo(18L)
        assertThat(result.items.single().variants.map { it.rawSeriesId }).containsExactly(18L, 17L).inOrder()
        verify(seriesDao).getFreshCursorPage(7L, 40)
        verify(seriesDao, never()).getByProviderPage(any(), any(), any())
    }

    @Test
    fun `searchSeries groups duplicate results when duplicate handling is enabled`() = runTest {
        whenever(preferencesRepository.parentalControlLevel).thenReturn(flowOf(0))
        whenever(seriesDao.search(eq(7L), any(), eq(200))).thenReturn(
            flowOf(
                listOf(
                    SeriesBrowseEntity(
                        id = 17L,
                        seriesId = 1700L,
                        providerSeriesId = "1700",
                        name = "Drama HD",
                        providerId = 7L,
                        releaseDate = "2024-01-01",
                        tmdbId = 5000L,
                        lastModified = 10L
                    ),
                    SeriesBrowseEntity(
                        id = 18L,
                        seriesId = 1800L,
                        providerSeriesId = "1800",
                        name = "Drama 4K",
                        providerId = 7L,
                        releaseDate = "2024-02-01",
                        tmdbId = 5000L,
                        lastModified = 20L
                    )
                )
            )
        )
        whenever(favoriteDao.getAllByType(7L, ContentType.SERIES.name)).thenReturn(flowOf(emptyList()))

        val result = createRepository(
            duplicateHandlingMode = VodDuplicateHandlingMode.SMART,
            variantPreferenceMode = VodVariantPreferenceMode.BEST_QUALITY
        ).searchSeries(7L, "drama").first()

        assertThat(result).hasSize(1)
        assertThat(result.single().selectedVariantId).isEqualTo(18L)
        assertThat(result.single().variants.map { it.rawSeriesId }).containsExactly(18L, 17L).inOrder()
    }

    private fun createRepository(
        duplicateHandlingMode: VodDuplicateHandlingMode = VodDuplicateHandlingMode.SHOW_ALL,
        variantPreferenceMode: VodVariantPreferenceMode = VodVariantPreferenceMode.BALANCED,
        preferredVariants: Map<String, Long> = emptyMap(),
        variantObservations: Map<Long, VodVariantObservation> = emptyMap()
    ): SeriesRepositoryImpl {
        whenever(preferencesRepository.vodDuplicateHandlingMode).thenReturn(flowOf(duplicateHandlingMode))
        whenever(preferencesRepository.vodVariantPreferenceMode).thenReturn(flowOf(variantPreferenceMode))
        whenever(preferencesRepository.vodVariantSelections).thenReturn(flowOf(preferredVariants))
        whenever(preferencesRepository.vodVariantObservations).thenReturn(flowOf(variantObservations))
        return SeriesRepositoryImpl(
            seriesDao = seriesDao,
            episodeDao = episodeDao,
            categoryDao = categoryDao,
            favoriteDao = favoriteDao,
            playbackHistoryDao = playbackHistoryDao,
            playbackHistoryRepository = playbackHistoryRepository,
            providerDao = providerDao,
            preferencesRepository = preferencesRepository,
            xtreamStreamUrlResolver = xtreamStreamUrlResolver,
            xtreamContentIndexDao = xtreamContentIndexDao,
            xtreamIndexJobDao = xtreamIndexJobDao,
            syncManager = syncManager,
            seriesCategoryHydrationDao = seriesCategoryHydrationDao,
            stalkerRequestCoordinator = com.streamvault.data.remote.stalker.StalkerRequestCoordinator(),
            providerCapabilityResolver = providerCapabilityResolver,
            typedProviderClientFactory = typedProviderClientFactory
        )
    }

    private suspend fun stubProvider(provider: Provider) {
        whenever(providerDao.getById(provider.id)).thenReturn(provider.toEntity())
        val snapshot = provider.toProviderSnapshot()
        whenever(providerCapabilityResolver.snapshot(provider.id)).thenReturn(snapshot)
        if (provider.type == ProviderType.STALKER_PORTAL) {
            whenever(typedProviderClientFactory.stalker(snapshot)).thenReturn(
                CapabilityResolution.Available(
                    StalkerProvider(
                        providerId = provider.id,
                        api = stalkerApiService,
                        portalUrl = provider.serverUrl,
                        macAddress = provider.stalkerMacAddress,
                        deviceProfile = provider.stalkerDeviceProfile,
                        timezone = provider.stalkerDeviceTimezone,
                        locale = provider.stalkerDeviceLocale,
                        identityResolver = stalkerRemoteIdentityResolver,
                        portalStateStore = mock()
                    )
                )
            )
        }
    }

    private fun stalkerProvider() = Provider(
        id = 7L,
        name = "Stalker",
        type = ProviderType.STALKER_PORTAL,
        serverUrl = "http://example.com",
        stalkerMacAddress = "00:11:22:33:44:55",
        status = ProviderStatus.ACTIVE
    )

    private fun stalkerSessionResult(): Result<Pair<StalkerSession, StalkerProviderProfile>> =
        Result.success(
            StalkerSession(
                loadUrl = "http://example.com/stalker_portal/server/load.php",
                portalReferer = "http://example.com/stalker_portal/c/",
                token = "token"
            ) to StalkerProviderProfile(accountName = "Stalker")
        )

    private fun stalkerSeriesPage(page: Int, itemCount: Int): StalkerPagedItems =
        StalkerPagedItems(
            items = (1..itemCount).map { index ->
                StalkerItemRecord(
                    id = "${page}00$index",
                    name = "Series $page-$index",
                    categoryId = "77",
                    isSeries = true
                )
            },
            page = page,
            totalPages = 10,
            pageSize = itemCount
        )
}
