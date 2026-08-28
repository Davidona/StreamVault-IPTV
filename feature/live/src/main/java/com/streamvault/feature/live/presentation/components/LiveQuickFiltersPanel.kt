package com.streamvault.feature.live.presentation.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.core.ui.interaction.TvButton
import com.streamvault.core.ui.interaction.TvClickableSurface
import com.streamvault.core.ui.theme.OnSurface
import com.streamvault.core.ui.theme.OnSurfaceDim
import com.streamvault.core.ui.theme.Primary
import com.streamvault.core.ui.theme.SurfaceElevated
import com.streamvault.core.ui.theme.SurfaceHighlight

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
            .padding(bottom = 10.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = SurfaceElevated,
            focusedContainerColor = SurfaceHighlight.copy(alpha = 0.9f)
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, Primary.copy(alpha = 0.85f)),
                shape = RoundedCornerShape(12.dp)
            )
        ),
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
                Text(text = buttonTitle, style = MaterialTheme.typography.labelLarge, color = OnSurface)
                Text(
                    text = if (showDrawer) hideLabel else showLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = Primary
                )
            }
            Text(
                text = filterSubtitle,
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceDim
            )
        }
    }

    if (!showDrawer) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
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
