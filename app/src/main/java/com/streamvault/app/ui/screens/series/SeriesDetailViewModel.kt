package com.streamvault.app.ui.screens.series

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamvault.app.download.OfflineDownloadResult
import com.streamvault.app.download.OfflineDownloadItem
import com.streamvault.app.download.OfflineDownloadStatus
import com.streamvault.app.download.OfflineVodDownloadManager
import com.streamvault.app.download.isKeptDownload
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.Episode
import com.streamvault.domain.model.ExternalRatings
import com.streamvault.domain.model.ExternalRatingsLookup
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.Season
import com.streamvault.domain.model.Series
import com.streamvault.domain.repository.ExternalRatingsRepository
import com.streamvault.domain.repository.FavoriteRepository
import com.streamvault.domain.repository.PlaybackHistoryRepository
import com.streamvault.domain.repository.ProviderRepository
import com.streamvault.domain.repository.SeriesRepository
import com.streamvault.domain.util.isPlaybackComplete
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SeriesDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val seriesRepository: SeriesRepository,
    private val providerRepository: ProviderRepository,
    private val playbackHistoryRepository: PlaybackHistoryRepository,
    private val externalRatingsRepository: ExternalRatingsRepository,
    private val favoriteRepository: FavoriteRepository,
    private val offlineVodDownloadManager: OfflineVodDownloadManager
) : ViewModel() {

    private val seriesId: Long = checkNotNull(
        savedStateHandle.get<Long>("seriesId")
            ?: savedStateHandle.get<String>("seriesId")?.toLongOrNull()
    )

    private val _uiState = MutableStateFlow(SeriesDetailUiState())
    val uiState: StateFlow<SeriesDetailUiState> = _uiState.asStateFlow()

    private var providerDetailJob: Job? = null

    init {
        observeDownloadStatuses()
        viewModelScope.launch {
            providerRepository.getActiveProvider().collect { provider ->
                providerDetailJob?.cancel()
                _uiState.value = SeriesDetailUiState(isLoading = true)
                if (provider == null) {
                    _uiState.update { it.copy(isLoading = false, error = "No active provider") }
                    return@collect
                }
                providerDetailJob = launch {
                    val effectiveProviderId = resolveEffectiveProviderId(provider.id)
                    launch {
                        playbackHistoryRepository.getUnwatchedCount(
                            providerId = effectiveProviderId,
                            seriesId = seriesId
                        ).collect { count ->
                            _uiState.update { it.copy(unwatchedEpisodeCount = count) }
                        }
                    }
                    loadSeriesDetailsForProvider(effectiveProviderId)
                }
            }
        }
    }

    private fun observeDownloadStatuses() {
        viewModelScope.launch {
            while (true) {
                refreshDownloadStatuses()
                delay(2_000)
            }
        }
    }

    private fun refreshDownloadStatuses() {
        val episodes = _uiState.value.series?.seasons.orEmpty().flatMap { it.episodes }
        if (episodes.isEmpty()) return
        val items = episodes.mapNotNull { episode ->
            offlineVodDownloadManager.findBySourceUrl(episode.streamUrl)
                ?.takeIf { it.status.isKeptDownload }
                ?.let { episode.id to it }
        }.toMap()
        _uiState.update {
            it.copy(
                episodeDownloadItems = items,
                episodeDownloadStatuses = items.mapValues { entry -> entry.value.status }
            )
        }
    }

    private suspend fun resolveEffectiveProviderId(fallbackProviderId: Long): Long {
        return seriesRepository.getSeriesById(seriesId)?.providerId?.takeIf { it > 0L }
            ?: fallbackProviderId
    }

    private suspend fun loadSeriesDetailsForProvider(providerId: Long) {
        try {
            _uiState.update { it.copy(isLoading = true, error = null) }

            when (val result = seriesRepository.getSeriesDetails(providerId, seriesId)) {
                is Result.Success -> {
                    val isFavoriteDeferred = viewModelScope.async {
                        favoriteRepository.isFavorite(providerId, seriesId, ContentType.SERIES)
                    }
                    loadExternalRatings(result.data)
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            series = result.data.copy(isFavorite = isFavoriteDeferred.await()),
                            selectedSeason = result.data.seasons.firstOrNull(),
                            resumeEpisode = findResumeEpisode(result.data),
                            error = null
                        )
                    }
                    refreshDownloadStatuses()
                }
                is Result.Error -> {
                    _uiState.update {
                        it.copy(isLoading = false, error = result.message)
                    }
                }
                is Result.Loading -> {
                    _uiState.update { it.copy(isLoading = true) }
                }
            }
        } catch (e: Exception) {
            _uiState.update {
                it.copy(
                    isLoading = false,
                    error = e.message ?: "Failed to load series details"
                )
            }
        }
    }

    fun toggleFavorite() {
        val series = _uiState.value.series ?: return
        viewModelScope.launch {
            val newState = !series.isFavorite
            if (newState) {
                favoriteRepository.addFavorite(series.providerId, series.id, ContentType.SERIES)
            } else {
                favoriteRepository.removeFavorite(series.providerId, series.id, ContentType.SERIES)
            }
            _uiState.update { it.copy(series = series.copy(isFavorite = newState)) }
        }
    }

    private fun loadExternalRatings(series: Series) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingExternalRatings = true) }
            val ratingsResult = externalRatingsRepository.getRatings(
                ExternalRatingsLookup(
                    contentType = ContentType.SERIES,
                    title = series.name,
                    releaseYear = series.releaseDate,
                    tmdbId = series.tmdbId
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

    fun selectSeason(season: Season) {
        _uiState.update { it.copy(selectedSeason = season) }
    }

    fun downloadEpisode(episode: Episode) {
        val seriesTitle = _uiState.value.series?.name.orEmpty()
        val title = buildString {
            if (seriesTitle.isNotBlank()) {
                append(seriesTitle)
                append(" - ")
            }
            append("S")
            append(episode.seasonNumber.toString().padStart(2, '0'))
            append("E")
            append(episode.episodeNumber.toString().padStart(2, '0'))
            append(" - ")
            append(episode.title)
        }
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    downloadingEpisodeIds = it.downloadingEpisodeIds + episode.id,
                    downloadMessage = "Preparing download..."
                )
            }
            when (val streamResult = seriesRepository.getEpisodeStreamInfo(episode)) {
                is Result.Success -> {
                    val restartPaused = _uiState.value.episodeDownloadItems[episode.id]?.status == OfflineDownloadStatus.PAUSED
                    val downloadResult = offlineVodDownloadManager.enqueue(
                        title = title,
                        streamInfo = streamResult.data,
                        lookupUrl = episode.streamUrl,
                        restartPaused = restartPaused
                    )
                    val message = when (downloadResult) {
                        is OfflineDownloadResult.Queued -> "Download queued: ${downloadResult.fileName}"
                        is OfflineDownloadResult.AlreadyExists -> downloadResult.item.status.toExistingDownloadMessage()
                        is OfflineDownloadResult.Unsupported -> downloadResult.message
                        is OfflineDownloadResult.Error -> downloadResult.message
                    }
                    _uiState.update {
                        it.copy(
                            downloadingEpisodeIds = it.downloadingEpisodeIds - episode.id,
                            episodeDownloadStatuses = when (downloadResult) {
                                is OfflineDownloadResult.Queued -> it.episodeDownloadStatuses + (episode.id to OfflineDownloadStatus.PENDING)
                                is OfflineDownloadResult.AlreadyExists -> it.episodeDownloadStatuses + (episode.id to downloadResult.item.status)
                                else -> it.episodeDownloadStatuses
                            },
                            episodeDownloadItems = when (downloadResult) {
                                is OfflineDownloadResult.AlreadyExists -> it.episodeDownloadItems + (episode.id to downloadResult.item)
                                else -> it.episodeDownloadItems
                            },
                            downloadMessage = message
                        )
                    }
                }
                is Result.Error -> _uiState.update {
                    it.copy(
                        downloadingEpisodeIds = it.downloadingEpisodeIds - episode.id,
                        downloadMessage = streamResult.message
                    )
                }
                is Result.Loading -> Unit
            }
        }
    }


    private fun findResumeEpisode(series: Series): Episode? {
        val ordered = series.seasons
            .sortedBy { it.seasonNumber }
            .flatMap { season -> season.episodes.sortedBy { it.episodeNumber } }
        // Prefer the most-recently-watched in-progress episode
        val inProgress = ordered
            .filter { ep ->
                ep.watchProgress > 5000L &&
                    !isPlaybackComplete(ep.watchProgress, ep.durationSeconds.toLong() * 1000L)
            }
            .maxByOrNull { it.lastWatchedAt }
        if (inProgress != null) return inProgress
        // Fall back to the first episode that has never been started
        return ordered.firstOrNull { ep -> ep.lastWatchedAt == 0L }
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

data class SeriesDetailUiState(
    val isLoading: Boolean = false,
    val series: Series? = null,
    val selectedSeason: Season? = null,
    val resumeEpisode: Episode? = null,
    val unwatchedEpisodeCount: Int = 0,
    val error: String? = null,
    val isLoadingExternalRatings: Boolean = false,
    val externalRatings: ExternalRatings = ExternalRatings.unavailable(),
    val downloadingEpisodeIds: Set<Long> = emptySet(),
    val episodeDownloadStatuses: Map<Long, OfflineDownloadStatus> = emptyMap(),
    val episodeDownloadItems: Map<Long, OfflineDownloadItem> = emptyMap(),
    val downloadMessage: String? = null
)
