package com.streamvault.feature.settings.presentation

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.streamvault.domain.model.ExternalPlaybackMode
import com.streamvault.feature.settings.R

@Composable
public fun ExternalPlaybackModeDialog(
    selectedMode: ExternalPlaybackMode,
    onDismiss: () -> Unit,
    onModeSelected: (ExternalPlaybackMode) -> Unit,
) {
    SettingsSingleChoiceDialog(
        title = stringResource(R.string.settings_external_playback),
        subtitle = stringResource(R.string.settings_external_playback_subtitle),
        onDismiss = onDismiss,
    ) {
        listOf(ExternalPlaybackMode.INTERNAL_PLAYER, ExternalPlaybackMode.EXTERNAL_PLAYER).forEach { mode ->
            val isSelected = mode == selectedMode ||
                (mode == ExternalPlaybackMode.EXTERNAL_PLAYER && selectedMode == ExternalPlaybackMode.ASK_EVERY_TIME)
            SettingsChoiceOption(
                label = stringResource(
                    when (mode) {
                        ExternalPlaybackMode.INTERNAL_PLAYER -> R.string.settings_external_playback_mode_internal
                        ExternalPlaybackMode.EXTERNAL_PLAYER,
                        ExternalPlaybackMode.ASK_EVERY_TIME -> R.string.settings_external_playback_mode_external
                    },
                ),
                selected = isSelected,
                onSelect = { onModeSelected(mode) },
            )
        }
    }
}
