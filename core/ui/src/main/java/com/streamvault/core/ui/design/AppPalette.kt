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
        const val FOREST_ID = "forest"
        const val SUNSET_ID = "sunset"
        const val OCEAN_ID = "ocean"
        const val ROSE_ID = "rose"
        const val AMOLED_BLACK_ID = "amoled_black"
        const val VINTAGE_ID = "vintage"

        fun forTheme(themeId: String?): AppPalette = when (themeId?.trim()?.lowercase()) {
            M3_PURPLE_ID -> m3Purple()
            LIGHT_ID -> light()
            FOREST_ID -> forest()
            SUNSET_ID -> sunset()
            OCEAN_ID -> ocean()
            ROSE_ID -> rose()
            AMOLED_BLACK_ID -> amoledBlack()
            VINTAGE_ID -> vintage()
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

        fun forest(): AppPalette = AppPalette(
            canvas = Color(0xFF07140F),
            canvasElevated = Color(0xFF0B1B14),
            surface = Color(0xFF10231A),
            surfaceElevated = Color(0xFF193326),
            surfaceEmphasis = Color(0xFF244634),
            surfaceAccent = Color(0xFF2B5540),
            brand = Color(0xFF79D6A5),
            brandMuted = Color(0x3379D6A5),
            brandStrong = Color(0xFFA2F0C5),
            onPrimary = Color(0xFF07351F),
            focus = Color(0xFFE8FFF1),
            textPrimary = Color(0xFFF1F8F3),
            textSecondary = Color(0xFFBDCEC2),
            textTertiary = Color(0xFF83978A),
            textDisabled = Color(0xFF59695F),
            live = Color(0xFFFF6868),
            success = Color(0xFF79D6A5),
            warning = Color(0xFFFFC66D),
            info = Color(0xFF75D6D2),
            divider = Color(0x1AF1F8F3),
            outline = Color(0x335B8A70),
            heroTop = Color(0xCC07140F),
            heroBottom = Color(0xF207140F),
            isLight = false
        )

        fun sunset(): AppPalette = AppPalette(
            canvas = Color(0xFF19100F),
            canvasElevated = Color(0xFF201411),
            surface = Color(0xFF291A16),
            surfaceElevated = Color(0xFF38231C),
            surfaceEmphasis = Color(0xFF4A2D22),
            surfaceAccent = Color(0xFF5A3526),
            brand = Color(0xFFFFB27F),
            brandMuted = Color(0x33FFB27F),
            brandStrong = Color(0xFFFFD0AD),
            onPrimary = Color(0xFF4A210F),
            focus = Color(0xFFFFF1E8),
            textPrimary = Color(0xFFFFF6F0),
            textSecondary = Color(0xFFD9C4B8),
            textTertiary = Color(0xFFA88D80),
            textDisabled = Color(0xFF75635B),
            live = Color(0xFFFF6969),
            success = Color(0xFF8BD3A8),
            warning = Color(0xFFFFCA77),
            info = Color(0xFF8CCBDD),
            divider = Color(0x1AFFF6F0),
            outline = Color(0x337B5747),
            heroTop = Color(0xCC19100F),
            heroBottom = Color(0xF219100F),
            isLight = false
        )

        fun ocean(): AppPalette = AppPalette(
            canvas = Color(0xFF06151A),
            canvasElevated = Color(0xFF0A1D23),
            surface = Color(0xFF0D252C),
            surfaceElevated = Color(0xFF15343C),
            surfaceEmphasis = Color(0xFF1D4650),
            surfaceAccent = Color(0xFF23545F),
            brand = Color(0xFF67D9D5),
            brandMuted = Color(0x3367D9D5),
            brandStrong = Color(0xFF9AF2E8),
            onPrimary = Color(0xFF003735),
            focus = Color(0xFFE6FFFC),
            textPrimary = Color(0xFFF0FAFA),
            textSecondary = Color(0xFFB9D0D3),
            textTertiary = Color(0xFF7F9CA1),
            textDisabled = Color(0xFF566B70),
            live = Color(0xFFFF6868),
            success = Color(0xFF7BD8A8),
            warning = Color(0xFFFFC875),
            info = Color(0xFF67D9D5),
            divider = Color(0x1AF0FAFA),
            outline = Color(0x33517F87),
            heroTop = Color(0xCC06151A),
            heroBottom = Color(0xF206151A),
            isLight = false
        )

        fun rose(): AppPalette = AppPalette(
            canvas = Color(0xFF180E17),
            canvasElevated = Color(0xFF211320),
            surface = Color(0xFF2A1928),
            surfaceElevated = Color(0xFF382237),
            surfaceEmphasis = Color(0xFF4A2C48),
            surfaceAccent = Color(0xFF593555),
            brand = Color(0xFFF0A4CC),
            brandMuted = Color(0x33F0A4CC),
            brandStrong = Color(0xFFFFC6E2),
            onPrimary = Color(0xFF4B1735),
            focus = Color(0xFFFFEFF7),
            textPrimary = Color(0xFFFFF5FA),
            textSecondary = Color(0xFFD9C1D0),
            textTertiary = Color(0xFFA88C9E),
            textDisabled = Color(0xFF756171),
            live = Color(0xFFFF6C70),
            success = Color(0xFF83D2AA),
            warning = Color(0xFFFFC878),
            info = Color(0xFF8DCDE0),
            divider = Color(0x1AFFF5FA),
            outline = Color(0x337D5873),
            heroTop = Color(0xCC180E17),
            heroBottom = Color(0xF2180E17),
            isLight = false
        )

        fun amoledBlack(): AppPalette = AppPalette(
            canvas = Color.Black,
            canvasElevated = Color(0xFF030303),
            surface = Color(0xFF080808),
            surfaceElevated = Color(0xFF121212),
            surfaceEmphasis = Color(0xFF1C1C1C),
            surfaceAccent = Color(0xFF252525),
            brand = Color(0xFFA9BEFF),
            brandMuted = Color(0x33A9BEFF),
            brandStrong = Color(0xFFD1DAFF),
            onPrimary = Color(0xFF1B3269),
            focus = Color.White,
            textPrimary = Color(0xFFF6F6F8),
            textSecondary = Color(0xFFC2C2C8),
            textTertiary = Color(0xFF909097),
            textDisabled = Color(0xFF626268),
            live = Color(0xFFFF6868),
            success = Color(0xFF67D6A0),
            warning = Color(0xFFFFC66D),
            info = Color(0xFF71D4FF),
            divider = Color(0x1AFFFFFF),
            outline = Color(0x33FFFFFF),
            heroTop = Color(0xCC000000),
            heroBottom = Color(0xF2000000),
            isLight = false
        )

        fun vintage(): AppPalette = AppPalette(
            canvas = Color(0xFFF3EBDD),
            canvasElevated = Color(0xFFFBF6EC),
            surface = Color(0xFFFFFAF1),
            surfaceElevated = Color(0xFFEAE0CE),
            surfaceEmphasis = Color(0xFFDDD0B9),
            surfaceAccent = Color(0xFFD0C1A5),
            brand = Color(0xFF66764A),
            brandMuted = Color(0x3366764A),
            brandStrong = Color(0xFF485A34),
            onPrimary = Color.White,
            focus = Color(0xFF453729),
            textPrimary = Color(0xFF30271F),
            textSecondary = Color(0xFF55483A),
            textTertiary = Color(0xFF756655),
            textDisabled = Color(0xFF9A8C79),
            live = Color(0xFF9F3E3B),
            success = Color(0xFF496B4A),
            warning = Color(0xFF80521F),
            info = Color(0xFF426A72),
            divider = Color(0x1F30271F),
            outline = Color(0x33594A39),
            heroTop = Color(0xCCF3EBDD),
            heroBottom = Color(0xF2F3EBDD),
            isLight = true
        )
    }
}
