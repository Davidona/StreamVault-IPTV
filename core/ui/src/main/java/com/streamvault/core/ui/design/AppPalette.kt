package com.streamvault.core.ui.design

import androidx.compose.ui.graphics.Color

/** Complete color palette for one selectable application theme. */
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
    val isLight: Boolean
) {
    companion object {
        const val CLASSIC_BLUE_ID = "classic_blue"
        const val M3_PURPLE_ID = "m3_purple"
        const val LIGHT_ID = "light"

        fun forTheme(themeId: String?): AppPalette = when (themeId?.trim()?.lowercase()) {
            M3_PURPLE_ID -> m3Purple()
            LIGHT_ID -> light()
            else -> classicBlue()
        }

        fun classicBlue(): AppPalette = AppPalette(
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
            isLight = false
        )

        fun m3Purple(): AppPalette = AppPalette(
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
            isLight = false
        )

        fun light(): AppPalette = AppPalette(
            canvas = Color(0xFFF4F7FB),
            canvasElevated = Color.White,
            surface = Color.White,
            surfaceElevated = Color(0xFFEAF0F8),
            surfaceEmphasis = Color(0xFFDDE7F3),
            surfaceAccent = Color(0xFFD2E2F5),
            brand = Color(0xFF185FB4),
            brandMuted = Color(0x33185FB4),
            brandStrong = Color(0xFF0D47A1),
            onPrimary = Color.White,
            focus = Color(0xFF123B6D),
            textPrimary = Color(0xFF132033),
            textSecondary = Color(0xFF41516A),
            textTertiary = Color(0xFF667892),
            textDisabled = Color(0xFF9AA7B8),
            live = Color(0xFFB3261E),
            success = Color(0xFF176B47),
            warning = Color(0xFF8A5200),
            info = Color(0xFF006A8E),
            divider = Color(0x1F132033),
            outline = Color(0x335B6B82),
            heroTop = Color(0xCCF4F7FB),
            heroBottom = Color(0xF2F4F7FB),
            isLight = true
        )
    }
}
