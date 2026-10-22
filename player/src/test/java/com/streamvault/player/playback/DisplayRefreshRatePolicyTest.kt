package com.streamvault.player.playback

import org.junit.Assert.*
import org.junit.Test

class DisplayRefreshRatePolicyTest {
    private fun mode(id: Int, hz: Float, width: Int = 3840, height: Int = 2160) =
        RefreshRateDisplayMode(id, width, height, hz)

    @Test fun `broadcast content selects even cadence without changing output resolution`() {
        val modes = listOf(mode(1, 60f), mode(2, 50f), mode(3, 24f), mode(4, 23.976f), mode(5, 59.94f))
        for ((fps, expected) in listOf(25f to 2, 50f to 2, 24f to 3, 23.976f to 4, 29.97f to 5, 59.94f to 5, 30f to 1, 60f to 1)) {
            assertEquals("fps=$fps", expected, DisplayRefreshRatePolicy.select(fps, modes[0], modes)?.id)
        }
    }

    @Test fun `fractional rates do not match integer display modes`() {
        assertFalse(DisplayRefreshRatePolicy.hasEvenCadence(24f, 23.976f))
        assertFalse(DisplayRefreshRatePolicy.hasEvenCadence(60f, 29.97f))
        assertFalse(DisplayRefreshRatePolicy.hasEvenCadence(59.94f, 30f))
        assertTrue(DisplayRefreshRatePolicy.hasEvenCadence(59.94006f, 29.97f))
        assertTrue(DisplayRefreshRatePolicy.hasEvenCadence(119.88f, 23.976f))
    }

    @Test fun `an already compatible higher refresh rate avoids HDMI resync`() {
        val current = mode(1, 120f)
        assertEquals(current, DisplayRefreshRatePolicy.select(24f, current, listOf(current, mode(2, 24f))))
    }

    @Test fun `unsupported cadence does not choose the nearest incompatible mode`() {
        assertNull(DisplayRefreshRatePolicy.select(25f, mode(1, 60f), listOf(mode(1, 60f))))
        assertNull(DisplayRefreshRatePolicy.select(60f, mode(1, 50f), listOf(mode(1, 50f))))
    }

    @Test fun `matching lower resolution is never substituted for the output resolution`() {
        val current = mode(1, 60f)
        assertNull(DisplayRefreshRatePolicy.select(25f, current, listOf(current, mode(2, 50f, 1920, 1080))))
    }

    @Test fun `25 and 30 fps prefer double cadence when switching from an incompatible mode`() {
        val current = mode(1, 24f)
        val supported = listOf(current, mode(2, 25f), mode(3, 50f), mode(4, 100f), mode(5, 30f), mode(6, 60f))
        assertEquals(3, DisplayRefreshRatePolicy.select(25f, current, supported)?.id)
        assertEquals(6, DisplayRefreshRatePolicy.select(30f, current, supported)?.id)
    }

    @Test fun `invalid metadata and empty capability lists do not request display modes`() {
        val current = mode(1, 60f)
        for (rate in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY, 1000f)) {
            assertNull(DisplayRefreshRatePolicy.select(rate, current, listOf(current)))
        }
        assertNull(DisplayRefreshRatePolicy.select(24f, current, emptyList()))
    }

    @Test fun `user playback speed can produce a supported cadence`() {
        val current = mode(1, 50f)
        assertEquals(2, DisplayRefreshRatePolicy.select(24f * 1.25f, current, listOf(current, mode(2, 60f)))?.id)
    }
}
