package com.streamvault.app.ui.screens.settings

import com.streamvault.feature.settings.presentation.*

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.app.R
import com.streamvault.core.ui.components.dialogs.PremiumDialog
import com.streamvault.core.ui.components.dialogs.PremiumDialogFooterButton
import com.streamvault.core.ui.interaction.TvButton
import com.streamvault.core.ui.theme.OnBackground
import com.streamvault.core.ui.theme.OnSurface
import com.streamvault.domain.model.LegacyProvider as Provider
import com.streamvault.domain.model.ProviderType

private fun availableSyncSelections(provider: Provider): List<ProviderSyncSelection> = buildList {
    add(ProviderSyncSelection.TV)
    add(ProviderSyncSelection.MOVIES)
    if (provider.type == ProviderType.XTREAM_CODES) {
        add(ProviderSyncSelection.SERIES)
    }
    add(ProviderSyncSelection.EPG)
}

@Composable
internal fun ProviderSyncOptionsDialog(
    provider: Provider,
    onDismiss: () -> Unit,
    onSelect: (ProviderSyncSelection?) -> Unit
) {
    PremiumDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.settings_sync_dialog_title, provider.name),
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.settings_sync_dialog_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnSurface
                )
                SyncOptionButton(stringResource(R.string.settings_sync_option_sync_now)) {
                    onSelect(ProviderSyncSelection.SYNC_NOW)
                }
                if (provider.type == ProviderType.XTREAM_CODES) {
                    SyncOptionButton(stringResource(R.string.settings_sync_option_rebuild_index)) {
                        onSelect(ProviderSyncSelection.REBUILD_INDEX)
                    }
                }
                availableSyncSelections(provider).forEach { option ->
                    SyncOptionButton(text = syncSelectionLabel(option)) {
                        onSelect(option)
                    }
                }
                OutlinedButton(
                    onClick = { onSelect(null) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.settings_sync_option_custom))
                }
            }
        }
    )
}

@Composable
internal fun ProviderCustomSyncDialog(
    provider: Provider,
    selected: Set<ProviderSyncSelection>,
    onToggle: (ProviderSyncSelection) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    PremiumDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.settings_sync_custom_title, provider.name),
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.settings_sync_custom_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnSurface
                )
                availableSyncSelections(provider).forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggle(option) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Checkbox(
                            checked = option in selected,
                            onCheckedChange = { onToggle(option) }
                        )
                        Text(
                            text = syncSelectionLabel(option),
                            style = MaterialTheme.typography.bodyLarge,
                            color = OnBackground
                        )
                    }
                }
            }
        },
        footer = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)
            ) {
                PremiumDialogFooterButton(
                    label = stringResource(R.string.settings_cancel),
                    onClick = onDismiss
                )
                PremiumDialogFooterButton(
                    label = stringResource(R.string.settings_sync_btn),
                    onClick = onConfirm,
                    enabled = selected.isNotEmpty()
                )
            }
        }
    )
}

@Composable
private fun SyncOptionButton(
    text: String,
    onClick: () -> Unit
) {
    TvButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(text)
    }
}

@Composable
private fun syncSelectionLabel(selection: ProviderSyncSelection): String = when (selection) {
    ProviderSyncSelection.SYNC_NOW -> stringResource(R.string.settings_sync_option_sync_now)
    ProviderSyncSelection.REBUILD_INDEX -> stringResource(R.string.settings_sync_option_rebuild_index)
    ProviderSyncSelection.TV -> stringResource(R.string.settings_sync_option_tv)
    ProviderSyncSelection.MOVIES -> stringResource(R.string.settings_sync_option_movies)
    ProviderSyncSelection.SERIES -> stringResource(R.string.settings_sync_option_series)
    ProviderSyncSelection.EPG -> stringResource(R.string.settings_sync_option_epg)
}
