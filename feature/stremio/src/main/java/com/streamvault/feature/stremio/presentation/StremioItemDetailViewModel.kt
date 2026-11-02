package com.streamvault.feature.stremio.presentation

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamvault.domain.model.DownloadContentType
import com.streamvault.domain.model.DownloadRequest
import com.streamvault.domain.model.DownloadSourceType
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.stremio.*
import com.streamvault.domain.model.stremio.StremioAddonResourceFailure
import com.streamvault.domain.model.stremio.StremioAddonSource
import com.streamvault.domain.model.stremio.StremioMediaType
import com.streamvault.domain.model.stremio.StremioMetaDetail
import com.streamvault.domain.model.stremio.StremioMetaPreview
import com.streamvault.domain.model.stremio.StremioStream
import com.streamvault.domain.model.stremio.StremioStreamKind
import com.streamvault.domain.model.stremio.StremioSubtitle
import com.streamvault.domain.model.stremio.StremioVideo
import com.streamvault.domain.model.stremio.stremioLibraryKey
import com.streamvault.domain.model.stremio.stremioWatchedKey
import com.streamvault.domain.model.stremio.installFingerprint
import com.streamvault.domain.model.stremio.stremioMediaRequestHeaders
import com.streamvault.domain.model.stremio.toMagnetUri
import com.streamvault.domain.repository.StremioAddonRepository
import com.streamvault.domain.repository.DownloadManager
import com.streamvault.feature.stremio.R
import com.streamvault.feature.stremio.api.StremioDownloadStarter
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import java.io.File
import java.net.URI
import java.nio.ByteBuffer
import java.security.MessageDigest
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
    val inLibrary: Boolean = false,
    val watchedKeys: Set<String> = emptySet(),
    val libraryBusy: Boolean = false,
    val libraryFailed: Boolean = false,
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
    val subtitles: List<com.streamvault.domain.model.stremio.StremioAttributedResource<StremioSubtitle>> = emptyList(),
    val subtitleFailures: List<StremioAddonResourceFailure> = emptyList(),
    val isEnqueueingDownload: Boolean = false,
    val downloadMessage: StremioDownloadMessage? = null
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
    private val downloadManager: DownloadManager,
    private val downloadStarter: StremioDownloadStarter,
    @param:ApplicationContext private val context: Context,
    private val playbackHistoryRepository: com.streamvault.domain.repository.StremioPlaybackHistoryRepository? = null
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

    private var viewingHistory = emptyList<com.streamvault.domain.model.stremio.StremioPlaybackHistory>()
    private var metadataJob: Job? = null
    private var streamJob: Job? = null
    private var streamGeneration = 0L

    init {
        loadMetadata()
        playbackHistoryRepository?.let { history ->
            viewModelScope.launch {
                history.observeHistory().collect { records ->
                    viewingHistory = records
                    reconcileWatched()
                }
            }
        }
    }

    fun retryMetadata() = loadMetadata()

    private fun reconcileWatched() {
        val origin = state.value.catalogOriginKey ?: return
        val ids = state.value.videos.map { it.id }.ifEmpty { listOf(exactId) }
        _state.update { current ->
            val keys = current.watchedKeys.toMutableSet()
            for (id in ids) {
                val key = stremioPlaybackContentKey(type.typeName, id, exactId.takeIf { current.videos.isNotEmpty() }, origin)
                val record = viewingHistory.firstOrNull { it.contentKey == key } ?: continue
                val marker = stremioWatchedKey(type, id)
                if (record.watched) keys.add(marker) else keys.remove(marker)
            }
            current.copy(watchedKeys = keys)
        }
    }

    fun setSeasonWatched(season: Int?, watched: Boolean) {
        val current = state.value
        if (current.libraryBusy || !contentAccess.canAccessContentNow()) return
        val videos = current.videos.filter { (season == null || it.season == season) &&
            (!watched || it.releaseInstant()?.let { release -> release <= java.time.Instant.now() } != false) }
        val origin = current.catalogOriginKey ?: return
        viewModelScope.launch {
            _state.update { it.copy(libraryBusy = true, libraryFailed = false) }
            try {
                val records = videos.mapNotNull { video ->
                    val key = stremioPlaybackContentKey(type.typeName, video.id, exactId, origin) ?: return@mapNotNull null
                    val previous = viewingHistory.firstOrNull { it.contentKey == key }
                    val record = previous ?: StremioPlaybackHistory(key, type.typeName, video.id, exactId,
                        "${current.displayName} · ${video.title ?: video.id}", current.displayPoster, catalogOriginKey = origin)
                    record.copy(watched = watched, resumePositionMs = if (watched) record.totalDurationMs else 0L,
                        lastWatchedAt = if (previous == null && !watched) 0L else record.lastWatchedAt)
                }
                if (playbackHistoryRepository?.saveAll(records)?.isError == true) error("history update failed")
                val markers = videos.map { stremioWatchedKey(type,it.id) }.toSet()
                _state.update { it.copy(watchedKeys = if (watched) it.watchedKeys + markers else it.watchedKeys - markers) }
                viewModelScope.launch {
                    try {
                        videos.chunked(100).forEach { batch -> repository.reportActivity("library", type, exactId,
                            listOf("action" to if (watched) "watched" else "unwatched", "videoId" to batch.joinToString(",") { it.id })) }
                    } catch (cancel: CancellationException) { throw cancel
                    } catch (_: Exception) { /* Local watched state remains saved when an optional event fails. */ }
                }
            } catch (cancel: CancellationException) { throw cancel
            } catch (_: Exception) { _state.update { it.copy(libraryFailed = true) }
            } finally { _state.update { it.copy(libraryBusy = false) } }
        }
    }

    private val libraryEventMutex = kotlinx.coroutines.sync.Mutex()
    fun toggleLibrary() = updateLibrary(watched = false)
    fun toggleWatched() = updateLibrary(watched = true)
    private fun updateLibrary(watched: Boolean) {
        val current = _state.value
        if (!contentAccess.canAccessContentNow() || current.libraryBusy || current.replayOriginUnavailable) return
        val videoId = current.requestedVideoId ?: exactId
        val key = if (watched) stremioWatchedKey(type, videoId) else stremioLibraryKey(type, exactId)
        val enabled = if (watched) key !in current.watchedKeys else !current.inLibrary
        _state.update { it.copy(libraryBusy = true, libraryFailed = false) }
        viewModelScope.launch {
            try {
                val result = repository.setCatalogEnabled(originAddonId, key, enabled)
                if (result.getOrNull() != null) {
                    _state.update {
                        if (watched) it.copy(watchedKeys = if (enabled) it.watchedKeys + key else it.watchedKeys - key)
                        else it.copy(inLibrary = enabled)
                    }
                    val action = if (watched) { if (enabled) "watched" else "unwatched" }
                        else if (enabled) "libraryAdd" else "libraryRemove"
                    val extras = buildList {
                        add("action" to action)
                        if (watched && videoId != exactId) add("videoId" to videoId)
                    }
                    if (watched) {
                        val originKey = current.catalogOriginKey
                        val contentKey = originKey?.let {
                            stremioPlaybackContentKey(type.typeName, videoId,
                                exactId.takeIf { current.videos.isNotEmpty() }, it)
                        }
                        if (contentKey != null && originKey != null) {
                            val history = playbackHistoryRepository?.get(contentKey) ?: StremioPlaybackHistory(
                                contentKey, type.typeName, videoId, exactId.takeIf { current.videos.isNotEmpty() },
                                current.displayName, current.displayPoster, catalogOriginKey = originKey,
                                lastWatchedAt = if (enabled) System.currentTimeMillis() else 0L)
                            val saved = playbackHistoryRepository?.save(history.copy(watched = enabled,
                                resumePositionMs = if (enabled) history.resumePositionMs else 0L))
                            if (saved?.isError == true) _state.update { it.copy(libraryFailed = true) }
                        }
                    } else if (enabled) {
                        repository.setCatalogEnabled(originAddonId, stremioFollowedKey(type, exactId, System.currentTimeMillis()), true)
                    }
                    viewModelScope.launch {
                        libraryEventMutex.lock()
                        try { repository.reportActivity("library", type, exactId, extras)
                        } catch (cancel: CancellationException) { throw cancel
                        } catch (_: Exception) {
                        } finally { libraryEventMutex.unlock() }
                    }
                } else _state.update { it.copy(libraryFailed = true) }
            } catch (cancel: CancellationException) { throw cancel
            } catch (_: Exception) {
                _state.update { it.copy(libraryFailed = true) }
            } finally { _state.update { it.copy(libraryBusy = false) } }
        }
    }

    fun loadStreams(videoId: String) {
        if (!contentAccess.canAccessContentNow() || videoId.isBlank() ||
            type == StremioMediaType.UNKNOWN || _state.value.replayOriginUnavailable
        ) return
        val current = _state.value
        val selectedVideo = if (type == StremioMediaType.TV || current.metadata?.isLive == true) {
            if (videoId != exactId) return
            null
        } else {
            current.videos.firstOrNull { it.id == videoId } ?: run {
                if (videoId != exactId && videoId != replayVideoId && videoId != current.metadata?.defaultVideoId) return
                if (type == StremioMediaType.SERIES && current.videos.isNotEmpty() && videoId != replayVideoId) return
                null
            }
        }
        streamJob?.cancel()
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
                subtitles = emptyList(),
                subtitleFailures = emptyList(),
                downloadMessage = null
            )
        }
        val embeddedStreams = selectedVideo?.streams
        val embeddedOrigin = current.metaOrigin
        if (embeddedStreams != null && embeddedOrigin != null) {
            _state.update {
                it.copy(
                    streamsLoading = false,
                    streams = embeddedStreams.map { stream ->
                        com.streamvault.domain.model.stremio.StremioAttributedResource(embeddedOrigin, stream)
                    },
                    streamCandidateCount = 1,
                    streamProviderOrder = listOf(embeddedOrigin.addonId)
                )
            }
            return
        }
        streamJob = viewModelScope.launch {
            try {
                if (!contentAccess.canAccessContentNow()) return@launch
                val result = repository.resolveStreamsProgressively(type, videoId) { progress ->
                    if (generation == streamGeneration) {
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
                        it.copy(streamsLoading = false, streamError = context.getString(R.string.stremio_error_generic))
                    }
                }
            }
        }
    }

    fun clearSelectedEpisode() {
        streamJob?.cancel()
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
                        it.copy(streamsLoading = false, streamError = context.getString(R.string.stremio_error_generic))
                    }
                }
            }
        }
    }

    fun enqueueDownload(source: StremioAddonSource, stream: StremioStream, videoId: String) {
        val current = _state.value
        if (!contentAccess.canAccessContentNow() || videoId.isBlank() ||
            current.isEnqueueingDownload || current.replayOriginUnavailable
        ) return

        val sourceUrl: String
        val sourceType: DownloadSourceType
        val requestHeaders: Map<String, String>
        when (stream.kind) {
            StremioStreamKind.TORRENT -> {
                sourceUrl = stream.toMagnetUri() ?: return
                sourceType = DownloadSourceType.TORRENT_MAGNET
                requestHeaders = emptyMap()
            }
            StremioStreamKind.DIRECT -> {
                val url = stream.url?.takeIf {
                    it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true)
                } ?: return
                if (!stream.canDownloadAsSingleFile()) return
                val mediaHeaders = stremioMediaRequestHeaders(
                    url,
                    stream.behaviorHints?.proxyHeaders?.request
                ) ?: return
                sourceUrl = url
                sourceType = DownloadSourceType.DIRECT_URL
                requestHeaders = mediaHeaders.headers
            }
            else -> return
        }

        val contentType = when (current.preview.type) {
            StremioMediaType.MOVIE -> DownloadContentType.MOVIE
            StremioMediaType.SERIES -> DownloadContentType.SERIES_EPISODE
            else -> DownloadContentType.MOVIE
        }
        val selectedVideo = current.selectedVideo?.takeIf { it.id == videoId }
        val request = DownloadRequest(
            providerId = source.addonId,
            contentType = contentType,
            contentId = stableContentId(videoId),
            contentName = selectedVideo?.title?.takeIf(String::isNotBlank) ?: current.displayName,
            streamUrl = sourceUrl,
            containerExtension = streamFileExtension(stream),
            posterUrl = selectedVideo?.thumbnail ?: current.displayPoster,
            seriesId = if (contentType == DownloadContentType.SERIES_EPISODE) {
                stableContentId(current.preview.id)
            } else null,
            seasonNumber = selectedVideo?.season,
            episodeNumber = selectedVideo?.episode,
            sourceType = sourceType,
            requestHeaders = requestHeaders
        )

        _state.update { it.copy(isEnqueueingDownload = true, downloadMessage = null) }
        viewModelScope.launch {
            val result = runCatching { downloadManager.enqueueDownload(request) }
                .getOrElse { Result.error(context.getString(R.string.stremio_error_generic), it) }
            val message = when (result) {
                is Result.Success -> {
                    runCatching { downloadStarter.startDownload(result.data.id) }
                    StremioDownloadMessage.QUEUED
                }
                is Result.Error,
                Result.Loading -> StremioDownloadMessage.FAILED
            }
            _state.update { it.copy(isEnqueueingDownload = false, downloadMessage = message) }
        }
    }

    fun clearDownloadMessage() {
        _state.update { it.copy(downloadMessage = null) }
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
                _state.update { current ->
                    current.copy(inLibrary = origin?.catalogSettings?.get(stremioLibraryKey(type, exactId)) == true,
                        watchedKeys = origin?.catalogSettings.orEmpty().filter { it.value && it.key.startsWith("__watched:") }.keys)
                }
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
                // History already supplies an exact video ID. Metadata must not delay or
                // prevent replay, and retrying metadata must not restart an active lookup.
                if (_state.value.requestedVideoId == null) replayVideoId?.let(::loadStreams)
                val result = repository.resolveMeta(originAddonId, type, exactId)
                _state.update {
                    it.copy(
                        metadataLoading = false,
                        metadata = result.result?.resource ?: it.metadata,
                        metaOrigin = result.result?.source ?: it.metaOrigin,
                        metadataFailures = result.failures
                    )
                }
                reconcileWatched()
                val resolvedMeta = result.result?.resource
                val requestedId = _state.value.requestedVideoId
                if (requestedId == null && type != StremioMediaType.TV && resolvedMeta?.isLive != true) {
                    resolvedMeta?.defaultVideoId?.let(::loadStreams)
                } else if (requestedId == replayVideoId && requestedId != null &&
                    resolvedMeta?.videos?.any { it.id == requestedId && it.streams != null } == true
                ) {
                    // Replay may start before metadata arrives. Inline streams remain authoritative.
                    loadStreams(requestedId)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                _state.update {
                    it.copy(metadataLoading = false, metadataError = context.getString(R.string.stremio_error_generic))
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

enum class StremioDownloadMessage {
    QUEUED,
    FAILED
}

private fun stableContentId(value: String): Long {
    val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    return (ByteBuffer.wrap(digest).long and Long.MAX_VALUE).coerceAtLeast(1L)
}

private fun streamFileExtension(stream: StremioStream): String? {
    val names = listOfNotNull(
        stream.behaviorHints?.filename,
        stream.title,
        stream.name,
        stream.url?.let { runCatching { URI(it).path }.getOrNull() }
    )
    return names.asSequence()
        .map { File(it).extension.lowercase() }
        .firstOrNull { extension -> extension.length in 2..5 && extension.all(Char::isLetterOrDigit) }
}

internal fun StremioStream.canDownloadAsSingleFile(): Boolean {
    if (kind == StremioStreamKind.TORRENT) return toMagnetUri() != null
    if (kind != StremioStreamKind.DIRECT) return false
    val possibleNames = listOfNotNull(
        behaviorHints?.filename,
        url?.let { runCatching { URI(it).path }.getOrNull() }
    )
    return possibleNames.none { name ->
        File(name).extension.equals("m3u8", ignoreCase = true) ||
            File(name).extension.equals("mpd", ignoreCase = true) ||
            File(name).extension.equals("m3u", ignoreCase = true)
    }
}
