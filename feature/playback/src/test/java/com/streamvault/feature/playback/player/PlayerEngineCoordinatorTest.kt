package com.streamvault.feature.playback.player

import com.google.common.truth.Truth.assertThat
import com.streamvault.player.PlayerEngine
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify

class PlayerEngineCoordinatorTest {

    @Test
    fun `switching engines disables the outgoing media session`() {
        val mainEngine = mock<PlayerEngine>()
        val nextEngine = mock<PlayerEngine>()
        val coordinator = PlayerEngineCoordinator(mainEngine)

        coordinator.switchTo(nextEngine)

        assertThat(coordinator.currentEngine).isSameInstanceAs(nextEngine)
        verify(mainEngine).setMediaSessionEnabled(false)
    }

    @Test
    fun `switching to the current engine is a no-op`() {
        val mainEngine = mock<PlayerEngine>()
        val coordinator = PlayerEngineCoordinator(mainEngine)

        coordinator.switchTo(mainEngine)

        assertThat(coordinator.currentEngine).isSameInstanceAs(mainEngine)
        verify(mainEngine, org.mockito.kotlin.never()).setMediaSessionEnabled(false)
    }
}
