package com.streamvault.feature.playback.player.overlay

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.Role
import kotlinx.coroutines.launch

private const val FocusScaleMillis = 60

/**
 * Focus surface for the action row. The TV Material surface animates colour, scale and border
 * through state read during composition, which recomposed the whole button every frame of a focus
 * move. Here focus is a state read only in the draw phase (background, border) and in the
 * [content] colour producer, and the scale is an [Animatable] driven from the focus callback and
 * read in a graphicsLayer block. A focus move therefore recomposes nothing in this surface; the
 * animation frames cost draw time, not composition time.
 */
@Composable
internal fun TvFocusSurface(
    onClick: () -> Unit,
    shape: Shape,
    containerColor: Color,
    focusedContainerColor: Color,
    contentColor: Color,
    focusedContentColor: Color,
    focusedScale: Float,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    focusedBorder: BorderStroke? = null,
    content: @Composable BoxScope.(contentColor: () -> Color) -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val scale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    val interactionSource = remember { MutableInteractionSource() }
    val interactionModifier = if (enabled) {
        Modifier.clickable(
            interactionSource = interactionSource,
            indication = null,
            role = Role.Button,
            onClick = onClick
        )
    } else {
        // Disabled entries stay focusable so Up from the owner cannot fall through to a channel
        // change, but they expose no click action to accessibility or to the D-pad.
        Modifier.focusable()
    }
    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            }
            .drawBehind {
                drawFocusSurface(
                    shape = shape,
                    fill = if (isFocused) focusedContainerColor else containerColor,
                    border = if (isFocused) focusedBorder else null
                )
            }
            .onFocusChanged {
                isFocused = it.isFocused
                val target = if (it.isFocused) focusedScale else 1f
                scope.launch { scale.animateTo(target, tween(FocusScaleMillis)) }
            }
            .then(interactionModifier)
    ) {
        content { if (isFocused) focusedContentColor else contentColor }
    }
}

/** Matches Modifier.background plus Modifier.border: the stroke sits inside the bounds. */
internal fun DrawScope.drawFocusSurface(shape: Shape, fill: Color, border: BorderStroke?) {
    if (fill.alpha > 0f) {
        drawOutline(shape.createOutline(size, layoutDirection, this), fill)
    }
    if (border != null) {
        val strokeWidth = border.width.toPx()
        val inset = strokeWidth / 2f
        translate(inset, inset) {
            val outline = shape.createOutline(
                Size(size.width - strokeWidth, size.height - strokeWidth),
                layoutDirection,
                this
            )
            drawOutline(outline, border.brush, style = Stroke(strokeWidth))
        }
    }
}

/**
 * Icon tinted by a colour producer read in the draw phase, so a focus colour change redraws the icon
 * without recomposing it (androidx.tv Icon takes a plain Color, which needs a composition read).
 */
@Composable
internal fun TvTintedIcon(imageVector: ImageVector, tint: () -> Color, modifier: Modifier = Modifier) {
    val painter = rememberVectorPainter(imageVector)
    Spacer(
        modifier.drawBehind {
            with(painter) { draw(size, colorFilter = ColorFilter.tint(tint())) }
        }
    )
}
