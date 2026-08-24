package com.streamvault.feature.playback.player

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.Program
import org.junit.Test

class PlayerGuideTimelineCoordinatorTest {

    @Test
    fun `apply publishes current next history and upcoming slices`() {
        val coordinator = PlayerGuideTimelineCoordinator()
        val programs = listOf(
            Program(channelId = "channel", title = "Past", startTime = 0L, endTime = 10L),
            Program(channelId = "channel", title = "Now", startTime = 10L, endTime = 30L),
            Program(channelId = "channel", title = "Next", startTime = 30L, endTime = 50L)
        )

        coordinator.apply(programs, now = 20L, channel = null)

        assertThat(coordinator.currentProgram.value?.title).isEqualTo("Now")
        assertThat(coordinator.nextProgram.value?.title).isEqualTo("Next")
        assertThat(coordinator.programHistory.value).isEmpty()
        assertThat(coordinator.upcomingPrograms.value.map { it.title })
            .containsExactly("Now", "Next")
            .inOrder()
    }

    @Test
    fun `clear removes the previous timeline`() {
        val coordinator = PlayerGuideTimelineCoordinator()
        coordinator.apply(
            programs = listOf(Program(channelId = "channel", title = "Now", startTime = 0L, endTime = 10L)),
            now = 1L,
            channel = null
        )

        coordinator.clear()

        assertThat(coordinator.currentProgram.value).isNull()
        assertThat(coordinator.programHistory.value).isEmpty()
        assertThat(coordinator.upcomingPrograms.value).isEmpty()
    }
}
