package com.streamvault.feature.live.home

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
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
import com.streamvault.core.ui.design.LocalAppSpacing
import com.streamvault.core.ui.design.AppColors
import com.streamvault.core.ui.design.browse.OnSurface
import com.streamvault.core.ui.design.browse.SurfaceElevated
import com.streamvault.core.ui.interaction.TvButton
import com.streamvault.domain.model.Channel
import com.streamvault.feature.live.R
import com.streamvault.feature.live.phone.PhoneOrderingDialog
import com.streamvault.feature.live.phone.CombinedPhoneOrderingDialog
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
    val spacing = LocalAppSpacing.current
    var showDiscard by remember { mutableStateOf(false) }
    var section by remember(combinedProfileId) {
        mutableStateOf(if (combinedProfileId == null) CategoryOrderSection.PERSONAL else CategoryOrderSection.PROVIDER)
    }
    var showTools by remember(section) { mutableStateOf(false) }
    var showPhone by remember { mutableStateOf(false) }
    var showProviderReset by remember { mutableStateOf(false) }
    var dragging by remember(section) { mutableStateOf<Channel?>(null) }
    var pickupPosition by remember(section) { mutableIntStateOf(0) }
    var jumpToken by remember { mutableLongStateOf(0L) }
    var jumpRequest by remember { mutableStateOf<Pair<Int, Long>?>(null) }
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
    val rows = remember(draft?.keys, state.categories) { draft?.keys.orEmpty().map { key ->
        val category = state.categories.getValue(key.toLong())
        Channel(id = category.id, providerId = providerId ?: 0L, name = category.name)
    } }
    val rowPositions = remember(rows) { rows.mapIndexed { index, item -> item.id to index + 1 }.toMap() }
    val dismiss = { if (!state.saving) {
        if (dragging != null) {
            viewModel.cancelMove()
            dragging = null
            if (pickupPosition > 0) { jumpToken += 1; jumpRequest = pickupPosition to jumpToken }
        } else if (state.drafts.values.any { it.hasChanges }) showDiscard = true else onDismiss()
    } }
    AppDialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false,
        dismissOnBackPress = false, dismissOnClickOutside = false)) {
        Surface(modifier = Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.92f), shape = BrowseShapes.Panel,
            colors = SurfaceDefaults.colors(containerColor = AppColors.SurfaceElevated)) {
            Column(Modifier.fillMaxSize().onPreviewKeyEvent { event ->
                if (event.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_BACK) {
                    if (event.type == KeyEventType.KeyUp) dismiss()
                    true
                } else false
            }.padding(spacing.md), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                OrderEditorHeader(title = title, enabled = !state.saving,
                    saveEnabled = !state.loading && state.drafts.isNotEmpty(), onCancel = dismiss,
                    onSave = { viewModel.save() })
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                    CategoryOrderSection.entries.filter { choice ->
                        (combinedProfileId == null && state.showPersonalSection) || choice != CategoryOrderSection.PERSONAL
                    }.forEach { choice ->
                        val label = stringResource(when (choice) {
                            CategoryOrderSection.PERSONAL -> R.string.live_category_order_personal
                            CategoryOrderSection.PINNED -> R.string.live_category_order_pinned
                            CategoryOrderSection.PROVIDER -> R.string.live_category_order_other
                        })
                        OrderEditorTab(label = label, selected = section == choice, enabled = !state.saving && !state.loading,
                            onClick = { viewModel.finishMove(); dragging = null; section = choice })
                    }
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                    OrderEditorAction(stringResource(R.string.home_search_categories), enabled = !state.saving && rows.isNotEmpty(),
                        onClick = { showTools = true })
                    OrderEditorAction(stringResource(R.string.live_order_undo), enabled = !state.saving && draft?.canUndo == true,
                        onClick = { viewModel.undo(section) })
                    OrderEditorAction(stringResource(R.string.live_order_redo), enabled = !state.saving && draft?.canRedo == true,
                        onClick = { viewModel.redo(section) })
                    OrderEditorAction(stringResource(R.string.live_phone_start),
                        enabled = !state.saving && !state.loading && state.drafts.values.none { it.hasChanges },
                        onClick = { showPhone = true })
                    OrderEditorAction(stringResource(R.string.live_category_order_provider),
                        enabled = !state.saving && !state.loading, onClick = { showProviderReset = true })
                }
                state.message?.let { Text(stringResource(it), color = OnSurface) }
                if (state.loading) Text(stringResource(R.string.home_loading_categories), color = OnSurface)
                LiveChannelListHost(channels = rows, hasMoreChannels = false, isReorderMode = true,
                    draggingChannel = dragging, focusedChannelId = dragging?.id, channelFocusRequesters = requesters,
                    contentPaddingBottom = spacing.sm, channelListSpacing = spacing.xs,
                    onDraggingChannelChange = { dragging = it }, onExitReorderMode = dismiss,
                    onMoveChannelUp = { item -> viewModel.move(section, item.id, rows.indexOfFirst { it.id == item.id }) },
                    onMoveChannelDown = { item -> viewModel.move(section, item.id, rows.indexOfFirst { it.id == item.id } + 2) },
                    onCancelMove = viewModel::cancelMove, jumpRequest = jumpRequest, onVisibleChannelWindowChanged = { _, _ -> }, onLoadMore = {},
                    modifier = Modifier.weight(1f), itemContent = { item, requester, pickedUp ->
                        OrderEditorRow(name = item.name, position = rowPositions[item.id] ?: 0,
                            badge = when {
                                pickedUp -> stringResource(R.string.badge_moving)
                                item.id in state.hiddenIds -> stringResource(R.string.live_category_order_hidden)
                                else -> null
                            }, enabled = !state.saving, modifier = Modifier.focusRequester(requester), onClick = {
                                if (pickedUp) { viewModel.finishMove(); dragging = null }
                                else { pickupPosition = rowPositions[item.id] ?: 0; viewModel.beginMove(section); dragging = item }
                            })
                    })
            }
        }
    }
    if (showProviderReset && !state.saving) AppDialog(onDismissRequest = { showProviderReset = false }) {
        Surface(shape = BrowseShapes.Panel, colors = SurfaceDefaults.colors(containerColor = AppColors.SurfaceElevated)) {
            Column(Modifier.widthIn(max = 520.dp).padding(spacing.lg), verticalArrangement = Arrangement.spacedBy(spacing.md)) {
                Text(stringResource(R.string.live_order_reset_manual_prompt),
                    style = androidx.tv.material3.MaterialTheme.typography.bodyLarge, color = AppColors.TextPrimary)
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                    OrderEditorAction(stringResource(R.string.action_cancel), onClick = { showProviderReset = false })
                    OrderEditorAction(stringResource(R.string.live_category_order_provider), emphasized = true, onClick = {
                        showProviderReset = false
                        viewModel.save(providerOrder = true)
                    })
                }
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
