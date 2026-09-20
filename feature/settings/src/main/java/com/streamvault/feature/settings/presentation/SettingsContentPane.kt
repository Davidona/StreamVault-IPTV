package com.streamvault.feature.settings.presentation

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import com.streamvault.feature.settings.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.streamvault.domain.model.LegacyProvider as Provider

@Composable
public fun SettingsContentPane(
    uiState: SettingsUiState,
    viewModel: SettingsViewModel,
    context: Context,
    appVersionLabel: String,
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
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onCategoryBack: (() -> Unit)? = null,
    onSearch: (() -> Unit)? = null,
    onExit: (() -> Unit)? = null,
    searchModifier: Modifier = Modifier,
    requestedDestination: SettingsSearchTarget? = null,
    onSearchResultBack: (() -> Unit)? = null,
    restoredPage: SettingsPage? = null,
    restorePageRequestId: Long = 0L,
    onPageChanged: (SettingsPage?) -> Unit = {},
    categorySelectionId: Long = 0L,
    onCloseApp: () -> Unit = {}
) {
    val category = SettingsCategory.fromId(dialogState.selectedCategory)
    var page by rememberSaveable(category.legacyId) { mutableStateOf<SettingsPage?>(null) }
    var lastPage by rememberSaveable(category.legacyId) { mutableStateOf<SettingsPage?>(null) }
    val pageHeaderFocus = remember { FocusRequester() }
    val returnFocus = remember { FocusRequester() }
    val overviewEntryFocus = remember { FocusRequester() }
    val searchTargetFocus = remember { FocusRequester() }
    val focusCoordinator = remember { SettingsFocusCoordinator() }
    val focusManager = LocalFocusManager.current
    var pageHeaderPlaced by remember(category.legacyId, page) { mutableStateOf(false) }
    var overviewEntryPlaced by remember(category.legacyId, page) { mutableStateOf(false) }
    var searchTargetPlaced by remember(category.legacyId, page, requestedDestination?.requestId) {
        mutableStateOf(false)
    }
    val activeSearchTarget = requestedDestination?.takeIf {
        it.category == category && it.page == page
    }
    LaunchedEffect(page) { onPageChanged(page) }
    LaunchedEffect(restorePageRequestId) {
        if (restorePageRequestId > 0L) {
            page = restoredPage
            lastPage = restoredPage
        }
    }
    val searchTargetModifier = Modifier
        .focusRequester(searchTargetFocus)
        .onGloballyPositioned { searchTargetPlaced = true }
    val directEntryModifier = Modifier
        .focusRequester(overviewEntryFocus)
        .onGloballyPositioned { overviewEntryPlaced = true }
    val savedScrollPositions = rememberSaveable(saver = SettingsScrollPositionsSaver) {
        mutableMapOf<String, SettingsScrollPosition>()
    }
    val scrollStateCache = remember { mutableMapOf<String, LazyListState>() }
    fun scrollState(key: String): LazyListState = scrollStateCache.getOrPut(key) {
        val saved = savedScrollPositions[key]
        LazyListState(
            firstVisibleItemIndex = saved?.index ?: 0,
            firstVisibleItemScrollOffset = saved?.offset ?: 0,
        )
    }
    val overviewScrollKey = settingsScrollKey(category, null)
    val overviewScrollState = remember(overviewScrollKey) { scrollState(overviewScrollKey) }
    val activeScrollKey = settingsScrollKey(category, page)
    val activeScrollState = remember(activeScrollKey) { scrollState(activeScrollKey) }
    LaunchedEffect(activeScrollKey, activeScrollState) {
        snapshotFlow {
            SettingsScrollPosition(
                index = activeScrollState.firstVisibleItemIndex,
                offset = activeScrollState.firstVisibleItemScrollOffset,
            )
        }.collect { position -> savedScrollPositions[activeScrollKey] = position }
    }
    LaunchedEffect(categorySelectionId) {
        if (categorySelectionId > 0L) {
            page = null
            lastPage = null
            overviewScrollState.scrollToItem(0)
        }
    }
    LaunchedEffect(requestedDestination?.requestId) {
        requestedDestination?.let { target ->
            if (target.category == category) {
                page = target.page
                lastPage = target.page
            }
        }
    }
    BackHandler(enabled = activeSearchTarget != null || page != null || onCategoryBack != null) {
        when {
            activeSearchTarget != null -> onSearchResultBack?.invoke()
            page != null -> page = null
            else -> onCategoryBack?.invoke()
        }
    }
    LaunchedEffect(
        category.legacyId,
        page,
        pageHeaderPlaced,
        overviewEntryPlaced,
        activeSearchTarget?.requestId,
        searchTargetPlaced,
    ) {
        when {
            activeSearchTarget != null && searchTargetPlaced -> {
                val intent = focusCoordinator.next(activeSearchTarget.itemId)
                withFrameNanos { }
                if (focusCoordinator.canApply(intent, activeSearchTarget.itemId)) {
                    searchTargetFocus.requestFocus()
                }
            }
            page != null && pageHeaderPlaced -> {
                val targetId = "page.${requireNotNull(page).name.lowercase()}.first"
                val intent = focusCoordinator.next(targetId)
                withFrameNanos { }
                if (focusCoordinator.canApply(intent, targetId)) {
                    pageHeaderFocus.requestFocus()
                    withFrameNanos { }
                    if (focusCoordinator.canApply(intent, targetId)) {
                        focusManager.moveFocus(FocusDirection.Down)
                    }
                }
            }
            page == null && lastPage != null && overviewEntryPlaced -> {
                val targetId = "page.${lastPage!!.name.lowercase()}"
                val intent = focusCoordinator.next(targetId)
                withFrameNanos { }
                if (focusCoordinator.canApply(intent, targetId)) returnFocus.requestFocus()
            }
            page == null && lastPage == null && overviewEntryPlaced -> {
                val targetId = "category.${category.name.lowercase()}.first"
                val intent = focusCoordinator.next(targetId)
                withFrameNanos { }
                if (focusCoordinator.canApply(intent, targetId)) overviewEntryFocus.requestFocus()
            }
        }
    }
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.TopStart) {
        key(category.legacyId, page) {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .then(
                        if (compact) Modifier.fillMaxWidth()
                        else Modifier.widthIn(
                            max = SettingsDesignTokens.contentMaxWidth +
                                SettingsDesignTokens.space24 +
                                SettingsDesignTokens.tvHorizontalInset
                        )
                    )
                    .padding(
                        start = if (compact) SettingsDesignTokens.compactInset else SettingsDesignTokens.space24,
                        top = if (compact) SettingsDesignTokens.space12 else SettingsDesignTokens.space16,
                        end = if (compact) SettingsDesignTokens.compactInset else SettingsDesignTokens.tvHorizontalInset,
                    )
            ) {
                val headerBack = when {
                    activeSearchTarget != null -> onSearchResultBack
                    page != null -> ({ page = null })
                    else -> onCategoryBack
                }
                SettingsLocalHeader(
                    title = stringResource(page?.title ?: category.title),
                    description = stringResource(page?.description ?: category.description),
                    parentTitle = when {
                        activeSearchTarget != null -> stringResource(R.string.settings_search_title)
                        page != null -> stringResource(category.title)
                        onCategoryBack != null -> stringResource(R.string.settings_title)
                        else -> null
                    },
                    onBack = headerBack,
                    onSearch = onSearch,
                    onExit = onExit,
                    searchModifier = searchModifier,
                    backModifier = Modifier
                        .focusRequester(pageHeaderFocus)
                        .onGloballyPositioned { pageHeaderPlaced = true },
                )
                LazyColumn(
                    state = activeScrollState,
                    modifier = Modifier.weight(1f).fillMaxWidth().imePadding(),
                    contentPadding = PaddingValues(
                        top = SettingsDesignTokens.space12,
                        bottom = SettingsDesignTokens.space24,
                    ),
                    verticalArrangement = Arrangement.spacedBy(SettingsDesignTokens.space8),
                    userScrollEnabled = true,
                ) {
                if (page == null && category.pages.isNotEmpty()) {
                    items(category.pages, key = { it.name }) { destination ->
                            val isReturnTarget = destination == lastPage
                            val isEntryTarget = destination == category.pages.first()
                            val focusModifier = when {
                                isReturnTarget -> Modifier.focusRequester(returnFocus)
                                    .onGloballyPositioned { overviewEntryPlaced = true }
                                isEntryTarget -> Modifier.focusRequester(overviewEntryFocus)
                                    .onGloballyPositioned { overviewEntryPlaced = true }
                                else -> Modifier
                            }
                                SettingsPageCard(destination,
                                    modifier = focusModifier,
                                    onClick = { lastPage = destination; page = destination })
                    }
                } else {
                    if (page == SettingsPage.SOURCE_COMPATIBILITY) {
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                SwitchSettingsRow(stringResource(R.string.settings_xtream_text_classification),
                                    stringResource(R.string.settings_xtream_text_classification_subtitle),
                                    uiState.useXtreamTextClassification, { viewModel.toggleXtreamTextClassification() },
                                    modifier = if (activeSearchTarget?.itemId == "sources.xtream_text_classification") {
                                        searchTargetModifier
                                    } else Modifier)
                                SwitchSettingsRow(stringResource(R.string.settings_xtream_base64_compatibility),
                                    stringResource(R.string.settings_xtream_base64_compatibility_subtitle),
                                    uiState.xtreamBase64TextCompatibility, { viewModel.toggleXtreamBase64TextCompatibility() },
                                    modifier = if (activeSearchTarget?.itemId == "sources.xtream_base64") {
                                        searchTargetModifier
                                    } else Modifier)
                            }
                        }
                    } else if (dialogState.selectedCategory == 0) {
                        providerSection(
                            uiState = uiState,
                            onAddProvider = onAddProvider,
                            onEditProvider = onEditProvider,
                            onNavigateToParentalControl = onNavigateToParentalControl,
                            viewModel = viewModel,
                            providerState = providerState,
                            targetItemId = activeSearchTarget?.itemId,
                            targetFocusModifier = searchTargetModifier,
                        )
                    }
                    if (dialogState.selectedCategory == 1 || page in listOf(SettingsPage.CLOCK, SettingsPage.TIMESHIFT, SettingsPage.MULTIVIEW, SettingsPage.VOD_PLAYBACK)) {
                        settingsPlaybackSection(
                            uiState = uiState,
                            page = page,
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
                            onShowEthernetQualityDialogChange = { dialogState.showEthernetQualityDialog = it },
                            targetItemId = activeSearchTarget?.itemId,
                            targetFocusModifier = searchTargetModifier,
                        )
                    }
                    if (dialogState.selectedCategory in listOf(2, 8, 9)) {
                        settingsBrowsingSection(
                            uiState = uiState,
                            page = page,
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
                            onShowThemeDialogChange = { dialogState.showThemeDialog = it },
                            onShowVodDuplicateHandlingDialogChange = { dialogState.showVodDuplicateHandlingDialog = it },
                            onShowVodVariantPreferenceDialogChange = { dialogState.showVodVariantPreferenceDialog = it },
                            onCategorySortDialogTypeChange = { dialogState.categorySortDialogType = it },
                            onShowLanguageDialogChange = { dialogState.showLanguageDialog = it },
                            onRemoteShortcutDialogTargetChange = {
                                dialogState.selectedRemoteShortcutTargetKey = it?.storageKey()
                            },
                            targetItemId = activeSearchTarget?.itemId,
                            targetFocusModifier = searchTargetModifier,
                        )
                    } else if (dialogState.selectedCategory == 3) {
                        settingsPrivacySection(
                            uiState = uiState,
                            onToggleIncognitoMode = viewModel::toggleIncognitoMode,
                            onToggleXtreamTextClassification = viewModel::toggleXtreamTextClassification,
                            onToggleXtreamBase64TextCompatibility = viewModel::toggleXtreamBase64TextCompatibility,
                            onPendingProtectionLevelChange = { dialogState.pendingProtectionLevel = it },
                            onPendingActionChange = { dialogState.pendingAction = it },
                            onShowPinDialogChange = { dialogState.showPinDialog = it },
                            onShowLevelDialogChange = { dialogState.showLevelDialog = it },
                            onShowClearHistoryDialogChange = { dialogState.showClearHistoryDialog = it },
                            onManageCategories = uiState.activeProviderId?.let { providerId ->
                                { onNavigateToParentalControl(providerId) }
                            },
                            firstFocusModifier = directEntryModifier,
                            targetItemId = activeSearchTarget?.itemId,
                            targetFocusModifier = searchTargetModifier,
                        )
                    } else if (dialogState.selectedCategory == 4) {
                        settingsRecordingSection(
                            uiState = uiState,
                            page = page,
                            viewModel = viewModel,
                            onChooseFolder = onChooseRecordingFolder,
                            onUseUsbStorage = onUseUsbRecordingStorage,
                            onShowRecordingPatternDialogChange = { dialogState.showRecordingPatternDialog = it },
                            onShowRecordingRetentionDialogChange = { dialogState.showRecordingRetentionDialog = it },
                            onShowRecordingConcurrencyDialogChange = { dialogState.showRecordingConcurrencyDialog = it },
                            onShowRecordingPaddingDialogChange = { dialogState.showRecordingPaddingDialog = it },
                            onShowRecordingBrowserDialogChange = { dialogState.showRecordingBrowserDialog = it },
                            targetItemId = activeSearchTarget?.itemId,
                            targetFocusModifier = searchTargetModifier,
                        )
                    } else if (dialogState.selectedCategory == 5) {
                        if (page == SettingsPage.LOCAL_BACKUP) settingsBackupSection(
                            uiState = uiState,
                            viewModel = viewModel,
                            onCreateBackup = onCreateBackup,
                            onManageLocalBackups = onManageLocalBackups,
                            onShareBackup = onShareBackup,
                            onRestoreBackup = onRestoreBackup,
                            onCreateBackupUsb = onCreateBackupUsb,
                            onRestoreBackupUsb = onRestoreBackupUsb,
                            targetItemId = activeSearchTarget?.itemId,
                            targetFocusModifier = searchTargetModifier,
                        )
                        if (page == SettingsPage.DRIVE_BACKUP) settingsDriveBackupSection(
                            uiState = uiState,
                            onSignIn = onDriveSignIn,
                            onSignOut = onDriveSignOut,
                            onPush = onDrivePush,
                            onPull = onDrivePull,
                            onManageBackups = viewModel::manageDriveBackups,
                            targetItemId = activeSearchTarget?.itemId,
                            targetFocusModifier = searchTargetModifier,
                        )
                    } else if (dialogState.selectedCategory == 6) {
                        epgSourcesSection(
                            uiState = uiState,
                            viewModel = viewModel,
                            firstFocusModifier = directEntryModifier,
                            targetItemId = activeSearchTarget?.itemId,
                            targetFocusModifier = searchTargetModifier,
                        )
                    } else if (dialogState.selectedCategory == 7) {
                        settingsAboutSection(
                            uiState = uiState,
                            page = page,
                            context = context,
                            appVersionLabel = appVersionLabel,
                            onCloseApp = onCloseApp,
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
                            onDeleteCrashReport = onDeleteCrashReport,
                            targetItemId = activeSearchTarget?.itemId,
                            targetFocusModifier = searchTargetModifier,
                        )
                    }
                }
                }
            }
        }
    }
}

internal data class SettingsScrollPosition(val index: Int, val offset: Int)

internal fun settingsScrollKey(category: SettingsCategory, page: SettingsPage?): String =
    "${category.name}:${page?.name ?: "overview"}"

private val SettingsScrollPositionsSaver = listSaver<MutableMap<String, SettingsScrollPosition>, Any>(
    save = { positions ->
        positions.toSortedMap().flatMap { (key, position) ->
            listOf(key, position.index, position.offset)
        }
    },
    restore = { values ->
        buildMap {
            values.chunked(3).forEach { entry ->
                put(
                    entry[0] as String,
                    SettingsScrollPosition(entry[1] as Int, entry[2] as Int),
                )
            }
        }.toMutableMap()
    },
)
