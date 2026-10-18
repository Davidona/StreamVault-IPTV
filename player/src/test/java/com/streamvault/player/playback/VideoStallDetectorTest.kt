package com.streamvault.player.playback

import com.google.common.truth.Truth.assertThat
import com.streamvault.player.PlaybackState
import org.junit.Test

class VideoStallDetectorTest {
    private var nowMs = 0L

    @Test
    fun `unsuccessful recovery is reported again after grace without inventing a rendered frame`() {
        val detector = detector()
        detector.reset()
        nowMs = 1_000L
        detector.onVideoFrameRendered(500L)
        nowMs = 9_000L
        assertThat(detector.shouldReportStall(
            PlaybackState.READY, true, true, 9_000L, 5_000L,
            recoverFrameSilentReadyStalls = true
        )).isTrue()
        detector.restartAfterRecoveryAttempt()
        assertThat(detector.lastVideoFrameAgoMs()).isEqualTo(8_000L)
        nowMs = 12_000L
        assertThat(detector.shouldReportStall(
            PlaybackState.READY, true, true, 12_000L, 5_000L,
            recoverFrameSilentReadyStalls = true
        )).isFalse()
        nowMs = 18_000L
        assertThat(detector.shouldReportStall(
            PlaybackState.READY, true, true, 18_000L, 5_000L,
            recoverFrameSilentReadyStalls = true
        )).isTrue()
    }

    @Test
    fun `does not report before initial grace`() {
        val detector = detector()
        detector.reset()
        detector.onVideoFrameRendered()
        nowMs = 3_000L

        assertThat(
            detector.shouldReportStall(
                playbackState = PlaybackState.READY,
                isPlaying = true,
                playbackStarted = true,
                currentPositionMs = 3_000L,
                bufferedDurationMs = 5_000L
            )
        ).isFalse()
    }

    @Test
    fun `reports once when position advances and frames stop`() {
        val detector = detector()
        detector.reset()
        nowMs = 1_000L
        detector.onVideoFrameRendered(currentPositionMs = 500L)
        nowMs = 9_000L

        assertThat(
            detector.shouldReportStall(
                playbackState = PlaybackState.READY,
                isPlaying = true,
                playbackStarted = true,
                currentPositionMs = 3_000L,
                bufferedDurationMs = 5_000L
            )
        ).isTrue()
        assertThat(
            detector.shouldReportStall(
                playbackState = PlaybackState.READY,
                isPlaying = true,
                playbackStarted = true,
                currentPositionMs = 5_000L,
                bufferedDurationMs = 5_000L
            )
        ).isFalse()
    }

    @Test
    fun `does not report ready stall when ready stall recovery is disabled`() {
        val detector = detector()
        detector.reset()
        nowMs = 1_000L
        detector.onVideoFrameRendered(currentPositionMs = 500L)
        nowMs = 9_000L

        assertThat(
            detector.shouldReportStall(
                playbackState = PlaybackState.READY,
                isPlaying = true,
                playbackStarted = true,
                currentPositionMs = 3_000L,
                bufferedDurationMs = 5_000L,
                recoverReadyStalls = false
            )
        ).isFalse()
    }

    @Test
    fun `does not report while buffering paused or empty buffered`() {
        val detector = detector()
        detector.reset()
        nowMs = 1_000L
        detector.onVideoFrameRendered(currentPositionMs = 500L)
        nowMs = 9_000L

        assertThat(detector.shouldReportStall(PlaybackState.BUFFERING, true, true, 4_000L, 5_000L)).isFalse()
        assertThat(detector.shouldReportStall(PlaybackState.READY, false, true, 4_000L, 5_000L)).isFalse()
        assertThat(detector.shouldReportStall(PlaybackState.READY, true, false, 4_000L, 5_000L)).isFalse()
        assertThat(detector.shouldReportStall(PlaybackState.READY, true, true, 4_000L, 0L)).isFalse()
    }

    @Test
    fun `reports live buffering recovery after prior playback started without new frame`() {
        val detector = detector()
        detector.reset()
        nowMs = 9_000L

        assertThat(
            detector.shouldReportStall(
                playbackState = PlaybackState.BUFFERING,
                isPlaying = false,
                playWhenReady = true,
                playbackStarted = true,
                currentPositionMs = 0L,
                bufferedDurationMs = 0L,
                recoverBufferingStalls = true
            )
        ).isTrue()
    }

    @Test
    fun `does not report live buffering recovery before grace period`() {
        val detector = detector()
        detector.reset()
        nowMs = 3_000L

        assertThat(
            detector.shouldReportStall(
                playbackState = PlaybackState.BUFFERING,
                isPlaying = false,
                playWhenReady = true,
                playbackStarted = true,
                currentPositionMs = 0L,
                bufferedDurationMs = 0L,
                recoverBufferingStalls = true
            )
        ).isFalse()
    }

    @Test
    fun `does not report live buffering recovery before buffering threshold`() {
        val detector = VideoStallDetector(
            nowMs = { nowMs },
            initialGraceMs = 5_000L,
            stallThresholdMs = 4_000L,
            bufferingStallThresholdMs = 10_000L,
            minPositionAdvanceMs = 1_000L
        )
        detector.reset()
        nowMs = 9_000L

        assertThat(
            detector.shouldReportStall(
                playbackState = PlaybackState.BUFFERING,
                isPlaying = false,
                playWhenReady = true,
                playbackStarted = true,
                currentPositionMs = 0L,
                bufferedDurationMs = 0L,
                recoverBufferingStalls = true
            )
        ).isFalse()

        nowMs = 10_000L
        assertThat(
            detector.shouldReportStall(
                playbackState = PlaybackState.BUFFERING,
                isPlaying = false,
                playWhenReady = true,
                playbackStarted = true,
                currentPositionMs = 0L,
                bufferedDurationMs = 0L,
                recoverBufferingStalls = true
            )
        ).isTrue()
    }

    @Test
    fun `default live buffering recovery reports after buffering threshold`() {
        val detector = VideoStallDetector(nowMs = { nowMs })
        detector.reset()
        nowMs = 1_000L
        detector.onVideoFrameRendered(currentPositionMs = 500L)
        nowMs = 9_000L

        assertThat(
            detector.shouldReportStall(
                playbackState = PlaybackState.BUFFERING,
                isPlaying = false,
                playWhenReady = true,
                playbackStarted = true,
                currentPositionMs = 500L,
                bufferedDurationMs = 0L,
                recoverBufferingStalls = true
            )
        ).isTrue()
    }

    @Test
    fun `reports when playback is requested but exoplayer stops isPlaying updates`() {
        val detector = detector()
        detector.reset()
        nowMs = 1_000L
        detector.onVideoFrameRendered(currentPositionMs = 500L)
        nowMs = 9_000L

        assertThat(
            detector.shouldReportStall(
                playbackState = PlaybackState.READY,
                isPlaying = false,
                playWhenReady = true,
                playbackStarted = true,
                currentPositionMs = 500L,
                bufferedDurationMs = 5_000L
            )
        ).isTrue()
    }

    @Test
    fun `reports requested playback stall even when live buffer is empty`() {
        val detector = detector()
        detector.reset()
        nowMs = 1_000L
        detector.onVideoFrameRendered(currentPositionMs = 500L)
        nowMs = 9_000L

        assertThat(
            detector.shouldReportStall(
                playbackState = PlaybackState.READY,
                isPlaying = false,
                playWhenReady = true,
                playbackStarted = true,
                currentPositionMs = 500L,
                bufferedDurationMs = 0L
            )
        ).isTrue()
    }

    @Test
    fun `does not report position advancing ready stall when that recovery is disabled`() {
        val detector = detector()
        detector.reset()
        nowMs = 1_000L
        detector.onVideoFrameRendered(currentPositionMs = 500L)
        nowMs = 9_000L

        assertThat(
            detector.shouldReportStall(
                playbackState = PlaybackState.READY,
                isPlaying = true,
                playWhenReady = true,
                playbackStarted = true,
                currentPositionMs = 3_000L,
                bufferedDurationMs = 5_000L,
                recoverPositionAdvancingReadyStalls = false
            )
        ).isFalse()
    }

    @Test
    fun `reports requested playback stall when position advancing recovery is disabled`() {
        val detector = detector()
        detector.reset()
        nowMs = 1_000L
        detector.onVideoFrameRendered(currentPositionMs = 500L)
        nowMs = 9_000L

        assertThat(
            detector.shouldReportStall(
                playbackState = PlaybackState.READY,
                isPlaying = false,
                playWhenReady = true,
                playbackStarted = true,
                currentPositionMs = 500L,
                bufferedDurationMs = 5_000L,
                recoverPositionAdvancingReadyStalls = false
            )
        ).isTrue()
    }

    @Test
    fun `reports live ready stall when frames stop and live position is not useful`() {
        val detector = detector()
        detector.reset()
        nowMs = 1_000L
        detector.onVideoFrameRendered(currentPositionMs = 0L)
        nowMs = 9_000L

        assertThat(
            detector.shouldReportStall(
                playbackState = PlaybackState.READY,
                isPlaying = true,
                playWhenReady = true,
                playbackStarted = true,
                currentPositionMs = 0L,
                bufferedDurationMs = 0L,
                recoverPositionAdvancingReadyStalls = false,
                recoverFrameSilentReadyStalls = true
            )
        ).isTrue()
    }

    @Test
    fun `does not report when playback is not requested`() {
        val detector = detector()
        detector.reset()
        nowMs = 1_000L
        detector.onVideoFrameRendered(currentPositionMs = 500L)
        nowMs = 9_000L

        assertThat(
            detector.shouldReportStall(
                playbackState = PlaybackState.READY,
                isPlaying = false,
                playWhenReady = false,
                playbackStarted = true,
                currentPositionMs = 500L,
                bufferedDurationMs = 5_000L
            )
        ).isFalse()
    }

    @Test
    fun `new rendered frame clears stalled state`() {
        val detector = detector()
        detector.reset()
        nowMs = 1_000L
        detector.onVideoFrameRendered(currentPositionMs = 500L)
        nowMs = 9_000L
        assertThat(detector.shouldReportStall(PlaybackState.READY, true, true, 3_000L, 5_000L)).isTrue()

        nowMs = 9_100L
        detector.onVideoFrameRendered(currentPositionMs = 3_000L)
        nowMs = 15_000L
        assertThat(detector.shouldReportStall(PlaybackState.READY, true, true, 6_000L, 5_000L)).isTrue()
    }

    @Test
    fun `does not report before playback advances beyond first frame`() {
        val detector = detector()
        detector.reset()
        nowMs = 1_000L
        detector.onVideoFrameRendered(currentPositionMs = 2_500L)
        nowMs = 9_000L

        assertThat(
            detector.shouldReportStall(
                playbackState = PlaybackState.READY,
                isPlaying = true,
                playbackStarted = true,
                currentPositionMs = 3_000L,
                bufferedDurationMs = 5_000L
            )
        ).isFalse()
    }

    private fun detector() = VideoStallDetector(
        nowMs = { nowMs },
        initialGraceMs = 5_000L,
        stallThresholdMs = 4_000L,
        bufferingStallThresholdMs = 4_000L,
        minPositionAdvanceMs = 1_000L
    )
}
