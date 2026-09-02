package com.streamvault.feature.catalog.presentation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.google.common.truth.Truth.assertThat
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.DownloadContentType
import com.streamvault.domain.model.DownloadItem
import com.streamvault.domain.model.ExternalRatings
import com.streamvault.domain.model.LegacyProvider
import com.streamvault.domain.model.Movie
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.repository.DownloadManager
import com.streamvault.domain.repository.ExternalRatingsRepository
import com.streamvault.domain.repository.FavoriteRepository
import com.streamvault.domain.repository.MovieRepository
import com.streamvault.domain.repository.PlaybackHistoryRepository
import com.streamvault.domain.repository.ProviderRepository
import com.streamvault.feature.catalog.api.CatalogCastPort
import com.streamvault.feature.catalog.api.CatalogDownloadStarter
import com.streamvault.feature.catalog.api.CatalogMessage
import com.streamvault.feature.catalog.api.CatalogStreamPreparer
import com.streamvault.feature.catalog.api.CatalogUiEvent
import com.streamvault.feature.catalog.presentation.movies.MovieDetailViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
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

class CatalogDownloadBehaviorTest {

    @get:Rule
    val mainDispatcherRule = CatalogMainDispatcherRule()

    @Test
    fun `movie download starts service and emits started message`() {
        runBlocking {
        val movie = movie()
        val starter = RecordingDownloadStarter()
        val downloadManager: DownloadManager = mock()
        whenever(downloadManager.enqueueDownload(any())).thenReturn(
            Result.success(
                DownloadItem(
                    id = "download-42",
                    providerId = movie.providerId,
                    contentType = DownloadContentType.MOVIE,
                    contentId = movie.id,
                    contentName = movie.name,
                    streamUrl = "https://example.test/movie.m3u8"
                )
            )
        )
        val viewModel = createViewModel(movie, downloadManager, starter)

        try {
            withTimeout(5_000L) { viewModel.uiState.first { it.movie != null } }
            val event = async(start = CoroutineStart.UNDISPATCHED) { viewModel.uiEvents.first() }
            viewModel.downloadMovie()

            assertThat(withTimeout(5_000L) { event.await() })
                .isEqualTo(CatalogUiEvent.ShowMessage(CatalogMessage.DownloadStarted))
            assertThat(starter.startedIds).containsExactly("download-42")
        } finally {
            viewModel.viewModelScope.cancel()
        }
        }
    }

    @Test
    fun `movie download reports unavailable URL without starting service`() {
        runBlocking {
        val movie = movie()
        val starter = RecordingDownloadStarter()
        val downloadManager: DownloadManager = mock()
        val viewModel = createViewModel(
            movie = movie,
            downloadManager = downloadManager,
            starter = starter,
            streamResult = Result.error("missing stream")
        )

        try {
            withTimeout(5_000L) { viewModel.uiState.first { it.movie != null } }
            val event = async(start = CoroutineStart.UNDISPATCHED) { viewModel.uiEvents.first() }
            viewModel.downloadMovie()

            assertThat(withTimeout(5_000L) { event.await() })
                .isEqualTo(CatalogUiEvent.ShowMessage(CatalogMessage.DownloadUrlUnavailable))
            assertThat(starter.startedIds).isEmpty()
        } finally {
            viewModel.viewModelScope.cancel()
        }
        }
    }

    private suspend fun createViewModel(
        movie: Movie,
        downloadManager: DownloadManager,
        starter: CatalogDownloadStarter,
        streamResult: Result<StreamInfo> = Result.success(StreamInfo("https://example.test/movie.m3u8"))
    ): MovieDetailViewModel {
        val provider = LegacyProvider(
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
        whenever(movieRepository.getStreamInfo(any())).thenReturn(streamResult)
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
            downloadManager = downloadManager,
            downloadStarter = starter,
            castPort = EmptyCatalogCastPort()
        )
    }

    private class RecordingDownloadStarter : CatalogDownloadStarter {
        val startedIds = mutableListOf<String>()

        override fun startDownload(downloadId: String) {
            startedIds += downloadId
        }
    }

    private class EmptyCatalogCastPort : CatalogCastPort {
        override val playbackEvents: SharedFlow<com.streamvault.feature.catalog.api.CatalogCastPlaybackEvent> =
            MutableSharedFlow<com.streamvault.feature.catalog.api.CatalogCastPlaybackEvent>().asSharedFlow()

        override suspend fun startCasting(
            request: com.streamvault.feature.catalog.api.CatalogCastRequest
        ): com.streamvault.feature.catalog.api.CatalogCastStartResult =
            com.streamvault.feature.catalog.api.CatalogCastStartResult.Unavailable
    }

    private companion object {
        fun movie() = Movie(
            id = 10L,
            name = "Movie",
            providerId = 1L,
            posterUrl = "https://example.test/poster.jpg"
        )
    }
}
