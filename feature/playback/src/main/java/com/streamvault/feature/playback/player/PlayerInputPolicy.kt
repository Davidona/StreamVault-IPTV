package com.streamvault.feature.playback.player

internal sealed interface PlayerInputKey {
    data object DpadCenter : PlayerInputKey
    data object Enter : PlayerInputKey
    data object NumpadEnter : PlayerInputKey
    data object DpadLeft : PlayerInputKey
    data object DpadRight : PlayerInputKey
    data object DpadUp : PlayerInputKey
    data object DpadDown : PlayerInputKey
    data object DpadUpRight : PlayerInputKey
    data object DpadDownLeft : PlayerInputKey
    data object Back : PlayerInputKey
    data object MediaPlayPause : PlayerInputKey
    data class Mute(val isRepeat: Boolean) : PlayerInputKey
    data object ChannelUp : PlayerInputKey
    data object ChannelDown : PlayerInputKey
    data object MediaPrevious : PlayerInputKey
    data object Guide : PlayerInputKey
    data object Info : PlayerInputKey
    data object Menu : PlayerInputKey
    data class Digit(val value: Int) : PlayerInputKey
    data object Other : PlayerInputKey
}

internal data class PlayerInputState(
    val contentType: String,
    val isCatchUpPlayback: Boolean,
    val isRtl: Boolean,
    val nextEpisodeCountdownVisible: Boolean = false,
    val showChannelListOverlay: Boolean = false,
    val showCategoryListOverlay: Boolean = false,
    val showEpgOverlay: Boolean = false,
    val showChannelInfoOverlay: Boolean = false,
    val channelInfoSubPanelOpen: Boolean = false,
    val showDiagnostics: Boolean = false,
    val showTrackSelection: Boolean = false,
    val showVariantSelection: Boolean = false,
    val showSpeedSelection: Boolean = false,
    val showAudioVideoOffsetDialog: Boolean = false,
    val showStopPlaybackTimerDialog: Boolean = false,
    val showIdleStandbyTimerDialog: Boolean = false,
    val showProgramHistory: Boolean = false,
    val showSplitDialog: Boolean = false,
    val showEpisodePicker: Boolean = false,
    val showControls: Boolean = false,
    val hasPendingNumericChannelInput: Boolean = false,
    val canOpenEpisodePicker: Boolean = false
)

/**
 * Builds the input snapshot when an event is handled instead of reusing a
 * snapshot captured during composition. This matters for ViewModel-backed
 * buffers, whose value can change before Compose has had a chance to
 * recompose the screen.
 */
internal fun playerInputDecisionAtEvent(
    stateProvider: () -> PlayerInputState,
    key: PlayerInputKey
): PlayerInputDecision = playerInputDecision(stateProvider(), key)

internal sealed interface PlayerInputAction {
    data object Pass : PlayerInputAction
    data object Consume : PlayerInputAction
    data object PlayNext : PlayerInputAction
    data object PlayPrevious : PlayerInputAction
    data object CancelAutoPlay : PlayerInputAction
    data object DismissAudioVideoOffset : PlayerInputAction
    data object CloseSpeedSelection : PlayerInputAction
    data object CloseVariantSelection : PlayerInputAction
    data object CloseStopIdleTimersAndTrackSelection : PlayerInputAction
    data object CommitNumericChannelInput : PlayerInputAction
    data object OpenChannelInfo : PlayerInputAction
    data object CloseChannelInfo : PlayerInputAction
    data object OpenChannelList : PlayerInputAction
    data object OpenCategoryList : PlayerInputAction
    data object OpenEpg : PlayerInputAction
    data object SeekBackward : PlayerInputAction
    data object SeekForward : PlayerInputAction
    data object ToggleControls : PlayerInputAction
    data object TogglePlayback : PlayerInputAction
    data object ToggleMute : PlayerInputAction
    data object ZapToLastChannel : PlayerInputAction
    data object ShowEpisodePicker : PlayerInputAction
    data class InputNumericDigit(val digit: Int) : PlayerInputAction
    data object DelegateBack : PlayerInputAction
}

internal data class PlayerInputDecision(
    val action: PlayerInputAction,
    val notifyLiveOverlayInteraction: Boolean = false
)

internal fun playerPreviewInputDecision(
    state: PlayerInputState,
    key: PlayerInputKey
): PlayerInputDecision {
    if (state.nextEpisodeCountdownVisible || state.contentType != "LIVE") {
        return PlayerInputDecision(PlayerInputAction.Pass)
    }
    if (
        state.showChannelListOverlay ||
        state.showCategoryListOverlay ||
        state.showEpgOverlay ||
        state.showDiagnostics ||
        state.showTrackSelection ||
        state.showVariantSelection ||
        state.showSpeedSelection ||
        state.showAudioVideoOffsetDialog ||
        state.showStopPlaybackTimerDialog ||
        state.showIdleStandbyTimerDialog ||
        state.showProgramHistory ||
        state.showSplitDialog ||
        state.showEpisodePicker ||
        state.showControls ||
        (state.showChannelInfoOverlay && state.channelInfoSubPanelOpen)
    ) {
        return PlayerInputDecision(PlayerInputAction.Pass)
    }

    val notifyInteraction = state.showChannelInfoOverlay || state.showDiagnostics
    return when (key) {
        PlayerInputKey.DpadUp,
        PlayerInputKey.ChannelUp,
        PlayerInputKey.DpadUpRight -> PlayerInputDecision(PlayerInputAction.PlayNext, notifyInteraction)
        PlayerInputKey.DpadDown,
        PlayerInputKey.ChannelDown,
        PlayerInputKey.DpadDownLeft -> PlayerInputDecision(PlayerInputAction.PlayPrevious, notifyInteraction)
        else -> PlayerInputDecision(PlayerInputAction.Pass)
    }
}

internal fun playerInputDecision(
    state: PlayerInputState,
    key: PlayerInputKey
): PlayerInputDecision {
    if (state.nextEpisodeCountdownVisible) {
        return if (key == PlayerInputKey.Back) {
            PlayerInputDecision(PlayerInputAction.CancelAutoPlay)
        } else {
            PlayerInputDecision(PlayerInputAction.Consume)
        }
    }

    when {
        state.showAudioVideoOffsetDialog ->
            return modalInputDecision(PlayerInputAction.DismissAudioVideoOffset, key)
        state.showSpeedSelection ->
            return modalInputDecision(PlayerInputAction.CloseSpeedSelection, key)
        state.showVariantSelection ->
            return modalInputDecision(PlayerInputAction.CloseVariantSelection, key)
        state.showTrackSelection || state.showStopPlaybackTimerDialog || state.showIdleStandbyTimerDialog ->
            return modalInputDecision(PlayerInputAction.CloseStopIdleTimersAndTrackSelection, key)
    }

    return when (key) {
        PlayerInputKey.DpadCenter,
        PlayerInputKey.Enter -> {
            val notifyInteraction = state.showChannelListOverlay ||
                state.showEpgOverlay ||
                state.showChannelInfoOverlay ||
                state.showDiagnostics
            val action = when {
                state.contentType == "LIVE" && !state.isCatchUpPlayback && state.hasPendingNumericChannelInput ->
                    PlayerInputAction.CommitNumericChannelInput
                state.contentType == "LIVE" && !state.isCatchUpPlayback ->
                    if (state.showChannelInfoOverlay) PlayerInputAction.CloseChannelInfo
                    else PlayerInputAction.OpenChannelInfo
                state.showControls -> PlayerInputAction.Pass
                else -> PlayerInputAction.ToggleControls
            }
            PlayerInputDecision(action, notifyInteraction)
        }

        PlayerInputKey.DpadLeft -> directionalInputDecision(state, isLeft = true)
        PlayerInputKey.DpadRight -> directionalInputDecision(state, isLeft = false)
        PlayerInputKey.DpadUp -> verticalInputDecision(state, isUp = true)
        PlayerInputKey.DpadDown -> verticalInputDecision(state, isUp = false)
        PlayerInputKey.Back -> PlayerInputDecision(PlayerInputAction.DelegateBack)
        PlayerInputKey.MediaPlayPause -> PlayerInputDecision(PlayerInputAction.TogglePlayback)
        is PlayerInputKey.Mute -> PlayerInputDecision(
            if (key.isRepeat) PlayerInputAction.Consume else PlayerInputAction.ToggleMute
        )
        PlayerInputKey.ChannelUp,
        PlayerInputKey.DpadUpRight -> channelStepDecision(state, isUp = true)
        PlayerInputKey.ChannelDown,
        PlayerInputKey.DpadDownLeft -> channelStepDecision(state, isUp = false)
        PlayerInputKey.MediaPrevious -> if (state.channelInfoSubPanelOpen) {
            PlayerInputDecision(PlayerInputAction.Consume)
        } else if (state.contentType == "LIVE") {
            PlayerInputDecision(PlayerInputAction.ZapToLastChannel)
        } else {
            PlayerInputDecision(PlayerInputAction.Pass)
        }
        PlayerInputKey.Guide -> if (state.contentType == "LIVE") {
            PlayerInputDecision(PlayerInputAction.OpenEpg)
        } else {
            PlayerInputDecision(PlayerInputAction.Pass)
        }
        PlayerInputKey.Info -> PlayerInputDecision(
            action = if (state.contentType == "LIVE") {
                if (state.showChannelInfoOverlay) PlayerInputAction.CloseChannelInfo
                else PlayerInputAction.OpenChannelInfo
            } else {
                PlayerInputAction.ToggleControls
            },
            notifyLiveOverlayInteraction = state.showChannelListOverlay ||
                state.showEpgOverlay ||
                state.showChannelInfoOverlay ||
                state.showDiagnostics
        )
        PlayerInputKey.Menu -> PlayerInputDecision(
            action = PlayerInputAction.ToggleControls,
            notifyLiveOverlayInteraction = state.showChannelListOverlay ||
                state.showEpgOverlay ||
                state.showChannelInfoOverlay ||
                state.showDiagnostics
        )
        is PlayerInputKey.Digit -> if (state.contentType == "LIVE") {
            PlayerInputDecision(PlayerInputAction.InputNumericDigit(key.value))
        } else {
            PlayerInputDecision(PlayerInputAction.Pass)
        }
        PlayerInputKey.NumpadEnter,
        PlayerInputKey.Other -> PlayerInputDecision(PlayerInputAction.Pass)
    }
}

private fun modalInputDecision(
    backAction: PlayerInputAction,
    key: PlayerInputKey
): PlayerInputDecision = when {
    key == PlayerInputKey.Back -> PlayerInputDecision(backAction)
    key.isDialogNavigationKey() -> PlayerInputDecision(PlayerInputAction.Pass)
    else -> PlayerInputDecision(PlayerInputAction.Consume)
}

private fun PlayerInputKey.isDialogNavigationKey(): Boolean = when (this) {
    PlayerInputKey.DpadUp,
    PlayerInputKey.DpadDown,
    PlayerInputKey.DpadLeft,
    PlayerInputKey.DpadRight,
    PlayerInputKey.DpadCenter,
    PlayerInputKey.Enter,
    PlayerInputKey.NumpadEnter -> true
    else -> false
}

private fun directionalInputDecision(
    state: PlayerInputState,
    isLeft: Boolean
): PlayerInputDecision {
    val notifyInteraction = if (isLeft) {
        state.showChannelListOverlay ||
            state.showCategoryListOverlay ||
            state.showEpgOverlay ||
            state.showChannelInfoOverlay ||
            state.showDiagnostics
    } else {
        state.showChannelListOverlay ||
            state.showEpgOverlay ||
            state.showChannelInfoOverlay ||
            state.showDiagnostics
    }

    if (state.showControls) {
        return PlayerInputDecision(PlayerInputAction.Pass, notifyInteraction)
    }

    if (isLeft && state.showChannelListOverlay && state.contentType == "LIVE" && !state.isCatchUpPlayback) {
        return PlayerInputDecision(PlayerInputAction.OpenCategoryList, notifyInteraction)
    }
    val liveWithoutOverlay = state.contentType == "LIVE" &&
        !state.isCatchUpPlayback &&
        !state.showChannelListOverlay &&
        !state.showEpgOverlay &&
        !state.showChannelInfoOverlay &&
        (isLeft.not() || !state.showCategoryListOverlay)
    if (liveWithoutOverlay) {
        val openChannelList = if (isLeft) !state.isRtl else state.isRtl
        return PlayerInputDecision(
            if (openChannelList) PlayerInputAction.OpenChannelList else PlayerInputAction.OpenEpg,
            notifyInteraction
        )
    }

    val noSeekBlockingOverlay = if (isLeft) {
        !state.showChannelListOverlay &&
            !state.showCategoryListOverlay &&
            !state.showEpgOverlay &&
            !state.showChannelInfoOverlay
    } else {
        !state.showChannelListOverlay &&
            !state.showEpgOverlay &&
            !state.showChannelInfoOverlay
    }
    if (!noSeekBlockingOverlay) {
        return PlayerInputDecision(PlayerInputAction.Pass, notifyInteraction)
    }

    val seekBackward = if (isLeft) !state.isRtl else state.isRtl
    return PlayerInputDecision(
        if (seekBackward) PlayerInputAction.SeekBackward else PlayerInputAction.SeekForward,
        notifyInteraction
    )
}

private fun channelStepDecision(
    state: PlayerInputState,
    isUp: Boolean
): PlayerInputDecision = when {
    state.showDiagnostics || (state.showChannelInfoOverlay && state.channelInfoSubPanelOpen) ->
        PlayerInputDecision(PlayerInputAction.Consume)
    state.contentType == "LIVE" ->
        PlayerInputDecision(if (isUp) PlayerInputAction.PlayNext else PlayerInputAction.PlayPrevious)
    else -> PlayerInputDecision(PlayerInputAction.Pass)
}

private fun verticalInputDecision(
    state: PlayerInputState,
    isUp: Boolean
): PlayerInputDecision {
    val notifyInteraction = state.showChannelListOverlay ||
        state.showCategoryListOverlay ||
        state.showEpgOverlay ||
        state.showChannelInfoOverlay ||
        state.showDiagnostics
    if (state.showChannelInfoOverlay && state.channelInfoSubPanelOpen) {
        return PlayerInputDecision(PlayerInputAction.Pass, notifyInteraction)
    }
    if (state.showControls) {
        return PlayerInputDecision(PlayerInputAction.Pass, notifyInteraction)
    }
    val blocksVerticalNavigation = state.showChannelListOverlay ||
        state.showCategoryListOverlay ||
        state.showEpgOverlay ||
        state.showDiagnostics ||
        (isUp && state.showChannelInfoOverlay)
    if (blocksVerticalNavigation) {
        return PlayerInputDecision(PlayerInputAction.Pass, notifyInteraction)
    }
    val action = if (state.contentType == "LIVE" && !state.isCatchUpPlayback) {
        if (isUp) PlayerInputAction.PlayNext else PlayerInputAction.PlayPrevious
    } else if (isUp && state.canOpenEpisodePicker) {
        PlayerInputAction.ShowEpisodePicker
    } else {
        PlayerInputAction.ToggleControls
    }
    return PlayerInputDecision(action, notifyInteraction)
}
