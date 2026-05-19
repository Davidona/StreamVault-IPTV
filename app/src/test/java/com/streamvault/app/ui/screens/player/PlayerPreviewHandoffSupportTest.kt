package com.streamvault.app.ui.screens.player

import com.google.common.truth.Truth.assertThat
import com.streamvault.player.PlaybackState
import com.streamvault.player.PlayerStats
import org.junit.Test

class PlayerPreviewHandoffSupportTest {

    @Test
    fun `ready preview with rendered frame skips fullscreen renewal`() {
        val shouldRenew = shouldRenewAdoptedPreviewOnFullscreen(
            playbackState = PlaybackState.READY,
            playerStats = PlayerStats(ttffMs = 250L)
        )

        assertThat(shouldRenew).isFalse()
    }

    @Test
    fun `ready preview without rendered frame renews fullscreen stream`() {
        val shouldRenew = shouldRenewAdoptedPreviewOnFullscreen(
            playbackState = PlaybackState.READY,
            playerStats = PlayerStats(ttffMs = 0L)
        )

        assertThat(shouldRenew).isTrue()
    }

    @Test
    fun `non ready preview renews fullscreen stream`() {
        val states = listOf(
            PlaybackState.IDLE,
            PlaybackState.BUFFERING,
            PlaybackState.ENDED,
            PlaybackState.ERROR
        )

        states.forEach { state ->
            assertThat(
                shouldRenewAdoptedPreviewOnFullscreen(
                    playbackState = state,
                    playerStats = PlayerStats(ttffMs = 250L)
                )
            ).isTrue()
        }
    }
}
