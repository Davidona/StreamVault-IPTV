package com.streamvault.feature.playback.player

import android.view.KeyEvent
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.streamvault.feature.playback.R
import com.streamvault.player.PlayerChapter

private const val VOD_DPAD_SEEK_STEP_MS = 10_000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VodTimeline(
    chapters: List<PlayerChapter>,
    currentChapter: PlayerChapter?,
    currentPositionMs: Long,
    durationMs: Long,
    timelineFocusRequester: FocusRequester,
    playButtonFocusRequester: FocusRequester,
    onSeekToPosition: (Long) -> Unit,
    onSetScrubbingMode: (Boolean) -> Unit,
    onSeekPreviewPositionChanged: (Long?) -> Unit,
    onUserInteraction: () -> Unit,
    modifier: Modifier = Modifier
) {
    val timelineDescription = stringResource(R.string.player_vod_timeline)
    val layoutDirection = LocalLayoutDirection.current
    val safeDurationMs = durationMs.coerceAtLeast(1L)
    var scrubFraction by remember { mutableFloatStateOf(0f) }
    var isScrubbing by remember { mutableStateOf(false) }
    var isTimelineFocused by remember { mutableStateOf(false) }
    var isKeyboardScrubbing by remember { mutableStateOf(false) }
    var keyboardPositionMs by remember { mutableLongStateOf(0L) }
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
                isKeyboardScrubbing = false
                scrubFraction = fraction
                isScrubbing = true
                onSetScrubbingMode(true)
                onSeekPreviewPositionChanged((fraction * safeDurationMs).toLong())
                onUserInteraction()
            },
            onValueChangeFinished = {
                val positionMs = (scrubFraction * safeDurationMs).toLong().coerceIn(0L, safeDurationMs)
                isKeyboardScrubbing = false
                onSetScrubbingMode(false)
                onSeekPreviewPositionChanged(null)
                onSeekToPosition(positionMs)
                isScrubbing = false
            },
            modifier = Modifier
                .matchParentSize()
                .focusRequester(timelineFocusRequester)
                .focusProperties { down = playButtonFocusRequester }
                .onFocusChanged { isTimelineFocused = it.isFocused }
                .onPreviewKeyEvent { event ->
                    val nativeEvent = event.nativeKeyEvent
                    val seekDeltaMs = when (nativeEvent.keyCode) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> if (layoutDirection == LayoutDirection.Ltr) {
                            -VOD_DPAD_SEEK_STEP_MS
                        } else {
                            VOD_DPAD_SEEK_STEP_MS
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> if (layoutDirection == LayoutDirection.Ltr) {
                            VOD_DPAD_SEEK_STEP_MS
                        } else {
                            -VOD_DPAD_SEEK_STEP_MS
                        }
                        else -> return@onPreviewKeyEvent false
                    }
                    if (durationMs <= 0L) return@onPreviewKeyEvent false

                    when (nativeEvent.action) {
                        KeyEvent.ACTION_DOWN -> {
                            if (!isKeyboardScrubbing) {
                                keyboardPositionMs = currentPositionMs.coerceIn(0L, durationMs)
                                isKeyboardScrubbing = true
                            }
                            keyboardPositionMs = (keyboardPositionMs + seekDeltaMs).coerceIn(0L, durationMs)
                            scrubFraction = keyboardPositionMs.toFloat() / safeDurationMs
                            isScrubbing = true
                            onSetScrubbingMode(true)
                            onSeekPreviewPositionChanged(keyboardPositionMs)
                            onUserInteraction()
                            true
                        }
                        KeyEvent.ACTION_UP -> {
                            if (!isKeyboardScrubbing) return@onPreviewKeyEvent false
                            val positionMs = keyboardPositionMs.coerceIn(0L, durationMs)
                            isKeyboardScrubbing = false
                            onSetScrubbingMode(false)
                            onSeekPreviewPositionChanged(null)
                            onSeekToPosition(positionMs)
                            isScrubbing = false
                            onUserInteraction()
                            true
                        }
                        else -> false
                    }
                },
            thumb = {
                Box(
                    modifier = Modifier
                        .size(if (isTimelineFocused) 20.dp else 12.dp)
                        .background(Color.White, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (isTimelineFocused) {
                        Box(Modifier.size(8.dp).background(Color(0xFF10151D), CircleShape))
                    }
                }
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
