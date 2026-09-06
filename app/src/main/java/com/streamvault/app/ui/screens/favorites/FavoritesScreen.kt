package com.streamvault.app.ui.screens.favorites

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.streamvault.app.R
import com.streamvault.app.ui.components.SelectionChip
import com.streamvault.app.ui.components.SelectionChipRow
import com.streamvault.app.ui.components.dialogs.PremiumDialog
import com.streamvault.app.ui.components.dialogs.PremiumDialogActionButton
import com.streamvault.app.ui.components.dialogs.PremiumDialogFooterButton
import com.streamvault.app.ui.components.shell.AppNavigationChrome
import com.streamvault.app.ui.components.shell.AppHeroHeader
import com.streamvault.app.ui.components.shell.AppScreenScaffold
import com.streamvault.app.ui.components.shell.AppSectionHeader
import com.streamvault.app.ui.components.shell.StatusPill
import com.streamvault.app.ui.theme.OnBackground
import com.streamvault.app.ui.theme.OnPrimary
import com.streamvault.app.ui.design.MaterialVerticalScrollbar
import com.streamvault.app.ui.theme.OnSurface
import com.streamvault.app.ui.theme.OnSurfaceDim
import com.streamvault.app.ui.theme.Primary
import com.streamvault.app.ui.theme.Surface
import com.streamvault.app.ui.theme.SurfaceElevated
import com.streamvault.app.ui.theme.SurfaceHighlight
import com.streamvault.domain.model.ContentType
import kotlinx.coroutines.launch
import com.streamvault.app.ui.interaction.TvClickableSurface
import com.streamvault.app.ui.interaction.TvButton
import com.streamvault.app.ui.interaction.TvIconButton

@Composable
fun FavoritesScreen(
    onItemClick: (FavoriteUiModel) -> Unit,
    onHistoryClick: (SavedHistoryUiModel) -> Unit,
    onNavigate: (String) -> Unit,
    currentRoute: String,
    viewModel: FavoritesViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val activeReorderSection = uiState.sections.firstOrNull { it.key == uiState.reorderSectionKey }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val homeShelfGlobalTitle = stringResource(R.string.saved_preset_home_shelf_global_title)
    val homeShelfGlobalSubtitle = stringResource(R.string.saved_preset_home_shelf_global_subtitle)
    val providerScopedSections = remember(
        uiState.sections,
        uiState.selectedPreset,
        uiState.selectedProviderScope,
        uiState.activeProviderId,
        uiState.managedGroups,
        homeShelfGlobalTitle,
        homeShelfGlobalSubtitle
    ) {
        val presetSections = when (uiState.selectedPreset) {
            SavedLibraryPreset.ALL_SAVED,
            SavedLibraryPreset.WATCH_NEXT -> uiState.sections
            SavedLibraryPreset.HOME_SHELF -> uiState.sections.mapNotNull { section ->
                if (section.key == "global") {
                    val liveFavorites = section.items.filter { it.favorite.contentType == ContentType.LIVE }
                    if (liveFavorites.isEmpty()) null else section.copy(
                        title = homeShelfGlobalTitle,
                        subtitle = homeShelfGlobalSubtitle,
                        items = liveFavorites,
                        reorderable = liveFavorites.size > 1
                    )
                } else {
                    val groupId = section.key.removePrefix("group:").toLongOrNull()
                    val managedGroup = uiState.managedGroups.firstOrNull { it.group.id == groupId }
                    if (managedGroup?.isPromotedOnHome == true) section else null
                }
            }
            SavedLibraryPreset.LIVE_RECALL -> uiState.sections.filter { section ->
                section.items.any { it.favorite.contentType == ContentType.LIVE }
            }
            SavedLibraryPreset.MOVIES -> uiState.sections.filter { section ->
                section.items.any { it.favorite.contentType == ContentType.MOVIE }
            }
            SavedLibraryPreset.SERIES -> uiState.sections.filter { section ->
                section.items.any { it.favorite.contentType == ContentType.SERIES }
            }
            SavedLibraryPreset.CUSTOM_GROUPS -> uiState.sections.filterNot { it.key == "global" }
        }
        if (uiState.selectedProviderScope != SavedLibraryProviderScope.CURRENT_PROVIDER || uiState.activeProviderId == null) {
            presetSections
        } else {
            presetSections.mapNotNull { section ->
                val scopedItems = section.items.filter { it.providerId == uiState.activeProviderId }
                if (scopedItems.isEmpty()) {
                    null
                } else {
                    section.copy(
                        subtitle = "${scopedItems.size} saved items",
                        items = scopedItems,
                        reorderable = section.reorderable && scopedItems.size > 1
                    )
                }
            }
        }
    }
    val visibleContinueWatching = remember(uiState.continueWatching, uiState.selectedPreset, uiState.selectedProviderScope, uiState.activeProviderId) {
        when (uiState.selectedPreset) {
            SavedLibraryPreset.ALL_SAVED,
            SavedLibraryPreset.WATCH_NEXT -> uiState.continueWatching
            SavedLibraryPreset.MOVIES -> uiState.continueWatching.filter { it.history.contentType == ContentType.MOVIE }
            SavedLibraryPreset.SERIES -> uiState.continueWatching.filter {
                it.history.contentType == ContentType.SERIES || it.history.contentType == ContentType.SERIES_EPISODE
            }
            else -> emptyList()
        }.let { items ->
            if (uiState.selectedProviderScope == SavedLibraryProviderScope.CURRENT_PROVIDER && uiState.activeProviderId != null) {
                items.filter { it.providerId == uiState.activeProviderId }
            } else {
                items
            }
        }
    }
    val visibleRecentLive = remember(uiState.recentLive, uiState.selectedPreset, uiState.selectedProviderScope, uiState.activeProviderId) {
        when (uiState.selectedPreset) {
            SavedLibraryPreset.ALL_SAVED,
            SavedLibraryPreset.LIVE_RECALL,
            SavedLibraryPreset.HOME_SHELF -> uiState.recentLive
            else -> emptyList()
        }.let { items ->
            if (uiState.selectedProviderScope == SavedLibraryProviderScope.CURRENT_PROVIDER && uiState.activeProviderId != null) {
                items.filter { it.providerId == uiState.activeProviderId }
            } else {
                items
            }
        }
    }
    val filteredSections = remember(providerScopedSections, uiState.selectedFilter, uiState.selectedSort, uiState.isReorderMode) {
        providerScopedSections
            .mapNotNull { section ->
                val filteredItems = when (uiState.selectedFilter) {
                    SavedLibraryFilter.ALL -> section.items
                    SavedLibraryFilter.LIVE -> section.items.filter { it.favorite.contentType == ContentType.LIVE }
                    SavedLibraryFilter.MOVIE -> section.items.filter { it.favorite.contentType == ContentType.MOVIE }
                    SavedLibraryFilter.SERIES -> section.items.filter { it.favorite.contentType == ContentType.SERIES }
                }
                if (filteredItems.isEmpty()) {
                    null
                } else {
                    val sortedItems = if (uiState.isReorderMode) {
                        filteredItems
                    } else {
                        when (uiState.selectedSort) {
                            SavedLibrarySort.DEFAULT -> filteredItems
                            SavedLibrarySort.RECENT -> filteredItems.sortedByDescending { it.lastWatchedAt }
                            SavedLibrarySort.TITLE -> filteredItems.sortedBy { it.title.lowercase() }
                        }
                    }
                    section.copy(
                        subtitle = "${sortedItems.size} saved items",
                        items = sortedItems,
                        reorderable = section.reorderable && filteredItems.size > 1
                    )
                }
            }
            .let { sections ->
                if (uiState.isReorderMode || uiState.selectedSort == SavedLibrarySort.DEFAULT) {
                    sections
                } else {
                    sections.sortedByDescending { section ->
                        when (uiState.selectedSort) {
                            SavedLibrarySort.RECENT -> section.items.maxOfOrNull { it.lastWatchedAt } ?: 0L
                            SavedLibrarySort.TITLE -> 0L
                            SavedLibrarySort.DEFAULT -> 0L
                        }
                    }.let { recentSorted ->
                        if (uiState.selectedSort == SavedLibrarySort.TITLE) {
                            sections.sortedBy { it.title.lowercase() }
                        } else {
                            recentSorted
                        }
                    }
                }
            }
    }
    val providerScopedSummary = remember(filteredSections, providerScopedSections, visibleContinueWatching, visibleRecentLive) {
        val globalItems = providerScopedSections.firstOrNull { it.key == "global" }?.items.orEmpty()
        SavedLibrarySummary(
            totalItems = globalItems.size,
            liveCount = globalItems.count { it.favorite.contentType == ContentType.LIVE },
            movieCount = globalItems.count { it.favorite.contentType == ContentType.MOVIE },
            seriesCount = globalItems.count { it.favorite.contentType == ContentType.SERIES },
            customGroupCount = providerScopedSections.count { it.key != "global" },
            continueWatchingCount = visibleContinueWatching.size,
            recentLiveCount = visibleRecentLive.size
        )
    }
    val providerScopedPresetSummary = remember(providerScopedSections, visibleContinueWatching, visibleRecentLive) {
        val globalItems = providerScopedSections.firstOrNull { it.key == "global" }?.items.orEmpty()
        SavedLibraryPresetSummary(
            allSavedCount = globalItems.size,
            watchNextCount = visibleContinueWatching.size,
            liveRecallCount = visibleRecentLive.size,
            movieCount = globalItems.count { it.favorite.contentType == ContentType.MOVIE },
            seriesCount = globalItems.count { it.favorite.contentType == ContentType.SERIES },
            customGroupCount = providerScopedSections.count { it.key != "global" }
        )
    }
    val visibleManagedGroups = remember(uiState.managedGroups, providerScopedSections, uiState.selectedProviderScope, uiState.activeProviderId) {
        val scopedSectionCounts = providerScopedSections
            .filter { it.key.startsWith("group:") }
            .associate { section ->
                section.key.removePrefix("group:").toLongOrNull() to section.items.size
            }
        uiState.managedGroups.mapNotNull { group ->
            val scopedCount = scopedSectionCounts[group.group.id]
            if (uiState.selectedProviderScope == SavedLibraryProviderScope.CURRENT_PROVIDER && uiState.activeProviderId != null) {
                if (scopedCount == null || scopedCount == 0) {
                    null
                } else {
                    group.copy(itemCount = scopedCount)
                }
            } else {
                group
            }
        }
    }
    val hasVisibleContent = remember(filteredSections, visibleContinueWatching, visibleRecentLive) {
        filteredSections.isNotEmpty() || visibleContinueWatching.isNotEmpty() || visibleRecentLive.isNotEmpty()
    }
    val sectionBaseIndex = 5 +
        (if (visibleContinueWatching.isNotEmpty()) 1 else 0) +
        (if (visibleRecentLive.isNotEmpty()) 1 else 0)
    val sectionChipIndex = remember(filteredSections, sectionBaseIndex) {
        buildMap {
            var currentIndex = sectionBaseIndex
            filteredSections.forEach { section ->
                put(section.key, currentIndex)
                currentIndex += 1 + section.items.size
            }
        }
    }
    val presetChips = listOf(
        SelectionChip(
            key = SavedLibraryPreset.ALL_SAVED.name,
            label = stringResource(R.string.saved_preset_all_saved),
            supportingText = stringResource(R.string.library_saved_items_count, providerScopedPresetSummary.allSavedCount)
        ),
        SelectionChip(
            key = SavedLibraryPreset.HOME_SHELF.name,
            label = stringResource(R.string.saved_preset_home_shelf),
            supportingText = stringResource(
                R.string.library_saved_items_count,
                visibleManagedGroups.count { it.isPromotedOnHome } + visibleRecentLive.size
            )
        ),
        SelectionChip(
            key = SavedLibraryPreset.WATCH_NEXT.name,
            label = stringResource(R.string.saved_preset_watch_next),
            supportingText = stringResource(R.string.library_saved_items_count, providerScopedPresetSummary.watchNextCount)
        ),
        SelectionChip(
            key = SavedLibraryPreset.LIVE_RECALL.name,
            label = stringResource(R.string.saved_preset_live_recall),
            supportingText = stringResource(R.string.library_saved_items_count, providerScopedPresetSummary.liveRecallCount)
        ),
        SelectionChip(
            key = SavedLibraryPreset.MOVIES.name,
            label = stringResource(R.string.search_movies),
            supportingText = stringResource(R.string.library_saved_items_count, providerScopedPresetSummary.movieCount)
        ),
        SelectionChip(
            key = SavedLibraryPreset.SERIES.name,
            label = stringResource(R.string.search_series),
            supportingText = stringResource(R.string.library_saved_items_count, providerScopedPresetSummary.seriesCount)
        ),
        SelectionChip(
            key = SavedLibraryPreset.CUSTOM_GROUPS.name,
            label = stringResource(R.string.saved_preset_custom_groups),
            supportingText = stringResource(R.string.library_saved_items_count, providerScopedPresetSummary.customGroupCount)
        )
    )
    val providerScopeChips = listOf(
        SelectionChip(
            key = SavedLibraryProviderScope.CURRENT_PROVIDER.name,
            label = uiState.activeProviderName ?: stringResource(R.string.saved_scope_current_provider),
            supportingText = stringResource(R.string.saved_scope_current_provider_hint)
        ),
        SelectionChip(
            key = SavedLibraryProviderScope.ALL_PROVIDERS.name,
            label = stringResource(R.string.saved_scope_all_providers),
            supportingText = stringResource(R.string.saved_scope_all_providers_hint)
        )
    )
    val filterChips = listOf(
        SelectionChip(
            key = SavedLibraryFilter.ALL.name,
            label = stringResource(R.string.search_all),
            supportingText = stringResource(R.string.library_saved_items_count, providerScopedSummary.totalItems)
        ),
        SelectionChip(
            key = SavedLibraryFilter.LIVE.name,
            label = stringResource(R.string.search_live_tv),
            supportingText = stringResource(R.string.library_saved_items_count, providerScopedSummary.liveCount)
        ),
        SelectionChip(
            key = SavedLibraryFilter.MOVIE.name,
            label = stringResource(R.string.search_movies),
            supportingText = stringResource(R.string.library_saved_items_count, providerScopedSummary.movieCount)
        ),
        SelectionChip(
            key = SavedLibraryFilter.SERIES.name,
            label = stringResource(R.string.search_series),
            supportingText = stringResource(R.string.library_saved_items_count, providerScopedSummary.seriesCount)
        )
    )

    Box(modifier = Modifier.fillMaxSize()) {
        AppScreenScaffold(
            currentRoute = currentRoute,
            onNavigate = onNavigate,
            title = stringResource(R.string.favorites_title),
            subtitle = stringResource(R.string.saved_shell_subtitle),
            navigationChrome = AppNavigationChrome.TopBar,
            compactHeader = true,
            showScreenHeader = false
        ) {
            when {
                uiState.isLoading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = stringResource(R.string.favorites_loading),
                            style = MaterialTheme.typography.bodyLarge,
                            color = OnSurface
                        )
                    }
                }

                !hasVisibleContent -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = stringResource(R.string.favorites_no_favorites),
                                style = MaterialTheme.typography.titleLarge,
                                color = OnSurface
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.favorites_empty_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = OnSurfaceDim
                            )
                        }
                    }
                }

                else -> {
                    Box(modifier = Modifier.fillMaxSize()) {
                        Box(modifier = Modifier.fillMaxSize()) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 32.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                        item(key = "summary") {
                            SavedLibrarySummaryCard(
                                summary = providerScopedSummary,
                                currentFilter = uiState.selectedFilter
                            )
                        }

                        item(key = "provider_scope") {
                            SelectionChipRow(
                                title = stringResource(R.string.saved_scope_title),
                                subtitle = stringResource(
                                    R.string.saved_scope_subtitle,
                                    uiState.activeProviderName ?: stringResource(R.string.settings_overview_no_provider)
                                ),
                                chips = providerScopeChips,
                                selectedKey = uiState.selectedProviderScope.name,
                                onChipSelected = { key ->
                                    SavedLibraryProviderScope.entries.firstOrNull { it.name == key }?.let(viewModel::selectProviderScope)
                                },
                                contentPadding = PaddingValues(horizontal = 0.dp)
                            )
                        }

                        item(key = "filters") {
                            SelectionChipRow(
                                title = stringResource(R.string.saved_preset_title),
                                subtitle = stringResource(R.string.saved_preset_subtitle),
                                chips = presetChips,
                                selectedKey = uiState.selectedPreset.name,
                                onChipSelected = { key ->
                                    SavedLibraryPreset.entries
                                        .firstOrNull { it.name == key }
                                        ?.let(viewModel::selectPreset)
                                },
                                contentPadding = PaddingValues(horizontal = 0.dp)
                            )
                        }

                        item(key = "filter_types") {
                            SelectionChipRow(
                                title = stringResource(R.string.favorites_filters_title),
                                subtitle = stringResource(R.string.favorites_filters_subtitle),
                                chips = filterChips,
                                selectedKey = uiState.selectedFilter.name,
                                onChipSelected = { key ->
                                    SavedLibraryFilter.entries
                                        .firstOrNull { it.name == key }
                                        ?.let(viewModel::selectFilter)
                                },
                                contentPadding = PaddingValues(horizontal = 0.dp)
                            )
                        }

                        item(key = "sorts") {
                            SelectionChipRow(
                                title = stringResource(R.string.favorites_sort_title),
                                subtitle = stringResource(R.string.favorites_sort_subtitle),
                                chips = listOf(
                                    SelectionChip(
                                        key = SavedLibrarySort.DEFAULT.name,
                                        label = stringResource(R.string.favorites_sort_default),
                                        supportingText = stringResource(R.string.favorites_sort_default_hint)
                                    ),
                                    SelectionChip(
                                        key = SavedLibrarySort.RECENT.name,
                                        label = stringResource(R.string.favorites_sort_recent),
                                        supportingText = stringResource(R.string.favorites_sort_recent_hint)
                                    ),
                                    SelectionChip(
                                        key = SavedLibrarySort.TITLE.name,
                                        label = stringResource(R.string.favorites_sort_title_alpha),
                                        supportingText = stringResource(R.string.favorites_sort_title_hint)
                                    )
                                ),
                                selectedKey = uiState.selectedSort.name,
                                onChipSelected = { key ->
                                    SavedLibrarySort.entries.firstOrNull { it.name == key }?.let(viewModel::selectSort)
                                },
                                contentPadding = PaddingValues(horizontal = 0.dp)
                            )
                        }

                        if (visibleManagedGroups.isNotEmpty()) {
                            item(key = "group_management") {
                                SavedGroupManagementRow(
                                    groups = visibleManagedGroups,
                                    onGroupClick = { group ->
                                        scope.launch {
                                            val itemIndex = sectionChipIndex["group:${group.group.id}"]
                                            if (itemIndex != null) {
                                                listState.animateScrollToItem(itemIndex)
                                            } else {
                                                when (group.group.contentType) {
                                                    ContentType.LIVE -> viewModel.selectPreset(SavedLibraryPreset.LIVE_RECALL)
                                                    ContentType.MOVIE -> viewModel.selectPreset(SavedLibraryPreset.MOVIES)
                                                    ContentType.SERIES -> viewModel.selectPreset(SavedLibraryPreset.SERIES)
                                                    ContentType.VOD,
                                                    ContentType.SERIES_EPISODE -> Unit
                                                }
                                            }
                                        }
                                    },
                                    onGroupLongClick = viewModel::showGroupOptions
                                )
                            }
                        }

                        if (visibleContinueWatching.isNotEmpty()) {
                            item(key = "continue_watching") {
                                SavedHistoryRow(
                                    title = stringResource(R.string.favorites_continue_title),
                                    subtitle = stringResource(R.string.favorites_continue_subtitle),
                                    items = visibleContinueWatching,
                                    onItemClick = onHistoryClick
                                )
                            }
                        }

                        if (visibleRecentLive.isNotEmpty()) {
                            item(key = "recent_live") {
                                SavedHistoryRow(
                                    title = stringResource(R.string.favorites_recent_live_title),
                                    subtitle = stringResource(R.string.favorites_recent_live_subtitle),
                                    items = visibleRecentLive,
                                    onItemClick = onHistoryClick
                                )
                            }
                        }

                        if (filteredSections.isNotEmpty()) {
                            item(key = "jumps") {
                                SelectionChipRow(
                                    title = stringResource(R.string.favorites_jump_title),
                                    subtitle = stringResource(R.string.favorites_jump_subtitle),
                                    chips = filteredSections.map { section ->
                                        SelectionChip(
                                            key = section.key,
                                            label = section.title,
                                            supportingText = stringResource(
                                                R.string.library_saved_items_count,
                                                section.items.size
                                            )
                                        )
                                    },
                                    selectedKey = null,
                                    onChipSelected = { key ->
                                        val itemIndex = sectionChipIndex[key]
                                        if (itemIndex != null) {
                                            scope.launch {
                                                listState.animateScrollToItem(itemIndex)
                                            }
                                        }
                                    },
                                    contentPadding = PaddingValues(horizontal = 0.dp)
                                )
                            }
                        } else {
                            item(key = "filtered_empty") {
                                Text(
                                    text = stringResource(R.string.library_filter_empty),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = OnSurfaceDim
                                )
                            }
                        }

                        filteredSections.forEach { section ->
                            item(key = "header:${section.key}") {
                                FavoriteSectionHeader(section = section)
                            }

                            items(
                                items = section.items,
                                key = { item -> "${section.key}:${item.favorite.id}" }
                            ) { item ->
                                FavoriteRow(
                                    item = item,
                                    isReorderMode = uiState.isReorderMode,
                                    isReorderingThis = uiState.isReorderMode && uiState.reorderItem?.id == item.favorite.id,
                                    onClick = {
                                        if (uiState.isReorderMode) {
                                            if (uiState.reorderItem?.id == item.favorite.id) {
                                                viewModel.saveReorder()
                                            }
                                        } else if (item.favorite.contentType == ContentType.SERIES) {
                                            onNavigate("series_detail/${item.favorite.contentId}")
                                        } else {
                                            onItemClick(item)
                                        }
                                    },
                                    onLongClick = { viewModel.showItemOptions(item) },
                                    onMoveUp = { viewModel.moveItem(-1) },
                                    onMoveDown = { viewModel.moveItem(1) },
                                    onConfirm = { viewModel.saveReorder() },
                                    onCancel = { viewModel.exitReorderMode() }
                                )
                            }
                        }
                        }

                        androidx.compose.animation.AnimatedVisibility(
                            visible = uiState.isReorderMode && activeReorderSection != null,
                            enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
                            exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = 24.dp)
                        ) {
                            ReorderPanel(
                                section = activeReorderSection,
                                movingFavoriteId = uiState.reorderItem?.id
                            )
                        }
                        MaterialVerticalScrollbar(state = listState)
                        }
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 18.dp)
        )
    }

    androidx.compose.runtime.LaunchedEffect(uiState.userMessage) {
        uiState.userMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.userMessageShown()
        }
    }

    uiState.itemDialogItem?.let { item ->
        SavedItemOptionsDialog(
            item = item,
            moveTargets = uiState.itemMoveTargets,
            onDismiss = viewModel::dismissSavedDialog,
            onRemove = { viewModel.removeFromSavedContext(item) },
            onMoveToGroup = viewModel::moveItemToGroup
        )
    }

            uiState.managedGroupDialog?.let { group ->
        SavedGroupOptionsDialog(
            group = group,
            canMerge = visibleManagedGroups.any {
                it.group.contentType == group.group.contentType && it.group.id != group.group.id
            },
            onDismiss = viewModel::dismissSavedDialog,
            onReorder = {
                val section = uiState.sections.firstOrNull { it.key == "group:${group.group.id}" }
                val firstItem = section?.items?.firstOrNull()
                if (section != null && firstItem != null) {
                    viewModel.enterReorderMode(section.key, firstItem)
                }
                viewModel.dismissSavedDialog()
            },
            onTogglePromotion = if (group.group.contentType == ContentType.LIVE) {
                { viewModel.toggleHomePromotion(group) }
            } else null,
            onMerge = { viewModel.startMergeGroup(group) },
            onDelete = { viewModel.deleteManagedGroup(group) }
        )
    }

    uiState.mergeSourceGroup?.let { source ->
        MergeSavedGroupDialog(
            source = source,
            targets = uiState.mergeTargetCandidates,
            onDismiss = viewModel::dismissSavedDialog,
            onTargetSelected = viewModel::mergeGroupInto
        )
    }

    if (uiState.isReorderMode) {
        BackHandler(onBack = viewModel::exitReorderMode)
    }
}

@Composable
private fun SavedLibrarySummaryCard(
    summary: SavedLibrarySummary,
    currentFilter: SavedLibraryFilter
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        colors = SurfaceDefaults.colors(containerColor = SurfaceElevated)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            AppSectionHeader(
                title = stringResource(R.string.favorites_overview_title),
                subtitle = stringResource(
                    R.string.favorites_overview_subtitle,
                    summary.totalItems,
                    summary.customGroupCount
                )
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SavedLibraryStatPill(
                    label = stringResource(R.string.search_live_tv),
                    value = summary.liveCount,
                    highlighted = currentFilter == SavedLibraryFilter.LIVE
                )
                SavedLibraryStatPill(
                    label = stringResource(R.string.search_movies),
                    value = summary.movieCount,
                    highlighted = currentFilter == SavedLibraryFilter.MOVIE
                )
                SavedLibraryStatPill(
                    label = stringResource(R.string.search_series),
                    value = summary.seriesCount,
                    highlighted = currentFilter == SavedLibraryFilter.SERIES
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusPill(label = stringResource(R.string.favorites_status_pill_format, stringResource(R.string.favorites_continue_short), summary.continueWatchingCount))
                StatusPill(label = stringResource(R.string.favorites_status_pill_format, stringResource(R.string.favorites_recent_short), summary.recentLiveCount))
            }
        }
    }
}

@Composable
private fun SavedLibraryStatPill(
    label: String,
    value: Int,
    highlighted: Boolean
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        colors = SurfaceDefaults.colors(
            containerColor = if (highlighted) Primary.copy(alpha = 0.18f) else SurfaceHighlight
        )
    ) {
        Text(
            text = "$label $value",
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelMedium,
            color = if (highlighted) Primary else OnSurface
        )
    }
}

@Composable
private fun FavoriteSectionHeader(section: FavoriteSectionUiModel) {
    Column(modifier = Modifier.padding(top = 6.dp, bottom = 4.dp)) {
        Text(
            text = section.title,
            style = MaterialTheme.typography.titleMedium,
            color = OnBackground
        )
        Text(
            text = section.subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = OnSurfaceDim
        )
    }
}

@Composable
private fun SavedHistoryRow(
    title: String,
    subtitle: String,
    items: List<SavedHistoryUiModel>,
    onItemClick: (SavedHistoryUiModel) -> Unit
) {
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val cardWidth = if (screenWidth < 700.dp) 180.dp else 250.dp
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = OnBackground
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = OnSurfaceDim
        )
        androidx.compose.foundation.lazy.LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 4.dp)
        ) {
            items(items, key = { "${it.history.contentType}:${it.history.contentId}" }) { item ->
                TvClickableSurface(
                    onClick = { onItemClick(item) },
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Surface,
                        focusedContainerColor = SurfaceHighlight,
                        contentColor = OnSurface,
                        focusedContentColor = OnSurface
                    ),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
                    modifier = Modifier.width(cardWidth)
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = OnSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = item.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurfaceDim,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SavedGroupManagementRow(
    groups: List<SavedGroupManagementUiModel>,
    onGroupClick: (SavedGroupManagementUiModel) -> Unit,
    onGroupLongClick: (SavedGroupManagementUiModel) -> Unit
) {
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val cardWidth = if (screenWidth < 700.dp) 180.dp else 240.dp
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.saved_group_manage_title),
            style = MaterialTheme.typography.titleMedium,
            color = OnBackground
        )
        Text(
            text = stringResource(R.string.saved_group_manage_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = OnSurfaceDim
        )
        androidx.compose.foundation.lazy.LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 4.dp)
        ) {
            items(groups, key = { "group-manage:${it.group.id}" }) { group ->
                TvClickableSurface(
                    onClick = { onGroupClick(group) },
                    onLongClick = { onGroupLongClick(group) },
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Surface,
                        focusedContainerColor = SurfaceHighlight
                    ),
                    modifier = Modifier.width(cardWidth)
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = group.group.name,
                            style = MaterialTheme.typography.titleSmall,
                            color = OnSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (group.isPromotedOnHome) {
                                stringResource(R.string.saved_group_manage_promoted, group.itemCount)
                            } else {
                                stringResource(R.string.saved_group_manage_count, group.itemCount)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurfaceDim
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SavedItemOptionsDialog(
    item: FavoriteUiModel,
    moveTargets: List<SavedGroupManagementUiModel>,
    onDismiss: () -> Unit,
    onRemove: () -> Unit,
    onMoveToGroup: (FavoriteUiModel, Long) -> Unit
) {
    PremiumDialog(
        title = item.title,
        subtitle = stringResource(R.string.library_saved_manage_hint),
        onDismissRequest = onDismiss,
        content = {
            PremiumDialogActionButton(
                label = if (item.favorite.groupId == null) {
                    stringResource(R.string.saved_item_remove_saved)
                } else {
                    stringResource(R.string.saved_item_remove_group)
                },
                onClick = onRemove,
                destructive = true
            )
            moveTargets.forEach { target ->
                PremiumDialogActionButton(
                    label = stringResource(R.string.saved_item_move_to, target.group.name),
                    onClick = { onMoveToGroup(item, target.group.id) }
                )
            }
        },
        footer = {
            PremiumDialogFooterButton(
                label = stringResource(R.string.settings_cancel),
                onClick = onDismiss
            )
        }
    )
}

@Composable
private fun SavedGroupOptionsDialog(
    group: SavedGroupManagementUiModel,
    canMerge: Boolean,
    onDismiss: () -> Unit,
    onReorder: () -> Unit,
    onTogglePromotion: (() -> Unit)?,
    onMerge: () -> Unit,
    onDelete: () -> Unit
) {
    PremiumDialog(
        title = group.group.name,
        subtitle = stringResource(R.string.saved_group_manage_subtitle),
        onDismissRequest = onDismiss,
        content = {
            if (group.itemCount > 1) {
                PremiumDialogActionButton(
                    label = stringResource(R.string.category_options_reorder),
                    onClick = onReorder
                )
            }
            onTogglePromotion?.let { toggle ->
                PremiumDialogActionButton(
                    label = if (group.isPromotedOnHome) {
                        stringResource(R.string.saved_group_demote_home)
                    } else {
                        stringResource(R.string.saved_group_promote_home)
                    },
                    onClick = toggle
                )
            }
            if (canMerge) {
                PremiumDialogActionButton(
                    label = stringResource(R.string.saved_group_merge),
                    onClick = onMerge
                )
            }
            PremiumDialogActionButton(
                label = if (group.itemCount == 0) {
                    stringResource(R.string.saved_group_delete_empty)
                } else {
                    stringResource(R.string.category_options_delete)
                },
                onClick = onDelete,
                destructive = true
            )
        },
        footer = {
            PremiumDialogFooterButton(
                label = stringResource(R.string.settings_cancel),
                onClick = onDismiss
            )
        }
    )
}

@Composable
private fun MergeSavedGroupDialog(
    source: SavedGroupManagementUiModel,
    targets: List<SavedGroupManagementUiModel>,
    onDismiss: () -> Unit,
    onTargetSelected: (Long) -> Unit
) {
    PremiumDialog(
        title = stringResource(R.string.saved_group_merge_title, source.group.name),
        subtitle = stringResource(R.string.saved_group_manage_subtitle),
        onDismissRequest = onDismiss,
        content = {
            targets.forEach { target ->
                PremiumDialogActionButton(
                    label = target.group.name,
                    onClick = { onTargetSelected(target.group.id) }
                )
            }
        },
        footer = {
            PremiumDialogFooterButton(
                label = stringResource(R.string.settings_cancel),
                onClick = onDismiss
            )
        }
    )
}

@Composable
private fun FavoriteRow(
    item: FavoriteUiModel,
    isReorderMode: Boolean,
    isReorderingThis: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    val scale by animateFloatAsState(if (isReorderingThis) 1.03f else 1f, label = "favoriteRowScale")

    TvClickableSurface(
        onClick = onClick,
        onLongClick = {
            if (!isReorderMode) {
                onLongClick()
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .scale(scale)
            .then(
                if (isReorderingThis) {
                    Modifier.onKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false

                        when (event.nativeKeyEvent.keyCode) {
                            android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                                onMoveUp()
                                true
                            }

                            android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                                onMoveDown()
                                true
                            }

                            android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                            android.view.KeyEvent.KEYCODE_ENTER -> {
                                onConfirm()
                                true
                            }

                            android.view.KeyEvent.KEYCODE_BACK -> {
                                onCancel()
                                true
                            }

                            else -> false
                        }
                    }
                } else {
                    Modifier
                }
            ),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (isReorderingThis) Primary.copy(alpha = 0.18f) else SurfaceElevated,
            focusedContainerColor = if (isReorderingThis) Primary else SurfaceHighlight,
            contentColor = if (isReorderingThis) Primary else OnSurface,
            focusedContentColor = if (isReorderingThis) OnPrimary else OnSurface
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = androidx.compose.foundation.BorderStroke(
                    width = if (isReorderingThis) 2.dp else 0.dp,
                    color = if (isReorderingThis) Primary else Color.Transparent
                )
            )
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isReorderingThis) Primary else OnSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!item.subtitle.isNullOrBlank()) {
                    Text(
                        text = item.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isReorderingThis) Primary else OnSurfaceDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (isReorderingThis) {
                Text(
                    text = stringResource(R.string.favorites_reordering),
                    style = MaterialTheme.typography.bodySmall,
                    color = Primary
                )
            }
        }
    }
}

@Composable
private fun ReorderPanel(
    section: FavoriteSectionUiModel?,
    movingFavoriteId: Long?
) {
    if (section == null) return

    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val panelWidth = if (screenWidth < 700.dp) 180.dp else 250.dp

    Column(
        modifier = Modifier
            .width(panelWidth)
            .heightIn(max = 300.dp)
            .background(
                color = Surface.copy(alpha = 0.97f),
                shape = RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp)
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = stringResource(R.string.favorites_reorder_mode),
            style = MaterialTheme.typography.titleSmall,
            color = Primary
        )
        Text(
            text = section.title,
            style = MaterialTheme.typography.bodySmall,
            color = OnSurfaceDim
        )

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ReorderHintRow(label = stringResource(R.string.favorites_move_up))
            ReorderHintRow(label = stringResource(R.string.favorites_move_down))
            ReorderHintRow(label = stringResource(R.string.favorites_confirm))
            ReorderHintRow(label = stringResource(R.string.favorites_cancel))
        }

        Column(
            modifier = Modifier
                .background(SurfaceElevated.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            section.items.take(10).forEach { item ->
                val isMoving = movingFavoriteId == item.favorite.id
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isMoving) Primary else OnSurfaceDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ReorderHintRow(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = OnSurfaceDim,
        modifier = Modifier.padding(vertical = 1.dp)
    )
}
