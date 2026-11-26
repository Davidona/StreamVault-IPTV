package com.streamvault.feature.playback.player

import android.os.SystemClock
import androidx.lifecycle.viewModelScope
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.Episode
import com.streamvault.player.timeshift.LiveTimeshiftStatus
import kotlinx.coroutines.launch

private const val PLAYBACK_CONTROL_MUTE_TOGGLE_DEBOUNCE_MS = 250L

fun PlayerViewModel.seekForward() {
    webhookState.discontinuity = true
    notifyUserActivity()
    emitSeekFeedback(SeekDirection.FORWARD)
    playerEngine.seekForward(SEEK_STEP_MS)
}

fun PlayerViewModel.seekBackward() {
    webhookState.discontinuity = true
    notifyUserActivity()
    emitSeekFeedback(SeekDirection.BACKWARD)
    playerEngine.seekBackward(SEEK_STEP_MS)
}

// Live seeks go through timeshift and have no stable position to show, so feedback is for VOD only.
private fun PlayerViewModel.emitSeekFeedback(direction: SeekDirection) {
    if (currentContentType == ContentType.LIVE) return
    seekTargetTracker.next(
        direction = direction,
        enginePositionMs = playerEngine.currentPosition.value,
        durationMs = playerEngine.duration.value,
        nowMs = SystemClock.elapsedRealtime()
    )?.let(_seekFeedback::tryEmit)
}

fun PlayerViewModel.seekToLiveEdge() {
    webhookState.discontinuity = true
    notifyUserActivity()
    playerEngine.seekToLiveEdge()
}

fun PlayerViewModel.playEpisode(episode: Episode, showResumePrompt: Boolean = true) {
    prepare(
        streamUrl = episode.streamUrl,
        epgChannelId = null,
        internalChannelId = episode.id,
        categoryId = -1,
        providerId = episode.providerId,
        isVirtual = false,
        contentType = ContentType.SERIES_EPISODE.name,
        title = buildEpisodePlaybackTitle(episode),
        artworkUrl = episode.coverUrl ?: currentSeries.value?.posterUrl ?: currentSeries.value?.backdropUrl,
        seriesId = currentSeriesId ?: episode.seriesId.takeIf { it > 0L },
        seasonNumber = episode.seasonNumber,
        episodeNumber = episode.episodeNumber,
        showResumePrompt = showResumePrompt
    )
}

fun PlayerViewModel.playEpisodeFromNavigation(episode: Episode) {
    cancelAutoPlay()
    playEpisode(episode)
}

fun PlayerViewModel.toggleMute() {
    val now = SystemClock.elapsedRealtime()
    if (now - lastMuteToggleAtMs < PLAYBACK_CONTROL_MUTE_TOGGLE_DEBOUNCE_MS) return
    lastMuteToggleAtMs = now
    playerEngine.toggleMute()
    val muted = playerEngine.isMuted.value
    mutePersistJob?.cancel()
    mutePersistJob = viewModelScope.launch {
        playerPreferencesCoordinator.setPlayerMuted(muted)
    }
}

fun PlayerViewModel.toggleControls() {
    closeChannelInfoOverlay()
    showControlsFlow.value = !showControlsFlow.value
    if (!showControlsFlow.value) {
        clearSeekPreview()
    }
}

fun PlayerViewModel.toggleAspectRatio() {
    setAspectRatio(
        when (_aspectRatio.value) {
            AspectRatio.FIT -> AspectRatio.FILL
            AspectRatio.FILL -> AspectRatio.ZOOM
            AspectRatio.ZOOM -> AspectRatio.FIT
        }
    )
}

fun PlayerViewModel.setAspectRatio(ratio: AspectRatio) {
    _aspectRatio.value = ratio
    _aspectRatioChanges.tryEmit(ratio)

    if (currentContentType == ContentType.LIVE && currentContentId > 0L) {
        viewModelScope.launch {
            playerPreferencesCoordinator.setAspectRatioForChannel(currentContentId, ratio.name)
        }
    }
}

fun PlayerViewModel.dismissResumePrompt(resume: Boolean) {
    val prompt = _resumePrompt.value
    _resumePrompt.value = ResumePromptState()
    if (resume && prompt.positionMs > 0) {
        webhookState.discontinuity = true
        playerEngine.seekTo(prompt.positionMs)
    }
    playerEngine.play()
}

fun PlayerViewModel.play() {
    webhookState.handedOff = false
    webhookState.userPaused = false
    notifyUserActivity()
    if (
        currentContentType == ContentType.LIVE &&
        timeshiftConfig.enabled &&
        timeshiftUiState.value.engineState.status == LiveTimeshiftStatus.PAUSED_BEHIND_LIVE
    ) {
        playerEngine.resumeTimeshift()
    } else {
        playerEngine.play()
    }
}

fun PlayerViewModel.pause() {
    webhookState.userPaused = true
    notifyUserActivity()
    if (currentContentType == ContentType.LIVE && timeshiftConfig.enabled) {
        playerEngine.pauseTimeshift()
    } else {
        playerEngine.pause()
    }
}
