package com.streamvault.feature.live.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.core.ui.components.SearchInput
import com.streamvault.core.ui.theme.OnSurface
import com.streamvault.domain.model.ActiveLiveSource
import com.streamvault.domain.model.ActiveLiveSourceOption
import com.streamvault.domain.model.LiveTvQuickFilterVisibilityMode
import com.streamvault.feature.live.presentation.components.LiveQuickFiltersPanel
import com.streamvault.feature.live.presentation.components.LiveSourceSwitcher

@Composable
fun LiveCategorySidebarHeader(
    title: String,
    currentSource: ActiveLiveSource?,
    sourceOptions: List<ActiveLiveSourceOption>,
    showSourceSwitcher: Boolean,
    onSourceSelected: (ActiveLiveSource) -> Unit,
    categorySearchQuery: String,
    onCategorySearchQueryChanged: (String) -> Unit,
    categorySearchFocusRequester: FocusRequester,
    categorySearchPlaceholder: String,
    quickFilterVisibilityMode: LiveTvQuickFilterVisibilityMode,
    savedCategoryFilters: List<String>,
    activeCategoryFilter: String?,
    hiddenCategoriesButtonLabel: String?,
    hiddenChannelsButtonLabel: String?,
    quickFiltersButtonTitle: String,
    quickFiltersTitle: String,
    quickFiltersShowLabel: String,
    quickFiltersHideLabel: String,
    quickFiltersShowingAllLabel: String,
    quickFiltersActiveLabel: String,
    quickFiltersManualSearchLabel: String,
    quickFiltersAllLabel: String,
    quickFiltersEmptyLabel: String,
    quickFiltersAddChipLabel: String,
    noSourceLabel: String,
    selectedLabel: String,
    unavailableLabel: String,
    isReorderMode: Boolean,
    onShowHiddenCategories: () -> Unit,
    onShowHiddenChannels: () -> Unit,
    onAddFilter: () -> Unit,
    onAllSelected: () -> Unit,
    onSavedFilterSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp, start = 4.dp, end = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = OnSurface,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            if (showSourceSwitcher) {
                Spacer(modifier = Modifier.width(8.dp))
                LiveSourceSwitcher(
                    currentSource = currentSource,
                    options = sourceOptions,
                    onSourceSelected = onSourceSelected,
                    compact = true,
                    noSourceLabel = noSourceLabel,
                    selectedLabel = selectedLabel,
                    unavailableLabel = unavailableLabel
                )
            }
        }
        SearchInput(
            value = categorySearchQuery,
            onValueChange = { if (!isReorderMode) onCategorySearchQueryChanged(it) },
            placeholder = categorySearchPlaceholder,
            focusRequester = categorySearchFocusRequester,
            modifier = Modifier.padding(bottom = 10.dp),
            enabled = !isReorderMode
        )

        val shouldShowQuickFiltersControl = when (quickFilterVisibilityMode) {
            LiveTvQuickFilterVisibilityMode.HIDE -> false
            LiveTvQuickFilterVisibilityMode.SHOW_WHEN_FILTERS_AVAILABLE -> savedCategoryFilters.isNotEmpty()
            LiveTvQuickFilterVisibilityMode.ALWAYS_VISIBLE -> true
        }
        if (shouldShowQuickFiltersControl) {
            val filterSubtitle = when {
                categorySearchQuery.isBlank() -> quickFiltersShowingAllLabel
                activeCategoryFilter != null -> quickFiltersActiveLabel
                else -> quickFiltersManualSearchLabel
            }
            LiveQuickFiltersPanel(
                savedFilters = savedCategoryFilters,
                categorySearchQuery = categorySearchQuery,
                activeFilter = activeCategoryFilter,
                hiddenCategoriesButtonLabel = hiddenCategoriesButtonLabel,
                hiddenChannelsButtonLabel = hiddenChannelsButtonLabel,
                buttonTitle = quickFiltersButtonTitle,
                chipTitle = quickFiltersTitle,
                showLabel = quickFiltersShowLabel,
                hideLabel = quickFiltersHideLabel,
                filterSubtitle = filterSubtitle,
                allLabel = quickFiltersAllLabel,
                emptyLabel = quickFiltersEmptyLabel,
                addChipLabel = quickFiltersAddChipLabel,
                isReorderMode = isReorderMode,
                onShowHiddenCategories = onShowHiddenCategories,
                onShowHiddenChannels = onShowHiddenChannels,
                onAddFilter = onAddFilter,
                onAllSelected = onAllSelected,
                onSavedFilterSelected = onSavedFilterSelected
            )
        }
    }
}
