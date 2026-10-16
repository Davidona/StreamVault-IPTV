package com.streamvault.feature.stremio.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.streamvault.core.navigation.AppDestination
import com.streamvault.core.ui.components.dialogs.PremiumDialog
import com.streamvault.core.ui.components.dialogs.PremiumDialogFooterButton
import com.streamvault.core.ui.components.shell.ExtensionsEmptyState
import com.streamvault.core.ui.components.shell.StatusPill
import com.streamvault.core.ui.design.BrowseColors as AppColors
import com.streamvault.core.ui.design.BrowseShapes
import com.streamvault.core.ui.design.browsePanel
import com.streamvault.core.ui.interaction.TvButton
import com.streamvault.core.ui.interaction.TvPrimaryButton
import com.streamvault.core.ui.interaction.TvIconButton
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Border
import androidx.compose.foundation.BorderStroke
import com.streamvault.core.ui.interaction.TvClickableSurface
import com.streamvault.core.ui.components.shell.ExtensionsSectionTabs
import com.streamvault.domain.model.stremio.StremioMetaPreview
import com.streamvault.domain.model.stremio.StremioPlaybackHistory
import com.streamvault.domain.model.stremio.StremioResourceKind
import com.streamvault.domain.model.stremio.installFingerprint
import com.streamvault.feature.stremio.R
import com.streamvault.feature.stremio.api.StremioScaffoldContent
import kotlinx.coroutines.delay

private data class AddonFocusRestore(
    val actionAddonId: Long,
    val targetAddonId: Long?,
    val expectedAddonIds: List<Long>
)

@Composable
fun AddonsScreen(
    scaffold: StremioScaffoldContent,
    onNavigateToPlugins: () -> Unit = {},
    onOpenBrowse: () -> Unit = {},
    viewModel: AddonsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pendingRemoval by remember { mutableStateOf<AddonsViewModel.AddonEntry?>(null) }
    var pendingFocusRestore by remember { mutableStateOf<AddonFocusRestore?>(null) }
    val addonIds = state.addons.map { it.addon.id }
    val addonFocusRequesters = remember(addonIds) { addonIds.associateWith { FocusRequester() } }
    val addButtonFocusRequester = remember { FocusRequester() }

    LaunchedEffect(addonIds, pendingFocusRestore, state.busyAddonIds) {
        val request = pendingFocusRestore ?: return@LaunchedEffect
        if (addonIds != request.expectedAddonIds) {
            if (request.actionAddonId !in state.busyAddonIds) {
                delay(250)
                withFrameNanos { }
                val fallbackRequester = addonFocusRequesters[request.actionAddonId] ?: addButtonFocusRequester
                runCatching { fallbackRequester.requestFocus() }
                pendingFocusRestore = null
            }
            return@LaunchedEffect
        }
        withFrameNanos { }
        val requester = request.targetAddonId?.let(addonFocusRequesters::get) ?: addButtonFocusRequester
        runCatching { requester.requestFocus() }
        pendingFocusRestore = null
    }

    fun moveAddonAndRestoreFocus(entry: AddonsViewModel.AddonEntry, index: Int, offset: Int) {
        val destination = index + offset
        if (destination !in addonIds.indices) return
        val expectedOrder = addonIds.toMutableList().apply {
            val movedId = removeAt(index)
            add(destination, movedId)
        }
        pendingFocusRestore = AddonFocusRestore(entry.addon.id, entry.addon.id, expectedOrder)
        viewModel.moveAddon(entry, offset)
    }

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
        Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ExtensionsSectionTabs(
                selectedIndex = 1,
                pluginsLabel = stringResource(R.string.extensions_tab_plugins),
                stremioAddonsLabel = stringResource(R.string.extensions_tab_stremio_addons),
                onTabSelected = { selectedIndex ->
                    if (selectedIndex == 0) onNavigateToPlugins()
                }
            )
            state.errorMessage?.let { message ->
                MessageBanner(message = message, onClick = viewModel::consumeError)
            }
            state.statusMessageRes?.let { messageRes ->
                MessageBanner(message = stringResource(messageRes), onClick = viewModel::consumeStatusMessage)
            }
            when {
                state.loading && state.addons.isEmpty() -> Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator(color = AppColors.Brand) }
                state.addons.isEmpty() -> ExtensionsEmptyState(
                    title = stringResource(R.string.stremio_empty_title),
                    description = stringResource(R.string.stremio_empty_description),
                    modifier = Modifier.weight(1f),
                    action = {
                        TvPrimaryButton(
                            onClick = viewModel::showInstallDialog,
                            modifier = Modifier.focusRequester(addButtonFocusRequester)
                        ) {
                            Icon(imageVector = Icons.Default.Add, contentDescription = null)
                            Spacer(Modifier.size(8.dp))
                            Text(stringResource(R.string.stremio_add))
                        }
                    }
                )
                else -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.stremio_installed_count, state.addons.size),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium,
                            color = AppColors.TextPrimary
                        )
                        TvPrimaryButton(
                            onClick = viewModel::showInstallDialog,
                            modifier = Modifier.focusRequester(addButtonFocusRequester)
                        ) {
                            Icon(imageVector = Icons.Default.Add, contentDescription = null)
                            Spacer(Modifier.size(8.dp))
                            Text(stringResource(R.string.stremio_add))
                        }
                        TvButton(onClick = onOpenBrowse) {
                            Text(stringResource(R.string.stremio_browse))
                        }
                    }
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 20.dp)
                    ) {
                        itemsIndexed(items = state.addons, key = { _, entry -> entry.addon.id }) { index, entry ->
                            AddonCard(
                                entry = entry,
                                detailsFocusRequester = addonFocusRequesters.getValue(entry.addon.id),
                                expanded = state.expandedAddonId == entry.addon.id,
                                refreshing = state.refreshingAddonId == entry.addon.id,
                                busy = entry.addon.id in state.busyAddonIds,
                                canMoveUp = index > 0,
                                canMoveDown = index < state.addons.lastIndex,
                                onEnabledChange = { enabled -> viewModel.setEnabled(entry, enabled) },
                                onMoveUp = { moveAddonAndRestoreFocus(entry, index, -1) },
                                onMoveDown = { moveAddonAndRestoreFocus(entry, index, 1) },
                                onToggleExpanded = { viewModel.toggleExpanded(entry.addon.id) },
                                onCatalogEnabledChange = { catalogKey, enabled ->
                                    viewModel.setCatalogEnabled(entry, catalogKey, enabled)
                                },
                                onRefresh = { viewModel.refreshAddon(entry) },
                                onRemove = { pendingRemoval = entry }
                            )
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

    pendingRemoval?.let { entry ->
        PremiumDialog(
            title = stringResource(R.string.stremio_remove_confirm_title),
            subtitle = stringResource(R.string.stremio_remove_confirm_body, entry.addon.name),
            onDismissRequest = { pendingRemoval = null },
            heightFraction = null,
            content = {},
            footer = {
                PremiumDialogFooterButton(
                    label = stringResource(R.string.stremio_cancel),
                    onClick = { pendingRemoval = null }
                )
                PremiumDialogFooterButton(
                    label = stringResource(R.string.stremio_remove),
                    onClick = {
                        val removedIndex = addonIds.indexOf(entry.addon.id)
                        val targetId = when {
                            removedIndex > 0 -> addonIds[removedIndex - 1]
                            removedIndex >= 0 -> addonIds.getOrNull(removedIndex + 1)
                            else -> null
                        }
                        pendingFocusRestore = AddonFocusRestore(
                            actionAddonId = entry.addon.id,
                            targetAddonId = targetId,
                            expectedAddonIds = addonIds.filterNot { it == entry.addon.id }
                        )
                        pendingRemoval = null
                        viewModel.removeAddon(entry)
                    },
                    emphasized = true
                )
            }
        )
    }
}

@Composable
private fun ContinueWatchingCard(item: StremioPlaybackHistory, onClick: () -> Unit) {
    val progress = if (item.totalDurationMs > 0L) {
        (item.resumePositionMs.toFloat() / item.totalDurationMs).coerceIn(0f, 1f)
    } else 0f
    TvClickableSurface(
        onClick = onClick,
        modifier = Modifier.size(width = 236.dp, height = 142.dp),
        shape = ClickableSurfaceDefaults.shape(BrowseShapes.Artwork)
    ) {
        Box(Modifier.fillMaxSize().clip(BrowseShapes.Artwork)) {
            item.posterUrl?.takeIf(String::isNotBlank)?.let { poster ->
                AsyncImage(
                    model = poster,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.08f), Color.Black.copy(alpha = 0.9f))
                    )
                )
            )
            Column(
                modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = AppColors.Brand,
                    trackColor = Color.White.copy(alpha = 0.28f)
                )
            }
        }
    }
}

@Composable
private fun AddonCard(
    entry: AddonsViewModel.AddonEntry,
    detailsFocusRequester: FocusRequester,
    expanded: Boolean,
    refreshing: Boolean,
    busy: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onToggleExpanded: () -> Unit,
    onCatalogEnabledChange: (String, Boolean) -> Unit,
    onRefresh: () -> Unit,
    onRemove: () -> Unit
) {
    val addon = entry.addon
    val manifest = entry.manifest
    val hasStreams = manifest?.resources?.any { it.kind == StremioResourceKind.STREAM } == true
    val hasSubtitles = manifest?.resources?.any { it.kind == StremioResourceKind.SUBTITLES } == true
    Column(
        modifier = Modifier.fillMaxWidth().browsePanel(BrowseShapes.Panel).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(46.dp).clip(BrowseShapes.Artwork).background(AppColors.SurfaceEmphasis),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = addon.name.take(1).uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    color = AppColors.BrandStrong
                )
                manifest?.logo?.takeIf(String::isNotBlank)?.let { logo ->
                    AsyncImage(
                        model = logo,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    text = addon.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = AppColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (entry.catalogs.isNotEmpty()) StatusPill(label = stringResource(R.string.stremio_catalogs_capability))
                    if (hasStreams) StatusPill(label = stringResource(R.string.stremio_streams_capability))
                    if (hasSubtitles) StatusPill(label = stringResource(R.string.stremio_subtitles_capability))
                }
            }
            Switch(
                checked = addon.enabled,
                onCheckedChange = onEnabledChange,
                enabled = !busy,
                colors = stremioSwitchColors()
            )
        }

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TvButton(
                onClick = onToggleExpanded,
                modifier = Modifier.heightIn(min = 44.dp).focusRequester(detailsFocusRequester),
                colors = androidx.tv.material3.ButtonDefaults.colors(
                    containerColor = AppColors.Surface,
                    contentColor = AppColors.TextPrimary,
                    focusedContainerColor = AppColors.SurfaceEmphasis,
                    focusedContentColor = AppColors.TextPrimary
                )
            ) {
                Text(stringResource(if (expanded) R.string.stremio_hide_details else R.string.stremio_details))
            }
            Spacer(modifier = Modifier.weight(1f))
        }

        if (expanded) {
            Text(
                text = stringResource(R.string.stremio_version_and_server_format,
                    addon.version,
                    stremioAddonOriginLabel(addon.installUrl)
                        ?: stringResource(R.string.stremio_addon_server_unknown)
                ),
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            manifest?.description?.takeIf(String::isNotBlank)?.let { description ->
                Text(description, style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
            }
            if (entry.catalogs.isEmpty()) {
                val capabilityHint = when {
                    hasStreams && hasSubtitles -> R.string.stremio_streams_and_subtitles_only_hint
                    hasStreams -> R.string.stremio_streams_only_hint
                    hasSubtitles -> R.string.stremio_subtitles_only_hint
                    else -> null
                }
                capabilityHint?.let { hint ->
                    Text(
                        text = stringResource(hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.TextSecondary
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TvButton(onClick = onRefresh, enabled = !refreshing && !busy) {
                    if (refreshing) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = AppColors.Brand)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(stringResource(R.string.stremio_refresh_manifest))
                }
                TvIconButton(onClick = onMoveUp, enabled = canMoveUp && !busy) {
                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = stringResource(R.string.stremio_move_up))
                }
                TvIconButton(onClick = onMoveDown, enabled = canMoveDown && !busy) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = stringResource(R.string.stremio_move_down))
                }
                Spacer(Modifier.weight(1f))
                TvButton(onClick = onRemove, enabled = !busy) {
                    Text(stringResource(R.string.stremio_remove))
                }
            }
            if (entry.catalogs.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.stremio_catalogs_capability),
                    style = MaterialTheme.typography.titleSmall,
                    color = AppColors.TextPrimary
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    entry.catalogs.forEach { catalog ->
                        Row(
                            modifier = Modifier.fillMaxWidth().browsePanel(BrowseShapes.Control).padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(
                                    text = catalog.name ?: catalog.id,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = AppColors.TextPrimary
                                )
                                Text(
                                    text = "${catalog.type.typeName} · ${catalog.id}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AppColors.TextTertiary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Switch(
                                checked = addon.isCatalogEnabled(catalog.settingsKey),
                                onCheckedChange = { enabled ->
                                    onCatalogEnabledChange(catalog.settingsKey, enabled)
                                },
                                enabled = !busy,
                                colors = stremioSwitchColors()
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun stremioSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = AppColors.OnPrimary,
    checkedTrackColor = AppColors.Brand,
    checkedBorderColor = AppColors.Brand,
    uncheckedThumbColor = AppColors.TextSecondary,
    uncheckedTrackColor = AppColors.SurfaceEmphasis,
    uncheckedBorderColor = AppColors.Outline
)

@Composable
private fun InstallAddonDialog(
    url: String,
    installing: Boolean,
    errorMessage: String?,
    onUrlChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val fieldFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { fieldFocusRequester.requestFocus() }
    }
    PremiumDialog(
        title = stringResource(R.string.stremio_install_title),
        subtitle = stringResource(R.string.stremio_install_description),
        onDismissRequest = { if (!installing) onDismiss() },
        widthFraction = 0.62f,
        heightFraction = null,
        content = {
            OutlinedTextField(
                value = url,
                onValueChange = onUrlChange,
                modifier = Modifier.fillMaxWidth().focusRequester(fieldFocusRequester),
                enabled = !installing,
                singleLine = true,
                label = { Text(stringResource(R.string.stremio_install_url_label)) },
                placeholder = { Text(stringResource(R.string.stremio_install_hint)) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = AppColors.TextPrimary,
                    unfocusedTextColor = AppColors.TextPrimary,
                    disabledTextColor = AppColors.TextSecondary,
                    focusedBorderColor = AppColors.Brand,
                    unfocusedBorderColor = AppColors.Outline,
                    disabledBorderColor = AppColors.Outline.copy(alpha = 0.4f),
                    focusedLabelColor = AppColors.Brand,
                    unfocusedLabelColor = AppColors.TextSecondary,
                    disabledLabelColor = AppColors.TextTertiary,
                    cursorColor = AppColors.Brand
                )
            )
            if (installing) {
                Row(
                    modifier = Modifier.padding(top = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = AppColors.Brand)
                    Text(text = stringResource(R.string.stremio_installing), color = AppColors.TextSecondary)
                }
            }
            errorMessage?.let { message ->
                Text(
                    text = message,
                    modifier = Modifier.padding(top = 12.dp),
                    color = AppColors.Live,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        },
        footer = {
            PremiumDialogFooterButton(
                label = stringResource(R.string.stremio_cancel),
                onClick = onDismiss,
                enabled = !installing
            )
            PremiumDialogFooterButton(
                label = stringResource(R.string.stremio_install_confirm),
                onClick = onConfirm,
                enabled = url.isNotBlank() && !installing,
                emphasized = true
            )
        }
    )
}

@Composable
private fun MessageBanner(message: String, onClick: () -> Unit) {
    TvClickableSurface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(BrowseShapes.Control),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = AppColors.SurfaceEmphasis,
            focusedContainerColor = AppColors.SurfaceAccent,
            contentColor = AppColors.TextSecondary,
            focusedContentColor = AppColors.TextPrimary
        )
    ) {
        Text(
            text = message,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun StremioViewTabs(selectedIndex: Int, onTabSelected: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(R.string.stremio_tab_browse, R.string.stremio_tab_manage).forEachIndexed { index, label ->
            val selected = index == selectedIndex
            val shape = BrowseShapes.Control
            TvButton(
                onClick = { if (!selected) onTabSelected(index) },
                modifier = Modifier.heightIn(min = 44.dp),
                shape = ButtonDefaults.shape(shape),
                border = ButtonDefaults.border(
                    border = if (selected) Border(BorderStroke(1.dp, AppColors.Brand.copy(alpha = 0.7f)), shape = shape) else Border.None,
                    focusedBorder = Border(BorderStroke(2.dp, AppColors.Focus), shape = shape)
                ),
                colors = ButtonDefaults.colors(
                    containerColor = if (selected) AppColors.BrandMuted else AppColors.Surface,
                    contentColor = if (selected) AppColors.TextPrimary else AppColors.TextSecondary,
                    focusedContainerColor = if (selected) AppColors.BrandMuted else AppColors.SurfaceEmphasis,
                    focusedContentColor = AppColors.TextPrimary
                )
            ) {
                Text(stringResource(label), modifier = Modifier.padding(horizontal = 8.dp))
            }
        }
    }
}
