package com.streamvault.core.ui.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowsePaletteContrastTest {
    private val allThemeIds = listOf(
        AppPalette.CLASSIC_BLUE_ID, AppPalette.M3_PURPLE_ID, AppPalette.LIGHT_ID,
        AppPalette.FOREST_ID, AppPalette.SUNSET_ID, AppPalette.OCEAN_ID,
        AppPalette.ROSE_ID, AppPalette.AMOLED_BLACK_ID, AppPalette.VINTAGE_ID,
    )

    private fun contrast(a: Color, b: Color): Float {
        val la = a.luminance()
        val lb = b.luminance()
        return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
    }

    @Test
    fun allNineThemesResolveToDistinctPalettes() {
        assertEquals(9, allThemeIds.map { AppPalette.forTheme(it) }.toSet().size)
    }

    @Test
    fun focusRingIsVisibleOnEveryBrowseSurfaceInEveryTheme() {
        allThemeIds.forEach { id ->
            val browse = AppPalette.forTheme(id).forBrowsing()
            mapOf(
                "canvas" to browse.canvas,
                "canvasElevated" to browse.canvasElevated,
                "surface" to browse.surface,
                "surfaceElevated" to browse.surfaceElevated,
                "surfaceAccent" to browse.surfaceAccent,
            ).forEach { (name, background) ->
                val ratio = contrast(browse.focus, background)
                assertTrue("$id focus ring on $name was $ratio", ratio >= 3f)
            }
        }
    }

    @Test
    fun browseFocusIsTheAppFocusToken() {
        allThemeIds.forEach { id ->
            val palette = AppPalette.forTheme(id)
            assertEquals(id, palette.focus, palette.forBrowsing().focus)
        }
    }

    @Test
    fun metadataRemainsReadableOnFocusedSurfaces() {
        listOf(AppPalette.classicBlue(), AppPalette.m3Purple(), AppPalette.light()).forEach {
            val browse = it.forBrowsing()
            val background = browse.surfaceEmphasis.luminance()
            val foreground = browse.textTertiary.luminance()
            val contrast = (maxOf(background, foreground) + 0.05f) /
                (minOf(background, foreground) + 0.05f)
            assertTrue("Metadata contrast was $contrast", contrast >= 4.5f)
        }
    }

    @Test
    fun primaryActionsPreserveAccentAndReadableInkAcrossPalettes() {
        val palettes = listOf(
            AppPalette.classicBlue(), AppPalette.m3Purple(), AppPalette.light(),
            AppPalette.classicBlue().copy(brand = Color(0xFF8EDADC)),
            AppPalette.classicBlue().copy(brand = Color(0xFF103858)),
        )
        palettes.forEach { original ->
            val browse = original.forBrowsing()
            assertEquals(original.brand, browse.brand)
            val background = browse.brand.luminance()
            val foreground = browse.onPrimary.luminance()
            val contrast = (maxOf(background, foreground) + 0.05f) /
                (minOf(background, foreground) + 0.05f)
            assertTrue("Primary action contrast was $contrast", contrast >= 4.5f)
        }
    }
}
