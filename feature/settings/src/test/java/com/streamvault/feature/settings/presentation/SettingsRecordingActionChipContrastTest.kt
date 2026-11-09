package com.streamvault.feature.settings.presentation

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import com.streamvault.core.ui.design.AppPalette
import com.streamvault.core.ui.design.FocusSpec
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsRecordingActionChipContrastTest {
    private val themeIds = listOf(
        AppPalette.CLASSIC_BLUE_ID,
        AppPalette.M3_PURPLE_ID,
        AppPalette.LIGHT_ID,
        AppPalette.FOREST_ID,
        AppPalette.SUNSET_ID,
        AppPalette.OCEAN_ID,
        AppPalette.ROSE_ID,
        AppPalette.AMOLED_BLACK_ID,
        AppPalette.VINTAGE_ID,
    )

    @Test
    fun primaryInkMeetsContrastOnRestingAndFocusedActionChipsInEveryTheme() {
        themeIds.forEach { themeId ->
            val palette = AppPalette.forTheme(themeId)
            val actionAccents = listOf(
                "Brand" to palette.brand,
                "Live" to palette.live,
                "Success" to palette.success,
                "TextPrimary" to palette.textPrimary,
            )
            val chipSurfaces = listOf(palette.surface, palette.surfaceElevated)

            actionAccents.forEach { (accentToken, accent) ->
                listOf(
                    "resting" to 0.14f,
                    "focused" to 0.30f,
                ).forEach { (state, alpha) ->
                    chipSurfaces.forEachIndexed { surfaceIndex, surface ->
                        val tintedSurface = accent.copy(alpha = alpha).compositeOver(surface)
                        val contrast = contrastRatio(palette.textPrimary, tintedSurface)
                        assertTrue(
                            "$themeId AppColors.TextPrimary on $accentToken tint ($state, surface $surfaceIndex) " +
                                "had contrast $contrast, expected at least 4.5:1",
                            contrast >= 4.5f,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun focusBorderMeetsContrastAgainstRecordingSurfacesInEveryTheme() {
        assertTrue("Focus border must be at least 2dp", FocusSpec.BorderWidth.value >= 2f)

        themeIds.forEach { themeId ->
            val palette = AppPalette.forTheme(themeId)
            listOf(palette.surface, palette.surfaceElevated).forEachIndexed { surfaceIndex, surface ->
                val contrast = contrastRatio(palette.focus, surface)
                assertTrue(
                    "$themeId AppColors.Focus against recording surface $surfaceIndex " +
                        "had contrast $contrast, expected at least 3:1",
                    contrast >= 3f,
                )
            }
        }
    }

    private fun contrastRatio(first: Color, second: Color): Float {
        val firstLuminance = first.luminance()
        val secondLuminance = second.luminance()
        return (maxOf(firstLuminance, secondLuminance) + 0.05f) /
            (minOf(firstLuminance, secondLuminance) + 0.05f)
    }
}
