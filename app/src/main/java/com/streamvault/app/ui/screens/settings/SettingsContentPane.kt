package com.streamvault.app.ui.screens.settings

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.streamvault.app.download.OfflineDownloadItem
import com.streamvault.domain.model.Provider

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
    onCreateBackup: () -> Unit,
    onShareBackup: () -> Unit,
    onViewCrashReport: () -> Unit,
    onShareCrashReport: () -> Unit,
    onDeleteCrashReport: () -> Unit,
    onRestoreBackup: () -> Unit,
    onDriveSignIn: () -> Unit,
    onDriveSignOut: () -> Unit,
    onDrivePush: () -> Unit,
    onDrivePull: () -> Unit,
    onPlayDownload: (OfflineDownloadItem) -> Unit,
    onOpenUri: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
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
                decoderModeLabel = screenLabels.decoderModeLabel,
                audioOutputPreferenceLabel = screenLabels.audioOutputPreferenceLabel,
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
                wifiQualityLabel = screenLabels.wifiQualityLabel,
                ethernetQualityLabel = screenLabels.ethernetQualityLabel,
                lastSpeedTestLabel = screenLabels.lastSpeedTestLabel,
                lastSpeedTestSummary = screenLabels.lastSpeedTestSummary,
                speedTestRecommendationLabel = screenLabels.speedTestRecommendationLabel,
                onShowTimeshiftDepthDialogChange = { dialogState.showTimeshiftDepthDialog = it },
                onShowDecoderModeDialogChange = { dialogState.showDecoderModeDialog = it },
                onShowAudioOutputPreferenceDialogChange = { dialogState.showAudioOutputPreferenceDialog = it },
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
                onShowWifiQualityDialogChange = { dialogState.showWifiQualityDialog = it },
                onShowEthernetQualityDialogChange = { dialogState.showEthernetQualityDialog = it }
            )
        } else if (dialogState.selectedCategory == 2) {
            settingsBrowsingSection(
                uiState = uiState,
                viewModel = viewModel,
                context = context,
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
                onShowGuideDefaultCategoryDialogChange = { dialogState.showGuideDefaultCategoryDialog = it },
                onShowTimeFormatDialogChange = { dialogState.showTimeFormatDialog = it },
                onShowVodViewModeDialogChange = { dialogState.showVodViewModeDialog = it },
                onCategorySortDialogTypeChange = { dialogState.categorySortDialogType = it },
                onShowLanguageDialogChange = { dialogState.showLanguageDialog = it }
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
                onShowRecordingPatternDialogChange = { dialogState.showRecordingPatternDialog = it },
                onShowRecordingRetentionDialogChange = { dialogState.showRecordingRetentionDialog = it },
                onShowRecordingConcurrencyDialogChange = { dialogState.showRecordingConcurrencyDialog = it },
                onShowRecordingPaddingDialogChange = { dialogState.showRecordingPaddingDialog = it },
                onShowRecordingBrowserDialogChange = { dialogState.showRecordingBrowserDialog = it }
            )
        } else if (dialogState.selectedCategory == SETTINGS_CATEGORY_DOWNLOADS) {
            settingsDownloadsSection(
                uiState = uiState,
                viewModel = viewModel,
                onPlayDownload = onPlayDownload
            )
        } else if (dialogState.selectedCategory == SETTINGS_CATEGORY_BACKUP) {
            settingsBackupSection(
                onCreateBackup = onCreateBackup,
                onShareBackup = onShareBackup,
                onRestoreBackup = onRestoreBackup
            )
            settingsDriveBackupSection(
                uiState = uiState,
                onSignIn = onDriveSignIn,
                onSignOut = onDriveSignOut,
                onPush = onDrivePush,
                onPull = onDrivePull
            )
        } else if (dialogState.selectedCategory == 7) {
            epgSourcesSection(
                uiState = uiState,
                viewModel = viewModel
            )
        } else if (dialogState.selectedCategory == 8) {
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
}
