package com.streamvault.core.ui.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.compositeOver
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackWebhookContrastTest {
    @Test fun `webhook text and focus remain readable in all states and themes`() {
        listOf("classic_blue", "m3_purple", "light", "forest", "sunset", "ocean", "rose", "amoled_black", "vintage").forEach { id ->
            val palette = AppPalette.forTheme(id)
            val text = contrast(palette.textPrimary, palette.surfaceElevated)
            val label = contrast(palette.textPrimary, palette.canvas)
            val focusInside = contrast(palette.focus, palette.surfaceElevated)
            val focusOutside = contrast(palette.focus, palette.canvas)
            val selection = palette.focus.copy(alpha = 0.22f).compositeOver(palette.surfaceElevated)
            val selectedText = contrast(palette.textPrimary, selection)
            val handle = contrast(palette.focus, selection)
            println("$id textPrimary/surfaceElevated=$text textPrimary/canvas=$label focus/surfaceElevated=$focusInside focus/canvas=$focusOutside textPrimary/selection=$selectedText focus/selection=$handle")
            assertTrue("$id textPrimary/surfaceElevated=$text", text >= 4.5f)
            assertTrue("$id textPrimary/canvas=$label", label >= 4.5f)
            assertTrue("$id focus/surfaceElevated=$focusInside", focusInside >= 3f)
            assertTrue("$id focus/canvas=$focusOutside", focusOutside >= 3f)
            assertTrue("$id textPrimary/selection=$selectedText", selectedText >= 4.5f)
            assertTrue("$id focus/selection=$handle", handle >= 3f)
        }
    }
    private fun contrast(a: Color, b: Color): Float = (maxOf(a.luminance(), b.luminance()) + .05f) /
        (minOf(a.luminance(), b.luminance()) + .05f)
}
