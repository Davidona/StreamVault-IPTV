package com.streamvault.feature.catalog.presentation.vod

import com.streamvault.domain.model.LibraryFilterType
import com.streamvault.domain.model.LibrarySortBy
import com.streamvault.domain.model.ProviderType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

inline fun <State> selectVodCategory(
    categoryName: String?,
    selectedCategoryLoadLimit: MutableStateFlow<Int>,
    selectedLibraryFilterType: StateFlow<LibraryFilterType>,
    selectedLibrarySortBy: StateFlow<LibrarySortBy>,
    uiState: MutableStateFlow<State>,
    crossinline updateState: State.(
        selectedCategory: String?,
        filterType: LibraryFilterType,
        sortBy: LibrarySortBy,
        isLoadingSelectedCategory: Boolean
    ) -> State
) {
    selectedCategoryLoadLimit.value = VodBrowseDefaults.SELECTED_CATEGORY_PAGE_SIZE
    uiState.update { state ->
        state.updateState(
            categoryName,
            selectedLibraryFilterType.value,
            selectedLibrarySortBy.value,
            categoryName != null
        )
    }
}

fun incrementVodSelectedCategoryLoadLimit(
    canLoadMore: Boolean,
    selectedCategoryLoadLimit: MutableStateFlow<Int>
) {
    if (!canLoadMore) return
    selectedCategoryLoadLimit.update { it + VodBrowseDefaults.SELECTED_CATEGORY_PAGE_SIZE }
}

/**
 * Modern VOD preview shelves are the browse surface only when no category is
 * selected. Reorder mode is a distinct grid surface even when it was entered
 * from the preview's category picker.
 */
fun shouldShowVodPreview(selectedCategory: String?, isReorderMode: Boolean): Boolean =
    selectedCategory == null && !isReorderMode

/** Keeps a provider-wide Stalker bucket when it is the only available category. */
fun <T> keepNonProviderWideCategoriesOrAll(
    categories: List<T>,
    isProviderWide: (T) -> Boolean
): List<T> {
    val nonProviderWide = categories.filterNot(isProviderWide)
    return if (nonProviderWide.isNotEmpty()) nonProviderWide else categories
}

fun isPortalVodSearchQuery(
    providerType: ProviderType?,
    enabled: Boolean,
    query: String
): Boolean = enabled && providerType == ProviderType.STALKER_PORTAL && query.trim().length >= 2

fun portalVodSearchBelongsToProvider(snapshotProviderId: Long?, activeProviderId: Long?): Boolean =
    snapshotProviderId != null && snapshotProviderId == activeProviderId

enum class VodReorderMoveDirection {
    EARLIER,
    LATER
}

fun vodReorderMoveDirection(keyCode: Int): VodReorderMoveDirection? = when (keyCode) {
    android.view.KeyEvent.KEYCODE_DPAD_UP,
    android.view.KeyEvent.KEYCODE_DPAD_LEFT -> VodReorderMoveDirection.EARLIER
    android.view.KeyEvent.KEYCODE_DPAD_DOWN,
    android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> VodReorderMoveDirection.LATER
    else -> null
}

inline fun <State> setVodSearchQuery(
    query: String,
    searchQuery: MutableStateFlow<String>,
    uiState: MutableStateFlow<State>,
    crossinline updateState: State.(String) -> State
) {
    searchQuery.value = query
    uiState.update { state -> state.updateState(query) }
}

inline fun <State> setVodLibraryFilterType(
    filterType: LibraryFilterType,
    selectedLibraryFilterType: MutableStateFlow<LibraryFilterType>,
    selectedCategoryLoadLimit: MutableStateFlow<Int>,
    uiState: MutableStateFlow<State>,
    crossinline hasSelectedCategory: (State) -> Boolean,
    crossinline updateState: State.(
        filterType: LibraryFilterType,
        isLoadingSelectedCategory: Boolean
    ) -> State
) {
    selectedLibraryFilterType.value = filterType
    selectedCategoryLoadLimit.value = VodBrowseDefaults.SELECTED_CATEGORY_PAGE_SIZE
    uiState.update { state ->
        state.updateState(filterType, hasSelectedCategory(state))
    }
}

inline fun <State> setVodLibrarySortBy(
    sortBy: LibrarySortBy,
    selectedLibrarySortBy: MutableStateFlow<LibrarySortBy>,
    selectedCategoryLoadLimit: MutableStateFlow<Int>,
    uiState: MutableStateFlow<State>,
    crossinline hasSelectedCategory: (State) -> Boolean,
    crossinline updateState: State.(
        sortBy: LibrarySortBy,
        isLoadingSelectedCategory: Boolean
    ) -> State
) {
    selectedLibrarySortBy.value = sortBy
    selectedCategoryLoadLimit.value = VodBrowseDefaults.SELECTED_CATEGORY_PAGE_SIZE
    uiState.update { state ->
        state.updateState(sortBy, hasSelectedCategory(state))
    }
}

/**
 * Returns a compact summary label for the currently active filter and/or sort
 * (e.g. "Favorites ֲ· Rating"), or null when both are at their defaults.
 * This is used to decorate the "Filter & Sort" action chip so the user can
 * tell at a glance that a non-default browse mode is in effect.
 */
fun vodActiveFilterSortDetail(filter: LibraryFilterType, sort: LibrarySortBy): String? {
    val filterLabel = when (filter) {
        LibraryFilterType.ALL -> null
        LibraryFilterType.FAVORITES -> "Favorites"
        LibraryFilterType.IN_PROGRESS -> "Resume"
        LibraryFilterType.UNWATCHED -> "Unwatched"
        LibraryFilterType.RECENTLY_UPDATED -> "Recent"
        LibraryFilterType.TOP_RATED -> "Top Rated"
    }
    val sortLabel = when (sort) {
        LibrarySortBy.LIBRARY -> null
        LibrarySortBy.TITLE -> "A-Z"
        LibrarySortBy.RELEASE -> "Newest"
        LibrarySortBy.UPDATED -> "Updated"
        LibrarySortBy.RATING -> "Rating"
        LibrarySortBy.WATCH_COUNT -> "Recent Activity"
    }
    return listOfNotNull(filterLabel, sortLabel).joinToString(" ֲ· ").ifEmpty { null }
}
