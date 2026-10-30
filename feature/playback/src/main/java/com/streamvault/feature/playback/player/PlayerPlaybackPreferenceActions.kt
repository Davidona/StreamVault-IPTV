package com.streamvault.feature.playback.player

import androidx.lifecycle.viewModelScope
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.LiveChannelObservedQuality
import com.streamvault.domain.model.VodVariantObservation
import com.streamvault.domain.model.VideoFormat
import com.streamvault.domain.settings.VodTrackPreference
import com.streamvault.domain.settings.VodTrackPreferences
import com.streamvault.domain.settings.PlaybackQualityPreferenceScope
import com.streamvault.domain.settings.QualityLimitOverride
import com.streamvault.domain.settings.ScopedPlaybackQualityPreference
import com.streamvault.player.AUDIO_VIDEO_OFFSET_MAX_MS
import com.streamvault.player.AUDIO_VIDEO_OFFSET_MIN_MS
import com.streamvault.player.PLAYER_TRACK_AUTO_ID
import com.streamvault.player.SUBTITLE_OFFSET_MAX_MS
import com.streamvault.player.SUBTITLE_OFFSET_MIN_MS
import com.streamvault.player.PlaybackState
import com.streamvault.player.PlayerError
import com.streamvault.player.SubtitleTrackSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

fun PlayerViewModel.selectAudioTrack(trackId: String) {
    val selectedTrack = playerEngine.availableAudioTracks.value.firstOrNull { it.id == trackId }
    playerEngine.selectAudioTrack(trackId)
    selectedTrack?.let { track ->
        persistVodTrackPreferences(
            updated = (activeVodTrackPreferences ?: VodTrackPreferences()).copy(
                audio = track.toVodTrackPreference()
            )
        )
    }
}

fun PlayerViewModel.selectSubtitleTrack(trackId: String?) {
    // Capture the track before changing the engine selection. Some engines emit a
    // new track list synchronously, which can make a valid selection look unavailable.
    val selectedTrack = trackId?.let { id ->
        playerEngine.availableSubtitleTracks.value.firstOrNull { it.id == id }
    }
    playerEngine.selectSubtitleTrack(trackId)

    val current = activeVodTrackPreferences ?: VodTrackPreferences()
    val rememberedDownloadId = current.subtitle
        ?.externalSubtitleDownloadId
        ?.takeIf { selectedTrack?.subtitleSource == SubtitleTrackSource.ONLINE }
    val subtitlePreference = when {
        trackId == null -> VodTrackPreference(disabled = true)
        selectedTrack != null -> selectedTrack.toVodTrackPreference().copy(
            externalSubtitleDownloadId = rememberedDownloadId
        )
        else -> VodTrackPreference(
            trackId = trackId,
            externalSubtitleDownloadId = rememberedDownloadId
        )
    }
    persistVodTrackPreferences(
        updated = current.copy(
            subtitle = subtitlePreference
        )
    )
}

private fun com.streamvault.player.PlayerTrack.toVodTrackPreference() = VodTrackPreference(
    trackId = id,
    language = language,
    label = name
)

internal fun PlayerViewModel.persistVodTrackPreferences(
    updated: VodTrackPreferences,
    persistGlobal: Boolean = true
) {
    val scope = buildVodTrackPreferenceScope(
        contentType = currentContentType,
        providerId = currentProviderId,
        contentId = currentContentId,
        seriesId = currentSeriesId
    ) ?: return
    activeVodTrackPreferences = updated
    viewModelScope.launch {
        vodTrackPreferenceSaveMutex.withLock {
            playerPreferencesCoordinator.setVodTrackPreferences(scope, updated)
            if (persistGlobal) {
                playerPreferencesCoordinator.setGlobalVodTrackPreferences(
                    updated.copy(
                        subtitle = updated.subtitle?.copy(externalSubtitleDownloadId = null)
                    )
                )
            }
        }
    }
}

internal fun PlayerViewModel.clearRememberedOpenSubtitleForCurrentContent() {
    val current = activeVodTrackPreferences ?: return
    if (current.subtitle?.externalSubtitleDownloadId == null) return
    persistVodTrackPreferences(
        updated = current.copy(subtitle = null),
        persistGlobal = false
    )
}

fun PlayerViewModel.selectVideoQuality(trackId: String): Boolean {
    if (trackId == PLAYER_TRACK_AUTO_ID && temporaryPlaybackQualityPreferenceFlow.value != null) {
        episodeSourceState.onVideoSelection()
        clearTemporaryQualityLimit()
        return true
    }
    val selected = playerEngine.trySelectVideoTrack(trackId)
    if (selected) episodeSourceState.onVideoSelection()
    return selected
}

fun PlayerViewModel.saveCurrentQualityAsAdaptiveLimit() {
    val scope = playbackQualityPreferenceScope.value ?: return
    val height = currentObservedVideoHeight() ?: return
    updatePlaybackQualityPreference(scope) { current ->
        current.copy(maxVideoHeight = QualityLimitOverride.Maximum(height))
    }
}

fun PlayerViewModel.saveCurrentQualityAsProviderDefaultLimit() {
    val providerId = currentProviderId.takeIf { it > 0L } ?: return
    val height = currentObservedVideoHeight() ?: return
    val scope = PlaybackQualityPreferenceScope.providerDefault(providerId)
    updatePlaybackQualityPreference(scope) { current ->
        current.copy(maxVideoHeight = QualityLimitOverride.Maximum(height))
    }
}

fun PlayerViewModel.saveCurrentVideoBitrateAsAdaptiveLimit() {
    val scope = playbackQualityPreferenceScope.value ?: return
    val bitrate = currentObservedVideoBitrate() ?: return
    updatePlaybackQualityPreference(scope) { current ->
        current.copy(maxVideoBitrateBps = QualityLimitOverride.Maximum(bitrate))
    }
}

fun PlayerViewModel.saveCurrentVideoBitrateAsProviderDefaultLimit() {
    val providerId = currentProviderId.takeIf { it > 0L } ?: return
    val bitrate = currentObservedVideoBitrate() ?: return
    updatePlaybackQualityPreference(PlaybackQualityPreferenceScope.providerDefault(providerId)) { current ->
        current.copy(maxVideoBitrateBps = QualityLimitOverride.Maximum(bitrate))
    }
}

fun PlayerViewModel.useInheritedPlaybackHeightLimit() = updateCurrentContentQualityPreference { current ->
    current.copy(maxVideoHeight = QualityLimitOverride.Inherit)
}

fun PlayerViewModel.useInheritedPlaybackBitrateLimit() = updateCurrentContentQualityPreference { current ->
    current.copy(maxVideoBitrateBps = QualityLimitOverride.Inherit)
}

fun PlayerViewModel.useNoAdditionalPlaybackHeightLimit() = updateCurrentContentQualityPreference { current ->
    current.copy(maxVideoHeight = QualityLimitOverride.NoAdditionalLimit)
}

fun PlayerViewModel.useNoAdditionalPlaybackBitrateLimit() = updateCurrentContentQualityPreference { current ->
    current.copy(maxVideoBitrateBps = QualityLimitOverride.NoAdditionalLimit)
}

fun PlayerViewModel.useInheritedProviderHeightLimit() {
    val providerId = currentProviderId.takeIf { it > 0L } ?: return
    updatePlaybackQualityPreference(PlaybackQualityPreferenceScope.providerDefault(providerId)) { current ->
        current.copy(maxVideoHeight = QualityLimitOverride.Inherit)
    }
}

fun PlayerViewModel.useInheritedProviderBitrateLimit() {
    val providerId = currentProviderId.takeIf { it > 0L } ?: return
    updatePlaybackQualityPreference(PlaybackQualityPreferenceScope.providerDefault(providerId)) { current ->
        current.copy(maxVideoBitrateBps = QualityLimitOverride.Inherit)
    }
}

private fun PlayerViewModel.updateCurrentContentQualityPreference(
    update: (ScopedPlaybackQualityPreference) -> ScopedPlaybackQualityPreference
) {
    playbackQualityPreferenceScope.value?.let { scope -> updatePlaybackQualityPreference(scope, update) }
}

private fun PlayerViewModel.updatePlaybackQualityPreference(
    scope: PlaybackQualityPreferenceScope,
    update: (ScopedPlaybackQualityPreference) -> ScopedPlaybackQualityPreference
) {
    viewModelScope.launch {
        playbackQualityPreferenceSaveMutex.withLock {
            if (!isPlaybackQualityPreferenceScopeActive(scope)) return@withLock
            val current = playerPreferencesCoordinator.getPlaybackQualityPreference(scope).first()
                ?: ScopedPlaybackQualityPreference()
            val updated = update(current)
            if (updated != current) {
                playerPreferencesCoordinator.setPlaybackQualityPreference(scope, updated)
            }
            if (isPlaybackQualityPreferenceScopeActive(scope)) {
                playerEngine.selectVideoTrack(PLAYER_TRACK_AUTO_ID)
            }
        }
    }
}

private fun PlayerViewModel.isPlaybackQualityPreferenceScopeActive(
    scope: PlaybackQualityPreferenceScope
): Boolean = when (scope.type) {
    com.streamvault.domain.settings.PlaybackQualityPreferenceScopeType.PROVIDER_DEFAULT ->
        currentProviderId == scope.providerId
    else -> playbackQualityPreferenceScope.value == scope
}

private fun PlayerViewModel.currentObservedVideoHeight(): Int? =
    videoFormat.value.height.takeIf { it > 0 }
        ?: playerEngine.availableVideoTracks.value.firstOrNull { it.isSelected }
            ?.videoHeight?.takeIf { it > 0 }

private fun PlayerViewModel.currentObservedVideoBitrate(): Long? =
    playerEngine.availableVideoTracks.value.firstOrNull { it.isSelected }
        ?.videoBitrateBps?.takeIf { it > 0L }
        ?: videoFormat.value.bitrate.takeIf { it > 0 }?.toLong()

fun PlayerViewModel.removeProviderQualityLimit() {
    val providerId = currentProviderId.takeIf { it > 0L } ?: return
    val scope = PlaybackQualityPreferenceScope.providerDefault(providerId)
    viewModelScope.launch {
        playerPreferencesCoordinator.clearPlaybackQualityPreference(scope)
        if (currentProviderId != providerId) return@launch
        val contentScope = playbackQualityPreferenceScope.value
            ?.takeIf { it.providerId == providerId }
        val contentPreference = contentScope
            ?.let { playerPreferencesCoordinator.getPlaybackQualityPreference(it).first() }
        if (currentProviderId != providerId || playbackQualityPreferenceScope.value != contentScope) return@launch
        playerEngine.setScopedPlaybackQualityPreferences(
            providerDefault = null,
            contentPreference = contentPreference,
            temporarySelection = temporaryPlaybackQualityPreferenceFlow.value
        )
    }
}

fun PlayerViewModel.setTemporaryQualityLimitAtCurrentResolution() {
    val height = videoFormat.value.height.takeIf { it > 0 }
        ?: playerEngine.availableVideoTracks.value.firstOrNull { it.isSelected }
            ?.videoHeight?.takeIf { it > 0 }
        ?: return
    val temporary = ScopedPlaybackQualityPreference(
        maxVideoHeight = QualityLimitOverride.Maximum(height)
    )
    temporaryPlaybackQualityPreferenceFlow.value = temporary
    val requestVersion = prepareRequestVersion
    val preferenceScope = playbackQualityPreferenceScope.value
    val providerId = currentProviderId
    viewModelScope.launch {
        val providerDefault = providerId.takeIf { it > 0L }
            ?.let(PlaybackQualityPreferenceScope::providerDefault)
            ?.let { playerPreferencesCoordinator.getPlaybackQualityPreference(it).first() }
        val contentPreference = preferenceScope
            ?.takeIf { it.providerId == providerId }
            ?.let { playerPreferencesCoordinator.getPlaybackQualityPreference(it).first() }
        if (!isActivePlaybackSession(requestVersion) ||
            temporaryPlaybackQualityPreferenceFlow.value != temporary) return@launch
        playerEngine.setScopedPlaybackQualityPreferences(providerDefault, contentPreference, temporary)
        playerEngine.selectVideoTrack(PLAYER_TRACK_AUTO_ID)
    }
}

fun PlayerViewModel.clearTemporaryQualityLimit() {
    if (temporaryPlaybackQualityPreferenceFlow.value == null) return
    temporaryPlaybackQualityPreferenceFlow.value = null
    val requestVersion = prepareRequestVersion
    val preferenceScope = playbackQualityPreferenceScope.value
    val providerId = currentProviderId
    viewModelScope.launch {
        val providerDefault = providerId.takeIf { it > 0L }
            ?.let(PlaybackQualityPreferenceScope::providerDefault)
            ?.let { playerPreferencesCoordinator.getPlaybackQualityPreference(it).first() }
        val contentPreference = preferenceScope
            ?.takeIf { it.providerId == providerId }
            ?.let { playerPreferencesCoordinator.getPlaybackQualityPreference(it).first() }
        if (!isActivePlaybackSession(requestVersion) ||
            temporaryPlaybackQualityPreferenceFlow.value != null) return@launch
        playerEngine.setScopedPlaybackQualityPreferences(providerDefault, contentPreference, null)
        playerEngine.selectVideoTrack(PLAYER_TRACK_AUTO_ID)
    }
}

fun PlayerViewModel.removeSavedQualityLimit() {
    val scope = playbackQualityPreferenceScope.value ?: return
    viewModelScope.launch {
        playerPreferencesCoordinator.clearPlaybackQualityPreference(scope)
        if (playbackQualityPreferenceScope.value != scope) return@launch
        val providerDefault = playerPreferencesCoordinator.getPlaybackQualityPreference(
            PlaybackQualityPreferenceScope.providerDefault(scope.providerId)
        ).first()
        if (playbackQualityPreferenceScope.value != scope) return@launch
        playerEngine.setScopedPlaybackQualityPreferences(
            providerDefault,
            null,
            temporaryPlaybackQualityPreferenceFlow.value
        )
    }
}

fun PlayerViewModel.selectLiveVariant(rawChannelId: Long) {
    val currentChannel = currentChannelFlow.value?.sanitizedForPlayer() ?: return
    val updatedChannel = currentChannel.withSelectedVariant(rawChannelId)?.sanitizedForPlayer() ?: return
    if (updatedChannel.selectedVariantId == currentChannel.selectedVariantId) return

    val requestVersion = beginPlaybackSession()
    playerRecoveryCoordinator.clearStreamAttempts()
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
    playbackSessionScope(requestVersion)?.launch {
        playerPreferencesCoordinator.setPreferredLiveVariant(
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
    playerRecoveryCoordinator.markStreamAttempt(formatUrl)
    currentStreamUrl = formatUrl
    updateStreamClass("Format")
    playbackSessionScope(requestVersion)?.launch {
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
        val existing = playerPreferencesCoordinator.liveVariantObservations.first()[rawChannelId]
        playerPreferencesCoordinator.recordLiveVariantObservation(
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

fun PlayerViewModel.recordMovieVariantSuccessObservation() {
    if (currentContentType != ContentType.MOVIE) return
    val rawMovieId = currentContentId.takeIf { it > 0L } ?: return
    val signature = "success|$prepareRequestVersion|$rawMovieId"
    if (signature == lastRecordedVodVariantObservationSignature) return
    lastRecordedVodVariantObservationSignature = signature

    viewModelScope.launch {
        val existing = playerPreferencesCoordinator.vodVariantObservations.first()[rawMovieId]
        playerPreferencesCoordinator.recordVodVariantObservation(
            rawItemId = rawMovieId,
            observation = VodVariantObservation(
                successCount = (existing?.successCount ?: 0) + 1,
                failureCount = existing?.failureCount ?: 0,
                lastSuccessfulAt = System.currentTimeMillis(),
                lastFailedAt = existing?.lastFailedAt ?: 0L
            )
        )
    }
}

fun PlayerViewModel.recordMovieVariantFailureObservation(error: PlayerError) {
    if (currentContentType != ContentType.MOVIE) return
    val rawMovieId = currentContentId.takeIf { it > 0L } ?: return
    val signature = buildString {
        append("failure|")
        append(prepareRequestVersion)
        append('|')
        append(rawMovieId)
        append('|')
        append(error::class.java.simpleName)
        append('|')
        append(error.message)
    }
    if (signature == lastRecordedVodVariantObservationSignature) return
    lastRecordedVodVariantObservationSignature = signature

    viewModelScope.launch {
        val existing = playerPreferencesCoordinator.vodVariantObservations.first()[rawMovieId]
        playerPreferencesCoordinator.recordVodVariantObservation(
            rawItemId = rawMovieId,
            observation = VodVariantObservation(
                successCount = existing?.successCount ?: 0,
                failureCount = (existing?.failureCount ?: 0) + 1,
                lastSuccessfulAt = existing?.lastSuccessfulAt ?: 0L,
                lastFailedAt = System.currentTimeMillis()
            )
        )
    }
}

fun PlayerViewModel.setPlaybackSpeed(speed: Float) {
    val normalizedSpeed = speed.coerceIn(0.5f, 2f)
    playerEngine.setPlaybackSpeed(normalizedSpeed)
    viewModelScope.launch {
        playerPreferencesCoordinator.setPlayerPlaybackSpeed(normalizedSpeed)
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

fun PlayerViewModel.adjustSubtitleOffset(deltaMs: Int) {
    val current = subtitleOffsetMs.value
    playerEngine.setSubtitleOffsetMs((current + deltaMs).coerceIn(SUBTITLE_OFFSET_MIN_MS, SUBTITLE_OFFSET_MAX_MS))
}

fun PlayerViewModel.resetSubtitleOffset() {
    playerEngine.setSubtitleOffsetMs(0)
}

internal fun PlayerViewModel.setSubtitleTextScale(scale: Float) {
    viewModelScope.launch { playerPreferencesCoordinator.setSubtitleTextScale(scale) }
}

internal fun PlayerViewModel.setSubtitleBackgroundOpacity(opacity: Int) {
    viewModelScope.launch { playerPreferencesCoordinator.setSubtitleBackgroundOpacity(opacity) }
}

fun PlayerViewModel.dismissAudioVideoOffsetPreview() {
    audioVideoOffsetPreviewMs.value = null
}

fun PlayerViewModel.saveAudioVideoOffsetForChannel() {
    val channelId = currentChannelFlow.value?.id?.takeIf { it > 0L } ?: return
    val offsetMs = _audioVideoOffsetUiState.value.effectiveOffsetMs
    viewModelScope.launch {
        playerPreferencesCoordinator.setAudioVideoOffsetForChannel(channelId, offsetMs)
        audioVideoOffsetPreviewMs.value = null
    }
}

fun PlayerViewModel.saveAudioVideoOffsetAsGlobal() {
    val offsetMs = _audioVideoOffsetUiState.value.effectiveOffsetMs
    val channelId = currentChannelFlow.value?.id?.takeIf { it > 0L }
    viewModelScope.launch {
        playerPreferencesCoordinator.setPlayerAudioVideoOffsetMs(offsetMs)
        if (channelId != null) {
            playerPreferencesCoordinator.clearAudioVideoOffsetForChannel(channelId)
        }
        audioVideoOffsetPreviewMs.value = null
    }
}

fun PlayerViewModel.useGlobalAudioVideoOffset() {
    val channelId = currentChannelFlow.value?.id?.takeIf { it > 0L } ?: return
    viewModelScope.launch {
        playerPreferencesCoordinator.clearAudioVideoOffsetForChannel(channelId)
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
    val previewStreamInfo = currentResolvedStreamInfo
    val isLive = currentContentType == ContentType.LIVE
    val canExtractFrame = previewUrl.isNotBlank() && playerThumbnailCoordinator.supportsFrameExtraction(
        streamUrl = previewUrl,
        streamInfo = previewStreamInfo,
        isLive = isLive
    )

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
        val bitmap = playerThumbnailCoordinator.loadFrame(
            streamUrl = previewUrl,
            streamInfo = previewStreamInfo,
            isLive = isLive,
            positionMs = previewPositionMs
        )
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

internal fun PlayerViewModel.clearSeekPreview() {
    seekPreviewJob?.cancel()
    seekPreviewRequestVersion++
    _seekPreview.value = SeekPreviewState()
}
