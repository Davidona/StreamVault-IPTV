package com.streamvault.feature.live.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.streamvault.domain.model.Channel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

@Composable
fun LiveChannelListHost(
    channels: List<Channel>,
    hasMoreChannels: Boolean,
    isReorderMode: Boolean,
    draggingChannel: Channel?,
    focusedChannelId: Long?,
    channelFocusRequesters: MutableMap<Long, FocusRequester>,
    contentPaddingBottom: Dp,
    channelListSpacing: Dp,
    onDraggingChannelChange: (Channel?) -> Unit,
    onExitReorderMode: () -> Unit,
    onMoveChannelUp: (Channel) -> Unit,
    onMoveChannelDown: (Channel) -> Unit,
    onVisibleChannelWindowChanged: (List<Long>, Long?) -> Unit,
    onLoadMore: () -> Unit,
    itemContent: @Composable (channel: Channel, focusRequester: FocusRequester, isDragging: Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val channelListState = rememberLazyListState()

    LaunchedEffect(isReorderMode, draggingChannel?.id, channels) {
        if (!isReorderMode) return@LaunchedEffect
        val draggingChannelId = draggingChannel?.id ?: return@LaunchedEffect
        val draggedIndex = channels.indexOfFirst { it.id == draggingChannelId }
        if (draggedIndex < 0) return@LaunchedEffect

        val visibleItems = channelListState.layoutInfo.visibleItemsInfo
        val firstVisibleIndex = visibleItems.firstOrNull()?.index
        val lastVisibleIndex = visibleItems.lastOrNull()?.index
        if (
            firstVisibleIndex != null &&
            lastVisibleIndex != null &&
            (draggedIndex <= firstVisibleIndex || draggedIndex >= lastVisibleIndex)
        ) {
            channelListState.scrollToItem(draggedIndex)
        }

        runCatching { channelFocusRequesters[draggingChannelId]?.requestFocus() }
    }

    LaunchedEffect(channelListState, channels, focusedChannelId) {
        snapshotFlow {
            channelListState.layoutInfo.visibleItemsInfo.mapNotNull { item ->
                channels.getOrNull(item.index)?.id
            } to focusedChannelId
        }
            .distinctUntilChanged()
            .collect { (visibleIds, focusedId) ->
                onVisibleChannelWindowChanged(visibleIds, focusedId)
            }
    }

    LaunchedEffect(channelListState) {
        snapshotFlow {
            val info = channelListState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            val total = info.totalItemsCount
            hasMoreChannels && total > 0 && lastVisible >= total - 5
        }
            .distinctUntilChanged()
            .filter { it }
            .collect { onLoadMore() }
    }

    DisposableEffect(Unit) {
        onDispose { onVisibleChannelWindowChanged(emptyList(), null) }
    }

    LazyColumn(
        state = channelListState,
        modifier = modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (!isReorderMode || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.nativeKeyEvent.keyCode) {
                    android.view.KeyEvent.KEYCODE_BACK -> {
                        if (draggingChannel != null) {
                            onDraggingChannelChange(null)
                        } else {
                            onExitReorderMode()
                        }
                        true
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT,
                    android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                        draggingChannel?.let(onMoveChannelUp)
                        draggingChannel != null
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
                    android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                        draggingChannel?.let(onMoveChannelDown)
                        draggingChannel != null
                    }
                    else -> false
                }
            },
        contentPadding = PaddingValues(start = 10.dp, end = 10.dp, bottom = contentPaddingBottom),
        verticalArrangement = Arrangement.spacedBy(channelListSpacing)
    ) {
        items(
            items = channels,
            key = { it.id },
            contentType = { "live_channel" }
        ) { channel ->
            val isDragging = draggingChannel == channel
            val focusRequester = channelFocusRequesters.getOrPut(channel.id) { FocusRequester() }
            itemContent(channel, focusRequester, isDragging)
        }
    }
}
