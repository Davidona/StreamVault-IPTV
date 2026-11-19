package com.streamvault.feature.live.presentation.home

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.LiveTvChannelMode
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.Channel
import org.junit.Test

class HomeUiStateTest {
    private val largeCategory = Category(id = 12, name = "Sports", count = 525)
    private val firstPage = (1L..200L).map { Channel(id = it, name = "Channel $it") }

    @Test fun `browse reports full category total before all pages load`() {
        val state = HomeUiState(selectedCategory = largeCategory, filteredChannels = firstPage)
        assertThat(state.channelResultCount).isEqualTo(525)
        assertThat(state.copy(categories = listOf(largeCategory.copy(count = 600))).channelResultCount).isEqualTo(600)
        assertThat(state.copy(selectedCategory = largeCategory.copy(id = 13, count = 10),
            categories = emptyList(), filteredChannels = firstPage.take(10)).channelResultCount).isEqualTo(10)
    }

    @Test fun `search virtual and provider filtered results use their own membership`() {
        val state = HomeUiState(selectedCategory = largeCategory, filteredChannels = firstPage)
        assertThat(state.copy(channelSearchQuery = "news").channelResultCount).isEqualTo(200)
        assertThat(state.copy(selectedCategory = largeCategory.copy(isVirtual = true)).channelResultCount).isEqualTo(200)
        assertThat(state.copy(selectedCombinedSourceProviderId = 7).channelResultCount).isEqualTo(200)
        assertThat(state.copy(isChannelReorderMode = true).channelResultCount).isEqualTo(200)
        assertThat(state.copy(selectedCategory = largeCategory.copy(count = 0)).channelResultCount).isEqualTo(200)
    }

    @Test
    fun `defaults preserve live browse startup state`() {
        val state = HomeUiState()

        assertThat(state.isLoading).isTrue()
        assertThat(state.isCategoriesLoading).isTrue()
        assertThat(state.liveTvChannelMode).isEqualTo(LiveTvChannelMode.PRO)
        assertThat(state.liveTvAutoHideCategories).isFalse()
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
