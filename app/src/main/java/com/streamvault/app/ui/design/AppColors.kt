package com.streamvault.app.ui.design

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color

/**
 * The full app palette for one theme.
 *
 * - [light]: the ORIGINAL StreamVault look — blue-navy surfaces, blue brand,
 *   near-white focus ring (colors restored from git history, pre-M3 commit).
 * - [dark]: the M3 purple look — neutral black surfaces, lavender primary
 *   (#D0BCFF), subtle gray focus ring (colors restored from the M3 commit).
 */
data class AppPalette(
    val canvas: Color,
    val canvasElevated: Color,
    val surface: Color,
    val surfaceElevated: Color,
    val surfaceEmphasis: Color,
    val surfaceAccent: Color,
    val brand: Color,
    val brandMuted: Color,
    val brandStrong: Color,
    val onPrimary: Color,
    val focus: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val textDisabled: Color,
    val live: Color,
    val success: Color,
    val warning: Color,
    val info: Color,
    val divider: Color,
    val outline: Color,
    val heroTop: Color,
    val heroBottom: Color,
    val isDark: Boolean
) {
    companion object {
        /** The original blue theme (was the app's only theme before the M3 commit). */
        fun light(): AppPalette = AppPalette(
            canvas = Color(0xFF07111B),
            canvasElevated = Color(0xFF0B1622),
            surface = Color(0xFF0F1B29),
            surfaceElevated = Color(0xFF162338),
            surfaceEmphasis = Color(0xFF1D2E46),
            surfaceAccent = Color(0xFF223754),
            brand = Color(0xFF69A8FF),
            brandMuted = Color(0x335FA4FF),
            brandStrong = Color(0xFF8BBCFF),
            onPrimary = Color.White,
            focus = Color(0xFFF4F8FF),
            textPrimary = Color(0xFFF5F7FB),
            textSecondary = Color(0xFFBBC6D8),
            textTertiary = Color(0xFF7F8DA5),
            textDisabled = Color(0xFF566173),
            live = Color(0xFFFF5C61),
            success = Color(0xFF4FD39A),
            warning = Color(0xFFFFC766),
            info = Color(0xFF57C9FF),
            divider = Color(0x1AF4F8FF),
            outline = Color(0x264C6D95),
            heroTop = Color(0xCC07111B),
            heroBottom = Color(0xF207111B),
            isDark = false
        )

        /** The M3 purple-on-black theme (restored from the reverted M3 commit). */
        fun dark(): AppPalette = AppPalette(
            canvas = Color(0xFF0A0A0D),
            canvasElevated = Color(0xFF0E0E12),
            surface = Color(0xFF141419),
            surfaceElevated = Color(0xFF1B1B21),
            surfaceEmphasis = Color(0xFF232329),
            surfaceAccent = Color(0xFF2A2A31),
            brand = Color(0xFFD0BCFF),
            brandMuted = Color(0x33D0BCFF),
            brandStrong = Color(0xFFEADDFF),
            onPrimary = Color(0xFF381E72),
            focus = Color(0xFF8A8A92),
            textPrimary = Color(0xFFF5F7FB),
            textSecondary = Color(0xFFC2C2C8),
            textTertiary = Color(0xFF8A8A92),
            textDisabled = Color(0xFF606068),
            live = Color(0xFFFF5C61),
            success = Color(0xFF4FD39A),
            warning = Color(0xFFFFC766),
            info = Color(0xFF57C9FF),
            divider = Color(0x1AF4F8FF),
            outline = Color(0x26FFFFFF),
            heroTop = Color(0xCC0A0A0D),
            heroBottom = Color(0xF20A0A0D),
            isDark = true
        )
    }
}

/**
 * Central color access. Every property is backed by snapshot state, so any
 * composable reading `AppColors.X` recomposes automatically when the theme
 * palette is swapped by [com.streamvault.app.ui.theme.StreamVaultTheme].
 */
object AppColors {
    private val activeState: MutableState<AppPalette> = mutableStateOf(AppPalette.light())

    var current: AppPalette
        get() = activeState.value
        set(value) {
            activeState.value = value
        }

    val Canvas: Color get() = current.canvas
    val CanvasElevated: Color get() = current.canvasElevated
    val Surface: Color get() = current.surface
    val SurfaceElevated: Color get() = current.surfaceElevated
    val SurfaceEmphasis: Color get() = current.surfaceEmphasis
    val SurfaceAccent: Color get() = current.surfaceAccent

    val Brand: Color get() = current.brand
    val BrandMuted: Color get() = current.brandMuted
    val BrandStrong: Color get() = current.brandStrong
    val OnPrimary: Color get() = current.onPrimary
    val Focus: Color get() = current.focus

    val TextPrimary: Color get() = current.textPrimary
    val TextSecondary: Color get() = current.textSecondary
    val TextTertiary: Color get() = current.textTertiary
    val TextDisabled: Color get() = current.textDisabled

    val Live: Color get() = current.live
    val Success: Color get() = current.success
    val Warning: Color get() = current.warning
    val Info: Color get() = current.info

    val Divider: Color get() = current.divider
    val Outline: Color get() = current.outline

    val HeroTop: Color get() = current.heroTop
    val HeroBottom: Color get() = current.heroBottom
}
