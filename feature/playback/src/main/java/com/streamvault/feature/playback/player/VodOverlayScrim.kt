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

// Few stops, two small boxes: one full-screen brush with 19 stops cost +5.8 ms GPU p50 on the TCL TV
// over the 3-stop baseline brush (tv-overlay-results.md, P7 bisect).
private const val FADE_STEPS = 4

/**
 * Header scrim, drawn in a box [VOD_SCRIM_TOP_END] of the screen tall, so stop positions are
 * fractions of that box. Rule 9: at least 55 percent black behind the header text (up to the
 * header's end). The fade is a smoothstep sampled at [FADE_STEPS] points, which keeps the lower edge
 * soft where a straight ramp shows an edge.
 */
internal fun vodTopScrimStops(): List<Pair<Float, Color>> = buildList {
    add(0f to Color.Black.copy(alpha = VOD_SCRIM_TOP_ALPHA))
    add(VOD_SCRIM_TOP_TEXT_END / VOD_SCRIM_TOP_END to Color.Black.copy(alpha = VOD_SCRIM_TEXT_ALPHA))
    addAll(smoothFade(VOD_SCRIM_TOP_TEXT_END / VOD_SCRIM_TOP_END, 1f, VOD_SCRIM_TEXT_ALPHA, 0f))
}

/**
 * Bottom scrim, drawn in a box from [VOD_SCRIM_BOTTOM_START] down, so stop positions are fractions
 * of that box. Rule 9: at least 55 percent black behind the time row (about 83 percent down).
 */
internal fun vodBottomScrimStops(): List<Pair<Float, Color>> = buildList {
    add(0f to Color.Transparent)
    addAll(smoothFade(0f, 1f, 0f, VOD_SCRIM_BOTTOM_ALPHA))
}

private fun smoothFade(from: Float, to: Float, fromAlpha: Float, toAlpha: Float): List<Pair<Float, Color>> =
    (1..FADE_STEPS).map { step ->
        val t = step / FADE_STEPS.toFloat()
        val eased = t * t * (3f - 2f * t)
        // Exact end position: float arithmetic can land a hair short of 1f.
        val position = if (step == FADE_STEPS) to else from + (to - from) * t
        position to Color.Black.copy(alpha = fromAlpha + (toAlpha - fromAlpha) * eased)
    }

internal val VodTopScrimBrush: Brush = Brush.verticalGradient(*vodTopScrimStops().toTypedArray())
internal val VodBottomScrimBrush: Brush = Brush.verticalGradient(*vodBottomScrimStops().toTypedArray())
