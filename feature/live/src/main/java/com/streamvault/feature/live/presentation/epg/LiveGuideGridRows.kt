package com.streamvault.feature.live.presentation.epg

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.core.ui.image.ChannelLogoBadge
import com.streamvault.core.ui.interaction.TvClickableSurface
import com.streamvault.core.ui.theme.FocusBorder
import com.streamvault.core.ui.theme.OnSurface
import com.streamvault.core.ui.theme.OnSurfaceDim
import com.streamvault.core.ui.theme.Primary
import com.streamvault.core.ui.theme.SurfaceElevated
import com.streamvault.core.ui.theme.SurfaceHighlight
import com.streamvault.core.ui.theme.TextPrimary
import com.streamvault.core.ui.theme.TextSecondary
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.Program
import com.streamvault.domain.playback.archivePlaybackCapability
import com.streamvault.feature.live.presentation.time.LocalLiveTimeFormat
import com.streamvault.feature.live.presentation.time.createLiveTimeFormatter
import java.time.Instant
import java.time.ZoneId
import kotlin.math.max

data class LiveGuideGridLabels(
    val noSchedule: String,
    val archiveBadge: String,
    val favoriteBadge: String
)

fun List<Program>.liveCurrentProgramAt(now: Long): Program? =
    firstOrNull { now in it.startTime until it.endTime }

@Composable
fun LiveGuideGridRow(
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
    labels: LiveGuideGridLabels,
    focusRequester: FocusRequester? = null,
    onChannelClick: () -> Unit,
    onChannelLongClick: ((Program?) -> Unit)? = null,
    onChannelFocused: (Program?) -> Unit,
    onProgramClick: (Program) -> Unit,
    onProgramFocused: (Program) -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val now = currentLiveGuideNow()
    val currentProgram by remember(programs, now) {
        derivedStateOf { programs.liveCurrentProgramAt(now) }
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
            shape = ClickableSurfaceDefaults.shape(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)),
            border = ClickableSurfaceDefaults.border(
                focusedBorder = Border(
                    border = BorderStroke(2.dp, FocusBorder),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
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
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
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
                        text = currentProgram?.title ?: labels.noSchedule,
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
                                shape = androidx.compose.foundation.shape.CircleShape
                            )
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = if (hasUsableArchive) labels.archiveBadge else labels.favoriteBadge,
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
                    androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                )
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
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
                            Text(labels.noSchedule, color = OnSurfaceDim)
                        }
                    } else {
                        programs.forEach { program ->
                            LiveGuideProgramItem(
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
fun LiveGuideProgramItem(
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
    val now = currentLiveGuideNow()
    val isCurrent = now in program.startTime until program.endTime

    val appTimeFormat = LocalLiveTimeFormat.current
    val format = remember(appTimeFormat) { appTimeFormat.createLiveTimeFormatter() }
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
        shape = ClickableSurfaceDefaults.shape(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
            ),
            focusedBorder = Border(
                border = BorderStroke(2.dp, FocusBorder),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
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
