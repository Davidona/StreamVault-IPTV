package com.streamvault.app.ui.screens.settings

import com.streamvault.feature.settings.presentation.SettingsUiState
import com.streamvault.feature.settings.presentation.SettingsViewModel
import com.streamvault.feature.settings.presentation.playbackUrl

import androidx.compose.runtime.Composable
import com.streamvault.app.MainActivity
import com.streamvault.app.navigation.AppRouteCodec
import com.streamvault.app.navigation.playerNavigationRequest

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
