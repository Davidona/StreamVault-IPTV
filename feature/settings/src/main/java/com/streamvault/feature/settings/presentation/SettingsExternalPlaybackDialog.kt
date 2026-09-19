package com.streamvault.feature.settings.presentation

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
import androidx.tv.material3.Text
import com.streamvault.core.ui.components.dialogs.PremiumDialog
import com.streamvault.core.ui.components.dialogs.PremiumDialogFooterButton
import com.streamvault.core.ui.interaction.TvClickableSurface
import com.streamvault.core.ui.theme.OnBackground
import com.streamvault.core.ui.theme.Primary
import com.streamvault.core.ui.theme.SurfaceElevated
import com.streamvault.domain.model.ExternalPlaybackMode
import com.streamvault.feature.settings.R

@Composable
public fun ExternalPlaybackModeDialog(
    selectedMode: ExternalPlaybackMode,
    onDismiss: () -> Unit,
    onModeSelected: (ExternalPlaybackMode) -> Unit
) {
    val modes = listOf(ExternalPlaybackMode.INTERNAL_PLAYER, ExternalPlaybackMode.EXTERNAL_PLAYER)
    PremiumDialog(
        title = stringResource(R.string.settings_external_playback),
        subtitle = stringResource(R.string.settings_external_playback_subtitle),
        onDismissRequest = onDismiss,
        widthFraction = 0.52f,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                modes.forEach { mode ->
                    val isSelected = mode == selectedMode ||
                        (mode == ExternalPlaybackMode.EXTERNAL_PLAYER && selectedMode == ExternalPlaybackMode.ASK_EVERY_TIME)
                    TvClickableSurface(
                        onClick = { onModeSelected(mode) },
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
                                text = stringResource(
                                    when (mode) {
                                        ExternalPlaybackMode.INTERNAL_PLAYER -> R.string.settings_external_playback_mode_internal
                                        ExternalPlaybackMode.EXTERNAL_PLAYER -> R.string.settings_external_playback_mode_external
                                        ExternalPlaybackMode.ASK_EVERY_TIME -> R.string.settings_external_playback_mode_external
                                    }
                                ),
                                style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
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
