package com.streamvault.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import androidx.tv.material3.lightColorScheme
import com.streamvault.app.ui.design.AppColors
import com.streamvault.app.ui.design.AppPalette
import com.streamvault.app.ui.design.AppShapes
import com.streamvault.app.ui.design.LocalAppShapes
import com.streamvault.app.ui.design.LocalAppSpacing
import com.streamvault.app.ui.design.rememberAppTypography

/**
 * StreamVault theme.
 *
 * - Light (default): the ORIGINAL blue StreamVault look.
 * - Dark: the M3 purple-on-black look (lavender primary, neutral surfaces).
 *
 * Beyond the Material scheme, this installs the shared [AppColors] palette so
 * every hardcoded `AppColors.X` / `ui.theme.X` color reference follows the
 * dark/light toggle.
 */
private fun colorSchemeFor(palette: AppPalette, dark: Boolean) =
    if (dark) {
        // M3 purple scheme: dark purple text on the lavender primary.
        darkColorScheme(
            primary = palette.brand,
            onPrimary = palette.onPrimary,
            secondary = palette.success,
            onSecondary = Color(0xFF003320),
            tertiary = palette.info,
            onTertiary = Color(0xFF00344A),
            background = palette.canvasElevated,
            onBackground = palette.textPrimary,
            surface = palette.surface,
            onSurface = palette.textPrimary,
            surfaceVariant = palette.surfaceElevated,
            onSurfaceVariant = palette.textSecondary,
            error = palette.live,
            onError = palette.onPrimary,
            errorContainer = palette.live.copy(alpha = 0.20f),
            onErrorContainer = Color(0xFFFFDCDE)
        )
    } else {
        // Original blue scheme: white text on the blue primary.
        lightColorScheme(
            primary = palette.brand,
            onPrimary = Color.White,
            secondary = palette.success,
            onSecondary = Color(0xFF003320),
            tertiary = palette.info,
            onTertiary = Color(0xFF00344A),
            background = palette.canvasElevated,
            onBackground = palette.textPrimary,
            surface = palette.surface,
            onSurface = palette.textPrimary,
            surfaceVariant = palette.surfaceElevated,
            onSurfaceVariant = palette.textSecondary,
            error = palette.live,
            onError = Color.White,
            errorContainer = palette.live.copy(alpha = 0.14f),
            onErrorContainer = Color(0xFF5F1316)
        )
    }

val LocalDarkTheme = staticCompositionLocalOf { false }

@Composable
fun StreamVaultTheme(
    useDarkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    val typography = rememberAppTypography()
    // Install the palette before composing children so every AppColors read in
    // this pass observes the active theme (and all past readers recompose).
    AppColors.current = if (useDarkTheme) AppPalette.dark() else AppPalette.light()
    CompositionLocalProvider(
        LocalAppSpacing provides com.streamvault.app.ui.design.AppSpacing(),
        LocalAppShapes provides AppShapes(),
        LocalDarkTheme provides useDarkTheme
    ) {
        MaterialTheme(
            colorScheme = colorSchemeFor(AppColors.current, useDarkTheme),
            typography = typography,
            content = content
        )
    }
}
