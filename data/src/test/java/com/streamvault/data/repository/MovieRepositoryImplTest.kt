package com.streamvault.data.repository

import android.database.sqlite.SQLiteException
import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.dao.CategoryDao
import com.streamvault.data.local.dao.FavoriteDao
import com.streamvault.data.local.dao.MovieCategoryHydrationDao
import com.streamvault.data.local.dao.MovieDao
import com.streamvault.data.local.dao.PlaybackHistoryDao
import com.streamvault.data.local.dao.ProviderDao
import com.streamvault.data.local.dao.XtreamContentIndexDao
import com.streamvault.data.local.dao.XtreamIndexJobDao
import com.streamvault.data.local.DatabaseTransactionRunner
import com.streamvault.data.local.entity.FavoriteEntity
import com.streamvault.data.local.entity.MovieBrowseEntity
import com.streamvault.data.local.entity.MovieCategoryHydrationEntity
import com.streamvault.data.local.entity.MovieEntity
import com.streamvault.data.local.entity.PlaybackHistoryLiteEntity
import com.streamvault.data.local.entity.ProviderEntity
import com.streamvault.data.local.entity.XtreamIndexJobEntity
import com.streamvault.data.mapper.toEntity
import com.streamvault.data.provider.ProviderCapabilityResolver
import com.streamvault.data.provider.TypedProviderClientFactory
import com.streamvault.data.provider.toProviderSnapshot
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.data.remote.stalker.StalkerDeviceProfile
import com.streamvault.data.remote.stalker.StalkerProviderProfile
import com.streamvault.data.remote.stalker.StalkerProvider
import com.streamvault.data.remote.stalker.StalkerSession
import com.streamvault.data.remote.stalker.StalkerCategoryRecord
import com.streamvault.data.remote.stalker.StalkerItemRecord
import com.streamvault.data.remote.stalker.StalkerPagedItems
import com.streamvault.data.remote.stalker.StalkerRemoteIdentityResolver
import com.streamvault.data.remote.stalker.stalkerStableHashId
import com.streamvault.data.remote.dto.XtreamCategory
import com.streamvault.data.remote.dto.XtreamStream
import com.streamvault.data.remote.stalker.StalkerApiService
import com.streamvault.data.remote.xtream.XtreamApiService
import com.streamvault.data.remote.xtream.XtreamStreamUrlResolver
import com.streamvault.data.security.CredentialCrypto
import com.streamvault.data.sync.SyncManager
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.LibraryBrowseQuery
import com.streamvault.domain.model.LibraryFilterBy
import com.streamvault.domain.model.LibraryFilterType
import com.streamvault.domain.model.LibrarySortBy
import com.streamvault.domain.model.MovieDetailPresentationHint
import com.streamvault.domain.model.PlaybackHistory
import com.streamvault.domain.model.ProviderStatus
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.LegacyProvider as Provider
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.SyncMetadata
import com.streamvault.domain.model.VodDuplicateConfidence
import com.streamvault.domain.model.VodDuplicateHandlingMode
import com.streamvault.domain.model.VodCategoryHydrationRequest
import com.streamvault.domain.model.VodCategoryLoadMode
import com.streamvault.domain.model.VodMovieVariant
import com.streamvault.domain.model.VodSyncMode
import com.streamvault.domain.model.VodVariantObservation
import com.streamvault.domain.model.VodVariantPreferenceMode
import com.streamvault.domain.repository.PlaybackHistoryRepository
import com.streamvault.domain.repository.SyncMetadataRepository
import com.streamvault.domain.provider.CapabilityResolution
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.Mockito.timeout
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class MovieRepositoryImplTest {

    private val movieDao: MovieDao = mock()
    private val categoryDao: CategoryDao = mock()
    private val providerDao: ProviderDao = mock()
    private val stalkerApiService: StalkerApiService = mock()
    private val xtreamApiService: XtreamApiService = mock()
    private val preferencesRepository: PreferencesRepository = mock()
    private val favoriteDao: FavoriteDao = mock()
    private val playbackHistoryDao: PlaybackHistoryDao = mock()
    private val playbackHistoryRepository: PlaybackHistoryRepository = mock()
    private val xtreamStreamUrlResolver: XtreamStreamUrlResolver = mock()
    private val movieCategoryHydrationDao: MovieCategoryHydrationDao = mock()
    private val syncMetadataRepository: SyncMetadataRepository = mock()
    private val xtreamContentIndexDao: XtreamContentIndexDao = mock()
    private val xtreamIndexJobDao: XtreamIndexJobDao = mock()
    private val syncManager: SyncManager = mock()
    private val providerCapabilityResolver: ProviderCapabilityResolver = mock()
    private val typedProviderClientFactory: TypedProviderClientFactory = mock()
    private val stalkerRemoteIdentityResolver: StalkerRemoteIdentityResolver = mock()
    private val credentialCrypto = object : CredentialCrypto {
        override fun encryptIfNeeded(value: String): String = value
        override fun decryptIfNeeded(value: String): String = value
    }
    private val transactionRunner = object : DatabaseTransactionRunner {
        override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
    }

    @Test
    fun `getRecommendations prioritizes movies similar to recent history`() = runTest {
        val watchedMovie = movieEntity(
            id = 1L,
            name = "Space Run",
            genre = "Action, Sci-Fi",
            categoryId = 10L,
            rating = 7.2f
        )
        val recommendedMovie = movieEntity(
            id = 2L,
            name = "Galaxy Pursuit",
            genre = "Sci-Fi Action",
            categoryId = 10L,
            rating = 8.8f
        )
        val unrelatedMovie = movieEntity(
            id = 3L,
            name = "Quiet Tea",
            genre = "Drama",
            categoryId = 11L,
            rating = 9.5f
        )

    whenever(preferencesRepository.parentalControlLevel).thenReturn(flowOf(0))
    whenever(preferencesRepository.xtreamBase64TextCompatibility).thenReturn(flowOf(false))
        whenever(movieDao.getTopRatedPreview(7L, 48)).thenReturn(flowOf(listOf(recommendedMovie, unrelatedMovie, watchedMovie)))
        whenever(movieDao.getFreshPreview(7L, 48)).thenReturn(flowOf(listOf(recommendedMovie, unrelatedMovie, watchedMovie)))
        whenever(favoriteDao.getAllByType(7L, ContentType.MOVIE.name)).thenReturn(flowOf(emptyList()))
        whenever(playbackHistoryDao.getRecentlyWatchedByProvider(eq(7L), eq(24))).thenReturn(
            flowOf(
                listOf(
                    PlaybackHistoryLiteEntity(
                        contentId = watchedMovie.id,
                        contentType = ContentType.MOVIE,
                        providerId = 7L,
                        title = watchedMovie.name,
                        lastWatchedAt = 1_000L,
                        resumePositionMs = 1_000L,
                        totalDurationMs = 10_000L
                    )
                )
            )
        )

        val repository = createRepository()

        val result = repository.getRecommendations(7L, limit = 5).first()

        assertThat(result.map { it.name }).containsExactly("Galaxy Pursuit", "Quiet Tea").inOrder()
    }

    @Test
    fun `getRelatedContent ranks shared genre ahead of category only matches`() = runTest {
        val targetMovie = movieEntity(
            id = 1L,
            name = "Space Run",
            genre = "Action, Sci-Fi",
            categoryId = 10L,
            rating = 7.2f
        )
        val closeMatch = movieEntity(
            id = 2L,
            name = "Galaxy Pursuit",
            genre = "Sci-Fi Action",
            categoryId = 10L,
            rating = 8.8f
        )
        val categoryOnly = movieEntity(
            id = 3L,
            name = "Harbor Escape",
            genre = "Thriller",
            categoryId = 10L,
            rating = 9.0f
        )

        whenever(preferencesRepository.parentalControlLevel).thenReturn(flowOf(0))
        whenever(preferencesRepository.xtreamBase64TextCompatibility).thenReturn(flowOf(false))
        whenever(movieDao.getCountByCategory(7L, 10L)).thenReturn(flowOf(1))
        whenever(movieDao.getById(targetMovie.id)).thenReturn(
            movieRecord(
                id = targetMovie.id,
                name = targetMovie.name,
                genre = targetMovie.genre.orEmpty(),
                categoryId = targetMovie.categoryId ?: 0L,
                rating = targetMovie.rating
            )
        )
        whenever(movieDao.getByCategoryPreview(7L, 10L, 48)).thenReturn(flowOf(listOf(targetMovie, closeMatch, categoryOnly)))
        whenever(movieDao.getTopRatedPreview(7L, 32)).thenReturn(flowOf(listOf(closeMatch, categoryOnly)))

        val repository = createRepository()

        val result = repository.getRelatedContent(7L, targetMovie.id, limit = 5).first()

        assertThat(result.map { it.name }).containsExactly("Galaxy Pursuit", "Harbor Escape").inOrder()
    }

    @Test
    fun `getMoviesByCategory does not hydrate xtream category when local cache is empty`() = runTest {
        whenever(preferencesRepository.parentalControlLevel).thenReturn(flowOf(0))
        whenever(preferencesRepository.xtreamBase64TextCompatibility).thenReturn(flowOf(false))
        whenever(movieDao.getCountByCategory(7L, 42L)).thenReturn(flowOf(0))
        whenever(movieDao.getByCategory(7L, 42L)).thenReturn(flowOf(emptyList()))
        whenever(movieCategoryHydrationDao.get(7L, 42L)).thenReturn(null)
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
        val repository = createRepository()

        val result = repository.getMoviesByCategory(7L, 42L).first()

        assertThat(result).isEmpty()
        verify(syncManager).prioritizeXtreamIndexCategory(7L, ContentType.MOVIE, 42L)
        verify(movieDao, never()).replaceCategory(eq(7L), eq(42L), any())
        verify(xtreamApiService, never()).getVodStreams(any(), any())
    }

    @Test
    fun `getMoviesByCategory skips legacy xtream empty retry hydration`() = runTest {
        whenever(preferencesRepository.parentalControlLevel).thenReturn(flowOf(0))
        whenever(preferencesRepository.xtreamBase64TextCompatibility).thenReturn(flowOf(false))
        whenever(movieDao.getCountByCategory(7L, 42L)).thenReturn(flowOf(0))
        whenever(movieDao.getByCategory(7L, 42L)).thenReturn(flowOf(emptyList()))
        whenever(movieCategoryHydrationDao.get(7L, 42L)).thenReturn(null)
        whenever(syncMetadataRepository.getMetadata(7L)).thenReturn(
            SyncMetadata(providerId = 7L, movieSyncMode = VodSyncMode.LAZY_BY_CATEGORY)
        )
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
        val repository = createRepository()

        repository.getMoviesByCategory(7L, 42L).first()

        verify(syncManager).prioritizeXtreamIndexCategory(7L, ContentType.MOVIE, 42L)
        verify(movieDao, never()).replaceCategory(eq(7L), eq(42L), any())
        verify(movieCategoryHydrationDao, never()).upsert(any())
        verify(xtreamApiService, never()).getVodStreams(any(), any())
    }

    @Test
    fun `getMoviesByCategory directly hydrates stalker category even when background fetch is running`() = runTest {
        whenever(preferencesRepository.parentalControlLevel).thenReturn(flowOf(0))
        whenever(preferencesRepository.xtreamBase64TextCompatibility).thenReturn(flowOf(false))
        whenever(movieDao.getCountByCategory(7L, 42L)).thenReturn(flowOf(0))
        whenever(movieDao.getByCategory(7L, 42L)).thenReturn(flowOf(emptyList()))
        whenever(movieCategoryHydrationDao.get(7L, 42L)).thenReturn(null)
        whenever(xtreamIndexJobDao.get(7L, ContentType.MOVIE.name)).thenReturn(
            XtreamIndexJobEntity(providerId = 7L, section = ContentType.MOVIE.name, state = "RUNNING")
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
        whenever(stalkerApiService.getVodCategories(any(), any())).thenReturn(
            Result.success(listOf(StalkerCategoryRecord("42", "Action")))
        )
        whenever(stalkerApiService.getVodStreamsPage(any(), any(), anyOrNull(), any())).thenReturn(
            Result.success(StalkerPagedItems(emptyList(), page = 1, totalPages = 1, pageSize = 20))
        )
        val repository = createRepository()

        val result = repository.getMoviesByCategory(7L, 42L).first()

        assertThat(result).isEmpty()
        verify(syncManager, never()).prioritizeStalkerIndexCategory(any(), any(), any())
        verify(stalkerApiService).getVodStreamsPage(any(), any(), anyOrNull(), eq(1))
    }

    @Test
    fun `getMovieDetails uses fresh xtream hydrated cache without refetching`() = runTest {
        val hydratedAt = System.currentTimeMillis()
        whenever(movieDao.getById(99L)).thenReturn(
            movieRecord(
                id = 99L,
                name = "Cached Movie",
                genre = "Drama",
                categoryId = 42L,
                rating = 7.1f
            ).copy(
                cacheState = "DETAIL_HYDRATED",
                detailHydratedAt = hydratedAt
            )
        )
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

        val result = createRepository().getMovieDetails(7L, 99L)

        assertThat(result.getOrNull()?.name).isEqualTo("Cached Movie")
        verify(xtreamApiService, never()).getVodInfo(any(), any())
        verify(xtreamContentIndexDao, never()).markDetailHydrated(any(), any(), any(), any(), anyOrNull(), any())
    }

    @Test
    fun `getMovieDetails with duplicate handling uses narrow tmdb candidates instead of full provider scan`() = runTest {
        val hydratedAt = System.currentTimeMillis()
        val rawMovie = movieRecord(
            id = 99L,
            name = "Cached Movie HD",
            genre = "Drama",
            categoryId = 42L,
            rating = 7.1f
        ).copy(
            tmdbId = 777L,
            year = "2024",
            cacheState = "DETAIL_HYDRATED",
            detailHydratedAt = hydratedAt
        )
        val alternateMovie = movieRecord(
            id = 100L,
            name = "Cached Movie 4K",
            genre = "Drama",
            categoryId = 42L,
            rating = 7.1f
        ).copy(
            tmdbId = 777L,
            year = "2024"
        )
        whenever(movieDao.getById(99L)).thenReturn(rawMovie)
        whenever(movieDao.getByProviderAndTmdbIdSync(7L, 777L)).thenReturn(listOf(rawMovie, alternateMovie))
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

        val result = createRepository(
            duplicateHandlingMode = VodDuplicateHandlingMode.SMART,
            variantPreferenceMode = VodVariantPreferenceMode.BEST_QUALITY
        ).getMovieDetails(7L, 99L)

        assertThat(result.getOrNull()?.selectedVariantId).isEqualTo(99L)
        assertThat(result.getOrNull()?.variants?.map { it.rawMovieId }).containsExactly(99L, 100L)
        verify(movieDao).getByProviderAndTmdbIdSync(7L, 777L)
        verify(movieDao, never()).getByProvider(7L)
        verify(movieDao, never()).getByProviderSync(7L)
    }

    @Test
    fun `getMovieDetails reuses known presentation hint instead of loading duplicate candidates`() = runTest {
        val hydratedAt = System.currentTimeMillis()
        val rawMovie = movieRecord(
            id = 99L,
            name = "Cached Movie HD",
            genre = "Drama",
            categoryId = 42L,
            rating = 7.1f
        ).copy(
            tmdbId = 777L,
            year = "2024",
            cacheState = "DETAIL_HYDRATED",
            detailHydratedAt = hydratedAt
        )
        whenever(movieDao.getById(99L)).thenReturn(rawMovie)
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

        val result = createRepository(
            duplicateHandlingMode = VodDuplicateHandlingMode.SMART,
            variantPreferenceMode = VodVariantPreferenceMode.BEST_QUALITY
        ).getMovieDetails(
            7L,
            99L,
            MovieDetailPresentationHint(
                providerId = 7L,
                logicalGroupId = "movie:7:tmdb:777",
                variants = listOf(
                    VodMovieVariant(
                        rawMovieId = 100L,
                        name = "Cached Movie 4K",
                        streamUrl = "https://example.com/100.m3u8",
                        streamId = 100L,
                        containerExtension = null,
                        releaseDate = "2024-01-01",
                        year = "2024",
                        durationSeconds = 0,
                        rating = 7.1f,
                        addedAt = 0L,
                        qualityScore = 2160,
                        recencyScore = 20240101L,
                        reliabilityScore = 0,
                        label = "4K 2024"
                    ),
                    VodMovieVariant(
                        rawMovieId = 99L,
                        name = "Cached Movie HD",
                        streamUrl = "https://example.com/99.m3u8",
                        streamId = 99L,
                        containerExtension = null,
                        releaseDate = "2024-01-01",
                        year = "2024",
                        durationSeconds = 0,
                        rating = 7.1f,
                        addedAt = 0L,
                        qualityScore = 1080,
                        recencyScore = 20240101L,
                        reliabilityScore = 0,
                        label = "1080p 2024"
                    )
                ),
                duplicateConfidence = VodDuplicateConfidence.EXACT
            )
        )

        assertThat(result.getOrNull()?.selectedVariantId).isEqualTo(99L)
        assertThat(result.getOrNull()?.variants?.map { it.rawMovieId }).containsExactly(100L, 99L).inOrder()
        verify(movieDao, never()).getByProviderAndTmdbIdSync(any(), any())
        verify(movieDao, never()).getByProviderAndYearSync(any(), any())
        verify(movieDao, never()).getByProviderAndReleaseYearPrefixSync(any(), any())
    }

    @Test
    fun `getMoviesByCategory lazily hydrates stalker category when local cache is empty`() = runTest {
        whenever(preferencesRepository.parentalControlLevel).thenReturn(flowOf(0))
        whenever(movieDao.getCountByCategory(7L, 42L)).thenReturn(flowOf(0))
        whenever(movieDao.getByCategory(7L, 42L)).thenReturn(flowOf(emptyList()))
        whenever(movieCategoryHydrationDao.get(7L, 42L)).thenReturn(null)
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
        whenever(stalkerApiService.getVodCategories(any(), any())).thenReturn(
            Result.success(listOf(StalkerCategoryRecord(id = "42", name = "Action")))
        )
        whenever(stalkerApiService.getVodStreamsPage(any(), any(), anyOrNull(), eq(1))).thenReturn(
            Result.success(
                StalkerPagedItems(
                    items = listOf(
                        StalkerItemRecord(
                            id = "101",
                            name = "Movie",
                            categoryId = "42",
                            cmd = "ffmpeg http://example.com/movie.mp4",
                            containerExtension = "mp4"
                        )
                    ),
                    page = 1,
                    totalPages = 1,
                    pageSize = 1
                )
            )
        )

        val repository = createRepository()

        repository.getMoviesByCategory(7L, 42L).first()

        verify(movieDao).upsertCategoryPage(eq(7L), any())
        verify(movieDao, never()).replaceCategory(eq(7L), eq(42L), any())
    }

    @Test
    fun `stalker movie preview loads only first page`() = runTest {
        whenever(preferencesRepository.parentalControlLevel).thenReturn(flowOf(0))
        whenever(movieDao.getCountByCategory(7L, 42L)).thenReturn(flowOf(0), flowOf(18))
        whenever(movieDao.getByCategoryPreview(7L, 42L, 18)).thenReturn(flowOf(emptyList()))
        whenever(movieCategoryHydrationDao.get(7L, 42L)).thenReturn(null)
        whenever(categoryDao.getByProviderAndType(7L, ContentType.MOVIE.name)).thenReturn(
            flowOf(listOf(com.streamvault.data.local.entity.CategoryEntity(providerId = 7L, categoryId = 42L, name = "Action", type = ContentType.MOVIE)))
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
        whenever(stalkerApiService.getVodCategories(any(), any())).thenReturn(
            Result.success(listOf(StalkerCategoryRecord(id = "42", name = "Action")))
        )
        whenever(stalkerApiService.getVodStreamsPage(any(), any(), anyOrNull(), eq(1))).thenReturn(
            Result.success(
                StalkerPagedItems(
                    items = (1..18).map { index ->
                        StalkerItemRecord(id = "10$index", name = "Movie $index", categoryId = "42")
                    },
                    page = 1,
                    totalPages = 2,
                    pageSize = 50
                )
            )
        )

        val repository = createRepository()

        repository.getCategoryPreviewRows(7L, listOf(42L), 18).first()

        verify(stalkerApiService, timeout(1_000)).getVodStreamsPage(any(), any(), anyOrNull(), eq(1))
        verify(stalkerApiService, never()).getVodStreamsPage(any(), any(), anyOrNull(), eq(2))
    }

    @Test
    fun `stalker preview keeps wildcard-only movie category`() = runTest {
        val wildcardId = stalkerStableHashId(7L, ContentType.MOVIE, "*")
        whenever(preferencesRepository.parentalControlLevel).thenReturn(flowOf(0))
        whenever(categoryDao.getByProviderAndType(7L, ContentType.MOVIE.name)).thenReturn(
            flowOf(
                listOf(
                    CategoryEntity(
                        providerId = 7L,
                        categoryId = wildcardId,
                        name = "*",
                        type = ContentType.MOVIE
                    )
                )
            )
        )
        whenever(movieDao.getCountByCategory(7L, wildcardId)).thenReturn(flowOf(18))
        whenever(movieCategoryHydrationDao.get(7L, wildcardId)).thenReturn(null)
        whenever(movieDao.getByCategoryPreview(7L, wildcardId, 18)).thenReturn(
            flowOf(
                listOf(
                    MovieEntity(
                        id = 1L,
                        streamId = 1L,
                        name = "Movie",
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
    fun `stalker preview hydration survives preview flow cancellation`() = runTest {
        val requestStarted = CompletableDeferred<Unit>()
        val releaseRequest = CompletableDeferred<Unit>()
        whenever(preferencesRepository.parentalControlLevel).thenReturn(flowOf(0))
        whenever(movieDao.getCountByCategory(7L, 42L)).thenReturn(flowOf(0), flowOf(18))
        whenever(movieDao.getByCategoryPreview(7L, 42L, 18)).thenReturn(flowOf(emptyList()))
        whenever(movieCategoryHydrationDao.get(7L, 42L)).thenReturn(null)
        whenever(categoryDao.getByProviderAndType(7L, ContentType.MOVIE.name)).thenReturn(
            flowOf(
                listOf(
                    com.streamvault.data.local.entity.CategoryEntity(
                        providerId = 7L,
                        categoryId = 42L,
                        name = "Action",
                        type = ContentType.MOVIE
                    )
                )
            )
        )
        stubProvider(stalkerProvider())
        whenever(stalkerApiService.authenticate(any())).thenReturn(stalkerSessionResult())
        whenever(stalkerApiService.getVodCategories(any(), any())).thenReturn(
            Result.success(listOf(StalkerCategoryRecord(id = "42", name = "Action")))
        )
        whenever(stalkerApiService.getVodStreamsPage(any(), any(), anyOrNull(), eq(1))).doSuspendableAnswer {
            requestStarted.complete(Unit)
            releaseRequest.await()
            Result.success(stalkerMoviePage(page = 1, itemCount = 18, totalPages = 1))
        }

        createRepository().getCategoryPreviewRows(7L, listOf(42L), 18).first()
        requestStarted.await()
        releaseRequest.complete(Unit)

        verify(movieDao, timeout(1_000)).upsertCategoryPage(eq(7L), any())
    }

    @Test
    fun `stalker movie hydration restarts at page one when successful pages produced no category rows`() = runTest {
        whenever(preferencesRepository.parentalControlLevel).thenReturn(flowOf(0))
        whenever(movieDao.getCountByCategory(7L, 42L)).thenReturn(flowOf(0), flowOf(18))
        whenever(movieDao.getByCategoryPreview(7L, 42L, 18)).thenReturn(flowOf(emptyList()))
        whenever(movieCategoryHydrationDao.get(7L, 42L)).thenReturn(
            MovieCategoryHydrationEntity(
                providerId = 7L,
                categoryId = 42L,
                itemCount = 0,
                lastStatus = "TRUNCATED",
                lastLoadedPage = 2,
                lastAttemptedPage = 2,
                lastSuccessfulPage = 2,
                totalPages = 10,
                pageSize = 14
            )
        )
        whenever(categoryDao.getByProviderAndType(7L, ContentType.MOVIE.name)).thenReturn(
            flowOf(
                listOf(
                    com.streamvault.data.local.entity.CategoryEntity(
                        providerId = 7L,
                        categoryId = 42L,
                        name = "Action",
                        type = ContentType.MOVIE
                    )
                )
            )
        )
        stubProvider(stalkerProvider())
        whenever(stalkerApiService.authenticate(any())).thenReturn(stalkerSessionResult())
        whenever(stalkerApiService.getVodCategories(any(), any())).thenReturn(
            Result.success(listOf(StalkerCategoryRecord(id = "42", name = "Action")))
        )
        whenever(stalkerApiService.getVodStreamsPage(any(), any(), anyOrNull(), eq(1))).thenReturn(
            Result.success(stalkerMoviePage(page = 1, itemCount = 18))
        )

        createRepository().getCategoryPreviewRows(7L, listOf(42L), 18).first()

        verify(stalkerApiService, timeout(1_000)).getVodStreamsPage(any(), any(), anyOrNull(), eq(1))
        verify(stalkerApiService, never()).getVodStreamsPage(any(), any(), anyOrNull(), eq(3))
    }

    @Test
    fun `opening stalker movie category fills at least forty items across natural pages`() = runTest {
        whenever(preferencesRepository.parentalControlLevel).thenReturn(flowOf(0))
        whenever(movieDao.getCountByCategory(7L, 42L)).thenReturn(flowOf(0), flowOf(14), flowOf(28), flowOf(42))
        whenever(movieDao.getByCategoryPage(7L, 42L, 60, 0)).thenReturn(flowOf(emptyList()))
        whenever(movieCategoryHydrationDao.get(7L, 42L)).thenReturn(null)
        stubProvider(stalkerProvider())
        whenever(stalkerApiService.authenticate(any())).thenReturn(stalkerSessionResult())
        whenever(stalkerApiService.getVodCategories(any(), any())).thenReturn(
            Result.success(listOf(StalkerCategoryRecord(id = "42", name = "Action")))
        )
        (1..3).forEach { page ->
            whenever(stalkerApiService.getVodStreamsPage(any(), any(), anyOrNull(), eq(page))).thenReturn(
                Result.success(stalkerMoviePage(page = page, itemCount = 14))
            )
        }

        createRepository().getMoviesByCategoryPage(7L, 42L, limit = 60, offset = 0).first()

        (1..3).forEach { page ->
            verify(stalkerApiService).getVodStreamsPage(any(), any(), anyOrNull(), eq(page))
        }
        verify(stalkerApiService, never()).getVodStreamsPage(any(), any(), anyOrNull(), eq(4))
    }

    @Test
    fun `complete request hydrates every advertised stalker movie page before returning`() = runTest {
        whenever(preferencesRepository.parentalControlLevel).thenReturn(flowOf(0))
        whenever(preferencesRepository.vodCategoryLoadMode).thenReturn(flowOf(VodCategoryLoadMode.PAGED))
        whenever(movieDao.getCountByCategory(7L, 42L)).thenReturn(
            flowOf(0),
            flowOf(14),
            flowOf(28),
            flowOf(42)
        )
        whenever(movieCategoryHydrationDao.get(7L, 42L)).thenReturn(null)
        stubProvider(stalkerProvider())
        whenever(stalkerApiService.authenticate(any())).thenReturn(stalkerSessionResult())
        whenever(stalkerApiService.getVodCategories(any(), any())).thenReturn(
            Result.success(listOf(StalkerCategoryRecord(id = "42", name = "Action")))
        )
        (1..3).forEach { page ->
            whenever(stalkerApiService.getVodStreamsPage(any(), any(), anyOrNull(), eq(page))).thenReturn(
                Result.success(stalkerMoviePage(page = page, itemCount = 14, totalPages = 3))
            )
        }
        whenever(movieDao.getFreshByCategoryCursorPage(7L, 42L, 40)).thenReturn(emptyList())
        whenever(favoriteDao.getAllByType(7L, ContentType.MOVIE.name)).thenReturn(flowOf(emptyList()))

        createRepository().requestCategoryHydration(
            7L,
            42L,
            VodCategoryHydrationRequest.COMPLETE
        )

        (1..3).forEach { page ->
            verify(stalkerApiService).getVodStreamsPage(any(), any(), anyOrNull(), eq(page))
        }
        verify(stalkerApiService, never()).getVodStreamsPage(any(), any(), anyOrNull(), eq(4))
    }

    @Test
    fun `scrolling cached stalker movie category appends exactly one natural page`() = runTest {
        whenever(preferencesRepository.parentalControlLevel).thenReturn(flowOf(0))
        whenever(movieDao.getCountByCategory(7L, 42L)).thenReturn(flowOf(42), flowOf(56))
        whenever(movieDao.getByCategoryPage(7L, 42L, 60, 60)).thenReturn(flowOf(emptyList()))
        whenever(movieCategoryHydrationDao.get(7L, 42L)).thenReturn(
            MovieCategoryHydrationEntity(
                providerId = 7L,
                categoryId = 42L,
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
        whenever(stalkerApiService.getVodCategories(any(), any())).thenReturn(
            Result.success(listOf(StalkerCategoryRecord(id = "42", name = "Action")))
        )
        whenever(stalkerApiService.getVodStreamsPage(any(), any(), anyOrNull(), eq(4))).thenReturn(
            Result.success(stalkerMoviePage(page = 4, itemCount = 14))
        )

        createRepository().getMoviesByCategoryPage(7L, 42L, limit = 60, offset = 60).first()

        verify(stalkerApiService).getVodStreamsPage(any(), any(), anyOrNull(), eq(4))
        verify(stalkerApiService, never()).getVodStreamsPage(any(), any(), anyOrNull(), eq(5))
    }

    @Test
    fun `stalker movie category hydration preserves requested category when portal omits item category fields`() = runTest {
        val actionCategoryId = ("7/${ContentType.MOVIE.name}/42".hashCode().toLong() and 0x7fff_ffffL)
            .coerceAtLeast(1L)
        whenever(
            stalkerRemoteIdentityResolver.resolveCategories(eq(7L), eq(ContentType.MOVIE), any())
        ).thenReturn(mapOf("42" to actionCategoryId))
        whenever(preferencesRepository.parentalControlLevel).thenReturn(flowOf(0))
        whenever(movieDao.getCountByCategory(7L, actionCategoryId)).thenReturn(flowOf(0), flowOf(1))
        whenever(movieDao.getByCategory(7L, actionCategoryId)).thenReturn(flowOf(emptyList()))
        whenever(movieCategoryHydrationDao.get(7L, actionCategoryId)).thenReturn(null)
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
        whenever(stalkerApiService.getVodCategories(any(), any())).thenReturn(
            Result.success(listOf(StalkerCategoryRecord(id = "42", name = "Action")))
        )
        whenever(stalkerApiService.getVodStreamsPage(any(), any(), anyOrNull(), eq(1))).thenReturn(
            Result.success(
                StalkerPagedItems(
                    items = listOf(
                        StalkerItemRecord(
                            id = "101",
                            name = "Movie",
                            cmd = "ffmpeg http://example.com/movie.mp4",
                            containerExtension = "mp4"
                        )
                    ),
                    page = 1,
                    totalPages = 1,
                    pageSize = 1
                )
            )
        )

        val repository = createRepository()

        repository.getMoviesByCategory(7L, actionCategoryId).first()

        val movieCaptor = argumentCaptor<List<MovieEntity>>()
        verify(movieDao).upsertCategoryPage(eq(7L), movieCaptor.capture())
        assertThat(movieCaptor.firstValue).hasSize(1)
        assertThat(movieCaptor.firstValue.single().categoryId).isEqualTo(actionCategoryId)
        assertThat(movieCaptor.firstValue.single().categoryName).isEqualTo("Action")
    }

    @Test
    fun `getMovieDetails returns local data for stalker without full remote fetch`() = runTest {
        whenever(movieDao.getById(101L)).thenReturn(
            movieRecord(
                id = 101L,
                name = "Stored Movie",
                genre = "Action",
                categoryId = 42L,
                rating = 8.5f
            )
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

        val repository = createRepository()

        val result = repository.getMovieDetails(7L, 101L)

        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat((result as Result.Success).data.name).isEqualTo("Stored Movie")
        verify(stalkerApiService, never()).authenticate(any())
    }

    @Test
    fun `searchMovies returns empty list when sqlite throws for malformed fts query`() = runTest {
        whenever(preferencesRepository.parentalControlLevel).thenReturn(flowOf(0))
        whenever(movieDao.search(eq(7L), any(), any())).thenReturn(
            flow { throw SQLiteException("malformed MATCH expression") }
        )
        whenever(movieDao.searchFallback(eq(7L), any(), any())).thenReturn(
            flowOf(
                listOf(
                    movieEntity(
                        id = 101L,
                        name = "Matrix Reloaded",
                        genre = "Action",
                        categoryId = 42L,
                        rating = 8.0f
                    )
                )
            )
        )
        whenever(favoriteDao.getAllByType(7L, ContentType.MOVIE.name)).thenReturn(flowOf(emptyList()))

        val repository = createRepository()

        val result = repository.searchMovies(7L, "matrix").first()

        assertThat(result.map { it.name }).containsExactly("Matrix Reloaded")
    }

    @Test
    fun `searchMovies returns empty without like fallback when fts has no rows`() = runTest {
        whenever(preferencesRepository.parentalControlLevel).thenReturn(flowOf(0))
        whenever(movieDao.search(eq(7L), any(), any())).thenReturn(flowOf(emptyList()))
        whenever(favoriteDao.getAllByType(7L, ContentType.MOVIE.name)).thenReturn(flowOf(emptyList()))

        val repository = createRepository()

        val result = repository.searchMovies(7L, "matrix").first()

        assertThat(result).isEmpty()
        verify(movieDao, never()).searchFallback(eq(7L), any(), any())
    }

    @Test
    fun `searchMovies does not run like fallback when fts returns rows`() = runTest {
        whenever(preferencesRepository.parentalControlLevel).thenReturn(flowOf(0))
        whenever(movieDao.search(eq(7L), any(), any())).thenReturn(
            flowOf(
                listOf(
                    movieEntity(
                        id = 103L,
                        name = "Matrix",
                        genre = "Action",
                        categoryId = 42L,
                        rating = 9.0f
                    )
                )
            )
        )
        whenever(favoriteDao.getAllByType(7L, ContentType.MOVIE.name)).thenReturn(flowOf(emptyList()))

        val repository = createRepository()

        val result = repository.searchMovies(7L, "matrix").first()

        assertThat(result.map { it.name }).containsExactly("Matrix")
        verify(movieDao, never()).searchFallback(eq(7L), any(), any())
    }

    @Test
    fun `getMoviesByCategory show all preserves raw movie variants`() = runTest {
        val hdVersion = movieEntity(
            id = 101L,
            name = "Arrival HD",
            genre = "Sci-Fi",
            categoryId = 42L,
            rating = 7.9f
        ).copy(releaseDate = "2016-11-11", year = "2016")
        val fhdVersion = movieEntity(
            id = 102L,
            name = "Arrival FHD",
            genre = "Sci-Fi",
            categoryId = 42L,
            rating = 7.9f
        ).copy(releaseDate = "2016-11-11", year = "2016")
        whenever(preferencesRepository.parentalControlLevel).thenReturn(flowOf(0))
        whenever(preferencesRepository.xtreamBase64TextCompatibility).thenReturn(flowOf(false))
        whenever(movieDao.getCountByCategory(7L, 42L)).thenReturn(flowOf(2))
        whenever(movieDao.getByCategory(7L, 42L)).thenReturn(flowOf(listOf(hdVersion, fhdVersion)))

        val result = createRepository().getMoviesByCategory(7L, 42L).first()

        assertThat(result.map { it.id }).containsExactly(101L, 102L).inOrder()
        assertThat(result.all { it.variants.isEmpty() }).isTrue()
    }

    @Test
    fun `getMoviesByCategory smart groups same movie variants and prefers latest`() = runTest {
        val hdVersion = movieEntity(
            id = 101L,
            name = "Arrival HD",
            genre = "Sci-Fi",
            categoryId = 42L,
            rating = 7.9f
        ).copy(releaseDate = "2016-11-11", year = "2016", addedAt = 1_000L)
        val fhdVersion = movieEntity(
            id = 102L,
            name = "Arrival FHD",
            genre = "Sci-Fi",
            categoryId = 42L,
            rating = 7.9f
        ).copy(releaseDate = "2016-11-11", year = "2016", addedAt = 2_000L)
        val remake = movieEntity(
            id = 103L,
            name = "Arrival",
            genre = "Sci-Fi",
            categoryId = 42L,
            rating = 6.5f
        ).copy(releaseDate = "2024-01-01", year = "2024", addedAt = 3_000L)
        whenever(preferencesRepository.parentalControlLevel).thenReturn(flowOf(0))
        whenever(preferencesRepository.xtreamBase64TextCompatibility).thenReturn(flowOf(false))
        whenever(movieDao.getCountByCategory(7L, 42L)).thenReturn(flowOf(3))
        whenever(movieDao.getByCategory(7L, 42L)).thenReturn(flowOf(listOf(remake, fhdVersion, hdVersion)))

        val result = createRepository(
            duplicateHandlingMode = VodDuplicateHandlingMode.SMART,
            variantPreferenceMode = VodVariantPreferenceMode.FORCE_LATEST
        ).getMoviesByCategory(7L, 42L).first()

        assertThat(result.map { it.id }).containsExactly(103L, 102L).inOrder()
        val groupedArrival = result.single { it.id == 102L }
        assertThat(groupedArrival.variants.map { it.rawMovieId }).containsExactly(102L, 101L).inOrder()
        assertThat(groupedArrival.selectedVariantId).isEqualTo(102L)
    }

    @Test
    fun `getMoviesByIds stays raw when vod grouping is enabled`() = runTest {
        val hdVersion = movieEntity(
            id = 101L,
            name = "Arrival HD",
            genre = "Sci-Fi",
            categoryId = 42L,
            rating = 7.9f
        ).copy(releaseDate = "2016-11-11", year = "2016")
        val fhdVersion = movieEntity(
            id = 102L,
            name = "Arrival FHD",
            genre = "Sci-Fi",
            categoryId = 42L,
            rating = 7.9f
        ).copy(releaseDate = "2016-11-11", year = "2016")
        whenever(movieDao.getByIds(listOf(101L, 102L))).thenReturn(flowOf(listOf(hdVersion, fhdVersion)))

        val result = createRepository(
            duplicateHandlingMode = VodDuplicateHandlingMode.SMART,
            variantPreferenceMode = VodVariantPreferenceMode.FORCE_LATEST
        ).getMoviesByIds(listOf(101L, 102L)).first()

        assertThat(result.map { it.id }).containsExactly(101L, 102L).inOrder()
        assertThat(result.all { it.variants.isEmpty() }).isTrue()
    }

    @Test
    fun `browseMovies search uses bounded page query`() = runTest {
        whenever(preferencesRepository.parentalControlLevel).thenReturn(flowOf(0))
        whenever(movieDao.searchPage(eq(7L), any(), any(), any(), any(), any(), any())).thenReturn(
            listOf(
                movieEntity(id = 101L, name = "Matrix", genre = "Action", categoryId = 42L, rating = 9.0f),
                movieEntity(id = 102L, name = "Matrix Reloaded", genre = "Action", categoryId = 42L, rating = 7.5f)
            )
        )
        whenever(favoriteDao.getAllByType(7L, ContentType.MOVIE.name)).thenReturn(flowOf(emptyList()))

        val result = createRepository().browseMovies(
            LibraryBrowseQuery(
                providerId = 7L,
                searchQuery = "matrix",
                offset = 0,
                limit = 1
            )
        ).first()

        assertThat(result.totalCount).isEqualTo(2)
        assertThat(result.items.map { it.name }).containsExactly("Matrix")
        verify(movieDao, times(1)).searchPage(eq(7L), any(), any(), any(), any(), eq(2), eq(0))
        verify(movieDao, never()).search(eq(7L), any(), any())
        verify(movieDao, never()).searchFallback(eq(7L), any(), any())
    }

    @Test
    fun `browseMovies top rated excludes unrated entries and uses filtered total count`() = runTest {
        whenever(preferencesRepository.parentalControlLevel).thenReturn(flowOf(0))
        whenever(preferencesRepository.xtreamBase64TextCompatibility).thenReturn(flowOf(false))
        whenever(movieDao.getTopRatedCountByProvider(7L)).thenReturn(flowOf(1))
        whenever(movieDao.getTopRatedPreview(7L, 100)).thenReturn(
            flowOf(
                listOf(
                    movieEntity(id = 1L, name = "Rated Movie", genre = "Drama", categoryId = 10L, rating = 8.5f),
                    movieEntity(id = 2L, name = "Unrated Movie", genre = "Drama", categoryId = 10L, rating = 0f)
                )
            )
        )
        whenever(favoriteDao.getAllByType(7L, ContentType.MOVIE.name)).thenReturn(flowOf(emptyList()))
        whenever(playbackHistoryDao.getByProvider(7L)).thenReturn(flowOf(emptyList()))

        val repository = createRepository()

        val result = repository.browseMovies(
            LibraryBrowseQuery(
                providerId = 7L,
                sortBy = LibrarySortBy.RATING,
                filterBy = LibraryFilterBy(LibraryFilterType.TOP_RATED),
                offset = 0,
                limit = 20
            )
        ).first()

        assertThat(result.totalCount).isEqualTo(1)
        assertThat(result.items.map { it.name }).containsExactly("Rated Movie")
    }

    @Test
    fun `browseMovies release uses release ordering instead of added order`() = runTest {
        whenever(preferencesRepository.parentalControlLevel).thenReturn(flowOf(0))
        whenever(preferencesRepository.xtreamBase64TextCompatibility).thenReturn(flowOf(false))
        whenever(movieDao.getCount(7L)).thenReturn(flowOf(2))
        whenever(movieDao.getReleasedPreview(7L, 100)).thenReturn(
            flowOf(
                listOf(
                    MovieBrowseEntity(
                        id = 1L,
                        streamId = 1L,
                        name = "Added Later",
                        providerId = 7L,
                        categoryId = 10L,
                        releaseDate = "2020-01-01",
                        addedAt = 20_000L,
                        streamUrl = "https://example.com/1.m3u8"
                    ),
                    MovieBrowseEntity(
                        id = 2L,
                        streamId = 2L,
                        name = "Released Later",
                        providerId = 7L,
                        categoryId = 10L,
                        releaseDate = "2024-01-01",
                        addedAt = 10_000L,
                        streamUrl = "https://example.com/2.m3u8"
                    )
                )
            )
        )
        whenever(favoriteDao.getAllByType(7L, ContentType.MOVIE.name)).thenReturn(flowOf(emptyList()))
        whenever(playbackHistoryDao.getByProvider(7L)).thenReturn(flowOf(emptyList()))

        val repository = createRepository()

        val result = repository.browseMovies(
            LibraryBrowseQuery(
                providerId = 7L,
                sortBy = LibrarySortBy.RELEASE,
                offset = 0,
                limit = 20
            )
        ).first()

        assertThat(result.totalCount).isEqualTo(2)
        assertThat(result.items.map { it.name }).containsExactly("Released Later", "Added Later").inOrder()
        verify(movieDao).getReleasedPreview(7L, 100)
        verify(movieDao, never()).getFreshPreview(7L, 100)
    }

    @Test
    fun `browseMovies library order uses added at freshness instead of title order`() = runTest {
        whenever(preferencesRepository.parentalControlLevel).thenReturn(flowOf(0))
        whenever(preferencesRepository.xtreamBase64TextCompatibility).thenReturn(flowOf(false))
        whenever(movieDao.getCount(7L)).thenReturn(flowOf(2))
        whenever(movieDao.getFreshCursorPage(7L, 40)).thenReturn(
            listOf(
                MovieBrowseEntity(id = 2L, streamId = 102L, name = "Zulu Movie", providerId = 7L, addedAt = 20_000L),
                MovieBrowseEntity(id = 1L, streamId = 101L, name = "Alpha Movie", providerId = 7L, addedAt = 10_000L)
            )
        )
        whenever(favoriteDao.getAllByType(7L, ContentType.MOVIE.name)).thenReturn(flowOf(emptyList()))

        val repository = createRepository()

        val result = repository.browseMovies(
            LibraryBrowseQuery(
                providerId = 7L,
                sortBy = LibrarySortBy.LIBRARY,
                offset = 0,
                limit = 20
            )
        ).first()

        assertThat(result.totalCount).isEqualTo(2)
        assertThat(result.items.map { it.name }).containsExactly("Zulu Movie", "Alpha Movie").inOrder()
        verify(movieDao).getFreshCursorPage(7L, 40)
        verify(movieDao, never()).getByProviderCursorPage(any(), any())
    }

    @Test
    fun `browseMovies groups duplicates while using the cursor window`() = runTest {
        whenever(preferencesRepository.parentalControlLevel).thenReturn(flowOf(0))
        whenever(movieDao.getCount(7L)).thenReturn(flowOf(2))
        whenever(movieDao.getFreshCursorPage(7L, 40)).thenReturn(
            listOf(
                movieEntity(id = 101L, name = "Arrival HD", genre = "Sci-Fi", categoryId = 42L, rating = 7.9f)
                    .copy(year = "2016", releaseDate = "2016-11-11", addedAt = 20L),
                movieEntity(id = 102L, name = "Arrival 4K", genre = "Sci-Fi", categoryId = 42L, rating = 7.9f)
                    .copy(year = "2016", releaseDate = "2016-11-11", addedAt = 10L)
            )
        )
        whenever(favoriteDao.getAllByType(7L, ContentType.MOVIE.name)).thenReturn(flowOf(emptyList()))

        val result = createRepository(
            duplicateHandlingMode = VodDuplicateHandlingMode.SMART,
            variantPreferenceMode = VodVariantPreferenceMode.BEST_QUALITY
        ).browseMovies(
            LibraryBrowseQuery(
                providerId = 7L,
                sortBy = LibrarySortBy.LIBRARY,
                offset = 0,
                limit = 20
            )
        ).first()

        assertThat(result.totalCount).isEqualTo(1)
        assertThat(result.items).hasSize(1)
        assertThat(result.items.single().selectedVariantId).isEqualTo(102L)
        verify(movieDao).getFreshCursorPage(7L, 40)
        verify(movieDao, never()).getByProviderPage(any(), any(), any())
    }

    private fun createRepository(
        duplicateHandlingMode: VodDuplicateHandlingMode = VodDuplicateHandlingMode.SHOW_ALL,
        variantPreferenceMode: VodVariantPreferenceMode = VodVariantPreferenceMode.BALANCED,
        preferredVariants: Map<String, Long> = emptyMap(),
        variantObservations: Map<Long, VodVariantObservation> = emptyMap()
    ): MovieRepositoryImpl {
        whenever(preferencesRepository.vodDuplicateHandlingMode).thenReturn(flowOf(duplicateHandlingMode))
        whenever(preferencesRepository.vodVariantPreferenceMode).thenReturn(flowOf(variantPreferenceMode))
        whenever(preferencesRepository.vodVariantSelections).thenReturn(flowOf(preferredVariants))
        whenever(preferencesRepository.vodVariantObservations).thenReturn(flowOf(variantObservations))
        return MovieRepositoryImpl(
            movieDao = movieDao,
            categoryDao = categoryDao,
            providerDao = providerDao,
            preferencesRepository = preferencesRepository,
            favoriteDao = favoriteDao,
            playbackHistoryDao = playbackHistoryDao,
            playbackHistoryRepository = playbackHistoryRepository,
            xtreamStreamUrlResolver = xtreamStreamUrlResolver,
            movieCategoryHydrationDao = movieCategoryHydrationDao,
            syncMetadataRepository = syncMetadataRepository,
            xtreamContentIndexDao = xtreamContentIndexDao,
            xtreamIndexJobDao = xtreamIndexJobDao,
            syncManager = syncManager,
            transactionRunner = transactionRunner,
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

    private fun stalkerMoviePage(page: Int, itemCount: Int, totalPages: Int = 10): StalkerPagedItems =
        StalkerPagedItems(
            items = (1..itemCount).map { index ->
                StalkerItemRecord(
                    id = "${page}00$index",
                    name = "Movie $page-$index",
                    categoryId = "42",
                    cmd = "ffmpeg http://example.com/movie-$page-$index.mp4",
                    containerExtension = "mp4"
                )
            },
            page = page,
            totalPages = totalPages,
            pageSize = itemCount
        )

    private fun movieEntity(
        id: Long,
        name: String,
        genre: String,
        categoryId: Long,
        rating: Float
    ) = MovieBrowseEntity(
        id = id,
        streamId = id,
        name = name,
        genre = genre,
        categoryId = categoryId,
        providerId = 7L,
        rating = rating,
        streamUrl = "https://example.com/$id.m3u8"
    )

    private fun movieRecord(
        id: Long,
        name: String,
        genre: String,
        categoryId: Long,
        rating: Float
    ) = MovieEntity(
        id = id,
        streamId = id,
        name = name,
        genre = genre,
        categoryId = categoryId,
        providerId = 7L,
        rating = rating,
        streamUrl = "https://example.com/$id.m3u8"
    )
}
