package com.streamvault.player.stats

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.roundToLong

class FrameRateDetectorTest {

    @Test
    fun `millisecond rounded timestamps preserve broadcast cadence`() {
        for (rate in listOf(23.976f, 24f, 25f, 29.97f, 30f, 47.952f, 48f, 50f, 59.94f, 60f, 100f, 119.88f, 120f, 240f)) {
            val detector = FrameRateDetector()
            repeat(1500) { frame ->
                val pts = (frame * 1_000_000.0 / rate / 1000).roundToLong() * 1000
                detector.onFrame(pts)
            }
            assertThat(detector.lockedFrameRate).isEqualTo(rate)
        }
    }

    @Test
    fun `rounded timestamps with dropped frames retain fractional cadence`() {
        for (rate in listOf(29.97f, 59.94f, 60f)) {
            val detector = FrameRateDetector()
            var frame = 0L
            val strides = intArrayOf(2, 3, 1, 2, 1, 3, 1)
            repeat(600) { sample ->
                detector.onFrame((frame * 1_000_000.0 / rate / 1000).roundToLong() * 1000)
                frame += strides[sample % strides.size]
            }
            assertThat(detector.lockedFrameRate).isEqualTo(rate)
        }
    }

    @Test fun `rare single frame intervals do not make a high rate stream lock to half rate`() {
        for (rate in listOf(59.94f, 60f)) {
            val detector = FrameRateDetector()
            var frame = 0L
            repeat(600) { sample ->
                detector.onFrame((frame * 1_000_000.0 / rate / 1000).roundToLong() * 1000)
                frame += if (sample % 80 == 0) 1 else 2
            }
            assertThat(detector.lockedFrameRate).isEqualTo(rate)
        }
    }

    @Test
    fun `isolated short interval cannot determine the stream cadence`() {
        val detector = FrameRateDetector()
        var pts = 0L
        repeat(400) { frame ->
            detector.onFrame(pts)
            pts += when (frame) {
                20 -> 8_000
                21 -> 72_000
                else -> 40_000
            }
        }
        assertThat(detector.lockedFrameRate).isEqualTo(25f)
    }

    @Test fun `four seconds of timing and the requested sample count are both required`() {
        val detector = FrameRateDetector()
        feed(detector, intervalUs = 16_667, frames = 200)
        assertThat(detector.lockedFrameRate).isEqualTo(0f)
        feed(detector, intervalUs = 16_667, frames = 200, startUs = 200 * 16_667L)
        assertThat(detector.lockedFrameRate).isEqualTo(60f)

        val moreSamples = FrameRateDetector(framesToLock = 300)
        feed(moreSamples, intervalUs = 40_000, frames = 200)
        assertThat(moreSamples.lockedFrameRate).isEqualTo(0f)
        feed(moreSamples, intervalUs = 40_000, frames = 200, startUs = 8_000_000L)
        assertThat(moreSamples.lockedFrameRate).isEqualTo(25f)
    }

    @Test fun `duplicate timestamps do not erase valid observations`() {
        val detector = FrameRateDetector()
        repeat(400) { frame ->
            repeat(2) { detector.onFrame(frame * 20_000L) }
        }
        assertThat(detector.lockedFrameRate).isEqualTo(50f)
    }

    @Test fun `stable nonstandard cadence is retained but irregular timing stays unknown`() {
        val nonstandard = FrameRateDetector()
        feed(nonstandard, intervalUs = 25_000, frames = 400)
        assertThat(nonstandard.lockedFrameRate).isEqualTo(40f)
        val irregular = FrameRateDetector()
        val gaps = longArrayOf(20_000, 33_000, 47_000, 73_000)
        var pts = 0L
        repeat(1500) { frame ->
            irregular.onFrame(pts)
            pts += gaps[frame % gaps.size]
        }
        assertThat(irregular.lockedFrameRate).isEqualTo(0f)
    }

    @Test
    fun `fractional timestamp cadences remain distinct from integer rates`() {
        for ((interval, expected) in listOf(41_708L to 23.976f, 33_367L to 29.97f, 16_683L to 59.94f)) {
            val detector = FrameRateDetector(framesToLock = 10)
            feed(detector, intervalUs = interval, frames = 600)
            assertThat(detector.lockedFrameRate).isEqualTo(expected)
        }
    }

    private fun feed(detector: FrameRateDetector, intervalUs: Long, frames: Int, startUs: Long = 0L) {
        var pts = startUs
        repeat(frames) {
            detector.onFrame(pts)
            pts += intervalUs
        }
    }

    @Test
    fun `stays unknown until enough frames were seen`() {
        val detector = FrameRateDetector(framesToLock = 10)
        feed(detector, intervalUs = 40_000, frames = 10) // 9 usable deltas

        assertThat(detector.lockedFrameRate).isEqualTo(0f)
    }

    @Test
    fun `locks to 25 fps for 40ms frame spacing`() {
        val detector = FrameRateDetector(framesToLock = 10)
        feed(detector, intervalUs = 40_000, frames = 200)

        assertThat(detector.lockedFrameRate).isEqualTo(25f)
    }

    @Test
    fun `snaps jittery 50 fps timestamps to 50`() {
        val detector = FrameRateDetector(framesToLock = 10)
        var pts = 0L
        val jitter = longArrayOf(19_900, 20_100, 20_000, 19_950, 20_050)
        repeat(400) { i ->
            detector.onFrame(pts)
            pts += jitter[i % jitter.size]
        }

        assertThat(detector.lockedFrameRate).isEqualTo(50f)
    }

    @Test
    fun `reports the stream rate even when frames are being dropped`() {
        val detector = FrameRateDetector(framesToLock = 10)
        // 25 fps stream but the device renders only every other or third frame, except a few.
        var pts = 0L
        val gapsInFrames = intArrayOf(2, 3, 1, 2, 3, 3, 2, 1, 2, 3, 2)
        repeat(300) { index ->
            val gap = gapsInFrames[index % gapsInFrames.size]
            detector.onFrame(pts)
            pts += 40_000L * gap
        }

        assertThat(detector.lockedFrameRate).isEqualTo(25f)
    }

    @Test
    fun `snaps 23_976 and 29_97 to their standard rates`() {
        assertThat(FrameRateDetector.snapToStandardRate(23.9f)).isEqualTo(23.976f)
        assertThat(FrameRateDetector.snapToStandardRate(29.9f)).isEqualTo(29.97f)
        assertThat(FrameRateDetector.snapToStandardRate(60.2f)).isEqualTo(60f)
        assertThat(FrameRateDetector.snapToStandardRate(40f)).isEqualTo(40f)
    }

    @Test
    fun `discontinuity restarts observations and a completed lock stays stable`() {
        val detector = FrameRateDetector(framesToLock = 10)
        feed(detector, intervalUs = 40_000, frames = 50)
        detector.onFrame(90_000_000L)
        repeat(400) { frame ->
            detector.onFrame(90_016_667L + frame * 16_667L)
            if (frame == 120) assertThat(detector.lockedFrameRate).isEqualTo(0f)
        }
        assertThat(detector.lockedFrameRate).isEqualTo(60f)

        // Once locked, wildly different spacing is ignored.
        feed(detector, intervalUs = 40_000, frames = 300, startUs = 200_000_000L)
        assertThat(detector.lockedFrameRate).isEqualTo(60f)
    }

    @Test
    fun `reset allows a new stream to be detected`() {
        val detector = FrameRateDetector(framesToLock = 10)
        feed(detector, intervalUs = 40_000, frames = 200)
        assertThat(detector.lockedFrameRate).isEqualTo(25f)

        detector.reset()
        assertThat(detector.lockedFrameRate).isEqualTo(0f)
        feed(detector, intervalUs = 20_000, frames = 300)
        assertThat(detector.lockedFrameRate).isEqualTo(50f)
    }
}
