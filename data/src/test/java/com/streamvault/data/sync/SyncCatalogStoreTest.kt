package com.streamvault.data.sync

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.DatabaseTransactionRunner
import com.streamvault.data.local.dao.CatalogSyncDao
import com.streamvault.data.local.dao.CategoryDao
import com.streamvault.data.local.dao.ChannelDao
import com.streamvault.data.local.dao.MovieDao
import com.streamvault.data.local.dao.SeriesDao
import com.streamvault.data.local.dao.TmdbIdentityDao
import com.streamvault.data.local.entity.CategoryEntity
import com.streamvault.data.local.entity.CategoryImportStageEntity
import com.streamvault.data.local.entity.ChannelEntity
import com.streamvault.data.local.entity.ChannelImportStageEntity
import com.streamvault.data.local.entity.MovieEntity
import com.streamvault.data.local.entity.MovieImportStageEntity
import com.streamvault.data.local.entity.SeriesEntity
import com.streamvault.data.local.entity.SeriesImportStageEntity
import com.streamvault.data.local.dao.ChannelStageCategorySummary
import com.streamvault.domain.model.ContentType
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class SyncCatalogStoreTest {

    private val channelDao: ChannelDao = mock()
    private val movieDao: MovieDao = mock()
    private val seriesDao: SeriesDao = mock()
    private val categoryDao: CategoryDao = mock()
    private val catalogSyncDao: CatalogSyncDao = mock()
    private val tmdbIdentityDao: TmdbIdentityDao = mock()
    private val defaultTransactionRunner = TrackingTransactionRunner()

    private fun store(
        sizeLimits: CatalogSizeLimits = CatalogSizeLimits(),
        transactionRunner: DatabaseTransactionRunner = defaultTransactionRunner
    ) = SyncCatalogStore(
        channelDao = channelDao,
        movieDao = movieDao,
        seriesDao = seriesDao,
        categoryDao = categoryDao,
        catalogSyncDao = catalogSyncDao,
        tmdbIdentityDao = tmdbIdentityDao,
        transactionRunner = transactionRunner,
        sizeLimits = sizeLimits
    )

    @Before
    fun setup() {
        runBlocking {
            whenever(movieDao.getTmdbIdsByProvider(any())).thenReturn(emptyList())
            whenever(seriesDao.getTmdbIdsByProvider(any())).thenReturn(emptyList())
            whenever(catalogSyncDao.countChannelStages(any(), any())).thenReturn(0)
            whenever(catalogSyncDao.countMovieStages(any(), any())).thenReturn(0)
            whenever(catalogSyncDao.countSeriesStages(any(), any())).thenReturn(0)
            whenever(catalogSyncDao.countCategoryStages(any(), any(), any())).thenReturn(0)
        }
    }

    @Test
    fun `live admission counts check fresh session once and enforce the limit`() = runTest {
        val store = store(sizeLimits = CatalogSizeLimits(maxChannelsPerProvider = 2))
        val first = ChannelEntity(streamId = 1, name = "One", providerId = 7)
        store.stageAdmittedLiveBatch(7, 90, listOf(first), 1)
        store.stageAdmittedLiveBatch(7, 90, listOf(first.copy(streamId = 2)), 2)
        val failure = runCatching {
            store.stageAdmittedLiveBatch(7, 90, listOf(first.copy(streamId = 3)), 3)
        }.exceptionOrNull()
        assertThat(failure).isNotNull()
        verify(catalogSyncDao).countChannelStages(7, 90)
        verify(catalogSyncDao, org.mockito.kotlin.times(2)).insertChannelStages(any())
    }

    @Test
    fun `live admission refuses a nonempty initial session`() = runTest {
        whenever(catalogSyncDao.countChannelStages(7, 90)).thenReturn(1)
        val failure = runCatching {
            store().stageAdmittedLiveBatch(7, 90, listOf(ChannelEntity(streamId = 1, name = "One", providerId = 7)), 1)
        }.exceptionOrNull()
        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        verify(catalogSyncDao, never()).insertChannelStages(any())
    }

    @Test
    fun `category fingerprints retain the existing persisted encoding`() = runTest {
        val category = CategoryEntity(categoryId = 12, name = "  NEWS\t世界  ", providerId = 7)
        store().stageCategories(7, 90, listOf(category))
        val rows = argumentCaptor<List<CategoryImportStageEntity>>()
        verify(catalogSyncDao).insertCategoryStages(rows.capture())
        val legacyDigest = java.security.MessageDigest.getInstance("SHA-256")
        listOf("12", "news 世界", category.parentId?.toString().orEmpty(), "LIVE", category.providerOrder.toString(), "false").forEach {
            legacyDigest.update(it.toByteArray(Charsets.UTF_8))
            legacyDigest.update(0)
        }
        val legacyFingerprint = legacyDigest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        assertThat(rows.firstValue.single().syncFingerprint).isEqualTo(legacyFingerprint)
    }

    @Test
    fun `replaceLiveCatalog batches changed category and channel updates`() = runTest {
        val providerId = 7L
        val currentCategory = CategoryEntity(
            id = 11L,
            categoryId = 101L,
            name = "News",
            type = ContentType.LIVE,
            providerId = providerId,
            providerOrder = 4,
            syncFingerprint = "old-category"
        )
        val currentChannel = ChannelEntity(
            id = 21L,
            streamId = 1001L,
            name = "Old News",
            providerId = providerId,
            streamUrl = "https://old.example.com/live",
            syncFingerprint = "old-channel"
        )

        whenever(categoryDao.getByProviderAndTypeSync(providerId, ContentType.LIVE.name)).thenReturn(listOf(currentCategory))
        whenever(channelDao.getByProviderSync(providerId)).thenReturn(listOf(currentChannel))
        whenever(catalogSyncDao.getCategoryStages(eq(providerId), any(), eq(ContentType.LIVE.name))).thenReturn(
            listOf(
                CategoryImportStageEntity(
                    sessionId = 1L,
                    providerId = providerId,
                    categoryId = 101L,
                    name = "World News",
                    type = ContentType.LIVE,
                    providerOrder = 9,
                    syncFingerprint = "new-category"
                )
            )
        )

        store().replaceLiveCatalog(
            providerId = providerId,
            categories = listOf(currentCategory.copy(name = "World News")),
            channels = listOf(currentChannel.copy(name = "World News HD"))
        )

        val updatedCategories = argumentCaptor<List<CategoryEntity>>()
        verify(categoryDao).updateAll(updatedCategories.capture())
        assertThat(updatedCategories.firstValue.single()).isEqualTo(
            currentCategory.copy(
                name = "World News",
                providerOrder = 9,
                syncFingerprint = "new-category"
            )
        )

        verify(catalogSyncDao).updateChangedChannelsFromStage(eq(providerId), any())
    }

    @Test
    fun `applyStagedMovieCatalog batches changed movie updates`() = runTest {
        val providerId = 7L
        val sessionId = 33L
        store().applyStagedMovieCatalog(providerId, sessionId, categories = null)

        verify(catalogSyncDao).updateChangedMoviesFromStage(providerId, sessionId)
        verify(catalogSyncDao).rebuildMovieFts()
        verify(movieDao).restoreWatchProgress(providerId)
    }

    @Test
    fun `applyStagedSeriesCatalog batches changed series updates`() = runTest {
        val providerId = 7L
        val sessionId = 44L
        store().applyStagedSeriesCatalog(providerId, sessionId, categories = null)

        verify(catalogSyncDao).updateChangedSeriesFromStage(providerId, sessionId)
        verify(catalogSyncDao).rebuildSeriesFts()
    }

    @Test
    fun `applyStagedLiveCatalog promotes inside transaction without rebuilding global fts`() = runTest {
        val runner = TrackingTransactionRunner()
        doAnswer {
            assertThat(runner.isInTransaction).isTrue()
            Unit
        }.whenever(catalogSyncDao).updateChangedChannelsFromStage(7L, 55L)

        store(transactionRunner = runner).applyStagedLiveCatalog(providerId = 7L, sessionId = 55L, categories = null)

        // One transaction applies the catalog and one atomically clears its staging session.
        assertThat(runner.calls).isEqualTo(2)
        verify(catalogSyncDao, never()).rebuildChannelFts()
    }

    @Test
    fun `stagedLiveImportState prefers staged category rows over channel-derived placeholders`() = runTest {
        val providerId = 7L
        val sessionId = 55L
        whenever(catalogSyncDao.getCategoryStages(providerId, sessionId, ContentType.LIVE.name)).thenReturn(
            listOf(
                CategoryImportStageEntity(
                    sessionId = sessionId,
                    providerId = providerId,
                    categoryId = 1382L,
                    name = "VIP | GOLDEN EVENTS",
                    parentId = 0,
                    type = ContentType.LIVE,
                    isAdult = false,
                    syncFingerprint = "cat-1382"
                )
            )
        )
        whenever(catalogSyncDao.getChannelStageCategorySummaries(providerId, sessionId)).thenReturn(
            listOf(ChannelStageCategorySummary(categoryId = 1382L, name = "Category 1382", isAdult = false))
        )
        whenever(catalogSyncDao.countChannelStages(providerId, sessionId)).thenReturn(1)
        whenever(catalogSyncDao.countMovieStages(providerId, sessionId)).thenReturn(0)
        whenever(catalogSyncDao.countSeriesStages(providerId, sessionId)).thenReturn(0)

        val staged = store().stagedLiveImportState(providerId, sessionId)

        assertThat(staged.categories).hasSize(1)
        assertThat(staged.categories.single().categoryId).isEqualTo(1382L)
        assertThat(staged.categories.single().name).isEqualTo("VIP | GOLDEN EVENTS")
    }

    @Test
    fun `replaceSeriesCatalog stages provider-native series ids`() = runTest {
        val providerId = 7L
        whenever(seriesDao.getByProviderSync(providerId)).thenReturn(emptyList())
        whenever(catalogSyncDao.getSeriesStages(eq(providerId), any())).thenReturn(emptyList())

        val acceptedCount = store().replaceSeriesCatalog(
            providerId = providerId,
            categories = null,
            series = sequenceOf(
                SeriesEntity(
                    seriesId = 256103980L,
                    providerSeriesId = "55000:55000",
                    name = "Composite Series",
                    providerId = providerId,
                    syncFingerprint = ""
                )
            )
        )

        assertThat(acceptedCount).isEqualTo(1)
        val insertedStages = argumentCaptor<List<SeriesImportStageEntity>>()
        verify(catalogSyncDao).insertSeriesStages(insertedStages.capture())
        assertThat(insertedStages.firstValue.single().providerSeriesId).isEqualTo("55000:55000")
        assertThat(insertedStages.firstValue.single().providerSeriesKey).isEqualTo("55000:55000")
    }

    @Test
    fun `stageMovieBatch preserves movie metadata needed by staged apply`() = runTest {
        val providerId = 7L
        val sessionId = 88L
        val movie = MovieEntity(
            streamId = 1001L,
            name = "Late Night Feature",
            posterUrl = "https://img.example.test/movie-poster.jpg",
            backdropUrl = "https://img.example.test/movie-backdrop.jpg",
            categoryId = 42L,
            categoryName = "Thriller",
            streamUrl = "https://stream.example.test/movie.m3u8",
            containerExtension = "mp4",
            plot = "A staged sync guardrail.",
            cast = "Lead Actor",
            director = "Director Name",
            genre = "Thriller",
            releaseDate = "2026-05-02",
            duration = "01:45:00",
            durationSeconds = 6_300,
            rating = 8.4f,
            year = "2026",
            tmdbId = 555001L,
            youtubeTrailer = "trailer123",
            providerId = providerId,
            isAdult = true,
            addedAt = 123_456L
        )

        store().stageMovieBatch(providerId, sessionId, listOf(movie))

        val insertedStages = argumentCaptor<List<MovieImportStageEntity>>()
        verify(catalogSyncDao).insertMovieStages(insertedStages.capture())
        val stagedMovie = insertedStages.firstValue.single()
        assertThat(stagedMovie.copy(syncFingerprint = "")).isEqualTo(
            MovieImportStageEntity(
                sessionId = sessionId,
                providerId = providerId,
                streamId = movie.streamId,
                name = movie.name,
                posterUrl = movie.posterUrl,
                backdropUrl = movie.backdropUrl,
                categoryId = movie.categoryId,
                categoryName = movie.categoryName,
                streamUrl = movie.streamUrl,
                containerExtension = movie.containerExtension,
                plot = movie.plot,
                cast = movie.cast,
                director = movie.director,
                genre = movie.genre,
                releaseDate = movie.releaseDate,
                duration = movie.duration,
                durationSeconds = movie.durationSeconds,
                rating = movie.rating,
                year = movie.year,
                tmdbId = movie.tmdbId,
                youtubeTrailer = movie.youtubeTrailer,
                isAdult = movie.isAdult,
                syncFingerprint = "",
                addedAt = movie.addedAt
            )
        )
        assertThat(stagedMovie.syncFingerprint).isNotEmpty()
    }

    @Test
    fun `stageChannelBatch fingerprints xtream live rows without derived volatile fields`() = runTest {
        val providerId = 7L
        val sessionId = 90L
        val baseChannel = ChannelEntity(
            streamId = 777L,
            name = "World News",
            logoUrl = "https://img.example.test/logo.png",
            groupTitle = "News",
            categoryId = 12L,
            categoryName = "News",
            streamUrl = "xtream://7/live/777?ext=m3u8&src=https%3A%2F%2Fcdn.example.test%2Fa.m3u8%3Ftoken%3Done",
            epgChannelId = "world-news",
            number = 4,
            catchUpSupported = true,
            catchUpDays = 3,
            catchUpSource = "source-a",
            providerId = providerId,
            isAdult = false
        )

        val store = store()
        store.stageChannelBatch(providerId, sessionId, listOf(baseChannel))
        store.stageChannelBatch(
            providerId,
            sessionId + 1,
            listOf(
                baseChannel.copy(
                    categoryName = "Renamed News",
                    streamUrl = "xtream://7/live/777?ext=m3u8&src=https%3A%2F%2Fcdn.example.test%2Fb.m3u8%3Ftoken%3Dtwo",
                    catchUpSource = "source-b"
                )
            )
        )

        val insertedStages = argumentCaptor<List<ChannelImportStageEntity>>()
        verify(catalogSyncDao, org.mockito.kotlin.times(2)).insertChannelStages(insertedStages.capture())
        val fingerprints = insertedStages.allValues.map { it.single().syncFingerprint }
        assertThat(fingerprints).hasSize(2)
        assertThat(fingerprints.first()).isEqualTo(fingerprints.last())
    }

    @Test
    fun `stageSeriesBatch preserves provider identity and metadata needed by staged apply`() = runTest {
        val providerId = 7L
        val sessionId = 89L
        val series = SeriesEntity(
            seriesId = 256103980L,
            providerSeriesId = "55000:55000",
            name = "Composite Series",
            posterUrl = "https://img.example.test/series-poster.jpg",
            backdropUrl = "https://img.example.test/series-backdrop.jpg",
            categoryId = 77L,
            categoryName = "Drama",
            plot = "Series staging guardrail.",
            cast = "Ensemble Cast",
            director = "Showrunner",
            genre = "Drama",
            releaseDate = "2026-05-02",
            rating = 9.1f,
            tmdbId = 777001L,
            youtubeTrailer = "seriesTrailer",
            episodeRunTime = "52",
            lastModified = 9_999L,
            providerId = providerId,
            isAdult = true
        )

        store().stageSeriesBatch(providerId, sessionId, listOf(series))

        val insertedStages = argumentCaptor<List<SeriesImportStageEntity>>()
        verify(catalogSyncDao).insertSeriesStages(insertedStages.capture())
        val stagedSeries = insertedStages.firstValue.single()
        assertThat(stagedSeries.copy(syncFingerprint = "")).isEqualTo(
            SeriesImportStageEntity(
                sessionId = sessionId,
                providerId = providerId,
                seriesId = series.seriesId,
                providerSeriesId = series.providerSeriesId,
                providerSeriesKey = series.providerSeriesId!!,
                name = series.name,
                posterUrl = series.posterUrl,
                backdropUrl = series.backdropUrl,
                categoryId = series.categoryId,
                categoryName = series.categoryName,
                plot = series.plot,
                cast = series.cast,
                director = series.director,
                genre = series.genre,
                releaseDate = series.releaseDate,
                rating = series.rating,
                tmdbId = series.tmdbId,
                youtubeTrailer = series.youtubeTrailer,
                episodeRunTime = series.episodeRunTime,
                lastModified = series.lastModified,
                isAdult = series.isAdult,
                syncFingerprint = ""
            )
        )
        assertThat(stagedSeries.syncFingerprint).isNotEmpty()
    }

    @Test
    fun `stageChannelBatch checks and inserts within one transaction`() = runTest {
        val runner = TrackingTransactionRunner()
        val providerId = 7L
        val sessionId = 91L
        whenever(catalogSyncDao.countChannelStages(providerId, sessionId)).thenAnswer {
            assertThat(runner.isInTransaction).isTrue()
            0
        }
        doAnswer {
            assertThat(runner.isInTransaction).isTrue()
            Unit
        }.whenever(catalogSyncDao).insertChannelStages(any())

        store(
            sizeLimits = CatalogSizeLimits(maxChannelsPerProvider = 1),
            transactionRunner = runner
        ).stageChannelBatch(
            providerId,
            sessionId,
            listOf(
                ChannelEntity(
                    streamId = 1L,
                    name = "News",
                    providerId = providerId,
                    streamUrl = "https://stream.example.com/news.ts"
                )
            )
        )

        assertThat(runner.calls).isEqualTo(1)
        verify(catalogSyncDao).insertChannelStages(any())
    }

    @Test
    fun `stageCategories rejects a provider type beyond the shared limit`() = runTest {
        val providerId = 7L
        val sessionId = 92L
        whenever(catalogSyncDao.countCategoryStages(providerId, sessionId, ContentType.LIVE.name)).thenReturn(1)

        val failure = runCatching {
            store(CatalogSizeLimits(maxM3uCategoriesPerType = 1)).stageCategories(
                providerId,
                sessionId,
                listOf(
                    CategoryEntity(
                        categoryId = 2L,
                        name = "Sports",
                        type = ContentType.LIVE,
                        providerId = providerId
                    )
                )
            )
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(CatalogAdmissionExceeded::class.java)
        assertThat(failure).hasMessageThat().contains("live category limit exceeded")
        verify(catalogSyncDao, never()).insertCategoryStages(any())
    }

    @Test
    fun `discardStagedImport clears every stage table in one transaction`() = runTest {
        val runner = TrackingTransactionRunner()
        val providerId = 7L
        val sessionId = 93L
        doAnswer {
            assertThat(runner.isInTransaction).isTrue()
            Unit
        }.whenever(catalogSyncDao).clearChannelStages(providerId, sessionId)
        doAnswer {
            assertThat(runner.isInTransaction).isTrue()
            Unit
        }.whenever(catalogSyncDao).clearMovieStages(providerId, sessionId)
        doAnswer {
            assertThat(runner.isInTransaction).isTrue()
            Unit
        }.whenever(catalogSyncDao).clearSeriesStages(providerId, sessionId)
        doAnswer {
            assertThat(runner.isInTransaction).isTrue()
            Unit
        }.whenever(catalogSyncDao).clearCategoryStages(providerId, sessionId)

        store(transactionRunner = runner).discardStagedImport(providerId, sessionId)

        assertThat(runner.calls).isEqualTo(1)
    }

    @Test
    fun `replaceCategories preserves user protection when provider renames category`() = runTest {
        val providerId = 7L
        val currentCategory = CategoryEntity(
            id = 51L,
            categoryId = 909L,
            name = "Kids",
            type = ContentType.MOVIE,
            providerId = providerId,
            isUserProtected = true,
            syncFingerprint = "old-category"
        )
        whenever(categoryDao.getByProviderAndTypeSync(providerId, ContentType.MOVIE.name)).thenReturn(listOf(currentCategory))
        whenever(catalogSyncDao.getCategoryStages(eq(providerId), any(), eq(ContentType.MOVIE.name))).thenReturn(
            listOf(
                CategoryImportStageEntity(
                    sessionId = 1L,
                    providerId = providerId,
                    categoryId = 909L,
                    name = "Documentaries",
                    type = ContentType.MOVIE,
                    syncFingerprint = "new-category"
                )
            )
        )

        store().replaceCategories(
            providerId = providerId,
            type = ContentType.MOVIE.name,
            categories = listOf(currentCategory.copy(name = "Documentaries", isUserProtected = false))
        )

        val updatedCategories = argumentCaptor<List<CategoryEntity>>()
        verify(categoryDao).updateAll(updatedCategories.capture())
        assertThat(updatedCategories.firstValue.single()).isEqualTo(
            currentCategory.copy(
                name = "Documentaries",
                isUserProtected = true,
                syncFingerprint = "new-category"
            )
        )
        verify(movieDao, never()).clearProtectionForCategories(any(), any())
        verify(channelDao, never()).clearProtectionForCategories(any(), any())
        verify(seriesDao, never()).clearProtectionForCategories(any(), any())
    }

    @Test
    fun `upsertCategories preserves unstaged categories for index-first setup`() = runTest {
        val providerId = 7L
        whenever(categoryDao.getByProviderAndTypeSync(providerId, ContentType.MOVIE.name)).thenReturn(
            listOf(
                CategoryEntity(
                    id = 51L,
                    categoryId = 909L,
                    name = "Existing",
                    type = ContentType.MOVIE,
                    providerId = providerId,
                    isUserProtected = true,
                    syncFingerprint = "existing"
                ),
                CategoryEntity(
                    id = 52L,
                    categoryId = 910L,
                    name = "Still Remote",
                    type = ContentType.MOVIE,
                    providerId = providerId,
                    syncFingerprint = "still-remote"
                )
            )
        )
        whenever(catalogSyncDao.getCategoryStages(eq(providerId), any(), eq(ContentType.MOVIE.name))).thenReturn(
            listOf(
                CategoryImportStageEntity(
                    sessionId = 1L,
                    providerId = providerId,
                    categoryId = 909L,
                    name = "Existing Renamed",
                    type = ContentType.MOVIE,
                    syncFingerprint = "existing-renamed"
                )
            )
        )

        store().upsertCategories(
            providerId = providerId,
            type = ContentType.MOVIE.name,
            categories = listOf(
                CategoryEntity(
                    categoryId = 909L,
                    name = "Existing Renamed",
                    type = ContentType.MOVIE,
                    providerId = providerId
                )
            )
        )

        verify(catalogSyncDao).insertMissingCategoriesFromStage(eq(providerId), any(), eq(ContentType.MOVIE.name))
        verify(catalogSyncDao, never()).deleteStaleCategoriesForStage(any(), any(), any())
        verify(movieDao, never()).clearProtectionForCategories(any(), any())
    }

    @Test
    fun `replaceLiveCatalog keeps lowest numbered channels within configured budget`() = runTest {
        val providerId = 9L
        whenever(categoryDao.getByProviderAndTypeSync(providerId, ContentType.LIVE.name)).thenReturn(emptyList())
        whenever(channelDao.getByProviderSync(providerId)).thenReturn(emptyList())
        whenever(catalogSyncDao.getChannelStages(eq(providerId), any())).thenReturn(emptyList())

        val limitedStore = store(CatalogSizeLimits(maxChannelsPerProvider = 2))
        val channels = listOf(
            ChannelEntity(streamId = 1001L, name = "News", number = 50, providerId = providerId, streamUrl = "https://example.com/50"),
            ChannelEntity(streamId = 1002L, name = "Sports", number = 10, providerId = providerId, streamUrl = "https://example.com/10"),
            ChannelEntity(streamId = 1003L, name = "Movies", number = 30, providerId = providerId, streamUrl = "https://example.com/30")
        )

        val acceptedCount = limitedStore.replaceLiveCatalog(providerId, categories = null, channels = channels)

        assertThat(acceptedCount).isEqualTo(2)
        val insertedChannels = argumentCaptor<List<ChannelImportStageEntity>>()
        verify(catalogSyncDao).insertChannelStages(insertedChannels.capture())
        assertThat(insertedChannels.firstValue.map { it.streamId }).containsExactly(1002L, 1003L).inOrder()
        verify(catalogSyncDao).rebuildChannelFts()
    }

    @Test
    fun `replaceMovieCatalog keeps highest rated movies within configured budget`() = runTest {
        val providerId = 11L
        whenever(movieDao.getByProviderSync(providerId)).thenReturn(emptyList())
        whenever(catalogSyncDao.getMovieStages(eq(providerId), any())).thenReturn(emptyList())

        val limitedStore = store(CatalogSizeLimits(maxMoviesPerProvider = 2))
        val movies = sequenceOf(
            MovieEntity(streamId = 2001L, name = "Movie A", providerId = providerId, streamUrl = "https://example.com/a", rating = 4.2f),
            MovieEntity(streamId = 2002L, name = "Movie B", providerId = providerId, streamUrl = "https://example.com/b", rating = 9.1f),
            MovieEntity(streamId = 2003L, name = "Movie C", providerId = providerId, streamUrl = "https://example.com/c", rating = 7.6f)
        )

        val acceptedCount = limitedStore.replaceMovieCatalog(providerId, categories = null, movies = movies)

        assertThat(acceptedCount).isEqualTo(2)
        val insertedMovies = argumentCaptor<List<MovieImportStageEntity>>()
        verify(catalogSyncDao).insertMovieStages(insertedMovies.capture())
        assertThat(insertedMovies.firstValue.map { it.streamId }).containsExactly(2002L, 2003L).inOrder()
        verify(catalogSyncDao).rebuildMovieFts()
    }

    @Test
    fun `finalizeStagedImport rebuilds live and movie fts inside transaction`() = runTest {
        val runner = TrackingTransactionRunner()
        whenever(categoryDao.getByProviderAndTypeSync(7L, ContentType.LIVE.name)).thenReturn(emptyList())
        whenever(categoryDao.getByProviderAndTypeSync(7L, ContentType.MOVIE.name)).thenReturn(emptyList())
        whenever(catalogSyncDao.getCategoryStages(eq(7L), any(), eq(ContentType.LIVE.name))).thenReturn(emptyList())
        whenever(catalogSyncDao.getCategoryStages(eq(7L), any(), eq(ContentType.MOVIE.name))).thenReturn(emptyList())
        doAnswer {
            assertThat(runner.isInTransaction).isTrue()
            Unit
        }.whenever(catalogSyncDao).rebuildChannelFts()
        doAnswer {
            assertThat(runner.isInTransaction).isTrue()
            Unit
        }.whenever(catalogSyncDao).rebuildMovieFts()

        store(transactionRunner = runner).finalizeStagedImport(
            providerId = 7L,
            sessionId = 66L,
            liveCategories = null,
            movieCategories = null,
            includeLive = true,
            includeMovies = true
        )

        verify(catalogSyncDao).rebuildChannelFts()
        verify(catalogSyncDao).rebuildMovieFts()
        verify(movieDao).restoreWatchProgress(7L)
    }

    @Test
    fun `applyStagedJellyfinCatalog promotes movies and series in one transaction`() = runTest {
        val runner = TrackingTransactionRunner()
        whenever(categoryDao.getByProviderAndTypeSync(7L, ContentType.MOVIE.name)).thenReturn(emptyList())
        whenever(categoryDao.getByProviderAndTypeSync(7L, ContentType.SERIES.name)).thenReturn(emptyList())
        whenever(catalogSyncDao.getCategoryStages(7L, 101L, ContentType.MOVIE.name)).thenReturn(emptyList())
        whenever(catalogSyncDao.getCategoryStages(7L, 202L, ContentType.SERIES.name)).thenReturn(emptyList())
        doAnswer { assertThat(runner.isInTransaction).isTrue(); Unit }
            .whenever(catalogSyncDao).rebuildMovieFts()
        doAnswer { assertThat(runner.isInTransaction).isTrue(); Unit }
            .whenever(catalogSyncDao).rebuildSeriesFts()
        var callbackInTransaction = false

        store(transactionRunner = runner).applyStagedJellyfinCatalog(
            providerId = 7L,
            movieSessionId = 101L,
            seriesSessionId = 202L,
            movieCategories = listOf(CategoryEntity(providerId = 7L, categoryId = 1L, name = "Movies", type = ContentType.MOVIE)),
            seriesCategories = listOf(CategoryEntity(providerId = 7L, categoryId = 2L, name = "Series", type = ContentType.SERIES)),
            afterCatalogApply = { callbackInTransaction = runner.isInTransaction }
        )

        assertThat(callbackInTransaction).isTrue()
        verify(catalogSyncDao).rebuildMovieFts()
        verify(catalogSyncDao).rebuildSeriesFts()
        verify(movieDao).restoreWatchProgress(7L)
        verify(catalogSyncDao).clearMovieStages(7L, 101L)
        verify(catalogSyncDao).clearSeriesStages(7L, 202L)
    }

    private class TrackingTransactionRunner : DatabaseTransactionRunner {
        var calls: Int = 0
        private var depth: Int = 0

        val isInTransaction: Boolean
            get() = depth > 0

        override suspend fun <T> inTransaction(block: suspend () -> T): T {
            calls += 1
            depth += 1
            return try {
                block()
            } finally {
                depth -= 1
            }
        }
    }
}
