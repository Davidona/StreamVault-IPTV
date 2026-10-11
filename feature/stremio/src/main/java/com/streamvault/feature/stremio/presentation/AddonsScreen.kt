package com.streamvault.feature.stremio.presentation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.streamvault.core.navigation.AppDestination
import com.streamvault.feature.stremio.R
import com.streamvault.feature.stremio.api.StremioScaffoldContent
import kotlinx.coroutines.delay

@Composable
fun AddonsScreen(
    scaffold: StremioScaffoldContent,
    viewModel: AddonsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    state.statusMessageRes?.let { messageRes ->
        LaunchedEffect(messageRes) {
            delay(3_000)
            viewModel.consumeStatusMessage()
        }
    }

    scaffold(
        AppDestination.Addons,
        stringResource(R.string.stremio_title),
        stringResource(R.string.stremio_subtitle),
        false,
        true
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(onClick = viewModel::showInstallDialog) {
                Icon(imageVector = Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.size(8.dp))
                Text(text = stringResource(R.string.stremio_add))
            }
            state.errorMessage?.let { message ->
                MessageBanner(message = message, onClick = viewModel::consumeError)
            }
            state.statusMessageRes?.let { messageRes ->
                MessageBanner(message = stringResource(messageRes), onClick = viewModel::consumeStatusMessage)
            }
            when {
                state.loading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
                state.addons.isEmpty() -> Text(
                    text = stringResource(R.string.stremio_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    itemsIndexed(
                        items = state.addons,
                        key = { _, entry -> entry.addon.id }
                    ) { index, entry ->
                        AddonCard(
                            entry = entry,
                            expanded = state.expandedAddonId == entry.addon.id,
                            canMoveUp = index > 0,
                            canMoveDown = index < state.addons.lastIndex,
                            onEnabledChange = { enabled -> viewModel.setEnabled(entry, enabled) },
                            onMoveUp = { viewModel.moveAddon(entry, -1) },
                            onMoveDown = { viewModel.moveAddon(entry, 1) },
                            onToggleExpanded = { viewModel.toggleExpanded(entry.addon.id) },
                            onCatalogEnabledChange = { catalogKey, enabled ->
                                viewModel.setCatalogEnabled(entry, catalogKey, enabled)
                            },
                            onRemove = { viewModel.removeAddon(entry) }
                        )
                    }
                }
            }
        }
    }

    if (state.installDialogVisible) {
        InstallAddonDialog(
            url = state.installUrl,
            installing = state.installing,
            errorMessage = state.errorMessage,
            onUrlChange = viewModel::updateInstallUrl,
            onConfirm = viewModel::install,
            onDismiss = viewModel::dismissInstallDialog
        )
    }
}

@Composable
private fun AddonCard(
    entry: AddonsViewModel.AddonEntry,
    expanded: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onToggleExpanded: () -> Unit,
    onCatalogEnabledChange: (String, Boolean) -> Unit,
    onRemove: () -> Unit
) {
    val addon = entry.addon
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = addon.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = stringResource(R.string.stremio_version_format, addon.version),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = addon.installUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Switch(checked = addon.enabled, onCheckedChange = onEnabledChange)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(onClick = onMoveUp, enabled = canMoveUp) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowUp,
                        contentDescription = stringResource(R.string.stremio_move_up)
                    )
                }
                IconButton(onClick = onMoveDown, enabled = canMoveDown) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = stringResource(R.string.stremio_move_down)
                    )
                }
                TextButton(onClick = onToggleExpanded) {
                    Text(text = stringResource(R.string.stremio_catalogs))
                }
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onRemove) {
                    Text(
                        text = stringResource(R.string.stremio_remove),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            if (expanded) {
                if (entry.catalogs.isEmpty()) {
                    Text(
                        text = stringResource(R.string.stremio_no_catalogs),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    entry.catalogs.forEach { catalog ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Switch(
                                checked = addon.isCatalogEnabled(catalog.settingsKey),
                                onCheckedChange = { enabled ->
                                    onCatalogEnabledChange(catalog.settingsKey, enabled)
                                }
                            )
                            Spacer(modifier = Modifier.size(12.dp))
                            Column {
                                Text(
                                    text = catalog.name ?: catalog.id,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    text = "${catalog.type.typeName} · ${catalog.id}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InstallAddonDialog(
    url: String,
    installing: Boolean,
    errorMessage: String?,
    onUrlChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!installing) onDismiss() },
        title = { Text(text = stringResource(R.string.stremio_install_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = onUrlChange,
                    singleLine = true,
                    enabled = !installing,
                    placeholder = { Text(text = stringResource(R.string.stremio_install_hint)) },
                    modifier = Modifier.fillMaxWidth()
                )
                if (installing) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        Text(text = stringResource(R.string.stremio_installing))
                    }
                }
                errorMessage?.let { message ->
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = url.isNotBlank() && !installing
            ) {
                Text(text = stringResource(R.string.stremio_install_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !installing) {
                Text(text = stringResource(R.string.stremio_cancel))
            }
        }
    )
}

@Composable
private fun MessageBanner(message: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
