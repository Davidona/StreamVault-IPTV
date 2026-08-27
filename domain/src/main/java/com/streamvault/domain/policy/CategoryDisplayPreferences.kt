package com.streamvault.domain.policy

import com.streamvault.domain.model.Category
import com.streamvault.domain.model.CategorySortMode

fun applyProviderCategoryDisplayPreferences(
    categories: List<Category>,
    hiddenCategoryIds: Set<Long>,
    sortMode: CategorySortMode
): List<Category> {
    val visible = categories.filterNot { it.id in hiddenCategoryIds }
    return when (sortMode) {
        CategorySortMode.DEFAULT -> visible
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
