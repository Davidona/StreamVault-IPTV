package com.streamvault.data.remote.stalker

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test

class StalkerTrafficCoordinatorTest {
    @Test
    fun `background continuation yields during playback even with adaptive network admission`() {
        StalkerTrafficCoordinator.notePlaybackStarted(7L)
        assertThat(StalkerTrafficCoordinator.shouldDeferIndexForPlayback(7L)).isTrue()
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
    @Test fun engineOwnedLivePlaybackDefersOnlyMatchingAutomaticWork() {
        val owner=Any()
        val automatic=com.streamvault.data.sync.AutomaticSyncExecution.automatic
        val policy=com.streamvault.data.sync.AutomaticSyncExecution.policy
        val previousAutomatic=automatic.get(); val previousPolicy=policy.get()
        try {
            com.streamvault.domain.sync.AutomaticSyncActivity.started(owner,7)
            automatic.set(true); policy.set(com.streamvault.domain.sync.AutomaticSyncSettings())
            assertThat(StalkerTrafficCoordinator.isPlaybackActive(7)).isTrue()
            assertThat(StalkerTrafficCoordinator.shouldDeferIndexForPlayback(7)).isTrue()
            assertThat(StalkerTrafficCoordinator.shouldDeferIndexForPlayback(8)).isFalse()
            com.streamvault.domain.sync.AutomaticSyncActivity.stopped(owner)
            assertThat(StalkerTrafficCoordinator.shouldDeferIndexForPlayback(7)).isFalse()
        } finally {
            automatic.set(previousAutomatic); policy.set(previousPolicy)
            com.streamvault.domain.sync.AutomaticSyncActivity.stopped(owner)
        }
    }
    @Test fun manualAndOptOutKeepWorkingWithoutHidingActualPlaybackOwnership() {
        val owner=Any()
        val automatic=com.streamvault.data.sync.AutomaticSyncExecution.automatic
        val policy=com.streamvault.data.sync.AutomaticSyncExecution.policy
        val previousAutomatic=automatic.get(); val previousPolicy=policy.get()
        try {
            com.streamvault.domain.sync.AutomaticSyncActivity.started(owner,7)
            automatic.set(false); policy.set(com.streamvault.domain.sync.AutomaticSyncSettings())
            assertThat(StalkerTrafficCoordinator.isPlaybackActive(7)).isTrue()
            assertThat(StalkerTrafficCoordinator.shouldDeferIndexForPlayback(7)).isFalse()
            automatic.set(true); policy.set(com.streamvault.domain.sync.AutomaticSyncSettings(deferWhileBusy=false))
            assertThat(StalkerTrafficCoordinator.shouldDeferIndexForPlayback(7)).isFalse()
            assertThat(StalkerTrafficCoordinator.backgroundInterPageDelayMillis(7,false)).isEqualTo(1000L)
            policy.set(null)
            assertThat(StalkerTrafficCoordinator.shouldDeferIndexForPlayback(7)).isTrue()
        } finally {
            automatic.set(previousAutomatic); policy.set(previousPolicy)
            com.streamvault.domain.sync.AutomaticSyncActivity.stopped(owner)
        }
    }
}
