package com.streamvault.feature.playback.player

import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.ContentType
import kotlinx.coroutines.launch

fun PlayerViewModel.hasAlternateStream(): Boolean {
    if (isCatchUpPlayback()) {
        return nextCatchUpVariant() != null
    }
    if (currentContentType != ContentType.LIVE) return false
    val channel = currentChannelFlow.value?.sanitizedForPlayer() ?: return false
    return selectNextLiveRecoveryCandidate(
        channel = channel,
        currentVariantId = channel.selectedVariantId.takeIf { it > 0 } ?: channel.id,
        currentStreamUrl = currentStreamUrl,
        currentResolvedPlaybackUrl = currentResolvedPlaybackUrl,
        triedAlternativeStreams = playerRecoveryCoordinator.streamAttemptSnapshot(),
        failedStreamsThisSession = playerRecoveryCoordinator.failedStreamSnapshot(),
        preferXtreamTsFallback = false
    ) != null
}

fun PlayerViewModel.tryAlternateStream(): Boolean {
    if (isCatchUpPlayback()) {
        return tryNextCatchUpVariantInternal()
    }
    if (currentContentType != ContentType.LIVE) return false
    val channel = currentChannelFlow.value?.sanitizedForPlayer() ?: return false
    return tryAlternateStreamInternal(channel)
}

internal fun PlayerViewModel.tryAlternateStreamInternal(
    channel: Channel,
    preferXtreamTsFallback: Boolean = false,
    allowXtreamTsFallback: Boolean = true
): Boolean {
    val candidate = selectNextLiveRecoveryCandidate(
        channel = channel,
        currentVariantId = channel.selectedVariantId.takeIf { it > 0 } ?: channel.id,
        currentStreamUrl = currentStreamUrl,
        currentResolvedPlaybackUrl = currentResolvedPlaybackUrl,
        triedAlternativeStreams = playerRecoveryCoordinator.streamAttemptSnapshot(),
        failedStreamsThisSession = playerRecoveryCoordinator.failedStreamSnapshot(),
        preferXtreamTsFallback = preferXtreamTsFallback,
        allowXtreamTsFallback = allowXtreamTsFallback
    ) ?: run {
        android.util.Log.w(
            "PlayerVM",
            "live-recovery no-candidate preferTsFallback=$preferXtreamTsFallback " +
                "allowTsFallback=$allowXtreamTsFallback " +
                "hasResolvedUrl=${currentResolvedPlaybackUrl.isNotBlank()} " +
                "alternates=${channel.alternativeStreams.size} variants=${channel.variants.size}"
        )
        return false
    }
    android.util.Log.i(
        "PlayerVM",
        "live-recovery selected=${candidate.kind} preferTsFallback=$preferXtreamTsFallback " +
            "allowTsFallback=$allowXtreamTsFallback"
    )

    if (candidate.kind == LiveRecoveryCandidateKind.VARIANT) {
        val nextVariant = candidate.variant ?: return false
        val updatedChannel = channel.withSelectedVariant(nextVariant.rawChannelId)?.sanitizedForPlayer()
            ?: return false
        val requestVersion = beginPlaybackSession()
        playerRecoveryCoordinator.markStreamAttempt(nextVariant.streamUrl)
        currentContentId = updatedChannel.id
        currentStreamUrl = updatedChannel.streamUrl
        currentTitle = nextVariant.originalName.ifBlank { updatedChannel.name }
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
                rawChannelId = nextVariant.rawChannelId
            )
            val streamInfo = resolvePlaybackStreamInfo(
                logicalUrl = nextVariant.streamUrl,
                internalContentId = updatedChannel.id,
                providerId = updatedChannel.providerId,
                contentType = ContentType.LIVE
            ) ?: return@launch
            if (!isActivePlaybackSession(requestVersion, nextVariant.streamUrl)) return@launch
            requestEpg(
                providerId = updatedChannel.providerId,
                epgChannelId = updatedChannel.epgChannelId,
                streamId = updatedChannel.streamId,
                internalChannelId = updatedChannel.id
            )
            if (!preparePlayer(streamInfo.copy(title = streamInfo.title ?: currentTitle), requestVersion)) return@launch
            playerEngine.play()
        }
        return true
    }

    val requestVersion = beginPlaybackSession()
    val nextStream = candidate.url
    playerRecoveryCoordinator.markStreamAttempt(nextStream)
    currentStreamUrl = nextStream
    updateStreamClass(
        when (candidate.kind) {
            LiveRecoveryCandidateKind.XTREAM_TS_FALLBACK -> "MPEG-TS fallback"
            LiveRecoveryCandidateKind.ALTERNATE -> "Alternate"
            LiveRecoveryCandidateKind.VARIANT -> "Variant"
        }
    )
    playbackSessionScope(requestVersion)?.launch {
        val streamInfo = resolvePlaybackStreamInfo(nextStream, channel.id, channel.providerId, ContentType.LIVE)
            ?: return@launch
        if (!isActivePlaybackSession(requestVersion, nextStream)) return@launch
        if (!preparePlayer(streamInfo.copy(title = streamInfo.title ?: currentTitle), requestVersion)) return@launch
        playerEngine.play()
    }
    return true
}

internal fun PlayerViewModel.isCatchUpPlayback(): Boolean = isCatchUpPlayback.value

private fun PlayerViewModel.nextCatchUpVariant(): String? {
    return selectNextAlternateUrl(
        candidateUrls = pendingCatchUpUrls,
        currentStreamUrl = currentStreamUrl,
        triedAlternativeStreams = playerRecoveryCoordinator.streamAttemptSnapshot(),
        failedStreamsThisSession = playerRecoveryCoordinator.failedStreamSnapshot()
    )
}

internal fun PlayerViewModel.tryNextCatchUpVariantInternal(): Boolean {
    val nextStream = nextCatchUpVariant() ?: return false
    val requestVersion = beginPlaybackSession()
    playerRecoveryCoordinator.markStreamAttempt(nextStream)
    currentStreamUrl = nextStream
    updateStreamClass("Catch-up")
    playbackSessionScope(requestVersion)?.launch {
        val streamInfo = resolveCatchUpStreamInfo(
            candidateUrl = nextStream,
            title = currentTitle,
            currentContentId = currentContentId,
            currentProviderId = currentProviderId,
            resolveStreamInfo = ::resolvePlaybackStreamInfo
        )
            ?: return@launch
        if (!isActivePlaybackSession(requestVersion, nextStream)) return@launch
        if (!preparePlayer(streamInfo, requestVersion)) return@launch
        playerEngine.play()
    }
    return true
}
