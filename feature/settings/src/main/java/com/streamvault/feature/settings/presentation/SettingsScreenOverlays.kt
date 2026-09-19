package com.streamvault.feature.settings.presentation

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope

@Composable
public fun BoxScope.SettingsScreenOverlays(
    snackbarHostState: SnackbarHostState,
    uiState: SettingsUiState,
    viewModel: SettingsViewModel,
    context: android.content.Context,
    scope: CoroutineScope,
    dialogState: SettingsScreenDialogState,
    recordingBrowserContent: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    SnackbarHost(
        hostState = snackbarHostState,
        modifier = modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 16.dp)
    )

    if (dialogState.showRecordingBrowserDialog) {
        recordingBrowserContent()
    }

    SettingsScreenDialogs(
        uiState = uiState,
        viewModel = viewModel,
        context = context,
        scope = scope,
        dialogState = dialogState,
        onCancelSync = { viewModel.cancelSync() }
    )
}
