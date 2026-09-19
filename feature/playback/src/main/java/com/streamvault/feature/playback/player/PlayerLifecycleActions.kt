package com.streamvault.feature.playback.player

import androidx.lifecycle.viewModelScope
import com.streamvault.domain.model.ContentType
import com.streamvault.player.PlaybackState
import com.streamvault.player.PlayerEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private const val LIFECYCLE_TOKEN_RENEWAL_LEAD_MS = 60_000L
private const val LIFECYCLE_TOKEN_RENEWAL_CHECK_INTERVAL_MS = 10_000L

/** Captures the old content before prepare mutates the shared playback context. */
internal fun PlayerViewModel.queueContentSwitchProgressFlush(): Job? {
    if (currentContentType == ContentType.LIVE) return null
    val history = buildPlaybackHistorySnapshot(
        positionMs = playerEngine.currentPosition.value,
        durationMs = playerEngine.duration.value
    )
    return viewModelScope.launch {
        if (history != null) {
            val result = playbackHistoryCoordinator.updateResumePosition(history)
            logRepositoryFailure(
                operation = "Flush playback progress for content switch",
                result = result
            )
            if (result.isSuccess) {
                playbackHistoryCoordinator.updateWatchNextProgress(history)
            }
        }
        logRepositoryFailure(
            operation = "Flush pending playback progress for content switch",
            result = playbackHistoryCoordinator.flushPendingProgress()
        )
    }
}

/**
 * Queues a lifecycle-boundary flush and returns the job so a transition owner can await it.
 * The job remains in viewModelScope, so a caller that only needs to request the flush may safely
 * ignore the return value.
 */
internal fun PlayerViewModel.queueForcedProgressFlush(): Job? {
    if (currentContentType == ContentType.LIVE) return null
    return viewModelScope.launch {
        persistPlaybackProgress()
        logRepositoryFailure(
            operation = "Flush pending playback progress at lifecycle boundary",
            result = playbackHistoryCoordinator.flushPendingProgress()
        )
    }
}

internal fun PlayerViewModel.startProgressTracking() {
    progressTrackingJob?.cancel()
    if (currentContentType == ContentType.LIVE) return

    val requestVersion = prepareRequestVersion
    progressTrackingJob = playbackSessionScope(requestVersion)?.launch {
        while (true) {
            delay(30_000)
            if (!isAppInForeground || !playerEngine.isPlaying.value) continue
            persistPlaybackProgress()
        }
    }
}

internal suspend fun PlayerViewModel.persistPlaybackProgress() {
    val pos = playerEngine.currentPosition.value
    val dur = playerEngine.duration.value

    if (pos > 0 && dur > 0) {
        val history = buildPlaybackHistorySnapshot(pos, dur) ?: return
        val result = playbackHistoryCoordinator.updateResumePosition(history)
        logRepositoryFailure(
            operation = "Persist playback resume position",
            result = result
        )
        if (result.isSuccess) {
            playbackHistoryCoordinator.updateWatchNextProgress(history)
        }
    }
}

internal fun PlayerViewModel.startTokenRenewalMonitoring(expirationTime: Long?) {
    tokenRenewalJob?.cancel()
    tokenRenewalJob = null
    val expiry = expirationTime?.takeIf { it > 0L } ?: return
    val requestVersion = prepareRequestVersion
    tokenRenewalJob = playbackSessionScope(requestVersion)?.launch {
        while (true) {
            delay(LIFECYCLE_TOKEN_RENEWAL_CHECK_INTERVAL_MS)
            if (!playerEngine.isPlaying.value) continue
            val remaining = expiry - System.currentTimeMillis()
            if (remaining > LIFECYCLE_TOKEN_RENEWAL_LEAD_MS) continue
            if (!isActivePlaybackSession(requestVersion)) return@launch
            val refreshed = resolvePlaybackStreamInfo(
                logicalUrl = currentStreamUrl,
                internalContentId = currentContentId,
                providerId = currentProviderId,
                contentType = currentContentType
            ) ?: return@launch
            if (!isActivePlaybackSession(requestVersion)) return@launch
            currentResolvedPlaybackUrl = refreshed.url
            currentResolvedStreamInfo = refreshed
            playerEngine.renewStreamUrl(refreshed)
            startTokenRenewalMonitoring(refreshed.expirationTime)
            return@launch
        }
    }
}

fun PlayerViewModel.onAppBackgrounded(): Job? {
    if (!isAppInForeground) return null
    isAppInForeground = false
    shouldResumeAfterForeground = playerEngine.isPlaying.value
    if (shouldResumeAfterForeground) {
        playerEngine.pause()
    }
    return queueForcedProgressFlush()
}

fun PlayerViewModel.onAppForegrounded() {
    if (isAppInForeground) return
    isAppInForeground = true
    if (shouldResumeAfterForeground && !resumePrompt.value.show) {
        playerEngine.play()
    }
    shouldResumeAfterForeground = false
}

fun PlayerViewModel.onPlayerScreenDisposed(): Job? {
    val progressFlush = queueForcedProgressFlush()
    clearPreloadWindow()
    playerPlaybackContextCoordinator.clearSelectedCatchUpProgram()
    playerEngine.stopLiveTimeshift()
    stopLiveTranslationSession()
    clearPlaybackTimers()
    return progressFlush
}

internal fun PlayerViewModel.clearPlaybackTimers() {
    stopPlaybackTimerJob?.cancel()
    idleStandbyTimerJob?.cancel()
    stopPlaybackTimerJob = null
    idleStandbyTimerJob = null
    stopPlaybackTimerEndsAtMs = 0L
    idleStandbyTimerEndsAtMs = 0L
    playbackTimerDefaultsApplied = false
    sleepTimerExitEmitted = false
    _sleepTimerUiState.value = SleepTimerUiState()
}

fun PlayerViewModel.handOffPlaybackToMultiView(): Job? {
    val progressFlush = queueForcedProgressFlush()
    clearPreloadWindow()
    playerPlaybackContextCoordinator.clearSelectedCatchUpProgram()
    playerEngine.stopLiveTimeshift()
    stopLiveTranslationSession()
    playerPreviewCoordinator.clear(playerEngine)
    return progressFlush
}

internal fun PlayerViewModel.cleanupAfterCleared(mainPlayerEngine: PlayerEngine) {
    onPlayerScreenDisposed()
    channelInfoHideJob?.cancel()
    liveOverlayHideJob?.cancel()
    diagnosticsHideJob?.cancel()
    numericInputCommitJob?.cancel()
    numericInputFeedbackJob?.cancel()
    playerNoticeHideJob?.cancel()
    epgCoordinator.cancel()
    playlistJob?.cancel()
    controlsHideJob?.cancel()
    zapOverlayJob?.cancel()
    zapBufferWatchdogJob?.cancel()
    progressTrackingJob?.cancel()
    tokenRenewalJob?.cancel()
    aspectRatioJob?.cancel()
    recentChannelsJob?.cancel()
    lastVisitedCategoryJob?.cancel()
    thumbnailPreloadJob?.cancel()
    inFlightThumbnailPreloadKey = null
    lastCompletedThumbnailPreloadKey = null
    playerThumbnailCoordinator.clearCache()

    val activeEngine = playerEngine
    val channel = currentChannel.value
    val streamInfo = currentResolvedStreamInfo
    val canReverseHandoff = currentContentType == ContentType.LIVE
        && !isCatchUpPlayback.value
        && activeEngine !== mainPlayerEngine
        && channel != null
        && streamInfo != null
        && activeEngine.playbackState.value != PlaybackState.ERROR

    if (canReverseHandoff) {
        playerPreviewCoordinator.beginReverseHandoff(
            channel = channel!!,
            streamInfo = streamInfo!!,
            engine = activeEngine,
            source = com.streamvault.feature.playback.preview.PreviewHandoffSource.HOME
        )
        mainPlayerEngine.resetForReuse()
    } else {
        playerPreviewCoordinator.clear(activeEngine)
        if (activeEngine === mainPlayerEngine) {
            mainPlayerEngine.resetForReuse()
        } else {
            activeEngine.release()
            mainPlayerEngine.resetForReuse()
        }
    }
}
