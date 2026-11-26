package com.streamvault.feature.playback.player

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import com.streamvault.core.ui.design.AppPalette
import org.junit.Assert.assertTrue
import org.junit.Test

class VodContrastTest {
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

    private fun check(label: String, ratio: Float, minimum: Float) {
        println("VOD $label=$ratio")
        assertTrue("$label was $ratio, needs $minimum", ratio >= minimum)
    }

    @Test
    fun panelTextAndRowsAreReadableInEveryTheme() {
        allThemeIds.forEach { id ->
            val p = AppPalette.forTheme(id)
            check("$id TextPrimary/Surface", contrast(p.textPrimary, p.surface), 4.5f)
            check("$id TextSecondary/Surface", contrast(p.textSecondary, p.surface), 4.5f)
            check("$id Canvas/Focus (focused row, chip, button)", contrast(p.canvas, p.focus), 4.5f)
            check("$id OnSelectedContainer/SelectedContainer (selected row, chip)", contrast(p.onSelectedContainer, p.selectedContainer), 4.5f)
            check("$id TextPrimary/controlContainer (chip at rest)", contrast(p.textPrimary, p.controlContainer), 4.5f)
        }
    }

    @Test
    fun panelStateEdgesAreVisibleAgainstTheSurface() {
        allThemeIds.forEach { id ->
            val p = AppPalette.forTheme(id)
            check("$id Focus vs Surface", contrast(p.focus, p.surface), 3f)
            check("$id SelectedContainer vs Surface", contrast(p.selectedContainer, p.surface), 3f)
            // The palette itself only guarantees 1.3:1 for controlContainer in light (see
            // BrowsePaletteContrastTest), so the chip edge is held to that floor, not to 3:1.
            // A chip at rest is also labelled, so its state is not carried by the edge alone.
            val floor = if (p.isLight) 1.3f else 1.5f
            check("$id controlContainer vs Surface", contrast(p.controlContainer, p.surface), floor)
        }
    }

    // Overlay fills sit over video, not over a palette surface, so the worst case is modelled: a pure
    // white frame, the scrim at its lightest where the element sits, then the fill, then the ink.
    // Fixed white alphas, so the result is the same in every theme and one run covers all nine.
    @Test
    fun chipTextIsReadableOverTheLightestFrameUnderTheHeaderScrim() {
        val underScrim = Color.Black.copy(alpha = VOD_SCRIM_TEXT_ALPHA).compositeOver(Color.White)
        val chip = VodChipContainer.compositeOver(underScrim)
        check("White/VodChipContainer over header scrim (white frame)", contrast(Color.White, chip), 4.5f)
    }

    @Test
    fun buttonIconsAndRingAreReadableOverTheLightestFrameUnderTheBottomScrim() {
        // The button row sits at about 0.85 to 0.92 of the bottom scrim; 0.85 is the lighter edge.
        val buttonRowScrimAlpha = 0.85f
        val underScrim = Color.Black.copy(alpha = buttonRowScrimAlpha).compositeOver(Color.White)
        val button = VodButtonContainer.compositeOver(underScrim)
        check("White icon/VodButtonContainer over bottom scrim (white frame)", contrast(Color.White, button), 4.5f)
        check("White focus ring vs bottom scrim (white frame)", contrast(Color.White, underScrim), 3f)
    }
}
