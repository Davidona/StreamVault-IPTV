package com.streamvault.feature.live.presentation.epg

import com.streamvault.domain.model.Category
import com.streamvault.domain.model.VirtualCategoryIds
import com.streamvault.domain.repository.ChannelRepository

fun resolveLiveGuideCategorySelection(
    requestedCategoryId: Long,
    categories: List<Category>,
    parentalControlLevel: Int,
    unlockedCategoryIds: Set<Long>,
    fallbackFromEmptyFavorites: Boolean = false
): Long {
    val requestedExists = categories.any { it.id == requestedCategoryId }
    if (requestedCategoryId == ChannelRepository.ALL_CHANNELS_ID && requestedExists) {
        return ChannelRepository.ALL_CHANNELS_ID
    }

    val requestedCategory = categories.firstOrNull { it.id == requestedCategoryId }
    if (requestedCategory != null &&
        isLiveGuideCategoryAccessible(requestedCategory, parentalControlLevel, unlockedCategoryIds)
    ) {
        if (fallbackFromEmptyFavorites &&
            requestedCategory.id == VirtualCategoryIds.FAVORITES &&
            requestedCategory.count <= 0
        ) {
            return categories.find { it.id == ChannelRepository.ALL_CHANNELS_ID }?.id
                ?: categories.firstOrNull {
                    !(it.id == VirtualCategoryIds.FAVORITES && it.count <= 0) &&
                        isLiveGuideCategoryAccessible(it, parentalControlLevel, unlockedCategoryIds)
                }?.id
                ?: categories.firstOrNull()?.id
                ?: ChannelRepository.ALL_CHANNELS_ID
        }
        return requestedCategory.id
    }

    return categories.firstOrNull { category ->
        if (fallbackFromEmptyFavorites &&
            category.id == VirtualCategoryIds.FAVORITES &&
            category.count <= 0
        ) {
            false
        } else {
            isLiveGuideCategoryAccessible(category, parentalControlLevel, unlockedCategoryIds)
        }
    }?.id ?: categories.firstOrNull()?.id ?: ChannelRepository.ALL_CHANNELS_ID
}
