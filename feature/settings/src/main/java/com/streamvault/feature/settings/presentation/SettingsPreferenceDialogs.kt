package com.streamvault.feature.settings.presentation

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import com.streamvault.feature.settings.R
import com.streamvault.domain.model.AppTimeFormat
import com.streamvault.domain.model.AppTheme
import com.streamvault.domain.model.AppLandingDestination
import com.streamvault.domain.model.AppTopLevelDestination
import com.streamvault.domain.model.CategorySortMode
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.DecoderMode
import com.streamvault.domain.model.PlayerSurfaceMode
import com.streamvault.domain.model.RemoteShortcutSelection

@Composable
public fun SettingsPreferenceDialogs(
    uiState: SettingsUiState,
    viewModel: SettingsViewModel,
    context: Context,
    showTopNavigationDialog: Boolean,
    onShowTopNavigationDialogChange: (Boolean) -> Unit,
    showHomeDashboardDialog: Boolean,
    onShowHomeDashboardDialogChange: (Boolean) -> Unit,
    showLandingScreenDialog: Boolean,
    onShowLandingScreenDialogChange: (Boolean) -> Unit,
    showGuideDefaultCategoryDialog: Boolean,
    onShowGuideDefaultCategoryDialogChange: (Boolean) -> Unit,
    showPlaybackSpeedDialog: Boolean,
    onShowPlaybackSpeedDialogChange: (Boolean) -> Unit,
    showTimeFormatDialog: Boolean,
    onShowTimeFormatDialogChange: (Boolean) -> Unit,
    showThemeDialog: Boolean,
    onShowThemeDialogChange: (Boolean) -> Unit,
    showAudioVideoOffsetDialog: Boolean,
    onShowAudioVideoOffsetDialogChange: (Boolean) -> Unit,
    showAudioDecoderModeDialog: Boolean,
    onShowAudioDecoderModeDialogChange: (Boolean) -> Unit,
    showVideoDecoderModeDialog: Boolean,
    onShowVideoDecoderModeDialogChange: (Boolean) -> Unit,
    showPlaybackBufferModeDialog: Boolean,
    onShowPlaybackBufferModeDialogChange: (Boolean) -> Unit,
    showAudioOutputPreferenceDialog: Boolean,
    onShowAudioOutputPreferenceDialogChange: (Boolean) -> Unit,
    showSurfaceModeDialog: Boolean,
    onShowSurfaceModeDialogChange: (Boolean) -> Unit,
    showVodHttpProtocolDialog: Boolean,
    onShowVodHttpProtocolDialogChange: (Boolean) -> Unit,
    showTimeshiftDepthDialog: Boolean,
    onShowTimeshiftDepthDialogChange: (Boolean) -> Unit,
    showTimeshiftBackendDialog: Boolean,
    onShowTimeshiftBackendDialogChange: (Boolean) -> Unit,
    showDefaultStopTimerDialog: Boolean,
    onShowDefaultStopTimerDialogChange: (Boolean) -> Unit,
    showDefaultIdleTimerDialog: Boolean,
    onShowDefaultIdleTimerDialogChange: (Boolean) -> Unit,
    showControlsTimeoutDialog: Boolean,
    onShowControlsTimeoutDialogChange: (Boolean) -> Unit,
    showLiveOverlayTimeoutDialog: Boolean,
    onShowLiveOverlayTimeoutDialogChange: (Boolean) -> Unit,
    showNoticeTimeoutDialog: Boolean,
    onShowNoticeTimeoutDialogChange: (Boolean) -> Unit,
    showDiagnosticsTimeoutDialog: Boolean,
    onShowDiagnosticsTimeoutDialogChange: (Boolean) -> Unit,
    showLiveTvFiltersDialog: Boolean,
    onShowLiveTvFiltersDialogChange: (Boolean) -> Unit,
    showAudioLanguageDialog: Boolean,
    onShowAudioLanguageDialogChange: (Boolean) -> Unit,
    showSubtitleSizeDialog: Boolean,
    onShowSubtitleSizeDialogChange: (Boolean) -> Unit,
    showSubtitleTextColorDialog: Boolean,
    onShowSubtitleTextColorDialogChange: (Boolean) -> Unit,
    showSubtitleBackgroundDialog: Boolean,
    onShowSubtitleBackgroundDialogChange: (Boolean) -> Unit,
    showLiveTranslationEndpointDialog: Boolean,
    onShowLiveTranslationEndpointDialogChange: (Boolean) -> Unit,
    showWifiQualityDialog: Boolean,
    onShowWifiQualityDialogChange: (Boolean) -> Unit,
    showEthernetQualityDialog: Boolean,
    onShowEthernetQualityDialogChange: (Boolean) -> Unit,
    showLanguageDialog: Boolean,
    onShowLanguageDialogChange: (Boolean) -> Unit,
    categorySortDialogType: String?,
    onCategorySortDialogTypeChange: (String?) -> Unit,
    selectedRemoteShortcutTargetKey: String?,
    onSelectedRemoteShortcutTargetKeyChange: (String?) -> Unit
) {
    SettingsPlayerPreferenceDialogs(
        uiState = uiState,
        viewModel = viewModel,
        context = context,
        showPlaybackSpeedDialog = showPlaybackSpeedDialog,
        onShowPlaybackSpeedDialogChange = onShowPlaybackSpeedDialogChange,
        showTimeFormatDialog = showTimeFormatDialog,
        onShowTimeFormatDialogChange = onShowTimeFormatDialogChange,
        showAudioVideoOffsetDialog = showAudioVideoOffsetDialog,
        onShowAudioVideoOffsetDialogChange = onShowAudioVideoOffsetDialogChange,
        showAudioDecoderModeDialog = showAudioDecoderModeDialog,
        onShowAudioDecoderModeDialogChange = onShowAudioDecoderModeDialogChange,
        showVideoDecoderModeDialog = showVideoDecoderModeDialog,
        onShowVideoDecoderModeDialogChange = onShowVideoDecoderModeDialogChange,
        showPlaybackBufferModeDialog = showPlaybackBufferModeDialog,
        onShowPlaybackBufferModeDialogChange = onShowPlaybackBufferModeDialogChange,
        showAudioOutputPreferenceDialog = showAudioOutputPreferenceDialog,
        onShowAudioOutputPreferenceDialogChange = onShowAudioOutputPreferenceDialogChange,
        showSurfaceModeDialog = showSurfaceModeDialog,
        onShowSurfaceModeDialogChange = onShowSurfaceModeDialogChange,
        showVodHttpProtocolDialog = showVodHttpProtocolDialog,
        onShowVodHttpProtocolDialogChange = onShowVodHttpProtocolDialogChange,
        showTimeshiftDepthDialog = showTimeshiftDepthDialog,
        onShowTimeshiftDepthDialogChange = onShowTimeshiftDepthDialogChange,
        showTimeshiftBackendDialog = showTimeshiftBackendDialog,
        onShowTimeshiftBackendDialogChange = onShowTimeshiftBackendDialogChange,
        showDefaultStopTimerDialog = showDefaultStopTimerDialog,
        onShowDefaultStopTimerDialogChange = onShowDefaultStopTimerDialogChange,
        showDefaultIdleTimerDialog = showDefaultIdleTimerDialog,
        onShowDefaultIdleTimerDialogChange = onShowDefaultIdleTimerDialogChange,
        showControlsTimeoutDialog = showControlsTimeoutDialog,
        onShowControlsTimeoutDialogChange = onShowControlsTimeoutDialogChange,
        showLiveOverlayTimeoutDialog = showLiveOverlayTimeoutDialog,
        onShowLiveOverlayTimeoutDialogChange = onShowLiveOverlayTimeoutDialogChange,
        showNoticeTimeoutDialog = showNoticeTimeoutDialog,
        onShowNoticeTimeoutDialogChange = onShowNoticeTimeoutDialogChange,
        showDiagnosticsTimeoutDialog = showDiagnosticsTimeoutDialog,
        onShowDiagnosticsTimeoutDialogChange = onShowDiagnosticsTimeoutDialogChange,
        showLiveTranslationEndpointDialog = showLiveTranslationEndpointDialog,
        onShowLiveTranslationEndpointDialogChange = onShowLiveTranslationEndpointDialogChange
    )

    if (showThemeDialog) {
        PremiumSelectionDialog(
            title = stringResource(R.string.settings_select_theme),
            onDismiss = { onShowThemeDialogChange(false) }
        ) {
            AppTheme.entries.forEachIndexed { index, theme ->
                LevelOption(
                    level = index,
                    text = context.getString(theme.labelResId()),
                    currentLevel = if (uiState.appTheme == theme) index else -1,
                    onSelect = {
                        viewModel.setAppTheme(theme)
                        onShowThemeDialogChange(false)
                    }
                )
            }
        }
    }

    if (showTopNavigationDialog) {
        TopNavigationDialog(
            currentDestinations = uiState.appTopLevelDestinations,
            onDismiss = { onShowTopNavigationDialogChange(false) },
            onSave = { destinations ->
                viewModel.setAppTopLevelDestinations(destinations)
                onShowTopNavigationDialogChange(false)
            }
        )
    }

    if (showHomeDashboardDialog) {
        DashboardShelfCustomizationDialog(
            currentShelves = uiState.appHomeDashboardShelves,
            onDismiss = { onShowHomeDashboardDialogChange(false) },
            onSave = { shelves ->
                viewModel.setAppHomeDashboardShelves(shelves)
                onShowHomeDashboardDialogChange(false)
            }
        )
    }

    if (showLandingScreenDialog) {
        val availableLandingDestinations = remember(uiState.appTopLevelDestinations) {
            AppTopLevelDestination.availableLandingDestinations(uiState.appTopLevelDestinations)
        }
        PremiumSelectionDialog(
            title = stringResource(R.string.settings_select_default_landing_screen),
            onDismiss = { onShowLandingScreenDialogChange(false) }
        ) {
            availableLandingDestinations.forEachIndexed { index, destination ->
                LevelOption(
                    level = index,
                    text = stringResource(destination.labelResId()),
                    currentLevel = if (uiState.appLandingDestination == destination) index else -1,
                    onSelect = {
                        viewModel.setAppLandingDestination(destination)
                        onShowLandingScreenDialogChange(false)
                    }
                )
            }
        }
    }

    if (showGuideDefaultCategoryDialog) {
        PremiumSelectionDialog(
            title = stringResource(R.string.settings_select_guide_default_category),
            onDismiss = { onShowGuideDefaultCategoryDialogChange(false) }
        ) {
            uiState.guideDefaultCategoryOptions.forEachIndexed { index, category ->
                LevelOption(
                    level = index,
                    text = category.name,
                    currentLevel = if (uiState.guideDefaultCategoryId == category.id) index else -1,
                    onSelect = {
                        viewModel.setGuideDefaultCategory(category.id)
                        onShowGuideDefaultCategoryDialogChange(false)
                    }
                )
            }
        }
    }

    if (showLiveTvFiltersDialog) {
        LiveTvQuickFiltersDialog(
            filters = uiState.liveTvCategoryFilters,
            onDismiss = { onShowLiveTvFiltersDialogChange(false) },
            onAddFilter = viewModel::addLiveTvCategoryFilter,
            onRemoveFilter = viewModel::removeLiveTvCategoryFilter
        )
    }

    val remoteShortcutTarget = remember(selectedRemoteShortcutTargetKey) {
        RemoteShortcutDialogTarget.fromStorageKey(selectedRemoteShortcutTargetKey)
    }
    if (remoteShortcutTarget != null) {
        RemoteShortcutSelectionDialog(
            target = remoteShortcutTarget,
            selectedSelection = uiState.remoteShortcutPreferences.selection(
                remoteShortcutTarget.profile,
                remoteShortcutTarget.button
            ),
            onDismiss = { onSelectedRemoteShortcutTargetKeyChange(null) },
            onSelection = { selection: RemoteShortcutSelection ->
                viewModel.setRemoteShortcutSelection(
                    profile = remoteShortcutTarget.profile,
                    button = remoteShortcutTarget.button,
                    selection = selection
                )
                onSelectedRemoteShortcutTargetKeyChange(null)
            }
        )
    }

    if (showAudioLanguageDialog) {
        val autoLabel = stringResource(R.string.settings_audio_language_auto)
        val audioLanguageOptions = remember(autoLabel) { supportedAudioLanguages(autoLabel) }
        PremiumSelectionDialog(
            title = stringResource(R.string.settings_select_audio_language),
            onDismiss = { onShowAudioLanguageDialogChange(false) }
        ) {
            audioLanguageOptions.forEachIndexed { index, option ->
                LevelOption(
                    level = index,
                    text = option.label,
                    currentLevel = if (uiState.preferredAudioLanguage == option.tag) index else -1,
                    onSelect = {
                        viewModel.setPreferredAudioLanguage(option.tag)
                        onShowAudioLanguageDialogChange(false)
                    }
                )
            }
        }
    }

    if (showSubtitleSizeDialog) {
        val subtitleSizeOptions = remember { subtitleSizeOptions() }
        PremiumSelectionDialog(
            title = stringResource(R.string.settings_select_subtitle_size),
            onDismiss = { onShowSubtitleSizeDialogChange(false) }
        ) {
            subtitleSizeOptions.forEachIndexed { index, option ->
                LevelOption(
                    level = index,
                    text = option.label(context),
                    currentLevel = if (uiState.subtitleTextScale == option.scale) index else -1,
                    onSelect = {
                        viewModel.setSubtitleTextScale(option.scale)
                        onShowSubtitleSizeDialogChange(false)
                    }
                )
            }
        }
    }

    if (showSubtitleTextColorDialog) {
        val options = remember(context) { subtitleTextColorOptions(context) }
        PremiumSelectionDialog(
            title = stringResource(R.string.settings_select_subtitle_text_color),
            onDismiss = { onShowSubtitleTextColorDialogChange(false) }
        ) {
            options.forEachIndexed { index, option ->
                LevelOption(
                    level = index,
                    text = option.label,
                    currentLevel = if (uiState.subtitleTextColor == option.colorArgb) index else -1,
                    onSelect = {
                        viewModel.setSubtitleTextColor(option.colorArgb)
                        onShowSubtitleTextColorDialogChange(false)
                    }
                )
            }
        }
    }

    if (showSubtitleBackgroundDialog) {
        val options = remember(context) { subtitleBackgroundColorOptions(context) }
        PremiumSelectionDialog(
            title = stringResource(R.string.settings_select_subtitle_background),
            onDismiss = { onShowSubtitleBackgroundDialogChange(false) }
        ) {
            options.forEachIndexed { index, option ->
                LevelOption(
                    level = index,
                    text = option.label,
                    currentLevel = if (uiState.subtitleBackgroundColor == option.colorArgb) index else -1,
                    onSelect = {
                        viewModel.setSubtitleBackgroundColor(option.colorArgb)
                        onShowSubtitleBackgroundDialogChange(false)
                    }
                )
            }
        }
    }

    if (showWifiQualityDialog) {
        QualityCapSelectionDialog(
            title = stringResource(R.string.settings_select_wifi_quality_cap),
            currentValue = uiState.wifiMaxVideoHeight,
            onDismiss = { onShowWifiQualityDialogChange(false) },
            onSelect = {
                viewModel.setWifiQualityCap(it)
                onShowWifiQualityDialogChange(false)
            }
        )
    }

    if (showEthernetQualityDialog) {
        QualityCapSelectionDialog(
            title = stringResource(R.string.settings_select_ethernet_quality_cap),
            currentValue = uiState.ethernetMaxVideoHeight,
            onDismiss = { onShowEthernetQualityDialogChange(false) },
            onSelect = {
                viewModel.setEthernetQualityCap(it)
                onShowEthernetQualityDialogChange(false)
            }
        )
    }

    if (showLanguageDialog) {
        val systemDefaultLabel = stringResource(R.string.settings_system_default)
        val languageOptions = remember(systemDefaultLabel) { supportedAppLanguages(systemDefaultLabel) }
        PremiumSelectionDialog(
            title = stringResource(R.string.settings_select_language),
            onDismiss = { onShowLanguageDialogChange(false) }
        ) {
            languageOptions.forEachIndexed { index, option ->
                LevelOption(
                    level = index,
                    text = option.label,
                    currentLevel = if (uiState.appLanguage == option.tag) index else -1,
                    onSelect = {
                        viewModel.setAppLanguage(option.tag)
                        onShowLanguageDialogChange(false)
                    }
                )
            }
        }
    }

    categorySortDialogType?.let { typeName ->
        val type = ContentType.entries.firstOrNull { it.name == typeName }
        if (type != null) {
            CategorySortModeDialog(
                type = type,
                currentMode = uiState.categorySortModes[type] ?: CategorySortMode.DEFAULT,
                onDismiss = { onCategorySortDialogTypeChange(null) },
                onModeSelected = { mode ->
                    viewModel.setCategorySortMode(type, mode)
                    onCategorySortDialogTypeChange(null)
                }
            )
        }
    }
}

private fun AppLandingDestination.labelResId(): Int = when (this) {
    AppLandingDestination.HOME -> R.string.nav_home
    AppLandingDestination.LIVE_TV -> R.string.nav_live_tv
    AppLandingDestination.FIRST_FAVORITE_LIVE -> R.string.settings_startup_first_favorite_live
    AppLandingDestination.LAST_WATCHED_LIVE -> R.string.settings_startup_last_watched_live
    AppLandingDestination.MOVIES -> R.string.nav_movies
    AppLandingDestination.SERIES -> R.string.nav_series
    AppLandingDestination.GUIDE -> R.string.nav_epg
    AppLandingDestination.DOWNLOADS -> R.string.nav_downloads
    AppLandingDestination.PLUGINS -> R.string.nav_plugins
    AppLandingDestination.SETTINGS -> R.string.nav_settings
}

private fun AppTheme.labelResId(): Int = when (this) {
    AppTheme.CLASSIC_BLUE -> R.string.settings_theme_classic_blue
    AppTheme.M3_PURPLE -> R.string.settings_theme_m3_purple
    AppTheme.LIGHT -> R.string.settings_theme_light
}
