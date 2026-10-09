package com.streamvault.player.playback

import androidx.media3.common.C
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlayerRetrySeekPositionPolicyTest {

    @Test
    fun `network retry for progressive movie preserves current position`() {
        val seekPosition = resolveRetrySeekPositionMs(
            category = PlaybackErrorCategory.NETWORK,
            resolvedStreamType = ResolvedStreamType.PROGRESSIVE,
            currentPositionMs = 1_695_105L,
            durationMs = C.TIME_UNSET,
            isCurrentMediaItemLive = false,
            playbackStarted = true
        )

        assertThat(seekPosition).isEqualTo(1_695_105L)
    }

    @Test
    fun `startup retry for progressive movie does not preserve stuck resume position before first frame`() {
        val seekPosition = resolveRetrySeekPositionMs(
            category = PlaybackErrorCategory.NETWORK,
            resolvedStreamType = ResolvedStreamType.PROGRESSIVE,
            currentPositionMs = 1_001_654L,
            durationMs = C.TIME_UNSET,
            isCurrentMediaItemLive = false,
            playbackStarted = false
        )

        assertThat(seekPosition).isNull()
    }

    @Test
    fun `network retry for finite hls movie preserves current position`() {
        val seekPosition = resolveRetrySeekPositionMs(
            category = PlaybackErrorCategory.NETWORK,
            resolvedStreamType = ResolvedStreamType.HLS,
            currentPositionMs = 1_695_105L,
            durationMs = 7_200_000L,
            isCurrentMediaItemLive = false,
            playbackStarted = true
        )

        assertThat(seekPosition).isEqualTo(1_695_105L)
    }

    @Test
    fun `network retry for live hls does not seek into the live window`() {
        val seekPosition = resolveRetrySeekPositionMs(
            category = PlaybackErrorCategory.NETWORK,
            resolvedStreamType = ResolvedStreamType.HLS,
            currentPositionMs = 1_695_105L,
            durationMs = 7_200_000L,
            isCurrentMediaItemLive = true,
            playbackStarted = true
        )

        assertThat(seekPosition).isNull()
    }

    @Test
    fun `live window retry does not preserve stale position`() {
        val seekPosition = resolveRetrySeekPositionMs(
            category = PlaybackErrorCategory.LIVE_WINDOW,
            resolvedStreamType = ResolvedStreamType.PROGRESSIVE,
            currentPositionMs = 1_695_105L,
            durationMs = 7_200_000L,
            isCurrentMediaItemLive = false,
            playbackStarted = true
        )

        assertThat(seekPosition).isNull()
    }

    @Test
    fun `ready movie playback after retry restores transient retry budget`() {
        val nextAttempt = resolveRetryAttemptAfterReady(
            currentAttempt = 1,
            playbackStarted = true
        )

        assertThat(nextAttempt).isEqualTo(0)
    }

    @Test
    fun `ready live playback after retry restores transient retry budget`() {
        val nextAttempt = resolveRetryAttemptAfterReady(
            currentAttempt = 1,
            playbackStarted = true
        )

        assertThat(nextAttempt).isEqualTo(0)
    }

    @Test
    fun `first frame after retry restores transient retry budget`() {
        val nextAttempt = resolveRetryAttemptAfterPlaybackStarted(currentAttempt = 1)

        assertThat(nextAttempt).isEqualTo(0)
    }

    @Test
    fun `whole source retry budget continues across changing error categories`() {
        assertThat(resolveNextAppSourceRetryAttempt(currentAttempt = 1)).isEqualTo(2)
    }

    @Test
    fun `first whole source retry starts at one`() {
        assertThat(resolveNextAppSourceRetryAttempt(currentAttempt = 0)).isEqualTo(1)
    }

    @Test
    fun `scheduled source retry is rejected after the source generation changes`() {
        assertThat(
            isScheduledAppSourceRetryCurrent(
                scheduledGeneration = 4L,
                currentGeneration = 5L,
                scheduledMediaId = "old-item",
                currentMediaId = "new-item",
                scheduledAttempt = 1,
                currentAttempt = 1
            )
        ).isFalse()
    }

    @Test
    fun `scheduled source retry remains valid only for its media item and attempt`() {
        assertThat(
            isScheduledAppSourceRetryCurrent(
                scheduledGeneration = 4L,
                currentGeneration = 4L,
                scheduledMediaId = "same-item",
                currentMediaId = "same-item",
                scheduledAttempt = 1,
                currentAttempt = 1
            )
        ).isTrue()
        assertThat(
            isScheduledAppSourceRetryCurrent(
                scheduledGeneration = 4L,
                currentGeneration = 4L,
                scheduledMediaId = "same-item",
                currentMediaId = "same-item",
                scheduledAttempt = 1,
                currentAttempt = 2
            )
        ).isFalse()
    }

    @Test
    fun `user seek during a pending retry replaces the captured stale position`() {
        assertThat(
            resolveScheduledRetrySeekPositionMs(
                category = PlaybackErrorCategory.NETWORK,
                plannedPositionMs = 120_000L,
                userSeekOverrideMs = 540_000L
            )
        ).isEqualTo(540_000L)
    }

    @Test
    fun `behind live window retry always uses the live default position`() {
        assertThat(
            resolveScheduledRetrySeekPositionMs(
                category = PlaybackErrorCategory.LIVE_WINDOW,
                plannedPositionMs = 120_000L,
                userSeekOverrideMs = 540_000L
            )
        ).isNull()
    }
}
