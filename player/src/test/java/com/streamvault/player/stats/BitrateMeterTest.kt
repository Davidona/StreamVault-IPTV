package com.streamvault.player.stats

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BitrateMeterTest {

    @Test
    fun `unknown until two samples`() {
        val meter = BitrateMeter()
        assertThat(meter.addSample(timeMs = 0, cumulativeBytes = 0)).isEqualTo(0L)
    }

    @Test
    fun `first interval is reported directly and rounded to 100 kbps`() {
        val meter = BitrateMeter()
        meter.addSample(timeMs = 0, cumulativeBytes = 0)
        // 530,000 bytes in 1 s = 4.24 Mbps -> 4.2 Mbps
        assertThat(meter.addSample(timeMs = 1_000, cumulativeBytes = 530_000)).isEqualTo(4_200_000L)
    }

    @Test
    fun `smooths towards a new rate instead of jumping`() {
        val meter = BitrateMeter(alpha = 0.5f)
        meter.addSample(timeMs = 0, cumulativeBytes = 0)
        meter.addSample(timeMs = 1_000, cumulativeBytes = 500_000) // 4 Mbps
        val next = meter.addSample(timeMs = 2_000, cumulativeBytes = 1_500_000) // 8 Mbps instant

        assertThat(next).isEqualTo(6_000_000L)
        assertThat(meter.bitrateBps).isEqualTo(6_000_000L)
    }

    @Test
    fun `counter reset starts over rather than going negative`() {
        val meter = BitrateMeter()
        meter.addSample(timeMs = 0, cumulativeBytes = 5_000_000)
        meter.addSample(timeMs = 1_000, cumulativeBytes = 5_500_000)
        assertThat(meter.addSample(timeMs = 2_000, cumulativeBytes = 100)).isEqualTo(0L)
        assertThat(meter.addSample(timeMs = 3_000, cumulativeBytes = 500_100)).isEqualTo(4_000_000L)
    }

    @Test
    fun `reset forgets history`() {
        val meter = BitrateMeter()
        meter.addSample(timeMs = 0, cumulativeBytes = 0)
        meter.addSample(timeMs = 1_000, cumulativeBytes = 500_000)
        meter.reset()
        assertThat(meter.bitrateBps).isEqualTo(0L)
        assertThat(meter.addSample(timeMs = 5_000, cumulativeBytes = 900_000)).isEqualTo(0L)
    }
}
