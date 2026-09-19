package com.streamvault.feature.provider.setup

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

@Composable
internal fun PasswordVisibilityGlyph(
    isVisible: Boolean,
    tint: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.size(18.dp)) {
        val stroke = Stroke(width = size.minDimension * 0.11f)
        drawOval(
            color = tint,
            topLeft = androidx.compose.ui.geometry.Offset(1.6f, size.height * 0.22f),
            size = androidx.compose.ui.geometry.Size(size.width - 3.2f, size.height * 0.56f),
            style = stroke
        )
        drawCircle(
            color = tint,
            radius = size.minDimension * 0.14f,
            center = center
        )
        if (!isVisible) {
            drawLine(
                color = tint,
                start = androidx.compose.ui.geometry.Offset(size.width * 0.18f, size.height * 0.82f),
                end = androidx.compose.ui.geometry.Offset(size.width * 0.82f, size.height * 0.18f),
                strokeWidth = size.minDimension * 0.11f
            )
        }
    }
}
