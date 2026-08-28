package com.streamvault.feature.settings.presentation

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.feature.settings.R
import com.streamvault.core.ui.theme.OnSurfaceDim

@Composable
public fun SettingsRecordingDialogs(
    uiState: SettingsUiState,
    viewModel: SettingsViewModel,
    showRecordingPatternDialog: Boolean,
    onShowRecordingPatternDialogChange: (Boolean) -> Unit,
    showRecordingRetentionDialog: Boolean,
    onShowRecordingRetentionDialogChange: (Boolean) -> Unit,
    showRecordingConcurrencyDialog: Boolean,
    onShowRecordingConcurrencyDialogChange: (Boolean) -> Unit,
    showRecordingPaddingDialog: Boolean,
    onShowRecordingPaddingDialogChange: (Boolean) -> Unit
) {
    if (showRecordingPatternDialog) {
        SimpleTextValueDialog(
            title = stringResource(R.string.settings_recording_pattern_title),
            subtitle = stringResource(R.string.settings_recording_pattern_hint),
            initialValue = uiState.recordingStorageState.fileNamePattern,
            onDismiss = { onShowRecordingPatternDialogChange(false) },
            onConfirm = { pattern ->
                viewModel.updateRecordingFileNamePattern(pattern)
                onShowRecordingPatternDialogChange(false)
            }
        )
    }

    if (showRecordingRetentionDialog) {
        val retentionOptions = listOf<Int?>(null, 7, 14, 30, 60, 90)
        PremiumSelectionDialog(
            title = stringResource(R.string.settings_recording_retention_title),
            onDismiss = { onShowRecordingRetentionDialogChange(false) }
        ) {
            retentionOptions.forEachIndexed { index, days ->
                LevelOption(
                    level = index,
                    text = if (days == null) {
                        stringResource(R.string.settings_recording_retention_keep_all)
                    } else {
                        stringResource(R.string.settings_recording_retention_days, days)
                    },
                    currentLevel = if (days == uiState.recordingStorageState.retentionDays) index else -1,
                    onSelect = {
                        viewModel.updateRecordingRetentionDays(days)
                        onShowRecordingRetentionDialogChange(false)
                    }
                )
            }
        }
    }

    if (showRecordingConcurrencyDialog) {
        PremiumSelectionDialog(
            title = stringResource(R.string.settings_recording_concurrency_title),
            onDismiss = { onShowRecordingConcurrencyDialogChange(false) }
        ) {
            (1..4).forEach { value ->
                LevelOption(
                    level = value,
                    text = value.toString(),
                    currentLevel = if (value == uiState.recordingStorageState.maxSimultaneousRecordings) value else -1,
                    onSelect = {
                        viewModel.updateRecordingMaxSimultaneous(value)
                        onShowRecordingConcurrencyDialogChange(false)
                    }
                )
            }
        }
    }

    if (showRecordingPaddingDialog) {
        val paddingOptions = listOf(0, 1, 2, 3, 5, 10, 15, 30)
        PremiumSelectionDialog(
            title = stringResource(R.string.settings_recording_padding_title),
            onDismiss = { onShowRecordingPaddingDialogChange(false) }
        ) {
            Text(
                text = stringResource(R.string.settings_recording_padding_before),
                style = MaterialTheme.typography.labelMedium,
                color = OnSurfaceDim,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            paddingOptions.forEach { minutes ->
                LevelOption(
                    level = minutes,
                    text = if (minutes == 0) {
                        stringResource(R.string.settings_recording_padding_none)
                    } else {
                        stringResource(R.string.settings_recording_padding_minutes, minutes)
                    },
                    currentLevel = uiState.recordingPaddingBeforeMinutes,
                    onSelect = {
                        viewModel.setRecordingPaddingBeforeMinutes(minutes)
                    }
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.settings_recording_padding_after),
                style = MaterialTheme.typography.labelMedium,
                color = OnSurfaceDim,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            paddingOptions.forEach { minutes ->
                LevelOption(
                    level = minutes,
                    text = if (minutes == 0) {
                        stringResource(R.string.settings_recording_padding_none)
                    } else {
                        stringResource(R.string.settings_recording_padding_minutes, minutes)
                    },
                    currentLevel = uiState.recordingPaddingAfterMinutes,
                    onSelect = {
                        viewModel.setRecordingPaddingAfterMinutes(minutes)
                    }
                )
            }
        }
    }
}
