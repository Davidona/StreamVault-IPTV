package com.streamvault.app.ui.screens.player

import androidx.lifecycle.viewModelScope
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.LiveChannelObservedQuality
import com.streamvault.domain.model.VideoFormat
import com.streamvault.player.AUDIO_VIDEO_OFFSET_MAX_MS
import com.streamvault.player.AUDIO_VIDEO_OFFSET_MIN_MS
import com.streamvault.player.PlaybackState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.collections.LinkedHashSet

private const val THUMBNAIL_PRELOAD_BUCKET_MS = 10_000L
private const val THUMBNAIL_PRELOAD_FRAME_BUDGET = 6

internal fun buildThumbnailPreloadPositions(
    durationMs: Long,
    currentPositionMs: Long,
    bucketMs: Long = THUMBNAIL_PRELOAD_BUCKET_MS,
    frameBudget: Int = THUMBNAIL_PRELOAD_FRAME_BUDGET
): List<Long> {
    if (durationMs <= 0L || bucketMs <= 0L || frameBudget <= 0) return emptyList()

    val maxPositionMs = durationMs.coerceAtLeast(0L)
    val anchorPositionMs = (currentPositionMs.coerceIn(0L, maxPositionMs) / bucketMs) * bucketMs
    val plannedPositions = LinkedHashSet<Long>()
    var offsetMs = 0L

    while (plannedPositions.size < frameBudget) {
        val forwardPositionMs = anchorPositionMs + offsetMs
        if (forwardPositionMs <= maxPositionMs) {
            plannedPositions += forwardPositionMs
        }
        if (plannedPositions.size >= frameBudget) break

        if (offsetMs > 0L) {
            val backwardPositionMs = anchorPositionMs - offsetMs
            if (backwardPositionMs >= 0L) {
                plannedPositions += backwardPositionMs
            }
        }

        if (forwardPositionMs > maxPositionMs && anchorPositionMs - offsetMs < 0L) {
            break
        }
        offsetMs += bucketMs
    }

    return plannedPositions.toList()
}

internal fun shouldStartThumbnailPreload(
    preloadKey: String,
    lastCompletedPreloadKey: String?,
    inFlightPreloadKey: String?
): Boolean = preloadKey.isNotBlank() &&
    preloadKey != lastCompletedPreloadKey &&
    preloadKey != inFlightPreloadKey

fun PlayerViewModel.selectAudioTrack(trackId: String) {
    playerEngine.selectAudioTrack(trackId)
}

fun PlayerViewModel.selectSubtitleTrack(trackId: String?) {
    playerEngine.selectSubtitleTrack(trackId)
}

fun PlayerViewModel.selectVideoQuality(trackId: String) {
    playerEngine.selectVideoTrack(trackId)
}

fun PlayerViewModel.selectLiveVariant(rawChannelId: Long) {
    val currentChannel = currentChannelFlow.value?.sanitizedForPlayer() ?: return
    val updatedChannel = currentChannel.withSelectedVariant(rawChannelId)?.sanitizedForPlayer() ?: return
    if (updatedChannel.selectedVariantId == currentChannel.selectedVariantId) return

    val requestVersion = beginPlaybackSession()
    triedAlternativeStreams.clear()
    currentContentId = updatedChannel.id
    currentStreamUrl = updatedChannel.streamUrl
    currentTitle = updatedChannel.currentVariant?.originalName ?: updatedChannel.name
    playbackTitleFlow.value = currentTitle
    currentChannelFlow.value = updatedChannel
    if (currentChannelIndex in channelList.indices) {
        channelList = channelList.mapIndexed { index, existing ->
            if (index == currentChannelIndex || existing.logicalGroupId == updatedChannel.logicalGroupId) {
                updatedChannel
            } else {
                existing
            }
        }
        currentChannelFlowList.value = channelList
    }
    if (currentChannelIndex >= 0) {
        displayChannelNumberFlow.value = resolveChannelNumber(updatedChannel, currentChannelIndex)
    }
    refreshCurrentChannelRecording()
    updateChannelDiagnostics(updatedChannel)
    updateStreamClass("Variant")
    viewModelScope.launch {
        preferencesRepository.setPreferredLiveVariant(
            providerId = updatedChannel.providerId,
            logicalGroupId = updatedChannel.logicalGroupId,
            rawChannelId = rawChannelId
        )
        val streamInfo = resolvePlaybackStreamInfo(
            logicalUrl = updatedChannel.streamUrl,
            internalContentId = updatedChannel.id,
            providerId = updatedChannel.providerId,
            contentType = ContentType.LIVE
        ) ?: return@launch
        if (!isActivePlaybackSession(requestVersion, updatedChannel.streamUrl)) return@launch
        if (currentContentType == ContentType.LIVE) {
            requestEpg(
                providerId = updatedChannel.providerId,
                epgChannelId = updatedChannel.epgChannelId,
                streamId = updatedChannel.streamId,
                internalChannelId = updatedChannel.id
            )
        }
        if (!preparePlayer(streamInfo.copy(title = streamInfo.title ?: currentTitle), requestVersion)) return@launch
        playerEngine.play()
    }
}

/**
 * Switches to a different stream format (e.g. HLS vs MPEG-TS) for the current live channel.
 * The [formatUrl] is one of the [com.streamvault.domain.model.ChannelQualityOption.url] values
 * from the channel's [com.streamvault.domain.model.Channel.qualityOptions].
 */
fun PlayerViewModel.selectStreamFormat(formatUrl: String) {
    val channel = currentChannelFlow.value?.sanitizedForPlayer() ?: return
    if (formatUrl == currentStreamUrl) return

    val requestVersion = beginPlaybackSession()
    triedAlternativeStreams.add(formatUrl)
    currentStreamUrl = formatUrl
    updateStreamClass("Format")
    viewModelScope.launch {
        val streamInfo = resolvePlaybackStreamInfo(
            logicalUrl = formatUrl,
            internalContentId = channel.id,
            providerId = channel.providerId,
            contentType = ContentType.LIVE
        ) ?: return@launch
        if (!isActivePlaybackSession(requestVersion, formatUrl)) return@launch
        if (!preparePlayer(streamInfo.copy(title = streamInfo.title ?: currentTitle), requestVersion)) return@launch
        playerEngine.play()
    }
}

fun PlayerViewModel.recordLiveVariantObservation(playbackState: PlaybackState, videoFormat: VideoFormat) {
    if (currentContentType != ContentType.LIVE || playbackState != PlaybackState.READY || videoFormat.isEmpty) {
        return
    }
    val channel = currentChannelFlow.value?.sanitizedForPlayer() ?: return
    val rawChannelId = channel.selectedVariantId.takeIf { it > 0 } ?: channel.id
    if (rawChannelId <= 0L) return
    val signature = buildString {
        append(rawChannelId)
        append('|')
        append(videoFormat.width)
        append('|')
        append(videoFormat.height)
        append('|')
        append(videoFormat.bitrate)
        append('|')
        append(videoFormat.frameRate)
    }
    if (signature == lastRecordedVariantObservationSignature) return
    lastRecordedVariantObservationSignature = signature

    viewModelScope.launch {
        val existing = preferencesRepository.liveVariantObservations.first()[rawChannelId]
        preferencesRepository.recordLiveVariantObservation(
            rawChannelId = rawChannelId,
            observedQuality = LiveChannelObservedQuality(
                lastObservedWidth = videoFormat.width,
                lastObservedHeight = videoFormat.height,
                lastObservedBitrate = videoFormat.bitrate,
                lastObservedFrameRate = videoFormat.frameRate,
                successCount = (existing?.successCount ?: 0) + 1,
                lastSuccessfulAt = System.currentTimeMillis()
            )
        )
    }
}

fun PlayerViewModel.setPlaybackSpeed(speed: Float) {
    val normalizedSpeed = speed.coerceIn(0.5f, 2f)
    playerEngine.setPlaybackSpeed(normalizedSpeed)
    viewModelScope.launch {
        preferencesRepository.setPlayerPlaybackSpeed(normalizedSpeed)
    }
}

fun PlayerViewModel.previewAudioVideoOffset(offsetMs: Int) {
    audioVideoOffsetPreviewMs.value = offsetMs.coerceIn(AUDIO_VIDEO_OFFSET_MIN_MS, AUDIO_VIDEO_OFFSET_MAX_MS)
}

fun PlayerViewModel.adjustAudioVideoOffset(deltaMs: Int) {
    val current = audioVideoOffsetPreviewMs.value ?: _audioVideoOffsetUiState.value.effectiveOffsetMs
    previewAudioVideoOffset(current + deltaMs)
}

fun PlayerViewModel.resetAudioVideoOffsetPreview() {
    previewAudioVideoOffset(0)
}

fun PlayerViewModel.dismissAudioVideoOffsetPreview() {
    audioVideoOffsetPreviewMs.value = null
}

fun PlayerViewModel.saveAudioVideoOffsetForChannel() {
    val channelId = currentChannelFlow.value?.id?.takeIf { it > 0L } ?: return
    val offsetMs = _audioVideoOffsetUiState.value.effectiveOffsetMs
    viewModelScope.launch {
        preferencesRepository.setAudioVideoOffsetForChannel(channelId, offsetMs)
        audioVideoOffsetPreviewMs.value = null
    }
}

fun PlayerViewModel.saveAudioVideoOffsetAsGlobal() {
    val offsetMs = _audioVideoOffsetUiState.value.effectiveOffsetMs
    val channelId = currentChannelFlow.value?.id?.takeIf { it > 0L }
    viewModelScope.launch {
        preferencesRepository.setPlayerAudioVideoOffsetMs(offsetMs)
        if (channelId != null) {
            preferencesRepository.clearAudioVideoOffsetForChannel(channelId)
        }
        audioVideoOffsetPreviewMs.value = null
    }
}

fun PlayerViewModel.useGlobalAudioVideoOffset() {
    val channelId = currentChannelFlow.value?.id?.takeIf { it > 0L } ?: return
    viewModelScope.launch {
        preferencesRepository.clearAudioVideoOffsetForChannel(channelId)
        audioVideoOffsetPreviewMs.value = null
    }
}

fun PlayerViewModel.seekTo(positionMs: Long) {
    notifyUserActivity()
    playerEngine.seekTo(positionMs)
    clearSeekPreview()
}

fun PlayerViewModel.setScrubbingMode(enabled: Boolean) {
    playerEngine.setScrubbingMode(enabled)
    if (!enabled) {
        clearSeekPreview()
    }
}

fun PlayerViewModel.updateSeekPreview(positionMs: Long?) {
    if (positionMs == null || currentContentType == ContentType.LIVE) {
        clearSeekPreview()
        return
    }

    val previewPositionMs = positionMs.coerceAtLeast(0L)
    val previewUrl = currentResolvedPlaybackUrl.ifBlank { currentStreamUrl }
    val canExtractFrame = previewUrl.isNotBlank() && seekThumbnailProvider.supportsFrameExtraction(previewUrl)

    _seekPreview.update { current ->
        current.copy(
            visible = true,
            positionMs = previewPositionMs,
            artworkUrl = currentArtworkUrl,
            title = currentTitle,
            isLoading = canExtractFrame,
            frameBitmap = if (canExtractFrame) current.frameBitmap else null
        )
    }

    seekPreviewJob?.cancel()
    if (!canExtractFrame) {
        return
    }

    val requestVersion = ++seekPreviewRequestVersion
    seekPreviewJob = viewModelScope.launch {
        delay(120)
        val bitmap = seekThumbnailProvider.loadFrame(previewUrl, previewPositionMs)
        if (requestVersion != seekPreviewRequestVersion) return@launch

        _seekPreview.update { current ->
            if (!current.visible || current.positionMs != previewPositionMs) {
                current
            } else {
                current.copy(
                    frameBitmap = bitmap,
                    artworkUrl = currentArtworkUrl,
                    title = currentTitle,
                    isLoading = false
                )
            }
        }
    }
}

internal fun PlayerViewModel.startThumbnailPreload() {
    val url = currentResolvedPlaybackUrl.ifBlank { currentStreamUrl }
    if (url.isBlank() || !seekThumbnailProvider.supportsFrameExtraction(url)) return
    if (!shouldStartThumbnailPreload(url, lastCompletedThumbnailPreloadKey, inFlightThumbnailPreloadKey)) return
    val durationMs = playerEngine.duration.value
    if (durationMs <= 0L) return
    val preloadPositions = buildThumbnailPreloadPositions(
        durationMs = durationMs,
        currentPositionMs = playerEngine.currentPosition.value
    )
    if (preloadPositions.isEmpty()) return

    thumbnailPreloadJob?.cancel()
    inFlightThumbnailPreloadKey = url
    thumbnailPreloadJob = viewModelScope.launch(Dispatchers.Default) {
        try {
            preloadPositions.forEach { positionMs ->
                ensureActive()
                seekThumbnailProvider.loadFrame(url, positionMs)
            }
            lastCompletedThumbnailPreloadKey = url
        } finally {
            if (inFlightThumbnailPreloadKey == url) {
                inFlightThumbnailPreloadKey = null
            }
        }
    }
}

internal fun PlayerViewModel.clearSeekPreview() {
    seekPreviewJob?.cancel()
    seekPreviewRequestVersion++
    _seekPreview.value = SeekPreviewState()
}