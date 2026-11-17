package com.streamvault.core.ui.design

import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveBrowseCountContrastTest {
    private val themes = listOf("classic_blue", "m3_purple", "light", "forest", "sunset", "ocean", "rose", "amoled_black", "vintage")

    @Test fun secondaryCancelButtonRemainsReadableAndFocusedAcrossEveryTheme() {
        themes.forEach { id ->
            val palette = AppPalette.forTheme(id).forBrowsing()
            listOf("ControlContainer" to palette.controlContainer, "SurfaceAccent" to palette.surfaceAccent)
                .forEach { (name, fill) ->
                    val a = palette.textPrimary.luminance()
                    val b = fill.luminance()
                    val ratio = (maxOf(a, b) + 0.05f) / (minOf(a, b) + 0.05f)
                    println("LIVE_CONTROL $id TextPrimary/$name=$ratio")
                    assertTrue("$id TextPrimary/$name $ratio", ratio >= 4.5f)
                }
            listOf("ControlContainer" to palette.controlContainer, "SurfaceAccent" to palette.surfaceAccent,
                "SurfaceElevated" to palette.surfaceElevated, "Surface" to palette.surface).forEach { (name, fill) ->
                val a = palette.focus.luminance()
                val b = fill.luminance()
                val ratio = (maxOf(a, b) + 0.05f) / (minOf(a, b) + 0.05f)
                println("LIVE_CONTROL $id Focus/$name=$ratio")
                assertTrue("$id Focus/$name $ratio", ratio >= 3f)
            }
        }
    }

    @Test fun countRemainsReadableAcrossEveryTheme() {
        themes
            .forEach { id ->
                val palette = AppPalette.forTheme(id).forBrowsing()
                listOf("Canvas" to palette.canvas, "CanvasElevated" to palette.canvasElevated, "Surface" to palette.surface,
                    "SurfaceElevated" to palette.surfaceElevated).forEach { (name, fill) ->
                    listOf("TextSecondary" to palette.textSecondary, "TextTertiary" to palette.textTertiary)
                        .forEach { (token, ink) ->
                            val a = ink.luminance()
                            val b = fill.luminance()
                            val ratio = (maxOf(a, b) + 0.05f) / (minOf(a, b) + 0.05f)
                            println("LIVE_COUNT $id $token/$name=$ratio")
                            assertTrue("$id $token/$name $ratio", ratio >= 4.5f)
                        }
                }
            }
    }
}
