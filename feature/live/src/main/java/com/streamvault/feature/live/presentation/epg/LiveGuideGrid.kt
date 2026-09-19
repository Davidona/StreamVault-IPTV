package com.streamvault.feature.live.presentation.epg

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.Program
import com.streamvault.feature.live.presentation.model.guideLookupKey
import kotlinx.coroutines.delay

fun liveGuideInitialFocusIndex(
    channels: List<Channel>,
    initialFocusedChannelId: Long?
): Int {
    val resolvedInitialChannelId = initialFocusedChannelId ?: channels.firstOrNull()?.id
    return resolvedInitialChannelId
        ?.let { channelId -> channels.indexOfFirst { it.id == channelId } }
        ?.takeIf { it >= 0 }
        ?: 0
}

@Composable
fun LiveGuideGrid(
    modifier: Modifier = Modifier,
    channels: List<Channel>,
    favoriteChannelIds: Set<Long>,
    programsByChannel: Map<String, List<Program>>,
    guideWindowStart: Long,
    guideWindowEnd: Long,
    density: GuideDensity,
    labels: LiveGuideGridLabels,
    transparentOverlay: Boolean = false,
    initialFocusedChannelId: Long? = null,
    onChannelClick: (Channel) -> Unit,
    onChannelLongClick: ((Channel, Program?) -> Unit)? = null,
    onProgramClick: (Channel, Program) -> Unit,
    onChannelFocused: (Channel, Program?, Boolean) -> Unit,
    onProgramFocused: (Channel, Program, Boolean) -> Unit,
    onRequestMoreChannels: () -> Unit = {}
) {
    val channelRailWidth = 180.dp
    val timelineGap = 4.dp
    val rowHeight = when (density) {
        GuideDensity.COMPACT -> 38.dp
        GuideDensity.COMFORTABLE -> 44.dp
        GuideDensity.CINEMATIC -> 52.dp
    }
    val horizontalScrollState = rememberScrollState()
    val verticalListState = rememberLazyListState()
    val resolvedInitialChannelId = initialFocusedChannelId ?: channels.firstOrNull()?.id
    val initialFocusRequester = remember(resolvedInitialChannelId) { FocusRequester() }
    val initialFocusIndex = remember(channels, resolvedInitialChannelId) {
        liveGuideInitialFocusIndex(channels, resolvedInitialChannelId)
    }

    LaunchedEffect(channels.size, resolvedInitialChannelId) {
        if (channels.isEmpty()) return@LaunchedEffect
        verticalListState.scrollToItem((initialFocusIndex - 2).coerceAtLeast(0))
        delay(140)
        initialFocusRequester.requestFocus()
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 2.dp)
    ) {
        val timelineViewportWidth = (maxWidth - channelRailWidth - timelineGap).coerceAtLeast(640.dp)
        val totalDuration = (guideWindowEnd - guideWindowStart).coerceAtLeast(1L)
        val visibleDurationMs = 3 * 60 * 60 * 1000L
        val calculatedTimelineWidth = timelineViewportWidth * (totalDuration.toFloat() / visibleDurationMs.toFloat())
        val totalTimelineWidth = if (calculatedTimelineWidth > timelineViewportWidth) {
            calculatedTimelineWidth
        } else {
            timelineViewportWidth
        }
        val markerStepMs = LIVE_GUIDE_MARKER_STEP_MS

        Column(modifier = Modifier.fillMaxSize()) {
            LiveGuideTimelineHeader(
                windowStart = guideWindowStart,
                windowEnd = guideWindowEnd,
                channelRailWidth = channelRailWidth,
                timelineGap = timelineGap,
                timelineViewportWidth = timelineViewportWidth,
                totalTimelineWidth = totalTimelineWidth,
                markerStepMs = markerStepMs,
                scrollState = horizontalScrollState
            )
            Spacer(modifier = Modifier.height(4.dp))
            LazyColumn(
                state = verticalListState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                itemsIndexed(
                    items = channels,
                    key = { index, channel -> liveEpgChannelKey(channel, index) },
                    contentType = { _, _ -> "epg_channel" }
                ) { index, channel ->
                    if (index >= channels.size - 15) {
                        LaunchedEffect(channels.size) { onRequestMoreChannels() }
                    }
                    val programs = channel.guideLookupKey()?.let { lookupKey ->
                        programsByChannel[lookupKey].orEmpty()
                    }.orEmpty()
                    val isFirstRow = index == 0
                    LiveGuideGridRow(
                        channel = channel,
                        isFavorite = channel.id in favoriteChannelIds,
                        programs = programs,
                        windowStart = guideWindowStart,
                        windowEnd = guideWindowEnd,
                        channelRailWidth = channelRailWidth,
                        timelineGap = timelineGap,
                        timelineViewportWidth = timelineViewportWidth,
                        totalTimelineWidth = totalTimelineWidth,
                        density = density,
                        transparentOverlay = transparentOverlay,
                        rowHeight = rowHeight,
                        markerStepMs = markerStepMs,
                        scrollState = horizontalScrollState,
                        labels = labels,
                        focusRequester = if (channel.id == resolvedInitialChannelId) initialFocusRequester else null,
                        onChannelClick = { onChannelClick(channel) },
                        onChannelLongClick = onChannelLongClick?.let { cb -> { prog -> cb(channel, prog) } },
                        onChannelFocused = { onChannelFocused(channel, it, isFirstRow) },
                        onProgramClick = { program -> onProgramClick(channel, program) },
                        onProgramFocused = { program -> onProgramFocused(channel, program, isFirstRow) }
                    )
                }
            }
        }
    }
}
