package com.streamvault.core.ui.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowsePaletteContrastTest {
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
