package com.streamvault.app.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.streamvault.app.R
import com.streamvault.app.ui.design.FocusSpec
import com.streamvault.app.ui.theme.FocusBorder
import com.streamvault.app.ui.interaction.TvClickableSurface
import com.streamvault.app.ui.theme.ErrorColor
import com.streamvault.app.ui.theme.OnBackground
import com.streamvault.app.ui.theme.OnSurfaceDim
import com.streamvault.app.ui.theme.Primary
import com.streamvault.app.ui.theme.Secondary
import com.streamvault.app.ui.theme.SurfaceElevated
import com.streamvault.app.ui.time.LocalAppTimeFormat
import com.streamvault.app.ui.time.createDateTimeFormat
import com.streamvault.domain.model.RecordingFailureCategory
import com.streamvault.domain.model.RecordingItem
import com.streamvault.domain.model.RecordingRecurrence
import com.streamvault.domain.model.RecordingStatus

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RecordingItemCard(
    item: RecordingItem,
    onPlay: () -> Unit,
    onStop: () -> Unit,
    onCancel: () -> Unit,
    onSkipOccurrence: () -> Unit,
    onDelete: () -> Unit,
    onRetry: () -> Unit,
    onToggleSchedule: (Boolean) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        colors = SurfaceDefaults.colors(containerColor = SurfaceElevated),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        item.programTitle ?: item.channelName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = OnBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (item.programTitle != null && item.programTitle != item.channelName) {
                        Text(
                            item.channelName,
                            style = MaterialTheme.typography.labelSmall,
                            color = OnSurfaceDim,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Text(
                    text = when (item.status) {
                        RecordingStatus.SCHEDULED -> stringResource(R.string.settings_recording_status_scheduled)
                        RecordingStatus.RECORDING -> stringResource(R.string.settings_recording_status_recording)
                        RecordingStatus.COMPLETED -> stringResource(R.string.settings_recording_status_completed)
                        RecordingStatus.FAILED -> stringResource(R.string.settings_recording_status_failed)
                        RecordingStatus.CANCELLED -> stringResource(R.string.settings_recording_status_cancelled)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = when (item.status) {
                        RecordingStatus.RECORDING -> Primary
                        RecordingStatus.COMPLETED -> OnBackground
                        RecordingStatus.FAILED -> ErrorColor
                        RecordingStatus.CANCELLED -> OnSurfaceDim
                        RecordingStatus.SCHEDULED -> Secondary
                    }
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val appTimeFormat = LocalAppTimeFormat.current
                val dateTimeFormat = remember(appTimeFormat) { appTimeFormat.createDateTimeFormat() }
                Text(
                    text = stringResource(
                        R.string.settings_recording_time_window,
                        formatTimestamp(item.scheduledStartMs, dateTimeFormat),
                        formatTimestamp(item.scheduledEndMs, dateTimeFormat)
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = OnSurfaceDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (item.recurrence != RecordingRecurrence.NONE) {
                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(start = 8.dp))
                    Text(
                        text = when (item.recurrence) {
                            RecordingRecurrence.DAILY -> stringResource(R.string.settings_recording_recurrence_daily)
                            RecordingRecurrence.WEEKLY -> stringResource(R.string.settings_recording_recurrence_weekly)
                            RecordingRecurrence.NONE -> stringResource(R.string.settings_recording_recurrence_none)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = Secondary,
                        maxLines = 1
                    )
                }
            }
            item.failureReason?.takeIf { it.isNotBlank() }?.let { reason ->
                Text(reason, style = MaterialTheme.typography.bodySmall, color = ErrorColor)
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth(),
                maxItemsInEachRow = 4
            ) {
                RecordingMetaPill(
                    label = stringResource(R.string.settings_recording_source_label),
                    value = formatRecordingSourceType(item.sourceType)
                )
                RecordingMetaPill(
                    label = stringResource(R.string.settings_recording_bytes_label),
                    value = formatBytes(item.bytesWritten)
                )
                RecordingMetaPill(
                    label = stringResource(R.string.settings_recording_speed_label),
                    value = if (item.averageThroughputBytesPerSecond > 0L) {
                        "${formatBytes(item.averageThroughputBytesPerSecond)}/s"
                    } else {
                        "N/A"
                    }
                )
                if (item.retryCount > 0) {
                    RecordingMetaPill(
                        label = stringResource(R.string.settings_recording_retry_count_label),
                        value = item.retryCount.toString()
                    )
                }
            }
            item.outputDisplayPath?.takeIf { it.isNotBlank() }?.let { output ->
                Text(
                    text = "${stringResource(R.string.settings_recording_output_label)}: ${summarizeRecordingOutputPath(output)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = OnSurfaceDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (item.failureCategory != RecordingFailureCategory.NONE) {
                Text(
                    text = "${stringResource(R.string.settings_recording_failure_label)}: ${formatRecordingFailureCategory(item.failureCategory)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = ErrorColor
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth(),
                maxItemsInEachRow = 4
            ) {
                if (item.status == RecordingStatus.COMPLETED && (!item.outputUri.isNullOrBlank() || !item.outputPath.isNullOrBlank())) {
                    CompactRecordingActionChip(
                        label = "Play",
                        accent = Primary,
                        onClick = onPlay
                    )
                }
                if (item.status == RecordingStatus.RECORDING) {
                    CompactRecordingActionChip(
                        label = stringResource(R.string.settings_recording_stop),
                        accent = ErrorColor,
                        onClick = onStop
                    )
                }
                if (item.status == RecordingStatus.SCHEDULED) {
                    CompactRecordingActionChip(
                        label = stringResource(
                            if (item.scheduleEnabled) R.string.settings_recording_disable
                            else R.string.settings_recording_enable
                        ),
                        accent = Secondary,
                        onClick = { onToggleSchedule(!item.scheduleEnabled) }
                    )
                    if (item.recurringRuleId != null) {
                        CompactRecordingActionChip(
                            label = stringResource(R.string.settings_recording_skip),
                            accent = OnBackground,
                            onClick = onSkipOccurrence
                        )
                    }
                    CompactRecordingActionChip(
                        label = stringResource(R.string.settings_recording_cancel),
                        accent = OnBackground,
                        onClick = onCancel
                    )
                }
                if (item.status == RecordingStatus.COMPLETED || item.status == RecordingStatus.FAILED || item.status == RecordingStatus.CANCELLED) {
                    if (item.status == RecordingStatus.FAILED) {
                        CompactRecordingActionChip(
                            label = stringResource(R.string.settings_recording_retry),
                            accent = Primary,
                            onClick = onRetry
                        )
                    }
                    CompactRecordingActionChip(
                        label = stringResource(R.string.settings_recording_delete),
                        accent = OnBackground,
                        onClick = onDelete
                    )
                }
            }
        }
    }
}

@Composable
internal fun CompactRecordingActionChip(label: String, accent: Color, onClick: () -> Unit) {
    TvClickableSurface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = accent.copy(alpha = 0.14f),
            focusedContainerColor = accent.copy(alpha = 0.3f)
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(FocusSpec.BorderWidth, FocusBorder),
                shape = RoundedCornerShape(8.dp)
            )
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
    ) {
        Text(
            text = label,
            color = accent,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
        )
    }
}

internal fun RecordingItem.playbackUrl(): String? {
    val persistedUri = outputUri?.trim()?.takeIf { it.isNotBlank() }
    if (persistedUri != null) {
        return persistedUri
    }
    val localPath = outputPath?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val parsed = runCatching { android.net.Uri.parse(localPath) }.getOrNull()
    return if (parsed?.scheme.isNullOrBlank()) {
        android.net.Uri.fromFile(java.io.File(localPath)).toString()
    } else {
        localPath
    }
}