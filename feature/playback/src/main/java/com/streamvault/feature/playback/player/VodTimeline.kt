package com.streamvault.feature.playback.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.streamvault.feature.playback.R
import com.streamvault.player.PlayerChapter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VodTimeline(
    chapters: List<PlayerChapter>,
    currentChapter: PlayerChapter?,
    currentPositionMs: Long,
    durationMs: Long,
    onSeekToPosition: (Long) -> Unit,
    onSetScrubbingMode: (Boolean) -> Unit,
    onSeekPreviewPositionChanged: (Long?) -> Unit,
    modifier: Modifier = Modifier
) {
    val timelineDescription = stringResource(R.string.player_vod_timeline)
    val safeDurationMs = durationMs.coerceAtLeast(1L)
    var scrubFraction by remember { mutableFloatStateOf(0f) }
    var isScrubbing by remember { mutableStateOf(false) }
    val currentFraction = currentPositionMs.coerceIn(0L, safeDurationMs).toFloat() / safeDurationMs
    val value = if (isScrubbing) scrubFraction else currentFraction

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(32.dp)
            .semantics { contentDescription = timelineDescription }
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val trackY = size.height / 2f
            chapters.forEach { chapter ->
                val markerFraction = (chapter.startTimeMs.toFloat() / safeDurationMs).coerceIn(0f, 1f)
                val isCurrent = chapter.index == currentChapter?.index
                drawCircle(
                    color = if (isCurrent) Color.White else Color.White.copy(alpha = 0.72f),
                    radius = if (isCurrent) 5.dp.toPx() else 3.dp.toPx(),
                    center = Offset(size.width * markerFraction, trackY)
                )
            }
        }
        Slider(
            value = value,
            onValueChange = { fraction ->
                scrubFraction = fraction
                isScrubbing = true
                onSetScrubbingMode(true)
                onSeekPreviewPositionChanged((fraction * safeDurationMs).toLong())
            },
            onValueChangeFinished = {
                val positionMs = (scrubFraction * safeDurationMs).toLong().coerceIn(0L, safeDurationMs)
                onSetScrubbingMode(false)
                onSeekPreviewPositionChanged(null)
                onSeekToPosition(positionMs)
                isScrubbing = false
            },
            modifier = Modifier.matchParentSize(),
            thumb = {
                Box(Modifier.size(12.dp).background(Color.White, CircleShape))
            },
            track = { sliderState ->
                SliderDefaults.Track(
                    sliderState = sliderState,
                    modifier = Modifier.height(4.dp),
                    thumbTrackGapSize = 0.dp,
                    drawStopIndicator = null,
                    colors = SliderDefaults.colors(
                        activeTrackColor = Color.White,
                        inactiveTrackColor = Color.White.copy(alpha = 0.28f)
                    )
                )
            },
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White,
                inactiveTrackColor = Color.White.copy(alpha = 0.24f)
            )
        )
    }
}
