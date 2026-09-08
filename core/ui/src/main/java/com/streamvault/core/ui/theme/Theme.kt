package com.streamvault.core.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import androidx.tv.material3.lightColorScheme
import com.streamvault.core.ui.design.AppColors
import com.streamvault.core.ui.design.AppPalette
import com.streamvault.core.ui.design.AppShapes
import com.streamvault.core.ui.design.LocalAppShapes
import com.streamvault.core.ui.design.LocalAppSpacing
import com.streamvault.core.ui.design.rememberAppTypography

@Composable
fun StreamVaultTheme(
    themeId: String = AppPalette.CLASSIC_BLUE_ID,
    content: @Composable () -> Unit
) {
    val palette = AppPalette.forTheme(themeId)
    val typography = rememberAppTypography()
    SideEffect {
        AppColors.current = palette
    }
    CompositionLocalProvider(
        LocalAppSpacing provides com.streamvault.core.ui.design.AppSpacing(),
        LocalAppShapes provides AppShapes()
    ) {
        MaterialTheme(
            colorScheme = if (palette.isLight) {
                lightColorScheme(
                    primary = palette.brand,
                    onPrimary = palette.onPrimary,
                    secondary = palette.success,
                    onSecondary = Color.White,
                    tertiary = palette.info,
                    onTertiary = Color.White,
                    surface = palette.surface,
                    onSurface = palette.textPrimary,
                    surfaceVariant = palette.surfaceElevated,
                    onSurfaceVariant = palette.textSecondary,
                    background = palette.canvasElevated,
                    onBackground = palette.textPrimary,
                    error = palette.live,
                    onError = palette.onPrimary,
                    errorContainer = palette.live.copy(alpha = 0.12f),
                    onErrorContainer = Color(0xFF601410)
                )
            } else {
                darkColorScheme(
                    primary = palette.brand,
                    onPrimary = palette.onPrimary,
                    secondary = palette.success,
                    onSecondary = Color(0xFF003320),
                    tertiary = palette.info,
                    onTertiary = Color(0xFF00344A),
                    surface = palette.surface,
                    onSurface = palette.textPrimary,
                    surfaceVariant = palette.surfaceElevated,
                    onSurfaceVariant = palette.textSecondary,
                    background = palette.canvasElevated,
                    onBackground = palette.textPrimary,
                    error = palette.live,
                    onError = palette.onPrimary,
                    errorContainer = palette.live.copy(alpha = 0.20f),
                    onErrorContainer = Color(0xFFFFDCDE)
                )
            },
            typography = typography,
            content = content
        )
    }
}
