package com.streamvault.feature.playback.player.overlay

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.streamvault.core.ui.design.AppPalette
import com.streamvault.core.ui.design.contentOnColor
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelListPaletteContrastTest {
    @Test
    fun channelListTextBadgesAndFocusRemainReadableAcrossPalettes() {
        val themeIds = listOf(
            AppPalette.CLASSIC_BLUE_ID,
            AppPalette.M3_PURPLE_ID,
            AppPalette.LIGHT_ID,
            AppPalette.FOREST_ID,
            AppPalette.SUNSET_ID,
            AppPalette.OCEAN_ID,
            AppPalette.ROSE_ID,
            AppPalette.AMOLED_BLACK_ID,
            AppPalette.VINTAGE_ID
        )

        themeIds.forEach { themeId ->
            val palette = AppPalette.forTheme(themeId)
            val primaryTextOnPanel = contrast(palette.textPrimary, palette.canvas)
            val secondaryTextOnPanel = contrast(palette.textSecondary, palette.canvas)
            val primaryTextOnRow = contrast(palette.textPrimary, palette.surfaceElevated)
            val secondaryTextOnRow = contrast(palette.textSecondary, palette.surfaceElevated)
            val primaryTextOnSelectedRow = contrast(palette.textPrimary, palette.surfaceEmphasis)
            val secondaryTextOnSelectedRow = contrast(palette.textSecondary, palette.surfaceEmphasis)
            val focusRingOnPanel = contrast(palette.focus, palette.canvas)
            val focusRingOnSelectedRow = contrast(palette.focus, palette.surfaceElevated)
            val focusRingOnRow = contrast(palette.focus, palette.surfaceEmphasis)
            val archiveBadgeText = contrast(contentOnColor(palette.warning), palette.warning)

            assertTrue("$themeId textPrimary/canvas=$primaryTextOnPanel", primaryTextOnPanel >= 4.5f)
            assertTrue("$themeId textSecondary/canvas=$secondaryTextOnPanel", secondaryTextOnPanel >= 4.5f)
            assertTrue("$themeId textPrimary/surfaceElevated=$primaryTextOnRow", primaryTextOnRow >= 4.5f)
            assertTrue("$themeId textSecondary/surfaceElevated=$secondaryTextOnRow", secondaryTextOnRow >= 4.5f)
            assertTrue(
                "$themeId textPrimary/surfaceEmphasis=$primaryTextOnSelectedRow",
                primaryTextOnSelectedRow >= 4.5f
            )
            assertTrue(
                "$themeId textSecondary/surfaceEmphasis=$secondaryTextOnSelectedRow",
                secondaryTextOnSelectedRow >= 4.5f
            )
            assertTrue("$themeId focus/canvas=$focusRingOnPanel", focusRingOnPanel >= 3f)
            assertTrue("$themeId focus/surfaceElevated=$focusRingOnSelectedRow", focusRingOnSelectedRow >= 3f)
            assertTrue("$themeId focus/surfaceEmphasis=$focusRingOnRow", focusRingOnRow >= 3f)
            assertTrue("$themeId onWarning/warning=$archiveBadgeText", archiveBadgeText >= 4.5f)
            println("DRAWER_CONTRAST $themeId textPrimary/canvas=$primaryTextOnPanel " +
                "textSecondary/canvas=$secondaryTextOnPanel textPrimary/surfaceElevated=$primaryTextOnRow " +
                "textSecondary/surfaceElevated=$secondaryTextOnRow textPrimary/surfaceEmphasis=$primaryTextOnSelectedRow " +
                "textSecondary/surfaceEmphasis=$secondaryTextOnSelectedRow focus/canvas=$focusRingOnPanel " +
                "focus/surfaceElevated=$focusRingOnSelectedRow focus/surfaceEmphasis=$focusRingOnRow")
        }
    }

    private fun contrast(foreground: Color, background: Color): Float {
        val foregroundLuminance = foreground.luminance()
        val backgroundLuminance = background.luminance()
        return (maxOf(foregroundLuminance, backgroundLuminance) + 0.05f) /
            (minOf(foregroundLuminance, backgroundLuminance) + 0.05f)
    }
}
