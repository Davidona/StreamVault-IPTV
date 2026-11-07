package com.streamvault.feature.live.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.streamvault.core.ui.components.dialogs.AppDialog
import com.streamvault.core.ui.components.dialogs.PinDialog
import com.streamvault.core.ui.design.BrowseShapes
import com.streamvault.core.ui.design.browse.OnSurface
import com.streamvault.core.ui.design.browse.SurfaceElevated
import com.streamvault.core.ui.interaction.TvButton
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.LiveChannelOrderMode
import com.streamvault.domain.policy.liveChannelPresetModes
import com.streamvault.feature.live.R
import com.streamvault.feature.live.phone.PhoneOrderingDialog
import com.streamvault.feature.live.presentation.components.LiveReorderTopBar
import com.streamvault.feature.live.presentation.dialogs.LiveChannelMoveDialog

/** Shared provider editor for Live TV long-press and provider Category controls. */
@Composable
fun ProviderChannelOrderEditor(
    providerId: Long,
    category: Category,
    onDismiss: () -> Unit,
    openModePickerOnStart: Boolean = false,
    viewModel: ProviderChannelOrderViewModel = hiltViewModel(key = "channel-order:$providerId:${category.id}")
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showDiscard by remember { mutableStateOf(false) }
    var showModes by remember(openModePickerOnStart) { mutableStateOf(openModePickerOnStart) }
    var showTools by remember { mutableStateOf(false) }
    var showResetConfirmation by remember { mutableStateOf(false) }
    var showPhone by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf<Channel?>(null) }
    var focused by remember { mutableStateOf<Long?>(null) }
    var jumpRequest by remember { mutableStateOf<Pair<Int, Long>?>(null) }
    var jumpToken by remember { mutableLongStateOf(0L) }
    val requesters = remember { mutableMapOf<Long, FocusRequester>() }
    LaunchedEffect(providerId, category.id) { viewModel.open(providerId, category) }
    DisposableEffect(viewModel) { onDispose { viewModel.reset() } }
    LaunchedEffect(state.closed) { if (state.closed) onDismiss() }
    val dismiss = { if (!state.saving) {
        if (viewModel.hasChanges()) showDiscard = true else onDismiss()
    } }
    if (state.locked) {
        PinDialog(onDismissRequest = dismiss, onPinEntered = viewModel::unlock,
            title = stringResource(R.string.pin_dialog_title), cancelLabel = stringResource(R.string.pin_dialog_cancel),
            error = state.message?.let { stringResource(it) })
        return
    }
    AppDialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false,
        dismissOnBackPress = !state.saving, dismissOnClickOutside = false)) {
        Surface(modifier = Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.92f), shape = BrowseShapes.Panel,
            colors = SurfaceDefaults.colors(containerColor = SurfaceElevated)) {
            Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LiveReorderTopBar(categoryName = state.category?.name ?: category.name,
                    onSave = { viewModel.save() }, onCancel = dismiss,
                    enabled = !state.saving, saveEnabled = state.draft != null && !state.loading,
                    onOpenTools = if (state.draft != null) ({ showTools = true }) else null,
                    onUndo = viewModel::undo, onRedo = viewModel::redo,
                    canUndo = state.draft?.canUndo == true || state.canUndoReset,
                    canRedo = state.draft?.canRedo == true || state.canRedoReset)
                state.message?.let { Text(stringResource(it), color = OnSurface) }
                if (state.loading) Text(stringResource(R.string.home_loading_channels), color = OnSurface)
                if (!state.loading && state.draft == null) TvButton(onClick = dismiss) { Text(stringResource(R.string.action_cancel)) }
                if (state.draft != null) {
                    TvButton(onClick = { showPhone = true }, enabled = !state.saving && !viewModel.hasChanges()) {
                        Text(stringResource(R.string.live_phone_start))
                    }
                    TvButton(onClick = { showModes = true }, enabled = !state.saving) {
                        Text(stringResource(R.string.live_order_mode))
                        Spacer(Modifier.width(16.dp))
                        Text(liveChannelOrderModeLabel(state.mode))
                        if (state.mode == LiveChannelOrderMode.USE_DEFAULT) {
                            Spacer(Modifier.width(16.dp))
                            Text(liveChannelOrderModeLabel(state.defaultMode))
                        }
                    }
                    if (state.hasManualOrder) {
                        TvButton(onClick = { showResetConfirmation = true }, enabled = !state.saving) {
                            Text(stringResource(R.string.live_order_reset_manual))
                        }
                    }
                    LiveChannelListHost(channels = state.channels, hasMoreChannels = false, isReorderMode = true,
                        draggingChannel = dragging, focusedChannelId = focused, channelFocusRequesters = requesters,
                        contentPaddingBottom = 12.dp, channelListSpacing = 8.dp,
                        onDraggingChannelChange = { dragging = it }, onExitReorderMode = dismiss,
                        onMoveChannelUp = { channel -> viewModel.move(channel, state.channels.indexOfFirst { it.id == channel.id }) },
                        onMoveChannelDown = { channel -> viewModel.move(channel, state.channels.indexOfFirst { it.id == channel.id } + 2) },
                        onCancelMove = viewModel::cancelMove, onVisibleChannelWindowChanged = { _, _ -> }, onLoadMore = {},
                        jumpRequest = jumpRequest,
                        modifier = Modifier.weight(1f), itemContent = { channel, requester, pickedUp ->
                                TvButton(onClick = {
                                    if (pickedUp) { viewModel.finishMove(); dragging = null }
                                    else { viewModel.beginMove(); dragging = channel }
                                    focused = channel.id
                                }, enabled = !state.saving, modifier = Modifier.fillMaxWidth().focusRequester(requester)
                                    .onFocusChanged { if (it.isFocused) focused = channel.id }) {
                                    Text(channel.name)
                                    if (pickedUp) Text(stringResource(R.string.badge_moving), Modifier.padding(start = 16.dp))
                                }
                        })
                }
            }
        }
    }
    if (showModes && !state.saving) AppDialog(onDismissRequest = { showModes = false }) {
        Surface(shape = BrowseShapes.Panel, colors = SurfaceDefaults.colors(containerColor = SurfaceElevated)) {
            Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.live_order_mode), color = OnSurface)
                Text(stringResource(R.string.live_order_usage_help), color = com.streamvault.core.ui.design.browse.OnSurfaceDim)
                val choices = listOf(LiveChannelOrderMode.USE_DEFAULT) + liveChannelPresetModes +
                    if (state.hasManualOrder) listOf(LiveChannelOrderMode.MANUAL) else emptyList()
                choices.forEach { mode ->
                    TvButton(onClick = { viewModel.selectMode(mode); showModes = false }, modifier = Modifier.fillMaxWidth()) {
                        Text(liveChannelOrderModeLabel(mode))
                        if (mode == LiveChannelOrderMode.USE_DEFAULT) {
                            Spacer(Modifier.width(16.dp))
                            Text(liveChannelOrderModeLabel(state.defaultMode))
                        }
                    }
                }
                TvButton(onClick = { showModes = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        }
    }
    if (showResetConfirmation && !state.saving) AppDialog(onDismissRequest = { showResetConfirmation = false }) {
        Surface(shape = BrowseShapes.Panel, colors = SurfaceDefaults.colors(containerColor = SurfaceElevated)) {
            Column(Modifier.widthIn(max = 520.dp).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.live_order_reset_manual_prompt), color = OnSurface)
                TvButton(onClick = {
                    showResetConfirmation = false
                    viewModel.resetManualArrangement()
                }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.live_order_reset_manual))
                }
                TvButton(onClick = { showResetConfirmation = false }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        }
    }
    if (showDiscard && !state.saving) com.streamvault.feature.live.presentation.dialogs.LiveOrderDiscardDialog(
        onDiscard = { showDiscard = false; onDismiss() }, onKeepEditing = { showDiscard = false })
    if (showTools && !state.saving) LiveChannelMoveDialog(
        state.channels,
        onMove = viewModel::move,
        onDismiss = { showTools = false },
        onMoveMany = viewModel::moveMany,
        onJumpToPosition = { position ->
            jumpToken += 1
            jumpRequest = position to jumpToken
        }
    )
    if (showPhone) PhoneOrderingDialog(providerId, category.id, onDismiss = {
        showPhone = false
        viewModel.open(providerId, category)
    })
}

@Composable
internal fun liveChannelOrderModeLabel(mode: LiveChannelOrderMode): String = stringResource(when (mode) {
    LiveChannelOrderMode.USE_DEFAULT -> R.string.live_order_use_default
    LiveChannelOrderMode.TITLE_ASC -> R.string.live_order_title_asc
    LiveChannelOrderMode.TITLE_DESC -> R.string.live_order_title_desc
    LiveChannelOrderMode.MANUAL -> R.string.live_category_order_manual
    LiveChannelOrderMode.RECENTLY_WATCHED -> R.string.live_order_recent
    LiveChannelOrderMode.MOST_WATCHED -> R.string.live_order_most
    LiveChannelOrderMode.PROVIDER -> R.string.live_category_order_provider
})
