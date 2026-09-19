package com.streamvault.feature.settings.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

class SettingsScreenDialogState(
    private val showPinDialogState: MutableState<Boolean>,
    private val showLevelDialogState: MutableState<Boolean>,
    private val showLanguageDialogState: MutableState<Boolean>,
    private val showTimeFormatDialogState: MutableState<Boolean>,
    private val showThemeDialogState: MutableState<Boolean>,
    private val showLiveTvModeDialogState: MutableState<Boolean>,
    private val showLiveTvQuickFilterVisibilityDialogState: MutableState<Boolean>,
    private val showLiveChannelNumberingDialogState: MutableState<Boolean>,
    private val showLiveChannelGroupingDialogState: MutableState<Boolean>,
    private val showGroupedChannelLabelDialogState: MutableState<Boolean>,
    private val showLiveVariantPreferenceDialogState: MutableState<Boolean>,
    private val showTopNavigationDialogState: MutableState<Boolean>,
    private val showHomeDashboardDialogState: MutableState<Boolean>,
    private val showLandingScreenDialogState: MutableState<Boolean>,
    private val showVodViewModeDialogState: MutableState<Boolean>,
    private val showVodDuplicateHandlingDialogState: MutableState<Boolean>,
    private val showVodVariantPreferenceDialogState: MutableState<Boolean>,
    private val showGuideDefaultCategoryDialogState: MutableState<Boolean>,
    private val showPlaybackSpeedDialogState: MutableState<Boolean>,
    private val showExternalPlaybackModeDialogState: MutableState<Boolean>,
    private val showAudioVideoOffsetDialogState: MutableState<Boolean>,
    private val showAudioDecoderModeDialogState: MutableState<Boolean>,
    private val showVideoDecoderModeDialogState: MutableState<Boolean>,
    private val showPlaybackBufferModeDialogState: MutableState<Boolean>,
    private val showAudioOutputPreferenceDialogState: MutableState<Boolean>,
    private val showSurfaceModeDialogState: MutableState<Boolean>,
    private val showVodHttpProtocolDialogState: MutableState<Boolean>,
    private val showTimeshiftDepthDialogState: MutableState<Boolean>,
    private val showTimeshiftBackendDialogState: MutableState<Boolean>,
    private val showDefaultStopTimerDialogState: MutableState<Boolean>,
    private val showDefaultIdleTimerDialogState: MutableState<Boolean>,
    private val showControlsTimeoutDialogState: MutableState<Boolean>,
    private val showLiveOverlayTimeoutDialogState: MutableState<Boolean>,
    private val showNoticeTimeoutDialogState: MutableState<Boolean>,
    private val showDiagnosticsTimeoutDialogState: MutableState<Boolean>,
    private val showLiveTvFiltersDialogState: MutableState<Boolean>,
    private val showAudioLanguageDialogState: MutableState<Boolean>,
    private val showSubtitleSizeDialogState: MutableState<Boolean>,
    private val showSubtitleTextColorDialogState: MutableState<Boolean>,
    private val showSubtitleBackgroundDialogState: MutableState<Boolean>,
    private val showLiveTranslationEndpointDialogState: MutableState<Boolean>,
    private val showWifiQualityDialogState: MutableState<Boolean>,
    private val showProviderSyncDialogState: MutableState<Boolean>,
    private val showCustomProviderSyncDialogState: MutableState<Boolean>,
    private val showCreateCombinedDialogState: MutableState<Boolean>,
    private val showAddCombinedMemberDialogState: MutableState<Boolean>,
    private val showRenameCombinedDialogState: MutableState<Boolean>,
    private val selectedCombinedProfileIdState: MutableState<Long?>,
    private val pendingSyncProviderIdState: MutableState<Long?>,
    private val customSyncSelectionsState: MutableState<Set<ProviderSyncSelection>>,
    private val showEthernetQualityDialogState: MutableState<Boolean>,
    private val showClearHistoryDialogState: MutableState<Boolean>,
    private val showRecordingPatternDialogState: MutableState<Boolean>,
    private val showRecordingRetentionDialogState: MutableState<Boolean>,
    private val showRecordingConcurrencyDialogState: MutableState<Boolean>,
    private val showRecordingPaddingDialogState: MutableState<Boolean>,
    private val showRecordingBrowserDialogState: MutableState<Boolean>,
    private val selectedRemoteShortcutTargetKeyState: MutableState<String?>,
    private val selectedRecordingIdState: MutableState<String?>,
    private val categorySortDialogTypeState: MutableState<String?>,
    private val selectedCategoryState: MutableState<Int>,
    private val pinErrorState: MutableState<String?>,
    private val pendingActionState: MutableState<ParentalAction?>,
    private val pendingProtectionLevelState: MutableState<Int?>,
    private val pendingDeleteProviderIdState: MutableState<Long?>
) {
    var showPinDialog by showPinDialogState
    var showLevelDialog by showLevelDialogState
    var showLanguageDialog by showLanguageDialogState
    var showTimeFormatDialog by showTimeFormatDialogState
    var showThemeDialog by showThemeDialogState
    var showLiveTvModeDialog by showLiveTvModeDialogState
    var showLiveTvQuickFilterVisibilityDialog by showLiveTvQuickFilterVisibilityDialogState
    var showLiveChannelNumberingDialog by showLiveChannelNumberingDialogState
    var showLiveChannelGroupingDialog by showLiveChannelGroupingDialogState
    var showGroupedChannelLabelDialog by showGroupedChannelLabelDialogState
    var showLiveVariantPreferenceDialog by showLiveVariantPreferenceDialogState
    var showTopNavigationDialog by showTopNavigationDialogState
    var showHomeDashboardDialog by showHomeDashboardDialogState
    var showLandingScreenDialog by showLandingScreenDialogState
    var showVodViewModeDialog by showVodViewModeDialogState
    var showVodDuplicateHandlingDialog by showVodDuplicateHandlingDialogState
    var showVodVariantPreferenceDialog by showVodVariantPreferenceDialogState
    var showGuideDefaultCategoryDialog by showGuideDefaultCategoryDialogState
    var showPlaybackSpeedDialog by showPlaybackSpeedDialogState
    var showExternalPlaybackModeDialog by showExternalPlaybackModeDialogState
    var showAudioVideoOffsetDialog by showAudioVideoOffsetDialogState
    var showAudioDecoderModeDialog by showAudioDecoderModeDialogState
    var showVideoDecoderModeDialog by showVideoDecoderModeDialogState
    var showPlaybackBufferModeDialog by showPlaybackBufferModeDialogState
    var showAudioOutputPreferenceDialog by showAudioOutputPreferenceDialogState
    var showSurfaceModeDialog by showSurfaceModeDialogState
    var showVodHttpProtocolDialog by showVodHttpProtocolDialogState
    var showTimeshiftDepthDialog by showTimeshiftDepthDialogState
    var showTimeshiftBackendDialog by showTimeshiftBackendDialogState
    var showDefaultStopTimerDialog by showDefaultStopTimerDialogState
    var showDefaultIdleTimerDialog by showDefaultIdleTimerDialogState
    var showControlsTimeoutDialog by showControlsTimeoutDialogState
    var showLiveOverlayTimeoutDialog by showLiveOverlayTimeoutDialogState
    var showNoticeTimeoutDialog by showNoticeTimeoutDialogState
    var showDiagnosticsTimeoutDialog by showDiagnosticsTimeoutDialogState
    var showLiveTvFiltersDialog by showLiveTvFiltersDialogState
    var showAudioLanguageDialog by showAudioLanguageDialogState
    var showSubtitleSizeDialog by showSubtitleSizeDialogState
    var showSubtitleTextColorDialog by showSubtitleTextColorDialogState
    var showSubtitleBackgroundDialog by showSubtitleBackgroundDialogState
    var showLiveTranslationEndpointDialog by showLiveTranslationEndpointDialogState
    var showWifiQualityDialog by showWifiQualityDialogState
    var showProviderSyncDialog by showProviderSyncDialogState
    var showCustomProviderSyncDialog by showCustomProviderSyncDialogState
    var showCreateCombinedDialog by showCreateCombinedDialogState
    var showAddCombinedMemberDialog by showAddCombinedMemberDialogState
    var showRenameCombinedDialog by showRenameCombinedDialogState
    var selectedCombinedProfileId by selectedCombinedProfileIdState
    var pendingSyncProviderId by pendingSyncProviderIdState
    var customSyncSelections by customSyncSelectionsState
    var showEthernetQualityDialog by showEthernetQualityDialogState
    var showClearHistoryDialog by showClearHistoryDialogState
    var showRecordingPatternDialog by showRecordingPatternDialogState
    var showRecordingRetentionDialog by showRecordingRetentionDialogState
    var showRecordingConcurrencyDialog by showRecordingConcurrencyDialogState
    var showRecordingPaddingDialog by showRecordingPaddingDialogState
    var showRecordingBrowserDialog by showRecordingBrowserDialogState
    var selectedRemoteShortcutTargetKey by selectedRemoteShortcutTargetKeyState
    var selectedRecordingId by selectedRecordingIdState
    var categorySortDialogType by categorySortDialogTypeState
    var selectedCategory by selectedCategoryState
    var pinError by pinErrorState
    var pendingAction by pendingActionState
    var pendingProtectionLevel by pendingProtectionLevelState
    var pendingDeleteProviderId by pendingDeleteProviderIdState
}

class SettingsProviderSectionState(
    private val dialogState: SettingsScreenDialogState
) {
    var showProviderSyncDialog: Boolean
        get() = dialogState.showProviderSyncDialog
        set(value) {
            dialogState.showProviderSyncDialog = value
        }

    var showCustomProviderSyncDialog: Boolean
        get() = dialogState.showCustomProviderSyncDialog
        set(value) {
            dialogState.showCustomProviderSyncDialog = value
        }

    var showCreateCombinedDialog: Boolean
        get() = dialogState.showCreateCombinedDialog
        set(value) {
            dialogState.showCreateCombinedDialog = value
        }

    var showAddCombinedMemberDialog: Boolean
        get() = dialogState.showAddCombinedMemberDialog
        set(value) {
            dialogState.showAddCombinedMemberDialog = value
        }

    var showRenameCombinedDialog: Boolean
        get() = dialogState.showRenameCombinedDialog
        set(value) {
            dialogState.showRenameCombinedDialog = value
        }

    var selectedCombinedProfileId: Long?
        get() = dialogState.selectedCombinedProfileId
        set(value) {
            dialogState.selectedCombinedProfileId = value
        }

    var pendingSyncProviderId: Long?
        get() = dialogState.pendingSyncProviderId
        set(value) {
            dialogState.pendingSyncProviderId = value
        }

    var customSyncSelections: Set<ProviderSyncSelection>
        get() = dialogState.customSyncSelections
        set(value) {
            dialogState.customSyncSelections = value
        }

    var pendingDeleteProviderId: Long?
        get() = dialogState.pendingDeleteProviderId
        set(value) {
            dialogState.pendingDeleteProviderId = value
        }
}

@Composable
fun rememberSettingsProviderSectionState(
    dialogState: SettingsScreenDialogState
): SettingsProviderSectionState = remember(dialogState) {
    SettingsProviderSectionState(dialogState)
}

@Composable
fun rememberSettingsScreenDialogState(): SettingsScreenDialogState {
    val showPinDialogState = rememberSaveable { mutableStateOf(false) }
    val showLevelDialogState = rememberSaveable { mutableStateOf(false) }
    val showLanguageDialogState = rememberSaveable { mutableStateOf(false) }
    val showTimeFormatDialogState = rememberSaveable { mutableStateOf(false) }
    val showThemeDialogState = rememberSaveable { mutableStateOf(false) }
    val showLiveTvModeDialogState = rememberSaveable { mutableStateOf(false) }
    val showLiveTvQuickFilterVisibilityDialogState = rememberSaveable { mutableStateOf(false) }
    val showLiveChannelNumberingDialogState = rememberSaveable { mutableStateOf(false) }
    val showLiveChannelGroupingDialogState = rememberSaveable { mutableStateOf(false) }
    val showGroupedChannelLabelDialogState = rememberSaveable { mutableStateOf(false) }
    val showLiveVariantPreferenceDialogState = rememberSaveable { mutableStateOf(false) }
    val showTopNavigationDialogState = rememberSaveable { mutableStateOf(false) }
    val showHomeDashboardDialogState = rememberSaveable { mutableStateOf(false) }
    val showLandingScreenDialogState = rememberSaveable { mutableStateOf(false) }
    val showVodViewModeDialogState = rememberSaveable { mutableStateOf(false) }
    val showVodDuplicateHandlingDialogState = rememberSaveable { mutableStateOf(false) }
    val showVodVariantPreferenceDialogState = rememberSaveable { mutableStateOf(false) }
    val showGuideDefaultCategoryDialogState = rememberSaveable { mutableStateOf(false) }
    val showPlaybackSpeedDialogState = rememberSaveable { mutableStateOf(false) }
    val showExternalPlaybackModeDialogState = rememberSaveable { mutableStateOf(false) }
    val showAudioVideoOffsetDialogState = rememberSaveable { mutableStateOf(false) }
    val showAudioDecoderModeDialogState = rememberSaveable { mutableStateOf(false) }
    val showVideoDecoderModeDialogState = rememberSaveable { mutableStateOf(false) }
    val showPlaybackBufferModeDialogState = rememberSaveable { mutableStateOf(false) }
    val showAudioOutputPreferenceDialogState = rememberSaveable { mutableStateOf(false) }
    val showSurfaceModeDialogState = rememberSaveable { mutableStateOf(false) }
    val showVodHttpProtocolDialogState = rememberSaveable { mutableStateOf(false) }
    val showTimeshiftDepthDialogState = rememberSaveable { mutableStateOf(false) }
    val showTimeshiftBackendDialogState = rememberSaveable { mutableStateOf(false) }
    val showDefaultStopTimerDialogState = rememberSaveable { mutableStateOf(false) }
    val showDefaultIdleTimerDialogState = rememberSaveable { mutableStateOf(false) }
    val showControlsTimeoutDialogState = rememberSaveable { mutableStateOf(false) }
    val showLiveOverlayTimeoutDialogState = rememberSaveable { mutableStateOf(false) }
    val showNoticeTimeoutDialogState = rememberSaveable { mutableStateOf(false) }
    val showDiagnosticsTimeoutDialogState = rememberSaveable { mutableStateOf(false) }
    val showLiveTvFiltersDialogState = rememberSaveable { mutableStateOf(false) }
    val showAudioLanguageDialogState = rememberSaveable { mutableStateOf(false) }
    val showSubtitleSizeDialogState = rememberSaveable { mutableStateOf(false) }
    val showSubtitleTextColorDialogState = rememberSaveable { mutableStateOf(false) }
    val showSubtitleBackgroundDialogState = rememberSaveable { mutableStateOf(false) }
    val showLiveTranslationEndpointDialogState = rememberSaveable { mutableStateOf(false) }
    val showWifiQualityDialogState = rememberSaveable { mutableStateOf(false) }
    val showProviderSyncDialogState = rememberSaveable { mutableStateOf(false) }
    val showCustomProviderSyncDialogState = rememberSaveable { mutableStateOf(false) }
    val showCreateCombinedDialogState = rememberSaveable { mutableStateOf(false) }
    val showAddCombinedMemberDialogState = rememberSaveable { mutableStateOf(false) }
    val showRenameCombinedDialogState = rememberSaveable { mutableStateOf(false) }
    val selectedCombinedProfileIdState = rememberSaveable { mutableStateOf<Long?>(null) }
    val pendingSyncProviderIdState = rememberSaveable { mutableStateOf<Long?>(null) }
    val customSyncSelectionsState = rememberSaveable {
        mutableStateOf(
            setOf(
                ProviderSyncSelection.TV,
                ProviderSyncSelection.MOVIES,
                ProviderSyncSelection.EPG
            )
        )
    }
    val showEthernetQualityDialogState = rememberSaveable { mutableStateOf(false) }
    val showClearHistoryDialogState = rememberSaveable { mutableStateOf(false) }
    val showRecordingPatternDialogState = rememberSaveable { mutableStateOf(false) }
    val showRecordingRetentionDialogState = rememberSaveable { mutableStateOf(false) }
    val showRecordingConcurrencyDialogState = rememberSaveable { mutableStateOf(false) }
    val showRecordingPaddingDialogState = rememberSaveable { mutableStateOf(false) }
    val showRecordingBrowserDialogState = rememberSaveable { mutableStateOf(false) }
    val selectedRemoteShortcutTargetKeyState = rememberSaveable { mutableStateOf<String?>(null) }
    val selectedRecordingIdState = rememberSaveable { mutableStateOf<String?>(null) }
    val categorySortDialogTypeState = rememberSaveable { mutableStateOf<String?>(null) }
    val selectedCategoryState = rememberSaveable { mutableStateOf(0) }
    val pinErrorState = rememberSaveable { mutableStateOf<String?>(null) }
    val pendingActionState = remember { mutableStateOf<ParentalAction?>(null) }
    val pendingProtectionLevelState = rememberSaveable { mutableStateOf<Int?>(null) }
    val pendingDeleteProviderIdState = rememberSaveable { mutableStateOf<Long?>(null) }

    return SettingsScreenDialogState(
        showPinDialogState = showPinDialogState,
        showLevelDialogState = showLevelDialogState,
        showLanguageDialogState = showLanguageDialogState,
        showTimeFormatDialogState = showTimeFormatDialogState,
        showThemeDialogState = showThemeDialogState,
        showLiveTvModeDialogState = showLiveTvModeDialogState,
        showLiveTvQuickFilterVisibilityDialogState = showLiveTvQuickFilterVisibilityDialogState,
        showLiveChannelNumberingDialogState = showLiveChannelNumberingDialogState,
        showLiveChannelGroupingDialogState = showLiveChannelGroupingDialogState,
        showGroupedChannelLabelDialogState = showGroupedChannelLabelDialogState,
        showLiveVariantPreferenceDialogState = showLiveVariantPreferenceDialogState,
        showTopNavigationDialogState = showTopNavigationDialogState,
        showHomeDashboardDialogState = showHomeDashboardDialogState,
        showLandingScreenDialogState = showLandingScreenDialogState,
        showVodViewModeDialogState = showVodViewModeDialogState,
        showVodDuplicateHandlingDialogState = showVodDuplicateHandlingDialogState,
        showVodVariantPreferenceDialogState = showVodVariantPreferenceDialogState,
        showGuideDefaultCategoryDialogState = showGuideDefaultCategoryDialogState,
        showPlaybackSpeedDialogState = showPlaybackSpeedDialogState,
        showExternalPlaybackModeDialogState = showExternalPlaybackModeDialogState,
        showAudioVideoOffsetDialogState = showAudioVideoOffsetDialogState,
        showAudioDecoderModeDialogState = showAudioDecoderModeDialogState,
        showVideoDecoderModeDialogState = showVideoDecoderModeDialogState,
        showPlaybackBufferModeDialogState = showPlaybackBufferModeDialogState,
        showAudioOutputPreferenceDialogState = showAudioOutputPreferenceDialogState,
        showSurfaceModeDialogState = showSurfaceModeDialogState,
        showVodHttpProtocolDialogState = showVodHttpProtocolDialogState,
        showTimeshiftDepthDialogState = showTimeshiftDepthDialogState,
        showTimeshiftBackendDialogState = showTimeshiftBackendDialogState,
        showDefaultStopTimerDialogState = showDefaultStopTimerDialogState,
        showDefaultIdleTimerDialogState = showDefaultIdleTimerDialogState,
        showControlsTimeoutDialogState = showControlsTimeoutDialogState,
        showLiveOverlayTimeoutDialogState = showLiveOverlayTimeoutDialogState,
        showNoticeTimeoutDialogState = showNoticeTimeoutDialogState,
        showDiagnosticsTimeoutDialogState = showDiagnosticsTimeoutDialogState,
        showLiveTvFiltersDialogState = showLiveTvFiltersDialogState,
        showAudioLanguageDialogState = showAudioLanguageDialogState,
        showSubtitleSizeDialogState = showSubtitleSizeDialogState,
        showSubtitleTextColorDialogState = showSubtitleTextColorDialogState,
        showSubtitleBackgroundDialogState = showSubtitleBackgroundDialogState,
        showLiveTranslationEndpointDialogState = showLiveTranslationEndpointDialogState,
        showWifiQualityDialogState = showWifiQualityDialogState,
        showProviderSyncDialogState = showProviderSyncDialogState,
        showCustomProviderSyncDialogState = showCustomProviderSyncDialogState,
        showCreateCombinedDialogState = showCreateCombinedDialogState,
        showAddCombinedMemberDialogState = showAddCombinedMemberDialogState,
        showRenameCombinedDialogState = showRenameCombinedDialogState,
        selectedCombinedProfileIdState = selectedCombinedProfileIdState,
        pendingSyncProviderIdState = pendingSyncProviderIdState,
        customSyncSelectionsState = customSyncSelectionsState,
        showEthernetQualityDialogState = showEthernetQualityDialogState,
        showClearHistoryDialogState = showClearHistoryDialogState,
        showRecordingPatternDialogState = showRecordingPatternDialogState,
        showRecordingRetentionDialogState = showRecordingRetentionDialogState,
        showRecordingConcurrencyDialogState = showRecordingConcurrencyDialogState,
        showRecordingPaddingDialogState = showRecordingPaddingDialogState,
        showRecordingBrowserDialogState = showRecordingBrowserDialogState,
        selectedRemoteShortcutTargetKeyState = selectedRemoteShortcutTargetKeyState,
        selectedRecordingIdState = selectedRecordingIdState,
        categorySortDialogTypeState = categorySortDialogTypeState,
        selectedCategoryState = selectedCategoryState,
        pinErrorState = pinErrorState,
        pendingActionState = pendingActionState,
        pendingProtectionLevelState = pendingProtectionLevelState,
        pendingDeleteProviderIdState = pendingDeleteProviderIdState
    )
}
