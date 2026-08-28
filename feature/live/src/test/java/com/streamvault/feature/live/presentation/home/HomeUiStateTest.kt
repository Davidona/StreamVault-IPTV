package com.streamvault.feature.live.presentation.home

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.LiveTvChannelMode
import org.junit.Test

class HomeUiStateTest {
    @Test
    fun `defaults preserve live browse startup state`() {
        val state = HomeUiState()

        assertThat(state.isLoading).isTrue()
        assertThat(state.isCategoriesLoading).isTrue()
        assertThat(state.liveTvChannelMode).isEqualTo(LiveTvChannelMode.PRO)
        assertThat(state.multiviewChannelCount).isEqualTo(0)
        assertThat(state.multiviewSlotCapacity).isEqualTo(4)
    }

    @Test
    fun `state updates remain immutable`() {
        val initial = HomeUiState()
        val updated = initial.copy(channelSearchQuery = "news", hasChannels = true)

        assertThat(initial.channelSearchQuery).isEmpty()
        assertThat(initial.hasChannels).isFalse()
        assertThat(updated.channelSearchQuery).isEqualTo("news")
        assertThat(updated.hasChannels).isTrue()
    }
}
