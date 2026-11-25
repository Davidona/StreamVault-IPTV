package com.streamvault.feature.playback.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.core.ui.design.AppColors
import com.streamvault.core.ui.design.LocalAppSpacing
import com.streamvault.feature.playback.player.overlay.TvFocusSurface
import com.streamvault.feature.playback.player.overlay.TvTintedIcon
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

internal val VOD_ROUND_BUTTON_SIZE = 40.dp
internal val VOD_PLAY_BUTTON_SIZE = VOD_BUTTON_ROW_HEIGHT

private val VodRoundIconSize = 20.dp
private val VodPlayIconSize = 28.dp
private const val VodRoundFocusScale = 1.04f
private const val VodPlayFocusScale = 1.08f

// AppColors.Focus is dark in the light and vintage themes (about 1.1:1 against the black scrim), so
// the fill alone cannot mark focus there. A white ring (allowed over video, rule 2) is 18:1 on the
// scrim in every theme, and in the dark themes the light Focus fill carries it as well.
private val VodFocusRing = BorderStroke(2.dp, Color.White)

// The bottom scrim is about 0.92 black, so any dark theme container vanishes into it (1.6 to 2:1).
// Mid gray, opaque, is 4.7:1 against black and 4.7:1 under a white icon, in every theme. Computed
// once; it is not a palette role because it only ever sits on this scrim.
private val VodButtonContainer = Color.White.copy(alpha = 0.45f).compositeOver(Color.Black)

// 500 ms: long enough that moving across the row does not flash a tooltip on every button, short
// enough that a pause on a button explains it. 1000 ms feels like the UI is stuck on a remote.
private const val TooltipDelayMillis = 500L

/**
 * Round transport or action button: opaque mid-gray fill with a white icon at rest, [AppColors.Focus]
 * fill with a [AppColors.Canvas] icon and a white ring when focused. A tooltip with [label] floats
 * above it once focus has stayed for [TooltipDelayMillis].
 */
@Composable
internal fun VodRoundButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    VodButtonWithTooltip(label = label, size = VOD_ROUND_BUTTON_SIZE) { focusModifier ->
        TvFocusSurface(
            onClick = onClick,
            shape = CircleShape,
            containerColor = VodButtonContainer,
            focusedContainerColor = AppColors.Focus,
            contentColor = Color.White,
            focusedContentColor = AppColors.Canvas,
            focusedScale = VodRoundFocusScale,
            focusedBorder = VodFocusRing,
            modifier = modifier
                .size(VOD_ROUND_BUTTON_SIZE)
                .semantics { contentDescription = label }
                .then(focusModifier)
        ) { contentColor ->
            TvTintedIcon(
                imageVector = icon,
                tint = contentColor,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(VodRoundIconSize)
            )
        }
    }
}

/** The one primary action on the overlay: brand fill like the Live TV play button. */
@Composable
internal fun VodPlayPauseButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    VodButtonWithTooltip(label = label, size = VOD_PLAY_BUTTON_SIZE) { focusModifier ->
        TvFocusSurface(
            onClick = onClick,
            shape = CircleShape,
            containerColor = AppColors.Brand,
            focusedContainerColor = AppColors.Focus,
            contentColor = AppColors.Canvas,
            focusedContentColor = AppColors.Canvas,
            focusedScale = VodPlayFocusScale,
            focusedBorder = VodFocusRing,
            modifier = modifier
                .size(VOD_PLAY_BUTTON_SIZE)
                .semantics { contentDescription = label }
                .then(focusModifier)
        ) { contentColor ->
            TvTintedIcon(
                imageVector = icon,
                tint = contentColor,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(VodPlayIconSize)
            )
        }
    }
}

/**
 * Wraps a button with its tooltip. Focus and the dwell timer live in state read only in the draw
 * phase (the tooltip's alpha), so a focus move recomposes nothing here. The tooltip is always
 * composed and shown through alpha, which avoids a text layout and node insert per focus move.
 */
@Composable
private fun VodButtonWithTooltip(
    label: String,
    size: Dp,
    button: @Composable (focusModifier: Modifier) -> Unit
) {
    val isFocused = remember { mutableStateOf(false) }
    val tooltipVisible = remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        snapshotFlow { isFocused.value }.collectLatest { focused ->
            if (focused) {
                delay(TooltipDelayMillis)
                tooltipVisible.value = true
            } else {
                tooltipVisible.value = false
            }
        }
    }
    Box(modifier = Modifier.size(size)) {
        button(Modifier.onFocusChanged { isFocused.value = it.isFocused })
        Box(
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer { alpha = if (tooltipVisible.value) 1f else 0f }
        ) {
            VodTooltip(label)
        }
    }
}

/** Opaque light chip with dark text, the strongest contrast on the dark scrim; placed above the button. */
@Composable
private fun VodTooltip(label: String) {
    val spacing = LocalAppSpacing.current
    Text(
        text = label,
        modifier = Modifier
            .clearAndSetSemantics {}
            .floatAbove(spacing.xs)
            .clip(RoundedCornerShape(spacing.xs))
            .background(Color.White)
            .padding(horizontal = spacing.sm, vertical = spacing.xs / 2),
        style = MaterialTheme.typography.bodyMedium,
        color = Color.Black,
        maxLines = 1
    )
}

/**
 * Centres the element over its parent and lifts it clear of the parent's top edge, without taking
 * layout space, so showing the tooltip never moves the button row.
 */
private fun Modifier.floatAbove(gap: Dp): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(Constraints())
    val gapPx = gap.roundToPx()
    layout(constraints.maxWidth, 0) {
        placeable.place((constraints.maxWidth - placeable.width) / 2, -placeable.height - gapPx)
    }
}
