package com.streamvault.app.ui.screens.settings

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
import com.streamvault.app.ui.interaction.TvButton
import com.streamvault.app.ui.interaction.TvClickableSurface
import com.streamvault.app.ui.design.FocusSpec
import com.streamvault.app.ui.theme.FocusBorder
import com.streamvault.app.ui.theme.OnSurface
import com.streamvault.app.ui.theme.OnSurfaceDim
import com.streamvault.app.ui.theme.Primary
import com.streamvault.domain.model.AppTopLevelDestination

@Composable
internal fun TopNavigationDialog(
    currentDestinations: List<AppTopLevelDestination>,
    onDismiss: () -> Unit,
    onSave: (List<AppTopLevelDestination>) -> Unit
) {
    var draftDestinations by remember(currentDestinations) {
        mutableStateOf(AppTopLevelDestination.normalizeForStorage(currentDestinations))
    }
    val displayDestinations = remember(draftDestinations) {
        val hidden = AppTopLevelDestination.defaultOrder.filterNot { it in draftDestinations }
        draftDestinations + hidden
    }

    PremiumDialog(
        title = stringResource(R.string.settings_top_navigation_dialog_title),
        subtitle = stringResource(R.string.settings_top_navigation_dialog_subtitle),
        onDismissRequest = onDismiss,
        widthFraction = 0.58f,
        bodyHeightFraction = 0.62f,
        content = {
            Text(
                text = stringResource(R.string.settings_top_navigation_landing_note),
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceDim
            )
            displayDestinations.forEachIndexed { index, destination ->
                TopNavigationDialogRow(
                    destination = destination,
                    enabled = destination in draftDestinations,
                    canMoveUp = index > 0,
                    canMoveDown = index < displayDestinations.lastIndex,
                    onEnabledChange = { enabled ->
                        if (!destination.isRequired) {
                            draftDestinations = if (enabled) {
                                AppTopLevelDestination.normalizeForStorage(
                                    displayDestinations
                                        .filter { item -> item == destination || item in draftDestinations }
                                )
                            } else {
                                AppTopLevelDestination.normalizeForStorage(
                                    draftDestinations.filterNot { it == destination }
                                )
                            }
                        }
                    },
                    onMoveUp = {
                        val reordered = displayDestinations.toMutableList().also { items ->
                            items[index] = items[index - 1]
                            items[index - 1] = destination
                        }
                        draftDestinations = AppTopLevelDestination.normalizeForStorage(
                            reordered.filter { it in draftDestinations || it.isRequired }
                        )
                    },
                    onMoveDown = {
                        val reordered = displayDestinations.toMutableList().also { items ->
                            items[index] = items[index + 1]
                            items[index + 1] = destination
                        }
                        draftDestinations = AppTopLevelDestination.normalizeForStorage(
                            reordered.filter { it in draftDestinations || it.isRequired }
                        )
                    }
                )
            }
        },
        footer = {
            PremiumDialogFooterButton(
                label = stringResource(R.string.settings_cancel),
                onClick = onDismiss
            )
            PremiumDialogFooterButton(
                label = stringResource(R.string.action_save_order),
                emphasized = true,
                onClick = { onSave(draftDestinations) }
            )
        }
    )
}

@Composable
private fun TopNavigationDialogRow(
    destination: AppTopLevelDestination,
    enabled: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
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
                    text = stringResource(destination.labelResId()),
                    style = MaterialTheme.typography.titleSmall,
                    color = OnSurface
                )
                Text(
                    text = if (destination.isRequired) {
                        stringResource(R.string.settings_top_navigation_required)
                    } else if (enabled) {
                        stringResource(R.string.settings_top_navigation_visible)
                    } else {
                        stringResource(R.string.settings_top_navigation_hidden)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (destination.isRequired) Primary else OnSurfaceDim
                )
            }
            NavigationVisibilityToggle(
                enabled = enabled,
                required = destination.isRequired,
                onToggle = { onEnabledChange(!enabled) }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TvButton(
                    enabled = canMoveUp,
                    onClick = onMoveUp
                ) {
                    Text(stringResource(R.string.settings_top_navigation_move_up))
                }
                TvButton(
                    enabled = canMoveDown,
                    onClick = onMoveDown
                ) {
                    Text(stringResource(R.string.settings_top_navigation_move_down))
                }
            }
        }
    }
}

@Composable
private fun NavigationVisibilityToggle(
    enabled: Boolean,
    required: Boolean,
    onToggle: () -> Unit
) {
    TvClickableSurface(
        onClick = onToggle,
        enabled = !required,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (enabled) Primary.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.05f),
            focusedContainerColor = if (enabled) Primary.copy(alpha = 0.24f) else Color.White.copy(alpha = 0.1f),
            contentColor = OnSurface,
            focusedContentColor = OnSurface
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)),
                shape = RoundedCornerShape(12.dp)
            ),
            focusedBorder = Border(
                border = BorderStroke(FocusSpec.BorderWidth, FocusBorder),
                shape = RoundedCornerShape(12.dp)
            )
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .background(
                        color = if (enabled) Primary else OnSurfaceDim,
                        shape = RoundedCornerShape(999.dp)
                    )
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(
                    text = if (enabled) {
                        stringResource(R.string.settings_top_navigation_visible)
                    } else {
                        stringResource(R.string.settings_top_navigation_hidden)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (enabled) Color.Black else OnSurface
                )
            }
        }
    }
}

@Composable
private fun AppTopLevelDestination.labelResId(): Int = when (this) {
    AppTopLevelDestination.HOME -> R.string.nav_home
    AppTopLevelDestination.LIVE_TV -> R.string.nav_live_tv
    AppTopLevelDestination.MOVIES -> R.string.nav_movies
    AppTopLevelDestination.SERIES -> R.string.nav_series
    AppTopLevelDestination.DOWNLOADS -> R.string.nav_downloads
    AppTopLevelDestination.GUIDE -> R.string.nav_epg
    AppTopLevelDestination.SEARCH -> R.string.search_title
    AppTopLevelDestination.PLUGINS -> R.string.nav_plugins
    AppTopLevelDestination.SETTINGS -> R.string.nav_settings
}
