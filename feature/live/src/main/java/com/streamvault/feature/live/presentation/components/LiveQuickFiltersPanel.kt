package com.streamvault.feature.live.presentation.components

import com.streamvault.core.ui.design.BrowseFocusSpec
import androidx.compose.ui.text.font.FontWeight
import com.streamvault.core.ui.design.selectableFocusBorder
import com.streamvault.core.ui.design.selectableSurfaceColors
import com.streamvault.core.ui.design.BrowseColors
import com.streamvault.core.ui.design.BrowseShapes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.core.ui.interaction.TvButton
import com.streamvault.core.ui.interaction.TvClickableSurface
import com.streamvault.core.ui.design.browse.OnSurfaceDim

const val LIVE_ALL_CATEGORY_FILTER_KEY = "__all_categories__"

fun liveQuickFilterKeys(savedFilters: List<String>): List<String> = buildList {
    add(LIVE_ALL_CATEGORY_FILTER_KEY)
    addAll(savedFilters)
}

fun liveQuickFilterSelection(activeFilter: String?, categorySearchQuery: String): String? =
    activeFilter ?: LIVE_ALL_CATEGORY_FILTER_KEY.takeIf { categorySearchQuery.isBlank() }

@Composable
fun LiveQuickFiltersPanel(
    savedFilters: List<String>,
    categorySearchQuery: String,
    activeFilter: String?,
    hiddenCategoriesButtonLabel: String?,
    hiddenChannelsButtonLabel: String?,
    buttonTitle: String,
    chipTitle: String,
    showLabel: String,
    hideLabel: String,
    filterSubtitle: String,
    allLabel: String,
    emptyLabel: String,
    addChipLabel: String,
    isReorderMode: Boolean,
    onShowHiddenCategories: () -> Unit,
    onShowHiddenChannels: () -> Unit,
    onAddFilter: () -> Unit,
    onAllSelected: () -> Unit,
    onSavedFilterSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDrawer by rememberSaveable(savedFilters) { mutableStateOf(false) }
    val hasFilterContent = savedFilters.isNotEmpty() || categorySearchQuery.isNotBlank()

    TvClickableSurface(
        onClick = { if (!isReorderMode) showDrawer = !showDrawer },
        enabled = !isReorderMode,
        modifier = modifier
            .fillMaxWidth()
            // Inset by the ring width so the focus ring is not clipped by the sidebar bounds.
            .padding(horizontal = BrowseFocusSpec.SelectableRingWidth)
            .padding(bottom = 10.dp),
        shape = ClickableSurfaceDefaults.shape(BrowseShapes.Artwork),
        // Tertiary row: no fill at rest so it does not read as another text input next to the search field.
        colors = selectableSurfaceColors(selected = false),
        border = selectableFocusBorder(BrowseShapes.Artwork),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = buttonTitle, style = MaterialTheme.typography.labelLarge, color = BrowseColors.TextPrimary)
                Text(
                    text = if (showDrawer) hideLabel else showLabel,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text(
                text = filterSubtitle,
                style = MaterialTheme.typography.bodySmall,
                color = BrowseColors.TextSecondary
            )
        }
    }

    if (!showDrawer) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = BrowseFocusSpec.SelectableRingWidth)
            .padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        hiddenCategoriesButtonLabel?.let { label ->
            TvButton(
                onClick = onShowHiddenCategories,
                enabled = !isReorderMode,
                modifier = Modifier.fillMaxWidth()
            ) { Text(text = label) }
        }
        hiddenChannelsButtonLabel?.let { label ->
            TvButton(
                onClick = onShowHiddenChannels,
                enabled = !isReorderMode,
                modifier = Modifier.fillMaxWidth()
            ) { Text(text = label) }
        }
        TvButton(
            onClick = onAddFilter,
            enabled = !isReorderMode,
            modifier = Modifier.fillMaxWidth()
        ) { Text(text = addChipLabel) }

        if (hasFilterContent) {
            LiveSelectionChipRow(
                title = chipTitle,
                chips = buildList {
                    add(LiveSelectionChip(LIVE_ALL_CATEGORY_FILTER_KEY, allLabel))
                    addAll(savedFilters.map { filter -> LiveSelectionChip(filter, filter) })
                },
                selectedKey = liveQuickFilterSelection(activeFilter, categorySearchQuery),
                onChipSelected = { key ->
                    if (!isReorderMode) {
                        if (key == LIVE_ALL_CATEGORY_FILTER_KEY) onAllSelected() else onSavedFilterSelected(key)
                        showDrawer = false
                    }
                },
                modifier = Modifier.padding(bottom = 10.dp),
                contentPadding = PaddingValues(horizontal = 0.dp)
            )
        } else {
            Text(
                text = emptyLabel,
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceDim,
                modifier = Modifier.padding(start = 4.dp, bottom = 10.dp)
            )
        }
    }
}
