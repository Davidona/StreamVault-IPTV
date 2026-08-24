package com.streamvault.app.ui.screens.series

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.google.common.truth.Truth.assertThat
import com.streamvault.app.MainDispatcherRule
import com.streamvault.app.R
import com.streamvault.feature.playback.R as PlaybackFeatureR
import com.streamvault.feature.playback.api.CastMediaRequest
import com.streamvault.feature.playback.cast.CastMediaRequestFactory
import com.streamvault.feature.playback.cast.CastPlaybackEvent
import com.streamvault.feature.playback.cast.CastPlaybackCoordinator
import com.streamvault.feature.playback.cast.CastStartResult
import com.streamvault.feature.playback.cast.CastUiEvent
import com.streamvault.app.plugins.StreamVaultPluginManager
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.domain.model.Episode
import com.streamvault.domain.model.ExternalRatings
import com.streamvault.domain.model.LegacyProvider as Provider
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.Season
import com.streamvault.domain.model.Series
import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.repository.DownloadManager
import com.streamvault.domain.repository.ExternalRatingsRepository
import com.streamvault.domain.repository.FavoriteRepository
import com.streamvault.domain.repository.PlaybackHistoryRepository
import com.streamvault.domain.repository.ProviderRepository
import com.streamvault.domain.repository.SeriesRepository
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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

class SeriesDetailViewModelCastingTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `castEpisode uses selected episode and preserves watch progress`() = runBlocking {
        val selectedEpisode = episode(id = 22L, episodeNumber = 2, watchProgress = 65_000L)
        val series = series(episodes = listOf(episode(id = 21L, episodeNumber = 1), selectedEpisode))
        val coordinator = FakeCastPlaybackCoordinator(CastStartResult.ROUTE_SELECTION_REQUIRED)
        val viewModel = createViewModel(series = series, coordinator = coordinator)

        try {
            val event = async(start = CoroutineStart.UNDISPATCHED) { viewModel.castEvents.first() }
            viewModel.castEpisode(selectedEpisode)

            assertThat(withTimeout(5_000L) { event.await() }).isEqualTo(CastUiEvent.OpenRouteChooser)
            assertThat(coordinator.lastRequest?.title).isEqualTo("Series - S1E2")
            assertThat(coordinator.lastRequest?.subtitle).isEqualTo("Episode 2")
            assertThat(coordinator.lastRequest?.startPositionMs).isEqualTo(65_000L)
            assertThat(viewModel.uiState.value.isCasting).isTrue()
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    @Test
    fun `castEpisode emits unavailable message from coordinator result`() = runBlocking {
        val coordinator = FakeCastPlaybackCoordinator(CastStartResult.UNAVAILABLE)
        val viewModel = createViewModel(coordinator = coordinator)

        try {
            val event = async(start = CoroutineStart.UNDISPATCHED) { viewModel.castEvents.first() }
            viewModel.castEpisode(episode())

            assertThat(withTimeout(5_000L) { event.await() })
                .isEqualTo(CastUiEvent.ShowMessage(PlaybackFeatureR.string.cast_unavailable))
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    @Test
    fun `castEpisode reports session failure after route selection`() = runBlocking {
        val coordinator = FakeCastPlaybackCoordinator(CastStartResult.ROUTE_SELECTION_REQUIRED)
        val viewModel = createViewModel(coordinator = coordinator)

        try {
            val routeEvent = async(start = CoroutineStart.UNDISPATCHED) { viewModel.castEvents.first() }
            viewModel.castEpisode(episode())
            assertThat(withTimeout(5_000L) { routeEvent.await() }).isEqualTo(CastUiEvent.OpenRouteChooser)

            val lifecycleEvent = async(start = CoroutineStart.UNDISPATCHED) { viewModel.castEvents.first() }
            coordinator.emit(CastPlaybackEvent.SessionStartFailed(errorCode = 7))

            assertThat(withTimeout(5_000L) { lifecycleEvent.await() })
                .isEqualTo(CastUiEvent.ShowMessage(PlaybackFeatureR.string.cast_session_failed))
            assertThat(viewModel.uiState.value.isCasting).isFalse()
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    @Test
    fun `castEpisode resets pending state when route selection is cancelled`() = runBlocking {
        val coordinator = FakeCastPlaybackCoordinator(CastStartResult.ROUTE_SELECTION_REQUIRED)
        val viewModel = createViewModel(coordinator = coordinator)

        try {
            val routeEvent = async(start = CoroutineStart.UNDISPATCHED) { viewModel.castEvents.first() }
            viewModel.castEpisode(episode())
            assertThat(withTimeout(5_000L) { routeEvent.await() }).isEqualTo(CastUiEvent.OpenRouteChooser)
            assertThat(viewModel.uiState.value.isCasting).isTrue()

            coordinator.emit(CastPlaybackEvent.RouteSelectionCancelled)

            withTimeout(5_000L) { viewModel.uiState.first { !it.isCasting } }
            assertThat(viewModel.uiState.value.isCasting).isFalse()
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    @Test
    fun `castEpisode ignores repeated launches while route selection is pending`() = runBlocking {
        val coordinator = FakeCastPlaybackCoordinator(CastStartResult.ROUTE_SELECTION_REQUIRED)
        val viewModel = createViewModel(coordinator = coordinator)

        try {
            val routeEvent = async(start = CoroutineStart.UNDISPATCHED) { viewModel.castEvents.first() }
            viewModel.castEpisode(episode())
            assertThat(withTimeout(5_000L) { routeEvent.await() }).isEqualTo(CastUiEvent.OpenRouteChooser)

            viewModel.castEpisode(episode(id = 22L, episodeNumber = 2))

            assertThat(coordinator.startCount).isEqualTo(1)
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    @Test
    fun `castEpisode explains streams that need proxy rewrite when cast remains unsupported`() = runBlocking {
        val coordinator = FakeCastPlaybackCoordinator(CastStartResult.UNSUPPORTED)
        val viewModel = createViewModel(
            streamInfo = StreamInfo(
                url = "https://example.test/episode.m3u8",
                proxyHost = "proxy.example.test",
                proxyPort = 8080
            ),
            coordinator = coordinator
        )

        try {
            val event = async(start = CoroutineStart.UNDISPATCHED) { viewModel.castEvents.first() }
            viewModel.castEpisode(episode())

            assertThat(withTimeout(5_000L) { event.await() })
                .isEqualTo(CastUiEvent.ShowMessage(PlaybackFeatureR.string.cast_proxy_unsupported))
            assertThat(coordinator.lastRequest?.requiresCastRewrite).isTrue()
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    private suspend fun createViewModel(
        series: Series = series(),
        streamInfo: StreamInfo = StreamInfo(url = "https://example.test/episode.m3u8"),
        coordinator: FakeCastPlaybackCoordinator = FakeCastPlaybackCoordinator()
    ): SeriesDetailViewModel {
        val provider = Provider(
            id = series.providerId,
            name = "Provider",
            type = ProviderType.XTREAM_CODES,
            serverUrl = "https://provider.test"
        )
        val seriesRepository: SeriesRepository = mock()
        val providerRepository: ProviderRepository = mock()
        val playbackHistoryRepository: PlaybackHistoryRepository = mock()
        val externalRatingsRepository: ExternalRatingsRepository = mock()
        val favoriteRepository: FavoriteRepository = mock()

        whenever(providerRepository.getActiveProvider()).thenReturn(flowOf(provider))
        whenever(seriesRepository.getSeriesById(series.id)).thenReturn(series)
        whenever(seriesRepository.getSeriesDetails(eq(provider.id), eq(series.id), anyOrNull()))
            .thenReturn(Result.success(series))
        whenever(seriesRepository.getEpisodeStreamInfo(any()))
            .thenReturn(Result.success(streamInfo))
        whenever(favoriteRepository.isFavorite(eq(provider.id), eq(series.id), any()))
            .thenReturn(false)
        whenever(playbackHistoryRepository.getUnwatchedCount(eq(provider.id), eq(series.id)))
            .thenReturn(flowOf(0))
        whenever(externalRatingsRepository.getRatings(any()))
            .thenReturn(Result.success(ExternalRatings.unavailable()))

        return SeriesDetailViewModel(
            savedStateHandle = SavedStateHandle(mapOf("seriesId" to series.id)),
            seriesRepository = seriesRepository,
            providerRepository = providerRepository,
            playbackHistoryRepository = playbackHistoryRepository,
            externalRatingsRepository = externalRatingsRepository,
            favoriteRepository = favoriteRepository,
            preferencesRepository = mock<PreferencesRepository>(),
            pluginManager = mock<StreamVaultPluginManager>(),
            downloadManager = mock<DownloadManager>(),
            castMediaRequestFactory = CastMediaRequestFactory(),
            castPlaybackCoordinator = coordinator
        )
    }

    private class FakeCastPlaybackCoordinator(
        var result: CastStartResult = CastStartResult.STARTED
    ) : CastPlaybackCoordinator {
        private val mutablePlaybackEvents = MutableSharedFlow<CastPlaybackEvent>(extraBufferCapacity = 8)
        override val playbackEvents: SharedFlow<CastPlaybackEvent> = mutablePlaybackEvents.asSharedFlow()
        var lastRequest: CastMediaRequest? = null

        override suspend fun startCasting(request: CastMediaRequest): CastStartResult {
            lastRequest = request
            startCount += 1
            return result
        }

        var startCount: Int = 0

        suspend fun emit(event: CastPlaybackEvent) {
            mutablePlaybackEvents.emit(event)
        }
    }

    private companion object {
        fun series(episodes: List<Episode> = listOf(episode())) = Series(
            id = 30L,
            name = "Series",
            providerId = 1L,
            posterUrl = "https://example.test/series.jpg",
            seasons = listOf(Season(seasonNumber = 1, episodes = episodes))
        )

        fun episode(
            id: Long = 21L,
            episodeNumber: Int = 1,
            watchProgress: Long = 0L
        ) = Episode(
            id = id,
            title = "Episode $episodeNumber",
            episodeNumber = episodeNumber,
            seasonNumber = 1,
            providerId = 1L,
            seriesId = 30L,
            watchProgress = watchProgress
        )
    }
}
