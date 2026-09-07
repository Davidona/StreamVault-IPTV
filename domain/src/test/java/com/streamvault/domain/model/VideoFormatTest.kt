package com.streamvault.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoFormatTest {

    @Test
    fun `frameRateLabel is null when frame rate is unknown`() {
        assertNull(VideoFormat(1920, 1080).frameRateLabel)
        assertNull(formatFrameRateLabel(0f))
        assertNull(formatFrameRateLabel(-1f))
    }

    @Test
    fun `frameRateLabel drops fractional part for whole frame rates`() {
        assertEquals("25", VideoFormat(1920, 1080, frameRate = 25f).frameRateLabel)
        assertEquals("50", formatFrameRateLabel(50f))
        assertEquals("60", formatFrameRateLabel(59.999f))
    }

    @Test
    fun `frameRateLabel keeps up to two decimals for fractional frame rates`() {
        assertEquals("29.97", formatFrameRateLabel(29.97f))
        assertEquals("23.98", formatFrameRateLabel(23.976f))
        assertEquals("24.5", formatFrameRateLabel(24.5f))
    }
}
