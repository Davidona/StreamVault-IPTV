package com.streamvault.feature.playback.player

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

internal const val VOD_SCRIM_TOP_ALPHA = 0.7f
internal const val VOD_SCRIM_TEXT_ALPHA = 0.6f
internal const val VOD_SCRIM_BOTTOM_ALPHA = 0.92f

// Fractions of the screen height. The header (title and chips) ends at about 15 percent.
internal const val VOD_SCRIM_TOP_TEXT_END = 0.15f
internal const val VOD_SCRIM_TOP_END = 0.28f
internal const val VOD_SCRIM_BOTTOM_START = 0.58f

private const val FADE_STEPS = 8

/**
 * One full-screen gradient for both scrims, so the header fade costs no extra layer. Each fade is a
 * smoothstep sampled at [FADE_STEPS] points, which keeps both ends soft where a straight ramp shows
 * an edge. Rule 9: at least 55 percent black behind the header text (up to the header's end) and
 * behind the time row (about 83 percent down).
 */
internal fun vodScrimStops(): List<Pair<Float, Color>> = buildList {
    add(0f to Color.Black.copy(alpha = VOD_SCRIM_TOP_ALPHA))
    add(VOD_SCRIM_TOP_TEXT_END to Color.Black.copy(alpha = VOD_SCRIM_TEXT_ALPHA))
    addAll(smoothFade(VOD_SCRIM_TOP_TEXT_END, VOD_SCRIM_TOP_END, VOD_SCRIM_TEXT_ALPHA, 0f))
    add(VOD_SCRIM_BOTTOM_START to Color.Transparent)
    addAll(smoothFade(VOD_SCRIM_BOTTOM_START, 1f, 0f, VOD_SCRIM_BOTTOM_ALPHA))
}

private fun smoothFade(from: Float, to: Float, fromAlpha: Float, toAlpha: Float): List<Pair<Float, Color>> =
    (1..FADE_STEPS).map { step ->
        val t = step / FADE_STEPS.toFloat()
        val eased = t * t * (3f - 2f * t)
        // Exact end position: float arithmetic can land a hair short of 1f.
        val position = if (step == FADE_STEPS) to else from + (to - from) * t
        position to Color.Black.copy(alpha = fromAlpha + (toAlpha - fromAlpha) * eased)
    }

internal val VodScrimBrush: Brush = Brush.verticalGradient(*vodScrimStops().toTypedArray())
