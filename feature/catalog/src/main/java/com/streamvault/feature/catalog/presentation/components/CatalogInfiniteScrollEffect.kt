package com.streamvault.feature.catalog.presentation.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

/**
 * Triggers [onLoadMore] once when the user scrolls within [prefetchDistance]
 * items of the end of the list. The callback is suppressed while [enabled]
 * is false, [canLoadMore] is false, or [isLoading] is true. The effect
 * re-arms after the visible tail moves further from the end and crosses
 * the threshold again.
 */
@Composable
fun InfiniteScrollEffect(
    listState: LazyListState,
    enabled: Boolean,
    canLoadMore: Boolean,
    isLoading: Boolean,
    prefetchDistance: Int = 3,
    onLoadMore: () -> Unit
) {
    if (!enabled) return
    val shouldLoadMore = remember(listState, prefetchDistance) {
        derivedStateOf {
            val info = listState.layoutInfo
            val total = info.totalItemsCount
            if (total == 0) return@derivedStateOf false
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
            catalogShouldLoadMore(lastVisible, total, prefetchDistance)
        }
    }
    LaunchedEffect(shouldLoadMore, canLoadMore, isLoading) {
        snapshotFlow { shouldLoadMore.value }
            .distinctUntilChanged()
            .filter { it && canLoadMore && !isLoading }
            .collect { onLoadMore() }
    }
}

/** Grid variant of [InfiniteScrollEffect]. */
@Composable
fun InfiniteScrollEffect(
    gridState: LazyGridState,
    enabled: Boolean,
    canLoadMore: Boolean,
    isLoading: Boolean,
    prefetchDistance: Int = 6,
    onLoadMore: () -> Unit
) {
    if (!enabled) return
    val shouldLoadMore = remember(gridState, prefetchDistance) {
        derivedStateOf {
            val info = gridState.layoutInfo
            val total = info.totalItemsCount
            if (total == 0) return@derivedStateOf false
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
            catalogShouldLoadMore(lastVisible, total, prefetchDistance)
        }
    }
    LaunchedEffect(shouldLoadMore, canLoadMore, isLoading) {
        snapshotFlow { shouldLoadMore.value }
            .distinctUntilChanged()
            .filter { it && canLoadMore && !isLoading }
            .collect { onLoadMore() }
    }
}


