package com.streamvault.app.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.dp
import com.streamvault.core.ui.accessibility.rememberReducedMotionEnabled
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

fun Modifier.shimmerEffect(baseColor: Color, enabled: Boolean = true): Modifier =
    if (enabled) this.then(ShimmerElement(baseColor)) else this

private data class ShimmerElement(
    private val baseColor: Color
) : ModifierNodeElement<ShimmerNode>() {
    override fun create(): ShimmerNode = ShimmerNode(baseColor)

    override fun update(node: ShimmerNode) {
        node.updateBaseColor(baseColor)
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "shimmerEffect"
        properties["baseColor"] = baseColor
    }
}

private class ShimmerNode(
    baseColor: Color
) : Modifier.Node(), DrawModifierNode {
    private var animationJob: Job? = null
    private var progress: Float = -1f
    private var shimmerBaseColor: Color = baseColor

    override fun onAttach() {
        startAnimation()
    }

    override fun onDetach() {
        animationJob?.cancel()
        animationJob = null
    }

    fun updateBaseColor(baseColor: Color) {
        if (shimmerBaseColor != baseColor) {
            shimmerBaseColor = baseColor
            invalidateDraw()
        }
    }

    private fun startAnimation() {
        if (animationJob != null) return
        animationJob = coroutineScope.launch {
            while (isActive) {
                animate(
                    initialValue = -1f,
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = 1000, easing = FastOutSlowInEasing)
                ) { value, _ ->
                    progress = value
                    invalidateDraw()
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        val shimmerColors = listOf(
            shimmerBaseColor.copy(alpha = 0.12f),
            shimmerBaseColor.copy(alpha = 0.04f),
            shimmerBaseColor.copy(alpha = 0.12f)
        )
        val startX = size.width * progress
        val endX = startX + size.width
        drawRect(
            brush = Brush.linearGradient(
                colors = shimmerColors,
                start = Offset(x = startX - size.width, y = 0f),
                end = Offset(x = endX, y = size.height)
            )
        )
    }
}

@Composable
fun SkeletonCard(modifier: Modifier = Modifier) {
    val bgColor = MaterialTheme.colorScheme.surfaceVariant
    val shimmerBaseColor = MaterialTheme.colorScheme.onSurface
    val reducedMotionEnabled = rememberReducedMotionEnabled()
    Box(
        modifier = modifier
            .background(bgColor, RoundedCornerShape(8.dp))
            .shimmerEffect(baseColor = shimmerBaseColor, enabled = !reducedMotionEnabled)
    )
}

@Composable
fun SkeletonRow(
    modifier: Modifier = Modifier,
    cardWidth: Int = 140,
    cardHeight: Int = 210,
    itemsCount: Int = 5
) {
    val bgColor = MaterialTheme.colorScheme.surfaceVariant
    val shimmerBaseColor = MaterialTheme.colorScheme.onSurface
    val reducedMotionEnabled = rememberReducedMotionEnabled()
    Column(modifier = modifier.padding(vertical = 16.dp)) {
        Box(
            modifier = Modifier
                .width(150.dp)
                .height(24.dp)
                .background(bgColor, RoundedCornerShape(4.dp))
                .shimmerEffect(baseColor = shimmerBaseColor, enabled = !reducedMotionEnabled)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            repeat(itemsCount) {
                SkeletonCard(
                    modifier = Modifier
                        .width(cardWidth.dp)
                        .height(cardHeight.dp)
                )
            }
        }
    }
}
