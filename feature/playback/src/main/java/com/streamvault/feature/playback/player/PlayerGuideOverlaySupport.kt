package com.streamvault.feature.playback.player

import com.streamvault.domain.model.VirtualCategoryIds
import com.streamvault.domain.repository.ChannelRepository

internal data class PlayerGuideNavigationContext(
    val categoryId: Long?,
    val favoritesOnly: Boolean
)

internal fun resolvePlayerGuideNavigationContext(
    activeCategoryId: Long,
    currentChannelCategoryId: Long?
): PlayerGuideNavigationContext =
    when (activeCategoryId) {
        ChannelRepository.ALL_CHANNELS_ID -> PlayerGuideNavigationContext(
            categoryId = currentChannelCategoryId?.takeIf { it > 0L } ?: ChannelRepository.ALL_CHANNELS_ID,
            favoritesOnly = false
        )
        VirtualCategoryIds.FAVORITES -> PlayerGuideNavigationContext(
            categoryId = ChannelRepository.ALL_CHANNELS_ID,
            favoritesOnly = true
        )
        in 1L..Long.MAX_VALUE -> PlayerGuideNavigationContext(
            categoryId = activeCategoryId,
            favoritesOnly = false
        )
        else -> PlayerGuideNavigationContext(
            categoryId = currentChannelCategoryId?.takeIf { it > 0L } ?: ChannelRepository.ALL_CHANNELS_ID,
            favoritesOnly = false
        )
    }
