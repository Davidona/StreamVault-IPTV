package com.streamvault.feature.live.presentation.epg

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.streamvault.core.ui.interaction.TvClickableSurface
import com.streamvault.core.ui.theme.FocusBorder
import com.streamvault.core.ui.theme.OnSurface
import com.streamvault.core.ui.theme.OnSurfaceDim
import com.streamvault.core.ui.theme.Primary
import com.streamvault.core.ui.theme.SurfaceElevated
import com.streamvault.core.ui.theme.SurfaceHighlight
import com.streamvault.domain.model.Category

data class LiveGuideCategoryPickerLabels(
    val title: String,
    val cancel: String,
    val searchLabel: String,
    val searchPlaceholder: String,
    val clearSearch: String,
    val parentalControl: String,
    val matchesCount: (Int) -> String,
    val channelCount: (Int) -> String,
    val jumpNow: String
)

@Composable
fun LiveGuideCategoryPickerDialog(
    categories: List<Category>,
    selectedCategoryId: Long,
    labels: LiveGuideCategoryPickerLabels,
    isCategoryLocked: (Category) -> Boolean,
    onDismiss: () -> Unit,
    onCategorySelected: (Category) -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    var shouldFocusFirstCategory by rememberSaveable { mutableStateOf(true) }
    val searchFocusRequester = remember { FocusRequester() }
    val firstCategoryFocusRequester = remember { FocusRequester() }
    val filteredCategories = remember(categories, query) {
        val trimmed = query.trim()
        val baseCategories = if (trimmed.isBlank()) {
            categories
        } else {
            categories.filter { it.name.contains(trimmed, ignoreCase = true) }
        }
        val selectedCategory = baseCategories.firstOrNull { it.id == selectedCategoryId }
        buildList {
            if (selectedCategory != null) add(selectedCategory)
            addAll(baseCategories.filterNot { it.id == selectedCategoryId })
        }
    }

    LiveGuideCategoryPickerModal(onDismiss = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.52f)
                .fillMaxHeight(0.78f)
                .focusGroup(),
            colors = SurfaceDefaults.colors(containerColor = SurfaceElevated),
            shape = RoundedCornerShape(22.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = labels.title,
                            style = MaterialTheme.typography.headlineSmall,
                            color = OnSurface
                        )
                        Text(
                            text = labels.matchesCount(filteredCategories.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurfaceDim
                        )
                    }
                    LiveGuideShortcutChip(
                        label = labels.cancel,
                        onClick = onDismiss
                    )
                }

                LiveGuideProgramSearchRow(
                    query = query,
                    onQueryChange = { query = it },
                    onClear = { query = "" },
                    searchLabel = labels.searchLabel,
                    searchPlaceholder = labels.searchPlaceholder,
                    clearLabel = labels.clearSearch,
                    focusRequester = searchFocusRequester,
                    contentPadding = PaddingValues(0.dp),
                    showLabel = false,
                    onSearchFieldActivated = {
                        shouldFocusFirstCategory = false
                    }
                )

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    itemsIndexed(
                        items = filteredCategories,
                        key = { index, category -> liveGuideCategoryKey(category, index) }
                    ) { _, category ->
                        val isSelected = category.id == selectedCategoryId
                        val isLocked = isCategoryLocked(category)
                        TvClickableSurface(
                            onClick = { onCategorySelected(category) },
                            modifier = if (category.id == filteredCategories.firstOrNull()?.id) {
                                Modifier.focusRequester(firstCategoryFocusRequester)
                            } else {
                                Modifier
                            },
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = if (isSelected) Primary.copy(alpha = 0.18f) else SurfaceHighlight,
                                focusedContainerColor = if (isSelected) {
                                    Primary.copy(alpha = 0.22f)
                                } else {
                                    SurfaceHighlight
                                },
                                contentColor = if (isSelected) Primary else OnSurface,
                                focusedContentColor = if (isSelected) Primary else OnSurface
                            ),
                            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
                            border = ClickableSurfaceDefaults.border(
                                focusedBorder = Border(
                                    border = BorderStroke(2.dp, FocusBorder),
                                    shape = RoundedCornerShape(14.dp)
                                )
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        text = category.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = if (isSelected) Primary else OnSurface
                                    )
                                    if (isLocked) {
                                        Text(
                                            text = labels.parentalControl,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = OnSurfaceDim
                                        )
                                    } else if (category.count > 0) {
                                        Text(
                                            text = labels.channelCount(category.count),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = OnSurfaceDim
                                        )
                                    }
                                }
                                if (isSelected) {
                                    Text(
                                        text = labels.jumpNow,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = Primary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(filteredCategories, shouldFocusFirstCategory) {
        if (shouldFocusFirstCategory && filteredCategories.isNotEmpty()) {
            firstCategoryFocusRequester.requestFocus()
            shouldFocusFirstCategory = false
        }
    }
}

private fun liveGuideCategoryKey(category: Category, index: Int): String {
    return "category:${category.id}:${category.name.trim()}:$index"
}

@Composable
private fun LiveGuideCategoryPickerModal(
    onDismiss: () -> Unit,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = contentAlignment
        ) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Color.Black.copy(alpha = 0.68f))
                    .clickable(
                        onClick = onDismiss,
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    )
            )
            content()
        }
    }
}
