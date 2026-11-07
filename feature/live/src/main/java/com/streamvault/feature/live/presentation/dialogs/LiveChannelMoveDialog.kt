package com.streamvault.feature.live.presentation.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import androidx.tv.material3.Icon
import com.streamvault.core.ui.components.TvOutlinedTextField
import com.streamvault.core.ui.components.dialogs.AppDialog
import com.streamvault.core.ui.design.BrowseShapes
import com.streamvault.core.ui.design.browse.SurfaceElevated
import com.streamvault.core.ui.design.browse.OnSurface
import com.streamvault.core.ui.design.browse.OnSurfaceDim
import com.streamvault.core.ui.design.browse.Primary
import com.streamvault.core.ui.interaction.TvButton
import com.streamvault.domain.model.Channel
import com.streamvault.feature.live.R

/** Search locates a row in the complete draft; moves always use absolute positions. */
@Composable
fun LiveChannelMoveDialog(
    channels: List<Channel>,
    onMove: (Channel, Int) -> Unit,
    onDismiss: () -> Unit,
    title: String = stringResource(R.string.home_reorder_channels),
    searchLabel: String = stringResource(R.string.home_search_channels),
    onMoveMany: ((List<Channel>, Int) -> Unit)? = null,
    onJumpToPosition: ((Int) -> Unit)? = null
) {
    var query by remember { mutableStateOf("") }
    var selectedKeys by remember { mutableStateOf(emptySet<String>()) }
    var multiple by remember { mutableStateOf(false) }
    var showActions by remember { mutableStateOf(false) }
    var destinationAfter by remember { mutableStateOf<Boolean?>(null) }
    var position by remember { mutableStateOf("") }
    var selectedPosition by remember { mutableStateOf<Int?>(null) }
    val searchFocus = remember { FocusRequester() }
    fun key(channel: Channel) = "${channel.providerId}:${channel.id}"
    val selectedChannels = channels.filter { key(it) in selectedKeys }
    val maxPosition = channels.size - selectedChannels.size + 1
    fun move(target: Int) {
        if (selectedChannels.isEmpty() || target !in 1..maxPosition) return
        if (onMoveMany != null) onMoveMany(selectedChannels, target)
        else onMove(selectedChannels.single(), target)
        onDismiss()
    }
    AppDialog(onDismissRequest = onDismiss) {
        Surface(shape = BrowseShapes.Panel, colors = SurfaceDefaults.colors(containerColor = SurfaceElevated)) {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().heightIn(max = 460.dp)
                .verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!showActions) {
                    LaunchedEffect(Unit) { searchFocus.requestFocus() }
                    Text(destinationAfter?.let { stringResource(if (it) R.string.live_order_after else R.string.live_order_before) }
                        ?: title, color = OnSurface)
                    TvOutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().focusRequester(searchFocus), label = { Text(searchLabel, color = OnSurfaceDim) },
                        colors = { liveOrderFieldColors() })
                    val results = remember(channels, query, destinationAfter, selectedKeys) {
                        channels.withIndex().filter { it.value.name.contains(query.trim(), ignoreCase = true) &&
                            (destinationAfter == null || key(it.value) !in selectedKeys) }
                    }
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 240.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(results, key = { _, item -> "${item.value.providerId}:${item.value.id}" }) { _, item ->
                            val isSelected = key(item.value) in selectedKeys
                            TvButton(onClick = {
                                val after = destinationAfter
                                if (after != null) {
                                    val remaining = channels.filterNot { key(it) in selectedKeys }
                                    move(remaining.indexOfFirst { key(it) == key(item.value) } + if (after) 2 else 1)
                                } else if (multiple) {
                                    selectedKeys = if (isSelected) selectedKeys - key(item.value) else selectedKeys + key(item.value)
                                } else {
                                    selectedKeys = setOf(key(item.value))
                                    position = (item.index + 1).toString()
                                    selectedPosition = item.index + 1
                                    showActions = true
                                }
                            }, modifier = Modifier.fillMaxWidth().semantics { selected = isSelected }) {
                                Text((item.index + 1).toString(), Modifier.padding(end = 12.dp))
                                Text(item.value.name, Modifier.weight(1f))
                                if (isSelected) Icon(Icons.Rounded.Check, contentDescription = null)
                            }
                        }
                    }
                    if (onMoveMany != null && destinationAfter == null) {
                        if (!multiple) TvButton(onClick = { multiple = true }) { Text(stringResource(R.string.live_order_select_multiple)) }
                        else {
                            TvButton(onClick = { selectedKeys = selectedKeys + results.map { key(it.value) } }, enabled = results.isNotEmpty()) {
                                Text(stringResource(R.string.live_order_select_results))
                            }
                            TvButton(onClick = {
                                position = (channels.indexOfFirst { key(it) in selectedKeys } + 1).coerceAtMost(maxPosition).toString()
                                showActions = true
                            }, enabled = selectedKeys.isNotEmpty()) { Text(stringResource(R.string.live_order_move_selected, selectedKeys.size)) }
                        }
                    }
                } else {
                    Text(selectedChannels.singleOrNull()?.name ?: stringResource(R.string.live_order_move_selected, selectedChannels.size), color = OnSurface)
                    if (selectedChannels.size == 1 && onJumpToPosition != null) {
                        TvButton(onClick = {
                            selectedPosition?.let(onJumpToPosition)
                            onDismiss()
                        }, enabled = selectedPosition != null) {
                            Text(stringResource(R.string.live_order_jump_to_position))
                        }
                    }
                    TvButton(onClick = { move(1) }) { Text(stringResource(R.string.live_order_top)) }
                    TvButton(onClick = { move(maxPosition) }) { Text(stringResource(R.string.live_order_bottom)) }
                    TvOutlinedTextField(value = position, onValueChange = { value -> if (value.all(Char::isDigit)) position = value },
                        label = { Text(stringResource(R.string.live_order_position, maxPosition), color = OnSurfaceDim) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), colors = { liveOrderFieldColors() })
                    val target = position.toIntOrNull()
                    TvButton(onClick = { target?.let(::move) }, enabled = target != null && target in 1..maxPosition) {
                        Text(stringResource(R.string.live_order_move))
                    }
                    listOf(false, true).forEach { after ->
                        TvButton(onClick = { destinationAfter = after; query = ""; showActions = false }, enabled = selectedChannels.size < channels.size) {
                            Text(stringResource(if (after) R.string.live_order_after else R.string.live_order_before))
                        }
                    }
                }
                TvButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        }
    }
}

@Composable
private fun liveOrderFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = OnSurface, unfocusedTextColor = OnSurface,
    focusedLabelColor = Primary, unfocusedLabelColor = OnSurfaceDim,
    focusedBorderColor = Primary, unfocusedBorderColor = OnSurfaceDim.copy(alpha = 0.5f),
    cursorColor = Primary
)
