package com.streamvault.feature.live.home

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.streamvault.core.ui.components.dialogs.AppDialog
import com.streamvault.core.ui.design.BrowseShapes
import com.streamvault.core.ui.design.browse.OnSurface
import com.streamvault.core.ui.design.browse.SurfaceElevated
import com.streamvault.core.ui.interaction.TvButton
import com.streamvault.domain.model.Channel
import com.streamvault.feature.live.R
import com.streamvault.feature.live.phone.PhoneOrderingDialog
import com.streamvault.feature.live.phone.CombinedPhoneOrderingDialog
import com.streamvault.feature.live.presentation.components.LiveReorderTopBar
import com.streamvault.feature.live.presentation.dialogs.LiveChannelMoveDialog

@Composable
fun ProviderCategoryOrderEditor(
    providerId: Long,
    onDismiss: () -> Unit,
    viewModel: ProviderCategoryOrderViewModel = hiltViewModel(key = "category-order:$providerId")
) = CategoryOrderEditorBody(providerId, null, onDismiss, viewModel)

@Composable
fun CombinedCategoryOrderEditor(
    profileId: Long,
    onDismiss: () -> Unit,
    viewModel: ProviderCategoryOrderViewModel = hiltViewModel(key = "category-order:combined:$profileId")
) = CategoryOrderEditorBody(null, profileId, onDismiss, viewModel)

@Composable
private fun CategoryOrderEditorBody(
    providerId: Long?,
    combinedProfileId: Long?,
    onDismiss: () -> Unit,
    viewModel: ProviderCategoryOrderViewModel
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showDiscard by remember { mutableStateOf(false) }
    var section by remember(combinedProfileId) {
        mutableStateOf(if (combinedProfileId == null) CategoryOrderSection.PERSONAL else CategoryOrderSection.PROVIDER)
    }
    var showTools by remember(section) { mutableStateOf(false) }
    var showPhone by remember { mutableStateOf(false) }
    var dragging by remember(section) { mutableStateOf<Channel?>(null) }
    val requesters = remember(section) { mutableMapOf<Long, FocusRequester>() }
    val title = stringResource(R.string.live_category_order_title)
    val favorites = stringResource(R.string.live_category_order_favorites)
    val recent = stringResource(R.string.live_category_order_recent)
    val all = stringResource(R.string.home_all_channels)
    LaunchedEffect(providerId, combinedProfileId, favorites, recent, all) {
        if (combinedProfileId != null) viewModel.openCombined(combinedProfileId, favorites, recent, all)
        else viewModel.open(checkNotNull(providerId), favorites, recent, all)
    }
    DisposableEffect(viewModel) { onDispose { viewModel.reset() } }
    LaunchedEffect(state.closed) { if (state.closed) onDismiss() }
    val draft = state.drafts[section]
    val sectionLabel = stringResource(when (section) {
        CategoryOrderSection.PERSONAL -> R.string.live_category_order_personal
        CategoryOrderSection.PINNED -> R.string.live_category_order_pinned
        CategoryOrderSection.PROVIDER -> R.string.live_category_order_other
    })
    val rows = remember(draft?.keys, state.categories) { draft?.keys.orEmpty().map { key ->
        val category = state.categories.getValue(key.toLong())
        Channel(id = category.id, providerId = providerId ?: 0L, name = category.name)
    } }
    val dismiss = { if (!state.saving) {
        if (state.drafts.values.any { it.hasChanges }) showDiscard = true else onDismiss()
    } }
    AppDialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false,
        dismissOnBackPress = !state.saving, dismissOnClickOutside = false)) {
        Surface(modifier = Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.92f), shape = BrowseShapes.Panel,
            colors = SurfaceDefaults.colors(containerColor = SurfaceElevated)) {
            Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LiveReorderTopBar(categoryName = title, titleFormat = { it }, onSave = { viewModel.save() }, onCancel = dismiss,
                    subtitle = sectionLabel,
                    enabled = !state.saving, saveEnabled = !state.loading && state.drafts.isNotEmpty(),
                    onOpenTools = if (rows.isNotEmpty()) ({ showTools = true }) else null,
                    onUndo = { viewModel.undo(section) }, onRedo = { viewModel.redo(section) },
                    canUndo = draft?.canUndo == true, canRedo = draft?.canRedo == true,
                    toolsLabel = stringResource(R.string.home_search_categories))
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CategoryOrderSection.entries.filter { choice ->
                        (combinedProfileId == null && state.showPersonalSection) || choice != CategoryOrderSection.PERSONAL
                    }.forEach { choice ->
                        val label = stringResource(when (choice) {
                            CategoryOrderSection.PERSONAL -> R.string.live_category_order_personal
                            CategoryOrderSection.PINNED -> R.string.live_category_order_pinned
                            CategoryOrderSection.PROVIDER -> R.string.live_category_order_other
                        })
                        TvButton(onClick = { viewModel.finishMove(); section = choice }, enabled = !state.saving && !state.loading) { Text(label) }
                    }
                }
                state.message?.let { Text(stringResource(it), color = OnSurface) }
                if (state.loading) Text(stringResource(R.string.home_loading_categories), color = OnSurface)
                if (providerId != null || combinedProfileId != null) {
                    TvButton(onClick = { showPhone = true }, enabled = !state.saving && !state.loading && state.drafts.values.none { it.hasChanges }) {
                        Text(stringResource(R.string.live_phone_start))
                    }
                }
                TvButton(onClick = { viewModel.save(providerOrder = true) }, enabled = !state.saving && !state.loading) {
                    Text(stringResource(R.string.live_category_order_provider))
                }
                LiveChannelListHost(channels = rows, hasMoreChannels = false, isReorderMode = true,
                    draggingChannel = dragging, focusedChannelId = dragging?.id, channelFocusRequesters = requesters,
                    contentPaddingBottom = 12.dp, channelListSpacing = 8.dp,
                    onDraggingChannelChange = { dragging = it }, onExitReorderMode = dismiss,
                    onMoveChannelUp = { item -> viewModel.move(section, item.id, rows.indexOfFirst { it.id == item.id }) },
                    onMoveChannelDown = { item -> viewModel.move(section, item.id, rows.indexOfFirst { it.id == item.id } + 2) },
                    onCancelMove = viewModel::cancelMove, onVisibleChannelWindowChanged = { _, _ -> }, onLoadMore = {},
                    modifier = Modifier.weight(1f), itemContent = { item, requester, pickedUp ->
                        TvButton(onClick = {
                            if (pickedUp) { viewModel.finishMove(); dragging = null }
                            else { viewModel.beginMove(section); dragging = item }
                        }, enabled = !state.saving, modifier = Modifier.fillMaxWidth().focusRequester(requester)) {
                            Text(item.name)
                            if (item.id in state.hiddenIds) Text(stringResource(R.string.live_category_order_hidden), Modifier.padding(start = 16.dp))
                            if (pickedUp) Text(stringResource(R.string.badge_moving), Modifier.padding(start = 16.dp))
                        }
                    })
            }
        }
    }
    if (showDiscard && !state.saving) com.streamvault.feature.live.presentation.dialogs.LiveOrderDiscardDialog(
        onDiscard = { showDiscard = false; onDismiss() }, onKeepEditing = { showDiscard = false })
    if (showTools && !state.saving) LiveChannelMoveDialog(rows,
        onMove = { item, position -> viewModel.move(section, item.id, position) }, onDismiss = { showTools = false },
        title = title, searchLabel = stringResource(R.string.home_search_categories),
        onMoveMany = { items, position -> viewModel.moveMany(section, items.map { it.id }.toSet(), position) })
    if (showPhone && providerId != null) PhoneOrderingDialog(providerId, null, onDismiss = {
        showPhone = false
        viewModel.open(providerId, favorites, recent, all)
    })
    if (showPhone && combinedProfileId != null) CombinedPhoneOrderingDialog(combinedProfileId, null, onDismiss = {
        showPhone = false
        viewModel.openCombined(combinedProfileId, favorites, recent, all)
    })
}
