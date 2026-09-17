package com.streamvault.feature.settings.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.feature.settings.R
import com.streamvault.core.ui.theme.OnBackground
import com.streamvault.domain.model.LiveStreamFormatMode
import com.streamvault.domain.model.LiveClockFont
import com.streamvault.domain.model.LiveClockPosition
import com.streamvault.domain.model.LiveClockSize
import com.streamvault.domain.model.PlayerBackButtonVisibility

public fun LazyListScope.settingsPlaybackSection(
    uiState: SettingsUiState,
    page: SettingsPage? = null,
    viewModel: SettingsViewModel,
    timeshiftDepthLabel: String,
    timeshiftBackendLabel: String,
    audioDecoderModeLabel: String,
    videoDecoderModeLabel: String,
    playbackBufferModeLabel: String,
    audioOutputPreferenceLabel: String,
    externalPlaybackModeLabel: String,
    surfaceModeLabel: String,
    vodHttpProtocolLabel: String,
    playbackSpeedLabel: String,
    defaultStopTimerLabel: String,
    defaultIdleTimerLabel: String,
    audioVideoOffsetLabel: String,
    controlsTimeoutLabel: String,
    liveOverlayTimeoutLabel: String,
    noticeTimeoutLabel: String,
    diagnosticsTimeoutLabel: String,
    preferredAudioLanguageLabel: String,
    subtitleSizeLabel: String,
    subtitleTextColorLabel: String,
    subtitleBackgroundLabel: String,
    liveTranslationEndpointLabel: String,
    wifiQualityLabel: String,
    ethernetQualityLabel: String,
    lastSpeedTestLabel: String,
    lastSpeedTestSummary: String,
    speedTestRecommendationLabel: String,
    onShowTimeshiftDepthDialogChange: (Boolean) -> Unit,
    onShowTimeshiftBackendDialogChange: (Boolean) -> Unit,
    onShowAudioDecoderModeDialogChange: (Boolean) -> Unit,
    onShowVideoDecoderModeDialogChange: (Boolean) -> Unit,
    onShowPlaybackBufferModeDialogChange: (Boolean) -> Unit,
    onShowAudioOutputPreferenceDialogChange: (Boolean) -> Unit,
    onShowExternalPlaybackModeDialogChange: (Boolean) -> Unit,
    onShowSurfaceModeDialogChange: (Boolean) -> Unit,
    onShowVodHttpProtocolDialogChange: (Boolean) -> Unit,
    onShowPlaybackSpeedDialogChange: (Boolean) -> Unit,
    onShowDefaultStopTimerDialogChange: (Boolean) -> Unit,
    onShowDefaultIdleTimerDialogChange: (Boolean) -> Unit,
    onShowAudioVideoOffsetDialogChange: (Boolean) -> Unit,
    onShowControlsTimeoutDialogChange: (Boolean) -> Unit,
    onShowLiveOverlayTimeoutDialogChange: (Boolean) -> Unit,
    onShowNoticeTimeoutDialogChange: (Boolean) -> Unit,
    onShowDiagnosticsTimeoutDialogChange: (Boolean) -> Unit,
    onShowAudioLanguageDialogChange: (Boolean) -> Unit,
    onShowSubtitleSizeDialogChange: (Boolean) -> Unit,
    onShowSubtitleTextColorDialogChange: (Boolean) -> Unit,
    onShowSubtitleBackgroundDialogChange: (Boolean) -> Unit,
    onShowLiveTranslationEndpointDialogChange: (Boolean) -> Unit,
    onShowWifiQualityDialogChange: (Boolean) -> Unit,
    onShowEthernetQualityDialogChange: (Boolean) -> Unit,
    targetItemId: String? = null,
    targetFocusModifier: Modifier = Modifier,
) {
    item {
        val context = LocalContext.current
        val liveStreamFormatMode by viewModel.playerLiveStreamFormatMode.collectAsStateWithLifecycle()
        var showLiveStreamFormatDialog by rememberSaveable { mutableStateOf(false) }
        var showPlayerBackButtonVisibilityDialog by rememberSaveable { mutableStateOf(false) }
        val backButtonVisibilityOptions = remember { PlayerBackButtonVisibility.entries }
        val liveStreamFormatOptions = remember {
            listOf(
                LiveStreamFormatMode.AUTO,
                LiveStreamFormatMode.HLS,
                LiveStreamFormatMode.MPEG_TS
            )
        }
        if (showLiveStreamFormatDialog) {
            PremiumSelectionDialog(
                title = stringResource(R.string.settings_live_stream_format_title),
                onDismiss = { showLiveStreamFormatDialog = false }
            ) {
                liveStreamFormatOptions.forEachIndexed { index, mode ->
                    LevelOption(
                        level = index,
                        text = formatLiveStreamFormatModeLabel(mode),
                        currentLevel = if (liveStreamFormatMode == mode) index else -1,
                        onSelect = {
                            viewModel.setPlayerLiveStreamFormatMode(mode)
                            showLiveStreamFormatDialog = false
                        }
                    )
                }
            }
        }
        var showLiveClockPositionDialog by rememberSaveable { mutableStateOf(false) }
        var showLiveClockSizeDialog by rememberSaveable { mutableStateOf(false) }
        var showLiveClockFontDialog by rememberSaveable { mutableStateOf(false) }

        if (showLiveClockPositionDialog) {
            val positionLabels = mapOf(
                LiveClockPosition.TOP_START to stringResource(R.string.settings_live_clock_position_top_left),
                LiveClockPosition.TOP_END to stringResource(R.string.settings_live_clock_position_top_right),
                LiveClockPosition.BOTTOM_START to stringResource(R.string.settings_live_clock_position_bottom_left),
                LiveClockPosition.BOTTOM_END to stringResource(R.string.settings_live_clock_position_bottom_right)
            )
            PremiumSelectionDialog(
                title = stringResource(R.string.settings_select_live_clock_position),
                onDismiss = { showLiveClockPositionDialog = false }
            ) {
                LiveClockPosition.entries.forEachIndexed { index, position ->
                    LevelOption(
                        level = index,
                        text = positionLabels.getValue(position),
                        currentLevel = if (uiState.playerLiveClockPosition == position) index else -1,
                        onSelect = {
                            viewModel.setPlayerLiveClockPosition(position)
                            showLiveClockPositionDialog = false
                        }
                    )
                }
            }
        }

        if (showLiveClockSizeDialog) {
            val sizeLabels = mapOf(
                LiveClockSize.SMALL to stringResource(R.string.settings_live_clock_size_small),
                LiveClockSize.MEDIUM to stringResource(R.string.settings_live_clock_size_medium),
                LiveClockSize.LARGE to stringResource(R.string.settings_live_clock_size_large)
            )
            PremiumSelectionDialog(
                title = stringResource(R.string.settings_select_live_clock_size),
                onDismiss = { showLiveClockSizeDialog = false }
            ) {
                LiveClockSize.entries.forEachIndexed { index, size ->
                    LevelOption(
                        level = index,
                        text = sizeLabels.getValue(size),
                        currentLevel = if (uiState.playerLiveClockSize == size) index else -1,
                        onSelect = {
                            viewModel.setPlayerLiveClockSize(size)
                            showLiveClockSizeDialog = false
                        }
                    )
                }
            }
        }

        if (showLiveClockFontDialog) {
            val fontLabels = mapOf(
                LiveClockFont.CLEAN to stringResource(R.string.settings_live_clock_font_clean),
                LiveClockFont.DIGITAL_MONO to stringResource(R.string.settings_live_clock_font_digital_mono),
                LiveClockFont.CLASSIC_SERIF to stringResource(R.string.settings_live_clock_font_classic_serif)
            )
            PremiumSelectionDialog(
                title = stringResource(R.string.settings_select_live_clock_font),
                onDismiss = { showLiveClockFontDialog = false }
            ) {
                LiveClockFont.entries.forEachIndexed { index, font ->
                    LevelOption(
                        level = index,
                        text = fontLabels.getValue(font),
                        currentLevel = if (uiState.playerLiveClockFont == font) index else -1,
                        onSelect = {
                            viewModel.setPlayerLiveClockFont(font)
                            showLiveClockFontDialog = false
                        }
                    )
                }
            }
        }

        if (showPlayerBackButtonVisibilityDialog) {
            PremiumSelectionDialog(
                title = stringResource(R.string.settings_player_back_button_dialog_title),
                onDismiss = { showPlayerBackButtonVisibilityDialog = false }
            ) {
                backButtonVisibilityOptions.forEachIndexed { index, visibility ->
                    LevelOption(
                        level = index,
                        text = formatPlayerBackButtonVisibilityLabel(visibility, context),
                        currentLevel = if (uiState.playerBackButtonVisibility == visibility) index else -1,
                        onSelect = {
                            viewModel.setPlayerBackButtonVisibility(visibility)
                            showPlayerBackButtonVisibilityDialog = false
                        }
                    )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (page == null || page == SettingsPage.CLOCK) {
                SwitchSettingsRow(
                    label = stringResource(R.string.settings_live_clock),
                    value = stringResource(R.string.settings_live_clock_subtitle),
                    checked = uiState.playerLiveClockEnabled,
                    onCheckedChange = viewModel::setPlayerLiveClockEnabled
                ,
                    modifier = if (targetItemId == "live.clock_enabled") targetFocusModifier else Modifier,)
                if (uiState.playerLiveClockEnabled) {
                    ClickableSettingsRow(
                        label = stringResource(R.string.settings_live_clock_position),
                        value = when (uiState.playerLiveClockPosition) {
                            LiveClockPosition.TOP_START -> stringResource(R.string.settings_live_clock_position_top_left)
                            LiveClockPosition.TOP_END -> stringResource(R.string.settings_live_clock_position_top_right)
                            LiveClockPosition.BOTTOM_START -> stringResource(R.string.settings_live_clock_position_bottom_left)
                            LiveClockPosition.BOTTOM_END -> stringResource(R.string.settings_live_clock_position_bottom_right)
                        },
                        onClick = { showLiveClockPositionDialog = true }
                    ,
                        modifier = if (targetItemId == "live.clock_position") targetFocusModifier else Modifier,)
                    ClickableSettingsRow(
                        label = stringResource(R.string.settings_live_clock_size),
                        value = when (uiState.playerLiveClockSize) {
                            LiveClockSize.SMALL -> stringResource(R.string.settings_live_clock_size_small)
                            LiveClockSize.MEDIUM -> stringResource(R.string.settings_live_clock_size_medium)
                            LiveClockSize.LARGE -> stringResource(R.string.settings_live_clock_size_large)
                        },
                        onClick = { showLiveClockSizeDialog = true }
                    ,
                        modifier = if (targetItemId == "live.clock_size") targetFocusModifier else Modifier,)
                    ClickableSettingsRow(
                        label = stringResource(R.string.settings_live_clock_font),
                        value = when (uiState.playerLiveClockFont) {
                            LiveClockFont.CLEAN -> stringResource(R.string.settings_live_clock_font_clean)
                            LiveClockFont.DIGITAL_MONO -> stringResource(R.string.settings_live_clock_font_digital_mono)
                            LiveClockFont.CLASSIC_SERIF -> stringResource(R.string.settings_live_clock_font_classic_serif)
                        },
                        onClick = { showLiveClockFontDialog = true }
                    ,
                        modifier = if (targetItemId == "live.clock_font") targetFocusModifier else Modifier,)
                }
            }
            if (page == null || page == SettingsPage.TIMERS) {
                SwitchSettingsRow(
                    label = stringResource(R.string.settings_prevent_standby),
                    value = stringResource(R.string.settings_prevent_standby_subtitle),
                    checked = uiState.preventStandbyDuringPlayback,
                    onCheckedChange = viewModel::setPreventStandbyDuringPlayback
                ,
                    modifier = if (targetItemId == "playback.prevent_standby") targetFocusModifier else Modifier,)
                ClickableSettingsRow(
                    label = stringResource(R.string.settings_default_stop_timer),
                    value = defaultStopTimerLabel,
                    onClick = { onShowDefaultStopTimerDialogChange(true) }
                ,
                    modifier = if (targetItemId == "playback.stop_timer") targetFocusModifier else Modifier,)
                ClickableSettingsRow(
                    label = stringResource(R.string.settings_default_idle_standby_timer),
                    value = defaultIdleTimerLabel,
                    onClick = { onShowDefaultIdleTimerDialogChange(true) }
                ,
                    modifier = if (targetItemId == "playback.idle_timer") targetFocusModifier else Modifier,)
            }
            if (page == null || page == SettingsPage.CONTROLS) {
                ClickableSettingsRow(
                    label = stringResource(R.string.settings_player_back_button),
                    value = formatPlayerBackButtonVisibilityLabel(uiState.playerBackButtonVisibility, context),
                    onClick = { showPlayerBackButtonVisibilityDialog = true }
                ,
                    modifier = if (targetItemId == "playback.back_button") targetFocusModifier else Modifier,)
                ClickableSettingsRow(
                    label = stringResource(R.string.settings_player_controls_timeout),
                    value = controlsTimeoutLabel,
                    onClick = { onShowControlsTimeoutDialogChange(true) }
                ,
                    modifier = if (targetItemId == "playback.controls_timeout") targetFocusModifier else Modifier,)
                ClickableSettingsRow(
                    label = stringResource(R.string.settings_live_overlay_timeout),
                    value = liveOverlayTimeoutLabel,
                    onClick = { onShowLiveOverlayTimeoutDialogChange(true) }
                ,
                    modifier = if (targetItemId == "playback.live_overlay_timeout") targetFocusModifier else Modifier,)
                ClickableSettingsRow(
                    label = stringResource(R.string.settings_player_notice_timeout),
                    value = noticeTimeoutLabel,
                    onClick = { onShowNoticeTimeoutDialogChange(true) }
                ,
                    modifier = if (targetItemId == "playback.notice_timeout") targetFocusModifier else Modifier,)
                ClickableSettingsRow(
                    label = stringResource(R.string.settings_player_diagnostics_timeout),
                    value = diagnosticsTimeoutLabel,
                    onClick = { onShowDiagnosticsTimeoutDialogChange(true) }
                ,
                    modifier = if (targetItemId == "playback.diagnostics_timeout") targetFocusModifier else Modifier,)
            }
            if (page == null || page == SettingsPage.VOD_PLAYBACK) {
                SwitchSettingsRow(
                    label = stringResource(R.string.settings_auto_play_next_episode),
                    value = stringResource(R.string.settings_auto_play_next_episode_subtitle),
                    checked = uiState.autoPlayNextEpisode,
                    onCheckedChange = viewModel::setAutoPlayNextEpisode
                ,
                    modifier = if (targetItemId == "vod.auto_next_episode") targetFocusModifier else Modifier,)
                ClickableSettingsRow(
                    label = stringResource(R.string.settings_vod_http_protocol_mode),
                    value = vodHttpProtocolLabel,
                    onClick = { onShowVodHttpProtocolDialogChange(true) }
                ,
                    modifier = if (targetItemId == "vod.http_protocol") targetFocusModifier else Modifier,)
            }
            if (page == null || page == SettingsPage.GENERAL) {
                SwitchSettingsRow(
                    label = stringResource(R.string.settings_media_session),
                    value = stringResource(R.string.settings_media_session_subtitle),
                    checked = uiState.playerMediaSessionEnabled,
                    onCheckedChange = viewModel::setPlayerMediaSessionEnabled,
                    modifier = if (targetItemId == "playback.media_session") targetFocusModifier else Modifier,
                )
                ClickableSettingsRow(
                    label = stringResource(R.string.settings_external_playback),
                    value = externalPlaybackModeLabel,
                    onClick = { onShowExternalPlaybackModeDialogChange(true) },
                    modifier = if (targetItemId == "playback.external") targetFocusModifier else Modifier,
                )
                ClickableSettingsRow(
                    label = stringResource(R.string.settings_default_playback_speed),
                    value = playbackSpeedLabel,
                    onClick = { onShowPlaybackSpeedDialogChange(true) },
                    modifier = if (targetItemId == "playback.speed") targetFocusModifier else Modifier,
                )
            }
            if (page == null || page == SettingsPage.NETWORK) {
                SwitchSettingsRow(
                    label = stringResource(R.string.settings_fast_retry_on_transient_failures),
                    value = stringResource(R.string.settings_fast_retry_on_transient_failures_subtitle),
                    checked = uiState.playerFastRetryOnTransientFailures,
                    onCheckedChange = viewModel::setPlayerFastRetryOnTransientFailures
                ,
                    modifier = if (targetItemId == "playback.fast_retry") targetFocusModifier else Modifier,)
                ClickableSettingsRow(
                    label = stringResource(R.string.settings_live_buffer_size),
                    value = playbackBufferModeLabel,
                    onClick = { onShowPlaybackBufferModeDialogChange(true) }
                ,
                    modifier = if (targetItemId == "playback.buffer") targetFocusModifier else Modifier,)
                ClickableSettingsRow(
                    label = stringResource(R.string.settings_wifi_quality_cap),
                    value = wifiQualityLabel,
                    onClick = { onShowWifiQualityDialogChange(true) }
                ,
                    modifier = if (targetItemId == "playback.wifi_cap") targetFocusModifier else Modifier,)
                ClickableSettingsRow(
                    label = stringResource(R.string.settings_ethernet_quality_cap),
                    value = ethernetQualityLabel,
                    onClick = { onShowEthernetQualityDialogChange(true) }
                ,
                    modifier = if (targetItemId == "playback.ethernet_cap") targetFocusModifier else Modifier,)
            }
            if (page == null || page == SettingsPage.SUBTITLES) {
                SwitchSettingsRow(
                    label = stringResource(R.string.settings_live_translation_enabled),
                    value = stringResource(R.string.settings_live_translation_enabled_subtitle),
                    checked = uiState.playerLiveTranslationEnabled,
                    onCheckedChange = viewModel::setPlayerLiveTranslationEnabled
                ,
                    modifier = if (targetItemId == "playback.live_translation") targetFocusModifier else Modifier,)
                ClickableSettingsRow(
                    label = stringResource(R.string.settings_live_translation_endpoint),
                    value = liveTranslationEndpointLabel,
                    onClick = { onShowLiveTranslationEndpointDialogChange(true) }
                ,
                    modifier = if (targetItemId == "playback.translation_endpoint") targetFocusModifier else Modifier,)
                ClickableSettingsRow(
                    label = stringResource(R.string.settings_subtitle_size),
                    value = subtitleSizeLabel,
                    onClick = { onShowSubtitleSizeDialogChange(true) }
                ,
                    modifier = if (targetItemId == "playback.subtitle_size") targetFocusModifier else Modifier,)
                ClickableSettingsRow(
                    label = stringResource(R.string.settings_subtitle_text_color),
                    value = subtitleTextColorLabel,
                    onClick = { onShowSubtitleTextColorDialogChange(true) }
                ,
                    modifier = if (targetItemId == "playback.subtitle_text_color") targetFocusModifier else Modifier,)
                ClickableSettingsRow(
                    label = stringResource(R.string.settings_subtitle_background),
                    value = subtitleBackgroundLabel,
                    onClick = { onShowSubtitleBackgroundDialogChange(true) }
                ,
                    modifier = if (targetItemId == "playback.subtitle_background") targetFocusModifier else Modifier,)
            }
            if (page == null || page == SettingsPage.TIMESHIFT) {
                SwitchSettingsRow(
                    label = stringResource(R.string.settings_live_timeshift),
                    value = stringResource(R.string.settings_live_timeshift_subtitle),
                    checked = uiState.playerTimeshiftEnabled,
                    onCheckedChange = viewModel::setPlayerTimeshiftEnabled
                ,
                    modifier = if (targetItemId == "live.timeshift_enabled") targetFocusModifier else Modifier,)
                ClickableSettingsRow(
                    label = stringResource(R.string.settings_live_timeshift_depth),
                    value = timeshiftDepthLabel,
                    onClick = { onShowTimeshiftDepthDialogChange(true) }
                ,
                    modifier = if (targetItemId == "live.timeshift_depth") targetFocusModifier else Modifier,)
                ClickableSettingsRow(
                    label = stringResource(R.string.settings_live_timeshift_backend),
                    value = timeshiftBackendLabel,
                    onClick = { onShowTimeshiftBackendDialogChange(true) }
                ,
                    modifier = if (targetItemId == "live.timeshift_backend") targetFocusModifier else Modifier,)
                Text(
                    text = stringResource(R.string.settings_live_timeshift_backend_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = OnBackground.copy(alpha = 0.6f),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
                ClickableSettingsRow(
                    label = stringResource(R.string.settings_live_stream_format_title),
                    value = formatLiveStreamFormatModeLabel(liveStreamFormatMode),
                    onClick = { showLiveStreamFormatDialog = true }
                ,
                    modifier = if (targetItemId == "playback.live_format") targetFocusModifier else Modifier,)
                SwitchSettingsRow(
                    label = stringResource(R.string.settings_zap_auto_revert),
                    value = stringResource(R.string.settings_zap_auto_revert_subtitle),
                    checked = uiState.zapAutoRevert,
                    onCheckedChange = viewModel::setZapAutoRevert
                ,
                    modifier = if (targetItemId == "live.zap_auto_revert") targetFocusModifier else Modifier,)
            }
            if (page == null || page == SettingsPage.COMPATIBILITY) {
                ClickableSettingsRow(
                    label = stringResource(R.string.settings_audio_decoder_mode),
                    value = audioDecoderModeLabel,
                    onClick = { onShowAudioDecoderModeDialogChange(true) }
                ,
                    modifier = if (targetItemId == "playback.audio_decoder") targetFocusModifier else Modifier,)
                ClickableSettingsRow(
                    label = stringResource(R.string.settings_video_decoder_mode),
                    value = videoDecoderModeLabel,
                    onClick = { onShowVideoDecoderModeDialogChange(true) }
                ,
                    modifier = if (targetItemId == "playback.video_decoder") targetFocusModifier else Modifier,)
                SwitchSettingsRow(
                    label = stringResource(R.string.settings_ffmpeg_compatibility_memory),
                    value = stringResource(R.string.settings_ffmpeg_compatibility_memory_subtitle),
                    checked = uiState.playerCompatibilityMemoryEnabled,
                    onCheckedChange = viewModel::setPlayerCompatibilityMemoryEnabled
                ,
                    modifier = if (targetItemId == "playback.compatibility_memory") targetFocusModifier else Modifier,)
                ClickableSettingsRow(
                    label = stringResource(R.string.settings_ffmpeg_compatibility_clear),
                    value = stringResource(R.string.settings_ffmpeg_compatibility_clear_value),
                    onClick = viewModel::clearLearnedPlaybackCompatibility
                ,
                    modifier = if (targetItemId == "playback.clear_compatibility") targetFocusModifier else Modifier,)
                ClickableSettingsRow(
                    label = stringResource(R.string.settings_surface_mode),
                    value = surfaceModeLabel,
                    onClick = { onShowSurfaceModeDialogChange(true) }
                ,
                    modifier = if (targetItemId == "playback.surface") targetFocusModifier else Modifier,)
            }
            if (page == null || page == SettingsPage.AUDIO) {
                ClickableSettingsRow(
                    label = stringResource(R.string.settings_audio_output_mode),
                    value = audioOutputPreferenceLabel,
                    onClick = { onShowAudioOutputPreferenceDialogChange(true) }
                ,
                    modifier = if (targetItemId == "playback.audio_output") targetFocusModifier else Modifier,)
                SwitchSettingsRow(
                    label = stringResource(R.string.settings_audio_video_sync_enabled),
                    value = stringResource(R.string.settings_audio_video_sync_enabled_subtitle),
                    checked = uiState.playerAudioVideoSyncEnabled,
                    onCheckedChange = viewModel::setPlayerAudioVideoSyncEnabled
                ,
                    modifier = if (targetItemId == "playback.av_sync") targetFocusModifier else Modifier,)
                if (uiState.playerAudioVideoSyncEnabled) {
                    ClickableSettingsRow(
                        label = stringResource(R.string.settings_audio_video_sync_default),
                        value = audioVideoOffsetLabel,
                        onClick = { onShowAudioVideoOffsetDialogChange(true) }
                    ,
                        modifier = if (targetItemId == "playback.av_offset") targetFocusModifier else Modifier,)
                }
                ClickableSettingsRow(
                    label = stringResource(R.string.settings_preferred_audio_language),
                    value = preferredAudioLanguageLabel,
                    onClick = { onShowAudioLanguageDialogChange(true) }
                ,
                    modifier = if (targetItemId == "playback.audio_language") targetFocusModifier else Modifier,)
            }
            if (page == null || page == SettingsPage.MULTIVIEW) {
                SwitchSettingsRow(
                    label = stringResource(R.string.settings_multiview_respect_provider_connection_limit),
                    value = stringResource(R.string.settings_multiview_respect_provider_connection_limit_subtitle),
                    checked = uiState.multiViewRespectProviderConnectionLimit,
                    onCheckedChange = viewModel::setMultiViewRespectProviderConnectionLimit
                ,
                    modifier = if (targetItemId == "live.multiview_connection_limit") targetFocusModifier else Modifier,)
                SwitchSettingsRow(
                    label = stringResource(R.string.settings_multiview_center_two_slot_layout),
                    value = stringResource(R.string.settings_multiview_center_two_slot_layout_subtitle),
                    checked = uiState.centerTwoSlotMultiviewLayout,
                    onCheckedChange = viewModel::setCenterTwoSlotMultiviewLayout
                ,
                    modifier = if (targetItemId == "live.multiview_center_two") targetFocusModifier else Modifier,)
            }
        }
    }

    if (page == null || page == SettingsPage.NETWORK) item {
        InternetSpeedTestCard(
            valueLabel = lastSpeedTestLabel,
            summary = lastSpeedTestSummary,
            recommendationLabel = speedTestRecommendationLabel,
            isRunning = uiState.isRunningInternetSpeedTest,
            canApplyRecommendation = uiState.lastSpeedTest != null,
            onRunTest = viewModel::runInternetSpeedTest,
            onApplyWifi = viewModel::applySpeedTestRecommendationToWifi,
            onApplyEthernet = viewModel::applySpeedTestRecommendationToEthernet,
            modifier = if (targetItemId == "playback.speed_test") targetFocusModifier else Modifier,
        )
    }
}
