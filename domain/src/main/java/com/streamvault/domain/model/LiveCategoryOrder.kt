package com.streamvault.domain.model

data class LiveCategoryOrder(
    val sortMode: CategorySortMode = CategorySortMode.DEFAULT,
    /** Complete provider sequence, including categories currently pinned or hidden. */
    val normalIds: List<Long> = emptyList(),
    val pinnedIds: List<Long> = emptyList(),
    val personalIds: List<Long> = emptyList(),
    /** Membership is read from the existing pin preference, not changed by a reorder save. */
    val pinnedCategoryIds: Set<Long> = emptySet(),
    val revision: Long = 0
) {
    init {
        require(revision >= 0)
        require(listOf(normalIds, pinnedIds, personalIds).all { it.distinct().size == it.size })
    }
}

sealed interface SaveLiveCategoryOrderResult {
    data class Saved(val order: LiveCategoryOrder) : SaveLiveCategoryOrderResult
    data class Conflict(val current: LiveCategoryOrder) : SaveLiveCategoryOrderResult
}
