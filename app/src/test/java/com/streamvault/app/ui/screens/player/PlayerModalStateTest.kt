package com.streamvault.app.ui.screens.player

import com.google.common.truth.Truth.assertThat
import com.streamvault.player.TrackType
import org.junit.Test

class PlayerModalStateTest {

    @Test
    fun `modal state reports whether any player modal is visible`() {
        assertThat(PlayerModalState().hasVisibleModal).isFalse()
        assertThat(PlayerModalState(showSpeedSelection = true).hasVisibleModal).isTrue()
        assertThat(PlayerModalState(trackSelection = TrackType.AUDIO).hasVisibleModal).isTrue()
    }
}
