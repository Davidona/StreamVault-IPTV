package com.streamvault.feature.catalog.presentation.series

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamvault.feature.catalog.api.CatalogCastPlaybackEvent
import com.streamvault.feature.catalog.api.CatalogCastPort
import com.streamvault.feature.catalog.api.CatalogCastRequest
import com.streamvault.feature.catalog.api.CatalogCastStartResult
import com.streamvault.feature.catalog.api.CatalogDownloadStarter
import com.streamvault.feature.catalog.api.CatalogMessage
import com.streamvault.feature.catalog.api.CatalogStreamPreparer
import com.streamvault.feature.catalog.api.CatalogUiEvent
import com.streamvault.feature.catalog.navigation.CatalogRoutePatterns
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.DownloadContentType
import com.streamvault.domain.model.DownloadRequest
import com.streamvault.domain.model.Episode
import com.streamvault.domain.model.ExternalRatings
import com.streamvault.domain.model.ExternalRatingsLookup
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.Season
import com.streamvault.domain.model.Series
import com.streamvault.domain.model.SeriesDetailPresentationHint
import com.streamvault.domain.repository.DownloadManager
import com.streamvault.domain.repository.ExternalRatingsRepository
import com.streamvault.domain.repository.FavoriteRepository
import com.streamvault.domain.repository.PlaybackHistoryRepository
import com.streamvault.domain.repository.ProviderRepository
import com.streamvault.domain.repository.SeriesRepository
import com.streamvault.domain.util.isPlaybackComplete
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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
    private val preferencesRepository: PreferencesRepository,
    private val streamPreparer: CatalogStreamPreparer,
    private val downloadManager: DownloadManager,
    private val downloadStarter: CatalogDownloadStarter,
    private val castPort: CatalogCastPort
) : ViewModel() {

    private val seriesId: Long = checkNotNull(
        savedStateHandle.get<Long>("seriesId")
            ?: savedStateHandle.get<String>("seriesId")?.toLongOrNull()
    )
    private val knownPresentationHint: SeriesDetailPresentationHint? =
        savedStateHandle[CatalogRoutePatterns.SERIES_DETAIL_PRESENTATION_HINT_KEY]

    private val _uiState = MutableStateFlow(SeriesDetailUiState())
    val uiState: StateFlow<SeriesDetailUiState> = _uiState.asStateFlow()

    private val _uiEvents = MutableSharedFlow<CatalogUiEvent>()
    val uiEvents: SharedFlow<CatalogUiEvent> = _uiEvents.asSharedFlow()
    val castEvents: SharedFlow<CatalogUiEvent> = uiEvents

    private var castPlaybackReportMode = CastPlaybackReportMode.NONE

    private var providerDetailJob: Job? = null
    private var unwatchedCountJob: Job? = null

    init {
        observeCastPlaybackEvents()
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
                    loadSeriesDetailsForProvider(effectiveProviderId, seriesId)
                }
            }
        }
    }

    private suspend fun resolveEffectiveProviderId(fallbackProviderId: Long): Long {
        return seriesRepository.getSeriesById(seriesId)?.providerId?.takeIf { it > 0L }
            ?: fallbackProviderId
    }

    private suspend fun loadSeriesDetailsForProvider(providerId: Long, requestedSeriesId: Long) {
        try {
            _uiState.update { it.copy(isLoading = true, error = null) }

            when (val result = seriesRepository.getSeriesDetails(providerId, requestedSeriesId, knownPresentationHint)) {
                is Result.Success -> {
                    val isFavoriteDeferred = viewModelScope.async {
                        favoriteRepository.isFavorite(providerId, result.data.id, ContentType.SERIES)
                    }
                    loadExternalRatings(result.data)
                    startUnwatchedCountCollection(providerId, result.data.id)
                    val selectedSeasonNumber = _uiState.value.selectedSeason?.seasonNumber
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            series = result.data.copy(isFavorite = isFavoriteDeferred.await()),
                            selectedSeason = result.data.seasons.firstOrNull { season ->
                                season.seasonNumber == selectedSeasonNumber
                            } ?: result.data.seasons.firstOrNull(),
                            resumeEpisode = findResumeEpisode(result.data),
                            error = null
                        )
                    }
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

    fun selectSeriesVariant(rawSeriesId: Long) {
        val currentSeries = _uiState.value.series ?: return
        if (rawSeriesId <= 0L || rawSeriesId == currentSeries.id) return
        viewModelScope.launch {
            currentSeries.logicalGroupId.takeIf { it.isNotBlank() }?.let { logicalGroupId ->
                preferencesRepository.setPreferredVodVariant(currentSeries.providerId, logicalGroupId, rawSeriesId)
            }
            loadSeriesDetailsForProvider(currentSeries.providerId, rawSeriesId)
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

    private fun startUnwatchedCountCollection(providerId: Long, selectedSeriesId: Long) {
        unwatchedCountJob?.cancel()
        unwatchedCountJob = viewModelScope.launch {
            playbackHistoryRepository.getUnwatchedCount(
                providerId = providerId,
                seriesId = selectedSeriesId
            ).collect { count ->
                _uiState.update { it.copy(unwatchedEpisodeCount = count) }
            }
        }
    }

    suspend fun resolveCopyStreamUrl(episode: Episode): Result<String> {
        val streamInfo = when (val result = seriesRepository.getEpisodeStreamInfo(episode)) {
            is Result.Success -> result.data
            is Result.Error -> return Result.error(result.message, result.exception)
            Result.Loading -> return Result.error("Could not resolve stream URL")
        }
        return when (val prepared = streamPreparer.prepare(streamInfo)) {
            is Result.Success -> prepared.data.url.trim().takeIf { it.isNotBlank() }
                ?.let { Result.success(it) }
                ?: Result.error("Could not resolve stream URL")
            is Result.Error -> Result.error(prepared.message, prepared.exception)
            Result.Loading -> Result.error("Could not resolve stream URL")
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
        val series = _uiState.value.series ?: return
        viewModelScope.launch {
            val resolvedUrl = resolveCopyStreamUrl(episode)
            when (resolvedUrl) {
                is Result.Success -> {
                    val request = DownloadRequest(
                        providerId = episode.providerId,
                        contentType = DownloadContentType.SERIES_EPISODE,
                        contentId = episode.id,
                        contentName = episode.title,
                        streamUrl = resolvedUrl.data,
                        sourceStreamUrl = episode.streamUrl,
                        sourceStreamId = episode.episodeId.takeIf { it > 0L } ?: episode.id,
                        containerExtension = episode.containerExtension,
                        posterUrl = episode.coverUrl,
                        seriesId = series.id,
                        seasonNumber = episode.seasonNumber,
                        episodeNumber = episode.episodeNumber
                    )
                    val result = downloadManager.enqueueDownload(request)
                    when (result) {
                        is Result.Success -> {
                            downloadStarter.startDownload(result.data.id)
                            _uiEvents.emit(CatalogUiEvent.ShowMessage(CatalogMessage.DownloadStarted))
                        }
                        is Result.Error ->
                            _uiEvents.emit(CatalogUiEvent.ShowMessage(CatalogMessage.DownloadFailed))
                        Result.Loading -> Unit
                    }
                }
                is Result.Error ->
                    _uiEvents.emit(CatalogUiEvent.ShowMessage(CatalogMessage.DownloadUrlUnavailable))
                Result.Loading -> Unit
            }
        }
    }

    fun castResumeEpisode() {
        _uiState.value.resumeEpisode?.let(::castEpisode)
    }

    fun castEpisode(episode: Episode) {
        if (_uiState.value.isCasting) return
        val series = _uiState.value.series ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isCasting = true) }
            castPlaybackReportMode = CastPlaybackReportMode.NONE
            var keepCastingPending = false
            try {
                val streamInfo = when (val result = seriesRepository.getEpisodeStreamInfo(episode)) {
                    is Result.Success -> result.data
                    is Result.Error -> {
                        _uiEvents.emit(CatalogUiEvent.ShowMessage(CatalogMessage.CastItemUnavailable))
                        return@launch
                    }
                    Result.Loading -> {
                        _uiEvents.emit(CatalogUiEvent.ShowMessage(CatalogMessage.CastItemUnavailable))
                        return@launch
                    }
                }
                val request = CatalogCastRequest(
                    streamInfo = streamInfo,
                    title = "${series.name} - S${episode.seasonNumber}E${episode.episodeNumber}",
                    subtitle = episode.title,
                    artworkUrl = episode.coverUrl ?: series.posterUrl ?: series.backdropUrl,
                    startPositionMs = episode.watchProgress
                )
                keepCastingPending = emitCastResult(castPort.startCasting(request))
            } finally {
                if (!keepCastingPending) {
                    _uiState.update { it.copy(isCasting = false) }
                }
            }
        }
    }

    private fun observeCastPlaybackEvents() {
        viewModelScope.launch {
            castPort.playbackEvents.collect { event ->
                handleCastPlaybackEvent(event)
            }
        }
    }

    private suspend fun handleCastPlaybackEvent(event: CatalogCastPlaybackEvent) {
        val reportMode = castPlaybackReportMode
        if (reportMode == CastPlaybackReportMode.NONE) return
        if (event is CatalogCastPlaybackEvent.RouteSelectionCancelled) {
            castPlaybackReportMode = CastPlaybackReportMode.NONE
            _uiState.update { it.copy(isCasting = false) }
            return
        }
        val isSuccess = event is CatalogCastPlaybackEvent.Finished && event.succeeded
        if (isSuccess && reportMode == CastPlaybackReportMode.FAILURES_ONLY) {
            castPlaybackReportMode = CastPlaybackReportMode.NONE
            _uiState.update { it.copy(isCasting = false) }
            return
        }
        castPlaybackReportMode = CastPlaybackReportMode.NONE
        _uiState.update { it.copy(isCasting = false) }
        val finished = event as? CatalogCastPlaybackEvent.Finished ?: return
        _uiEvents.emit(CatalogUiEvent.ShowMessage(finished.message))
    }

    private suspend fun emitCastResult(result: CatalogCastStartResult): Boolean {
        _uiEvents.emit(
            when (result) {
                CatalogCastStartResult.Started -> {
                    castPlaybackReportMode = CastPlaybackReportMode.FAILURES_ONLY
                    CatalogUiEvent.ShowMessage(CatalogMessage.CastStarted)
                }
                CatalogCastStartResult.RouteSelectionRequired -> {
                    castPlaybackReportMode = CastPlaybackReportMode.SUCCESS_AND_FAILURE
                    CatalogUiEvent.OpenCastRouteChooser
                }
                CatalogCastStartResult.Unavailable -> CatalogUiEvent.ShowMessage(CatalogMessage.CastUnavailable)
                is CatalogCastStartResult.Unsupported -> CatalogUiEvent.ShowMessage(result.message)
            }
        )
        return result == CatalogCastStartResult.Started || result == CatalogCastStartResult.RouteSelectionRequired
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
}

data class SeriesDetailUiState(
    val isLoading: Boolean = false,
    val series: Series? = null,
    val selectedSeason: Season? = null,
    val resumeEpisode: Episode? = null,
    val unwatchedEpisodeCount: Int = 0,
    val error: String? = null,
    val isCasting: Boolean = false,
    val isLoadingExternalRatings: Boolean = false,
    val externalRatings: ExternalRatings = ExternalRatings.unavailable()
)

private enum class CastPlaybackReportMode {
    NONE,
    FAILURES_ONLY,
    SUCCESS_AND_FAILURE
}
