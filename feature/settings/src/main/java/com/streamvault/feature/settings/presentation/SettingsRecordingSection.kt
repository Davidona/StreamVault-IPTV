package com.streamvault.feature.settings.presentation

import androidx.compose.foundation.lazy.LazyListScope
import com.streamvault.domain.model.RecordingStatus

public fun LazyListScope.settingsRecordingSection(
    uiState: SettingsUiState,
    viewModel: SettingsViewModel,
    onChooseFolder: () -> Unit,
    onUseUsbStorage: (() -> Unit)?,
    onShowRecordingPatternDialogChange: (Boolean) -> Unit,
    onShowRecordingRetentionDialogChange: (Boolean) -> Unit,
    onShowRecordingConcurrencyDialogChange: (Boolean) -> Unit,
    onShowRecordingPaddingDialogChange: (Boolean) -> Unit,
    onShowRecordingBrowserDialogChange: (Boolean) -> Unit
) {
    item {
        RecordingInfoCard(
            treeLabel = uiState.recordingStorageState.displayName,
            outputDirectory = uiState.recordingStorageState.outputDirectory,
            availableBytes = uiState.recordingStorageState.availableBytes,
            isWritable = uiState.recordingStorageState.isWritable,
            activeCount = uiState.recordingItems.count { it.status == RecordingStatus.RECORDING },
            scheduledCount = uiState.recordingItems.count { it.status == RecordingStatus.SCHEDULED },
            fileNamePattern = uiState.recordingStorageState.fileNamePattern,
            retentionDays = uiState.recordingStorageState.retentionDays,
            maxSimultaneousRecordings = uiState.recordingStorageState.maxSimultaneousRecordings,
            paddingBeforeMinutes = uiState.recordingPaddingBeforeMinutes,
            paddingAfterMinutes = uiState.recordingPaddingAfterMinutes
        )
    }
    item {
        RecordingActionsCard(
            wifiOnlyRecording = uiState.wifiOnlyRecording,
            onWifiOnlyRecordingChange = { viewModel.setRecordingWifiOnly(it) },
            onChooseFolder = onChooseFolder,
            onUseAppStorage = { viewModel.updateRecordingFolder(null, null) },
            onUseUsbStorage = onUseUsbStorage,
            onChangePattern = { onShowRecordingPatternDialogChange(true) },
            onChangeRetention = { onShowRecordingRetentionDialogChange(true) },
            onChangeConcurrency = { onShowRecordingConcurrencyDialogChange(true) },
            onChangePadding = { onShowRecordingPaddingDialogChange(true) },
            onRepairSchedule = { viewModel.reconcileRecordings() },
            onOpenBrowser = { onShowRecordingBrowserDialogChange(true) }
        )
    }
}
