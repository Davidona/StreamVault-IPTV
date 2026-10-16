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
import com.streamvault.domain.model.stremio.StremioSubtitle
import com.streamvault.domain.model.stremio.StremioVideo
import com.streamvault.domain.model.stremio.installFingerprint
import com.streamvault.domain.repository.StremioAddonRepository
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
    val requestedVideoId: String? = null,
    val selectedVideo: StremioVideo? = null,
    val streamsLoading: Boolean = false,
    val streams: List<com.streamvault.domain.model.stremio.StremioAttributedResource<StremioStream>> = emptyList(),
    val streamFailures: List<StremioAddonResourceFailure> = emptyList(),
    val streamCandidateCount: Int = 0,
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
    private val contentAccess: StremioContentAccess
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
    private var streamGeneration = 0L

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
        val generation = ++streamGeneration
        _state.update {
            it.copy(
                requestedVideoId = videoId,
                selectedVideo = selectedVideo,
                streamsLoading = true,
                streams = emptyList(),
                streamFailures = emptyList(),
                streamCandidateCount = 0,
                subtitles = emptyList(),
                subtitleFailures = emptyList()
            )
        }
        streamJob = viewModelScope.launch {
            try {
                if (!contentAccess.canAccessContentNow()) return@launch
                val result = repository.resolveStreams(type, videoId)
                if (generation == streamGeneration) {
                    _state.update {
                        it.copy(
                            streamsLoading = false,
                            streams = result.results,
                            streamFailures = result.failures,
                            streamCandidateCount = result.candidateCount
                        )
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                if (generation == streamGeneration) {
                    _state.update { it.copy(streamsLoading = false) }
                }
            }
        }
    }

    fun retryStreamsForAddon(addonId: Long) {
        if (!contentAccess.canAccessContentNow()) return
        val current = _state.value
        val videoId = current.requestedVideoId ?: return
        val generation = ++streamGeneration
        streamJob?.cancel()
        _state.update { it.copy(streamsLoading = true) }
        streamJob = viewModelScope.launch {
            try {
                if (!contentAccess.canAccessContentNow()) return@launch
                val result = repository.resolveStreams(type, videoId, setOf(addonId))
                if (generation == streamGeneration) {
                    _state.update { state ->
                        state.copy(
                            streamsLoading = false,
                            streams = state.streams.filterNot { it.source.addonId == addonId } + result.results,
                            streamFailures = state.streamFailures.filterNot { it.source.addonId == addonId } + result.failures,
                            streamCandidateCount = maxOf(state.streamCandidateCount, result.candidateCount)
                        )
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                if (generation == streamGeneration) {
                    _state.update { it.copy(streamsLoading = false) }
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
        _state.update { it.copy(metadataLoading = true, metadataFailures = emptyList()) }
        metadataJob = viewModelScope.launch {
            if (!contentAccess.canAccessContentNow()) {
                _state.update { it.copy(metadataLoading = false) }
                return@launch
            }
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
            try {
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
                _state.update { it.copy(metadataLoading = false) }
            }
        }
    }

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
