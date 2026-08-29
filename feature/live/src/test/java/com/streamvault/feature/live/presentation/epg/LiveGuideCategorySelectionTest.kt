package com.streamvault.feature.live.presentation.epg

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.VirtualCategoryIds
import com.streamvault.domain.repository.ChannelRepository
import org.junit.Test

class LiveGuideCategorySelectionTest {
    private val allChannels = Category(
        id = ChannelRepository.ALL_CHANNELS_ID,
        name = "All channels",
        isVirtual = true
    )
    private val favorites = Category(
        id = VirtualCategoryIds.FAVORITES,
        name = "Favorites",
        isVirtual = true,
        count = 0
    )
    private val general = Category(id = 10L, name = "General")
    private val protected = Category(id = 20L, name = "Protected", isAdult = true)

    @Test
    fun `empty favorite startup falls back to all channels`() {
        assertThat(
            resolveLiveGuideCategorySelection(
                requestedCategoryId = VirtualCategoryIds.FAVORITES,
                categories = listOf(allChannels, favorites, general),
                parentalControlLevel = 0,
                unlockedCategoryIds = emptySet(),
                fallbackFromEmptyFavorites = true
            )
        ).isEqualTo(ChannelRepository.ALL_CHANNELS_ID)
    }

    @Test
    fun `protected selection falls back to accessible category`() {
        assertThat(
            resolveLiveGuideCategorySelection(
                requestedCategoryId = protected.id,
                categories = listOf(allChannels, general, protected),
                parentalControlLevel = 2,
                unlockedCategoryIds = emptySet()
            )
        ).isEqualTo(ChannelRepository.ALL_CHANNELS_ID)
    }

    @Test
    fun `explicitly unlocked protected selection is retained`() {
        assertThat(
            resolveLiveGuideCategorySelection(
                requestedCategoryId = protected.id,
                categories = listOf(allChannels, general, protected),
                parentalControlLevel = 2,
                unlockedCategoryIds = setOf(protected.id)
            )
        ).isEqualTo(protected.id)
    }
}
