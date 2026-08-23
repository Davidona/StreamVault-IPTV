package com.streamvault.app.ui.screens.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import com.streamvault.app.MainActivity
import com.streamvault.app.navigation.AppRouteCodec
import com.streamvault.app.navigation.playerNavigationRequest
import com.streamvault.domain.model.RecordingStatus

internal fun LazyListScope.settingsRecordingSection(
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

@Composable
internal fun SettingsRecordingBrowserDialog(
    showRecordingBrowserDialog: Boolean,
    uiState: SettingsUiState,
    selectedRecordingId: String?,
    onSelectedRecordingChange: (String?) -> Unit,
    onShowRecordingBrowserDialogChange: (Boolean) -> Unit,
    mainActivity: MainActivity?,
    currentRoute: String,
    viewModel: SettingsViewModel
) {
    if (!showRecordingBrowserDialog) return

    RecordingBrowserDialog(
        recordingItems = uiState.recordingItems,
        selectedRecordingId = selectedRecordingId,
        onSelectedRecordingChange = onSelectedRecordingChange,
        onDismiss = { onShowRecordingBrowserDialogChange(false) },
        onPlay = { item ->
            val playbackUrl = item.playbackUrl()
            if (!playbackUrl.isNullOrBlank()) {
                mainActivity?.openPlayer(
                    playerNavigationRequest(
                        streamUrl = playbackUrl,
                        title = item.programTitle ?: item.channelName,
                        internalId = item.id.hashCode().toLong().and(0x7FFFFFFFL),
                        providerId = item.providerId,
                        contentType = "MOVIE",
                        returnDestination = AppRouteCodec.decode(currentRoute)
                    )
                )
            }
        },
        onStop = { item -> viewModel.stopRecording(item.id) },
        onCancel = { item -> viewModel.cancelRecording(item.id) },
        onSkipOccurrence = { item -> viewModel.skipOccurrence(item.id) },
        onDelete = { item -> viewModel.deleteRecording(item.id) },
        onRetry = { item -> viewModel.retryRecording(item.id) },
        onToggleSchedule = { item, enabled ->
            viewModel.setRecordingScheduleEnabled(item.id, enabled)
        }
    )
}
