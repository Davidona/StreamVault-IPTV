package com.streamvault.player.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LiveStartupWatchdogTest {
    @Test
    fun `flat reserve refreshes at exactly the no progress boundary once`() {
        val watchdog = LiveStartupWatchdog()

        assertThat(watchdog.evaluate(0L, eligible = true, firstVideoFrameRendered = false,
            audioOnlyPlaying = false, bufferedDurationMs = 0L)).isEqualTo(LiveStartupWatchdog.Action.NONE)
        assertThat(watchdog.evaluate(44_999L, eligible = true, firstVideoFrameRendered = false,
            audioOnlyPlaying = false, bufferedDurationMs = 0L)).isEqualTo(LiveStartupWatchdog.Action.NONE)
        assertThat(watchdog.evaluate(45_000L, eligible = true, firstVideoFrameRendered = false,
            audioOnlyPlaying = false, bufferedDurationMs = 0L)).isEqualTo(LiveStartupWatchdog.Action.REFRESH_SOURCE)
        assertThat(watchdog.evaluate(90_000L, eligible = true, firstVideoFrameRendered = false,
            audioOnlyPlaying = false, bufferedDurationMs = 0L)).isEqualTo(LiveStartupWatchdog.Action.NONE)
    }

    @Test
    fun `preserved refresh episode gets a fresh clock then gives up without a second refresh`() {
        val watchdog = LiveStartupWatchdog()
        assertThat(watchdog.evaluate(1_000L, true, false, false, 0L))
            .isEqualTo(LiveStartupWatchdog.Action.NONE)
        assertThat(watchdog.evaluate(46_000L, true, false, false, 0L))
            .isEqualTo(LiveStartupWatchdog.Action.REFRESH_SOURCE)

        watchdog.reset(preserveEpisode = true)
        assertThat(watchdog.evaluate(500_000L, true, false, false, 0L))
            .isEqualTo(LiveStartupWatchdog.Action.NONE)
        assertThat(watchdog.evaluate(544_999L, true, false, false, 0L))
            .isEqualTo(LiveStartupWatchdog.Action.NONE)
        assertThat(watchdog.evaluate(545_000L, true, false, false, 0L))
            .isEqualTo(LiveStartupWatchdog.Action.GIVE_UP)
        assertThat(watchdog.evaluate(590_000L, true, false, false, 0L))
            .isEqualTo(LiveStartupWatchdog.Action.NONE)
    }

    @Test
    fun `already used source retry skips refresh and gives up at boundary`() {
        val watchdog = LiveStartupWatchdog()
        assertThat(watchdog.evaluate(0L, true, false, false, 0L,
            sourceRetryAlreadyUsed = true)).isEqualTo(LiveStartupWatchdog.Action.NONE)
        assertThat(watchdog.evaluate(45_000L, true, false, false, 0L,
            sourceRetryAlreadyUsed = true)).isEqualTo(LiveStartupWatchdog.Action.GIVE_UP)
    }

    @Test
    fun `growing reserve for more than two minutes is not a no progress episode`() {
        val watchdog = LiveStartupWatchdog()
        for (second in 0..130) {
            assertThat(watchdog.evaluate(second * 1_000L, true, false, false,
                second * 1_000L)).isEqualTo(LiveStartupWatchdog.Action.NONE)
        }
        assertThat(watchdog.evaluate(174_999L, true, false, false, 130_000L))
            .isEqualTo(LiveStartupWatchdog.Action.NONE)
        assertThat(watchdog.evaluate(175_000L, true, false, false, 130_000L))
            .isEqualTo(LiveStartupWatchdog.Action.REFRESH_SOURCE)
    }

    @Test
    fun `buffer progress must clear the minimum gain above the high water mark`() {
        val watchdog = LiveStartupWatchdog(noProgressTimeoutMs = 1_000L, minBufferProgressMs = 250L)
        assertThat(watchdog.evaluate(0L, true, false, false, 1_000L))
            .isEqualTo(LiveStartupWatchdog.Action.NONE)
        assertThat(watchdog.evaluate(900L, true, false, false, 1_249L))
            .isEqualTo(LiveStartupWatchdog.Action.NONE)
        assertThat(watchdog.evaluate(1_000L, true, false, false, 1_250L))
            .isEqualTo(LiveStartupWatchdog.Action.NONE)
        assertThat(watchdog.evaluate(1_999L, true, false, false, 1_499L))
            .isEqualTo(LiveStartupWatchdog.Action.NONE)
        assertThat(watchdog.evaluate(2_000L, true, false, false, 1_500L))
            .isEqualTo(LiveStartupWatchdog.Action.NONE)
        assertThat(watchdog.evaluate(2_999L, true, false, false, 1_500L))
            .isEqualTo(LiveStartupWatchdog.Action.NONE)
        assertThat(watchdog.evaluate(3_000L, true, false, false, 1_500L))
            .isEqualTo(LiveStartupWatchdog.Action.REFRESH_SOURCE)
    }

    @Test
    fun `oscillating reserve does not accumulate progress`() {
        val watchdog = LiveStartupWatchdog(noProgressTimeoutMs = 1_000L, minBufferProgressMs = 250L)
        assertThat(watchdog.evaluate(0L, true, false, false, 1_000L))
            .isEqualTo(LiveStartupWatchdog.Action.NONE)
        assertThat(watchdog.evaluate(500L, true, false, false, 900L))
            .isEqualTo(LiveStartupWatchdog.Action.NONE)
        assertThat(watchdog.evaluate(999L, true, false, false, 1_100L))
            .isEqualTo(LiveStartupWatchdog.Action.NONE)
        assertThat(watchdog.evaluate(1_000L, true, false, false, 1_000L))
            .isEqualTo(LiveStartupWatchdog.Action.REFRESH_SOURCE)
    }

    @Test
    fun `ineligible interval suspends clock and reserve but retains episode budget`() {
        val watchdog = LiveStartupWatchdog(noProgressTimeoutMs = 1_000L, minBufferProgressMs = 250L)
        assertThat(watchdog.evaluate(0L, true, false, false, 700L))
            .isEqualTo(LiveStartupWatchdog.Action.NONE)
        assertThat(watchdog.evaluate(900L, false, false, false, 5_000L))
            .isEqualTo(LiveStartupWatchdog.Action.NONE)
        assertThat(watchdog.evaluate(100_000L, true, false, false, 0L))
            .isEqualTo(LiveStartupWatchdog.Action.NONE)
        assertThat(watchdog.evaluate(100_999L, true, false, false, 0L))
            .isEqualTo(LiveStartupWatchdog.Action.NONE)
        assertThat(watchdog.evaluate(101_000L, true, false, false, 0L))
            .isEqualTo(LiveStartupWatchdog.Action.REFRESH_SOURCE)

        watchdog.reset(preserveEpisode = true) // Source reprepare clears the report latch only.
        watchdog.evaluate(102_000L, false, false, false, 0L) // e.g. pause after refresh
        watchdog.evaluate(200_000L, true, false, false, 0L)
        assertThat(watchdog.evaluate(201_000L, true, false, false, 0L))
            .isEqualTo(LiveStartupWatchdog.Action.GIVE_UP)
    }

    @Test
    fun `first frame or audio only playback completes the episode`() {
        val videoWatchdog = LiveStartupWatchdog(noProgressTimeoutMs = 1_000L)
        assertThat(videoWatchdog.evaluate(0L, true, false, false, 0L))
            .isEqualTo(LiveStartupWatchdog.Action.NONE)
        assertThat(videoWatchdog.evaluate(500L, true, firstVideoFrameRendered = true,
            audioOnlyPlaying = false, bufferedDurationMs = 0L)).isEqualTo(LiveStartupWatchdog.Action.NONE)
        assertThat(videoWatchdog.evaluate(10_000L, true, false, false, 0L))
            .isEqualTo(LiveStartupWatchdog.Action.NONE)
        videoWatchdog.reset()
        assertThat(videoWatchdog.evaluate(20_000L, true, false, false, 0L))
            .isEqualTo(LiveStartupWatchdog.Action.NONE)
        assertThat(videoWatchdog.evaluate(21_000L, true, false, false, 0L))
            .isEqualTo(LiveStartupWatchdog.Action.REFRESH_SOURCE)

        val audioWatchdog = LiveStartupWatchdog(noProgressTimeoutMs = 1_000L)
        audioWatchdog.evaluate(0L, true, false, false, 0L)
        audioWatchdog.evaluate(500L, true, false, audioOnlyPlaying = true, bufferedDurationMs = 0L)
        assertThat(audioWatchdog.evaluate(10_000L, true, false, false, 0L))
            .isEqualTo(LiveStartupWatchdog.Action.NONE)
    }

    @Test
    fun `new prepare can explicitly start a fresh outage budget`() {
        val watchdog = LiveStartupWatchdog(noProgressTimeoutMs = 1_000L)
        watchdog.evaluate(0L, true, false, false, 0L)
        assertThat(watchdog.evaluate(1_000L, true, false, false, 0L))
            .isEqualTo(LiveStartupWatchdog.Action.REFRESH_SOURCE)

        watchdog.reset(preserveEpisode = false)
        assertThat(watchdog.evaluate(100_000L, true, false, false, 0L))
            .isEqualTo(LiveStartupWatchdog.Action.NONE)
        assertThat(watchdog.evaluate(101_000L, true, false, false, 0L))
            .isEqualTo(LiveStartupWatchdog.Action.REFRESH_SOURCE)
    }

    @Test
    fun `pause clock rebases no progress time and keeps the episode retry budget`() {
        val watchdog = LiveStartupWatchdog()
        assertThat(watchdog.evaluate(0L, true, false, false, 0L))
            .isEqualTo(LiveStartupWatchdog.Action.NONE)
        assertThat(watchdog.evaluate(44_999L, true, false, false, 0L))
            .isEqualTo(LiveStartupWatchdog.Action.NONE)
        watchdog.pauseClock() // Called synchronously at pause/detach, without relying on polling.

        assertThat(watchdog.evaluate(1_000_000L, true, false, false, 0L))
            .isEqualTo(LiveStartupWatchdog.Action.NONE)
        assertThat(watchdog.evaluate(1_044_999L, true, false, false, 0L))
            .isEqualTo(LiveStartupWatchdog.Action.NONE)
        assertThat(watchdog.evaluate(1_045_000L, true, false, false, 0L))
            .isEqualTo(LiveStartupWatchdog.Action.REFRESH_SOURCE)

        watchdog.reset(preserveEpisode = true)
        assertThat(watchdog.evaluate(2_000_000L, true, false, false, 0L))
            .isEqualTo(LiveStartupWatchdog.Action.NONE)
        watchdog.pauseClock()
        assertThat(watchdog.evaluate(2_100_000L, true, false, false, 0L))
            .isEqualTo(LiveStartupWatchdog.Action.NONE)
        assertThat(watchdog.evaluate(2_144_999L, true, false, false, 0L))
            .isEqualTo(LiveStartupWatchdog.Action.NONE)
        assertThat(watchdog.evaluate(2_145_000L, true, false, false, 0L))
            .isEqualTo(LiveStartupWatchdog.Action.GIVE_UP)

        val completedAudioWatchdog = LiveStartupWatchdog()
        completedAudioWatchdog.evaluate(0L, true, false, false, 0L)
        completedAudioWatchdog.evaluate(500L, true, false, audioOnlyPlaying = true, bufferedDurationMs = 0L)
        completedAudioWatchdog.pauseClock()
        assertThat(completedAudioWatchdog.evaluate(1_000_000L, true, false, false, 0L))
            .isEqualTo(LiveStartupWatchdog.Action.NONE)
    }

    @Test
    fun `large monotonic clock origin does not consume budget`() {
        val watchdog = LiveStartupWatchdog()
        val origin = 8_000_000_000L
        assertThat(watchdog.evaluate(origin, true, false, false, 0L))
            .isEqualTo(LiveStartupWatchdog.Action.NONE)
        assertThat(watchdog.evaluate(origin + 44_999L, true, false, false, 0L))
            .isEqualTo(LiveStartupWatchdog.Action.NONE)
        assertThat(watchdog.evaluate(origin + 45_000L, true, false, false, 0L))
            .isEqualTo(LiveStartupWatchdog.Action.REFRESH_SOURCE)
    }
}
