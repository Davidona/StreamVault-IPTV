package com.streamvault.feature.catalog.presentation.components

/** Returns the stable identity used by Catalog semantics and lazy-list keys. */
internal fun catalogStableSemanticKey(scope: String, providerId: String, contentId: String): String =
    "$scope:$providerId:$contentId"

/** Keeps category-chip identities unique when providers expose the same numeric category id. */
internal fun catalogVodSelectionChipKey(providerId: String, categoryId: Long): String =
    "$providerId:$categoryId"

/** Pure threshold calculation shared by list and grid infinite-scroll effects. */
internal fun catalogShouldLoadMore(
    lastVisibleIndex: Int?,
    totalItemCount: Int,
    prefetchDistance: Int
): Boolean {
    if (totalItemCount <= 0 || lastVisibleIndex == null) return false
    return lastVisibleIndex >= totalItemCount - 1 - prefetchDistance.coerceAtLeast(0)
}

