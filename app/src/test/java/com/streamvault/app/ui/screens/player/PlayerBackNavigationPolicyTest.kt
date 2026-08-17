package com.streamvault.app.ui.screens.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlayerBackNavigationPolicyTest {

    @Test
    fun `numeric input has priority over every visible layer`() {
        val state = PlayerBackNavigationState(
            hasPendingNumericChannelInput = true,
            hasAutoPlayCountdown = true,
            showControls = true
        )

        assertThat(playerBackAction(state)).isEqualTo(PlayerBackAction.CLEAR_NUMERIC_CHANNEL_INPUT)
    }

    @Test
    fun `dialogs close before live overlays`() {
        val state = PlayerBackNavigationState(
            showChannelInfoOverlay = true,
            showAudioVideoOffsetDialog = true
        )

        assertThat(playerBackAction(state)).isEqualTo(PlayerBackAction.CLOSE_AUDIO_VIDEO_OFFSET_DIALOG)
    }

    @Test
    fun `live overlays close before controls`() {
        val state = PlayerBackNavigationState(
            showChannelListOverlay = true,
            showControls = true
        )

        assertThat(playerBackAction(state)).isEqualTo(PlayerBackAction.CLOSE_LIVE_OVERLAYS)
    }

    @Test
    fun `empty player state navigates back`() {
        assertThat(playerBackAction(PlayerBackNavigationState()))
            .isEqualTo(PlayerBackAction.NAVIGATE_BACK)
    }
}
