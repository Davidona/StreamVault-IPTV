package com.streamvault.feature.playback.player

import androidx.lifecycle.viewModelScope
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.ProviderType
import com.streamvault.player.PlaybackState
import com.streamvault.player.PlayerError
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val PROVIDER_AUTH_RETRY_GRACE_MS = 1_200L

internal fun PlayerViewModel.buildRecoveryActions(recoveryType: PlayerRecoveryType): List<PlayerNoticeAction> {
    return PlayerRecoveryPolicy.buildActions(
        hasAlternateStream = hasAlternateStream(),
        hasLastChannel = hasLastChannel(),
        shouldOfferGuide = recoveryType == PlayerRecoveryType.CATCH_UP && currentContentType == ContentType.LIVE
    )
}

internal fun shouldAttemptProviderAuthRetry(
    providerType: ProviderType,
    contentType: ContentType
): Boolean = PlayerRecoveryPolicy.shouldAttemptProviderAuthRetry(providerType, contentType)

internal fun shouldCooldownLivePreloadAfterError(message: String?): Boolean {
    return PlayerRecoveryPolicy.shouldCooldownLivePreloadAfterError(message)
}

internal fun PlayerViewModel.cooldownLivePreloadForCurrentProvider(reason: String) {
    val providerId = currentProviderId.takeIf { it > 0L } ?: return
    if (playerRecoveryCoordinator.markLivePreloadCoolingDown(providerId)) {
        appendRecoveryAction("Disabled live preload for provider: $reason")
        playerEngine.preload(null)
    }
}

internal suspend fun PlayerViewModel.tryRefreshXtreamPlaybackAfterAuthError(
    error: PlayerError,
    requestVersion: Long,
    playbackUrl: String
): Boolean {
    if (!playerRecoveryCoordinator.canRetryXtreamAuthRefresh()) return false
    if (error !is PlayerError.NetworkError) return false
    if (!isAuthExpiryPlaybackError(error.message)) return false
    if (!isXtreamPlaybackSession()) return false

    val refreshedStreamInfo = resolvePlaybackStreamInfo(
        logicalUrl = currentStreamUrl,
        internalContentId = currentContentId,
        providerId = currentProviderId,
        contentType = currentContentType
    ) ?: return false

    if (!isActivePlaybackSession(requestVersion, playbackUrl)) return false

    playerRecoveryCoordinator.markXtreamAuthRefreshRetried()
    probePassedPlaybackKeys.remove(
        resolvePlaybackProbeCacheKey(
            currentStreamUrl = currentStreamUrl,
            url = playbackUrl
        )
    )
    setLastFailureReason(error.message)
    cooldownLivePreloadForCurrentProvider("auth or provider-limit response")
    appendRecoveryAction("Retrying provider playback from a fresh live URL")
    delay(PROVIDER_AUTH_RETRY_GRACE_MS)
    if (!isActivePlaybackSession(requestVersion, playbackUrl)) return true
    clearResolvedStream()
    playerEngine.preload(null)
    if (!preparePlayer(refreshedStreamInfo, requestVersion, probeBeforePlayback = false)) return true
    playerEngine.play()
    return true
}

internal suspend fun PlayerViewModel.isXtreamPlaybackSession(): Boolean {
    val providerId = currentProviderId.takeIf { it > 0L } ?: return false
    val provider = playerProviderCoordinator.getProvider(providerId) ?: return false
    return shouldAttemptProviderAuthRetry(provider.type, currentContentType)
}

internal fun PlayerViewModel.fallbackToPreviousChannel(reason: String): Boolean {
    val fallbackIndex = previousChannelIndex
    if (fallbackIndex in channelList.indices && fallbackIndex != currentChannelIndex) {
        android.util.Log.w("PlayerVM", "Falling back to previous channel: $reason")
        val savedPrevious = previousChannelIndex
        changeChannel(fallbackIndex, isAutoFallback = true)
        previousChannelIndex = savedPrevious
        return true
    }
    return false
}

internal fun PlayerViewModel.scheduleZapBufferWatchdog(targetIndex: Int) {
    if (!zapAutoRevertEnabled) return
    zapBufferWatchdogJob?.cancel()
    val requestVersion = prepareRequestVersion
    zapBufferWatchdogJob = playbackSessionScope(requestVersion)?.launch {
        repeat(15) {
            delay(1000)
            if (!isActivePlaybackSession(requestVersion)) return@launch
            if (currentChannelIndex != targetIndex) return@launch
            val state = playerEngine.playbackState.value
            if (state == PlaybackState.READY || state == PlaybackState.ENDED) return@launch
        }
        if (!isActivePlaybackSession(requestVersion)) return@launch
        val stillOnTarget = currentChannelIndex == targetIndex
        val state = playerEngine.playbackState.value
        val stalled = state == PlaybackState.BUFFERING || state == PlaybackState.ERROR
        if (stillOnTarget && stalled) {
            markStreamFailure(currentStreamUrl)
            setLastFailureReason("Channel timed out in buffering state")
            appendRecoveryAction("Buffer watchdog triggered")
            val recovered = fallbackToPreviousChannel("Channel timed out in buffering state")
            showPlayerNotice(
                message = if (recovered) {
                    "That channel stalled too long. Returned to the last channel."
                } else {
                    "That channel stalled too long. Try another source or open the guide."
                },
                recoveryType = PlayerRecoveryType.BUFFER_TIMEOUT,
                actions = buildRecoveryActions(PlayerRecoveryType.BUFFER_TIMEOUT)
            )
        }
    }
}
