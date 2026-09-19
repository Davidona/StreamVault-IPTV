package com.streamvault.feature.live.presentation.epg

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.Program
import org.junit.Test

class LiveGuideGridRowsTest {
    @Test
    fun `current program selects the program containing now`() {
        val programs = listOf(
            Program(channelId = "news", title = "Earlier", startTime = 0L, endTime = 100L),
            Program(channelId = "news", title = "Current", startTime = 100L, endTime = 200L),
            Program(channelId = "news", title = "Later", startTime = 200L, endTime = 300L)
        )

        assertThat(programs.liveCurrentProgramAt(150L)?.title).isEqualTo("Current")
    }

    @Test
    fun `current program excludes the end boundary`() {
        val program = Program(channelId = "news", title = "Current", startTime = 100L, endTime = 200L)

        assertThat(listOf(program).liveCurrentProgramAt(200L)).isNull()
    }
}
