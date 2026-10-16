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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.streamvault.core.navigation.AppDestination
import com.streamvault.core.ui.components.shell.ExtensionsEmptyState
import com.streamvault.core.ui.design.AppColors
import com.streamvault.core.ui.interaction.TvButton
import com.streamvault.core.ui.interaction.TvPrimaryButton
import androidx.tv.material3.ButtonDefaults
import com.streamvault.core.ui.components.shell.ExtensionsSectionTabs
import com.streamvault.domain.model.stremio.StremioMetaPreview
import com.streamvault.domain.model.stremio.StremioPlaybackHistory
import com.streamvault.domain.model.stremio.installFingerprint
import com.streamvault.feature.stremio.R
import com.streamvault.feature.stremio.api.StremioScaffoldContent
import kotlinx.coroutines.delay

@Composable
fun AddonsScreen(
    scaffold: StremioScaffoldContent,
    showContinueWatching: Boolean = true,
    onNavigateToPlugins: () -> Unit = {},
    onOpenItem: (Long, StremioMetaPreview) -> Unit = { _, _ -> },
    onOpenHistory: (StremioPlaybackHistory, Long) -> Unit = { _, _ -> },
    viewModel: AddonsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    state.statusMessageRes?.let { messageRes ->
        LaunchedEffect(messageRes) {
            delay(3_000)
            viewModel.consumeStatusMessage()
        }
    }

    scaffold(
        AppDestination.Addons,
        stringResource(R.string.extensions_title),
        null,
        false,
        true
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ExtensionsSectionTabs(
                selectedIndex = 1,
                pluginsLabel = stringResource(R.string.extensions_tab_plugins),
                stremioAddonsLabel = stringResource(R.string.extensions_tab_stremio_addons),
                onTabSelected = { selectedIndex ->
                    if (selectedIndex == 0) onNavigateToPlugins()
                }
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.stremio_title), style = androidx.tv.material3.MaterialTheme.typography.titleLarge, color = AppColors.TextPrimary)
                    Text(stringResource(R.string.stremio_section_description), style = MaterialTheme.typography.bodyMedium, color = AppColors.TextSecondary)
                }
                TvPrimaryButton(onClick = viewModel::showInstallDialog) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = null, tint = AppColors.OnPrimary)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.stremio_add), color = AppColors.OnPrimary)
                }
            }
            state.errorMessage?.let { message ->
                MessageBanner(message = message, onClick = viewModel::consumeError)
            }
            state.statusMessageRes?.let { messageRes ->
                MessageBanner(message = stringResource(messageRes), onClick = viewModel::consumeStatusMessage)
            }
            if (!state.loading && state.addons.isEmpty()) {
                ExtensionsEmptyState(
                    title = stringResource(R.string.stremio_empty_title),
                    description = stringResource(R.string.stremio_empty_description),
                    modifier = Modifier.weight(1f)
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf(R.string.stremio_tab_browse, R.string.stremio_tab_manage).forEachIndexed { index, label ->
                        TvButton(
                            onClick = { selectedTab = index },
                            scale = ButtonDefaults.scale(focusedScale = 1f, pressedScale = 1f),
                            colors = ButtonDefaults.colors(
                                containerColor = if (selectedTab == index) AppColors.BrandMuted else AppColors.Surface,
                                contentColor = AppColors.TextPrimary,
                                focusedContainerColor = AppColors.SurfaceEmphasis,
                                focusedContentColor = AppColors.TextPrimary
                            )
                        ) { Text(stringResource(label), color = AppColors.TextPrimary) }
                    }
                }
                if (selectedTab == 0) {
                    if (showContinueWatching && state.continueWatching.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = stringResource(R.string.stremio_continue_watching),
                                style = MaterialTheme.typography.titleMedium
                            )
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                items(state.continueWatching, key = { it.contentKey }) { item ->
                                    val originAddonId = state.addons.firstOrNull {
                                        it.addon.installFingerprint() == item.catalogOriginKey
                                    }?.addon?.id ?: 0L
                                    ContinueWatchingCard(
                                        item = item,
                                        onClick = { onOpenHistory(item, originAddonId) }
                                    )
                                }
                            }
                        }
                    }
                    StremioCatalogBrowseContent(
                        modifier = Modifier.weight(1f),
                        onOpenItem = onOpenItem
                    )
                } else {
                    when {
                        state.loading -> Box(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
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
                            modifier = Modifier.weight(1f).fillMaxWidth(),
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
private fun ContinueWatchingCard(item: StremioPlaybackHistory, onClick: () -> Unit) {
    val progress = if (item.totalDurationMs > 0L) {
        (item.resumePositionMs.toFloat() / item.totalDurationMs).coerceIn(0f, 1f)
    } else 0f
    Card(
        modifier = Modifier.size(width = 220.dp, height = 92.dp).clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        }
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
                        text = stringResource(
                            R.string.stremio_addon_server_format,
                            stremioAddonOriginLabel(addon.installUrl)
                                ?: stringResource(R.string.stremio_addon_server_unknown)
                        ),
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
                Text(
                    text = stringResource(R.string.stremio_install_description),
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = onUrlChange,
                    singleLine = true,
                    enabled = !installing,
                    label = { Text(stringResource(R.string.stremio_install_url_label)) },
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
