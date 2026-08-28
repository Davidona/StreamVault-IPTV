package com.streamvault.feature.settings.presentation

import androidx.compose.runtime.Composable
import com.streamvault.core.ui.device.rememberIsTelevisionDevice
import com.streamvault.feature.settings.api.SettingsPlatformHost
import com.streamvault.feature.settings.api.SettingsRecordingPlaybackRequest

@Composable
internal fun SettingsRecordingBrowserDialog(
    showRecordingBrowserDialog: Boolean,
    uiState: SettingsUiState,
    selectedRecordingId: String?,
    onSelectedRecordingChange: (String?) -> Unit,
    onShowRecordingBrowserDialogChange: (Boolean) -> Unit,
    platformHost: SettingsPlatformHost,
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
                platformHost.playRecording(
                    SettingsRecordingPlaybackRequest(
                        streamUrl = playbackUrl,
                        title = item.programTitle ?: item.channelName,
                        internalId = item.id.hashCode().toLong().and(0x7FFFFFFFL),
                        providerId = item.providerId,
                        contentType = "MOVIE",
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
        },
        appTimeFormat = uiState.appTimeFormat,
        isTelevisionDevice = rememberIsTelevisionDevice()
    )
}
