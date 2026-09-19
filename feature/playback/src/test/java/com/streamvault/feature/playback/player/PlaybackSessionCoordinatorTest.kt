package com.streamvault.feature.playback.player

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackSessionCoordinatorTest {

    @Test
    fun `begin cancels the previous session scope`() = runTest {
        val coordinator = PlaybackSessionCoordinator(this)
        val first = coordinator.begin()
        val firstJob = coordinator.launch(first.id) {
            awaitCancellation()
        }

        val second = coordinator.begin()
        advanceUntilIdle()

        assertThat(firstJob).isNotNull()
        assertThat(firstJob?.isCancelled).isTrue()
        assertThat(coordinator.isCurrent(first.id)).isFalse()
        assertThat(coordinator.isCurrent(second.id)).isTrue()

        coordinator.invalidate()
    }

    @Test
    fun `stale session cannot launch new work`() = runTest {
        val coordinator = PlaybackSessionCoordinator(this)
        val first = coordinator.begin()
        val second = coordinator.begin()
        var executions = 0

        val staleJob = coordinator.launch(first.id) {
            executions++
        }
        val activeJob = coordinator.launch(second.id) {
            executions++
        }
        advanceUntilIdle()

        assertThat(staleJob).isNull()
        assertThat(activeJob).isNotNull()
        assertThat(executions).isEqualTo(1)

        coordinator.invalidate()
    }

    @Test
    fun `invalidate cancels active session and clears current identity`() = runTest {
        val coordinator = PlaybackSessionCoordinator(this)
        val session = coordinator.begin()
        val job = coordinator.launch(session.id) {
            awaitCancellation()
        }

        coordinator.invalidate()
        advanceUntilIdle()

        assertThat(job?.isCancelled).isTrue()
        assertThat(coordinator.currentId).isEqualTo(0L)
        assertThat(coordinator.isCurrent(session.id)).isFalse()
    }
}
