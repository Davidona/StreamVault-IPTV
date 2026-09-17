package com.streamvault.feature.settings.presentation

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.streamvault.feature.settings.R
import com.streamvault.domain.model.RecordingStatus

public fun LazyListScope.settingsRecordingSection(
    uiState: SettingsUiState,
    page: SettingsPage? = null,
    viewModel: SettingsViewModel,
    onChooseFolder: () -> Unit,
    onUseUsbStorage: (() -> Unit)?,
    onShowRecordingPatternDialogChange: (Boolean) -> Unit,
    onShowRecordingRetentionDialogChange: (Boolean) -> Unit,
    onShowRecordingConcurrencyDialogChange: (Boolean) -> Unit,
    onShowRecordingPaddingDialogChange: (Boolean) -> Unit,
    onShowRecordingBrowserDialogChange: (Boolean) -> Unit,
    targetItemId: String? = null,
    targetFocusModifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
) {
    if (page == null || page == SettingsPage.RECORDING_STATUS) item {
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
    if (page == null || page == SettingsPage.RECORDING_STATUS) item {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ClickableSettingsRow(stringResource(R.string.settings_recording_open_browser), "",
                { onShowRecordingBrowserDialogChange(true) },
                modifier = if (targetItemId == "recording.browser") targetFocusModifier else androidx.compose.ui.Modifier)
            ClickableSettingsRow(stringResource(R.string.settings_recording_reconcile), "",
                { viewModel.reconcileRecordings() },
                modifier = if (targetItemId == "recording.reconcile") targetFocusModifier else androidx.compose.ui.Modifier)
        }
    }
    if (page == null || page == SettingsPage.RECORDING_STORAGE) item {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ClickableSettingsRow(stringResource(R.string.settings_recording_choose_folder),
                uiState.recordingStorageState.displayName.orEmpty(), onChooseFolder,
                modifier = if (targetItemId == "recording.folder") targetFocusModifier else androidx.compose.ui.Modifier)
            ClickableSettingsRow(stringResource(R.string.settings_recording_use_app_storage), "",
                { viewModel.updateRecordingFolder(null, null) },
                modifier = if (targetItemId == "recording.app_storage") targetFocusModifier else androidx.compose.ui.Modifier)
            if (onUseUsbStorage != null) ClickableSettingsRow(
                stringResource(R.string.settings_recording_use_usb_storage), "", onUseUsbStorage,
                modifier = if (targetItemId == "recording.usb_storage") targetFocusModifier else androidx.compose.ui.Modifier)
        }
    }
    if (page == null || page == SettingsPage.RECORDING_DEFAULTS) item {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ClickableSettingsRow(stringResource(R.string.settings_recording_pattern_title),
                uiState.recordingStorageState.fileNamePattern, { onShowRecordingPatternDialogChange(true) },
                modifier = if (targetItemId == "recording.filename") targetFocusModifier else androidx.compose.ui.Modifier)
            ClickableSettingsRow(stringResource(R.string.settings_recording_retention_title),
                uiState.recordingStorageState.retentionDays?.let {
                    stringResource(R.string.settings_recording_retention_days, it)
                } ?: stringResource(R.string.settings_recording_retention_keep_all), { onShowRecordingRetentionDialogChange(true) },
                modifier = if (targetItemId == "recording.retention") targetFocusModifier else androidx.compose.ui.Modifier)
            ClickableSettingsRow(stringResource(R.string.settings_recording_concurrency_title),
                uiState.recordingStorageState.maxSimultaneousRecordings.toString(), { onShowRecordingConcurrencyDialogChange(true) },
                modifier = if (targetItemId == "recording.concurrency") targetFocusModifier else androidx.compose.ui.Modifier)
            ClickableSettingsRow(stringResource(R.string.settings_recording_padding_title),
                "", { onShowRecordingPaddingDialogChange(true) },
                modifier = if (targetItemId == "recording.padding") targetFocusModifier else androidx.compose.ui.Modifier)
            SwitchSettingsRow(stringResource(R.string.settings_recording_wifi_only), "",
                uiState.wifiOnlyRecording, viewModel::setRecordingWifiOnly,
                modifier = if (targetItemId == "recording.wifi_only") targetFocusModifier else androidx.compose.ui.Modifier)
        }
    }
}
