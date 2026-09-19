package com.streamvault.feature.playback.player

import com.streamvault.player.TrackType

internal sealed interface PlayerModal {
    data class TrackSelection(val trackType: TrackType) : PlayerModal
    data object VariantSelection : PlayerModal
    data object SpeedSelection : PlayerModal
    data object AudioVideoOffset : PlayerModal
    data object StopPlaybackTimer : PlayerModal
    data object IdleStandbyTimer : PlayerModal
    data object ProgramHistory : PlayerModal
    data object Split : PlayerModal
    data object EpisodePicker : PlayerModal
    data object ChapterSelection : PlayerModal
    data object PlaybackSettings : PlayerModal
}
/**
 * UI-owned modal state for the player screen.
 *
 * The active modal is the single source of truth. The derived queries retain
 * the existing screen contracts while making modal visibility exclusive.
 */
internal data class PlayerModalState(
    val active: PlayerModal? = null
) {
    val trackSelection: TrackType?
        get() = (active as? PlayerModal.TrackSelection)?.trackType

    val showVariantSelection: Boolean
        get() = active is PlayerModal.VariantSelection

    val showSpeedSelection: Boolean
        get() = active is PlayerModal.SpeedSelection

    val showAudioVideoOffsetDialog: Boolean
        get() = active is PlayerModal.AudioVideoOffset

    val showStopPlaybackTimerDialog: Boolean
        get() = active is PlayerModal.StopPlaybackTimer

    val showIdleStandbyTimerDialog: Boolean
        get() = active is PlayerModal.IdleStandbyTimer

    val showProgramHistory: Boolean
        get() = active is PlayerModal.ProgramHistory

    val showSplitDialog: Boolean
        get() = active is PlayerModal.Split

    val showEpisodePicker: Boolean
        get() = active is PlayerModal.EpisodePicker

    val showChapterSelection: Boolean
        get() = active is PlayerModal.ChapterSelection

    val showPlaybackSettings: Boolean
        get() = active is PlayerModal.PlaybackSettings

    val hasVisibleModal: Boolean
        get() = active != null

    fun open(modal: PlayerModal): PlayerModalState = PlayerModalState(active = modal)

    fun dismiss(): PlayerModalState = PlayerModalState()
}
