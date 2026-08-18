package com.streamvault.app.ui.screens.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlayerInputPolicyTest {

    @Test
    fun `preview zaps live channels and reports channel info interaction`() {
        val decision = playerPreviewInputDecision(
            state = liveState(showChannelInfoOverlay = true),
            key = PlayerInputKey.DpadUp
        )

        assertThat(decision.action).isEqualTo(PlayerInputAction.PlayNext)
        assertThat(decision.notifyLiveOverlayInteraction).isTrue()
    }

    @Test
    fun `preview blocks zapping while a modal is visible`() {
        val decision = playerPreviewInputDecision(
            state = liveState(showSpeedSelection = true),
            key = PlayerInputKey.DpadUp
        )

        assertThat(decision).isEqualTo(PlayerInputDecision(PlayerInputAction.Pass))
    }

    @Test
    fun `preview ignores channel zapping for non-live content`() {
        val decision = playerPreviewInputDecision(
            state = liveState(contentType = "MOVIE"),
            key = PlayerInputKey.ChannelUp
        )

        assertThat(decision.action).isEqualTo(PlayerInputAction.Pass)
    }

    @Test
    fun `audio video offset Back dismisses preview and directional keys pass through`() {
        val state = liveState(showAudioVideoOffsetDialog = true)

        assertThat(playerInputDecision(state, PlayerInputKey.Back).action)
            .isEqualTo(PlayerInputAction.DismissAudioVideoOffset)
        assertThat(playerInputDecision(state, PlayerInputKey.DpadRight).action)
            .isEqualTo(PlayerInputAction.Pass)
    }

    @Test
    fun `speed modal consumes unrelated keys but passes navigation`() {
        val state = liveState(showSpeedSelection = true)

        assertThat(playerInputDecision(state, PlayerInputKey.Back).action)
            .isEqualTo(PlayerInputAction.CloseSpeedSelection)
        assertThat(playerInputDecision(state, PlayerInputKey.Info).action)
            .isEqualTo(PlayerInputAction.Consume)
        assertThat(playerInputDecision(state, PlayerInputKey.DpadCenter).action)
            .isEqualTo(PlayerInputAction.Pass)
    }

    @Test
    fun `center commits pending numeric live input before opening channel info`() {
        val decision = playerInputDecision(
            liveState(hasPendingNumericChannelInput = true),
            PlayerInputKey.DpadCenter
        )

        assertThat(decision.action).isEqualTo(PlayerInputAction.CommitNumericChannelInput)
    }

    @Test
    fun `event-time decision reads a pending numeric input that changed since the prior event`() {
        var hasPendingNumericInput = false
        val stateProvider = {
            liveState(hasPendingNumericChannelInput = hasPendingNumericInput)
        }

        assertThat(
            playerInputDecisionAtEvent(stateProvider, PlayerInputKey.DpadCenter).action
        ).isEqualTo(PlayerInputAction.OpenChannelInfo)

        hasPendingNumericInput = true

        assertThat(
            playerInputDecisionAtEvent(stateProvider, PlayerInputKey.DpadCenter).action
        ).isEqualTo(PlayerInputAction.CommitNumericChannelInput)
    }

    @Test
    fun `center opens or closes live channel info`() {
        assertThat(playerInputDecision(liveState(), PlayerInputKey.DpadCenter).action)
            .isEqualTo(PlayerInputAction.OpenChannelInfo)
        assertThat(
            playerInputDecision(
                liveState(showChannelInfoOverlay = true),
                PlayerInputKey.Enter
            ).action
        ).isEqualTo(PlayerInputAction.CloseChannelInfo)
    }

    @Test
    fun `numpad enter remains unhandled like the original root key branch`() {
        assertThat(playerInputDecision(liveState(), PlayerInputKey.NumpadEnter).action)
            .isEqualTo(PlayerInputAction.Pass)
    }

    @Test
    fun `channel info overlay blocks up but allows down channel navigation without subpanel`() {
        val state = liveState(showChannelInfoOverlay = true)

        assertThat(playerInputDecision(state, PlayerInputKey.DpadUp).action)
            .isEqualTo(PlayerInputAction.Pass)
        assertThat(playerInputDecision(state, PlayerInputKey.DpadDown).action)
            .isEqualTo(PlayerInputAction.PlayPrevious)
    }

    @Test
    fun `left and right map channel list and EPG according to layout direction`() {
        assertThat(playerInputDecision(liveState(), PlayerInputKey.DpadLeft).action)
            .isEqualTo(PlayerInputAction.OpenChannelList)
        assertThat(playerInputDecision(liveState(), PlayerInputKey.DpadRight).action)
            .isEqualTo(PlayerInputAction.OpenEpg)
        assertThat(playerInputDecision(liveState(isRtl = true), PlayerInputKey.DpadLeft).action)
            .isEqualTo(PlayerInputAction.OpenEpg)
        assertThat(playerInputDecision(liveState(isRtl = true), PlayerInputKey.DpadRight).action)
            .isEqualTo(PlayerInputAction.OpenChannelList)
    }

    @Test
    fun `left opens categories from channel list and right keeps the existing category behavior`() {
        assertThat(
            playerInputDecision(
                liveState(showChannelListOverlay = true),
                PlayerInputKey.DpadLeft
            ).action
        ).isEqualTo(PlayerInputAction.OpenCategoryList)
        assertThat(
            playerInputDecision(
                liveState(showCategoryListOverlay = true),
                PlayerInputKey.DpadRight
            ).action
        ).isEqualTo(PlayerInputAction.OpenEpg)
    }

    @Test
    fun `left and right seek when non-live overlays are absent`() {
        val state = liveState(contentType = "MOVIE")

        assertThat(playerInputDecision(state, PlayerInputKey.DpadLeft).action)
            .isEqualTo(PlayerInputAction.SeekBackward)
        assertThat(playerInputDecision(state, PlayerInputKey.DpadRight).action)
            .isEqualTo(PlayerInputAction.SeekForward)
        assertThat(playerInputDecision(state.copy(isRtl = true), PlayerInputKey.DpadLeft).action)
            .isEqualTo(PlayerInputAction.SeekForward)
    }

    @Test
    fun `up opens episode picker for eligible non-live content`() {
        val decision = playerInputDecision(
            liveState(contentType = "SERIES_EPISODE", canOpenEpisodePicker = true),
            PlayerInputKey.DpadUp
        )

        assertThat(decision.action).isEqualTo(PlayerInputAction.ShowEpisodePicker)
    }

    @Test
    fun `media guide info menu previous and mute map to typed actions`() {
        assertThat(playerInputDecision(liveState(), PlayerInputKey.MediaPlayPause).action)
            .isEqualTo(PlayerInputAction.TogglePlayback)
        assertThat(playerInputDecision(liveState(), PlayerInputKey.Guide).action)
            .isEqualTo(PlayerInputAction.OpenEpg)
        assertThat(playerInputDecision(liveState(), PlayerInputKey.Info).action)
            .isEqualTo(PlayerInputAction.OpenChannelInfo)
        assertThat(playerInputDecision(liveState(), PlayerInputKey.Menu).action)
            .isEqualTo(PlayerInputAction.ToggleControls)
        assertThat(playerInputDecision(liveState(), PlayerInputKey.MediaPrevious).action)
            .isEqualTo(PlayerInputAction.ZapToLastChannel)
        assertThat(playerInputDecision(liveState(), PlayerInputKey.Mute(isRepeat = false)).action)
            .isEqualTo(PlayerInputAction.ToggleMute)
        assertThat(playerInputDecision(liveState(), PlayerInputKey.Mute(isRepeat = true)).action)
            .isEqualTo(PlayerInputAction.Consume)
    }

    @Test
    fun `numeric digits become actions only for live content`() {
        assertThat(playerInputDecision(liveState(), PlayerInputKey.Digit(7)).action)
            .isEqualTo(PlayerInputAction.InputNumericDigit(7))
        assertThat(
            playerInputDecision(liveState(contentType = "MOVIE"), PlayerInputKey.Digit(7)).action
        ).isEqualTo(PlayerInputAction.Pass)
    }

    private fun liveState(
        contentType: String = "LIVE",
        isCatchUpPlayback: Boolean = false,
        isRtl: Boolean = false,
        nextEpisodeCountdownVisible: Boolean = false,
        showChannelListOverlay: Boolean = false,
        showCategoryListOverlay: Boolean = false,
        showEpgOverlay: Boolean = false,
        showChannelInfoOverlay: Boolean = false,
        channelInfoSubPanelOpen: Boolean = false,
        showDiagnostics: Boolean = false,
        showTrackSelection: Boolean = false,
        showVariantSelection: Boolean = false,
        showSpeedSelection: Boolean = false,
        showAudioVideoOffsetDialog: Boolean = false,
        showStopPlaybackTimerDialog: Boolean = false,
        showIdleStandbyTimerDialog: Boolean = false,
        showProgramHistory: Boolean = false,
        showSplitDialog: Boolean = false,
        showEpisodePicker: Boolean = false,
        showControls: Boolean = false,
        hasPendingNumericChannelInput: Boolean = false,
        canOpenEpisodePicker: Boolean = false
    ) = PlayerInputState(
        contentType = contentType,
        isCatchUpPlayback = isCatchUpPlayback,
        isRtl = isRtl,
        nextEpisodeCountdownVisible = nextEpisodeCountdownVisible,
        showChannelListOverlay = showChannelListOverlay,
        showCategoryListOverlay = showCategoryListOverlay,
        showEpgOverlay = showEpgOverlay,
        showChannelInfoOverlay = showChannelInfoOverlay,
        channelInfoSubPanelOpen = channelInfoSubPanelOpen,
        showDiagnostics = showDiagnostics,
        showTrackSelection = showTrackSelection,
        showVariantSelection = showVariantSelection,
        showSpeedSelection = showSpeedSelection,
        showAudioVideoOffsetDialog = showAudioVideoOffsetDialog,
        showStopPlaybackTimerDialog = showStopPlaybackTimerDialog,
        showIdleStandbyTimerDialog = showIdleStandbyTimerDialog,
        showProgramHistory = showProgramHistory,
        showSplitDialog = showSplitDialog,
        showEpisodePicker = showEpisodePicker,
        showControls = showControls,
        hasPendingNumericChannelInput = hasPendingNumericChannelInput,
        canOpenEpisodePicker = canOpenEpisodePicker
    )
}
