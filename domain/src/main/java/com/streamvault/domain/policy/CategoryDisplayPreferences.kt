package com.streamvault.domain.policy

import com.streamvault.domain.model.Category
import com.streamvault.domain.model.CategorySortMode
import com.streamvault.domain.model.LiveCategoryOrder
import com.streamvault.domain.repository.ChannelRepository

fun applyProviderCategoryDisplayPreferences(
    categories: List<Category>,
    hiddenCategoryIds: Set<Long>,
    sortMode: CategorySortMode,
    manualCategoryIds: List<Long> = emptyList()
): List<Category> {
    val visible = categories.filterNot { it.id in hiddenCategoryIds }
    return when (sortMode) {
        CategorySortMode.DEFAULT -> visible
        CategorySortMode.MANUAL -> orderCategoriesByIds(visible, manualCategoryIds)
        CategorySortMode.TITLE_ASC -> visible.sortedBy { it.name.lowercase() }
        CategorySortMode.TITLE_DESC -> visible.sortedByDescending { it.name.lowercase() }
        CategorySortMode.COUNT_DESC -> visible.sortedWith(
            compareByDescending<Category> { it.count }.thenBy { it.name.lowercase() }
        )
        CategorySortMode.COUNT_ASC -> visible.sortedWith(
            compareBy<Category> { it.count }.thenBy { it.name.lowercase() }
        )
    }
}

fun orderCategoriesByIds(categories: List<Category>, ids: List<Long>): List<Category> {
    val ranks = ids.withIndex().associate { it.value to it.index }
    return categories.sortedBy { ranks[it.id] ?: Int.MAX_VALUE }
}

/** Personal entries and pins have independent positions; pinning never erases normal positions. */
fun applyLiveCategorySections(
    categories: List<Category>,
    pinnedCategoryIds: Set<Long>,
    order: LiveCategoryOrder
): List<Category> {
    val (personal, provider) = categories.partition { it.isVirtual || it.id == ChannelRepository.ALL_CHANNELS_ID }
    val sorted = applyProviderCategoryDisplayPreferences(provider, emptySet(), order.sortMode, order.normalIds)
    return orderCategoriesByIds(personal, order.personalIds) +
        orderCategoriesByIds(sorted.filter { it.id in pinnedCategoryIds }, order.pinnedIds) +
        sorted.filterNot { it.id in pinnedCategoryIds }
}

fun mergeLiveCategoryPositions(base: List<Long>, moved: List<Long>): List<Long> {
    require(moved.distinct().size == moved.size)
    val moving = moved.toHashSet()
    val remaining = moved.iterator()
    return buildList {
        base.distinct().forEach { add(if (it in moving) remaining.next() else it) }
        while (remaining.hasNext()) add(remaining.next())
    }
}
