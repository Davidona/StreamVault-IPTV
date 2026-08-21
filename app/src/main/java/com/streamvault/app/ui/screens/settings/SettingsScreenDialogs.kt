package com.streamvault.app.ui.screens.settings

import androidx.compose.foundation.focusable
import android.content.Context
import android.view.KeyEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Text
import com.streamvault.app.R
import com.streamvault.app.ui.components.dialogs.PremiumDialog
import com.streamvault.app.ui.components.dialogs.PremiumDialogFooterButton
import com.streamvault.app.ui.interaction.TvClickableSurface
import com.streamvault.app.ui.theme.OnBackground
import com.streamvault.app.ui.theme.OnSurface
import com.streamvault.app.ui.theme.OnSurfaceDim
import com.streamvault.app.ui.theme.Primary
import com.streamvault.app.ui.theme.SurfaceElevated
import com.streamvault.app.ui.theme.TextSecondary
import com.streamvault.domain.model.ExternalPlaybackMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
internal fun SettingsScreenDialogs(
    uiState: SettingsUiState,
    viewModel: SettingsViewModel,
    context: Context,
    scope: CoroutineScope,
    dialogState: SettingsScreenDialogState,
    onCancelSync: () -> Unit
) {
    val providerState = rememberSettingsProviderSectionState(dialogState)

    SyncingOverlay(
        isSyncing = uiState.isSyncing,
        providerName = uiState.syncingProviderName,
        progress = uiState.syncProgress,
        sectionLabel = uiState.syncSectionLabel,
        startedAt = uiState.syncStartedAt,
        onCancel = onCancelSync.takeIf { uiState.syncCanCancel }
    )

    if (dialogState.showLiveTvModeDialog) {
        LiveTvChannelModeDialog(
            selectedMode = uiState.liveTvChannelMode,
            onDismiss = { dialogState.showLiveTvModeDialog = false },
            onModeSelected = { mode ->
                viewModel.setLiveTvChannelMode(mode)
                dialogState.showLiveTvModeDialog = false
            }
        )
    }

    if (dialogState.showLiveTvQuickFilterVisibilityDialog) {
        LiveTvQuickFilterVisibilityDialog(
            selectedMode = uiState.liveTvQuickFilterVisibilityMode,
            onDismiss = { dialogState.showLiveTvQuickFilterVisibilityDialog = false },
            onModeSelected = { mode ->
                viewModel.setLiveTvQuickFilterVisibilityMode(mode)
                dialogState.showLiveTvQuickFilterVisibilityDialog = false
            }
        )
    }

    if (dialogState.showLiveChannelNumberingDialog) {
        LiveChannelNumberingModeDialog(
            selectedMode = uiState.liveChannelNumberingMode,
            onDismiss = { dialogState.showLiveChannelNumberingDialog = false },
            onModeSelected = { mode ->
                viewModel.setLiveChannelNumberingMode(mode)
                dialogState.showLiveChannelNumberingDialog = false
            }
        )
    }

    if (dialogState.showLiveChannelGroupingDialog) {
        LiveChannelGroupingModeDialog(
            selectedMode = uiState.liveChannelGroupingMode,
            onDismiss = { dialogState.showLiveChannelGroupingDialog = false },
            onModeSelected = { mode ->
                viewModel.setLiveChannelGroupingMode(mode)
                dialogState.showLiveChannelGroupingDialog = false
            }
        )
    }

    if (dialogState.showGroupedChannelLabelDialog) {
        GroupedChannelLabelModeDialog(
            selectedMode = uiState.groupedChannelLabelMode,
            onDismiss = { dialogState.showGroupedChannelLabelDialog = false },
            onModeSelected = { mode ->
                viewModel.setGroupedChannelLabelMode(mode)
                dialogState.showGroupedChannelLabelDialog = false
            }
        )
    }

    if (dialogState.showLiveVariantPreferenceDialog) {
        LiveVariantPreferenceModeDialog(
            selectedMode = uiState.liveVariantPreferenceMode,
            onDismiss = { dialogState.showLiveVariantPreferenceDialog = false },
            onModeSelected = { mode ->
                viewModel.setLiveVariantPreferenceMode(mode)
                dialogState.showLiveVariantPreferenceDialog = false
            }
        )
    }

    if (dialogState.showExternalPlaybackModeDialog) {
        ExternalPlaybackModeDialog(
            selectedMode = uiState.playerExternalPlaybackMode,
            onDismiss = { dialogState.showExternalPlaybackModeDialog = false },
            onModeSelected = { mode ->
                viewModel.setExternalPlaybackMode(mode)
                dialogState.showExternalPlaybackModeDialog = false
            }
        )
    }

    if (dialogState.showVodViewModeDialog) {
        VodViewModeDialog(
            selectedMode = uiState.vodViewMode,
            onDismiss = { dialogState.showVodViewModeDialog = false },
            onModeSelected = { mode ->
                viewModel.setVodViewMode(mode)
                dialogState.showVodViewModeDialog = false
            }
        )
    }

    if (dialogState.showVodDuplicateHandlingDialog) {
        VodDuplicateHandlingModeDialog(
            selectedMode = uiState.vodDuplicateHandlingMode,
            onDismiss = { dialogState.showVodDuplicateHandlingDialog = false },
            onModeSelected = { mode ->
                viewModel.setVodDuplicateHandlingMode(mode)
                dialogState.showVodDuplicateHandlingDialog = false
            }
        )
    }

    if (dialogState.showVodVariantPreferenceDialog) {
        VodVariantPreferenceModeDialog(
            selectedMode = uiState.vodVariantPreferenceMode,
            onDismiss = { dialogState.showVodVariantPreferenceDialog = false },
            onModeSelected = { mode ->
                viewModel.setVodVariantPreferenceMode(mode)
                dialogState.showVodVariantPreferenceDialog = false
            }
        )
    }

    SettingsPreferenceDialogs(
        uiState = uiState,
        viewModel = viewModel,
        context = context,
        showTopNavigationDialog = dialogState.showTopNavigationDialog,
        onShowTopNavigationDialogChange = { dialogState.showTopNavigationDialog = it },
        showHomeDashboardDialog = dialogState.showHomeDashboardDialog,
        onShowHomeDashboardDialogChange = { dialogState.showHomeDashboardDialog = it },
        showLandingScreenDialog = dialogState.showLandingScreenDialog,
        onShowLandingScreenDialogChange = { dialogState.showLandingScreenDialog = it },
        showGuideDefaultCategoryDialog = dialogState.showGuideDefaultCategoryDialog,
        onShowGuideDefaultCategoryDialogChange = { dialogState.showGuideDefaultCategoryDialog = it },
        showPlaybackSpeedDialog = dialogState.showPlaybackSpeedDialog,
        onShowPlaybackSpeedDialogChange = { dialogState.showPlaybackSpeedDialog = it },
        showTimeFormatDialog = dialogState.showTimeFormatDialog,
        onShowTimeFormatDialogChange = { dialogState.showTimeFormatDialog = it },
        showAudioVideoOffsetDialog = dialogState.showAudioVideoOffsetDialog,
        onShowAudioVideoOffsetDialogChange = { dialogState.showAudioVideoOffsetDialog = it },
        showAudioDecoderModeDialog = dialogState.showAudioDecoderModeDialog,
        onShowAudioDecoderModeDialogChange = { dialogState.showAudioDecoderModeDialog = it },
        showVideoDecoderModeDialog = dialogState.showVideoDecoderModeDialog,
        onShowVideoDecoderModeDialogChange = { dialogState.showVideoDecoderModeDialog = it },
        showPlaybackBufferModeDialog = dialogState.showPlaybackBufferModeDialog,
        onShowPlaybackBufferModeDialogChange = { dialogState.showPlaybackBufferModeDialog = it },
        showAudioOutputPreferenceDialog = dialogState.showAudioOutputPreferenceDialog,
        onShowAudioOutputPreferenceDialogChange = { dialogState.showAudioOutputPreferenceDialog = it },
        showSurfaceModeDialog = dialogState.showSurfaceModeDialog,
        onShowSurfaceModeDialogChange = { dialogState.showSurfaceModeDialog = it },
        showVodHttpProtocolDialog = dialogState.showVodHttpProtocolDialog,
        onShowVodHttpProtocolDialogChange = { dialogState.showVodHttpProtocolDialog = it },
        showTimeshiftDepthDialog = dialogState.showTimeshiftDepthDialog,
        onShowTimeshiftDepthDialogChange = { dialogState.showTimeshiftDepthDialog = it },
        showTimeshiftBackendDialog = dialogState.showTimeshiftBackendDialog,
        onShowTimeshiftBackendDialogChange = { dialogState.showTimeshiftBackendDialog = it },
        showDefaultStopTimerDialog = dialogState.showDefaultStopTimerDialog,
        onShowDefaultStopTimerDialogChange = { dialogState.showDefaultStopTimerDialog = it },
        showDefaultIdleTimerDialog = dialogState.showDefaultIdleTimerDialog,
        onShowDefaultIdleTimerDialogChange = { dialogState.showDefaultIdleTimerDialog = it },
        showControlsTimeoutDialog = dialogState.showControlsTimeoutDialog,
        onShowControlsTimeoutDialogChange = { dialogState.showControlsTimeoutDialog = it },
        showLiveOverlayTimeoutDialog = dialogState.showLiveOverlayTimeoutDialog,
        onShowLiveOverlayTimeoutDialogChange = { dialogState.showLiveOverlayTimeoutDialog = it },
        showNoticeTimeoutDialog = dialogState.showNoticeTimeoutDialog,
        onShowNoticeTimeoutDialogChange = { dialogState.showNoticeTimeoutDialog = it },
        showDiagnosticsTimeoutDialog = dialogState.showDiagnosticsTimeoutDialog,
        onShowDiagnosticsTimeoutDialogChange = { dialogState.showDiagnosticsTimeoutDialog = it },
        showLiveTvFiltersDialog = dialogState.showLiveTvFiltersDialog,
        onShowLiveTvFiltersDialogChange = { dialogState.showLiveTvFiltersDialog = it },
        showAudioLanguageDialog = dialogState.showAudioLanguageDialog,
        onShowAudioLanguageDialogChange = { dialogState.showAudioLanguageDialog = it },
        showSubtitleSizeDialog = dialogState.showSubtitleSizeDialog,
        onShowSubtitleSizeDialogChange = { dialogState.showSubtitleSizeDialog = it },
        showSubtitleTextColorDialog = dialogState.showSubtitleTextColorDialog,
        onShowSubtitleTextColorDialogChange = { dialogState.showSubtitleTextColorDialog = it },
        showSubtitleBackgroundDialog = dialogState.showSubtitleBackgroundDialog,
        onShowSubtitleBackgroundDialogChange = { dialogState.showSubtitleBackgroundDialog = it },
        showLiveTranslationEndpointDialog = dialogState.showLiveTranslationEndpointDialog,
        onShowLiveTranslationEndpointDialogChange = { dialogState.showLiveTranslationEndpointDialog = it },
        showWifiQualityDialog = dialogState.showWifiQualityDialog,
        onShowWifiQualityDialogChange = { dialogState.showWifiQualityDialog = it },
        showEthernetQualityDialog = dialogState.showEthernetQualityDialog,
        onShowEthernetQualityDialogChange = { dialogState.showEthernetQualityDialog = it },
        showLanguageDialog = dialogState.showLanguageDialog,
        onShowLanguageDialogChange = { dialogState.showLanguageDialog = it },
        categorySortDialogType = dialogState.categorySortDialogType,
        onCategorySortDialogTypeChange = { dialogState.categorySortDialogType = it },
        selectedRemoteShortcutTargetKey = dialogState.selectedRemoteShortcutTargetKey,
        onSelectedRemoteShortcutTargetKeyChange = { dialogState.selectedRemoteShortcutTargetKey = it }
    )

    SettingsProtectionDialogs(
        uiState = uiState,
        viewModel = viewModel,
        context = context,
        scope = scope,
        showPinDialog = dialogState.showPinDialog,
        onShowPinDialogChange = { dialogState.showPinDialog = it },
        showLevelDialog = dialogState.showLevelDialog,
        onShowLevelDialogChange = { dialogState.showLevelDialog = it },
        pinError = dialogState.pinError,
        onPinErrorChange = { dialogState.pinError = it },
        pendingAction = dialogState.pendingAction,
        onPendingActionChange = { dialogState.pendingAction = it },
        pendingProtectionLevel = dialogState.pendingProtectionLevel,
        onPendingProtectionLevelChange = { dialogState.pendingProtectionLevel = it }
    )

    SettingsRecordingDialogs(
        uiState = uiState,
        viewModel = viewModel,
        showRecordingPatternDialog = dialogState.showRecordingPatternDialog,
        onShowRecordingPatternDialogChange = { dialogState.showRecordingPatternDialog = it },
        showRecordingRetentionDialog = dialogState.showRecordingRetentionDialog,
        onShowRecordingRetentionDialogChange = { dialogState.showRecordingRetentionDialog = it },
        showRecordingConcurrencyDialog = dialogState.showRecordingConcurrencyDialog,
        onShowRecordingConcurrencyDialogChange = { dialogState.showRecordingConcurrencyDialog = it },
        showRecordingPaddingDialog = dialogState.showRecordingPaddingDialog,
        onShowRecordingPaddingDialogChange = { dialogState.showRecordingPaddingDialog = it }
    )

    val backupPreview = uiState.backupPreview
    if (backupPreview != null && uiState.pendingBackupUri != null) {
        BackupImportPreviewDialog(
            preview = backupPreview,
            plan = uiState.backupImportPlan,
            onDismiss = { viewModel.dismissBackupPreview() },
            onStrategySelected = { viewModel.setBackupConflictStrategy(it) },
            onImportPreferencesChanged = { viewModel.setImportPreferences(it) },
            onImportProvidersChanged = { viewModel.setImportProviders(it) },
            onImportSavedLibraryChanged = { viewModel.setImportSavedLibrary(it) },
            onImportPlaybackHistoryChanged = { viewModel.setImportPlaybackHistory(it) },
            onImportMultiViewChanged = { viewModel.setImportMultiViewPresets(it) },
            onImportRecordingSchedulesChanged = { viewModel.setImportRecordingSchedules(it) },
            isImporting = uiState.isImportingBackup,
            onConfirm = { viewModel.confirmBackupImport() }
        )
    }

    if (uiState.pendingRestoreProviders.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = viewModel::restoreSyncLater,
            title = { Text("Restore waiting for sync") },
            text = {
                Column {
                    Text("Choose providers to sync now. Nothing is selected by default; pending restore data is kept if you choose Later.")
                    uiState.pendingRestoreProviders.forEachIndexed { index, provider ->
                        Row {
                            Checkbox(
                                checked = index in uiState.selectedRestoreProviderIndices,
                                onCheckedChange = { viewModel.toggleRestoreSyncProvider(index) }
                            )
                            Text(provider.serverUrl + provider.username.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty())
                        }
                    }
                    TextButton(onClick = viewModel::selectAllRestoreSyncProviders) { Text("Select all") }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = uiState.selectedRestoreProviderIndices.isNotEmpty(),
                    onClick = viewModel::syncSelectedRestoreProviders
                ) { Text("Sync selected") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::restoreSyncLater) { Text("Later") }
            }
        )
    }

    if (dialogState.showClearHistoryDialog) {
        AlertDialog(
            onDismissRequest = { dialogState.showClearHistoryDialog = false },
            title = { Text(text = stringResource(R.string.settings_clear_history_dialog_title)) },
            text = {
                Text(
                    text = stringResource(R.string.settings_clear_history_dialog_body),
                    color = OnSurface
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearHistory()
                        dialogState.showClearHistoryDialog = false
                    }
                ) {
                    Text(text = stringResource(R.string.settings_clear_history_confirm), color = Primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { dialogState.showClearHistoryDialog = false }) {
                    Text(text = stringResource(R.string.settings_cancel), color = OnSurface)
                }
            },
            containerColor = SurfaceElevated,
            titleContentColor = OnSurface,
            textContentColor = TextSecondary
        )
    }

    uiState.viewedCrashReport?.let { report ->
        val scrollState = rememberScrollState()
        val focusRequester = remember { FocusRequester() }
        val coroutineScope = rememberCoroutineScope()
        val canScrollUp by remember { derivedStateOf { scrollState.value > 0 } }
        val canScrollDown by remember { derivedStateOf { scrollState.value < scrollState.maxValue } }

        LaunchedEffect(report.fileName, report.timestamp) {
            focusRequester.requestFocus()
        }

        AlertDialog(
            onDismissRequest = viewModel::dismissCrashReport,
            title = { Text(text = stringResource(R.string.settings_crash_report_view_title)) },
            text = {
                Box(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .fillMaxWidth()
                ) {
                    Text(
                        text = report.content,
                        color = OnSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                            .focusable()
                            .onPreviewKeyEvent { event ->
                                if (event.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) {
                                    return@onPreviewKeyEvent false
                                }
                                when (event.nativeKeyEvent.keyCode) {
                                    KeyEvent.KEYCODE_DPAD_UP ->
                                        if (canScrollUp) {
                                            coroutineScope.launch {
                                                scrollState.animateScrollTo(
                                                    (scrollState.value - 120).coerceAtLeast(0)
                                                )
                                            }
                                            true
                                        } else {
                                            false
                                        }
                                    KeyEvent.KEYCODE_DPAD_DOWN ->
                                        if (canScrollDown) {
                                            coroutineScope.launch {
                                                scrollState.animateScrollTo(
                                                    (scrollState.value + 120).coerceAtMost(scrollState.maxValue)
                                                )
                                            }
                                            true
                                        } else {
                                            false
                                        }
                                    else -> false
                                }
                            }
                            .verticalScroll(scrollState)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::dismissCrashReport) {
                    Text(text = stringResource(R.string.player_close), color = Primary)
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::deleteCrashReport) {
                    Text(text = stringResource(R.string.settings_crash_report_delete), color = OnSurface)
                }
            },
            containerColor = SurfaceElevated,
            titleContentColor = OnSurface,
            textContentColor = TextSecondary
        )
    }

    SettingsProviderManagementDialogs(
        uiState = uiState,
        viewModel = viewModel,
        providerState = providerState
    )
}

@Composable
internal fun ExternalPlaybackModeDialog(
    selectedMode: ExternalPlaybackMode,
    onDismiss: () -> Unit,
    onModeSelected: (ExternalPlaybackMode) -> Unit
) {
    val modes = listOf(ExternalPlaybackMode.INTERNAL_PLAYER, ExternalPlaybackMode.EXTERNAL_PLAYER)
    PremiumDialog(
        title = stringResource(R.string.settings_external_playback),
        subtitle = stringResource(R.string.settings_external_playback_subtitle),
        onDismissRequest = onDismiss,
        widthFraction = 0.52f,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                modes.forEach { mode ->
                    val isSelected = mode == selectedMode ||
                        (mode == ExternalPlaybackMode.EXTERNAL_PLAYER && selectedMode == ExternalPlaybackMode.ASK_EVERY_TIME)
                    TvClickableSurface(
                        onClick = { onModeSelected(mode) },
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = if (isSelected) Primary.copy(alpha = 0.18f) else SurfaceElevated,
                            focusedContainerColor = Primary.copy(alpha = 0.28f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = stringResource(
                                    when (mode) {
                                        ExternalPlaybackMode.INTERNAL_PLAYER -> R.string.settings_external_playback_mode_internal
                                        ExternalPlaybackMode.EXTERNAL_PLAYER -> R.string.settings_external_playback_mode_external
                                        ExternalPlaybackMode.ASK_EVERY_TIME -> R.string.settings_external_playback_mode_external
                                    }
                                ),
                                style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
                                color = if (isSelected) Primary else OnBackground
                            )
                        }
                    }
                }
            }
        },
        footer = {
            PremiumDialogFooterButton(
                label = stringResource(R.string.settings_cancel),
                onClick = onDismiss
            )
        }
    )
}
