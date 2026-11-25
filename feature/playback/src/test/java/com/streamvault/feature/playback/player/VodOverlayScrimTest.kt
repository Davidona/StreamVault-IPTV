package com.streamvault.feature.playback.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VodOverlayScrimTest {

    // Compose colors keep alpha in 8 bits, so 0.92 reads back as 0.9216.
    private fun alphaAt(fraction: Float): Float {
        val stops = vodScrimStops()
        val after = stops.indexOfFirst { it.first >= fraction }
        if (after <= 0) return stops.first().second.alpha
        val (x0, c0) = stops[after - 1]
        val (x1, c1) = stops[after]
        val t = if (x1 == x0) 0f else (fraction - x0) / (x1 - x0)
        return c0.alpha + (c1.alpha - c0.alpha) * t
    }

    @Test
    fun `stop positions never go backwards`() {
        val positions = vodScrimStops().map { it.first }
        assertThat(positions).isInOrder()
        assertThat(positions.first()).isEqualTo(0f)
        assertThat(positions.last()).isEqualTo(1f)
    }

    @Test
    fun `header text region keeps at least 55 percent black`() {
        assertThat(alphaAt(0f)).isAtLeast(0.55f)
        assertThat(alphaAt(VOD_SCRIM_TOP_TEXT_END)).isAtLeast(0.55f)
    }

    @Test
    fun `time row keeps at least 55 percent black`() {
        assertThat(alphaAt(0.83f)).isAtLeast(0.55f)
        assertThat(alphaAt(1f)).isWithin(0.005f).of(VOD_SCRIM_BOTTOM_ALPHA)
    }

    @Test
    fun `the middle of the picture is untouched`() {
        assertThat(alphaAt(0.4f)).isEqualTo(0f)
    }

    @Test
    fun `each fade is monotonic so there is no visible step`() {
        val stops = vodScrimStops()
        val topFade = stops.filter { it.first in VOD_SCRIM_TOP_TEXT_END..VOD_SCRIM_TOP_END }.map { it.second.alpha }
        val bottomFade = stops.filter { it.first >= VOD_SCRIM_BOTTOM_START }.map { it.second.alpha }
        assertThat(topFade).isInOrder(compareByDescending<Float> { it })
        assertThat(bottomFade).isInOrder()
    }
}
