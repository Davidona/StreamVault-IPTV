package com.streamvault.feature.playback.player

import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VodOverlayScrimTest {

    // Compose colors keep alpha in 8 bits, so 0.92 reads back as 0.9216.
    private fun alphaAt(stops: List<Pair<Float, Color>>, fraction: Float): Float {
        val after = stops.indexOfFirst { it.first >= fraction }
        if (after <= 0) return stops.first().second.alpha
        val (x0, c0) = stops[after - 1]
        val (x1, c1) = stops[after]
        val t = if (x1 == x0) 0f else (fraction - x0) / (x1 - x0)
        return c0.alpha + (c1.alpha - c0.alpha) * t
    }

    // Position of a screen fraction inside the bottom box.
    private fun inBottomBox(screenFraction: Float) =
        (screenFraction - VOD_SCRIM_BOTTOM_START) / (1f - VOD_SCRIM_BOTTOM_START)

    @Test
    fun `stop positions never go backwards and span each box`() {
        for (stops in listOf(vodTopScrimStops(), vodBottomScrimStops())) {
            val positions = stops.map { it.first }
            assertThat(positions).isInOrder()
            assertThat(positions.first()).isEqualTo(0f)
            assertThat(positions.last()).isEqualTo(1f)
        }
    }

    @Test
    fun `each brush stays within the gradient shader's cheap stop count`() {
        assertThat(vodTopScrimStops().size).isAtMost(8)
        assertThat(vodBottomScrimStops().size).isAtMost(8)
    }

    @Test
    fun `header text region keeps at least 55 percent black`() {
        val top = vodTopScrimStops()
        assertThat(alphaAt(top, 0f)).isAtLeast(0.55f)
        assertThat(alphaAt(top, VOD_SCRIM_TOP_TEXT_END / VOD_SCRIM_TOP_END)).isAtLeast(0.55f)
    }

    @Test
    fun `time row keeps at least 55 percent black`() {
        val bottom = vodBottomScrimStops()
        assertThat(alphaAt(bottom, inBottomBox(0.83f))).isAtLeast(0.55f)
        assertThat(alphaAt(bottom, 1f)).isWithin(0.005f).of(VOD_SCRIM_BOTTOM_ALPHA)
    }

    @Test
    fun `the middle of the picture is outside both boxes`() {
        assertThat(VOD_SCRIM_TOP_END).isLessThan(0.4f)
        assertThat(VOD_SCRIM_BOTTOM_START).isGreaterThan(0.4f)
    }

    @Test
    fun `each fade is monotonic so there is no visible step`() {
        val topFade = vodTopScrimStops()
            .filter { it.first >= VOD_SCRIM_TOP_TEXT_END / VOD_SCRIM_TOP_END }.map { it.second.alpha }
        val bottomFade = vodBottomScrimStops().map { it.second.alpha }
        assertThat(topFade).isInOrder(compareByDescending<Float> { it })
        assertThat(bottomFade).isInOrder()
    }
}
