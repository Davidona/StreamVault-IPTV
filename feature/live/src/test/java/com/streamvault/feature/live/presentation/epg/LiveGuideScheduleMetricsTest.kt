package com.streamvault.feature.live.presentation.epg

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.Program
import org.junit.Test

class LiveGuideScheduleMetricsTest {
    private val scheduledChannel = Channel(id = 1L, name = "Scheduled", epgChannelId = "scheduled")
    private val missingChannel = Channel(id = 2L, name = "Missing", epgChannelId = "missing")
    private val scheduledProgram = Program(
        channelId = "scheduled",
        title = "News",
        startTime = 100L,
        endTime = 200L
    )

    @Test
    fun `schedule metrics count channels by distinct lookup keys`() {
        val duplicate = Channel(id = 3L, name = "Duplicate", epgChannelId = "scheduled")
        val programs = mapOf("scheduled" to listOf(scheduledProgram))

        assertThat(
            countMissingLiveGuideEntries(
                channels = listOf(scheduledChannel, duplicate, missingChannel),
                programsByChannel = programs
            )
        ).isEqualTo(1)
        assertThat(
            countLiveGuideChannelsWithSchedule(
                channels = listOf(scheduledChannel, duplicate, missingChannel),
                programsByChannel = programs
            )
        ).isEqualTo(2)
    }

    @Test
    fun `upcoming guide data requires a program ending after the window start`() {
        val programs = mapOf("scheduled" to listOf(scheduledProgram))

        assertThat(hasUpcomingLiveGuideData(programs, windowStart = 150L)).isTrue()
        assertThat(hasUpcomingLiveGuideData(programs, windowStart = 200L)).isFalse()
    }
}
