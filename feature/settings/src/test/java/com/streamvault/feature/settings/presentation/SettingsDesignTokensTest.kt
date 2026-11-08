package com.streamvault.feature.settings.presentation

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.streamvault.core.ui.design.AppPalette
import org.junit.Test

class SettingsDesignTokensTest {
    @Test
    fun `category controls text and focus contrast across palettes`() {
        val themes = listOf("classic_blue", "m3_purple", "light", "forest", "sunset", "ocean", "rose", "amoled_black", "vintage")
        themes.forEach { theme ->
            val palette = AppPalette.forTheme(theme)
            val pairs = listOf(
                "textPrimary/surface" to settingsContrastRatio(palette.textPrimary, palette.surface),
                "textPrimary/surfaceElevated" to settingsContrastRatio(palette.textPrimary, palette.surfaceElevated),
                "textSecondary/surface" to settingsContrastRatio(palette.textSecondary, palette.surface),
                "textPrimary/surfaceAccent" to settingsContrastRatio(palette.textPrimary, palette.surfaceAccent),
                "textSecondary/surfaceAccent" to settingsContrastRatio(palette.textSecondary, palette.surfaceAccent),
                "focus/surfaceAccent" to settingsContrastRatio(palette.focus, palette.surfaceAccent),
                "focus/canvas" to settingsContrastRatio(palette.focus, palette.canvas),
                "focus/surface" to settingsContrastRatio(palette.focus, palette.surface),
                "focus/surfaceElevated" to settingsContrastRatio(palette.focus, palette.surfaceElevated),
            )
            pairs.forEach { (name, ratio) ->
                println("$theme $name $ratio")
                assertWithMessage("$theme $name").that(ratio).isAtLeast(if (name.startsWith("focus")) 3.0 else 4.5)
            }
        }
    }

    @Test
    fun `informative text and search content remain readable in every theme`() {
        listOf("classic_blue", "m3_purple", "light").forEach { themeId ->
            val colors = SettingsDesignTokens.colors(AppPalette.forTheme(themeId))

            assertWithMessage("$themeId primary text on canvas")
                .that(settingsContrastRatio(colors.primaryText, colors.canvas))
                .isAtLeast(4.5)
            assertWithMessage("$themeId secondary text on group")
                .that(settingsContrastRatio(colors.secondaryText, colors.groupSurface))
                .isAtLeast(4.5)
            assertWithMessage("$themeId typed search text")
                .that(settingsContrastRatio(colors.searchText, colors.searchSurface))
                .isAtLeast(4.5)
            assertWithMessage("$themeId search placeholder")
                .that(settingsContrastRatio(colors.searchPlaceholder, colors.searchSurface))
                .isAtLeast(4.5)
            assertWithMessage("$themeId focused typed search text")
                .that(settingsContrastRatio(colors.searchText, colors.searchFocusedSurface))
                .isAtLeast(4.5)
            assertWithMessage("$themeId focused search placeholder")
                .that(settingsContrastRatio(colors.searchPlaceholder, colors.searchFocusedSurface))
                .isAtLeast(4.5)
            assertWithMessage("$themeId disabled prerequisite explanation")
                .that(settingsContrastRatio(colors.disabledText, colors.groupSurface))
                .isAtLeast(4.5)
            assertWithMessage("$themeId focus outline")
                .that(settingsContrastRatio(colors.focusOutline, colors.focusedSurface))
                .isAtLeast(3.0)
            assertWithMessage("$themeId search cursor")
                .that(settingsContrastRatio(colors.cursor, colors.searchFocusedSurface))
                .isAtLeast(3.0)
        }
    }

    @Test
    fun `overview and detail rows use the same base surface role`() {
        listOf("classic_blue", "m3_purple", "light").forEach { themeId ->
            val colors = SettingsDesignTokens.colors(AppPalette.forTheme(themeId))

            assertThat(colors.overviewRowSurface).isEqualTo(colors.detailRowSurface)
        }
    }
}
