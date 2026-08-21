package com.streamvault.app.ui.screens.player

import com.streamvault.player.TrackType

/**
 * UI-owned modal state for the player screen.
 *
 * Keeping these flags in one immutable snapshot makes modal visibility a
 * separate concern from high-frequency playback state and gives input,
 * focus, auto-hide, and modal rendering one consistent source of truth.
 */
internal data class PlayerModalState(
    val trackSelection: TrackType? = null,
    val showVariantSelection: Boolean = false,
    val showSpeedSelection: Boolean = false,
    val showAudioVideoOffsetDialog: Boolean = false,
    val showStopPlaybackTimerDialog: Boolean = false,
    val showIdleStandbyTimerDialog: Boolean = false,
    val showProgramHistory: Boolean = false,
    val showSplitDialog: Boolean = false,
    val showEpisodePicker: Boolean = false
) {
    val hasVisibleModal: Boolean
        get() = trackSelection != null ||
            showVariantSelection ||
            showSpeedSelection ||
            showAudioVideoOffsetDialog ||
            showStopPlaybackTimerDialog ||
            showIdleStandbyTimerDialog ||
            showProgramHistory ||
            showSplitDialog ||
            showEpisodePicker
}
