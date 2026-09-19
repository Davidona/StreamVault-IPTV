package com.streamvault.feature.playback.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlayerPlaybackPreferenceActionsTest {

    @Test
    fun `buildThumbnailPreloadPositions bounds preload near current position`() {
        val positions = buildThumbnailPreloadPositions(
            durationMs = 7 * 60 * 1000L,
            currentPositionMs = 95_000L,
            bucketMs = 10_000L,
            frameBudget = 6
        )

        assertThat(positions).containsExactly(90_000L, 100_000L, 80_000L, 110_000L, 70_000L, 120_000L).inOrder()
    }

    @Test
    fun `buildThumbnailPreloadPositions caps at media bounds`() {
        val positions = buildThumbnailPreloadPositions(
            durationMs = 25_000L,
            currentPositionMs = 0L,
            bucketMs = 10_000L,
            frameBudget = 6
        )

        assertThat(positions).containsExactly(0L, 10_000L, 20_000L).inOrder()
    }

    @Test
    fun `shouldStartThumbnailPreload rejects completed and in flight keys`() {
        assertThat(
            shouldStartThumbnailPreload(
                preloadKey = "media-a",
                lastCompletedPreloadKey = "media-a",
                inFlightPreloadKey = null
            )
        ).isFalse()

        assertThat(
            shouldStartThumbnailPreload(
                preloadKey = "media-a",
                lastCompletedPreloadKey = null,
                inFlightPreloadKey = "media-a"
            )
        ).isFalse()

        assertThat(
            shouldStartThumbnailPreload(
                preloadKey = "media-b",
                lastCompletedPreloadKey = "media-a",
                inFlightPreloadKey = null
            )
        ).isTrue()
    }
}
