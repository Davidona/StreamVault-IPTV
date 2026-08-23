package com.streamvault.app.ui.components.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.streamvault.app.device.rememberIsTelevisionDevice
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.streamvault.core.ui.design.AppColors
import com.streamvault.app.ui.components.ChipRowItem
import com.streamvault.app.ui.components.ChipRowSection
import com.streamvault.core.ui.design.AppColors.Brand as Primary
import com.streamvault.core.ui.design.AppColors.Focus as FocusBorder
import com.streamvault.core.ui.design.AppColors.SurfaceElevated as SurfaceElevated
import com.streamvault.core.ui.design.AppColors.SurfaceEmphasis as SurfaceHighlight
import com.streamvault.core.ui.design.AppColors.TextPrimary as TextPrimary
import com.streamvault.core.ui.design.AppColors.TextTertiary as OnSurfaceDim
import androidx.compose.foundation.BorderStroke
import com.streamvault.app.R
import com.streamvault.app.ui.components.SearchInput
import com.streamvault.app.ui.components.SelectionChip
import com.streamvault.app.ui.components.SelectionChipRow
import com.streamvault.app.ui.components.dialogs.PremiumDialog
import com.streamvault.app.ui.components.dialogs.PremiumDialogFooterButton
import com.streamvault.app.ui.interaction.TvClickableSurface
import com.streamvault.app.ui.interaction.TvButton
import com.streamvault.app.ui.interaction.TvIconButton

@Composable
fun VodSectionHeader(
    title: String,
    onSeeAll: (() -> Unit)? = null,
    seeAllLabel: String = stringResource(R.string.action_see_all)
) {
    AppSectionHeader(
        title = title,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 8.dp),
        actionLabel = onSeeAll?.let { seeAllLabel },
        onActionClick = onSeeAll,
        actionContentColor = AppColors.TextSecondary
    )
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun VodActionChipRow(
    actions: List<VodActionChip>,
    selectedKey: String? = null,
    modifier: Modifier = Modifier
) {
    ChipRowSection(
        chips = actions.map { action ->
            ChipRowItem(
                key = action.key,
                label = action.label,
                supportingText = action.detail,
                onClick = action.onClick
            )
        },
        selectedKey = selectedKey,
        modifier = modifier.focusRestorer(),
        contentPadding = PaddingValues(horizontal = 20.dp),
        chipHorizontalPadding = 14,
        supportingTextStyle = MaterialTheme.typography.labelSmall,
        supportingTextMaxLines = 1,
        focusedContainerBoostWhenSelected = true
    )
}

@Composable
fun VodHeroStrip(
    title: String,
    subtitle: String,
    actionLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    TvClickableSurface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .height(132.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(22.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = SurfaceHighlight,
            focusedContainerColor = Primary.copy(alpha = 0.22f)
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, FocusBorder),
                shape = RoundedCornerShape(22.dp)
            )
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceDim,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Surface(
                shape = RoundedCornerShape(999.dp),
                colors = SurfaceDefaults.colors(containerColor = Primary)
            ) {
                Text(
                    text = actionLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.Black,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }
        }
    }
}

data class VodActionChip(
    val key: String,
    val label: String,
    val detail: String? = null,
    val onClick: () -> Unit
)

data class VodCategoryOption(
    val name: String,
    val count: Int,
    val onClick: () -> Unit,
    val onLongClick: (() -> Unit)? = null,
    val isLocked: Boolean = false
)

@Composable
fun VodCategoryPickerDialog(
    title: String,
    subtitle: String,
    categories: List<VodCategoryOption>,
    onDismiss: () -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    val searchFocusRequester = remember { FocusRequester() }
    val filteredCategories = remember(categories, query) {
        val normalized = query.trim()
        if (normalized.isBlank()) categories
        else categories.filter { it.name.contains(normalized, ignoreCase = true) }
    }

    PremiumDialog(
        title = title,
        subtitle = subtitle,
        onDismissRequest = onDismiss,
        widthFraction = 0.52f,
        content = {
            SearchInput(
                value = query,
                onValueChange = { query = it },
                placeholder = stringResource(R.string.vod_category_picker_search_placeholder),
                focusRequester = searchFocusRequester,
                modifier = Modifier.fillMaxWidth()
            )

            if (filteredCategories.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.vod_category_picker_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurfaceDim
                    )
                }
            } else {
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    val isTelevisionDevice = rememberIsTelevisionDevice()
                    val listHeight = when {
                        maxWidth < 700.dp -> 300.dp
                        !isTelevisionDevice && maxWidth < 1280.dp -> 360.dp
                        else -> 420.dp
                    }

                    LazyColumn(
                        modifier = Modifier.height(listHeight),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filteredCategories, key = { it.name }) { category ->
                            TvClickableSurface(
                                onClick = {
                                    category.onClick()
                                    onDismiss()
                                },
                                onLongClick = category.onLongClick?.let { action ->
                                    {
                                        action()
                                        onDismiss()
                                    }
                                },
                                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
                                colors = ClickableSurfaceDefaults.colors(
                                    containerColor = SurfaceElevated,
                                    focusedContainerColor = SurfaceHighlight
                                ),
                                border = ClickableSurfaceDefaults.border(
                                    focusedBorder = Border(
                                        border = BorderStroke(2.dp, FocusBorder),
                                        shape = RoundedCornerShape(14.dp)
                                    )
                                ),
                                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = category.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = TextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (category.isLocked) {
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = stringResource(R.string.home_locked_short),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Primary
                                        )
                                    }
                                    if (category.count > 0) {
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = category.count.toString(),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = OnSurfaceDim
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        footer = {
            PremiumDialogFooterButton(
                label = stringResource(R.string.category_options_cancel),
                onClick = onDismiss
            )
        }
    )
}

@Composable
fun VodBrowseOptionsDialog(
    title: String,
    filterTitle: String,
    filterChips: List<SelectionChip>,
    selectedFilterKey: String,
    onFilterSelected: (String) -> Unit,
    sortTitle: String,
    sortChips: List<SelectionChip>,
    selectedSortKey: String,
    onSortSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    PremiumDialog(
        title = title,
        onDismissRequest = onDismiss,
        widthFraction = 0.6f,
        heightFraction = null,
        bodyHeightFraction = 0.44f,
        content = {
            VodBrowseOptionsSection(
                title = filterTitle,
                chips = filterChips,
                selectedKey = selectedFilterKey,
                onChipSelected = onFilterSelected
            )
            VodBrowseOptionsSection(
                title = sortTitle,
                chips = sortChips,
                selectedKey = selectedSortKey,
                onChipSelected = onSortSelected
            )
        },
        footer = {
            PremiumDialogFooterButton(
                label = stringResource(R.string.category_options_cancel),
                onClick = onDismiss
            )
        }
    )
}

@Composable
private fun VodBrowseOptionsSection(
    title: String,
    chips: List<SelectionChip>,
    selectedKey: String,
    onChipSelected: (String) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = AppColors.TextTertiary
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            chips.forEach { chip ->
                VodBrowseOptionChip(
                    label = chip.label,
                    supportingText = chip.supportingText,
                    selected = chip.key == selectedKey,
                    onClick = { onChipSelected(chip.key) }
                )
            }
        }
    }
}

@Composable
private fun VodBrowseOptionChip(
    label: String,
    supportingText: String?,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Primary.copy(alpha = 0.18f) else SurfaceElevated,
            focusedContainerColor = if (selected) Primary.copy(alpha = 0.28f) else SurfaceHighlight,
            contentColor = if (selected) Primary else TextPrimary,
            focusedContentColor = TextPrimary
        ),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(999.dp)),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, FocusBorder),
                shape = RoundedCornerShape(999.dp)
            )
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            supportingText?.takeIf { it.isNotBlank() }?.let { detail ->
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
