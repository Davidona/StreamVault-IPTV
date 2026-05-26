package com.streamvault.app.ui.screens.movies

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamvault.app.download.OfflineDownloadResult
import com.streamvault.app.download.OfflineDownloadItem
import com.streamvault.app.download.OfflineDownloadStatus
import com.streamvault.app.download.OfflineVodDownloadManager
import com.streamvault.app.download.isKeptDownload
import com.streamvault.app.util.isPlaybackComplete
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.ExternalRatings
import com.streamvault.domain.model.ExternalRatingsLookup
import com.streamvault.domain.model.Movie
import com.streamvault.domain.model.Result
import com.streamvault.domain.repository.ExternalRatingsRepository
import com.streamvault.domain.repository.FavoriteRepository
import com.streamvault.domain.repository.MovieRepository
import com.streamvault.domain.repository.PlaybackHistoryRepository
import com.streamvault.domain.repository.ProviderRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class MovieDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val movieRepository: MovieRepository,
    private val providerRepository: ProviderRepository,
    private val playbackHistoryRepository: PlaybackHistoryRepository,
    private val externalRatingsRepository: ExternalRatingsRepository,
    private val favoriteRepository: FavoriteRepository,
    private val offlineVodDownloadManager: OfflineVodDownloadManager
) : ViewModel() {

    private val movieId: Long = checkNotNull(
        savedStateHandle.get<Long>("movieId")
            ?: savedStateHandle.get<String>("movieId")?.toLongOrNull()
    )

    private val _uiState = MutableStateFlow(MovieDetailUiState())
    val uiState: StateFlow<MovieDetailUiState> = _uiState.asStateFlow()

    init {
        loadMovieDetails()
        observeDownloadStatus()
    }

    private fun observeDownloadStatus() {
        viewModelScope.launch {
            while (true) {
                refreshDownloadStatus()
                delay(2_000)
            }
        }
    }

    private fun refreshDownloadStatus() {
        val movie = _uiState.value.movie ?: return
        val item = offlineVodDownloadManager.findBySourceUrl(movie.streamUrl)
            ?.takeIf { it.status.isKeptDownload }
        _uiState.update { it.copy(downloadItem = item, downloadStatus = item?.status) }
    }

    private fun loadMovieDetails() {
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(isLoading = true) }

                // Derive the provider from the movie's own row so detail/history remain
                // correct even when the globally active provider differs from the opened movie.
                val movieRow = movieRepository.getMovie(movieId)
                val effectiveProviderId = movieRow?.providerId?.takeIf { it > 0L }
                    ?: providerRepository.getActiveProvider().first()?.id
                    ?: run {
                        _uiState.update { it.copy(isLoading = false, error = "No active provider") }
                        return@launch
                    }

                val playbackHistory = playbackHistoryRepository.getPlaybackHistory(
                    contentId = movieId,
                    contentType = ContentType.MOVIE,
                    providerId = effectiveProviderId
                )

                val isFavorite = favoriteRepository.isFavorite(effectiveProviderId, movieId, ContentType.MOVIE)

                when (val result = movieRepository.getMovieDetails(effectiveProviderId, movieId)) {
                    is Result.Success -> _uiState.update {
                        val movie = result.data
                        val movieDurationMs = movie.durationSeconds.takeIf { it > 0 }?.times(1000L) ?: 0L
                        val resumePositionMs = playbackHistory?.resumePositionMs ?: movie.watchProgress
                        val hasResume = resumePositionMs > 5000L && !isPlaybackComplete(
                            progressMs = resumePositionMs,
                            totalDurationMs = playbackHistory?.totalDurationMs?.takeIf { it > 0L } ?: movieDurationMs
                        )
                        it.copy(
                            isLoading = false,
                            movie = result.data.copy(isFavorite = isFavorite),
                            error = null,
                            hasResume = hasResume,
                            resumePositionMs = if (hasResume) resumePositionMs else 0L
                        )
                    }.also {
                        refreshDownloadStatus()
                        loadExternalRatings(result.data)
                        loadRelatedContent(effectiveProviderId)
                    }
                    is Result.Error -> _uiState.update {
                        it.copy(isLoading = false, error = result.message)
                    }
                    is Result.Loading -> _uiState.update {
                        it.copy(isLoading = true)
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, error = e.message ?: "Failed to load movie details")
                }
            }
        }
    }

    fun toggleFavorite() {
        val movie = _uiState.value.movie ?: return
        viewModelScope.launch {
            val newState = !movie.isFavorite
            if (newState) {
                favoriteRepository.addFavorite(movie.providerId, movie.id, ContentType.MOVIE)
            } else {
                favoriteRepository.removeFavorite(movie.providerId, movie.id, ContentType.MOVIE)
            }
            _uiState.update { it.copy(movie = movie.copy(isFavorite = newState)) }
        }
    }

    fun downloadMovie() {
        val movie = _uiState.value.movie ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isDownloadQueued = true, downloadMessage = "Preparing download...") }
            when (val streamResult = movieRepository.getStreamInfo(movie)) {
                is Result.Success -> {
                    val restartPaused = _uiState.value.downloadItem?.status == OfflineDownloadStatus.PAUSED
                    when (val downloadResult = offlineVodDownloadManager.enqueue(movie.name, streamResult.data, movie.streamUrl, restartPaused)) {
                        is OfflineDownloadResult.Queued -> _uiState.update {
                            it.copy(
                                isDownloadQueued = false,
                                downloadItem = null,
                                downloadStatus = OfflineDownloadStatus.PENDING,
                                downloadMessage = "Download queued: ${downloadResult.fileName}"
                            )
                        }
                        is OfflineDownloadResult.AlreadyExists -> _uiState.update {
                            it.copy(
                                isDownloadQueued = false,
                                downloadItem = downloadResult.item,
                                downloadStatus = downloadResult.item.status,
                                downloadMessage = downloadResult.item.status.toExistingDownloadMessage()
                            )
                        }
                        is OfflineDownloadResult.Unsupported -> _uiState.update {
                            it.copy(isDownloadQueued = false, downloadMessage = downloadResult.message)
                        }
                        is OfflineDownloadResult.Error -> _uiState.update {
                            it.copy(isDownloadQueued = false, downloadMessage = downloadResult.message)
                        }
                    }
                }
                is Result.Error -> _uiState.update {
                    it.copy(isDownloadQueued = false, downloadMessage = streamResult.message)
                }
                is Result.Loading -> Unit
            }
        }
    }

    private fun loadExternalRatings(movie: Movie) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingExternalRatings = true) }
            val ratingsResult = externalRatingsRepository.getRatings(
                ExternalRatingsLookup(
                    contentType = ContentType.MOVIE,
                    title = movie.name,
                    releaseYear = movie.year ?: movie.releaseDate,
                    tmdbId = movie.tmdbId
                )
            )
            _uiState.update { currentState ->
                when (ratingsResult) {
                    is Result.Success -> currentState.copy(
                        isLoadingExternalRatings = false,
                        externalRatings = ratingsResult.data
                    )
                    is Result.Error -> currentState.copy(
                        isLoadingExternalRatings = false,
                        externalRatings = ExternalRatings.unavailable()
                    )
                    is Result.Loading -> currentState
                }
            }
        }
    }

    private fun loadRelatedContent(providerId: Long) {
        viewModelScope.launch {
            val related = movieRepository.getRelatedContent(providerId, movieId, limit = 10).first()
            _uiState.update { it.copy(relatedContent = related) }
        }
    }

    private fun OfflineDownloadStatus.toExistingDownloadMessage(): String = when (this) {
        OfflineDownloadStatus.SUCCESSFUL -> "Already downloaded. Open Downloads from the top menu to manage it."
        OfflineDownloadStatus.PAUSED -> "Already in Downloads, but paused. Open Downloads from the top menu to manage it."
        OfflineDownloadStatus.PENDING,
        OfflineDownloadStatus.RUNNING -> "Already downloading. Open Downloads from the top menu to check progress."
        OfflineDownloadStatus.FAILED,
        OfflineDownloadStatus.UNKNOWN -> "Already in Downloads. Open Downloads from the top menu to manage it."
    }
}

data class MovieDetailUiState(
    val isLoading: Boolean = false,
    val movie: Movie? = null,
    val error: String? = null,
    val hasResume: Boolean = false,
    val resumePositionMs: Long = 0L,
    val isLoadingExternalRatings: Boolean = false,
    val externalRatings: ExternalRatings = ExternalRatings.unavailable(),
    val relatedContent: List<Movie> = emptyList(),
    val isDownloadQueued: Boolean = false,
    val downloadItem: OfflineDownloadItem? = null,
    val downloadStatus: OfflineDownloadStatus? = null,
    val downloadMessage: String? = null
)
