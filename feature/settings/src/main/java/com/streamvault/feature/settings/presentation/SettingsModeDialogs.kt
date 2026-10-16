package com.streamvault.feature.settings.presentation

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.streamvault.domain.model.ChannelNumberingMode
import com.streamvault.domain.model.ExternalPlaybackMode
import com.streamvault.domain.model.GroupedChannelLabelMode
import com.streamvault.domain.model.LiveChannelGroupingMode
import com.streamvault.domain.model.LiveVariantPreferenceMode
import com.streamvault.domain.model.RemoteShortcutSelection
import com.streamvault.domain.model.VodDuplicateHandlingMode
import com.streamvault.domain.model.VodVariantPreferenceMode
import com.streamvault.domain.model.VodViewMode
import com.streamvault.feature.settings.R

@Composable
public fun VodViewModeDialog(
    selectedMode: VodViewMode,
    onDismiss: () -> Unit,
    onModeSelected: (VodViewMode) -> Unit,
) {
    SettingsSingleChoiceDialog(
        title = stringResource(R.string.settings_vod_view_mode),
        subtitle = stringResource(R.string.settings_vod_view_mode_subtitle),
        onDismiss = onDismiss,
    ) {
        VodViewMode.entries.forEach { mode ->
            SettingsChoiceOption(
                label = stringResource(mode.labelResId()),
                description = stringResource(mode.descriptionResId()),
                selected = mode == selectedMode,
                onSelect = { onModeSelected(mode) },
            )
        }
    }
}

@Composable
public fun LiveChannelNumberingModeDialog(
    selectedMode: ChannelNumberingMode,
    onDismiss: () -> Unit,
    onModeSelected: (ChannelNumberingMode) -> Unit,
) {
    SettingsSingleChoiceDialog(
        title = stringResource(R.string.settings_live_channel_numbering_mode),
        subtitle = stringResource(R.string.settings_live_channel_numbering_mode_subtitle),
        onDismiss = onDismiss,
    ) {
        ChannelNumberingMode.entries.forEach { mode ->
            SettingsChoiceOption(
                label = stringResource(mode.labelResId()),
                description = stringResource(mode.descriptionResId()),
                selected = mode == selectedMode,
                onSelect = { onModeSelected(mode) },
            )
        }
    }
}

@Composable
public fun LiveChannelGroupingModeDialog(
    selectedMode: LiveChannelGroupingMode,
    onDismiss: () -> Unit,
    onModeSelected: (LiveChannelGroupingMode) -> Unit,
) {
    SettingsSingleChoiceDialog(
        title = stringResource(R.string.settings_live_channel_grouping_mode),
        subtitle = stringResource(R.string.settings_live_channel_grouping_mode_subtitle),
        onDismiss = onDismiss,
    ) {
        LiveChannelGroupingMode.entries.forEach { mode ->
            SettingsChoiceOption(
                label = stringResource(mode.labelResId()),
                description = stringResource(mode.descriptionResId()),
                selected = mode == selectedMode,
                onSelect = { onModeSelected(mode) },
            )
        }
    }
}

@Composable
public fun GroupedChannelLabelModeDialog(
    selectedMode: GroupedChannelLabelMode,
    onDismiss: () -> Unit,
    onModeSelected: (GroupedChannelLabelMode) -> Unit,
) {
    SettingsSingleChoiceDialog(
        title = stringResource(R.string.settings_grouped_channel_label_mode),
        subtitle = stringResource(R.string.settings_grouped_channel_label_mode_subtitle),
        onDismiss = onDismiss,
    ) {
        GroupedChannelLabelMode.entries.forEach { mode ->
            SettingsChoiceOption(
                label = stringResource(mode.labelResId()),
                description = stringResource(mode.descriptionResId()),
                selected = mode == selectedMode,
                onSelect = { onModeSelected(mode) },
            )
        }
    }
}

@Composable
public fun VodDuplicateHandlingModeDialog(
    selectedMode: VodDuplicateHandlingMode,
    onDismiss: () -> Unit,
    onModeSelected: (VodDuplicateHandlingMode) -> Unit,
) {
    SettingsSingleChoiceDialog(
        title = stringResource(R.string.settings_vod_duplicate_handling_mode),
        subtitle = stringResource(R.string.settings_vod_duplicate_handling_mode_subtitle),
        onDismiss = onDismiss,
    ) {
        VodDuplicateHandlingMode.entries.forEach { mode ->
            SettingsChoiceOption(
                label = stringResource(mode.labelResId()),
                description = stringResource(mode.descriptionResId()),
                selected = mode == selectedMode,
                onSelect = { onModeSelected(mode) },
            )
        }
    }
}

@Composable
public fun VodVariantPreferenceModeDialog(
    selectedMode: VodVariantPreferenceMode,
    onDismiss: () -> Unit,
    onModeSelected: (VodVariantPreferenceMode) -> Unit,
) {
    SettingsSingleChoiceDialog(
        title = stringResource(R.string.settings_vod_variant_preference_mode),
        subtitle = stringResource(R.string.settings_vod_variant_preference_mode_subtitle),
        onDismiss = onDismiss,
    ) {
        VodVariantPreferenceMode.entries.forEach { mode ->
            SettingsChoiceOption(
                label = stringResource(mode.labelResId()),
                description = stringResource(mode.descriptionResId()),
                selected = mode == selectedMode,
                onSelect = { onModeSelected(mode) },
            )
        }
    }
}

@Composable
public fun LiveVariantPreferenceModeDialog(
    selectedMode: LiveVariantPreferenceMode,
    onDismiss: () -> Unit,
    onModeSelected: (LiveVariantPreferenceMode) -> Unit,
) {
    SettingsSingleChoiceDialog(
        title = stringResource(R.string.settings_live_variant_preference_mode),
        subtitle = stringResource(R.string.settings_live_variant_preference_mode_subtitle),
        onDismiss = onDismiss,
    ) {
        LiveVariantPreferenceMode.entries.forEach { mode ->
            SettingsChoiceOption(
                label = stringResource(mode.labelResId()),
                description = stringResource(mode.descriptionResId()),
                selected = mode == selectedMode,
                onSelect = { onModeSelected(mode) },
            )
        }
    }
}

@Composable
public fun RemoteShortcutSelectionDialog(
    target: RemoteShortcutDialogTarget,
    selectedSelection: RemoteShortcutSelection,
    onDismiss: () -> Unit,
    onSelection: (RemoteShortcutSelection) -> Unit,
) {
    val context = LocalContext.current
    SettingsSingleChoiceDialog(
        title = stringResource(R.string.settings_remote_dialog_title, stringResource(target.button.labelResId())),
        subtitle = stringResource(R.string.settings_remote_dialog_subtitle, stringResource(target.profile.labelResId())),
        onDismiss = onDismiss,
        widthFraction = 0.56f,
    ) {
        remoteShortcutSelectionOptions(target.profile).forEach { selection ->
            SettingsChoiceOption(
                label = formatRemoteShortcutSelectionLabel(
                    selection = selection,
                    profile = target.profile,
                    button = target.button,
                    context = context,
                ),
                selected = selection == selectedSelection.normalizedForProfile(target.profile),
                onSelect = { onSelection(selection) },
            )
        }
    }
}
