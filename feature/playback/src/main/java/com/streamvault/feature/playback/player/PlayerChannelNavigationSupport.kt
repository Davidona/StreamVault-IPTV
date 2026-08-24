package com.streamvault.feature.playback.player

import com.streamvault.domain.model.Channel

internal fun resolveLiveChannelIndex(
    channelList: List<Channel>,
    currentChannelIndex: Int,
    currentContentId: Long,
    currentStreamUrl: String
): Int {
    if (channelList.isEmpty()) return -1

    val currentIndexMatchesChannel = currentChannelIndex in channelList.indices && run {
        val currentChannel = channelList[currentChannelIndex]
        currentChannel.id == currentContentId || currentChannel.streamUrl == currentStreamUrl
    }
    if (currentIndexMatchesChannel) {
        return currentChannelIndex
    }

    val indexByContentId = when {
        currentContentId > 0 -> channelList.indexOfFirst { it.id == currentContentId }
        else -> -1
    }
    if (indexByContentId != -1) {
        return indexByContentId
    }

    return channelList.indexOfFirst { it.streamUrl == currentStreamUrl }
}

internal fun computeWrappedChannelIndex(
    resolvedIndex: Int,
    channelCount: Int,
    offset: Int
): Int {
    if (resolvedIndex == -1 || channelCount <= 0) return -1
    return ((resolvedIndex + offset) % channelCount + channelCount) % channelCount
}