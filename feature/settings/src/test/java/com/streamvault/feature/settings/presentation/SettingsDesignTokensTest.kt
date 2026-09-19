package com.streamvault.feature.settings.presentation

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.streamvault.core.ui.design.AppPalette
import org.junit.Test

class SettingsDesignTokensTest {
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
