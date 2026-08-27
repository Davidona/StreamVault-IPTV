package com.streamvault.feature.settings.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.feature.settings.R
import com.streamvault.core.ui.interaction.TvClickableSurface
import com.streamvault.core.ui.theme.OnBackground
import com.streamvault.core.ui.theme.OnSurface
import com.streamvault.core.ui.theme.Primary
import com.streamvault.domain.model.LiveStreamFormatMode

public fun LazyListScope.settingsPlaybackSection(
    uiState: SettingsUiState,
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
    onShowEthernetQualityDialogChange: (Boolean) -> Unit
) {
    item {
        val liveStreamFormatMode by viewModel.playerLiveStreamFormatMode.collectAsStateWithLifecycle()
        var showLiveStreamFormatDialog by rememberSaveable { mutableStateOf(false) }
        val liveStreamFormatOptions = remember {
            listOf(
                LiveStreamFormatMode.AUTO,
                LiveStreamFormatMode.HLS,
                LiveStreamFormatMode.MPEG_TS
            )
        }
        if (showLiveStreamFormatDialog) {
            PremiumSelectionDialog(
                title = "Live stream format",
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
        TvClickableSurface(
            onClick = { viewModel.setPreventStandbyDuringPlayback(!uiState.preventStandbyDuringPlayback) },
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                focusedContainerColor = Primary.copy(alpha = 0.15f)
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = stringResource(R.string.settings_prevent_standby), style = MaterialTheme.typography.bodyMedium, color = OnSurface)
                    Text(text = stringResource(R.string.settings_prevent_standby_subtitle), style = MaterialTheme.typography.bodySmall, color = OnBackground.copy(alpha = 0.6f))
                }
                Switch(checked = uiState.preventStandbyDuringPlayback, onCheckedChange = { viewModel.setPreventStandbyDuringPlayback(it) })
            }
        }
        HorizontalDivider(color = Color.White.copy(alpha = 0.07f), modifier = Modifier.padding(vertical = 4.dp))
        TvClickableSurface(
            onClick = { viewModel.setAutoPlayNextEpisode(!uiState.autoPlayNextEpisode) },
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                focusedContainerColor = Primary.copy(alpha = 0.15f)
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = stringResource(R.string.settings_auto_play_next_episode), style = MaterialTheme.typography.bodyMedium, color = OnSurface)
                    Text(text = stringResource(R.string.settings_auto_play_next_episode_subtitle), style = MaterialTheme.typography.bodySmall, color = OnBackground.copy(alpha = 0.6f))
                }
                Switch(checked = uiState.autoPlayNextEpisode, onCheckedChange = { viewModel.setAutoPlayNextEpisode(it) })
            }
        }
        HorizontalDivider(color = Color.White.copy(alpha = 0.07f), modifier = Modifier.padding(vertical = 4.dp))
        TvClickableSurface(
            onClick = { viewModel.setPlayerMediaSessionEnabled(!uiState.playerMediaSessionEnabled) },
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                focusedContainerColor = Primary.copy(alpha = 0.15f)
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = stringResource(R.string.settings_media_session), style = MaterialTheme.typography.bodyMedium, color = OnSurface)
                    Text(text = stringResource(R.string.settings_media_session_subtitle), style = MaterialTheme.typography.bodySmall, color = OnBackground.copy(alpha = 0.6f))
                }
                Switch(checked = uiState.playerMediaSessionEnabled, onCheckedChange = { viewModel.setPlayerMediaSessionEnabled(it) })
            }
        }
        HorizontalDivider(color = Color.White.copy(alpha = 0.07f), modifier = Modifier.padding(vertical = 4.dp))
        TvClickableSurface(
            onClick = {
                viewModel.setPlayerFastRetryOnTransientFailures(!uiState.playerFastRetryOnTransientFailures)
            },
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                focusedContainerColor = Primary.copy(alpha = 0.15f)
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = stringResource(R.string.settings_fast_retry_on_transient_failures), style = MaterialTheme.typography.bodyMedium, color = OnSurface)
                    Text(text = stringResource(R.string.settings_fast_retry_on_transient_failures_subtitle), style = MaterialTheme.typography.bodySmall, color = OnBackground.copy(alpha = 0.6f))
                }
                Switch(
                    checked = uiState.playerFastRetryOnTransientFailures,
                    onCheckedChange = { viewModel.setPlayerFastRetryOnTransientFailures(it) }
                )
            }
        }
        HorizontalDivider(color = Color.White.copy(alpha = 0.07f), modifier = Modifier.padding(vertical = 4.dp))
        TvClickableSurface(
            onClick = { viewModel.setPlayerLiveTranslationEnabled(!uiState.playerLiveTranslationEnabled) },
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                focusedContainerColor = Primary.copy(alpha = 0.15f)
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = stringResource(R.string.settings_live_translation_enabled), style = MaterialTheme.typography.bodyMedium, color = OnSurface)
                    Text(text = stringResource(R.string.settings_live_translation_enabled_subtitle), style = MaterialTheme.typography.bodySmall, color = OnBackground.copy(alpha = 0.6f))
                }
                Switch(checked = uiState.playerLiveTranslationEnabled, onCheckedChange = { viewModel.setPlayerLiveTranslationEnabled(it) })
            }
        }
        ClickableSettingsRow(
            label = stringResource(R.string.settings_live_translation_endpoint),
            value = liveTranslationEndpointLabel,
            onClick = { onShowLiveTranslationEndpointDialogChange(true) }
        )
        HorizontalDivider(color = Color.White.copy(alpha = 0.07f), modifier = Modifier.padding(vertical = 4.dp))
        TvClickableSurface(
            onClick = { viewModel.setPlayerTimeshiftEnabled(!uiState.playerTimeshiftEnabled) },
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                focusedContainerColor = Primary.copy(alpha = 0.15f)
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = stringResource(R.string.settings_live_timeshift), style = MaterialTheme.typography.bodyMedium, color = OnSurface)
                    Text(text = stringResource(R.string.settings_live_timeshift_subtitle), style = MaterialTheme.typography.bodySmall, color = OnBackground.copy(alpha = 0.6f))
                }
                Switch(checked = uiState.playerTimeshiftEnabled, onCheckedChange = { viewModel.setPlayerTimeshiftEnabled(it) })
            }
        }
        ClickableSettingsRow(
            label = stringResource(R.string.settings_live_timeshift_depth),
            value = timeshiftDepthLabel,
            onClick = { onShowTimeshiftDepthDialogChange(true) }
        )
        ClickableSettingsRow(
            label = stringResource(R.string.settings_live_timeshift_backend),
            value = timeshiftBackendLabel,
            onClick = { onShowTimeshiftBackendDialogChange(true) }
        )
        Text(
            text = stringResource(R.string.settings_live_timeshift_backend_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = OnBackground.copy(alpha = 0.6f),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
        ClickableSettingsRow(
            label = "Live stream format",
            value = formatLiveStreamFormatModeLabel(liveStreamFormatMode),
            onClick = { showLiveStreamFormatDialog = true }
        )
        HorizontalDivider(color = Color.White.copy(alpha = 0.07f), modifier = Modifier.padding(vertical = 4.dp))
        TvClickableSurface(
            onClick = { viewModel.setZapAutoRevert(!uiState.zapAutoRevert) },
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                focusedContainerColor = Primary.copy(alpha = 0.15f)
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = stringResource(R.string.settings_zap_auto_revert), style = MaterialTheme.typography.bodyMedium, color = OnSurface)
                    Text(text = stringResource(R.string.settings_zap_auto_revert_subtitle), style = MaterialTheme.typography.bodySmall, color = OnBackground.copy(alpha = 0.6f))
                }
                Switch(checked = uiState.zapAutoRevert, onCheckedChange = { viewModel.setZapAutoRevert(it) })
            }
        }
        ClickableSettingsRow(
            label = stringResource(R.string.settings_audio_decoder_mode),
            value = audioDecoderModeLabel,
            onClick = { onShowAudioDecoderModeDialogChange(true) }
        )
        ClickableSettingsRow(
            label = stringResource(R.string.settings_video_decoder_mode),
            value = videoDecoderModeLabel,
            onClick = { onShowVideoDecoderModeDialogChange(true) }
        )
        ClickableSettingsRow(
            label = stringResource(R.string.settings_live_buffer_size),
            value = playbackBufferModeLabel,
            onClick = { onShowPlaybackBufferModeDialogChange(true) }
        )
        ClickableSettingsRow(
            label = stringResource(R.string.settings_audio_output_mode),
            value = audioOutputPreferenceLabel,
            onClick = { onShowAudioOutputPreferenceDialogChange(true) }
        )
        ClickableSettingsRow(
            label = stringResource(R.string.settings_external_playback),
            value = externalPlaybackModeLabel,
            onClick = { onShowExternalPlaybackModeDialogChange(true) }
        )
        TvClickableSurface(
            onClick = {
                viewModel.setPlayerCompatibilityMemoryEnabled(!uiState.playerCompatibilityMemoryEnabled)
            },
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                focusedContainerColor = Primary.copy(alpha = 0.15f)
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = stringResource(R.string.settings_ffmpeg_compatibility_memory), style = MaterialTheme.typography.bodyMedium, color = OnSurface)
                    Text(text = stringResource(R.string.settings_ffmpeg_compatibility_memory_subtitle), style = MaterialTheme.typography.bodySmall, color = OnBackground.copy(alpha = 0.6f))
                }
                Switch(
                    checked = uiState.playerCompatibilityMemoryEnabled,
                    onCheckedChange = { viewModel.setPlayerCompatibilityMemoryEnabled(it) }
                )
            }
        }
        ClickableSettingsRow(
            label = stringResource(R.string.settings_ffmpeg_compatibility_clear),
            value = stringResource(R.string.settings_ffmpeg_compatibility_clear_value),
            onClick = viewModel::clearLearnedPlaybackCompatibility
        )
        ClickableSettingsRow(
            label = stringResource(R.string.settings_surface_mode),
            value = surfaceModeLabel,
            onClick = { onShowSurfaceModeDialogChange(true) }
        )
        ClickableSettingsRow(
            label = stringResource(R.string.settings_vod_http_protocol_mode),
            value = vodHttpProtocolLabel,
            onClick = { onShowVodHttpProtocolDialogChange(true) }
        )
        HorizontalDivider(color = Color.White.copy(alpha = 0.07f), modifier = Modifier.padding(vertical = 4.dp))
        ClickableSettingsRow(
            label = stringResource(R.string.settings_default_playback_speed),
            value = playbackSpeedLabel,
            onClick = { onShowPlaybackSpeedDialogChange(true) }
        )
        ClickableSettingsRow(
            label = stringResource(R.string.settings_default_stop_timer),
            value = defaultStopTimerLabel,
            onClick = { onShowDefaultStopTimerDialogChange(true) }
        )
        ClickableSettingsRow(
            label = stringResource(R.string.settings_default_idle_standby_timer),
            value = defaultIdleTimerLabel,
            onClick = { onShowDefaultIdleTimerDialogChange(true) }
        )
        TvClickableSurface(
            onClick = { viewModel.setPlayerAudioVideoSyncEnabled(!uiState.playerAudioVideoSyncEnabled) },
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                focusedContainerColor = Primary.copy(alpha = 0.15f)
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = stringResource(R.string.settings_audio_video_sync_enabled), style = MaterialTheme.typography.bodyMedium, color = OnSurface)
                    Text(text = stringResource(R.string.settings_audio_video_sync_enabled_subtitle), style = MaterialTheme.typography.bodySmall, color = OnBackground.copy(alpha = 0.6f))
                }
                Switch(checked = uiState.playerAudioVideoSyncEnabled, onCheckedChange = { viewModel.setPlayerAudioVideoSyncEnabled(it) })
            }
        }
        if (uiState.playerAudioVideoSyncEnabled) {
            ClickableSettingsRow(
                label = stringResource(R.string.settings_audio_video_sync_default),
                value = audioVideoOffsetLabel,
                onClick = { onShowAudioVideoOffsetDialogChange(true) }
            )
        }
        HorizontalDivider(color = Color.White.copy(alpha = 0.07f), modifier = Modifier.padding(vertical = 4.dp))
        TvClickableSurface(
            onClick = {
                viewModel.setMultiViewRespectProviderConnectionLimit(!uiState.multiViewRespectProviderConnectionLimit)
            },
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                focusedContainerColor = Primary.copy(alpha = 0.15f)
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = stringResource(R.string.settings_multiview_respect_provider_connection_limit), style = MaterialTheme.typography.bodyMedium, color = OnSurface)
                    Text(text = stringResource(R.string.settings_multiview_respect_provider_connection_limit_subtitle), style = MaterialTheme.typography.bodySmall, color = OnBackground.copy(alpha = 0.6f))
                }
                Switch(
                    checked = uiState.multiViewRespectProviderConnectionLimit,
                    onCheckedChange = { viewModel.setMultiViewRespectProviderConnectionLimit(it) }
                )
            }
        }
        HorizontalDivider(color = Color.White.copy(alpha = 0.07f), modifier = Modifier.padding(vertical = 4.dp))
        TvClickableSurface(
            onClick = { viewModel.setCenterTwoSlotMultiviewLayout(!uiState.centerTwoSlotMultiviewLayout) },
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                focusedContainerColor = Primary.copy(alpha = 0.15f)
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = stringResource(R.string.settings_multiview_center_two_slot_layout), style = MaterialTheme.typography.bodyMedium, color = OnSurface)
                    Text(text = stringResource(R.string.settings_multiview_center_two_slot_layout_subtitle), style = MaterialTheme.typography.bodySmall, color = OnBackground.copy(alpha = 0.6f))
                }
                Switch(checked = uiState.centerTwoSlotMultiviewLayout, onCheckedChange = { viewModel.setCenterTwoSlotMultiviewLayout(it) })
            }
        }
        ClickableSettingsRow(
            label = stringResource(R.string.settings_player_controls_timeout),
            value = controlsTimeoutLabel,
            onClick = { onShowControlsTimeoutDialogChange(true) }
        )
        ClickableSettingsRow(
            label = stringResource(R.string.settings_live_overlay_timeout),
            value = liveOverlayTimeoutLabel,
            onClick = { onShowLiveOverlayTimeoutDialogChange(true) }
        )
        ClickableSettingsRow(
            label = stringResource(R.string.settings_player_notice_timeout),
            value = noticeTimeoutLabel,
            onClick = { onShowNoticeTimeoutDialogChange(true) }
        )
        ClickableSettingsRow(
            label = stringResource(R.string.settings_player_diagnostics_timeout),
            value = diagnosticsTimeoutLabel,
            onClick = { onShowDiagnosticsTimeoutDialogChange(true) }
        )
        ClickableSettingsRow(
            label = stringResource(R.string.settings_preferred_audio_language),
            value = preferredAudioLanguageLabel,
            onClick = { onShowAudioLanguageDialogChange(true) }
        )
        ClickableSettingsRow(
            label = stringResource(R.string.settings_subtitle_size),
            value = subtitleSizeLabel,
            onClick = { onShowSubtitleSizeDialogChange(true) }
        )
        ClickableSettingsRow(
            label = stringResource(R.string.settings_subtitle_text_color),
            value = subtitleTextColorLabel,
            onClick = { onShowSubtitleTextColorDialogChange(true) }
        )
        ClickableSettingsRow(
            label = stringResource(R.string.settings_subtitle_background),
            value = subtitleBackgroundLabel,
            onClick = { onShowSubtitleBackgroundDialogChange(true) }
        )
        HorizontalDivider(color = Color.White.copy(alpha = 0.07f), modifier = Modifier.padding(vertical = 4.dp))
        ClickableSettingsRow(
            label = stringResource(R.string.settings_wifi_quality_cap),
            value = wifiQualityLabel,
            onClick = { onShowWifiQualityDialogChange(true) }
        )
        ClickableSettingsRow(
            label = stringResource(R.string.settings_ethernet_quality_cap),
            value = ethernetQualityLabel,
            onClick = { onShowEthernetQualityDialogChange(true) }
        )
    }

    item {
        InternetSpeedTestCard(
            valueLabel = lastSpeedTestLabel,
            summary = lastSpeedTestSummary,
            recommendationLabel = speedTestRecommendationLabel,
            isRunning = uiState.isRunningInternetSpeedTest,
            canApplyRecommendation = uiState.lastSpeedTest != null,
            onRunTest = viewModel::runInternetSpeedTest,
            onApplyWifi = viewModel::applySpeedTestRecommendationToWifi,
            onApplyEthernet = viewModel::applySpeedTestRecommendationToEthernet
        )
    }
}
