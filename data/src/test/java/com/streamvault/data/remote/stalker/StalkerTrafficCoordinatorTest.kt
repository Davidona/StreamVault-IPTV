package com.streamvault.data.remote.stalker

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test

class StalkerTrafficCoordinatorTest {
    @Test
    fun `opt in lets worker run during playback with all pacing at wire admission`() {
        StalkerTrafficCoordinator.notePlaybackStarted(7L)
        assertThat(StalkerTrafficCoordinator.shouldDeferIndexForPlayback(7L, false)).isTrue()
        assertThat(StalkerTrafficCoordinator.shouldDeferIndexForPlayback(7L, true)).isFalse()
        assertThat(StalkerTrafficCoordinator.backgroundInterPageDelayMillis(7L, true)).isEqualTo(0L)
    }

    @Before
    fun setUp() {
        StalkerTrafficCoordinator.resetForTests()
    }

    @After
    fun tearDown() {
        StalkerTrafficCoordinator.resetForTests()
    }

    @Test
    fun `background delay increases while playback is active without blocking interactive work`() {
        val normalDelay = StalkerTrafficCoordinator.backgroundInterPageDelayMillis(providerId = 7L, adaptiveIndexingEnabled = false)
        StalkerTrafficCoordinator.notePlaybackStarted(providerId = 7L)

        val playbackDelay = StalkerTrafficCoordinator.backgroundInterPageDelayMillis(providerId = 7L, adaptiveIndexingEnabled = false)

        assertThat(StalkerTrafficCoordinator.isPlaybackActive(7L)).isTrue()
        assertThat(playbackDelay).isGreaterThan(normalDelay)
    }

    @Test
    fun `playback state clears immediately when playback stops`() {
        StalkerTrafficCoordinator.notePlaybackStarted(providerId = 7L)
        StalkerTrafficCoordinator.notePlaybackStopped(providerId = 7L)

        assertThat(StalkerTrafficCoordinator.isPlaybackActive(7L)).isFalse()
    }

    @Test
    fun `playback state tracks nested sessions for same provider`() {
        StalkerTrafficCoordinator.notePlaybackStarted(providerId = 7L)
        StalkerTrafficCoordinator.notePlaybackStarted(providerId = 7L)
        StalkerTrafficCoordinator.notePlaybackStopped(providerId = 7L)

        assertThat(StalkerTrafficCoordinator.isPlaybackActive(7L)).isTrue()
    }
}
