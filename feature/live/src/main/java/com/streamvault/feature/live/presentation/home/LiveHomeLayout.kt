package com.streamvault.feature.live.presentation.home

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.streamvault.domain.model.LiveTvChannelMode

data class LiveHomeLayoutMetrics(
    val sidebarWidth: Dp,
    val channelRowHeight: Dp,
    val channelListSpacing: Dp
)

fun liveHomeLayoutMetrics(
    screenWidthDp: Int,
    isTelevisionDevice: Boolean,
    channelMode: LiveTvChannelMode
): LiveHomeLayoutMetrics {
    val screenWidth = screenWidthDp.dp
    val sidebarWidth = if (screenWidth < 900.dp) {
        (screenWidth * 0.36f).coerceIn(188.dp, 220.dp)
    } else if (!isTelevisionDevice && screenWidth < 1280.dp) {
        (screenWidth * 0.28f).coerceIn(220.dp, 252.dp)
    } else {
        272.dp
    }
    val channelRowHeight = when (channelMode) {
        LiveTvChannelMode.COMFORTABLE -> 92.dp
        LiveTvChannelMode.COMPACT -> 54.dp
        LiveTvChannelMode.PRO -> 52.dp
    }
    val channelListSpacing = when (channelMode) {
        LiveTvChannelMode.COMFORTABLE -> 8.dp
        LiveTvChannelMode.COMPACT -> 2.dp
        LiveTvChannelMode.PRO -> 2.dp
    }
    return LiveHomeLayoutMetrics(
        sidebarWidth = sidebarWidth,
        channelRowHeight = channelRowHeight,
        channelListSpacing = channelListSpacing
    )
}
