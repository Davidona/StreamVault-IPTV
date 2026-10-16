package com.streamvault.feature.stremio.presentation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamvault.domain.model.stremio.StremioAddonResourceFailure
import com.streamvault.domain.model.stremio.StremioAddonSource
import com.streamvault.domain.model.stremio.StremioMediaType
import com.streamvault.domain.model.stremio.StremioMetaDetail
import com.streamvault.domain.model.stremio.StremioMetaPreview
import com.streamvault.domain.model.stremio.StremioStream
import com.streamvault.domain.model.stremio.StremioStreamKind
import com.streamvault.domain.model.stremio.StremioSubtitle
import com.streamvault.domain.model.stremio.StremioVideo
import com.streamvault.domain.model.stremio.installFingerprint
import com.streamvault.domain.repository.StremioAddonRepository
import com.streamvault.feature.stremio.api.StremioTorrentPlaybackAvailability
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class StremioItemDetailState(
    val preview: StremioMetaPreview,
    val catalogOriginAddonId: Long,
    val catalogOriginKey: String? = null,
    val replayOriginUnavailable: Boolean = false,
    val metadata: StremioMetaDetail? = null,
    val metaOrigin: StremioAddonSource? = null,
    val metadataLoading: Boolean = true,
    val metadataFailures: List<StremioAddonResourceFailure> = emptyList(),
    val metadataError: String? = null,
    val requestedVideoId: String? = null,
    val selectedVideo: StremioVideo? = null,
    val streamsLoading: Boolean = false,
    val streamError: String? = null,
    val streams: List<com.streamvault.domain.model.stremio.StremioAttributedResource<StremioStream>> = emptyList(),
    val streamFailures: List<StremioAddonResourceFailure> = emptyList(),
    val streamCandidateCount: Int = 0,
    val streamProviderOrder: List<Long> = emptyList(),
    val torrentPluginAvailable: Boolean? = null,
    val torrentPluginCheckLoading: Boolean = false,
    val subtitles: List<com.streamvault.domain.model.stremio.StremioAttributedResource<StremioSubtitle>> = emptyList(),
    val subtitleFailures: List<StremioAddonResourceFailure> = emptyList()
) {
    val displayName: String get() = metadata?.name ?: preview.name
    val displayPoster: String? get() = metadata?.poster ?: preview.poster
    val displayDescription: String? get() = metadata?.description ?: preview.description
    val displayReleaseInfo: String? get() = metadata?.releaseInfo ?: preview.releaseInfo
    val videos: List<StremioVideo> get() = metadata?.videos.orEmpty()
}

@HiltViewModel
class StremioItemDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: StremioAddonRepository,
    private val contentAccess: StremioContentAccess,
    private val torrentPlaybackAvailability: StremioTorrentPlaybackAvailability
) : ViewModel() {

    private val originAddonId = savedStateHandle.get<Long>(ARG_ORIGIN_ADDON_ID) ?: -1L
    private val type = StremioMediaType.fromTypeName(savedStateHandle[ARG_TYPE])
    private val exactId = savedStateHandle.get<String>(ARG_EXACT_ID).orEmpty()
    private val replayVideoId = savedStateHandle.get<String>(ARG_REPLAY_VIDEO_ID)?.takeIf(String::isNotBlank)
    private val replayOriginKey = savedStateHandle.get<String>(ARG_REPLAY_ORIGIN_KEY)?.takeIf(String::isNotBlank)
    private val replayRequiresOrigin = savedStateHandle.get<Boolean>(ARG_REPLAY_REQUIRES_ORIGIN) ?: false
    private val preview = StremioMetaPreview(
        id = exactId,
        type = type,
        name = savedStateHandle.get<String>(ARG_NAME)?.takeIf(String::isNotBlank) ?: exactId,
        poster = savedStateHandle.get<String>(ARG_POSTER)?.takeIf(String::isNotBlank),
        description = savedStateHandle.get<String>(ARG_DESCRIPTION)?.takeIf(String::isNotBlank),
        releaseInfo = savedStateHandle.get<String>(ARG_RELEASE_INFO)?.takeIf(String::isNotBlank)
    )

    private val _state = MutableStateFlow(
        StremioItemDetailState(
            preview = preview,
            catalogOriginAddonId = originAddonId,
            catalogOriginKey = replayOriginKey
        )
    )
    val state: StateFlow<StremioItemDetailState> = _state.asStateFlow()

    private var metadataJob: Job? = null
    private var streamJob: Job? = null
    private var torrentAvailabilityJob: Job? = null
    private var streamGeneration = 0L
    private var torrentAvailabilityGeneration = 0L

    init {
        loadMetadata()
    }

    fun retryMetadata() = loadMetadata()

    fun loadStreams(videoId: String) {
        if (!contentAccess.canAccessContentNow() || videoId.isBlank() ||
            type == StremioMediaType.UNKNOWN || _state.value.replayOriginUnavailable
        ) return
        val current = _state.value
        val selectedVideo = when (type) {
            StremioMediaType.MOVIE -> {
                if (videoId != exactId) return
                null
            }
            StremioMediaType.SERIES -> current.metadata?.videos?.firstOrNull { it.id == videoId }
                ?: if (videoId == replayVideoId) null else return
            else -> return
        }
        streamJob?.cancel()
        torrentAvailabilityJob?.cancel()
        torrentAvailabilityGeneration++
        val generation = ++streamGeneration
        _state.update {
            it.copy(
                requestedVideoId = videoId,
                selectedVideo = selectedVideo,
                streamsLoading = true,
                streamError = null,
                streams = emptyList(),
                streamFailures = emptyList(),
                streamCandidateCount = 0,
                streamProviderOrder = emptyList(),
                torrentPluginAvailable = null,
                torrentPluginCheckLoading = false,
                subtitles = emptyList(),
                subtitleFailures = emptyList()
            )
        }
        streamJob = viewModelScope.launch {
            try {
                if (!contentAccess.canAccessContentNow()) return@launch
                val result = repository.resolveStreamsProgressively(type, videoId) { progress ->
                    if (generation == streamGeneration) {
                        checkTorrentPlaybackAvailability(progress.results)
                        _state.update {
                            it.copy(
                                streams = progress.results,
                                streamFailures = progress.failures,
                                streamCandidateCount = progress.candidateCount,
                                streamProviderOrder = progress.completedAddonIds
                            )
                        }
                    }
                }
                if (generation == streamGeneration) {
                    checkTorrentPlaybackAvailability(result.results)
                    _state.update {
                        it.copy(
                            streamsLoading = false,
                            streams = result.results,
                            streamFailures = result.failures,
                            streamCandidateCount = result.candidateCount,
                            streamProviderOrder = result.completedAddonIds
                        )
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                if (generation == streamGeneration) {
                    _state.update {
                        it.copy(streamsLoading = false, streamError = "Sources could not be loaded. Try again.")
                    }
                }
            }
        }
    }

    fun clearSelectedEpisode() {
        streamJob?.cancel()
        torrentAvailabilityJob?.cancel()
        torrentAvailabilityGeneration++
        streamGeneration++
        _state.update {
            it.copy(
                requestedVideoId = null,
                selectedVideo = null,
                streamsLoading = false,
                streamError = null,
                streams = emptyList(),
                streamFailures = emptyList(),
                streamCandidateCount = 0,
                streamProviderOrder = emptyList(),
                torrentPluginAvailable = null,
                torrentPluginCheckLoading = false,
                subtitles = emptyList(),
                subtitleFailures = emptyList()
            )
        }
    }

    fun retryStreamsForAddon(addonId: Long) {
        if (!contentAccess.canAccessContentNow()) return
        val current = _state.value
        val videoId = current.requestedVideoId ?: return
        val generation = ++streamGeneration
        streamJob?.cancel()
        _state.update { it.copy(streamsLoading = true, streamError = null) }
        streamJob = viewModelScope.launch {
            try {
                if (!contentAccess.canAccessContentNow()) return@launch
                val result = repository.resolveStreamsProgressively(type, videoId, setOf(addonId)) { progress ->
                    if (generation == streamGeneration) {
                        checkTorrentPlaybackAvailability(progress.results)
                        _state.update { state ->
                            val providerOrder = state.streamProviderOrder.ifEmpty { progress.completedAddonIds }
                            state.copy(
                                streams = orderResourcesByProvider(
                                    state.streams.filterNot { it.source.addonId == addonId } + progress.results,
                                    providerOrder
                                ),
                                streamFailures = orderFailuresByProvider(
                                    state.streamFailures.filterNot { it.source.addonId == addonId } + progress.failures,
                                    providerOrder
                                ),
                                streamCandidateCount = maxOf(state.streamCandidateCount, progress.candidateCount),
                                streamProviderOrder = providerOrder
                            )
                        }
                    }
                }
                if (generation == streamGeneration) {
                    checkTorrentPlaybackAvailability(result.results)
                    _state.update { state ->
                        val providerOrder = state.streamProviderOrder.ifEmpty { result.completedAddonIds }
                        state.copy(
                            streamsLoading = false,
                            streams = orderResourcesByProvider(
                                state.streams.filterNot { it.source.addonId == addonId } + result.results,
                                providerOrder
                            ),
                            streamFailures = orderFailuresByProvider(
                                state.streamFailures.filterNot { it.source.addonId == addonId } + result.failures,
                                providerOrder
                            ),
                            streamCandidateCount = maxOf(state.streamCandidateCount, result.candidateCount),
                            streamProviderOrder = providerOrder
                        )
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                if (generation == streamGeneration) {
                    _state.update {
                        it.copy(streamsLoading = false, streamError = "This provider could not be retried.")
                    }
                }
            }
        }
    }

    /** Recheck when returning from Plugins so installing/enabling the torrent plugin is reflected. */
    fun refreshTorrentPlaybackAvailability() {
        checkTorrentPlaybackAvailability(_state.value.streams, force = true)
    }

    private fun checkTorrentPlaybackAvailability(
        streams: List<com.streamvault.domain.model.stremio.StremioAttributedResource<StremioStream>>,
        force: Boolean = false
    ) {
        if (streams.none { it.resource.kind == StremioStreamKind.TORRENT }) return
        val current = _state.value
        if (!force && (current.torrentPluginCheckLoading || current.torrentPluginAvailable != null)) return
        torrentAvailabilityJob?.cancel()
        val generation = ++torrentAvailabilityGeneration
        _state.update { it.copy(torrentPluginAvailable = null, torrentPluginCheckLoading = true) }
        torrentAvailabilityJob = viewModelScope.launch {
            val available = try {
                torrentPlaybackAvailability.hasEnabledMagnetPlugin()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                false
            }
            if (generation == torrentAvailabilityGeneration) {
                _state.update {
                    it.copy(torrentPluginAvailable = available, torrentPluginCheckLoading = false)
                }
            }
        }
    }

    private fun loadMetadata() {
        if (!contentAccess.canAccessContentNow()) {
            metadataJob?.cancel()
            _state.update { it.copy(metadataLoading = false) }
            return
        }
        metadataJob?.cancel()
        _state.update { it.copy(metadataLoading = true, metadataFailures = emptyList(), metadataError = null) }
        metadataJob = viewModelScope.launch {
            if (!contentAccess.canAccessContentNow()) {
                _state.update { it.copy(metadataLoading = false) }
                return@launch
            }
            try {
            val origin = repository.getAddon(originAddonId)
            val originKey = origin?.installFingerprint()
            if (replayRequiresOrigin && (origin == null || !origin.enabled || originKey != replayOriginKey)) {
                _state.update {
                    it.copy(
                        metadataLoading = false,
                        catalogOriginKey = replayOriginKey,
                        replayOriginUnavailable = true
                    )
                }
                return@launch
            }
            _state.update { it.copy(catalogOriginKey = replayOriginKey ?: originKey) }
                val result = repository.resolveMeta(originAddonId, type, exactId)
                _state.update {
                    it.copy(
                        metadataLoading = false,
                        metadata = result.result?.resource,
                        metaOrigin = result.result?.source,
                        metadataFailures = result.failures
                    )
                }
                replayVideoId?.let(::loadStreams)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                _state.update {
                    it.copy(metadataLoading = false, metadataError = "Title details could not be loaded. Try again.")
                }
            }
        }
    }

    private fun <T> orderResourcesByProvider(
        values: List<com.streamvault.domain.model.stremio.StremioAttributedResource<T>>,
        providerOrder: List<Long>
    ) = values.sortedBy { value -> providerOrder.indexOf(value.source.addonId).let { if (it < 0) Int.MAX_VALUE else it } }

    private fun orderFailuresByProvider(
        values: List<StremioAddonResourceFailure>,
        providerOrder: List<Long>
    ) = values.sortedBy { value -> providerOrder.indexOf(value.source.addonId).let { if (it < 0) Int.MAX_VALUE else it } }

    companion object {
        const val ARG_ORIGIN_ADDON_ID = "originAddonId"
        const val ARG_TYPE = "type"
        const val ARG_EXACT_ID = "exactId"
        const val ARG_NAME = "name"
        const val ARG_POSTER = "poster"
        const val ARG_DESCRIPTION = "description"
        const val ARG_RELEASE_INFO = "releaseInfo"
        const val ARG_REPLAY_VIDEO_ID = "replayVideoId"
        const val ARG_REPLAY_ORIGIN_KEY = "replayOriginKey"
        const val ARG_REPLAY_REQUIRES_ORIGIN = "replayRequiresOrigin"
    }
}
