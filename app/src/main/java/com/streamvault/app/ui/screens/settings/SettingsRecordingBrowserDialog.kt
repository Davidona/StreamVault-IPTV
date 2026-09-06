package com.streamvault.app.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.streamvault.app.R
import com.streamvault.app.ui.components.TvEmptyState
import com.streamvault.app.ui.theme.ErrorColor
import com.streamvault.app.ui.theme.OnBackground
import com.streamvault.app.ui.design.FocusSpec
import com.streamvault.app.ui.theme.FocusBorder
import com.streamvault.app.ui.interaction.TvClickableSurface
import com.streamvault.app.ui.theme.OnSurface
import com.streamvault.app.ui.theme.OnSurfaceDim
import com.streamvault.app.ui.theme.Primary
import com.streamvault.app.ui.theme.Secondary
import com.streamvault.app.ui.theme.SurfaceElevated
import com.streamvault.app.ui.time.LocalAppTimeFormat
import com.streamvault.app.ui.time.createDateTimeFormat
import com.streamvault.app.ui.theme.SurfaceHighlight
import com.streamvault.domain.model.RecordingFailureCategory
import com.streamvault.domain.model.RecordingItem
import com.streamvault.domain.model.RecordingRecurrence
import com.streamvault.domain.model.RecordingStatus
import androidx.compose.foundation.border
@Composable
internal fun RecordingBrowserDialog(
    recordingItems: List<RecordingItem>,
    selectedRecordingId: String?,
    onSelectedRecordingChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onPlay: (RecordingItem) -> Unit,
    onStop: (RecordingItem) -> Unit,
    onCancel: (RecordingItem) -> Unit,
    onSkipOccurrence: (RecordingItem) -> Unit,
    onDelete: (RecordingItem) -> Unit,
    onRetry: (RecordingItem) -> Unit,
    onToggleSchedule: (RecordingItem, Boolean) -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.68f)),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.93f)
                    .fillMaxHeight(0.9f),
                colors = SurfaceDefaults.colors(containerColor = SurfaceElevated),
                shape = RoundedCornerShape(22.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = stringResource(R.string.settings_recording_title),
                                style = MaterialTheme.typography.headlineSmall,
                                color = OnBackground
                            )
                            Text(
                                text = if (recordingItems.isNotEmpty()) {
                                    stringResource(R.string.settings_recording_item_count, recordingItems.size)
                                } else {
                                    stringResource(R.string.settings_recording_empty_title)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = OnSurfaceDim
                            )
                        }
                        CompactRecordingActionChip(
                            label = stringResource(R.string.settings_cancel),
                            accent = OnBackground,
                            onClick = onDismiss
                        )
                    }

                    if (recordingItems.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            TvEmptyState(
                                title = stringResource(R.string.settings_recording_empty_title),
                                subtitle = stringResource(R.string.settings_recording_empty_subtitle)
                            )
                        }
                    } else {
                        RecordingBrowserPanel(
                            recordingItems = recordingItems,
                            selectedRecordingId = selectedRecordingId,
                            onSelectedRecordingChange = onSelectedRecordingChange,
                            onPlay = onPlay,
                            onStop = onStop,
                            onCancel = onCancel,
                            onSkipOccurrence = onSkipOccurrence,
                            onDelete = onDelete,
                            onRetry = onRetry,
                            onToggleSchedule = onToggleSchedule
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordingBrowserPanel(
    recordingItems: List<RecordingItem>,
    selectedRecordingId: String?,
    onSelectedRecordingChange: (String) -> Unit,
    onPlay: (RecordingItem) -> Unit,
    onStop: (RecordingItem) -> Unit,
    onCancel: (RecordingItem) -> Unit,
    onSkipOccurrence: (RecordingItem) -> Unit,
    onDelete: (RecordingItem) -> Unit,
    onRetry: (RecordingItem) -> Unit,
    onToggleSchedule: (RecordingItem, Boolean) -> Unit
) {
    val selectedItem = recordingItems.firstOrNull { it.id == selectedRecordingId } ?: recordingItems.first()
    var searchQuery by remember { mutableStateOf("") }
    var statusFilter by remember { mutableStateOf<RecordingStatus?>(null) }
    val filteredItems = remember(recordingItems, searchQuery, statusFilter) {
        recordingItems.filter { item ->
            val matchesSearch = searchQuery.isBlank() ||
                item.channelName.contains(searchQuery, ignoreCase = true) ||
                item.programTitle?.contains(searchQuery, ignoreCase = true) == true
            val matchesStatus = statusFilter == null || item.status == statusFilter
            matchesSearch && matchesStatus
        }
    }

    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top
    ) {
        Surface(
            modifier = Modifier
                .width(380.dp)
                .fillMaxHeight(),
            colors = SurfaceDefaults.colors(containerColor = SurfaceElevated),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                RecordingBrowserSidebarControls(
                    filteredCount = filteredItems.size,
                    totalCount = recordingItems.size,
                    searchQuery = searchQuery,
                    onSearchQueryChange = { searchQuery = it },
                    statusFilter = statusFilter,
                    onStatusFilterChange = { statusFilter = it }
                )
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 4.dp)
                ) {
                    items(filteredItems, key = { item -> item.id }) { item ->
                        RecordingPickerRow(
                            item = item,
                            selected = item.id == selectedItem.id,
                            onSelected = { onSelectedRecordingChange(item.id) }
                        )
                    }
                }
            }
        }

        RecordingDetailPanel(
            item = selectedItem,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            onPlay = { onPlay(selectedItem) },
            onStop = { onStop(selectedItem) },
            onCancel = { onCancel(selectedItem) },
            onSkipOccurrence = { onSkipOccurrence(selectedItem) },
            onDelete = { onDelete(selectedItem) },
            onRetry = { onRetry(selectedItem) },
            onToggleSchedule = { enabled -> onToggleSchedule(selectedItem, enabled) }
        )
    }
}

@Composable
private fun RecordingPickerRow(
    item: RecordingItem,
    selected: Boolean,
    onSelected: () -> Unit
) {
    val accent = recordingStatusAccent(item.status)
    TvClickableSurface(
        onClick = onSelected,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) accent.copy(alpha = 0.14f) else Color.Transparent,
            contentColor = OnBackground,
            focusedContainerColor = SurfaceHighlight.copy(alpha = 0.48f),
            focusedContentColor = OnBackground
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = BorderStroke(
                    1.dp,
                    if (selected) accent.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.08f)
                ),
                shape = RoundedCornerShape(12.dp)
            ),
            focusedBorder = Border(
                border = BorderStroke(FocusSpec.BorderWidth, FocusBorder),
                shape = RoundedCornerShape(12.dp)
            )
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focusState ->
                if (focusState.isFocused) onSelected()
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(width = 5.dp, height = 36.dp)
                    .background(accent.copy(alpha = 0.92f), RoundedCornerShape(999.dp))
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = recordingDisplayTitle(item),
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = recordingListSecondaryLine(item),
                    style = MaterialTheme.typography.labelSmall,
                    color = OnSurfaceDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = recordingStatusLabel(item.status),
                style = MaterialTheme.typography.labelSmall,
                color = accent,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun RecordingDetailPanel(
    item: RecordingItem,
    modifier: Modifier = Modifier,
    onPlay: () -> Unit,
    onStop: () -> Unit,
    onCancel: () -> Unit,
    onSkipOccurrence: () -> Unit,
    onDelete: () -> Unit,
    onRetry: () -> Unit,
    onToggleSchedule: (Boolean) -> Unit
) {
    val accent = recordingStatusAccent(item.status)
    val appTimeFormat = LocalAppTimeFormat.current
    val dateTimeFormat = remember(appTimeFormat) { appTimeFormat.createDateTimeFormat() }
    Surface(
        modifier = modifier,
        colors = SurfaceDefaults.colors(containerColor = SurfaceElevated),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = recordingDisplayTitle(item),
                    style = MaterialTheme.typography.titleLarge,
                    color = OnBackground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                recordingDisplaySubtitle(item)?.let { subtitle ->
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = OnSurfaceDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatusTonePill(
                        label = recordingStatusLabel(item.status),
                        accent = accent
                    )
                    if (item.recurrence != RecordingRecurrence.NONE) {
                        StatusTonePill(
                            label = when (item.recurrence) {
                                RecordingRecurrence.DAILY -> stringResource(R.string.settings_recording_recurrence_daily)
                                RecordingRecurrence.WEEKLY -> stringResource(R.string.settings_recording_recurrence_weekly)
                                RecordingRecurrence.NONE -> stringResource(R.string.settings_recording_recurrence_none)
                            },
                            accent = Secondary
                        )
                    }
                }
                Text(
                    text = stringResource(
                        R.string.settings_recording_time_window,
                        formatTimestamp(item.scheduledStartMs, dateTimeFormat),
                        formatTimestamp(item.scheduledEndMs, dateTimeFormat)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurface
                )
            }

            RecordingDetailMetricsRow(item = item)

            if (item.retryCount > 0) {
                RecordingMetaPill(
                    label = stringResource(R.string.settings_recording_retry_count_label),
                    value = item.retryCount.toString()
                )
            }

            item.outputDisplayPath?.takeIf { output -> output.isNotBlank() }?.let { output ->
                Text(
                    text = "${stringResource(R.string.settings_recording_output_label)}: ${summarizeRecordingOutputPath(output)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            item.failureReason?.takeIf { reason -> reason.isNotBlank() }?.let { reason ->
                Text(
                    text = reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = ErrorColor
                )
            }

            if (item.failureCategory != RecordingFailureCategory.NONE) {
                Text(
                    text = "${stringResource(R.string.settings_recording_failure_label)}: ${formatRecordingFailureCategory(item.failureCategory)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = ErrorColor
                )
            }

            RecordingDetailActions(
                item = item,
                onPlay = onPlay,
                onStop = onStop,
                onCancel = onCancel,
                onSkipOccurrence = onSkipOccurrence,
                onDelete = onDelete,
                onRetry = onRetry,
                onToggleSchedule = onToggleSchedule
            )
        }
    }
}

