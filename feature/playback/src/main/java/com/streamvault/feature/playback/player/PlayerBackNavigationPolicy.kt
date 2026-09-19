package com.streamvault.feature.playback.player

/**
 * The modal priority used by the player Back key and system Back handler.
 *
 * This is deliberately platform-free so the priority can be tested without
 * composing the player or constructing a PlayerViewModel.
 */
internal enum class PlayerBackAction {
    CLEAR_NUMERIC_CHANNEL_INPUT,
    CANCEL_AUTO_PLAY,
    DISMISS_PLAYER_NOTICE,
    CLOSE_PROGRAM_HISTORY,
    CLOSE_SPLIT_DIALOG,
    CLOSE_EPISODE_PICKER,
    CLOSE_CHAPTER_SELECTION,
    CLOSE_PLAYBACK_SETTINGS,
    CLOSE_SPEED_SELECTION,
    CLOSE_AUDIO_VIDEO_OFFSET_DIALOG,
    CLOSE_STOP_PLAYBACK_TIMER,
    CLOSE_IDLE_STANDBY_TIMER,
    CLOSE_VARIANT_SELECTION,
    CLOSE_TRACK_SELECTION,
    TOGGLE_DIAGNOSTICS,
    CLOSE_CHANNEL_INFO,
    CLOSE_LIVE_OVERLAYS,
    TOGGLE_CONTROLS,
    NAVIGATE_BACK
}

internal data class PlayerBackNavigationState(
    val hasPendingNumericChannelInput: Boolean = false,
    val hasAutoPlayCountdown: Boolean = false,
    val hasPlayerNotice: Boolean = false,
    val showProgramHistory: Boolean = false,
    val showSplitDialog: Boolean = false,
    val showEpisodePicker: Boolean = false,
    val showChapterSelection: Boolean = false,
    val showPlaybackSettings: Boolean = false,
    val showSpeedSelection: Boolean = false,
    val showAudioVideoOffsetDialog: Boolean = false,
    val showStopPlaybackTimerDialog: Boolean = false,
    val showIdleStandbyTimerDialog: Boolean = false,
    val hasTrackSelection: Boolean = false,
    val showVariantSelection: Boolean = false,
    val showDiagnostics: Boolean = false,
    val showChannelInfoOverlay: Boolean = false,
    val showChannelListOverlay: Boolean = false,
    val showCategoryListOverlay: Boolean = false,
    val showEpgOverlay: Boolean = false,
    val showControls: Boolean = false
)

internal fun playerBackAction(state: PlayerBackNavigationState): PlayerBackAction = when {
    state.hasPendingNumericChannelInput -> PlayerBackAction.CLEAR_NUMERIC_CHANNEL_INPUT
    state.hasAutoPlayCountdown -> PlayerBackAction.CANCEL_AUTO_PLAY
    state.hasPlayerNotice -> PlayerBackAction.DISMISS_PLAYER_NOTICE
    state.showProgramHistory -> PlayerBackAction.CLOSE_PROGRAM_HISTORY
    state.showSplitDialog -> PlayerBackAction.CLOSE_SPLIT_DIALOG
    state.showEpisodePicker -> PlayerBackAction.CLOSE_EPISODE_PICKER
    state.showChapterSelection -> PlayerBackAction.CLOSE_CHAPTER_SELECTION
    state.showPlaybackSettings -> PlayerBackAction.CLOSE_PLAYBACK_SETTINGS
    state.showSpeedSelection -> PlayerBackAction.CLOSE_SPEED_SELECTION
    state.showAudioVideoOffsetDialog -> PlayerBackAction.CLOSE_AUDIO_VIDEO_OFFSET_DIALOG
    state.showStopPlaybackTimerDialog -> PlayerBackAction.CLOSE_STOP_PLAYBACK_TIMER
    state.showIdleStandbyTimerDialog -> PlayerBackAction.CLOSE_IDLE_STANDBY_TIMER
    state.showVariantSelection -> PlayerBackAction.CLOSE_VARIANT_SELECTION
    state.hasTrackSelection -> PlayerBackAction.CLOSE_TRACK_SELECTION
    state.showDiagnostics -> PlayerBackAction.TOGGLE_DIAGNOSTICS
    state.showChannelInfoOverlay -> PlayerBackAction.CLOSE_CHANNEL_INFO
    state.showChannelListOverlay || state.showCategoryListOverlay || state.showEpgOverlay ->
        PlayerBackAction.CLOSE_LIVE_OVERLAYS
    state.showControls -> PlayerBackAction.TOGGLE_CONTROLS
    else -> PlayerBackAction.NAVIGATE_BACK
}

/** Builds the Back decision from current state when the event is handled. */
internal fun playerBackActionAtEvent(
    stateProvider: () -> PlayerBackNavigationState
): PlayerBackAction = playerBackAction(stateProvider())
