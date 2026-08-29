package com.streamvault.feature.live.presentation.epg

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.Program
import org.junit.Test

class LiveGuideFocusTest {
    @Test
    fun `focus resolution preserves the selected channel and matching program`() {
        val channel = Channel(id = 10L, name = "News", streamId = 10L)
        val program = Program(
            channelId = "10",
            title = "Headlines",
            startTime = 1_000L,
            endTime = 2_000L
        )

        val result = resolveLiveGuideFocus(
            channels = listOf(channel),
            programsByChannel = mapOf("10" to listOf(program)),
            focusedChannel = channel,
            focusedProgram = program,
            now = 1_500L
        )

        assertThat(result.channel).isEqualTo(channel)
        assertThat(result.program).isEqualTo(program)
    }

    @Test
    fun `focus resolution falls back to the first channel and current program`() {
        val channel = Channel(id = 10L, name = "News", streamId = 10L)
        val program = Program(
            channelId = "10",
            title = "Headlines",
            startTime = 1_000L,
            endTime = 2_000L
        )

        val result = resolveLiveGuideFocus(
            channels = listOf(channel),
            programsByChannel = mapOf("10" to listOf(program)),
            focusedChannel = null,
            focusedProgram = null,
            now = 1_500L
        )

        assertThat(result.channel).isEqualTo(channel)
        assertThat(result.program).isEqualTo(program)
    }

    @Test
    fun `focus resolution clears both values when channels are empty`() {
        val result = resolveLiveGuideFocus(
            channels = emptyList(),
            programsByChannel = emptyMap(),
            focusedChannel = Channel(id = 10L, name = "News"),
            focusedProgram = Program(
                channelId = "10",
                title = "Headlines",
                startTime = 1_000L,
                endTime = 2_000L
            ),
            now = 1_500L
        )

        assertThat(result.channel).isNull()
        assertThat(result.program).isNull()
    }
}
