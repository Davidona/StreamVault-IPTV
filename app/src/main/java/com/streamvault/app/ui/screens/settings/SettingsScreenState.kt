package com.streamvault.app.ui.screens.settings

import com.streamvault.feature.settings.presentation.*

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.streamvault.app.MainActivity
import com.streamvault.app.R
import com.streamvault.app.ui.time.createDateTimeFormat
import com.streamvault.app.util.OfficialBuildStatus
import com.streamvault.domain.model.AppHomeDashboardShelf
import com.streamvault.domain.model.AppLandingDestination
import com.streamvault.domain.model.AppTopLevelDestination
import com.streamvault.domain.model.AppTimeFormat
import com.streamvault.domain.model.AudioOutputPreference
import com.streamvault.domain.model.PlaybackBufferMode
import com.streamvault.domain.model.TimeshiftBackendPreference
import com.streamvault.domain.model.VodHttpProtocolMode

internal data class SettingsScreenLabels(
    val buildVerificationLabel: String,
    val appLanguageLabel: String,
    val appLandingDestinationLabel: String,
    val topNavigationSummaryLabel: String,
    val homeDashboardSummaryLabel: String,
    val timeFormatLabel: String,
    val preferredAudioLanguageLabel: String,
    val playbackSpeedLabel: String,
    val audioVideoOffsetLabel: String,
    val audioDecoderModeLabel: String,
    val videoDecoderModeLabel: String,
    val playbackBufferModeLabel: String,
    val audioOutputPreferenceLabel: String,
    val surfaceModeLabel: String,
    val vodHttpProtocolLabel: String,
    val controlsTimeoutLabel: String,
    val liveOverlayTimeoutLabel: String,
    val noticeTimeoutLabel: String,
    val diagnosticsTimeoutLabel: String,
    val subtitleSizeLabel: String,
    val subtitleTextColorLabel: String,
    val subtitleBackgroundLabel: String,
    val liveTranslationEndpointLabel: String,
    val wifiQualityLabel: String,
    val ethernetQualityLabel: String,
    val timeshiftDepthLabel: String,
    val timeshiftBackendLabel: String,
    val defaultStopTimerLabel: String,
    val defaultIdleTimerLabel: String,
    val lastSpeedTestLabel: String,
    val lastSpeedTestSummary: String,
    val speedTestRecommendationLabel: String,
    val protectionSummary: String,
    val guideDefaultCategoryLabel: String,
    val externalPlaybackModeLabel: String
)

@Composable
internal fun rememberSettingsScreenLabels(
    uiState: SettingsUiState,
    context: Context,
    officialBuildStatus: OfficialBuildStatus
): SettingsScreenLabels {
    val buildVerificationLabel = remember(officialBuildStatus, context) {
        formatOfficialBuildStatusLabel(officialBuildStatus, context)
    }
    val appLanguageLabel = remember(uiState.appLanguage, context) {
        displayLanguageLabel(uiState.appLanguage, context.getString(R.string.settings_system_default))
    }
    val appLandingDestinationLabel = remember(uiState.appLandingDestination, context) {
        formatAppLandingDestinationLabel(uiState.appLandingDestination, context)
    }
    val topNavigationSummaryLabel = remember(uiState.appTopLevelDestinations, context) {
        formatTopNavigationSummaryLabel(uiState.appTopLevelDestinations, context)
    }
    val homeDashboardSummaryLabel = remember(uiState.appHomeDashboardShelves, context) {
        formatHomeDashboardSummaryLabel(uiState.appHomeDashboardShelves, context)
    }
    val timeFormatLabel = remember(uiState.appTimeFormat, context) {
        formatAppTimeFormatLabel(uiState.appTimeFormat, context)
    }
    val dateTimeFormat = remember(uiState.appTimeFormat) { uiState.appTimeFormat.createDateTimeFormat() }
    val preferredAudioLanguageLabel = remember(uiState.preferredAudioLanguage, context) {
        displayLanguageLabel(uiState.preferredAudioLanguage, context.getString(R.string.settings_audio_language_auto))
    }
    val playbackSpeedLabel = remember(uiState.playerPlaybackSpeed) {
        formatPlaybackSpeedLabel(uiState.playerPlaybackSpeed)
    }
    val audioVideoOffsetLabel = remember(uiState.playerAudioVideoOffsetMs) {
        formatAudioVideoOffsetLabel(uiState.playerAudioVideoOffsetMs)
    }
    val audioDecoderModeLabel = remember(uiState.playerAudioDecoderMode, context) {
        formatDecoderModeLabel(uiState.playerAudioDecoderMode, context)
    }
    val videoDecoderModeLabel = remember(uiState.playerVideoDecoderMode, context) {
        formatDecoderModeLabel(uiState.playerVideoDecoderMode, context)
    }
    val playbackBufferModeLabel = remember(uiState.playerPlaybackBufferMode, context) {
        formatPlaybackBufferModeLabel(uiState.playerPlaybackBufferMode, context)
    }
    val audioOutputPreferenceLabel = remember(uiState.playerAudioOutputPreference, context) {
        formatAudioOutputPreferenceLabel(uiState.playerAudioOutputPreference, context)
    }
    val surfaceModeLabel = remember(uiState.playerSurfaceMode, context) {
        formatSurfaceModeLabel(uiState.playerSurfaceMode, context)
    }
    val vodHttpProtocolLabel = remember(uiState.playerVodHttpProtocolMode, context) {
        formatVodHttpProtocolModeLabel(uiState.playerVodHttpProtocolMode, context)
    }
    val controlsTimeoutLabel = remember(uiState.playerControlsTimeoutSeconds, context) {
        formatTimeoutSecondsLabel(uiState.playerControlsTimeoutSeconds, context)
    }
    val liveOverlayTimeoutLabel = remember(uiState.playerLiveOverlayTimeoutSeconds, context) {
        formatTimeoutSecondsLabel(uiState.playerLiveOverlayTimeoutSeconds, context)
    }
    val noticeTimeoutLabel = remember(uiState.playerNoticeTimeoutSeconds, context) {
        formatTimeoutSecondsLabel(uiState.playerNoticeTimeoutSeconds, context)
    }
    val diagnosticsTimeoutLabel = remember(uiState.playerDiagnosticsTimeoutSeconds, context) {
        formatTimeoutSecondsLabel(uiState.playerDiagnosticsTimeoutSeconds, context)
    }
    val subtitleSizeLabel = remember(uiState.subtitleTextScale, context) {
        formatSubtitleSizeLabel(uiState.subtitleTextScale, context)
    }
    val subtitleTextColorLabel = remember(uiState.subtitleTextColor, context) {
        formatSubtitleColorLabel(uiState.subtitleTextColor, subtitleTextColorOptions(context))
    }
    val subtitleBackgroundLabel = remember(uiState.subtitleBackgroundColor, context) {
        formatSubtitleColorLabel(uiState.subtitleBackgroundColor, subtitleBackgroundColorOptions(context))
    }
    val liveTranslationEndpointLabel = remember(uiState.playerLiveTranslationEndpoint) {
        uiState.playerLiveTranslationEndpoint
    }
    val wifiQualityLabel = remember(uiState.wifiMaxVideoHeight, context) {
        formatQualityCapLabel(uiState.wifiMaxVideoHeight, context.getString(R.string.settings_quality_cap_auto))
    }
    val ethernetQualityLabel = remember(uiState.ethernetMaxVideoHeight, context) {
        formatQualityCapLabel(uiState.ethernetMaxVideoHeight, context.getString(R.string.settings_quality_cap_auto))
    }
    val timeshiftDepthLabel = remember(uiState.playerTimeshiftDepthMinutes, context) {
        formatTimeshiftDepthLabel(uiState.playerTimeshiftDepthMinutes, context)
    }
    val timeshiftBackendLabel = remember(uiState.playerTimeshiftBackend, context) {
        formatTimeshiftBackendPreferenceLabel(uiState.playerTimeshiftBackend, context)
    }
    val defaultStopTimerLabel = remember(uiState.defaultStopPlaybackTimerMinutes, context) {
        formatPlaybackTimerMinutesLabel(uiState.defaultStopPlaybackTimerMinutes, context)
    }
    val defaultIdleTimerLabel = remember(uiState.defaultIdleStandbyTimerMinutes, context) {
        formatPlaybackTimerMinutesLabel(uiState.defaultIdleStandbyTimerMinutes, context)
    }
    val lastSpeedTestLabel = remember(uiState.lastSpeedTest, context) {
        uiState.lastSpeedTest?.let(::formatSpeedTestValueLabel)
            ?: context.getString(R.string.settings_speed_test_not_run)
    }
    val lastSpeedTestSummary = remember(uiState.lastSpeedTest, context, dateTimeFormat) {
        uiState.lastSpeedTest?.let { formatSpeedTestSummary(it, context, dateTimeFormat) }
            ?: context.getString(R.string.settings_speed_test_summary_default)
    }
    val speedTestRecommendationLabel = remember(uiState.lastSpeedTest, context) {
        formatQualityCapLabel(
            uiState.lastSpeedTest?.recommendedMaxVideoHeight,
            context.getString(R.string.settings_quality_cap_auto)
        )
    }
    val protectionSummary = remember(uiState.parentalControlLevel, context) {
        when (uiState.parentalControlLevel) {
            0 -> context.getString(R.string.settings_level_off)
            1 -> context.getString(R.string.settings_level_locked)
            2 -> context.getString(R.string.settings_level_private)
            3 -> context.getString(R.string.settings_level_hidden)
            else -> context.getString(R.string.settings_level_unknown)
        }
    }
    val guideDefaultCategoryLabel = remember(
        uiState.guideDefaultCategoryId,
        uiState.guideDefaultCategoryOptions,
        context
    ) {
        uiState.guideDefaultCategoryOptions
            .firstOrNull { it.id == uiState.guideDefaultCategoryId }
            ?.name
            ?: context.getString(R.string.settings_guide_default_category_fallback)
    }
    val externalPlaybackModeLabel = remember(uiState.playerExternalPlaybackMode, context) {
        formatExternalPlaybackModeLabel(uiState.playerExternalPlaybackMode, context)
    }

    return SettingsScreenLabels(
        buildVerificationLabel = buildVerificationLabel,
        appLanguageLabel = appLanguageLabel,
        appLandingDestinationLabel = appLandingDestinationLabel,
        topNavigationSummaryLabel = topNavigationSummaryLabel,
        homeDashboardSummaryLabel = homeDashboardSummaryLabel,
        timeFormatLabel = timeFormatLabel,
        preferredAudioLanguageLabel = preferredAudioLanguageLabel,
        playbackSpeedLabel = playbackSpeedLabel,
        audioVideoOffsetLabel = audioVideoOffsetLabel,
        audioDecoderModeLabel = audioDecoderModeLabel,
        videoDecoderModeLabel = videoDecoderModeLabel,
        playbackBufferModeLabel = playbackBufferModeLabel,
        audioOutputPreferenceLabel = audioOutputPreferenceLabel,
        surfaceModeLabel = surfaceModeLabel,
        vodHttpProtocolLabel = vodHttpProtocolLabel,
        controlsTimeoutLabel = controlsTimeoutLabel,
        liveOverlayTimeoutLabel = liveOverlayTimeoutLabel,
        noticeTimeoutLabel = noticeTimeoutLabel,
        diagnosticsTimeoutLabel = diagnosticsTimeoutLabel,
        subtitleSizeLabel = subtitleSizeLabel,
        subtitleTextColorLabel = subtitleTextColorLabel,
        subtitleBackgroundLabel = subtitleBackgroundLabel,
        liveTranslationEndpointLabel = liveTranslationEndpointLabel,
        wifiQualityLabel = wifiQualityLabel,
        ethernetQualityLabel = ethernetQualityLabel,
        timeshiftDepthLabel = timeshiftDepthLabel,
        timeshiftBackendLabel = timeshiftBackendLabel,
        defaultStopTimerLabel = defaultStopTimerLabel,
        defaultIdleTimerLabel = defaultIdleTimerLabel,
        lastSpeedTestLabel = lastSpeedTestLabel,
        lastSpeedTestSummary = lastSpeedTestSummary,
        speedTestRecommendationLabel = speedTestRecommendationLabel,
        protectionSummary = protectionSummary,
        guideDefaultCategoryLabel = guideDefaultCategoryLabel,
        externalPlaybackModeLabel = externalPlaybackModeLabel
    )
}

internal fun Context.findMainActivity(): MainActivity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is MainActivity) return current
        current = current.baseContext
    }
    return null
}

private fun formatAppLandingDestinationLabel(
    destination: AppLandingDestination,
    context: Context
): String = context.getString(
    when (destination) {
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
)

private fun formatTopNavigationSummaryLabel(
    destinations: List<AppTopLevelDestination>,
    context: Context
): String = context.resources.getQuantityString(
    R.plurals.settings_top_navigation_count,
    destinations.size,
    destinations.size
)

private fun formatHomeDashboardSummaryLabel(
    shelves: List<AppHomeDashboardShelf>,
    context: Context
): String = context.resources.getQuantityString(
    R.plurals.settings_home_dashboard_count,
    shelves.size,
    shelves.size
)

private fun formatOfficialBuildStatusLabel(
    status: OfficialBuildStatus,
    context: Context
): String = when (status) {
    OfficialBuildStatus.OFFICIAL -> context.getString(R.string.settings_build_verification_official)
    OfficialBuildStatus.UNOFFICIAL -> context.getString(R.string.settings_build_verification_unofficial)
    OfficialBuildStatus.VERIFICATION_UNAVAILABLE -> context.getString(R.string.settings_build_verification_unavailable)
}

private fun formatAppTimeFormatLabel(
    format: AppTimeFormat,
    context: Context
): String = context.getString(
    when (format) {
        AppTimeFormat.SYSTEM -> R.string.settings_time_format_system
        AppTimeFormat.TWELVE_HOUR -> R.string.settings_time_format_12h
        AppTimeFormat.TWENTY_FOUR_HOUR -> R.string.settings_time_format_24h
    }
)

private fun formatTimeshiftDepthLabel(
    depthMinutes: Int,
    context: Context
): String = when (depthMinutes) {
    15 -> context.getString(R.string.settings_live_timeshift_depth_15)
    60 -> context.getString(R.string.settings_live_timeshift_depth_60)
    else -> context.getString(R.string.settings_live_timeshift_depth_30)
}

internal fun formatVodHttpProtocolModeLabel(
    mode: VodHttpProtocolMode,
    context: Context
): String = when (mode) {
    VodHttpProtocolMode.COMPATIBILITY_HTTP1 -> context.getString(R.string.settings_vod_http_protocol_compatibility)
    VodHttpProtocolMode.AUTO -> context.getString(R.string.settings_vod_http_protocol_auto)
}

internal fun formatPlaybackBufferModeLabel(
    mode: PlaybackBufferMode,
    context: Context
): String = when (mode) {
    PlaybackBufferMode.AUTO -> context.getString(R.string.settings_live_buffer_auto)
    PlaybackBufferMode.SMALL -> context.getString(R.string.settings_live_buffer_small)
    PlaybackBufferMode.MEDIUM -> context.getString(R.string.settings_live_buffer_medium)
    PlaybackBufferMode.LARGE -> context.getString(R.string.settings_live_buffer_large)
}

internal fun formatTimeshiftBackendPreferenceLabel(
    preference: TimeshiftBackendPreference,
    context: Context
): String = when (preference) {
    TimeshiftBackendPreference.AUTOMATIC -> context.getString(R.string.settings_live_timeshift_backend_auto)
    TimeshiftBackendPreference.STORAGE -> context.getString(R.string.settings_live_timeshift_backend_storage)
    TimeshiftBackendPreference.MEMORY -> context.getString(R.string.settings_live_timeshift_backend_memory)
}

private fun formatExternalPlaybackModeLabel(
    mode: com.streamvault.domain.model.ExternalPlaybackMode,
    context: Context
): String = when (mode) {
    com.streamvault.domain.model.ExternalPlaybackMode.INTERNAL_PLAYER -> context.getString(R.string.settings_external_playback_mode_internal)
    com.streamvault.domain.model.ExternalPlaybackMode.ASK_EVERY_TIME -> context.getString(R.string.settings_external_playback_mode_external)
    com.streamvault.domain.model.ExternalPlaybackMode.EXTERNAL_PLAYER -> context.getString(R.string.settings_external_playback_mode_external)
}
