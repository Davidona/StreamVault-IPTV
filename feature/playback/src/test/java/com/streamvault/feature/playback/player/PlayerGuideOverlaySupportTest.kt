package com.streamvault.feature.playback.player

import com.streamvault.domain.model.VirtualCategoryIds
import com.streamvault.domain.repository.ChannelRepository
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerGuideOverlaySupportTest {

    @Test
    fun `all channels anchors overlay to current channel category when available`() {
        assertEquals(
            PlayerGuideNavigationContext(categoryId = 42L, favoritesOnly = false),
            resolvePlayerGuideNavigationContext(
                activeCategoryId = ChannelRepository.ALL_CHANNELS_ID,
                currentChannelCategoryId = 42L
            )
        )
    }

    @Test
    fun `all channels falls back to explicit all channels when channel category is unavailable`() {
        assertEquals(
            PlayerGuideNavigationContext(categoryId = ChannelRepository.ALL_CHANNELS_ID, favoritesOnly = false),
            resolvePlayerGuideNavigationContext(
                activeCategoryId = ChannelRepository.ALL_CHANNELS_ID,
                currentChannelCategoryId = null
            )
        )
    }

    @Test
    fun `favorites category maps to favorites only guide mode`() {
        assertEquals(
            PlayerGuideNavigationContext(
                categoryId = ChannelRepository.ALL_CHANNELS_ID,
                favoritesOnly = true
            ),
            resolvePlayerGuideNavigationContext(
                activeCategoryId = VirtualCategoryIds.FAVORITES,
                currentChannelCategoryId = 42L
            )
        )
    }

    @Test
    fun `real category keeps explicit category id`() {
        assertEquals(
            PlayerGuideNavigationContext(categoryId = 42L, favoritesOnly = false),
            resolvePlayerGuideNavigationContext(
                activeCategoryId = 42L,
                currentChannelCategoryId = 99L
            )
        )
    }
}
