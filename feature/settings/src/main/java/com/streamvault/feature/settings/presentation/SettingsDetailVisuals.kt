package com.streamvault.feature.settings.presentation

import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import com.streamvault.core.ui.design.AppColors

/** Detail-page surfaces are local to settings; player sheets and the settings rail are unchanged. */
internal object SettingsDetailShapes {
    val Panel = RoundedCornerShape(16.dp)
    val Row = RoundedCornerShape(12.dp)
    val Control = RoundedCornerShape(10.dp)
    val Badge = RoundedCornerShape(999.dp)
}

internal fun Modifier.settingsDetailPanel(accented: Boolean = false): Modifier {
    val palette = AppColors.current
    return clip(SettingsDetailShapes.Panel)
        .drawWithCache {
            val fill = Brush.linearGradient(
                colors = listOf(
                    lerp(palette.surfaceElevated, palette.brand, if (accented) 0.12f else 0.035f),
                    palette.surface,
                ),
                start = Offset.Zero,
                end = Offset(size.width * 0.65f, size.height),
            )
            onDrawBehind {
                drawRect(fill)
                if (accented) {
                    drawRect(palette.brand, size = Size(3.dp.toPx(), size.height))
                }
            }
        }
        .border(
            1.dp,
            if (accented) palette.brand.copy(alpha = 0.4f)
            else palette.textPrimary.copy(alpha = if (palette.isLight) 0.12f else 0.09f),
            SettingsDetailShapes.Panel,
        )
}
