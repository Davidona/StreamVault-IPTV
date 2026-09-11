package com.streamvault.feature.settings.presentation

import android.content.Context
import com.streamvault.feature.settings.R
import com.streamvault.domain.model.LiveTvChannelMode
import com.streamvault.domain.model.LiveTvQuickFilterVisibilityMode
import com.streamvault.domain.model.VodViewMode
import com.streamvault.domain.model.AppTimeFormat
import com.streamvault.domain.model.AppTheme
import com.streamvault.domain.model.AppHomeDashboardShelf
import com.streamvault.domain.model.AppLandingDestination
import com.streamvault.domain.model.AppTopLevelDestination
import com.streamvault.domain.model.AudioOutputPreference
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.VodCategoryLoadMode
import com.streamvault.domain.model.ExternalPlaybackMode
import com.streamvault.domain.model.ChannelNumberingMode
import com.streamvault.domain.model.DecoderMode
import com.streamvault.domain.model.GroupedChannelLabelMode
import com.streamvault.domain.model.LiveChannelGroupingMode
import com.streamvault.domain.model.LiveVariantPreferenceMode
import com.streamvault.domain.model.LiveClockFont
import com.streamvault.domain.model.LiveClockPosition
import com.streamvault.domain.model.LiveClockSize
import com.streamvault.domain.model.PlaybackBufferMode
import com.streamvault.domain.model.VodDuplicateHandlingMode
import com.streamvault.domain.model.VodHttpProtocolMode
import com.streamvault.domain.model.VodVariantPreferenceMode
import com.streamvault.domain.model.PlayerSurfaceMode
import com.streamvault.domain.model.PlayerBackButtonVisibility
import com.streamvault.domain.model.LegacyProvider as Provider
import com.streamvault.domain.model.RemoteShortcutPreferences
import com.streamvault.domain.model.TimeshiftBackendPreference

enum class ProviderWarningAction {
    EPG,
    MOVIES,
    SERIES
}

enum class ProviderSyncSelection {
    SYNC_NOW,
    REBUILD_INDEX,
    TV,
    MOVIES,
    SERIES,
    EPG
}

data class SettingsPreferenceSnapshot(
    val providers: List<Provider>,
    val activeProviderId: Long?,
    val parentalControlLevel: Int,
    val hasParentalPin: Boolean,
    val appLanguage: String,
    val appLandingDestination: AppLandingDestination,
    val appTopLevelDestinations: List<AppTopLevelDestination>,
    val appHomeDashboardShelves: List<AppHomeDashboardShelf>,
    val appTimeFormat: AppTimeFormat,
    val appTheme: AppTheme,
    val preferredAudioLanguage: String,
    val playerMediaSessionEnabled: Boolean,
    val playerBackButtonVisibility: PlayerBackButtonVisibility,
    val playerFastRetryOnTransientFailures: Boolean,
    val playerAudioDecoderMode: DecoderMode,
    val playerVideoDecoderMode: DecoderMode,
    val playerPlaybackBufferMode: PlaybackBufferMode,
    val playerAudioOutputPreference: AudioOutputPreference,
    val playerCompatibilityMemoryEnabled: Boolean,
    val playerSurfaceMode: PlayerSurfaceMode,
    val playerVodHttpProtocolMode: VodHttpProtocolMode,
    val playerPlaybackSpeed: Float,
    val playerExternalPlaybackMode: ExternalPlaybackMode,
    val playerAudioVideoSyncEnabled: Boolean,
    val playerAudioVideoOffsetMs: Int,
    val centerTwoSlotMultiviewLayout: Boolean,
    val multiViewRespectProviderConnectionLimit: Boolean,
    val playerControlsTimeoutSeconds: Int,
    val playerLiveOverlayTimeoutSeconds: Int,
    val playerLiveClockEnabled: Boolean = false,
    val playerLiveClockPosition: LiveClockPosition = LiveClockPosition.TOP_END,
    val playerLiveClockSize: LiveClockSize = LiveClockSize.MEDIUM,
    val playerLiveClockFont: LiveClockFont = LiveClockFont.DIGITAL_MONO,
    val playerNoticeTimeoutSeconds: Int,
    val playerDiagnosticsTimeoutSeconds: Int,
    val subtitleTextScale: Float,
    val subtitleTextColor: Int,
    val subtitleBackgroundColor: Int,
    val playerLiveTranslationEnabled: Boolean,
    val playerLiveTranslationEndpoint: String,
    val wifiMaxVideoHeight: Int?,
    val ethernetMaxVideoHeight: Int?,
    val playerTimeshiftEnabled: Boolean,
    val playerTimeshiftDepthMinutes: Int,
    val playerTimeshiftBackend: TimeshiftBackendPreference,
    val defaultStopPlaybackTimerMinutes: Int,
    val defaultIdleStandbyTimerMinutes: Int,
    val lastSpeedTestMegabits: Double?,
    val lastSpeedTestTimestamp: Long?,
    val lastSpeedTestTransport: String?,
    val lastSpeedTestRecommendedHeight: Int?,
    val lastSpeedTestEstimated: Boolean,
    val isIncognitoMode: Boolean,
    val useXtreamTextClassification: Boolean,
    val xtreamBase64TextCompatibility: Boolean,
    val liveTvChannelMode: LiveTvChannelMode,
    val liveTvAutoHideCategories: Boolean,
    val showLiveSourceSwitcher: Boolean,
    val showFavoritesCategory: Boolean,
    val showAllChannelsCategory: Boolean,
    val showRecentChannelsCategory: Boolean,
    val remoteShortcutPreferences: RemoteShortcutPreferences,
    val liveTvCategoryFilters: List<String>,
    val liveTvQuickFilterVisibilityMode: LiveTvQuickFilterVisibilityMode,
    val hideDecorativeLiveRows: Boolean,
    val liveChannelNumberingMode: ChannelNumberingMode,
    val liveChannelGroupingMode: LiveChannelGroupingMode,
    val groupedChannelLabelMode: GroupedChannelLabelMode,
    val liveVariantPreferenceMode: LiveVariantPreferenceMode,
    val vodViewMode: VodViewMode,
    val vodTypeBadgeAsIcon: Boolean,
    val vodCategoryLoadMode: VodCategoryLoadMode,
    val vodInfiniteScroll: Boolean,
    val vodPortalSearch: Boolean = true,
    val vodDuplicateHandlingMode: VodDuplicateHandlingMode,
    val vodVariantPreferenceMode: VodVariantPreferenceMode,
    val guideDefaultCategoryId: Long,
    val guideDefaultCategoryOptions: List<Category>,
    val preventStandbyDuringPlayback: Boolean,
    val zapAutoRevert: Boolean,
    val autoPlayNextEpisode: Boolean,
    val autoCheckAppUpdates: Boolean,
    val autoDownloadAppUpdates: Boolean,
    val lastAppUpdateCheckAt: Long?,
    val cachedAppUpdateVersionName: String?,
    val cachedAppUpdateVersionCode: Int?,
    val cachedAppUpdateReleaseUrl: String?,
    val cachedAppUpdateDownloadUrl: String?,
    val cachedAppUpdateDownloadSha256: String?,
    val cachedAppUpdateReleaseNotes: String,
    val cachedAppUpdatePublishedAt: String?
)

fun ProviderSyncSelection.label(context: Context): String = when (this) {
    ProviderSyncSelection.SYNC_NOW -> context.getString(R.string.settings_sync_option_sync_now)
    ProviderSyncSelection.REBUILD_INDEX -> context.getString(R.string.settings_sync_option_rebuild_index)
    ProviderSyncSelection.TV -> context.getString(R.string.settings_sync_option_tv)
    ProviderSyncSelection.MOVIES -> context.getString(R.string.settings_sync_option_movies)
    ProviderSyncSelection.SERIES -> context.getString(R.string.settings_sync_option_series)
    ProviderSyncSelection.EPG -> context.getString(R.string.settings_sync_option_epg)
}
