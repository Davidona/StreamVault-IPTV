package com.streamvault.app.ui.screens.settings

import com.streamvault.feature.settings.presentation.formatBytes
import com.streamvault.feature.settings.presentation.formatRecordingSourceType
import com.streamvault.feature.settings.presentation.RecordingMetricCard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.streamvault.app.R
import com.streamvault.core.ui.theme.ErrorColor
import com.streamvault.core.ui.theme.OnBackground
import com.streamvault.core.ui.theme.Primary
import com.streamvault.core.ui.theme.Secondary
import com.streamvault.domain.model.RecordingItem
import com.streamvault.domain.model.RecordingStatus

@Composable
internal fun RecordingDetailMetricsRow(
    item: RecordingItem,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        RecordingMetricCard(
            modifier = Modifier.weight(1f),
            label = stringResource(R.string.settings_recording_source_label),
            value = formatRecordingSourceType(item.sourceType)
        )
        RecordingMetricCard(
            modifier = Modifier.weight(1f),
            label = stringResource(R.string.settings_recording_bytes_label),
            value = formatBytes(item.bytesWritten)
        )
        RecordingMetricCard(
            modifier = Modifier.weight(1f),
            label = stringResource(R.string.settings_recording_speed_label),
            value = if (item.averageThroughputBytesPerSecond > 0L) {
                "${formatBytes(item.averageThroughputBytesPerSecond)}/s"
            } else {
                "N/A"
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RecordingDetailActions(
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
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxWidth(),
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
