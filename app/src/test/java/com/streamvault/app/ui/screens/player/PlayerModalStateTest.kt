package com.streamvault.app.ui.screens.player

import com.google.common.truth.Truth.assertThat
import com.streamvault.player.TrackType
import org.junit.Test

class PlayerModalStateTest {

    @Test
    fun `opening a modal replaces the previous modal`() {
        val state = PlayerModalState()
            .open(PlayerModal.ProgramHistory)
            .open(PlayerModal.SpeedSelection)

        assertThat(state.showProgramHistory).isFalse()
        assertThat(state.showSpeedSelection).isTrue()
    }

    @Test
    fun `opening speed selection replaces active track selection`() {
        val state = PlayerModalState()
            .open(PlayerModal.TrackSelection(TrackType.AUDIO))
            .open(PlayerModal.SpeedSelection)

        assertThat(state.trackSelection).isNull()
        assertThat(state.showSpeedSelection).isTrue()
    }

    @Test
    fun `track selection retains its track type`() {
        val state = PlayerModalState().open(PlayerModal.TrackSelection(TrackType.AUDIO))

        assertThat(state.trackSelection).isEqualTo(TrackType.AUDIO)
    }

    @Test
    fun `derived visibility reflects the active modal`() {
        val state = PlayerModalState().open(PlayerModal.AudioVideoOffset)

        assertThat(state.showAudioVideoOffsetDialog).isTrue()
        assertThat(state.showVariantSelection).isFalse()
        assertThat(state.showSpeedSelection).isFalse()
        assertThat(state.showStopPlaybackTimerDialog).isFalse()
        assertThat(state.showIdleStandbyTimerDialog).isFalse()
        assertThat(state.showProgramHistory).isFalse()
        assertThat(state.showSplitDialog).isFalse()
        assertThat(state.showEpisodePicker).isFalse()
        assertThat(state.hasVisibleModal).isTrue()
    }

    @Test
    fun `dismissing a modal clears the active modal`() {
        val state = PlayerModalState()
            .open(PlayerModal.EpisodePicker)
            .dismiss()

        assertThat(state.active).isNull()
        assertThat(state.hasVisibleModal).isFalse()
    }

    @Test
    fun `empty state has no active modal`() {
        val state = PlayerModalState()

        assertThat(state.active).isNull()
        assertThat(state.hasVisibleModal).isFalse()
    }
}
