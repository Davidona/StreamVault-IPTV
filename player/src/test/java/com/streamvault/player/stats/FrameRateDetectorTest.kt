package com.streamvault.player.stats

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FrameRateDetectorTest {

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
        feed(detector, intervalUs = 40_000, frames = 11)

        assertThat(detector.lockedFrameRate).isEqualTo(25f)
    }

    @Test
    fun `snaps jittery 50 fps timestamps to 50`() {
        val detector = FrameRateDetector(framesToLock = 10)
        var pts = 0L
        val jitter = longArrayOf(19_900, 20_100, 20_000, 19_950, 20_050)
        repeat(12) { i ->
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
        for (gap in gapsInFrames) {
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
    fun `ignores discontinuities and stops working once locked`() {
        val detector = FrameRateDetector(framesToLock = 10)
        feed(detector, intervalUs = 40_000, frames = 5)
        detector.onFrame(90_000_000L) // seek: huge gap must not count
        feed(detector, intervalUs = 40_000, frames = 7, startUs = 90_040_000L)
        assertThat(detector.lockedFrameRate).isEqualTo(25f)

        // Once locked, wildly different spacing is ignored.
        feed(detector, intervalUs = 16_667, frames = 30, startUs = 200_000_000L)
        assertThat(detector.lockedFrameRate).isEqualTo(25f)
    }

    @Test
    fun `reset allows a new stream to be detected`() {
        val detector = FrameRateDetector(framesToLock = 10)
        feed(detector, intervalUs = 40_000, frames = 11)
        assertThat(detector.lockedFrameRate).isEqualTo(25f)

        detector.reset()
        assertThat(detector.lockedFrameRate).isEqualTo(0f)
        feed(detector, intervalUs = 20_000, frames = 11)
        assertThat(detector.lockedFrameRate).isEqualTo(50f)
    }
}
