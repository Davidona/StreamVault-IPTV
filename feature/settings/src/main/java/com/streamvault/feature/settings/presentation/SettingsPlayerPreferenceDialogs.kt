package com.streamvault.feature.settings.presentation

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import com.streamvault.feature.settings.R
import com.streamvault.domain.model.AppTimeFormat
import com.streamvault.domain.model.AudioOutputPreference
import com.streamvault.domain.model.DecoderMode
import com.streamvault.domain.model.PlaybackBufferMode
import com.streamvault.domain.model.PlayerSurfaceMode
import com.streamvault.domain.model.TimeshiftBackendPreference
import com.streamvault.domain.model.VodHttpProtocolMode

@Composable
public fun SettingsPlayerPreferenceDialogs(
    uiState: SettingsUiState,
    viewModel: SettingsViewModel,
    context: Context,
    showPlaybackSpeedDialog: Boolean,
    onShowPlaybackSpeedDialogChange: (Boolean) -> Unit,
    showTimeFormatDialog: Boolean,
    onShowTimeFormatDialogChange: (Boolean) -> Unit,
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
    showLiveTranslationEndpointDialog: Boolean,
    onShowLiveTranslationEndpointDialogChange: (Boolean) -> Unit
) {
    if (showPlaybackSpeedDialog) {
        val speedOptions = remember { listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f) }
        PremiumSelectionDialog(
            title = stringResource(R.string.settings_select_playback_speed),
            onDismiss = { onShowPlaybackSpeedDialogChange(false) }
        ) {
            speedOptions.forEachIndexed { index, speed ->
                LevelOption(
                    level = index,
                    text = formatPlaybackSpeedLabel(speed),
                    currentLevel = if (speed == uiState.playerPlaybackSpeed) index else -1,
                    onSelect = {
                        viewModel.setDefaultPlaybackSpeed(speed)
                        onShowPlaybackSpeedDialogChange(false)
                    }
                )
            }
        }
    }

    if (showTimeFormatDialog) {
        PremiumSelectionDialog(
            title = stringResource(R.string.settings_select_time_format),
            onDismiss = { onShowTimeFormatDialogChange(false) }
        ) {
            AppTimeFormat.entries.forEachIndexed { index, format ->
                LevelOption(
                    level = index,
                    text = context.getString(format.labelResId()),
                    currentLevel = if (uiState.appTimeFormat == format) index else -1,
                    onSelect = {
                        viewModel.setAppTimeFormat(format)
                        onShowTimeFormatDialogChange(false)
                    }
                )
            }
        }
    }

    if (showAudioVideoOffsetDialog && uiState.playerAudioVideoSyncEnabled) {
        AudioVideoOffsetValueDialog(
            title = stringResource(R.string.settings_audio_video_sync_default),
            subtitle = stringResource(R.string.settings_audio_video_sync_default_subtitle),
            initialValue = uiState.playerAudioVideoOffsetMs,
            onDismiss = { onShowAudioVideoOffsetDialogChange(false) },
            onConfirm = { offsetMs ->
                viewModel.setPlayerAudioVideoOffsetMs(offsetMs)
                onShowAudioVideoOffsetDialogChange(false)
            }
        )
    }

    if (showAudioDecoderModeDialog) {
        val decoderOptions = remember(context) {
            listOf(
                DecoderMode.AUTO to context.getString(R.string.settings_decoder_auto),
                DecoderMode.HARDWARE to context.getString(R.string.settings_decoder_hardware),
                DecoderMode.SOFTWARE to context.getString(R.string.settings_decoder_software),
                DecoderMode.COMPATIBILITY to context.getString(R.string.settings_decoder_compatibility)
            )
        }
        PremiumSelectionDialog(
            title = stringResource(R.string.settings_select_audio_decoder_mode),
            onDismiss = { onShowAudioDecoderModeDialogChange(false) }
        ) {
            decoderOptions.forEachIndexed { index, option ->
                LevelOption(
                    level = index,
                    text = option.second,
                    currentLevel = if (uiState.playerAudioDecoderMode == option.first) index else -1,
                    onSelect = {
                        viewModel.setPlayerAudioDecoderMode(option.first)
                        onShowAudioDecoderModeDialogChange(false)
                    }
                )
            }
        }
    }

    if (showVideoDecoderModeDialog) {
        val decoderOptions = remember(context) {
            listOf(
                DecoderMode.AUTO to context.getString(R.string.settings_decoder_auto),
                DecoderMode.HARDWARE to context.getString(R.string.settings_decoder_hardware),
                DecoderMode.SOFTWARE to context.getString(R.string.settings_decoder_software),
                DecoderMode.COMPATIBILITY to context.getString(R.string.settings_decoder_compatibility)
            )
        }
        PremiumSelectionDialog(
            title = stringResource(R.string.settings_select_video_decoder_mode),
            onDismiss = { onShowVideoDecoderModeDialogChange(false) }
        ) {
            decoderOptions.forEachIndexed { index, option ->
                LevelOption(
                    level = index,
                    text = option.second,
                    currentLevel = if (uiState.playerVideoDecoderMode == option.first) index else -1,
                    onSelect = {
                        viewModel.setPlayerVideoDecoderMode(option.first)
                        onShowVideoDecoderModeDialogChange(false)
                    }
                )
            }
        }
    }

    if (showPlaybackBufferModeDialog) {
        val bufferOptions = remember(context) {
            listOf(
                PlaybackBufferMode.AUTO to context.getString(R.string.settings_live_buffer_auto),
                PlaybackBufferMode.SMALL to context.getString(R.string.settings_live_buffer_small),
                PlaybackBufferMode.MEDIUM to context.getString(R.string.settings_live_buffer_medium),
                PlaybackBufferMode.LARGE to context.getString(R.string.settings_live_buffer_large)
            )
        }
        PremiumSelectionDialog(
            title = stringResource(R.string.settings_live_buffer_size),
            onDismiss = { onShowPlaybackBufferModeDialogChange(false) }
        ) {
            bufferOptions.forEachIndexed { index, option ->
                LevelOption(
                    level = index,
                    text = option.second,
                    currentLevel = if (uiState.playerPlaybackBufferMode == option.first) index else -1,
                    onSelect = {
                        viewModel.setPlayerPlaybackBufferMode(option.first)
                        onShowPlaybackBufferModeDialogChange(false)
                    }
                )
            }
        }
    }

    if (showAudioOutputPreferenceDialog) {
        val outputOptions = remember(context) {
            listOf(
                AudioOutputPreference.AUTO to context.getString(R.string.settings_audio_output_auto),
                AudioOutputPreference.PREFER_PASSTHROUGH to context.getString(R.string.settings_audio_output_prefer_passthrough),
                AudioOutputPreference.DISABLE_PASSTHROUGH to context.getString(R.string.settings_audio_output_disable_passthrough)
            )
        }
        PremiumSelectionDialog(
            title = stringResource(R.string.settings_audio_output_mode),
            onDismiss = { onShowAudioOutputPreferenceDialogChange(false) }
        ) {
            outputOptions.forEachIndexed { index, option ->
                LevelOption(
                    level = index,
                    text = option.second,
                    currentLevel = if (uiState.playerAudioOutputPreference == option.first) index else -1,
                    onSelect = {
                        viewModel.setPlayerAudioOutputPreference(option.first)
                        onShowAudioOutputPreferenceDialogChange(false)
                    }
                )
            }
        }
    }

    if (showSurfaceModeDialog) {
        val surfaceOptions = remember(context) {
            listOf(
                PlayerSurfaceMode.AUTO to context.getString(R.string.settings_surface_auto),
                PlayerSurfaceMode.SURFACE_VIEW to context.getString(R.string.settings_surface_surface_view),
                PlayerSurfaceMode.TEXTURE_VIEW to context.getString(R.string.settings_surface_texture_view)
            )
        }
        PremiumSelectionDialog(
            title = stringResource(R.string.settings_surface_mode),
            onDismiss = { onShowSurfaceModeDialogChange(false) }
        ) {
            surfaceOptions.forEachIndexed { index, option ->
                LevelOption(
                    level = index,
                    text = option.second,
                    currentLevel = if (uiState.playerSurfaceMode == option.first) index else -1,
                    onSelect = {
                        viewModel.setPlayerSurfaceMode(option.first)
                        onShowSurfaceModeDialogChange(false)
                    }
                )
            }
        }
    }

    if (showVodHttpProtocolDialog) {
        val protocolOptions = remember(context) {
            listOf(
                VodHttpProtocolMode.COMPATIBILITY_HTTP1 to context.getString(R.string.settings_vod_http_protocol_compatibility),
                VodHttpProtocolMode.AUTO to context.getString(R.string.settings_vod_http_protocol_auto)
            )
        }
        PremiumSelectionDialog(
            title = stringResource(R.string.settings_vod_http_protocol_mode),
            onDismiss = { onShowVodHttpProtocolDialogChange(false) }
        ) {
            protocolOptions.forEachIndexed { index, option ->
                LevelOption(
                    level = index,
                    text = option.second,
                    currentLevel = if (uiState.playerVodHttpProtocolMode == option.first) index else -1,
                    onSelect = {
                        viewModel.setPlayerVodHttpProtocolMode(option.first)
                        onShowVodHttpProtocolDialogChange(false)
                    }
                )
            }
        }
    }

    if (showControlsTimeoutDialog) {
        TimeoutValueDialog(
            title = stringResource(R.string.settings_player_controls_timeout),
            subtitle = stringResource(R.string.settings_timeout_vod_controls_subtitle),
            initialValue = uiState.playerControlsTimeoutSeconds,
            onDismiss = { onShowControlsTimeoutDialogChange(false) }
        ) { seconds ->
            viewModel.setPlayerControlsTimeoutSeconds(seconds)
            onShowControlsTimeoutDialogChange(false)
        }
    }

    if (showLiveOverlayTimeoutDialog) {
        TimeoutValueDialog(
            title = stringResource(R.string.settings_live_overlay_timeout),
            subtitle = stringResource(R.string.settings_timeout_live_overlays_subtitle),
            initialValue = uiState.playerLiveOverlayTimeoutSeconds,
            onDismiss = { onShowLiveOverlayTimeoutDialogChange(false) }
        ) { seconds ->
            viewModel.setPlayerLiveOverlayTimeoutSeconds(seconds)
            onShowLiveOverlayTimeoutDialogChange(false)
        }
    }

    if (showNoticeTimeoutDialog) {
        TimeoutValueDialog(
            title = stringResource(R.string.settings_player_notice_timeout),
            subtitle = stringResource(R.string.settings_timeout_notices_subtitle),
            initialValue = uiState.playerNoticeTimeoutSeconds,
            onDismiss = { onShowNoticeTimeoutDialogChange(false) }
        ) { seconds ->
            viewModel.setPlayerNoticeTimeoutSeconds(seconds)
            onShowNoticeTimeoutDialogChange(false)
        }
    }

    if (showDiagnosticsTimeoutDialog) {
        TimeoutValueDialog(
            title = stringResource(R.string.settings_player_diagnostics_timeout),
            subtitle = stringResource(R.string.settings_timeout_diagnostics_subtitle),
            initialValue = uiState.playerDiagnosticsTimeoutSeconds,
            onDismiss = { onShowDiagnosticsTimeoutDialogChange(false) }
        ) { seconds ->
            viewModel.setPlayerDiagnosticsTimeoutSeconds(seconds)
            onShowDiagnosticsTimeoutDialogChange(false)
        }
    }

    if (showTimeshiftDepthDialog) {
        val depthOptions = remember(context) {
            listOf(
                15 to context.getString(R.string.settings_live_timeshift_depth_15),
                30 to context.getString(R.string.settings_live_timeshift_depth_30),
                60 to context.getString(R.string.settings_live_timeshift_depth_60)
            )
        }
        PremiumSelectionDialog(
            title = stringResource(R.string.settings_select_live_timeshift_depth),
            onDismiss = { onShowTimeshiftDepthDialogChange(false) }
        ) {
            depthOptions.forEachIndexed { index, option ->
                LevelOption(
                    level = index,
                    text = option.second,
                    currentLevel = if (uiState.playerTimeshiftDepthMinutes == option.first) index else -1,
                    onSelect = {
                        viewModel.setPlayerTimeshiftDepthMinutes(option.first)
                        onShowTimeshiftDepthDialogChange(false)
                    }
                )
            }
        }
    }

    if (showTimeshiftBackendDialog) {
        val backendOptions = remember(context) {
            listOf(
                TimeshiftBackendPreference.AUTOMATIC to context.getString(R.string.settings_live_timeshift_backend_auto),
                TimeshiftBackendPreference.STORAGE to context.getString(R.string.settings_live_timeshift_backend_storage),
                TimeshiftBackendPreference.MEMORY to context.getString(R.string.settings_live_timeshift_backend_memory)
            )
        }
        PremiumSelectionDialog(
            title = stringResource(R.string.settings_select_live_timeshift_backend),
            onDismiss = { onShowTimeshiftBackendDialogChange(false) }
        ) {
            backendOptions.forEachIndexed { index, option ->
                LevelOption(
                    level = index,
                    text = option.second,
                    currentLevel = if (uiState.playerTimeshiftBackend == option.first) index else -1,
                    onSelect = {
                        viewModel.setPlayerTimeshiftBackend(option.first)
                        onShowTimeshiftBackendDialogChange(false)
                    }
                )
            }
        }
    }

    if (showDefaultStopTimerDialog) {
        PlaybackTimerPresetDialog(
            title = stringResource(R.string.settings_default_stop_timer),
            selectedMinutes = uiState.defaultStopPlaybackTimerMinutes,
            onDismiss = { onShowDefaultStopTimerDialogChange(false) },
            onSelect = { minutes ->
                viewModel.setDefaultStopPlaybackTimerMinutes(minutes)
                onShowDefaultStopTimerDialogChange(false)
            }
        )
    }

    if (showDefaultIdleTimerDialog) {
        PlaybackTimerPresetDialog(
            title = stringResource(R.string.settings_default_idle_standby_timer),
            selectedMinutes = uiState.defaultIdleStandbyTimerMinutes,
            onDismiss = { onShowDefaultIdleTimerDialogChange(false) },
            onSelect = { minutes ->
                viewModel.setDefaultIdleStandbyTimerMinutes(minutes)
                onShowDefaultIdleTimerDialogChange(false)
            }
        )
    }

    if (showLiveTranslationEndpointDialog) {
        SimpleTextValueDialog(
            title = stringResource(R.string.settings_live_translation_endpoint),
            subtitle = stringResource(R.string.settings_live_translation_endpoint_subtitle),
            initialValue = uiState.playerLiveTranslationEndpoint,
            onDismiss = { onShowLiveTranslationEndpointDialogChange(false) },
            onConfirm = { endpoint ->
                if (endpoint.isBlank()) {
                    viewModel.showUserMessage(
                        context.getString(R.string.settings_live_translation_endpoint_empty_error)
                    )
                } else {
                    viewModel.setPlayerLiveTranslationEndpoint(endpoint)
                }
                onShowLiveTranslationEndpointDialogChange(false)
            }
        )
    }
}

private fun AppTimeFormat.labelResId(): Int = when (this) {
    AppTimeFormat.SYSTEM -> R.string.settings_time_format_system
    AppTimeFormat.TWELVE_HOUR -> R.string.settings_time_format_12h
    AppTimeFormat.TWENTY_FOUR_HOUR -> R.string.settings_time_format_24h
}
