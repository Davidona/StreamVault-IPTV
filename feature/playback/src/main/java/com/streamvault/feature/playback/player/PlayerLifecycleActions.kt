package com.streamvault.feature.playback.player

import androidx.lifecycle.viewModelScope
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.StreamInfo
import com.streamvault.player.PlaybackState
import com.streamvault.player.PlayerEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val LIFECYCLE_TOKEN_RENEWAL_LEAD_MS = 60_000L
private const val LIFECYCLE_TOKEN_RENEWAL_RECHECK_INTERVAL_MS = 15_000L
private const val LIFECYCLE_TOKEN_RENEWAL_FINAL_GRACE_MS = 15_000L

internal fun isSamePlaybackRequestForRenewal(current: StreamInfo, refreshed: StreamInfo): Boolean =
    current.copy(title = null, expirationTime = null) ==
        refreshed.copy(title = null, expirationTime = null)

internal fun shouldArmNextTokenRenewal(
    previousExpiryTime: Long,
    refreshedExpiryTime: Long?,
    nowMs: Long
): Boolean {
    val refreshedExpiry = refreshedExpiryTime ?: return false
    if (refreshedExpiry >= Long.MAX_VALUE || refreshedExpiry <= previousExpiryTime) return false
    return refreshedExpiry > nowMs && refreshedExpiry - nowMs > LIFECYCLE_TOKEN_RENEWAL_LEAD_MS
}

internal fun nextTokenRenewalCheckAtMs(
    previousExpiryTime: Long,
    refreshedExpiryTime: Long?,
    nowMs: Long
): Long? {
    val refreshedExpiry = refreshedExpiryTime?.takeIf { it > 0L && it < Long.MAX_VALUE } ?: return null
    if (refreshedExpiry > nowMs && refreshedExpiry - nowMs > LIFECYCLE_TOKEN_RENEWAL_LEAD_MS) {
        return refreshedExpiry - LIFECYCLE_TOKEN_RENEWAL_LEAD_MS
    }

    val latestExpiry = maxOf(previousExpiryTime, refreshedExpiry)
    val lastCheckAt = if (latestExpiry > Long.MAX_VALUE - LIFECYCLE_TOKEN_RENEWAL_FINAL_GRACE_MS) {
        Long.MAX_VALUE
    } else {
        latestExpiry + LIFECYCLE_TOKEN_RENEWAL_FINAL_GRACE_MS
    }
    if (nowMs >= lastCheckAt) return null

    val nextIntervalAt = if (nowMs > Long.MAX_VALUE - LIFECYCLE_TOKEN_RENEWAL_RECHECK_INTERVAL_MS) {
        Long.MAX_VALUE
    } else {
        nowMs + LIFECYCLE_TOKEN_RENEWAL_RECHECK_INTERVAL_MS
    }
    return minOf(nextIntervalAt, lastCheckAt)
}

internal fun isUsableTokenRenewalExpiry(
    previousExpiryTime: Long,
    refreshedExpiryTime: Long?,
    nowMs: Long
): Boolean {
    val refreshedExpiry = refreshedExpiryTime ?: return true
    if (refreshedExpiry == 0L || refreshedExpiry == Long.MAX_VALUE) return true
    return refreshedExpiry > previousExpiryTime && refreshedExpiry > nowMs
}

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
    val history = buildPlaybackHistorySnapshot(playerEngine.currentPosition.value, playerEngine.duration.value)
    return viewModelScope.launch {
        if (history != null) {
            val result = playbackHistoryCoordinator.updateResumePosition(history)
            logRepositoryFailure("Persist playback resume position", result)
            if (result.isSuccess) playbackHistoryCoordinator.updateWatchNextProgress(history)
        }
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

internal fun PlayerViewModel.startTokenRenewalMonitoring(
    expirationTime: Long?,
    scheduledCheckAtMs: Long? = null
) {
    tokenRenewalJob?.cancel()
    tokenRenewalJob = null
    val expiry = expirationTime?.takeIf { it > 0L && it < Long.MAX_VALUE } ?: return
    val requestVersion = prepareRequestVersion
    val episodeToken = episodeSourceState.token
    tokenRenewalJob = playbackSessionScope(requestVersion)?.launch {
        val nowBeforeWait = System.currentTimeMillis()
        val defaultCheckAt = (expiry - LIFECYCLE_TOKEN_RENEWAL_LEAD_MS).coerceAtLeast(nowBeforeWait)
        val checkAt = scheduledCheckAtMs ?: defaultCheckAt
        delay((checkAt - nowBeforeWait).coerceAtLeast(0L))
        if (!isActivePlaybackSession(requestVersion) || !episodeSourceState.isCurrent(episodeToken)) return@launch

        // If playback is paused at the deadline, wait for resume instead of polling in the
        // background. Session cancellation still releases this wait when content changes.
        playerEngine.isPlaying.first { isPlaying -> isPlaying }
        if (!isActivePlaybackSession(requestVersion) || !episodeSourceState.isCurrent(episodeToken)) return@launch

        val refreshed = resolvePlaybackStreamInfo(
            logicalUrl = currentStreamUrl,
            internalContentId = currentContentId,
            providerId = currentProviderId,
            contentType = currentContentType
        ) ?: return@launch
        if (!isActivePlaybackSession(requestVersion) || !episodeSourceState.isCurrent(episodeToken)) return@launch

        val now = System.currentTimeMillis()
        val refreshedExpiry = refreshed.expirationTime
        val sameRequest = currentResolvedStreamInfo?.let {
            isSamePlaybackRequestForRenewal(it, refreshed)
        } == true
        val hasUsableChangedRequest = !sameRequest && refreshedExpiry?.let { it > now } == true
        if (!isUsableTokenRenewalExpiry(expiry, refreshedExpiry, now) && !hasUsableChangedRequest) {
            android.util.Log.w(
                "PlayerVM",
                "token-renewal skipped reason=stale-expiry current-remaining-ms=${expiry - now} " +
                "refreshed-remaining-ms=${refreshedExpiry?.minus(now)}"
            )
            nextTokenRenewalCheckAtMs(expiry, refreshedExpiry, now)?.let { nextCheckAt ->
                startTokenRenewalMonitoring(expiry, scheduledCheckAtMs = nextCheckAt)
            }
            return@launch
        }

        android.util.Log.i(
            "PlayerVM",
            "token-renewal request-changed=${!sameRequest} current-remaining-ms=${expiry - now} " +
                "refreshed-remaining-ms=${refreshedExpiry?.minus(now)}"
        )
        currentResolvedPlaybackUrl = refreshed.url
        currentResolvedStreamInfo = refreshed

        if (sameRequest) {
            // A timestamp or display-title change cannot justify replacing an identical request.
            // Recheck only during the final renewal window instead of discarding a healthy buffer.
            nextTokenRenewalCheckAtMs(expiry, refreshedExpiry, now)?.let { nextCheckAt ->
                startTokenRenewalMonitoring(refreshedExpiry, scheduledCheckAtMs = nextCheckAt)
            }
            return@launch
        }

        playerEngine.renewStreamUrl(refreshed)
        nextTokenRenewalCheckAtMs(expiry, refreshedExpiry, now)?.let { nextCheckAt ->
            startTokenRenewalMonitoring(refreshedExpiry, scheduledCheckAtMs = nextCheckAt)
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
    episodeSourceState.reset(prepareRequestVersion)
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
    episodeSourceState.reset(prepareRequestVersion)
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
    playerThumbnailCoordinator.clearCache()

    val activeEngine = playerEngine
    val channel = currentChannel.value
    val streamInfo = currentResolvedStreamInfo
    val reverseHandoffSource = playerPreviewCoordinator.consumeAdoptedHandoffSource()
    val canReverseHandoff = currentContentType == ContentType.LIVE
        && !isCatchUpPlayback.value
        && activeEngine !== mainPlayerEngine
        && channel != null
        && streamInfo != null
        && reverseHandoffSource != null
        && activeEngine.playbackState.value != PlaybackState.ERROR

    if (canReverseHandoff) {
        playerPreviewCoordinator.beginReverseHandoff(
            channel = channel!!,
            streamInfo = streamInfo!!,
            engine = activeEngine,
            source = reverseHandoffSource!!
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
