package com.streamvault.feature.live.presentation.epg

import com.streamvault.domain.model.Category
import com.streamvault.domain.model.Channel

fun isLiveGuideCategoryLocked(category: Category, parentalControlLevel: Int): Boolean =
    parentalControlLevel in 1..2 && (category.isAdult || category.isUserProtected)

fun isLiveGuideChannelLocked(
    channel: Channel,
    categoriesById: Map<Long, Category>,
    parentalControlLevel: Int
): Boolean {
    if (parentalControlLevel !in 1..2) {
        return false
    }
    val categoryLocked = channel.categoryId
        ?.let(categoriesById::get)
        ?.let { category -> category.isAdult || category.isUserProtected }
        ?: false
    return channel.isAdult || channel.isUserProtected || categoryLocked
}
