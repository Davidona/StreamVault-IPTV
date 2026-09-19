package com.streamvault.feature.live.presentation.epg

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.core.ui.theme.OnSurfaceDim
import com.streamvault.core.ui.theme.Primary
import com.streamvault.feature.live.presentation.time.LocalLiveTimeFormat
import com.streamvault.feature.live.presentation.time.createLiveTimeFormatter
import java.time.Instant
import java.time.ZoneId

const val LIVE_GUIDE_MARKER_STEP_MS = 30 * 60 * 1000L

fun liveGuideTimelineMarkers(
    windowStart: Long,
    windowEnd: Long,
    markerStepMs: Long = LIVE_GUIDE_MARKER_STEP_MS
): List<Long> {
    val firstMarker = windowStart - (windowStart % markerStepMs)
    return buildList {
        var marker = firstMarker
        while (marker <= windowEnd) {
            add(marker)
            marker += markerStepMs
        }
        if (lastOrNull() != windowEnd) {
            add(windowEnd)
        }
    }
}

@Composable
fun LiveGuideTimelineHeader(
    windowStart: Long,
    windowEnd: Long,
    channelRailWidth: Dp,
    timelineGap: Dp,
    timelineViewportWidth: Dp,
    totalTimelineWidth: Dp,
    markerStepMs: Long = LIVE_GUIDE_MARKER_STEP_MS,
    scrollState: androidx.compose.foundation.ScrollState
) {
    val now = currentLiveGuideNow()
    val appTimeFormat = LocalLiveTimeFormat.current
    val hourFormat = remember(appTimeFormat) { appTimeFormat.createLiveTimeFormatter() }
    val zone = remember { ZoneId.systemDefault() }
    val totalDuration = (windowEnd - windowStart).coerceAtLeast(1L)
    val clampedNow = now.coerceIn(windowStart, windowEnd)
    val elapsedRatio = ((clampedNow - windowStart).toFloat() / totalDuration.toFloat()).coerceIn(0f, 1f)
    val hourMarkers = remember(windowStart, windowEnd, markerStepMs) {
        liveGuideTimelineMarkers(windowStart, windowEnd, markerStepMs)
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
