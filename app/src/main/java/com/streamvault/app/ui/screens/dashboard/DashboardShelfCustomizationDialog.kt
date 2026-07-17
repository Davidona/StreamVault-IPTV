package com.streamvault.app.ui.screens.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.app.R
import com.streamvault.app.ui.components.dialogs.PremiumDialog
import com.streamvault.app.ui.components.dialogs.PremiumDialogFooterButton
import com.streamvault.app.ui.design.FocusSpec
import com.streamvault.app.ui.interaction.TvButton
import com.streamvault.app.ui.interaction.TvClickableSurface
import com.streamvault.app.ui.theme.OnSurface
import com.streamvault.app.ui.theme.OnSurfaceDim
import com.streamvault.app.ui.theme.Primary
import com.streamvault.domain.model.AppHomeDashboardShelf

@Composable
internal fun DashboardShelfCustomizationDialog(
    currentShelves: List<AppHomeDashboardShelf>,
    onDismiss: () -> Unit,
    onSave: (List<AppHomeDashboardShelf>) -> Unit
) {
    var draftShelves by remember(currentShelves) {
        mutableStateOf(AppHomeDashboardShelf.normalizeForStorage(currentShelves))
    }
    val enabledShelves = remember(draftShelves) {
        AppHomeDashboardShelf.normalizeForStorage(draftShelves)
    }
    val availableShelves = remember(enabledShelves) {
        AppHomeDashboardShelf.catalogOrder.filterNot { it in enabledShelves }
    }

    PremiumDialog(
        title = stringResource(R.string.settings_home_dashboard_dialog_title),
        subtitle = stringResource(R.string.settings_home_dashboard_dialog_subtitle),
        onDismissRequest = onDismiss,
        widthFraction = 0.62f,
        bodyHeightFraction = 0.72f,
        content = {
            DashboardShelfSectionTitle(stringResource(R.string.settings_home_dashboard_enabled_section))
            if (enabledShelves.isEmpty()) {
                Text(
                    text = stringResource(R.string.settings_home_dashboard_enabled_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceDim,
                    modifier = Modifier.padding(bottom = 10.dp)
                )
            }
            enabledShelves.forEachIndexed { index, shelf ->
                DashboardShelfCustomizationRow(
                    shelf = shelf,
                    isEnabled = true,
                    canMoveUp = index > 0,
                    canMoveDown = index < enabledShelves.lastIndex,
                    onPrimaryAction = {
                        draftShelves = enabledShelves.filterNot { it == shelf }
                    },
                    onMoveUp = {
                        draftShelves = enabledShelves.toMutableList().also { items ->
                            items[index] = items[index - 1]
                            items[index - 1] = shelf
                        }
                    },
                    onMoveDown = {
                        draftShelves = enabledShelves.toMutableList().also { items ->
                            items[index] = items[index + 1]
                            items[index + 1] = shelf
                        }
                    }
                )
            }

            DashboardShelfSectionTitle(
                text = stringResource(R.string.settings_home_dashboard_available_section),
                modifier = Modifier.padding(top = 12.dp)
            )
            availableShelves.forEach { shelf ->
                DashboardShelfCustomizationRow(
                    shelf = shelf,
                    isEnabled = false,
                    canMoveUp = false,
                    canMoveDown = false,
                    onPrimaryAction = {
                        draftShelves = enabledShelves + shelf
                    },
                    onMoveUp = {},
                    onMoveDown = {}
                )
            }
        },
        footer = {
            PremiumDialogFooterButton(
                label = stringResource(R.string.settings_cancel),
                onClick = onDismiss
            )
            PremiumDialogFooterButton(
                label = stringResource(R.string.settings_reset),
                onClick = { draftShelves = AppHomeDashboardShelf.defaultOrder }
            )
            PremiumDialogFooterButton(
                label = stringResource(R.string.action_save_order),
                emphasized = true,
                onClick = { onSave(enabledShelves) }
            )
        }
    )
}

@Composable
private fun DashboardShelfSectionTitle(
    text: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = OnSurface,
        modifier = modifier.padding(bottom = 8.dp)
    )
}

@Composable
private fun DashboardShelfCustomizationRow(
    shelf: AppHomeDashboardShelf,
    isEnabled: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onPrimaryAction: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = stringResource(shelf.labelResId()),
                    style = MaterialTheme.typography.titleSmall,
                    color = OnSurface
                )
                Text(
                    text = stringResource(
                        if (isEnabled) R.string.settings_top_navigation_visible
                        else R.string.settings_top_navigation_hidden
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isEnabled) Primary else OnSurfaceDim
                )
            }
            DashboardShelfActionChip(
                label = stringResource(
                    if (isEnabled) R.string.settings_home_dashboard_remove
                    else R.string.settings_home_dashboard_add
                ),
                emphasized = isEnabled,
                onClick = onPrimaryAction
            )
            if (isEnabled) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TvButton(enabled = canMoveUp, onClick = onMoveUp) {
                        Text(stringResource(R.string.settings_top_navigation_move_up))
                    }
                    TvButton(enabled = canMoveDown, onClick = onMoveDown) {
                        Text(stringResource(R.string.settings_top_navigation_move_down))
                    }
                }
            }
        }
    }
}

@Composable
private fun DashboardShelfActionChip(
    label: String,
    emphasized: Boolean,
    onClick: () -> Unit
) {
    TvClickableSurface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (emphasized) Primary.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.05f),
            focusedContainerColor = if (emphasized) Primary.copy(alpha = 0.24f) else Color.White.copy(alpha = 0.1f),
            contentColor = OnSurface,
            focusedContentColor = OnSurface
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)),
                shape = RoundedCornerShape(12.dp)
            ),
            focusedBorder = Border(
                border = BorderStroke(FocusSpec.BorderWidth, Color.White),
                shape = RoundedCornerShape(12.dp)
            )
        )
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
        )
    }
}

@Composable
private fun AppHomeDashboardShelf.labelResId(): Int = when (this) {
    AppHomeDashboardShelf.FAVORITE_CHANNELS -> R.string.dashboard_favorite_channels
    AppHomeDashboardShelf.RECENT_CHANNELS -> R.string.dashboard_recent_channels
    AppHomeDashboardShelf.LIVE_SHORTCUTS -> R.string.dashboard_live_shortcuts
    AppHomeDashboardShelf.CONTINUE_WATCHING -> R.string.dashboard_continue_watching
    AppHomeDashboardShelf.RECENT_MOVIES -> R.string.dashboard_recent_movies
    AppHomeDashboardShelf.RECENT_SERIES -> R.string.dashboard_recent_series
    AppHomeDashboardShelf.PINNED_CATEGORIES -> R.string.dashboard_pinned_categories
    AppHomeDashboardShelf.FAVORITE_MOVIES -> R.string.dashboard_favorite_movies
    AppHomeDashboardShelf.FAVORITE_SERIES -> R.string.dashboard_favorite_series
    AppHomeDashboardShelf.CONTINUE_WATCHING_MOVIES -> R.string.dashboard_continue_watching_movies
    AppHomeDashboardShelf.CONTINUE_WATCHING_SERIES -> R.string.dashboard_continue_watching_series
    AppHomeDashboardShelf.TOP_RATED_MOVIES -> R.string.dashboard_top_rated_movies
    AppHomeDashboardShelf.RECOMMENDED_MOVIES -> R.string.dashboard_recommended_movies
}
