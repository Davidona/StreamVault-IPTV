package com.streamvault.app.ui.screens.player

import androidx.lifecycle.viewModelScope
import com.streamvault.domain.playback.isArchivePlayable
import com.streamvault.domain.model.ContentType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

fun PlayerViewModel.dismissPlayerNotice() {
    playerNoticeHideJob?.cancel()
    _playerNotice.value = null
}

internal fun PlayerViewModel.dismissRecoveredNoticeIfPresent() {
    val notice = _playerNotice.value ?: return
    if (notice.isRetryNotice || notice.recoveryType != PlayerRecoveryType.UNKNOWN) {
        dismissPlayerNotice()
    }
}

fun PlayerViewModel.runPlayerNoticeAction(action: PlayerNoticeAction) {
    when (action) {
        PlayerNoticeAction.RETRY -> {
            appendRecoveryAction("Manual retry")
            retryStream(currentStreamUrl, currentChannelFlow.value?.epgChannelId)
        }
        PlayerNoticeAction.LAST_CHANNEL -> {
            appendRecoveryAction("Returned to last channel")
            zapToLastChannel()
        }
        PlayerNoticeAction.ALTERNATE_STREAM -> {
            appendRecoveryAction("Manual alternate stream")
            tryAlternateStream()
        }
        PlayerNoticeAction.OPEN_GUIDE -> {
            appendRecoveryAction("Opened guide from recovery")
            openEpgOverlay()
        }
    }
    dismissPlayerNotice()
}

internal fun PlayerViewModel.showPlayerNotice(
    message: String,
    recoveryType: PlayerRecoveryType = PlayerRecoveryType.UNKNOWN,
    actions: List<PlayerNoticeAction> = emptyList(),
    durationMs: Long = playerNoticeTimeoutMs,
    isRetryNotice: Boolean = false
) {
    playerNoticeHideJob?.cancel()
    _playerNotice.value = PlayerNoticeState(
        message = message,
        recoveryType = recoveryType,
        actions = actions.distinct(),
        isRetryNotice = isRetryNotice
    )
    playerNoticeHideJob = viewModelScope.launch {
        delay(durationMs)
        if (_playerNotice.value?.message == message) {
            _playerNotice.value = null
        }
    }
}

internal fun PlayerViewModel.showRetryNotice(status: com.streamvault.player.PlayerRetryStatus) {
    val formatLabel = resolvePlaybackFormatLabel(
        currentResolvedPlaybackUrl = currentResolvedPlaybackUrl,
        currentStreamUrl = currentStreamUrl
    )
    val message = buildRetryNoticeMessage(formatLabel, status)
    showPlayerNotice(
        message = message,
        recoveryType = PlayerRecoveryType.NETWORK,
        durationMs = maxOf(playerNoticeTimeoutMs, status.delayMs + 1500L),
        isRetryNotice = true
    )
}

internal fun buildRetryNoticeMessage(
    formatLabel: String,
    status: com.streamvault.player.PlayerRetryStatus
): String {
    val retryLabel = "Retrying $formatLabel ${status.attempt}/${status.maxAttempts}"
    val delaySeconds = status.delayMs / 1_000L
    return if (delaySeconds >= 1L) {
        "$retryLabel in ${delaySeconds}s..."
    } else {
        "$retryLabel..."
    }
}

fun PlayerViewModel.restartCurrentProgram() {
    val program = currentProgram.value ?: return
    val channel = currentChannelFlow.value ?: return
    if (channel.isArchivePlayable(program)) {
        playCatchUp(program)
    }
}

fun PlayerViewModel.retryStream(streamUrl: String, epgChannelId: String?) {
    if (isCatchUpPlayback.value) {
        val requestVersion = beginPlaybackSession()
        playbackSessionScope(requestVersion)?.launch {
            val streamInfo = resolvePlaybackStreamInfo(streamUrl, currentContentId, currentProviderId, currentContentType)
                ?: return@launch
            if (!isActivePlaybackSession(requestVersion, streamUrl)) return@launch
            if (!preparePlayer(streamInfo.copy(title = streamInfo.title ?: currentTitle), requestVersion)) return@launch
            playerEngine.play()
        }
        return
    }
    val currentId = if (currentContentType == ContentType.LIVE) {
        if (currentChannelIndex != -1 && channelList.isNotEmpty()) channelList[currentChannelIndex].id else -1L
    } else {
        currentContentId
    }
    prepare(
        streamUrl = streamUrl,
        epgChannelId = epgChannelId,
        internalChannelId = currentId,
        categoryId = currentCategoryId,
        providerId = currentProviderId,
        isVirtual = isVirtualCategory,
        combinedProfileId = currentCombinedProfileId,
        combinedSourceFilterProviderId = currentCombinedSourceFilterProviderId,
        contentType = currentContentType.name,
        title = currentTitle,
        artworkUrl = currentArtworkUrl,
        seriesId = currentSeriesId,
        seasonNumber = currentSeasonNumber,
        episodeNumber = currentEpisodeNumber,
        episodeId = currentStableEpisodeId
    )
}
