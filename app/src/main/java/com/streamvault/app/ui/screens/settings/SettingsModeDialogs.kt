package com.streamvault.app.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.app.R
import com.streamvault.app.ui.components.dialogs.PremiumDialog
import com.streamvault.app.ui.components.dialogs.PremiumDialogFooterButton
import com.streamvault.core.ui.interaction.TvClickableSurface
import com.streamvault.app.ui.model.VodViewMode
import com.streamvault.core.ui.theme.OnBackground
import com.streamvault.core.ui.theme.OnSurfaceDim
import com.streamvault.core.ui.theme.Primary
import com.streamvault.core.ui.theme.SurfaceElevated
import com.streamvault.domain.model.ChannelNumberingMode
import com.streamvault.domain.model.GroupedChannelLabelMode
import com.streamvault.domain.model.LiveChannelGroupingMode
import com.streamvault.domain.model.LiveVariantPreferenceMode
import com.streamvault.domain.model.RemoteColorButton
import com.streamvault.domain.model.RemoteShortcutProfile
import com.streamvault.domain.model.RemoteShortcutSelection
import com.streamvault.domain.model.VodDuplicateHandlingMode
import com.streamvault.domain.model.VodVariantPreferenceMode

@Composable
internal fun VodViewModeDialog(
    selectedMode: VodViewMode,
    onDismiss: () -> Unit,
    onModeSelected: (VodViewMode) -> Unit
) {
    PremiumDialog(
        title = stringResource(R.string.settings_vod_view_mode),
        subtitle = stringResource(R.string.settings_vod_view_mode_subtitle),
        onDismissRequest = onDismiss,
        widthFraction = 0.52f,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                VodViewMode.entries.forEach { mode ->
                    TvClickableSurface(
                        onClick = { onModeSelected(mode) },
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = if (mode == selectedMode) Primary.copy(alpha = 0.18f) else SurfaceElevated,
                            focusedContainerColor = Primary.copy(alpha = 0.28f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = stringResource(mode.labelResId()),
                                style = MaterialTheme.typography.titleSmall,
                                color = if (mode == selectedMode) Primary else OnBackground
                            )
                            Text(
                                text = stringResource(mode.descriptionResId()),
                                style = MaterialTheme.typography.bodySmall,
                                color = OnSurfaceDim
                            )
                        }
                    }
                }
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
internal fun LiveChannelNumberingModeDialog(
    selectedMode: ChannelNumberingMode,
    onDismiss: () -> Unit,
    onModeSelected: (ChannelNumberingMode) -> Unit
) {
    PremiumDialog(
        title = stringResource(R.string.settings_live_channel_numbering_mode),
        subtitle = stringResource(R.string.settings_live_channel_numbering_mode_subtitle),
        onDismissRequest = onDismiss,
        widthFraction = 0.52f,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ChannelNumberingMode.entries.forEach { mode ->
                    TvClickableSurface(
                        onClick = { onModeSelected(mode) },
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = if (mode == selectedMode) Primary.copy(alpha = 0.18f) else SurfaceElevated,
                            focusedContainerColor = Primary.copy(alpha = 0.28f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = stringResource(mode.labelResId()),
                                style = MaterialTheme.typography.titleSmall,
                                color = if (mode == selectedMode) Primary else OnBackground
                            )
                            Text(
                                text = stringResource(mode.descriptionResId()),
                                style = MaterialTheme.typography.bodySmall,
                                color = OnSurfaceDim
                            )
                        }
                    }
                }
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
internal fun LiveChannelGroupingModeDialog(
    selectedMode: LiveChannelGroupingMode,
    onDismiss: () -> Unit,
    onModeSelected: (LiveChannelGroupingMode) -> Unit
) {
    PremiumDialog(
        title = stringResource(R.string.settings_live_channel_grouping_mode),
        subtitle = stringResource(R.string.settings_live_channel_grouping_mode_subtitle),
        onDismissRequest = onDismiss,
        widthFraction = 0.52f,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                LiveChannelGroupingMode.entries.forEach { mode ->
                    TvClickableSurface(
                        onClick = { onModeSelected(mode) },
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = if (mode == selectedMode) Primary.copy(alpha = 0.18f) else SurfaceElevated,
                            focusedContainerColor = Primary.copy(alpha = 0.28f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = stringResource(mode.labelResId()),
                                style = MaterialTheme.typography.titleSmall,
                                color = if (mode == selectedMode) Primary else OnBackground
                            )
                            Text(
                                text = stringResource(mode.descriptionResId()),
                                style = MaterialTheme.typography.bodySmall,
                                color = OnSurfaceDim
                            )
                        }
                    }
                }
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
internal fun GroupedChannelLabelModeDialog(
    selectedMode: GroupedChannelLabelMode,
    onDismiss: () -> Unit,
    onModeSelected: (GroupedChannelLabelMode) -> Unit
) {
    PremiumDialog(
        title = stringResource(R.string.settings_grouped_channel_label_mode),
        subtitle = stringResource(R.string.settings_grouped_channel_label_mode_subtitle),
        onDismissRequest = onDismiss,
        widthFraction = 0.52f,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                GroupedChannelLabelMode.entries.forEach { mode ->
                    TvClickableSurface(
                        onClick = { onModeSelected(mode) },
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = if (mode == selectedMode) Primary.copy(alpha = 0.18f) else SurfaceElevated,
                            focusedContainerColor = Primary.copy(alpha = 0.28f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = stringResource(mode.labelResId()),
                                style = MaterialTheme.typography.titleSmall,
                                color = if (mode == selectedMode) Primary else OnBackground
                            )
                            Text(
                                text = stringResource(mode.descriptionResId()),
                                style = MaterialTheme.typography.bodySmall,
                                color = OnSurfaceDim
                            )
                        }
                    }
                }
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
internal fun VodDuplicateHandlingModeDialog(
    selectedMode: VodDuplicateHandlingMode,
    onDismiss: () -> Unit,
    onModeSelected: (VodDuplicateHandlingMode) -> Unit
) {
    PremiumDialog(
        title = stringResource(R.string.settings_vod_duplicate_handling_mode),
        subtitle = stringResource(R.string.settings_vod_duplicate_handling_mode_subtitle),
        onDismissRequest = onDismiss,
        widthFraction = 0.52f,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                VodDuplicateHandlingMode.entries.forEach { mode ->
                    TvClickableSurface(
                        onClick = { onModeSelected(mode) },
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = if (mode == selectedMode) Primary.copy(alpha = 0.18f) else SurfaceElevated,
                            focusedContainerColor = Primary.copy(alpha = 0.28f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = stringResource(mode.labelResId()),
                                style = MaterialTheme.typography.titleSmall,
                                color = if (mode == selectedMode) Primary else OnBackground
                            )
                            Text(
                                text = stringResource(mode.descriptionResId()),
                                style = MaterialTheme.typography.bodySmall,
                                color = OnSurfaceDim
                            )
                        }
                    }
                }
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
internal fun VodVariantPreferenceModeDialog(
    selectedMode: VodVariantPreferenceMode,
    onDismiss: () -> Unit,
    onModeSelected: (VodVariantPreferenceMode) -> Unit
) {
    PremiumDialog(
        title = stringResource(R.string.settings_vod_variant_preference_mode),
        subtitle = stringResource(R.string.settings_vod_variant_preference_mode_subtitle),
        onDismissRequest = onDismiss,
        widthFraction = 0.52f,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                VodVariantPreferenceMode.entries.forEach { mode ->
                    TvClickableSurface(
                        onClick = { onModeSelected(mode) },
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = if (mode == selectedMode) Primary.copy(alpha = 0.18f) else SurfaceElevated,
                            focusedContainerColor = Primary.copy(alpha = 0.28f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = stringResource(mode.labelResId()),
                                style = MaterialTheme.typography.titleSmall,
                                color = if (mode == selectedMode) Primary else OnBackground
                            )
                            Text(
                                text = stringResource(mode.descriptionResId()),
                                style = MaterialTheme.typography.bodySmall,
                                color = OnSurfaceDim
                            )
                        }
                    }
                }
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
internal fun LiveVariantPreferenceModeDialog(
    selectedMode: LiveVariantPreferenceMode,
    onDismiss: () -> Unit,
    onModeSelected: (LiveVariantPreferenceMode) -> Unit
) {
    PremiumDialog(
        title = stringResource(R.string.settings_live_variant_preference_mode),
        subtitle = stringResource(R.string.settings_live_variant_preference_mode_subtitle),
        onDismissRequest = onDismiss,
        widthFraction = 0.52f,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                LiveVariantPreferenceMode.entries.forEach { mode ->
                    TvClickableSurface(
                        onClick = { onModeSelected(mode) },
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = if (mode == selectedMode) Primary.copy(alpha = 0.18f) else SurfaceElevated,
                            focusedContainerColor = Primary.copy(alpha = 0.28f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = stringResource(mode.labelResId()),
                                style = MaterialTheme.typography.titleSmall,
                                color = if (mode == selectedMode) Primary else OnBackground
                            )
                            Text(
                                text = stringResource(mode.descriptionResId()),
                                style = MaterialTheme.typography.bodySmall,
                                color = OnSurfaceDim
                            )
                        }
                    }
                }
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
internal fun RemoteShortcutSelectionDialog(
    target: RemoteShortcutDialogTarget,
    selectedSelection: RemoteShortcutSelection,
    onDismiss: () -> Unit,
    onSelection: (RemoteShortcutSelection) -> Unit
) {
    PremiumDialog(
        title = stringResource(R.string.settings_remote_dialog_title, stringResource(target.button.labelResId())),
        subtitle = stringResource(R.string.settings_remote_dialog_subtitle, stringResource(target.profile.labelResId())),
        onDismissRequest = onDismiss,
        widthFraction = 0.56f,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                remoteShortcutSelectionOptions(target.profile).forEach { selection ->
                    val isSelected = selection == selectedSelection.normalizedForProfile(target.profile)
                    TvClickableSurface(
                        onClick = { onSelection(selection) },
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = if (isSelected) Primary.copy(alpha = 0.18f) else SurfaceElevated,
                            focusedContainerColor = Primary.copy(alpha = 0.28f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = formatRemoteShortcutSelectionLabel(
                                    selection = selection,
                                    profile = target.profile,
                                    button = target.button,
                                    context = androidx.compose.ui.platform.LocalContext.current
                                ),
                                style = MaterialTheme.typography.titleSmall,
                                color = if (isSelected) Primary else OnBackground
                            )
                        }
                    }
                }
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
