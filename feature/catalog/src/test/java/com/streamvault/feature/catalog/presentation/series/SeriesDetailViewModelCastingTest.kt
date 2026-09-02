package com.streamvault.feature.catalog.presentation.series

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.google.common.truth.Truth.assertThat
import com.streamvault.feature.catalog.api.CatalogCastPlaybackEvent
import com.streamvault.feature.catalog.api.CatalogCastPort
import com.streamvault.feature.catalog.api.CatalogCastRequest
import com.streamvault.feature.catalog.api.CatalogCastStartResult
import com.streamvault.feature.catalog.api.CatalogDownloadStarter
import com.streamvault.feature.catalog.api.CatalogMessage
import com.streamvault.feature.catalog.api.CatalogStreamPreparer
import com.streamvault.feature.catalog.api.CatalogUiEvent
import com.streamvault.feature.catalog.presentation.CatalogMainDispatcherRule
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
    val mainDispatcherRule = CatalogMainDispatcherRule()

    @Test
    fun `castEpisode uses selected episode and preserves watch progress`() = runBlocking {
        val selectedEpisode = episode(id = 22L, episodeNumber = 2, watchProgress = 65_000L)
        val series = series(episodes = listOf(episode(id = 21L, episodeNumber = 1), selectedEpisode))
        val castPort = FakeCatalogCastPort(CatalogCastStartResult.RouteSelectionRequired)
        val viewModel = createViewModel(series = series, castPort = castPort)

        try {
            val event = async(start = CoroutineStart.UNDISPATCHED) { viewModel.castEvents.first() }
            viewModel.castEpisode(selectedEpisode)

            assertThat(withTimeout(5_000L) { event.await() }).isEqualTo(CatalogUiEvent.OpenCastRouteChooser)
            assertThat(castPort.lastRequest?.title).isEqualTo("Series - S1E2")
            assertThat(castPort.lastRequest?.subtitle).isEqualTo("Episode 2")
            assertThat(castPort.lastRequest?.startPositionMs).isEqualTo(65_000L)
            assertThat(viewModel.uiState.value.isCasting).isTrue()
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    @Test
    fun `castEpisode emits unavailable message from coordinator result`() = runBlocking {
        val castPort = FakeCatalogCastPort(CatalogCastStartResult.Unavailable)
        val viewModel = createViewModel(castPort = castPort)

        try {
            val event = async(start = CoroutineStart.UNDISPATCHED) { viewModel.castEvents.first() }
            viewModel.castEpisode(episode())

            assertThat(withTimeout(5_000L) { event.await() })
                .isEqualTo(CatalogUiEvent.ShowMessage(CatalogMessage.CastUnavailable))
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    @Test
    fun `castEpisode reports session failure after route selection`() = runBlocking {
        val castPort = FakeCatalogCastPort(CatalogCastStartResult.RouteSelectionRequired)
        val viewModel = createViewModel(castPort = castPort)

        try {
            val routeEvent = async(start = CoroutineStart.UNDISPATCHED) { viewModel.castEvents.first() }
            viewModel.castEpisode(episode())
            assertThat(withTimeout(5_000L) { routeEvent.await() }).isEqualTo(CatalogUiEvent.OpenCastRouteChooser)

            val lifecycleEvent = async(start = CoroutineStart.UNDISPATCHED) { viewModel.castEvents.first() }
            castPort.emit(CatalogCastPlaybackEvent.Finished(false, CatalogMessage.CastSessionFailed))

            assertThat(withTimeout(5_000L) { lifecycleEvent.await() })
                .isEqualTo(CatalogUiEvent.ShowMessage(CatalogMessage.CastSessionFailed))
            assertThat(viewModel.uiState.value.isCasting).isFalse()
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    @Test
    fun `castEpisode resets pending state when route selection is cancelled`() = runBlocking {
        val castPort = FakeCatalogCastPort(CatalogCastStartResult.RouteSelectionRequired)
        val viewModel = createViewModel(castPort = castPort)

        try {
            val routeEvent = async(start = CoroutineStart.UNDISPATCHED) { viewModel.castEvents.first() }
            viewModel.castEpisode(episode())
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
    fun `castEpisode ignores repeated launches while route selection is pending`() = runBlocking {
        val castPort = FakeCatalogCastPort(CatalogCastStartResult.RouteSelectionRequired)
        val viewModel = createViewModel(castPort = castPort)

        try {
            val routeEvent = async(start = CoroutineStart.UNDISPATCHED) { viewModel.castEvents.first() }
            viewModel.castEpisode(episode())
            assertThat(withTimeout(5_000L) { routeEvent.await() }).isEqualTo(CatalogUiEvent.OpenCastRouteChooser)

            viewModel.castEpisode(episode(id = 22L, episodeNumber = 2))

            assertThat(castPort.startCount).isEqualTo(1)
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    @Test
    fun `castEpisode explains streams that need proxy rewrite when cast remains unsupported`() = runBlocking {
        val castPort = FakeCatalogCastPort(CatalogCastStartResult.Unsupported(CatalogMessage.CastUnsupported))
        val streamInfo = StreamInfo(
            url = "https://example.test/episode.m3u8",
            proxyHost = "proxy.example.test",
            proxyPort = 8080
        )
        val viewModel = createViewModel(
            streamInfo = streamInfo,
            castPort = castPort
        )

        try {
            val event = async(start = CoroutineStart.UNDISPATCHED) { viewModel.castEvents.first() }
            viewModel.castEpisode(episode())

            assertThat(withTimeout(5_000L) { event.await() })
                .isEqualTo(CatalogUiEvent.ShowMessage(CatalogMessage.CastUnsupported))
            assertThat(castPort.lastRequest?.streamInfo).isEqualTo(streamInfo)
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    private suspend fun createViewModel(
        series: Series = series(),
        streamInfo: StreamInfo = StreamInfo(url = "https://example.test/episode.m3u8"),
        castPort: FakeCatalogCastPort = FakeCatalogCastPort()
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
            streamPreparer = object : CatalogStreamPreparer {
                override suspend fun prepare(streamInfo: StreamInfo): Result<StreamInfo> =
                    Result.success(streamInfo)
            },
            downloadManager = mock<DownloadManager>(),
            downloadStarter = object : CatalogDownloadStarter {
                override fun startDownload(downloadId: String) = Unit
            },
            castPort = castPort
        )
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
