package com.streamvault.feature.live.presentation.home

import com.streamvault.domain.model.Category
import com.streamvault.domain.model.Channel
import kotlin.math.abs

fun isLiveHomeCategoryLocked(
    category: Category,
    parentalControlLevel: Int,
    unlockedCategoryIds: Set<Long>
): Boolean =
    (category.isAdult || category.isUserProtected) &&
        parentalControlLevel in 1..2 &&
        abs(category.id) !in unlockedCategoryIds

fun isLiveHomeChannelLocked(
    channel: Channel,
    categories: List<Category>,
    selectedCategory: Category?,
    parentalControlLevel: Int,
    unlockedCategoryIds: Set<Long>
): Boolean {
    val channelCategoryId = channel.categoryId
    val sourceCategory = categories.firstOrNull { it.id == channelCategoryId }
    val unlockedByChannelCategory =
        channelCategoryId != null && abs(channelCategoryId) in unlockedCategoryIds
    val unlockedBySelectedCategory =
        selectedCategory != null && abs(selectedCategory.id) in unlockedCategoryIds
    val unlocked = unlockedByChannelCategory || unlockedBySelectedCategory
    return (
        channel.isAdult ||
            channel.isUserProtected ||
            selectedCategory?.isAdult == true ||
            selectedCategory?.isUserProtected == true ||
            sourceCategory?.isAdult == true ||
            sourceCategory?.isUserProtected == true
        ) &&
        parentalControlLevel in 1..2 &&
        !unlocked
}
