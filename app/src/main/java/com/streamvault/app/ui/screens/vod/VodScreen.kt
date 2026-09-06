package com.streamvault.app.ui.screens.vod

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.app.R
import com.streamvault.app.ui.components.MovieCard
import com.streamvault.app.ui.components.SearchInput
import com.streamvault.app.ui.components.SelectionChip
import com.streamvault.app.ui.components.SeriesCard
import com.streamvault.app.ui.components.shell.AppMessageState
import com.streamvault.app.ui.components.shell.AppNavigationChrome
import com.streamvault.app.ui.components.shell.AppScreenScaffold
import com.streamvault.app.ui.components.shell.InfiniteScrollEffect
import com.streamvault.app.ui.components.shell.LoadMoreCard
import com.streamvault.app.ui.components.shell.VodActionChip
import com.streamvault.app.ui.components.shell.VodActionChipRow
import com.streamvault.app.ui.components.shell.VodBrowseOptionsDialog
import com.streamvault.app.ui.components.shell.VodCategoryOption
import com.streamvault.app.ui.components.shell.VodCategoryPickerDialog
import com.streamvault.app.ui.components.shell.VodSectionHeader
import com.streamvault.app.ui.design.MaterialVerticalScrollbar
import com.streamvault.app.ui.model.VodViewMode
import com.streamvault.domain.model.LibraryFilterType
import com.streamvault.domain.model.LibrarySortBy
import com.streamvault.domain.model.Movie
import com.streamvault.domain.model.Series
import com.streamvault.domain.model.VodCatalogItem

@Composable
fun VodScreen(
    onMovieClick: (Movie) -> Unit,
    onSeriesClick: (Series) -> Unit,
    onNavigate: (String) -> Unit,
    currentRoute: String,
    viewModel: VodViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    BackHandler(enabled = state.selectedCategory != null) { viewModel.selectCategory(null) }

    AppScreenScaffold(
        currentRoute = currentRoute,
        onNavigate = onNavigate,
        title = stringResource(R.string.nav_vod),
        navigationChrome = AppNavigationChrome.TopBar,
        compactHeader = true,
        showScreenHeader = false
    ) {
        when {
            state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.movies_loading))
            }
            !state.isUnifiedProvider -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                AppMessageState(
                    title = stringResource(R.string.vod_unavailable_title),
                    subtitle = stringResource(R.string.vod_unavailable_subtitle)
                )
            }
            state.selectedCategory != null -> SelectedVodCategory(
                title = state.selectedCategory!!.name,
                items = state.selectedItems,
                onMovieClick = onMovieClick,
                onSeriesClick = onSeriesClick,
                canLoadMore = state.canLoadMoreSelectedCategory,
                isInitialLoading = state.isLoadingSelectedCategory,
                isAppending = state.isLoadingMoreSelectedCategory,
                infiniteScroll = state.vodInfiniteScroll,
                loadedCount = state.selectedLoadedCount,
                totalCount = state.selectedTotalCount,
                rawPageSize = state.selectedRawPageSize,
                searchQuery = state.searchQuery,
                selectedFilterType = state.selectedLibraryFilterType,
                selectedSortBy = state.selectedLibrarySortBy,
                onSearchQueryChange = viewModel::setSearchQuery,
                onFilterTypeChange = viewModel::setSelectedLibraryFilterType,
                onSortByChange = viewModel::setSelectedLibrarySortBy,
                onBack = { viewModel.selectCategory(null) },
                onLoadMore = viewModel::loadMoreSelectedCategory
            )
            state.rows.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                AppMessageState(
                    title = stringResource(R.string.movies_no_found),
                    subtitle = stringResource(R.string.movies_no_found_subtitle)
                )
            }
            state.viewMode == VodViewMode.CLASSIC -> VodClassicCategories(
                state.rows,
                viewModel::selectCategory,
                viewModel::loadMoreCategories,
                state.canLoadMoreCategories
            )
            else -> VodModernShelves(
                state.rows,
                viewModel::selectCategory,
                onMovieClick,
                onSeriesClick,
                viewModel::loadMoreCategories,
                state.canLoadMoreCategories
            )
        }
    }
}

@Composable
private fun VodModernShelves(
    rows: List<VodCategoryRow>,
    onCategoryClick: (com.streamvault.domain.model.Category) -> Unit,
    onMovieClick: (Movie) -> Unit,
    onSeriesClick: (Series) -> Unit,
    onLoadMore: () -> Unit,
    canLoadMore: Boolean
) {
    var showCategoryPicker by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    InfiniteScrollEffect(listState, true, canLoadMore, false, onLoadMore = onLoadMore)
    Box(modifier = Modifier.fillMaxSize()) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item(key = "vod_actions") {
            VodActionChipRow(
                actions = listOf(
                    VodActionChip(
                        key = "categories",
                        label = stringResource(R.string.movies_categories_title),
                        detail = "${rows.size} groups",
                        onClick = { showCategoryPicker = true }
                    )
                )
            )
        }
        items(rows, key = { it.category.id }) { row ->
            if (row.items.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.tv.material3.Surface(
                        onClick = { onCategoryClick(row.category) }
                    ) {
                        Text(
                            text = row.category.name,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                        )
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(row.items, key = VodCatalogItem::stableId) { item ->
                            VodItemCard(item, onMovieClick, onSeriesClick)
                        }
                    }
                }
            }
        }
    }
        MaterialVerticalScrollbar(state = listState)
    }
    if (showCategoryPicker) {
        VodCategoryPickerDialog(
            title = stringResource(R.string.vod_category_picker_title),
            subtitle = stringResource(R.string.vod_category_picker_subtitle),
            categories = rows.map { row ->
                VodCategoryOption(
                    name = row.category.name,
                    count = row.items.size,
                    onClick = { onCategoryClick(row.category) }
                )
            },
            onDismiss = { showCategoryPicker = false }
        )
    }
}

@Composable
private fun VodClassicCategories(
    rows: List<VodCategoryRow>,
    onCategoryClick: (com.streamvault.domain.model.Category) -> Unit,
    onLoadMore: () -> Unit,
    canLoadMore: Boolean
) {
    var showCategoryPicker by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    InfiniteScrollEffect(listState, true, canLoadMore, false, onLoadMore = onLoadMore)
    Box(modifier = Modifier.fillMaxSize()) {
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        item(key = "vod_classic_actions") {
            VodActionChipRow(
                actions = listOf(
                    VodActionChip(
                        key = "categories",
                        label = stringResource(R.string.movies_categories_title),
                        detail = "${rows.size} groups",
                        onClick = { showCategoryPicker = true }
                    )
                )
            )
        }
        items(rows, key = { it.category.id }) { row ->
            androidx.tv.material3.Surface(
                onClick = { onCategoryClick(row.category) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)
            ) {
                Text(
                    text = row.category.name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(18.dp)
                )
            }
        }
    }
        MaterialVerticalScrollbar(state = listState)
    }
    if (showCategoryPicker) {
        VodCategoryPickerDialog(
            title = stringResource(R.string.vod_category_picker_title),
            subtitle = stringResource(R.string.vod_category_picker_subtitle),
            categories = rows.map { row ->
                VodCategoryOption(row.category.name, row.items.size, { onCategoryClick(row.category) })
            },
            onDismiss = { showCategoryPicker = false }
        )
    }
}

@Composable
private fun SelectedVodCategory(
    title: String,
    items: List<VodCatalogItem>,
    onMovieClick: (Movie) -> Unit,
    onSeriesClick: (Series) -> Unit,
    canLoadMore: Boolean,
    isInitialLoading: Boolean,
    isAppending: Boolean,
    infiniteScroll: Boolean,
    loadedCount: Int,
    totalCount: Int,
    rawPageSize: Int,
    searchQuery: String,
    selectedFilterType: LibraryFilterType,
    selectedSortBy: LibrarySortBy,
    onSearchQueryChange: (String) -> Unit,
    onFilterTypeChange: (LibraryFilterType) -> Unit,
    onSortByChange: (LibrarySortBy) -> Unit,
    onBack: () -> Unit,
    onLoadMore: () -> Unit
) {
    var showSearchBar by rememberSaveable(title) { mutableStateOf(searchQuery.isNotBlank()) }
    var showBrowseOptions by rememberSaveable(title) { mutableStateOf(false) }
    if (showBrowseOptions) {
        VodBrowseOptionsDialog(
            title = title,
            filterTitle = stringResource(R.string.library_filter_title),
            filterChips = unifiedVodFilterChips(),
            selectedFilterKey = selectedFilterType.name,
            onFilterSelected = { key ->
                LibraryFilterType.entries.firstOrNull { it.name == key }?.let(onFilterTypeChange)
            },
            sortTitle = stringResource(R.string.library_sort_title),
            sortChips = unifiedVodSortChips(),
            selectedSortKey = selectedSortBy.name,
            onSortSelected = { key ->
                LibrarySortBy.entries.firstOrNull { it.name == key }?.let(onSortByChange)
            },
            onDismiss = { showBrowseOptions = false }
        )
    }
    val gridState = rememberLazyGridState()
    InfiniteScrollEffect(
        gridState = gridState,
        enabled = infiniteScroll,
        canLoadMore = canLoadMore,
        isLoading = isInitialLoading || isAppending,
        prefetchDistance = rawPageSize.coerceAtLeast(6),
        onLoadMore = onLoadMore
    )
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        VodSectionHeader(title = title)
        VodActionChipRow(
            actions = listOf(
                VodActionChip("back", stringResource(R.string.nav_vod), onClick = onBack),
                VodActionChip(
                    "search",
                    stringResource(if (showSearchBar) R.string.library_action_hide_search else R.string.search_title),
                    onClick = { showSearchBar = !showSearchBar }
                ),
                VodActionChip(
                    "browse_options",
                    stringResource(R.string.library_action_filters_sort),
                    detail = vodActiveFilterSortDetail(selectedFilterType, selectedSortBy),
                    onClick = { showBrowseOptions = true }
                )
            ),
            selectedKey = "browse_options".takeIf {
                selectedFilterType != LibraryFilterType.ALL || selectedSortBy != LibrarySortBy.LIBRARY
            }
        )
        if (showSearchBar) {
            SearchInput(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                placeholder = stringResource(R.string.movies_search_placeholder),
                onSearch = {},
                modifier = Modifier.padding(horizontal = 20.dp)
            )
        }
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(136.dp),
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (isInitialLoading) {
                item(key = "vod_category_loading", span = { GridItemSpan(maxLineSpan) }) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
            } else {
                items(items, key = VodCatalogItem::stableId) { item ->
                    VodItemCard(item, onMovieClick, onSeriesClick)
                }
            }
            if (canLoadMore && !infiniteScroll && !isInitialLoading && !isAppending) {
                item(key = "load_next_vod_page", span = { GridItemSpan(maxLineSpan) }) {
                    LoadMoreCard(
                        label = stringResource(R.string.library_load_more, loadedCount, totalCount),
                        onClick = onLoadMore,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                }
            }
        }
    }
}

private fun unifiedVodFilterChips(): List<SelectionChip> = listOf(
    SelectionChip(LibraryFilterType.ALL.name, "All"),
    SelectionChip(LibraryFilterType.FAVORITES.name, "Favorites"),
    SelectionChip(LibraryFilterType.IN_PROGRESS.name, "Resume"),
    SelectionChip(LibraryFilterType.UNWATCHED.name, "Unwatched"),
    SelectionChip(LibraryFilterType.RECENTLY_UPDATED.name, "Recent"),
    SelectionChip(LibraryFilterType.TOP_RATED.name, "Top Rated")
)

private fun unifiedVodSortChips(): List<SelectionChip> = LibrarySortBy.entries.map { sort ->
    SelectionChip(
        sort.name,
        when (sort) {
            LibrarySortBy.LIBRARY -> "Library Order"
            LibrarySortBy.TITLE -> "A-Z"
            LibrarySortBy.RELEASE -> "Newest"
            LibrarySortBy.UPDATED -> "Recently Updated"
            LibrarySortBy.RATING -> "Rating"
            LibrarySortBy.WATCH_COUNT -> "Recent Activity"
        }
    )
}

@Composable
private fun VodItemCard(
    item: VodCatalogItem,
    onMovieClick: (Movie) -> Unit,
    onSeriesClick: (Series) -> Unit
) {
    when (item) {
        is VodCatalogItem.MovieItem -> MovieCard(
            movie = item.movie,
            onClick = { onMovieClick(item.movie) },
            isLocked = item.movie.isUserProtected,
            showTypeBadge = true
        )
        is VodCatalogItem.SeriesItem -> SeriesCard(
            series = item.series,
            onClick = { onSeriesClick(item.series) },
            isLocked = item.series.isUserProtected,
            showTypeBadge = true
        )
    }
}
