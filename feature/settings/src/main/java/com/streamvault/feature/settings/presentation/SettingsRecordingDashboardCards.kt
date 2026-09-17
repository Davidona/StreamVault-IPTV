package com.streamvault.feature.settings.presentation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
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
import com.streamvault.feature.settings.R
import com.streamvault.core.ui.interaction.TvClickableSurface
import com.streamvault.core.ui.theme.ErrorColor
import com.streamvault.core.ui.theme.FocusBorder
import com.streamvault.core.ui.theme.OnBackground
import com.streamvault.core.ui.theme.OnSurface
import com.streamvault.core.ui.theme.OnSurfaceDim
import com.streamvault.core.ui.theme.Primary
import com.streamvault.core.ui.theme.Secondary
import com.streamvault.core.ui.theme.SurfaceElevated

@OptIn(ExperimentalLayoutApi::class)
@Composable
public fun RecordingInfoCard(
    treeLabel: String?,
    outputDirectory: String?,
    availableBytes: Long?,
    isWritable: Boolean,
    activeCount: Int,
    scheduledCount: Int,
    fileNamePattern: String,
    retentionDays: Int?,
    maxSimultaneousRecordings: Int,
    paddingBeforeMinutes: Int,
    paddingAfterMinutes: Int
) {
    TvClickableSurface(
        onClick = { },
        modifier = Modifier.fillMaxWidth(),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = SurfaceElevated,
            focusedContainerColor = SurfaceElevated
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(16.dp)),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, FocusBorder),
                shape = RoundedCornerShape(16.dp)
            )
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = stringResource(R.string.settings_recording_storage_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = Primary
                    )
                    treeLabel?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = Secondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        text = outputDirectory ?: stringResource(R.string.settings_recording_storage_unknown),
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurfaceDim,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                StatusTonePill(
                    label = if (isWritable) {
                        stringResource(R.string.settings_recording_storage_ready)
                    } else {
                        stringResource(R.string.settings_recording_storage_unavailable)
                    },
                    accent = if (isWritable) OnSurface else ErrorColor
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SettingsOverviewStat(
                    label = stringResource(R.string.settings_recording_active_label),
                    value = activeCount.toString(),
                    modifier = Modifier.weight(1f)
                )
                SettingsOverviewStat(
                    label = stringResource(R.string.settings_recording_scheduled_label),
                    value = scheduledCount.toString(),
                    modifier = Modifier.weight(1f)
                )
                SettingsOverviewStat(
                    label = stringResource(R.string.settings_recording_space_label),
                    value = availableBytes?.let(::formatBytes) ?: stringResource(R.string.settings_recording_storage_unknown),
                    modifier = Modifier.weight(1f)
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                RecordingMetaPill(
                    label = stringResource(R.string.settings_recording_pattern_title),
                    value = fileNamePattern
                )
                RecordingMetaPill(
                    label = stringResource(R.string.settings_recording_retention_title),
                    value = retentionDays?.let {
                        stringResource(R.string.settings_recording_retention_days, it)
                    } ?: stringResource(R.string.settings_recording_retention_keep_all)
                )
                RecordingMetaPill(
                    label = stringResource(R.string.settings_recording_concurrency_title),
                    value = maxSimultaneousRecordings.toString()
                )
                RecordingMetaPill(
                    label = stringResource(R.string.settings_recording_padding_before),
                    value = if (paddingBeforeMinutes > 0) {
                        stringResource(R.string.settings_recording_padding_minutes, paddingBeforeMinutes)
                    } else {
                        stringResource(R.string.settings_recording_padding_none)
                    }
                )
                RecordingMetaPill(
                    label = stringResource(R.string.settings_recording_padding_after),
                    value = if (paddingAfterMinutes > 0) {
                        stringResource(R.string.settings_recording_padding_minutes, paddingAfterMinutes)
                    } else {
                        stringResource(R.string.settings_recording_padding_none)
                    }
                )
            }
        }
    }
}

@Composable
public fun RecordingActionsCard(
    wifiOnlyRecording: Boolean,
    onWifiOnlyRecordingChange: (Boolean) -> Unit,
    onChooseFolder: () -> Unit,
    onUseAppStorage: () -> Unit,
    onChangePattern: () -> Unit,
    onChangeRetention: () -> Unit,
    onChangeConcurrency: () -> Unit,
    onChangePadding: () -> Unit,
    onOpenBrowser: () -> Unit,
    onRepairSchedule: () -> Unit,
    onUseUsbStorage: (() -> Unit)? = null
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        colors = SurfaceDefaults.colors(containerColor = SurfaceElevated),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                RecordingActionButton(
                    label = stringResource(R.string.settings_recording_choose_folder),
                    accent = OnBackground,
                    modifier = Modifier.weight(1f),
                    onClick = onChooseFolder
                )
                RecordingActionButton(
                    label = stringResource(R.string.settings_recording_use_app_storage),
                    accent = OnBackground,
                    modifier = Modifier.weight(1f),
                    onClick = onUseAppStorage
                )
                if (onUseUsbStorage != null) {
                    RecordingActionButton(
                        label = stringResource(R.string.settings_recording_use_usb_storage),
                        accent = OnBackground,
                        modifier = Modifier.weight(1f),
                        onClick = onUseUsbStorage
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                RecordingActionButton(
                    label = stringResource(R.string.settings_recording_pattern_title),
                    accent = OnBackground,
                    modifier = Modifier.weight(1f),
                    onClick = onChangePattern
                )
                RecordingActionButton(
                    label = stringResource(R.string.settings_recording_retention_title),
                    accent = OnBackground,
                    modifier = Modifier.weight(1f),
                    onClick = onChangeRetention
                )
                RecordingActionButton(
                    label = stringResource(R.string.settings_recording_concurrency_title),
                    accent = OnBackground,
                    modifier = Modifier.weight(1f),
                    onClick = onChangeConcurrency
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                RecordingActionButton(
                    label = stringResource(R.string.settings_recording_open_browser),
                    accent = OnBackground,
                    modifier = Modifier.weight(1f),
                    onClick = onOpenBrowser
                )
                RecordingActionButton(
                    label = stringResource(R.string.settings_recording_reconcile),
                    accent = OnBackground,
                    modifier = Modifier.weight(1f),
                    onClick = onRepairSchedule
                )
                RecordingActionButton(
                    label = stringResource(R.string.settings_recording_padding_title),
                    accent = OnBackground,
                    modifier = Modifier.weight(1f),
                    onClick = onChangePadding
                )
            }
            TvClickableSurface(
                onClick = { onWifiOnlyRecordingChange(!wifiOnlyRecording) },
                modifier = Modifier.fillMaxWidth(),
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = Color.Transparent,
                    focusedContainerColor = Primary.copy(alpha = 0.12f)
                ),
                border = ClickableSurfaceDefaults.border(
                    focusedBorder = Border(
                        border = BorderStroke(2.dp, com.streamvault.core.ui.theme.FocusBorder),
                        shape = RoundedCornerShape(10.dp)
                    )
                ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.settings_recording_wifi_only),
                        style = MaterialTheme.typography.bodyMedium,
                        color = OnSurface
                    )
                    Switch(
                        checked = wifiOnlyRecording,
                        onCheckedChange = null,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Primary,
                            checkedTrackColor = Primary.copy(alpha = 0.3f),
                            uncheckedThumbColor = OnSurfaceDim,
                            uncheckedTrackColor = OnSurfaceDim.copy(alpha = 0.2f)
                        )
                    )
                }
            }
        }
    }
}
