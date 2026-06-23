package com.streamvault.app.ui.screens.movies

import android.content.Context
import android.widget.Toast
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamvault.app.R
import com.streamvault.app.navigation.MOVIE_DETAIL_PRESENTATION_HINT_KEY
import com.streamvault.app.plugins.StreamVaultPluginManager
import com.streamvault.app.service.DownloadForegroundService
import com.streamvault.app.util.isPlaybackComplete
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.DownloadContentType
import com.streamvault.domain.model.DownloadRequest
import com.streamvault.domain.model.ExternalRatings
import com.streamvault.domain.model.ExternalRatingsLookup
import com.streamvault.domain.model.MovieDetailPresentationHint
import com.streamvault.domain.model.Movie
import com.streamvault.domain.model.Result
import com.streamvault.domain.repository.DownloadManager
import com.streamvault.domain.repository.ExternalRatingsRepository
import com.streamvault.domain.repository.FavoriteRepository
import com.streamvault.domain.repository.MovieRepository
import com.streamvault.domain.repository.PlaybackHistoryRepository
import com.streamvault.domain.repository.ProviderRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
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
    private val preferencesRepository: PreferencesRepository,
    private val pluginManager: StreamVaultPluginManager,
    private val downloadManager: DownloadManager,
    private val castManager: com.streamvault.app.cast.CastManager
) : ViewModel() {

    private val movieId: Long = checkNotNull(
        savedStateHandle.get<Long>("movieId")
            ?: savedStateHandle.get<String>("movieId")?.toLongOrNull()
    )
    private val knownPresentationHint: MovieDetailPresentationHint? =
        savedStateHandle[MOVIE_DETAIL_PRESENTATION_HINT_KEY]

    private val _uiState = MutableStateFlow(MovieDetailUiState())
    val uiState: StateFlow<MovieDetailUiState> = _uiState.asStateFlow()

    init {
        loadMovieDetails()
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

                when (val result = movieRepository.getMovieDetails(effectiveProviderId, movieId, knownPresentationHint)) {
                    is Result.Success -> {
                        applyLoadedMovie(effectiveProviderId, result.data)
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

    fun selectMovieVariant(rawMovieId: Long) {
        val currentMovie = _uiState.value.movie ?: return
        if (rawMovieId <= 0L || rawMovieId == currentMovie.id) return
        viewModelScope.launch {
            currentMovie.logicalGroupId?.takeIf { it.isNotBlank() }?.let { logicalGroupId ->
                preferencesRepository.setPreferredVodVariant(currentMovie.providerId, logicalGroupId, rawMovieId)
            }
            when (val result = movieRepository.getMovieDetails(currentMovie.providerId, rawMovieId, knownPresentationHint)) {
                is Result.Success -> {
                    applyLoadedMovie(currentMovie.providerId, result.data)
                    loadExternalRatings(result.data)
                }
                is Result.Error -> _uiState.update { it.copy(error = result.message) }
                Result.Loading -> Unit
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

    suspend fun resolveCopyStreamUrl(): Result<String> {
        val movie = _uiState.value.movie ?: return Result.error("Could not resolve stream URL")
        val streamInfo = when (val result = movieRepository.getStreamInfo(movie)) {
            is Result.Success -> result.data
            is Result.Error -> return Result.error(result.message, result.exception)
            Result.Loading -> return Result.error("Could not resolve stream URL")
        }
        return when (val prepared = pluginManager.preparePlaybackStreamInfo(streamInfo)) {
            is Result.Success -> prepared.data.url.trim().takeIf { it.isNotBlank() }
                ?.let { Result.success(it) }
                ?: Result.error("Could not resolve stream URL")
            is Result.Error -> Result.error(prepared.message, prepared.exception)
            Result.Loading -> Result.error("Could not resolve stream URL")
        }
    }

    fun downloadMovie(context: Context) {
        val movie = _uiState.value.movie ?: return
        viewModelScope.launch {
            val resolvedUrl = resolveCopyStreamUrl()
            when (resolvedUrl) {
                is Result.Success -> {
                    val request = DownloadRequest(
                        providerId = movie.providerId,
                        contentType = DownloadContentType.MOVIE,
                        contentId = movie.id,
                        contentName = movie.name,
                        streamUrl = resolvedUrl.data,
                        sourceStreamUrl = movie.streamUrl,
                        sourceStreamId = movie.streamId.takeIf { it > 0L },
                        containerExtension = movie.containerExtension,
                        posterUrl = movie.posterUrl
                    )
                    val result = downloadManager.enqueueDownload(request)
                    when (result) {
                        is Result.Success -> {
                            DownloadForegroundService.startDownload(context, result.data.id)
                            Toast.makeText(context, context.getString(R.string.download_started), Toast.LENGTH_SHORT).show()
                        }
                        is Result.Error ->
                            Toast.makeText(context, context.getString(R.string.download_failed), Toast.LENGTH_SHORT).show()
                        Result.Loading -> Unit
                    }
                }
                is Result.Error ->
                    Toast.makeText(context, context.getString(R.string.download_error_no_url), Toast.LENGTH_SHORT).show()
                Result.Loading -> Unit
            }
    }
    
    fun castMovie(context: Context) {
        val movie = _uiState.value.movie ?: return
        viewModelScope.launch {
            val resolvedUrl = resolveCopyStreamUrl()
            when (resolvedUrl) {
                is Result.Success -> {
                    val request = com.streamvault.app.cast.CastMediaRequest(
                        url = resolvedUrl.data,
                        title = movie.name,
                        subtitle = movie.director ?: movie.year,
                        artworkUrl = movie.posterUrl ?: movie.backdropUrl,
                        isLive = false,
                        startPositionMs = _uiState.value.resumePositionMs
                    )
                    val result = castManager.startCasting(request)
                    if (result == com.streamvault.app.cast.CastStartResult.ROUTE_SELECTION_REQUIRED) {
                        context.startActivity(
                            android.content.Intent(context, com.streamvault.app.cast.CastRouteChooserActivity::class.java)
                        )
                    } else if (result == com.streamvault.app.cast.CastStartResult.UNAVAILABLE) {
                        Toast.makeText(context, "Google Cast indisponível", Toast.LENGTH_SHORT).show()
                    }
                }
                is Result.Error -> Toast.makeText(context, "Erro ao obter URL do filme", Toast.LENGTH_SHORT).show()
                Result.Loading -> Unit
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

    private suspend fun applyLoadedMovie(providerId: Long, movie: Movie) {
        val playbackHistory = playbackHistoryRepository.getPlaybackHistory(
            contentId = movie.id,
            contentType = ContentType.MOVIE,
            providerId = providerId
        )
        val isFavorite = favoriteRepository.isFavorite(providerId, movie.id, ContentType.MOVIE)
        val movieDurationMs = movie.durationSeconds.takeIf { it > 0 }?.times(1000L) ?: 0L
        val resumePositionMs = playbackHistory?.resumePositionMs ?: movie.watchProgress
        val hasResume = resumePositionMs > 5000L && !isPlaybackComplete(
            progressMs = resumePositionMs,
            totalDurationMs = playbackHistory?.totalDurationMs?.takeIf { it > 0L } ?: movieDurationMs
        )
        _uiState.update {
            it.copy(
                isLoading = false,
                movie = movie.copy(isFavorite = isFavorite),
                error = null,
                hasResume = hasResume,
                resumePositionMs = if (hasResume) resumePositionMs else 0L
            )
        }
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
    val relatedContent: List<Movie> = emptyList()
)
