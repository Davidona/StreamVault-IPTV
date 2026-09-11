package com.streamvault.domain.settings

import com.streamvault.domain.model.GroupedChannelLabelMode
import com.streamvault.domain.model.AudioOutputPreference
import com.streamvault.domain.model.ChannelNumberingMode
import com.streamvault.domain.model.CategorySortMode
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.DecoderMode
import com.streamvault.domain.model.AppHomeDashboardShelf
import com.streamvault.domain.model.AppLandingDestination
import com.streamvault.domain.model.AppTimeFormat
import com.streamvault.domain.model.AppTheme
import com.streamvault.domain.model.LiveChannelGroupingMode
import com.streamvault.domain.model.LiveStreamFormatMode
import com.streamvault.domain.model.LiveVariantPreferenceMode
import com.streamvault.domain.model.LiveClockFont
import com.streamvault.domain.model.LiveClockPosition
import com.streamvault.domain.model.LiveClockSize
import com.streamvault.domain.model.AppTopLevelDestination
import com.streamvault.domain.model.PlaybackBufferMode
import com.streamvault.domain.model.VodDuplicateHandlingMode
import com.streamvault.domain.model.VodHttpProtocolMode
import com.streamvault.domain.model.VodVariantPreferenceMode
import com.streamvault.domain.model.PlayerSurfaceMode
import com.streamvault.domain.model.RemoteColorButton
import com.streamvault.domain.model.RemoteShortcutPreferences
import com.streamvault.domain.model.RemoteShortcutProfile
import com.streamvault.domain.model.RemoteShortcutSelection
import com.streamvault.domain.model.TimeshiftBackendPreference
import kotlinx.coroutines.flow.Flow

/** Preference streams and commands consumed by settings; persistence remains in the data layer. */
interface SettingsPreferences {
    val isIncognitoMode: Flow<Boolean>

    val useXtreamTextClassification: Flow<Boolean>

    val xtreamBase64TextCompatibility: Flow<Boolean>

    val playerMediaSessionEnabled: Flow<Boolean>

    val playerFastRetryOnTransientFailures: Flow<Boolean>

    val playerAudioDecoderMode: Flow<DecoderMode>

    val playerVideoDecoderMode: Flow<DecoderMode>

    val playerPlaybackBufferMode: Flow<PlaybackBufferMode>

    val playerSurfaceMode: Flow<PlayerSurfaceMode>

    val playerLiveStreamFormatMode: Flow<LiveStreamFormatMode>

    val playerVodHttpProtocolMode: Flow<VodHttpProtocolMode>

    val playerAudioOutputPreference: Flow<AudioOutputPreference>

    val playerCompatibilityMemoryEnabled: Flow<Boolean>

    val playerPlaybackSpeed: Flow<Float>

    val playerExternalPlaybackMode: Flow<com.streamvault.domain.model.ExternalPlaybackMode>

    val playerAudioVideoOffsetMs: Flow<Int>

    val playerAudioVideoSyncEnabled: Flow<Boolean>

    val preferredAudioLanguage: Flow<String?>

    val playerSubtitleTextScale: Flow<Float>

    val playerSubtitleTextColor: Flow<Int>

    val playerSubtitleBackgroundColor: Flow<Int>

    val playerLiveTranslationEnabled: Flow<Boolean>

    val playerLiveTranslationEndpoint: Flow<String>

    val playerControlsTimeoutSeconds: Flow<Int>

    val playerLiveOverlayTimeoutSeconds: Flow<Int>

    val playerLiveClockEnabled: Flow<Boolean>

    val playerLiveClockPosition: Flow<LiveClockPosition>

    val playerLiveClockSize: Flow<LiveClockSize>

    val playerLiveClockFont: Flow<LiveClockFont>

    val playerNoticeTimeoutSeconds: Flow<Int>

    val playerDiagnosticsTimeoutSeconds: Flow<Int>

    val playerWifiMaxVideoHeight: Flow<Int?>

    val playerEthernetMaxVideoHeight: Flow<Int?>

    val playerTimeshiftEnabled: Flow<Boolean>

    val playerTimeshiftDepthMinutes: Flow<Int>

    val playerTimeshiftBackend: Flow<TimeshiftBackendPreference>

    val defaultStopPlaybackTimerMinutes: Flow<Int>

    val defaultIdleStandbyTimerMinutes: Flow<Int>

    val lastSpeedTestMegabits: Flow<Double?>

    val lastSpeedTestTimestamp: Flow<Long?>

    val lastSpeedTestTransport: Flow<String?>

    val lastSpeedTestRecommendedHeight: Flow<Int?>

    val lastSpeedTestEstimated: Flow<Boolean>

    val parentalControlLevel: Flow<Int>

    val hasParentalPin: Flow<Boolean>

    suspend fun setLastActiveProviderId(id: Long)

    suspend fun setParentalControlLevel(level: Int)

    suspend fun setIncognitoMode(enabled: Boolean)

    suspend fun setUseXtreamTextClassification(enabled: Boolean)

    suspend fun setXtreamBase64TextCompatibility(enabled: Boolean)

    suspend fun bumpXtreamTextImportGeneration(): Long

    suspend fun getXtreamTextImportGeneration(): Long

    suspend fun getXtreamTextImportAppliedGeneration(providerId: Long): Long

    suspend fun markXtreamTextImportApplied(providerId: Long, generation: Long)

    val preventStandbyDuringPlayback: Flow<Boolean>

    val autoPlayNextEpisode: Flow<Boolean>

    val autoCheckAppUpdates: Flow<Boolean>

    val autoDownloadAppUpdates: Flow<Boolean>

    val lastAppUpdateCheckTimestamp: Flow<Long?>

    val lastAppUpdateFailureTimestamp: Flow<Long?>

    val cachedAppUpdateVersionName: Flow<String?>

    val cachedAppUpdateVersionCode: Flow<Int?>

    val cachedAppUpdateReleaseUrl: Flow<String?>

    val cachedAppUpdateDownloadUrl: Flow<String?>

    val cachedAppUpdateDownloadSha256: Flow<String?>

    val cachedAppUpdateReleaseNotes: Flow<String>

    val cachedAppUpdatePublishedAt: Flow<String?>

    val lastMaintenanceSnapshot: Flow<DatabaseMaintenanceSnapshot?>

    val zapAutoRevert: Flow<Boolean>

    val recordingWifiOnly: Flow<Boolean>

    val recordingPaddingBeforeMinutes: Flow<Int>

    val recordingPaddingAfterMinutes: Flow<Int>

    suspend fun setZapAutoRevert(enabled: Boolean)

    suspend fun setRecordingWifiOnly(enabled: Boolean)

    suspend fun setRecordingPaddingBeforeMinutes(minutes: Int)

    suspend fun setRecordingPaddingAfterMinutes(minutes: Int)

    suspend fun setPreventStandbyDuringPlayback(prevent: Boolean)

    suspend fun setAutoPlayNextEpisode(enabled: Boolean)

    suspend fun setAutoCheckAppUpdates(enabled: Boolean)

    suspend fun setAutoDownloadAppUpdates(enabled: Boolean)

    suspend fun setLastAppUpdateCheckTimestamp(timestampMs: Long?)

    suspend fun setLastAppUpdateFailureTimestamp(timestampMs: Long?)

    suspend fun setLastAppUpdateAttemptTimestamp(timestampMs: Long?)

    suspend fun setLastAppUpdateOutcome(outcome: String?)

    suspend fun setCachedAppUpdateRelease(
        versionName: String?,
        versionCode: Int?,
        releaseUrl: String?,
        downloadUrl: String?,
        downloadSha256: String?,
        releaseNotes: String?,
        publishedAt: String?
    )

    suspend fun setPlayerMediaSessionEnabled(enabled: Boolean)

    suspend fun setPlayerFastRetryOnTransientFailures(enabled: Boolean)

    suspend fun setPlayerAudioDecoderMode(mode: DecoderMode)

    suspend fun setPlayerVideoDecoderMode(mode: DecoderMode)

    suspend fun setPlayerPlaybackBufferMode(mode: PlaybackBufferMode)

    suspend fun setPlayerAudioOutputPreference(preference: AudioOutputPreference)

    suspend fun setPlayerCompatibilityMemoryEnabled(enabled: Boolean)

    suspend fun setPlayerSurfaceMode(mode: PlayerSurfaceMode)

    suspend fun setPlayerLiveStreamFormatMode(mode: LiveStreamFormatMode)

    suspend fun setPlayerVodHttpProtocolMode(mode: VodHttpProtocolMode)

    suspend fun setPlayerPlaybackSpeed(speed: Float)

    suspend fun setPlayerExternalPlaybackMode(mode: com.streamvault.domain.model.ExternalPlaybackMode)

    suspend fun setPlayerAudioVideoOffsetMs(offsetMs: Int)

    suspend fun setPlayerAudioVideoSyncEnabled(enabled: Boolean)

    suspend fun setPreferredAudioLanguage(languageTag: String?)

    suspend fun setPlayerSubtitleTextScale(scale: Float)

    suspend fun setPlayerSubtitleTextColor(colorArgb: Int)

    suspend fun setPlayerSubtitleBackgroundColor(colorArgb: Int)

    suspend fun setPlayerLiveTranslationEnabled(enabled: Boolean)

    suspend fun setPlayerLiveTranslationEndpoint(endpoint: String)

    suspend fun setPlayerControlsTimeoutSeconds(seconds: Int)

    suspend fun setPlayerLiveOverlayTimeoutSeconds(seconds: Int)

    suspend fun setPlayerLiveClockEnabled(enabled: Boolean)

    suspend fun setPlayerLiveClockPosition(position: LiveClockPosition)

    suspend fun setPlayerLiveClockSize(size: LiveClockSize)

    suspend fun setPlayerLiveClockFont(font: LiveClockFont)

    suspend fun setPlayerNoticeTimeoutSeconds(seconds: Int)

    suspend fun setPlayerDiagnosticsTimeoutSeconds(seconds: Int)

    suspend fun setPlayerWifiMaxVideoHeight(maxHeight: Int?)

    suspend fun setPlayerEthernetMaxVideoHeight(maxHeight: Int?)

    suspend fun setPlayerTimeshiftEnabled(enabled: Boolean)

    suspend fun setPlayerTimeshiftDepthMinutes(minutes: Int)

    suspend fun setPlayerTimeshiftBackend(preference: TimeshiftBackendPreference)

    suspend fun setDefaultStopPlaybackTimerMinutes(minutes: Int)

    suspend fun setDefaultIdleStandbyTimerMinutes(minutes: Int)

    suspend fun setLastSpeedTestResult(
        megabitsPerSecond: Double?,
        measuredAtMs: Long?,
        transport: String?,
        recommendedMaxHeight: Int?,
        estimated: Boolean
    )

    suspend fun setParentalPin(pin: String)

    suspend fun verifyParentalPin(pin: String): Boolean

    val appLanguage: Flow<String>

    val remoteShortcutPreferences: Flow<RemoteShortcutPreferences>

    suspend fun setAppLanguage(language: String)

    suspend fun setRemoteShortcutSelection(
        profile: RemoteShortcutProfile,
        button: RemoteColorButton,
        selection: RemoteShortcutSelection
    )

    val appLandingDestination: Flow<AppLandingDestination>

    val appTopLevelDestinations: Flow<List<AppTopLevelDestination>>

    val appHomeDashboardShelves: Flow<List<AppHomeDashboardShelf>>

    suspend fun setAppLandingDestination(destination: AppLandingDestination)

    suspend fun setAppTopLevelDestinations(destinations: List<AppTopLevelDestination>)

    suspend fun setAppHomeDashboardShelves(shelves: List<AppHomeDashboardShelf>)

    val appTimeFormat: Flow<AppTimeFormat>

    suspend fun setAppTimeFormat(format: AppTimeFormat)

    val appTheme: Flow<AppTheme>

    suspend fun setAppTheme(theme: AppTheme)

    val liveTvChannelMode: Flow<String?>

    suspend fun setLiveTvChannelMode(mode: String)

    val liveTvAutoHideCategories: Flow<Boolean>

    suspend fun setLiveTvAutoHideCategories(enabled: Boolean)

    val showLiveSourceSwitcher: Flow<Boolean>

    suspend fun setShowLiveSourceSwitcher(enabled: Boolean)

    val showFavoritesCategory: Flow<Boolean>

    suspend fun setShowFavoritesCategory(enabled: Boolean)

    val showAllChannelsCategory: Flow<Boolean>

    suspend fun setShowAllChannelsCategory(enabled: Boolean)

    val showRecentChannelsCategory: Flow<Boolean>

    suspend fun setShowRecentChannelsCategory(enabled: Boolean)

    val liveTvQuickFilterVisibility: Flow<String?>

    suspend fun setLiveTvQuickFilterVisibility(mode: String)

    val liveTvCategoryFilters: Flow<List<String>>

    suspend fun addLiveTvCategoryFilter(filter: String): Boolean

    suspend fun removeLiveTvCategoryFilter(filter: String): Boolean

    val hideDecorativeLiveRows: Flow<Boolean>

    suspend fun setHideDecorativeLiveRows(hide: Boolean)

    val liveChannelNumberingMode: Flow<ChannelNumberingMode>

    suspend fun setLiveChannelNumberingMode(mode: ChannelNumberingMode)

    val liveChannelGroupingMode: Flow<LiveChannelGroupingMode>

    suspend fun setLiveChannelGroupingMode(mode: LiveChannelGroupingMode)

    val groupedChannelLabelMode: Flow<GroupedChannelLabelMode>

    suspend fun setGroupedChannelLabelMode(mode: GroupedChannelLabelMode)

    val liveVariantPreferenceMode: Flow<LiveVariantPreferenceMode>

    suspend fun setLiveVariantPreferenceMode(mode: LiveVariantPreferenceMode)

    val vodDuplicateHandlingMode: Flow<VodDuplicateHandlingMode>

    suspend fun setVodDuplicateHandlingMode(mode: VodDuplicateHandlingMode)

    val vodVariantPreferenceMode: Flow<VodVariantPreferenceMode>

    suspend fun setVodVariantPreferenceMode(mode: VodVariantPreferenceMode)

    val vodViewMode: Flow<String?>

    suspend fun setVodViewMode(mode: String)

    val vodTypeBadgeAsIcon: Flow<Boolean>

    suspend fun setVodTypeBadgeAsIcon(enabled: Boolean)

    val vodInfiniteScroll: Flow<Boolean>

    suspend fun setVodInfiniteScroll(enabled: Boolean)

    val vodPortalSearch: Flow<Boolean>

    suspend fun setVodPortalSearch(enabled: Boolean)

    val vodCategoryLoadMode: Flow<com.streamvault.domain.model.VodCategoryLoadMode>

    suspend fun setVodCategoryLoadMode(mode: com.streamvault.domain.model.VodCategoryLoadMode)

    val guideDefaultCategoryId: Flow<Long?>

    suspend fun setGuideDefaultCategoryId(categoryId: Long)

    val epgTimeShiftsByProvider: Flow<Map<Long, Int>>

    suspend fun setEpgTimeShiftMinutes(providerId: Long, minutes: Int)

    fun getHiddenCategoryIds(providerId: Long, type: ContentType): Flow<Set<Long>>

    suspend fun setCategoryHidden(
        providerId: Long,
        type: ContentType,
        categoryId: Long,
        hidden: Boolean
    )

    suspend fun setHiddenCategoryIds(
        providerId: Long,
        type: ContentType,
        categoryIds: Set<Long>
    )

    fun getCategorySortMode(providerId: Long, type: ContentType): Flow<CategorySortMode>

    suspend fun setCategorySortMode(providerId: Long, type: ContentType, mode: CategorySortMode)

    val multiViewCenterTwoSlotLayout: Flow<Boolean>

    suspend fun setMultiViewCenterTwoSlotLayout(enabled: Boolean)

    val multiViewRespectProviderConnectionLimit: Flow<Boolean>

    suspend fun setMultiViewRespectProviderConnectionLimit(enabled: Boolean)

    suspend fun clearAllRecentData()
}
