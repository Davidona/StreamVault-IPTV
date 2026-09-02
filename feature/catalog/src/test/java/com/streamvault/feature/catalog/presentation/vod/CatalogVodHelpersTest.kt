package com.streamvault.feature.catalog.presentation.vod

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.LibraryFilterType
import com.streamvault.domain.model.LibrarySortBy
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test

class CatalogVodHelpersTest {
    @Test
    fun reorderMovement_preservesIdentityAndNoOpEdges() {
        val items = listOf("a", "b", "c")
        assertThat(moveVodItemUp(items, "b")).containsExactly("b", "a", "c").inOrder()
        assertThat(moveVodItemDown(items, "b")).containsExactly("a", "c", "b").inOrder()
        assertThat(moveVodItemUp(items, "a")).isEqualTo(items)
        assertThat(moveVodItemDown(items, "c")).isEqualTo(items)
        assertThat(moveVodItemDown(items, "missing")).isEqualTo(items)
    }

    @Test
    fun filterAndSortDetail_isNullOnlyAtDefaults() {
        assertThat(vodActiveFilterSortDetail(LibraryFilterType.ALL, LibrarySortBy.LIBRARY)).isNull()
        assertThat(vodActiveFilterSortDetail(LibraryFilterType.FAVORITES, LibrarySortBy.RATING))
            .isEqualTo("Favorites ֲ· Rating")
        assertThat(vodActiveFilterSortDetail(LibraryFilterType.UNWATCHED, LibrarySortBy.TITLE))
            .isEqualTo("Unwatched ֲ· A-Z")
    }

    @Test
    fun categorySelection_resetsPageAndCarriesCurrentFilterAndSort() {
        data class State(val category: String?, val filter: LibraryFilterType, val sort: LibrarySortBy, val loading: Boolean)
        val limit = MutableStateFlow(999)
        val filter = MutableStateFlow(LibraryFilterType.FAVORITES)
        val sort = MutableStateFlow(LibrarySortBy.RATING)
        val state = MutableStateFlow(State(null, LibraryFilterType.ALL, LibrarySortBy.LIBRARY, false))

        selectVodCategory("Drama", limit, filter, sort, state) { category, filterType, sortBy, loading ->
            copy(category = category, filter = filterType, sort = sortBy, loading = loading)
        }

        assertThat(limit.value).isEqualTo(VodBrowseDefaults.SELECTED_CATEGORY_PAGE_SIZE)
        assertThat(state.value).isEqualTo(State("Drama", LibraryFilterType.FAVORITES, LibrarySortBy.RATING, true))
    }

    @Test
    fun incrementLoadLimit_isSuppressedWhenNoMoreItems() {
        val limit = MutableStateFlow(VodBrowseDefaults.SELECTED_CATEGORY_PAGE_SIZE)
        incrementVodSelectedCategoryLoadLimit(canLoadMore = false, selectedCategoryLoadLimit = limit)
        assertThat(limit.value).isEqualTo(VodBrowseDefaults.SELECTED_CATEGORY_PAGE_SIZE)
        incrementVodSelectedCategoryLoadLimit(canLoadMore = true, selectedCategoryLoadLimit = limit)
        assertThat(limit.value).isEqualTo(VodBrowseDefaults.SELECTED_CATEGORY_PAGE_SIZE * 2)
    }

    @Test
    fun groupMembership_normalizesVirtualAndStoredIds() {
        assertThat(matchesVodGroupMembership(7L, -7L)).isTrue()
        assertThat(matchesVodGroupMembership(-7L, 7L)).isTrue()
        assertThat(matchesVodGroupMembership(null, 7L)).isFalse()
        assertThat(matchesVodGroupMembership(7L, 8L)).isFalse()
    }
}
