package com.streamvault.feature.catalog.presentation.movies

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.google.common.truth.Truth.assertThat
import com.streamvault.feature.catalog.api.CatalogCastPlaybackEvent
import com.streamvault.feature.catalog.api.CatalogCastPort
import com.streamvault.feature.catalog.api.CatalogCastRequest
import com.streamvault.feature.catalog.api.CatalogCastStartResult
import com.streamvault.feature.catalog.api.CatalogMessage
import com.streamvault.feature.catalog.api.CatalogStreamPreparer
import com.streamvault.feature.catalog.api.CatalogUiEvent
import com.streamvault.feature.catalog.presentation.CatalogMainDispatcherRule
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.domain.model.ExternalRatings
import com.streamvault.domain.model.Movie
import com.streamvault.domain.model.LegacyProvider as Provider
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.repository.DownloadManager
import com.streamvault.domain.repository.ExternalRatingsRepository
import com.streamvault.domain.repository.FavoriteRepository
import com.streamvault.domain.repository.MovieRepository
import com.streamvault.domain.repository.PlaybackHistoryRepository
import com.streamvault.domain.repository.ProviderRepository
import com.streamvault.domain.trakt.TraktFavoriteActionResult
import com.streamvault.domain.trakt.TraktFavoriteIdentity
import com.streamvault.domain.trakt.TraktFavoriteType
import com.streamvault.domain.trakt.TraktFavorites
import com.streamvault.domain.trakt.TraktRatingActionResult
import com.streamvault.domain.trakt.TraktRatingIdentity
import com.streamvault.domain.trakt.TraktRatingType
import com.streamvault.domain.trakt.TraktRatingRefreshResult
import com.streamvault.domain.trakt.TraktRatings
import com.streamvault.domain.trakt.TraktRatingsState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class MovieDetailViewModelCastingTest {

    @get:Rule
    val mainDispatcherRule = CatalogMainDispatcherRule()

    @Test
    fun `rateOnTrakt sends exact movie identity and records successful rating`() = runBlocking {
        val ratings = RecordingTraktRatings()
        val viewModel = createViewModel(movie = movie().copy(tmdbId = 603L), traktRatings = ratings)

        try {
            viewModel.uiState.first { it.movie != null }
            val event = async(start = CoroutineStart.UNDISPATCHED) { viewModel.uiEvents.first() }
            viewModel.rateOnTrakt(8)

            assertThat(withTimeout(5_000L) { event.await() })
                .isEqualTo(CatalogUiEvent.ShowMessage(CatalogMessage.TraktRatingSaved))
            assertThat(ratings.writes).containsExactly(TraktRatingIdentity(TraktRatingType.MOVIE, 603L) to 8)
            assertThat(viewModel.uiState.value.traktUserRating).isEqualTo(8)
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    @Test
    fun `castMovie emits route chooser and preserves resume position`() = runBlocking {
        val movie = movie(watchProgress = 42_000L)
        val castPort = FakeCatalogCastPort(CatalogCastStartResult.RouteSelectionRequired)
        val viewModel = createViewModel(movie = movie, castPort = castPort)

        try {
            val event = async(start = CoroutineStart.UNDISPATCHED) { viewModel.castEvents.first() }
            viewModel.castMovie()

            assertThat(withTimeout(5_000L) { event.await() }).isEqualTo(CatalogUiEvent.OpenCastRouteChooser)
            assertThat(castPort.lastRequest?.streamInfo?.url).isEqualTo("https://example.test/movie.m3u8")
            assertThat(castPort.lastRequest?.startPositionMs).isEqualTo(42_000L)
            assertThat(viewModel.uiState.value.isCasting).isTrue()
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    @Test
    fun `castMovie emits unsupported message from coordinator result`() = runBlocking {
        val castPort = FakeCatalogCastPort(CatalogCastStartResult.Unsupported(CatalogMessage.CastUnsupported))
        val viewModel = createViewModel(castPort = castPort)

        try {
            val event = async(start = CoroutineStart.UNDISPATCHED) { viewModel.castEvents.first() }
            viewModel.castMovie()

            assertThat(withTimeout(5_000L) { event.await() })
                .isEqualTo(CatalogUiEvent.ShowMessage(CatalogMessage.CastUnsupported))
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    @Test
    fun `castMovie reports started after route-selected media load succeeds`() = runBlocking {
        val castPort = FakeCatalogCastPort(CatalogCastStartResult.RouteSelectionRequired)
        val viewModel = createViewModel(castPort = castPort)

        try {
            val routeEvent = async(start = CoroutineStart.UNDISPATCHED) { viewModel.castEvents.first() }
            viewModel.castMovie()
            assertThat(withTimeout(5_000L) { routeEvent.await() }).isEqualTo(CatalogUiEvent.OpenCastRouteChooser)

            val lifecycleEvent = async(start = CoroutineStart.UNDISPATCHED) { viewModel.castEvents.first() }
            castPort.emit(CatalogCastPlaybackEvent.Finished(true, CatalogMessage.CastStarted))

            assertThat(withTimeout(5_000L) { lifecycleEvent.await() })
                .isEqualTo(CatalogUiEvent.ShowMessage(CatalogMessage.CastStarted))
            assertThat(viewModel.uiState.value.isCasting).isFalse()
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    @Test
    fun `castMovie reports receiver load failure after immediate start`() = runBlocking {
        val castPort = FakeCatalogCastPort(CatalogCastStartResult.Started)
        val viewModel = createViewModel(castPort = castPort)

        try {
            val startEvent = async(start = CoroutineStart.UNDISPATCHED) { viewModel.castEvents.first() }
            viewModel.castMovie()
            assertThat(withTimeout(5_000L) { startEvent.await() })
                .isEqualTo(CatalogUiEvent.ShowMessage(CatalogMessage.CastStarted))

            val lifecycleEvent = async(start = CoroutineStart.UNDISPATCHED) { viewModel.castEvents.first() }
            castPort.emit(CatalogCastPlaybackEvent.Finished(false, CatalogMessage.CastLoadFailed))

            assertThat(withTimeout(5_000L) { lifecycleEvent.await() })
                .isEqualTo(CatalogUiEvent.ShowMessage(CatalogMessage.CastLoadFailed))
            assertThat(viewModel.uiState.value.isCasting).isFalse()
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    @Test
    fun `castMovie resets pending state when route selection is cancelled`() = runBlocking {
        val castPort = FakeCatalogCastPort(CatalogCastStartResult.RouteSelectionRequired)
        val viewModel = createViewModel(castPort = castPort)

        try {
            val routeEvent = async(start = CoroutineStart.UNDISPATCHED) { viewModel.castEvents.first() }
            viewModel.castMovie()
            assertThat(withTimeout(5_000L) { routeEvent.await() }).isEqualTo(CatalogUiEvent.OpenCastRouteChooser)
            assertThat(viewModel.uiState.value.isCasting).isTrue()

            castPort.emit(CatalogCastPlaybackEvent.RouteSelectionCancelled)

            withTimeout(5_000L) { viewModel.uiState.first { !it.isCasting } }
            assertThat(viewModel.uiState.value.isCasting).isFalse()
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    @Test
    fun `castMovie ignores repeated launches while route selection is pending`() = runBlocking {
        val castPort = FakeCatalogCastPort(CatalogCastStartResult.RouteSelectionRequired)
        val viewModel = createViewModel(castPort = castPort)

        try {
            val routeEvent = async(start = CoroutineStart.UNDISPATCHED) { viewModel.castEvents.first() }
            viewModel.castMovie()
            assertThat(withTimeout(5_000L) { routeEvent.await() }).isEqualTo(CatalogUiEvent.OpenCastRouteChooser)

            viewModel.castMovie()

            assertThat(castPort.startCount).isEqualTo(1)
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    @Test
    fun `heart toggle also syncs the movie TMDB id to Trakt favorites`() = runBlocking {
        val favorites = RecordingTraktFavorites()
        val viewModel = createViewModel(movie = movie().copy(tmdbId = 603L), traktFavorites = favorites)

        try {
            withTimeout(5_000L) { viewModel.uiState.first { it.movie != null } }
            viewModel.toggleFavorite()

            assertThat(withTimeout(5_000L) { favorites.completed.await() }).isEqualTo(Unit)
            assertThat(favorites.writes).containsExactly(
                TraktFavoriteIdentity(TraktFavoriteType.MOVIE, 603L) to true,
            )
            assertThat(viewModel.uiState.value.movie?.isFavorite).isTrue()
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    @Test
    fun `castMovie explains streams that need header rewrite when cast remains unsupported`() = runBlocking {
        val castPort = FakeCatalogCastPort(CatalogCastStartResult.Unsupported(CatalogMessage.CastUnsupported))
        val streamInfo = StreamInfo(
            url = "https://example.test/movie.m3u8",
            headers = mapOf("Cookie" to "session=abc")
        )
        val viewModel = createViewModel(
            streamInfo = streamInfo,
            castPort = castPort
        )

        try {
            val event = async(start = CoroutineStart.UNDISPATCHED) { viewModel.castEvents.first() }
            viewModel.castMovie()

            assertThat(withTimeout(5_000L) { event.await() })
                .isEqualTo(CatalogUiEvent.ShowMessage(CatalogMessage.CastUnsupported))
            assertThat(castPort.lastRequest?.streamInfo).isEqualTo(streamInfo)
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    private suspend fun createViewModel(
        movie: Movie = movie(),
        streamInfo: StreamInfo = StreamInfo(url = "https://example.test/movie.m3u8"),
        castPort: FakeCatalogCastPort = FakeCatalogCastPort(),
        traktFavorites: TraktFavorites = com.streamvault.feature.catalog.presentation.NoopTraktFavorites(),
        traktRatings: TraktRatings = com.streamvault.feature.catalog.presentation.NoopTraktRatings(),
    ): MovieDetailViewModel {
        val provider = Provider(
            id = movie.providerId,
            name = "Provider",
            type = ProviderType.XTREAM_CODES,
            serverUrl = "https://provider.test"
        )
        val movieRepository: MovieRepository = mock()
        val providerRepository: ProviderRepository = mock()
        val playbackHistoryRepository: PlaybackHistoryRepository = mock()
        val externalRatingsRepository: ExternalRatingsRepository = mock()
        val favoriteRepository: FavoriteRepository = mock()

        whenever(movieRepository.getMovie(movie.id)).thenReturn(movie)
        whenever(movieRepository.getMovieDetails(eq(provider.id), eq(movie.id), anyOrNull()))
            .thenReturn(Result.success(movie))
        whenever(movieRepository.getRelatedContent(eq(provider.id), eq(movie.id), any()))
            .thenReturn(flowOf(emptyList()))
        whenever(movieRepository.getStreamInfo(any()))
            .thenReturn(Result.success(streamInfo))
        whenever(providerRepository.getActiveProvider()).thenReturn(flowOf(provider))
        whenever(
            playbackHistoryRepository.getPlaybackHistory(
                contentId = eq(movie.id),
                contentType = any(),
                providerId = eq(provider.id),
                seriesId = anyOrNull(),
                seasonNumber = anyOrNull(),
                episodeNumber = anyOrNull()
            )
        ).thenReturn(null)
        whenever(favoriteRepository.isFavorite(eq(provider.id), eq(movie.id), any()))
            .thenReturn(false)
        whenever(externalRatingsRepository.getRatings(any()))
            .thenReturn(Result.success(ExternalRatings.unavailable()))

        return MovieDetailViewModel(
            savedStateHandle = SavedStateHandle(mapOf("movieId" to movie.id)),
            movieRepository = movieRepository,
            providerRepository = providerRepository,
            playbackHistoryRepository = playbackHistoryRepository,
            externalRatingsRepository = externalRatingsRepository,
            favoriteRepository = favoriteRepository,
            preferencesRepository = mock<PreferencesRepository>(),
            streamPreparer = object : CatalogStreamPreparer {
                override suspend fun prepare(streamInfo: StreamInfo): Result<StreamInfo> =
                    Result.success(streamInfo)
            },
            downloadManager = mock<DownloadManager>(),
            downloadStarter = object : com.streamvault.feature.catalog.api.CatalogDownloadStarter {
                override fun startDownload(downloadId: String) = Unit
            },
            castPort = castPort,
            traktWatchlist = com.streamvault.feature.catalog.presentation.NoopTraktWatchlist(),
            traktFavorites = traktFavorites,
            traktRatings = traktRatings,
        )
    }

    private class RecordingTraktFavorites : TraktFavorites {
        val writes = mutableListOf<Pair<TraktFavoriteIdentity, Boolean>>()
        val completed = CompletableDeferred<Unit>()

        override suspend fun setFavorited(identity: TraktFavoriteIdentity, favorited: Boolean): TraktFavoriteActionResult {
            writes += identity to favorited
            completed.complete(Unit)
            return TraktFavoriteActionResult.Success
        }
    }

    private class FakeCatalogCastPort(
        var result: CatalogCastStartResult = CatalogCastStartResult.Started
    ) : CatalogCastPort {
        private val mutablePlaybackEvents = MutableSharedFlow<CatalogCastPlaybackEvent>(extraBufferCapacity = 8)
        override val playbackEvents: SharedFlow<CatalogCastPlaybackEvent> = mutablePlaybackEvents.asSharedFlow()
        var lastRequest: CatalogCastRequest? = null

        override suspend fun startCasting(request: CatalogCastRequest): CatalogCastStartResult {
            lastRequest = request
            startCount += 1
            return result
        }

        var startCount: Int = 0

        suspend fun emit(event: CatalogCastPlaybackEvent) {
            mutablePlaybackEvents.emit(event)
        }
    }

    private class RecordingTraktRatings : TraktRatings {
        private val mutableState = kotlinx.coroutines.flow.MutableStateFlow(TraktRatingsState())
        override val state = mutableState.asStateFlow()
        val writes = mutableListOf<Pair<TraktRatingIdentity, Int>>()
        override suspend fun refresh(type: TraktRatingType, force: Boolean) = TraktRatingRefreshResult.Success(0)
        override suspend fun setRating(identity: TraktRatingIdentity, rating: Int): TraktRatingActionResult {
            writes += identity to rating
            mutableState.value = mutableState.value.copy(ratings = mutableState.value.ratings + (identity to rating))
            return TraktRatingActionResult.Success
        }
    }

    private companion object {
        fun movie(watchProgress: Long = 0L) = Movie(
            id = 10L,
            name = "Movie",
            providerId = 1L,
            posterUrl = "https://example.test/poster.jpg",
            watchProgress = watchProgress
        )
    }
}
