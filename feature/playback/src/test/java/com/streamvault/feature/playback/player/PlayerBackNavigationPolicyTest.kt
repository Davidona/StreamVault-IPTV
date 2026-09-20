package com.streamvault.feature.playback.player

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
    fun `event-time Back decision sees numeric input added after the previous composition`() {
        var hasPendingNumericInput = false
        val stateProvider = {
            PlayerBackNavigationState(hasPendingNumericChannelInput = hasPendingNumericInput)
        }

        assertThat(playerBackActionAtEvent(stateProvider))
            .isEqualTo(PlayerBackAction.NAVIGATE_BACK)

        hasPendingNumericInput = true

        assertThat(playerBackActionAtEvent(stateProvider))
            .isEqualTo(PlayerBackAction.CLEAR_NUMERIC_CHANNEL_INPUT)
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
    fun `chapter sheet closes before controls`() {
        val state = PlayerBackNavigationState(
            showChapterSelection = true,
            showControls = true
        )

        assertThat(playerBackAction(state)).isEqualTo(PlayerBackAction.CLOSE_CHAPTER_SELECTION)
    }

    @Test
    fun `playback settings close before controls`() {
        val state = PlayerBackNavigationState(
            showPlaybackSettings = true,
            showControls = true
        )

        assertThat(playerBackAction(state)).isEqualTo(PlayerBackAction.CLOSE_PLAYBACK_SETTINGS)
    }

    @Test
    fun `empty player state navigates back`() {
        assertThat(playerBackAction(PlayerBackNavigationState()))
            .isEqualTo(PlayerBackAction.NAVIGATE_BACK)
    }

    @Test
    fun `close playback confirmation cancels before every other layer`() {
        val state = PlayerBackNavigationState(
            showClosePlaybackConfirmation = true,
            hasPendingNumericChannelInput = true,
            showChannelInfoOverlay = true,
            showControls = true
        )

        assertThat(playerBackAction(state)).isEqualTo(PlayerBackAction.CANCEL_CLOSE_PLAYBACK)
    }
}
