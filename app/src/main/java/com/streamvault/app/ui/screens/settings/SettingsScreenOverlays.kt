package com.streamvault.app.ui.screens.settings

import com.streamvault.feature.settings.presentation.*

import android.content.Context
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
internal fun BoxScope.SettingsScreenOverlays(
    snackbarHostState: SnackbarHostState,
    uiState: SettingsUiState,
    viewModel: SettingsViewModel,
    context: Context,
    scope: CoroutineScope,
    dialogState: SettingsScreenDialogState,
    mainActivity: com.streamvault.app.MainActivity?,
    currentRoute: String,
    modifier: Modifier = Modifier
) {
    SnackbarHost(
        hostState = snackbarHostState,
        modifier = modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 16.dp)
    )

    SettingsRecordingBrowserDialog(
        showRecordingBrowserDialog = dialogState.showRecordingBrowserDialog,
        uiState = uiState,
        selectedRecordingId = dialogState.selectedRecordingId,
        onSelectedRecordingChange = { dialogState.selectedRecordingId = it },
        onShowRecordingBrowserDialogChange = { dialogState.showRecordingBrowserDialog = it },
        mainActivity = mainActivity,
        currentRoute = currentRoute,
        viewModel = viewModel
    )

    SettingsScreenDialogs(
        uiState = uiState,
        viewModel = viewModel,
        context = context,
        scope = scope,
        dialogState = dialogState,
        onCancelSync = { viewModel.cancelSync() }
    )
}
