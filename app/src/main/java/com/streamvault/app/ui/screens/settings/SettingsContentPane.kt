package com.streamvault.app.ui.screens.settings

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.streamvault.app.ui.design.MaterialVerticalScrollbar
import com.streamvault.domain.model.LegacyProvider as Provider

@Composable
internal fun SettingsContentPane(
    uiState: SettingsUiState,
    viewModel: SettingsViewModel,
    context: Context,
    screenLabels: SettingsScreenLabels,
    dialogState: SettingsScreenDialogState,
    providerState: SettingsProviderSectionState,
    onAddProvider: () -> Unit,
    onEditProvider: (Provider) -> Unit,
    onNavigateToParentalControl: (Long) -> Unit,
    onChooseRecordingFolder: () -> Unit,
    onUseUsbRecordingStorage: (() -> Unit)?,
    onCreateBackup: () -> Unit,
    onManageLocalBackups: () -> Unit,
    onCreateBackupUsb: (() -> Unit)?,
    onRestoreBackupUsb: (() -> Unit)?,
    onShareBackup: () -> Unit,
    onViewCrashReport: () -> Unit,
    onShareCrashReport: () -> Unit,
    onDeleteCrashReport: () -> Unit,
    onRestoreBackup: () -> Unit,
    onDriveSignIn: () -> Unit,
    onDriveSignOut: () -> Unit,
    onDrivePush: () -> Unit,
    onDrivePull: () -> Unit,
    onOpenUri: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val settingsListState = rememberLazyListState()

    Box(modifier = modifier.fillMaxSize()) {
    LazyColumn(
        state = settingsListState,
        modifier = Modifier
            .fillMaxHeight()
            .imePadding(),
        contentPadding = PaddingValues(start = 20.dp, top = 76.dp, end = 20.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        userScrollEnabled = !uiState.isSyncing
    ) {
        if (dialogState.selectedCategory == 0) {
            providerSection(
                uiState = uiState,
                onAddProvider = onAddProvider,
                onEditProvider = onEditProvider,
                onNavigateToParentalControl = onNavigateToParentalControl,
                viewModel = viewModel,
                providerState = providerState
            )
        } else if (dialogState.selectedCategory == 1) {
            settingsPlaybackSection(
                uiState = uiState,
                viewModel = viewModel,
                timeshiftDepthLabel = screenLabels.timeshiftDepthLabel,
                timeshiftBackendLabel = screenLabels.timeshiftBackendLabel,
                audioDecoderModeLabel = screenLabels.audioDecoderModeLabel,
                videoDecoderModeLabel = screenLabels.videoDecoderModeLabel,
                playbackBufferModeLabel = screenLabels.playbackBufferModeLabel,
                audioOutputPreferenceLabel = screenLabels.audioOutputPreferenceLabel,
                externalPlaybackModeLabel = screenLabels.externalPlaybackModeLabel,
                surfaceModeLabel = screenLabels.surfaceModeLabel,
                vodHttpProtocolLabel = screenLabels.vodHttpProtocolLabel,
                playbackSpeedLabel = screenLabels.playbackSpeedLabel,
                defaultStopTimerLabel = screenLabels.defaultStopTimerLabel,
                defaultIdleTimerLabel = screenLabels.defaultIdleTimerLabel,
                audioVideoOffsetLabel = screenLabels.audioVideoOffsetLabel,
                controlsTimeoutLabel = screenLabels.controlsTimeoutLabel,
                liveOverlayTimeoutLabel = screenLabels.liveOverlayTimeoutLabel,
                noticeTimeoutLabel = screenLabels.noticeTimeoutLabel,
                diagnosticsTimeoutLabel = screenLabels.diagnosticsTimeoutLabel,
                preferredAudioLanguageLabel = screenLabels.preferredAudioLanguageLabel,
                subtitleSizeLabel = screenLabels.subtitleSizeLabel,
                subtitleTextColorLabel = screenLabels.subtitleTextColorLabel,
                subtitleBackgroundLabel = screenLabels.subtitleBackgroundLabel,
                liveTranslationEndpointLabel = screenLabels.liveTranslationEndpointLabel,
                wifiQualityLabel = screenLabels.wifiQualityLabel,
                ethernetQualityLabel = screenLabels.ethernetQualityLabel,
                lastSpeedTestLabel = screenLabels.lastSpeedTestLabel,
                lastSpeedTestSummary = screenLabels.lastSpeedTestSummary,
                speedTestRecommendationLabel = screenLabels.speedTestRecommendationLabel,
                onShowTimeshiftDepthDialogChange = { dialogState.showTimeshiftDepthDialog = it },
                onShowTimeshiftBackendDialogChange = { dialogState.showTimeshiftBackendDialog = it },
                onShowAudioDecoderModeDialogChange = { dialogState.showAudioDecoderModeDialog = it },
                onShowVideoDecoderModeDialogChange = { dialogState.showVideoDecoderModeDialog = it },
                onShowPlaybackBufferModeDialogChange = { dialogState.showPlaybackBufferModeDialog = it },
                onShowAudioOutputPreferenceDialogChange = { dialogState.showAudioOutputPreferenceDialog = it },
                onShowExternalPlaybackModeDialogChange = { dialogState.showExternalPlaybackModeDialog = it },
                onShowSurfaceModeDialogChange = { dialogState.showSurfaceModeDialog = it },
                onShowVodHttpProtocolDialogChange = { dialogState.showVodHttpProtocolDialog = it },
                onShowPlaybackSpeedDialogChange = { dialogState.showPlaybackSpeedDialog = it },
                onShowDefaultStopTimerDialogChange = { dialogState.showDefaultStopTimerDialog = it },
                onShowDefaultIdleTimerDialogChange = { dialogState.showDefaultIdleTimerDialog = it },
                onShowAudioVideoOffsetDialogChange = { dialogState.showAudioVideoOffsetDialog = it },
                onShowControlsTimeoutDialogChange = { dialogState.showControlsTimeoutDialog = it },
                onShowLiveOverlayTimeoutDialogChange = { dialogState.showLiveOverlayTimeoutDialog = it },
                onShowNoticeTimeoutDialogChange = { dialogState.showNoticeTimeoutDialog = it },
                onShowDiagnosticsTimeoutDialogChange = { dialogState.showDiagnosticsTimeoutDialog = it },
                onShowAudioLanguageDialogChange = { dialogState.showAudioLanguageDialog = it },
                onShowSubtitleSizeDialogChange = { dialogState.showSubtitleSizeDialog = it },
                onShowSubtitleTextColorDialogChange = { dialogState.showSubtitleTextColorDialog = it },
                onShowSubtitleBackgroundDialogChange = { dialogState.showSubtitleBackgroundDialog = it },
                onShowLiveTranslationEndpointDialogChange = { dialogState.showLiveTranslationEndpointDialog = it },
                onShowWifiQualityDialogChange = { dialogState.showWifiQualityDialog = it },
                onShowEthernetQualityDialogChange = { dialogState.showEthernetQualityDialog = it }
            )
        } else if (dialogState.selectedCategory == 2) {
    settingsBrowsingSection(
        uiState = uiState,
        viewModel = viewModel,
        context = context,
        appLandingDestinationLabel = screenLabels.appLandingDestinationLabel,
        topNavigationSummaryLabel = screenLabels.topNavigationSummaryLabel,
        homeDashboardSummaryLabel = screenLabels.homeDashboardSummaryLabel,
        guideDefaultCategoryLabel = screenLabels.guideDefaultCategoryLabel,
        timeFormatLabel = screenLabels.timeFormatLabel,
        appLanguageLabel = screenLabels.appLanguageLabel,
                onShowLiveTvModeDialogChange = { dialogState.showLiveTvModeDialog = it },
                onShowLiveTvFiltersDialogChange = { dialogState.showLiveTvFiltersDialog = it },
                onShowLiveTvQuickFilterVisibilityDialogChange = { dialogState.showLiveTvQuickFilterVisibilityDialog = it },
                onShowLiveChannelNumberingDialogChange = { dialogState.showLiveChannelNumberingDialog = it },
        onShowLiveChannelGroupingDialogChange = { dialogState.showLiveChannelGroupingDialog = it },
        onShowGroupedChannelLabelDialogChange = { dialogState.showGroupedChannelLabelDialog = it },
        onShowLiveVariantPreferenceDialogChange = { dialogState.showLiveVariantPreferenceDialog = it },
        onShowTopNavigationDialogChange = { dialogState.showTopNavigationDialog = it },
        onShowHomeDashboardDialogChange = { dialogState.showHomeDashboardDialog = it },
        onShowLandingScreenDialogChange = { dialogState.showLandingScreenDialog = it },
        onShowGuideDefaultCategoryDialogChange = { dialogState.showGuideDefaultCategoryDialog = it },
        onShowTimeFormatDialogChange = { dialogState.showTimeFormatDialog = it },
                onShowVodViewModeDialogChange = { dialogState.showVodViewModeDialog = it },
                onShowVodDuplicateHandlingDialogChange = { dialogState.showVodDuplicateHandlingDialog = it },
                onShowVodVariantPreferenceDialogChange = { dialogState.showVodVariantPreferenceDialog = it },
                onCategorySortDialogTypeChange = { dialogState.categorySortDialogType = it },
                onShowLanguageDialogChange = { dialogState.showLanguageDialog = it },
                onRemoteShortcutDialogTargetChange = {
                    dialogState.selectedRemoteShortcutTargetKey = it?.storageKey()
                }
            )
        } else if (dialogState.selectedCategory == 3) {
            settingsPrivacySection(
                uiState = uiState,
                viewModel = viewModel,
                onPendingProtectionLevelChange = { dialogState.pendingProtectionLevel = it },
                onPendingActionChange = { dialogState.pendingAction = it },
                onShowPinDialogChange = { dialogState.showPinDialog = it },
                onShowLevelDialogChange = { dialogState.showLevelDialog = it },
                onShowClearHistoryDialogChange = { dialogState.showClearHistoryDialog = it }
            )
        } else if (dialogState.selectedCategory == 4) {
            settingsRecordingSection(
                uiState = uiState,
                viewModel = viewModel,
                onChooseFolder = onChooseRecordingFolder,
                onUseUsbStorage = onUseUsbRecordingStorage,
                onShowRecordingPatternDialogChange = { dialogState.showRecordingPatternDialog = it },
                onShowRecordingRetentionDialogChange = { dialogState.showRecordingRetentionDialog = it },
                onShowRecordingConcurrencyDialogChange = { dialogState.showRecordingConcurrencyDialog = it },
                onShowRecordingPaddingDialogChange = { dialogState.showRecordingPaddingDialog = it },
                onShowRecordingBrowserDialogChange = { dialogState.showRecordingBrowserDialog = it }
            )
        } else if (dialogState.selectedCategory == 5) {
            settingsBackupSection(
                uiState = uiState,
                viewModel = viewModel,
                onCreateBackup = onCreateBackup,
                onManageLocalBackups = onManageLocalBackups,
                onShareBackup = onShareBackup,
                onRestoreBackup = onRestoreBackup,
                onCreateBackupUsb = onCreateBackupUsb,
                onRestoreBackupUsb = onRestoreBackupUsb
            )
            settingsDriveBackupSection(
                uiState = uiState,
                onSignIn = onDriveSignIn,
                onSignOut = onDriveSignOut,
                onPush = onDrivePush,
                onPull = onDrivePull,
                onManageBackups = viewModel::manageDriveBackups,
            )
        } else if (dialogState.selectedCategory == 6) {
            epgSourcesSection(
                uiState = uiState,
                viewModel = viewModel
            )
        } else if (dialogState.selectedCategory == 7) {
            settingsAboutSection(
                uiState = uiState,
                context = context,
                buildVerificationLabel = screenLabels.buildVerificationLabel,
                onOpenUri = onOpenUri,
                onCheckForUpdates = viewModel::checkForAppUpdates,
                onInstallDownloadedUpdate = viewModel::installDownloadedUpdate,
                onDownloadLatestUpdate = viewModel::downloadLatestUpdate,
                onSetAutoCheckAppUpdates = viewModel::setAutoCheckAppUpdates,
                onSetAutoDownloadAppUpdates = viewModel::setAutoDownloadAppUpdates,
                onRefreshDownloadState = viewModel::refreshDownloadState,
                onViewCrashReport = onViewCrashReport,
                onShareCrashReport = onShareCrashReport,
                onDeleteCrashReport = onDeleteCrashReport
            )
        }
    }
        MaterialVerticalScrollbar(state = settingsListState)
    }
}
