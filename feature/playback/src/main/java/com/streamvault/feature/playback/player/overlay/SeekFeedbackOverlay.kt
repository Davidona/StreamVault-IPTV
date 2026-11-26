package com.streamvault.feature.playback.player.overlay

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.core.ui.design.LocalAppSpacing
import com.streamvault.feature.playback.player.SeekDirection
import com.streamvault.feature.playback.player.SeekFeedbackEvent
import com.streamvault.feature.playback.player.SeekFeedbackState
import com.streamvault.feature.playback.player.VOD_OVERLAY_HORIZONTAL_PADDING
import com.streamvault.feature.playback.player.VOD_TIMELINE_BOTTOM_OFFSET
import com.streamvault.feature.playback.player.VOD_TIMELINE_HEIGHT
import com.streamvault.feature.playback.player.VOD_TIMELINE_THUMB_SIZE
import com.streamvault.feature.playback.player.VOD_TIMELINE_TRACK_INSET
import com.streamvault.feature.playback.player.VOD_TIMELINE_TRACK_THICKNESS
import com.streamvault.feature.playback.player.formatVodDuration
import com.streamvault.feature.playback.player.reduceSeekFeedback
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest

private const val SEEK_FEEDBACK_HIDE_MS = 1_100L
private const val RIPPLE_DURATION_MS = 520
private const val RIPPLE_START_SCALE = 0.82f
private const val RIPPLE_END_SCALE = 1.18f

// Over video, so every surface carries a black scrim of at least 55% (rule 9). Contrast of white text
// on the 72% scrim over pure white video is about 9.3:1.
private val SeekScrim = Color.Black.copy(alpha = 0.72f)
private val SeekTrackScrim = Color.Black.copy(alpha = 0.55f)
private val BadgeDiameter = 115.dp

/**
 * Badge on the pressed side with the stacked seconds, plus a thin timeline strip with the new
 * position. The strip is hidden while the full controls are up, because their timeline already moves.
 */
@Composable
fun SeekFeedbackHost(
    events: Flow<SeekFeedbackEvent>,
    controlsVisible: Boolean,
    modifier: Modifier = Modifier
) {
    var state by remember { mutableStateOf<SeekFeedbackState?>(null) }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(events) {
        events.collectLatest { event ->
            state = reduceSeekFeedback(state, event, SystemClock.elapsedRealtime())
            visible = true
            delay(SEEK_FEEDBACK_HIDE_MS)
            visible = false
        }
    }

    val current = state ?: return
    Box(modifier = modifier.fillMaxSize()) {
        SeekBadge(state = current, visible = visible)
        SeekStrip(
            state = current,
            visible = visible && !controlsVisible && current.durationMs > 0L,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@Composable
private fun BoxScope.SeekBadge(state: SeekFeedbackState, visible: Boolean) {
    val spacing = LocalAppSpacing.current
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val backward = state.direction == SeekDirection.BACKWARD
    // Start is the reading-direction side, which is where the remote's "back" key sits in RTL too.
    val alignment = if (backward) Alignment.CenterStart else Alignment.CenterEnd
    val ripple = remember { Animatable(1f) }
    LaunchedEffect(state.pulse) {
        ripple.snapTo(0f)
        ripple.animateTo(1f, tween(RIPPLE_DURATION_MS))
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier
            .align(alignment)
            .padding(horizontal = spacing.screenGutter + spacing.xl)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(BadgeDiameter)) {
            Box(
                modifier = Modifier
                    .size(BadgeDiameter)
                    .graphicsLayer {
                        val scale = RIPPLE_START_SCALE + (RIPPLE_END_SCALE - RIPPLE_START_SCALE) * ripple.value
                        scaleX = scale
                        scaleY = scale
                        alpha = 0.9f * (1f - ripple.value)
                    }
                    .border(width = 2.dp, color = Color.White, shape = CircleShape)
            )
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .size(BadgeDiameter)
                    .clip(CircleShape)
                    .background(SeekScrim)
            ) {
                Icon(
                    imageVector = if (backward) Icons.Default.FastRewind else Icons.Default.FastForward,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier
                        .size(spacing.lg + spacing.xs)
                        .graphicsLayer { if (isRtl) scaleX = -1f }
                )
                Text(
                    text = (state.accumulatedMs / 1_000L).toString(),
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White
                )
            }
        }
    }
}

@Composable
private fun SeekStrip(state: SeekFeedbackState, visible: Boolean, modifier: Modifier = Modifier) {
    val spacing = LocalAppSpacing.current
    val ghostColor = MaterialTheme.colorScheme.primary
    val duration = state.durationMs.coerceAtLeast(1L).toFloat()
    val position = (state.toMs / duration).coerceIn(0f, 1f)
    val stackStart = (state.stackStartMs / duration).coerceIn(0f, 1f)
    // The overlay's Slider mirrors in RTL, so the strip has to fill from the same side to line up with it.
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl

    // Same box as the overlay's timeline: same side padding, same height, same distance from the
    // bottom, so the bar does not jump when the controls open.
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = VOD_OVERLAY_HORIZONTAL_PADDING,
                end = VOD_OVERLAY_HORIZONTAL_PADDING,
                bottom = VOD_TIMELINE_BOTTOM_OFFSET
            )
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
            Text(
                text = formatVodDuration(state.toMs),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                modifier = Modifier
                    .padding(horizontal = VOD_TIMELINE_TRACK_INSET)
                    .followFraction(if (isRtl) 1f - position else position)
                    .clip(RoundedCornerShape(spacing.xs))
                    .background(SeekScrim)
                    .padding(horizontal = spacing.sm, vertical = spacing.xs)
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(VOD_TIMELINE_HEIGHT)
                    .drawBehind {
                        withTransform({ if (isRtl) scale(-1f, 1f, pivot = center) }) {
                            val thickness = VOD_TIMELINE_TRACK_THICKNESS.toPx()
                            val inset = VOD_TIMELINE_TRACK_INSET.toPx()
                            val trackWidth = size.width - 2f * inset
                            val top = (size.height - thickness) / 2f
                            val radius = CornerRadius(thickness / 2f)
                            drawRoundRect(SeekTrackScrim, Offset(inset, top), Size(trackWidth, thickness), radius)
                            drawRoundRect(Color.White, Offset(inset, top), Size(trackWidth * position, thickness), radius)
                            val ghostLeft = inset + trackWidth * minOf(position, stackStart)
                            val ghostWidth = trackWidth * kotlin.math.abs(position - stackStart)
                            drawRoundRect(ghostColor, Offset(ghostLeft, top), Size(ghostWidth, thickness), radius)
                            drawCircle(
                                Color.White,
                                VOD_TIMELINE_THUMB_SIZE.toPx() / 2f,
                                Offset(inset + trackWidth * position, size.height / 2f)
                            )
                        }
                    }
            )
        }
    }
}

/** Centres the chip on [fraction] of the available width without leaving the bar's edges. */
private fun Modifier.followFraction(fraction: Float): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minWidth = 0))
    val x = (constraints.maxWidth * fraction - placeable.width / 2f)
        .coerceIn(0f, (constraints.maxWidth - placeable.width).coerceAtLeast(0).toFloat())
    layout(constraints.maxWidth, placeable.height) { placeable.place(IntOffset(x.toInt(), 0)) }
}
