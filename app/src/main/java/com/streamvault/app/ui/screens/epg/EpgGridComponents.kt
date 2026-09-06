package com.streamvault.app.ui.screens.epg

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.streamvault.app.R
import com.streamvault.app.ui.components.ChannelLogoBadge
import com.streamvault.app.ui.interaction.TvButton
import com.streamvault.app.ui.interaction.TvClickableSurface
import com.streamvault.app.ui.model.archivePlaybackCapability
import com.streamvault.app.ui.model.guideLookupKey
import com.streamvault.app.ui.time.LocalAppTimeFormat
import com.streamvault.app.ui.time.createTimeFormatter
import com.streamvault.app.ui.theme.FocusBorder
import com.streamvault.app.ui.design.MaterialVerticalScrollbar
import com.streamvault.app.ui.theme.OnSurface
import com.streamvault.app.ui.theme.OnSurfaceDim
import com.streamvault.app.ui.theme.Primary
import com.streamvault.app.ui.theme.SurfaceElevated
import com.streamvault.app.ui.theme.SurfaceHighlight
import com.streamvault.app.ui.theme.TextPrimary
import com.streamvault.app.ui.theme.TextSecondary
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.Program
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.delay
import kotlin.math.max

@Composable
internal fun GuideMessageState(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String?,
    actionLabel: String?,
    onAction: (() -> Unit)?
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(horizontal = 32.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = OnSurface
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnSurfaceDim
                )
            }
            if (actionLabel != null && onAction != null) {
                TvButton(
                    onClick = onAction,
                    colors = ButtonDefaults.colors(
                        containerColor = Primary,
                        contentColor = Color.White
                    )
                ) {
                    Text(actionLabel)
                }
            }
        }
    }
}

@Composable
internal fun EpgGrid(
    modifier: Modifier = Modifier,
    channels: List<Channel>,
    favoriteChannelIds: Set<Long>,
    programsByChannel: Map<String, List<Program>>,
    guideWindowStart: Long,
    guideWindowEnd: Long,
    density: GuideDensity,
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
        resolvedInitialChannelId
            ?.let { channelId -> channels.indexOfFirst { it.id == channelId } }
            ?.takeIf { it >= 0 }
            ?: 0
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
        val markerStepMs = EpgViewModel.HALF_HOUR_SHIFT_MS

        Column(modifier = Modifier.fillMaxSize()) {
            GuideTimelineHeader(
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
            Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = verticalListState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                itemsIndexed(
                    items = channels,
                    key = { index, channel -> epgChannelKey(channel, index) }
                ) { index, channel ->
                    if (index >= channels.size - 15) {
                        LaunchedEffect(channels.size) { onRequestMoreChannels() }
                    }
                    val programs = channel.guideLookupKey()?.let { lookupKey ->
                        programsByChannel[lookupKey].orEmpty()
                    }.orEmpty()
                    val isFirstRow = index == 0
                    EpgRow(
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
                        focusRequester = if (channel.id == resolvedInitialChannelId) initialFocusRequester else null,
                        onChannelClick = { onChannelClick(channel) },
                        onChannelLongClick = onChannelLongClick?.let { cb -> { prog -> cb(channel, prog) } },
                        onChannelFocused = { onChannelFocused(channel, it, isFirstRow) },
                        onProgramClick = { program -> onProgramClick(channel, program) },
                        onProgramFocused = { program -> onProgramFocused(channel, program, isFirstRow) }
                    )
                }
            }
                MaterialVerticalScrollbar(state = verticalListState)
        }
        }
    }
}

@Composable
private fun GuideTimelineHeader(
    windowStart: Long,
    windowEnd: Long,
    channelRailWidth: Dp,
    timelineGap: Dp,
    timelineViewportWidth: Dp,
    totalTimelineWidth: Dp,
    markerStepMs: Long,
    scrollState: androidx.compose.foundation.ScrollState
) {
    val now = currentGuideNow()
    val appTimeFormat = LocalAppTimeFormat.current
    val hourFormat = remember(appTimeFormat) { appTimeFormat.createTimeFormatter() }
    val zone = remember { ZoneId.systemDefault() }
    val totalDuration = (windowEnd - windowStart).coerceAtLeast(1L)
    val clampedNow = now.coerceIn(windowStart, windowEnd)
    val elapsedRatio = ((clampedNow - windowStart).toFloat() / totalDuration.toFloat()).coerceIn(0f, 1f)
    val markerLabel = if (now in windowStart..windowEnd) {
        stringResource(R.string.epg_now_marker, hourFormat.format(Instant.ofEpochMilli(now).atZone(zone)))
    } else {
        stringResource(R.string.epg_outside_window)
    }
    val hourMarkers = buildList {
        val firstMarker = windowStart - (windowStart % markerStepMs)
        var marker = firstMarker
        while (marker <= windowEnd) {
            add(marker)
            marker += markerStepMs
        }
        if (lastOrNull() != windowEnd) {
            add(windowEnd)
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 2.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        Spacer(modifier = Modifier.width(channelRailWidth + timelineGap))
        Column(
            modifier = Modifier.width(timelineViewportWidth),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Box(
                modifier = Modifier
                    .width(timelineViewportWidth)
                    .height(20.dp)
            ) {
                Row(
                    modifier = Modifier
                        .width(totalTimelineWidth)
                        .horizontalScroll(scrollState)
                ) {
                    Box(
                        modifier = Modifier
                            .width(totalTimelineWidth)
                            .height(20.dp)
                    ) {
                        hourMarkers.forEach { marker ->
                            val markerRatio = ((marker - windowStart).toFloat() / totalDuration.toFloat()).coerceIn(0f, 1f)
                            val markerOffset = totalTimelineWidth * markerRatio
                            Column(
                                modifier = Modifier.padding(start = markerOffset),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = hourFormat.format(Instant.ofEpochMilli(marker).atZone(zone)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = OnSurfaceDim
                                )
                                Box(
                                    modifier = Modifier
                                        .width(1.dp)
                                        .height(8.dp)
                                        .background(Color.White.copy(alpha = 0.16f))
                                )
                            }
                        }
                        if (now in windowStart..windowEnd) {
                            Box(
                                modifier = Modifier
                                    .padding(start = totalTimelineWidth * elapsedRatio)
                                    .width(2.dp)
                                    .fillMaxHeight()
                                    .background(Primary)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EpgRow(
    channel: Channel,
    isFavorite: Boolean,
    programs: List<Program>,
    windowStart: Long,
    windowEnd: Long,
    channelRailWidth: Dp,
    timelineGap: Dp,
    timelineViewportWidth: Dp,
    totalTimelineWidth: Dp,
    density: GuideDensity,
    transparentOverlay: Boolean,
    rowHeight: Dp,
    markerStepMs: Long,
    scrollState: androidx.compose.foundation.ScrollState,
    focusRequester: FocusRequester? = null,
    onChannelClick: () -> Unit,
    onChannelLongClick: ((Program?) -> Unit)? = null,
    onChannelFocused: (Program?) -> Unit,
    onProgramClick: (Program) -> Unit,
    onProgramFocused: (Program) -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val now = currentGuideNow()
    val currentProgram by remember(programs, now) {
        derivedStateOf { programs.currentProgramAt(now) }
    }
    val hasUsableArchive = channel.archivePlaybackCapability().canBuildReplayCandidate
    val totalDuration = (windowEnd - windowStart).coerceAtLeast(1L)
    val channelPaddingVertical = when (density) {
        GuideDensity.COMPACT -> 3.dp
        GuideDensity.COMFORTABLE -> 4.dp
        GuideDensity.CINEMATIC -> 5.dp
    }
    val channelLogoSize = when (density) {
        GuideDensity.COMPACT -> 22.dp
        GuideDensity.COMFORTABLE -> 24.dp
        GuideDensity.CINEMATIC -> 26.dp
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(rowHeight),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TvClickableSurface(
            onClick = onChannelClick,
            onLongClick = onChannelLongClick?.let { cb ->
                {
                    val prog = currentProgram
                        ?: programs.minByOrNull { kotlin.math.abs(it.startTime - now) }
                    cb(prog)
                }
            },
            modifier = Modifier
                .width(channelRailWidth)
                .fillMaxHeight()
                .then(
                    if (focusRequester != null) {
                        Modifier.focusRequester(focusRequester)
                    } else {
                        Modifier
                    }
                )
                .onFocusChanged {
                    if (it.isFocused && !isFocused) {
                        onChannelFocused(currentProgram)
                    }
                    isFocused = it.isFocused
                },
            colors = ClickableSurfaceDefaults.colors(
                containerColor = if (transparentOverlay) SurfaceElevated.copy(alpha = 0.62f) else SurfaceElevated,
                focusedContainerColor = if (transparentOverlay) SurfaceHighlight.copy(alpha = 0.88f) else SurfaceHighlight
            ),
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
            border = ClickableSurfaceDefaults.border(
                focusedBorder = Border(
                    border = BorderStroke(2.dp, FocusBorder),
                    shape = RoundedCornerShape(8.dp)
                )
            )
        ) {
            Row(
                modifier = Modifier
                    .padding(horizontal = 7.dp, vertical = channelPaddingVertical)
                    .fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ChannelLogoBadge(
                    channelName = channel.name,
                    logoUrl = channel.logoUrl,
                    modifier = Modifier
                        .width(channelLogoSize)
                        .height(channelLogoSize),
                    shape = RoundedCornerShape(6.dp)
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    Text(
                        text = if (channel.number > 0) "${channel.number}. ${channel.name}" else channel.name,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isFocused) TextPrimary else OnSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = if (isFocused) {
                            Modifier.basicMarquee(
                                iterations = Int.MAX_VALUE,
                                initialDelayMillis = 900,
                                repeatDelayMillis = 1200,
                                velocity = 24.dp
                            )
                        } else {
                            Modifier
                        }
                    )
                    Text(
                        text = currentProgram?.title ?: stringResource(R.string.epg_no_schedule_short),
                        style = MaterialTheme.typography.labelSmall,
                        color = OnSurfaceDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (isFavorite || hasUsableArchive) {
                    Box(
                        modifier = Modifier
                            .background(
                                color = Primary.copy(alpha = 0.16f),
                                shape = CircleShape
                            )
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = if (hasUsableArchive) stringResource(R.string.player_archive_badge) else stringResource(R.string.epg_favorite_badge),
                            style = MaterialTheme.typography.labelSmall,
                            color = Primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.width(timelineGap))

        Box(
            modifier = Modifier
                .width(timelineViewportWidth)
                .fillMaxHeight()
                .background(
                    if (transparentOverlay) SurfaceElevated.copy(alpha = 0.48f) else SurfaceElevated,
                    RoundedCornerShape(8.dp)
                )
                .clip(RoundedCornerShape(8.dp))
        ) {
            Row(
                modifier = Modifier
                    .width(totalTimelineWidth)
                    .horizontalScroll(scrollState)
            ) {
                Box(
                    modifier = Modifier
                        .width(totalTimelineWidth)
                        .fillMaxHeight()
                ) {
                val markers = remember(windowStart, windowEnd, markerStepMs) {
                    buildList {
                        val firstMarker = windowStart - (windowStart % markerStepMs)
                        var marker = firstMarker
                        while (marker <= windowEnd) {
                            add(marker)
                            marker += markerStepMs
                        }
                    }
                }
                markers.forEach { marker ->
                    val markerRatio = ((marker - windowStart).toFloat() / totalDuration.toFloat()).coerceIn(0f, 1f)
                    Box(
                        modifier = Modifier
                            .padding(start = totalTimelineWidth * markerRatio)
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(Color.White.copy(alpha = 0.08f))
                    )
                }
                if (programs.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.epg_no_schedule_short), color = OnSurfaceDim)
                    }
                } else {
                    programs.forEach { program ->
                        ProgramItem(
                            program = program,
                            density = density,
                            transparentOverlay = transparentOverlay,
                            windowStart = windowStart,
                            windowEnd = windowEnd,
                            totalTimelineWidth = totalTimelineWidth,
                            onClick = { onProgramClick(program) },
                            onFocused = { onProgramFocused(program) }
                        )
                    }
                }
                }
            }
        }
    }
}

@Composable
fun ProgramItem(
    program: Program,
    density: GuideDensity,
    transparentOverlay: Boolean,
    windowStart: Long,
    windowEnd: Long,
    totalTimelineWidth: Dp,
    onClick: () -> Unit,
    onFocused: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val now = currentGuideNow()
    val isCurrent = now in program.startTime until program.endTime

    val appTimeFormat = LocalAppTimeFormat.current
    val format = remember(appTimeFormat) { appTimeFormat.createTimeFormatter() }
    val zone = remember { ZoneId.systemDefault() }
    val startStr = format.format(Instant.ofEpochMilli(program.startTime).atZone(zone))
    val endStr = format.format(Instant.ofEpochMilli(program.endTime).atZone(zone))
    val totalDuration = (windowEnd - windowStart).coerceAtLeast(1L)
    val visibleStart = max(program.startTime, windowStart)
    val visibleEnd = max(visibleStart + 1, minOf(program.endTime, windowEnd))
    val startRatio = ((visibleStart - windowStart).toFloat() / totalDuration.toFloat()).coerceIn(0f, 1f)
    val widthRatio = ((visibleEnd - visibleStart).toFloat() / totalDuration.toFloat()).coerceIn(0f, 1f)
    val itemStart = totalTimelineWidth * startRatio
    val minimumItemWidth = when (density) {
        GuideDensity.COMPACT -> 40.dp
        GuideDensity.COMFORTABLE -> 48.dp
        GuideDensity.CINEMATIC -> 56.dp
    }
    val itemWidth = (totalTimelineWidth * widthRatio).coerceAtLeast(minimumItemWidth)
    val isCompactCell = itemWidth < 148.dp
    val isVeryCompactCell = itemWidth < 116.dp
    val outerVerticalPadding = when (density) {
        GuideDensity.COMPACT -> 2.dp
        GuideDensity.COMFORTABLE -> 2.dp
        GuideDensity.CINEMATIC -> 3.dp
    }
    val innerHorizontalPadding = when {
        isVeryCompactCell -> 5.dp
        isCompactCell -> 6.dp
        else -> 8.dp
    }
    val innerVerticalPadding = when (density) {
        GuideDensity.COMPACT -> 2.dp
        GuideDensity.COMFORTABLE -> 3.dp
        GuideDensity.CINEMATIC -> 4.dp
    }
    val titleStyle = when {
        isVeryCompactCell -> MaterialTheme.typography.labelSmall.copy(
            fontSize = 10.sp,
            lineHeight = 11.sp
        )
        isCompactCell || density == GuideDensity.COMPACT -> MaterialTheme.typography.labelMedium.copy(
            fontSize = 11.sp,
            lineHeight = 12.sp
        )
        else -> MaterialTheme.typography.labelLarge.copy(
            fontSize = 12.sp,
            lineHeight = 14.sp
        )
    }
    val timeStyle = when {
        isCompactCell || density == GuideDensity.COMPACT -> MaterialTheme.typography.labelSmall.copy(
            fontSize = 9.sp,
            lineHeight = 10.sp
        )
        else -> MaterialTheme.typography.labelSmall.copy(
            fontSize = 10.sp,
            lineHeight = 11.sp
        )
    }

    TvClickableSurface(
        onClick = onClick,
        modifier = Modifier
            .padding(start = itemStart, top = outerVerticalPadding, bottom = outerVerticalPadding)
            .width(itemWidth)
            .fillMaxHeight()
            .onFocusChanged {
                if (it.isFocused && !isFocused) {
                    onFocused()
                }
                isFocused = it.isFocused
            },
        colors = ClickableSurfaceDefaults.colors(
            containerColor = when {
                isCurrent && transparentOverlay -> Primary.copy(alpha = 0.28f)
                isCurrent -> Primary.copy(alpha = 0.2f)
                transparentOverlay -> SurfaceElevated.copy(alpha = 0.54f)
                else -> SurfaceElevated
            },
            focusedContainerColor = if (transparentOverlay) SurfaceHighlight.copy(alpha = 0.88f) else SurfaceHighlight
        ),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                shape = RoundedCornerShape(8.dp)
            ),
            focusedBorder = Border(
                border = BorderStroke(2.dp, FocusBorder),
                shape = RoundedCornerShape(8.dp)
            )
        )
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = innerHorizontalPadding, vertical = innerVerticalPadding)
                .fillMaxSize(),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = program.title,
                style = titleStyle,
                color = if (isFocused) TextPrimary else if (isCurrent) Primary else OnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!isVeryCompactCell) {
                Text(
                    text = "$startStr - $endStr",
                    style = timeStyle,
                    color = if (isFocused) TextSecondary else OnSurfaceDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private fun List<Program>.currentProgramAt(now: Long): Program? =
    firstOrNull { now in it.startTime until it.endTime }

internal fun formatWindowDuration(durationMs: Long): String {
    val hours = durationMs / (60 * 60 * 1000L)
    val minutes = (durationMs / (60 * 1000L)) % 60
    return if (minutes == 0L) {
        "${hours}h"
    } else {
        "${hours}h ${minutes}m"
    }
}

internal fun epgChannelKey(channel: Channel, index: Int): String {
    val epgId = channel.guideLookupKey().orEmpty()
    return "channel:${channel.id}:${channel.streamId}:${epgId}:${channel.name.trim()}:$index"
}
