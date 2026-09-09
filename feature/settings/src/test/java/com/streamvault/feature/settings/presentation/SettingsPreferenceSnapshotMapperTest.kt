package com.streamvault.feature.settings.presentation

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.AppHomeDashboardShelf
import com.streamvault.domain.model.AppLandingDestination
import com.streamvault.domain.model.AppTimeFormat
import com.streamvault.domain.model.AppTheme
import com.streamvault.domain.model.AppTopLevelDestination
import com.streamvault.domain.model.AudioOutputPreference
import com.streamvault.domain.model.ChannelNumberingMode
import com.streamvault.domain.model.DecoderMode
import com.streamvault.domain.model.ExternalPlaybackMode
import com.streamvault.domain.model.GroupedChannelLabelMode
import com.streamvault.domain.model.LiveChannelGroupingMode
import com.streamvault.domain.model.LiveTvChannelMode
import com.streamvault.domain.model.LiveTvQuickFilterVisibilityMode
import com.streamvault.domain.model.LiveVariantPreferenceMode
import com.streamvault.domain.model.PlaybackBufferMode
import com.streamvault.domain.model.PlayerSurfaceMode
import com.streamvault.domain.model.RemoteShortcutPreferences
import com.streamvault.domain.model.TimeshiftBackendPreference
import com.streamvault.domain.model.VodCategoryLoadMode
import com.streamvault.domain.model.VodDuplicateHandlingMode
import com.streamvault.domain.model.VodHttpProtocolMode
import com.streamvault.domain.model.VodVariantPreferenceMode
import com.streamvault.domain.model.VodViewMode
import org.junit.Test

class SettingsPreferenceSnapshotMapperTest {
    @Test
    fun `applying snapshot marks cached newer release as available`() {
        val snapshot = cachedReleaseSnapshot()

        val mappedState = SettingsUiState().applyPreferenceSnapshot(
            snapshot = snapshot,
            isRemoteVersionNewer = { versionCode, versionName, publishedAt ->
                versionCode == 42 &&
                    versionName == "9.9.9" &&
                    publishedAt == "2026-08-30T00:00:00Z"
            }
        )

        assertThat(mappedState.appUpdate.isUpdateAvailable).isTrue()
        assertThat(mappedState.liveTvAutoHideCategories).isFalse()
        assertThat(mappedState.appTheme).isEqualTo(AppTheme.M3_PURPLE)
        assertThat(mappedState.vodTypeBadgeAsIcon).isTrue()
    }

    private fun cachedReleaseSnapshot() = SettingsPreferenceSnapshot(
        providers = emptyList(),
        activeProviderId = null,
        parentalControlLevel = 0,
        hasParentalPin = false,
        appLanguage = "system",
        appLandingDestination = AppLandingDestination.HOME,
        appTopLevelDestinations = AppTopLevelDestination.defaultOrder,
        appHomeDashboardShelves = AppHomeDashboardShelf.defaultOrder,
        appTimeFormat = AppTimeFormat.SYSTEM,
        appTheme = AppTheme.M3_PURPLE,
        preferredAudioLanguage = "auto",
        playerMediaSessionEnabled = true,
        playerFastRetryOnTransientFailures = false,
        playerAudioDecoderMode = DecoderMode.AUTO,
        playerVideoDecoderMode = DecoderMode.AUTO,
        playerPlaybackBufferMode = PlaybackBufferMode.AUTO,
        playerAudioOutputPreference = AudioOutputPreference.AUTO,
        playerCompatibilityMemoryEnabled = true,
        playerSurfaceMode = PlayerSurfaceMode.AUTO,
        playerVodHttpProtocolMode = VodHttpProtocolMode.COMPATIBILITY_HTTP1,
        playerPlaybackSpeed = 1f,
        playerExternalPlaybackMode = ExternalPlaybackMode.INTERNAL_PLAYER,
        playerAudioVideoSyncEnabled = false,
        playerAudioVideoOffsetMs = 0,
        centerTwoSlotMultiviewLayout = false,
        multiViewRespectProviderConnectionLimit = true,
        playerControlsTimeoutSeconds = 5,
        playerLiveOverlayTimeoutSeconds = 4,
        playerNoticeTimeoutSeconds = 6,
        playerDiagnosticsTimeoutSeconds = 15,
        subtitleTextScale = 1f,
        subtitleTextColor = 0xFFFFFFFF.toInt(),
        subtitleBackgroundColor = 0x80000000.toInt(),
        playerLiveTranslationEnabled = false,
        playerLiveTranslationEndpoint = "http://10.0.2.2:8765",
        wifiMaxVideoHeight = null,
        ethernetMaxVideoHeight = null,
        playerTimeshiftEnabled = false,
        playerTimeshiftDepthMinutes = 30,
        playerTimeshiftBackend = TimeshiftBackendPreference.AUTOMATIC,
        defaultStopPlaybackTimerMinutes = 0,
        defaultIdleStandbyTimerMinutes = 0,
        lastSpeedTestMegabits = null,
        lastSpeedTestTimestamp = null,
        lastSpeedTestTransport = null,
        lastSpeedTestRecommendedHeight = null,
        lastSpeedTestEstimated = false,
        isIncognitoMode = false,
        useXtreamTextClassification = true,
        xtreamBase64TextCompatibility = false,
        liveTvChannelMode = LiveTvChannelMode.PRO,
        liveTvAutoHideCategories = false,
        showLiveSourceSwitcher = false,
        showFavoritesCategory = true,
        showAllChannelsCategory = true,
        showRecentChannelsCategory = true,
        remoteShortcutPreferences = RemoteShortcutPreferences(),
        liveTvCategoryFilters = emptyList(),
        liveTvQuickFilterVisibilityMode = LiveTvQuickFilterVisibilityMode.ALWAYS_VISIBLE,
        hideDecorativeLiveRows = true,
        liveChannelNumberingMode = ChannelNumberingMode.GROUP,
        liveChannelGroupingMode = LiveChannelGroupingMode.RAW_VARIANTS,
        groupedChannelLabelMode = GroupedChannelLabelMode.HYBRID,
        liveVariantPreferenceMode = LiveVariantPreferenceMode.BALANCED,
        vodViewMode = VodViewMode.MODERN,
        vodTypeBadgeAsIcon = true,
        vodCategoryLoadMode = VodCategoryLoadMode.PAGED,
        vodInfiniteScroll = true,
        vodDuplicateHandlingMode = VodDuplicateHandlingMode.SHOW_ALL,
        vodVariantPreferenceMode = VodVariantPreferenceMode.BALANCED,
        guideDefaultCategoryId = -1L,
        guideDefaultCategoryOptions = emptyList(),
        preventStandbyDuringPlayback = true,
        zapAutoRevert = true,
        autoPlayNextEpisode = true,
        autoCheckAppUpdates = true,
        autoDownloadAppUpdates = false,
        lastAppUpdateCheckAt = 1_777_777_777_000L,
        cachedAppUpdateVersionName = "9.9.9",
        cachedAppUpdateVersionCode = 42,
        cachedAppUpdateReleaseUrl = "https://example.test/releases/9.9.9",
        cachedAppUpdateDownloadUrl = "https://example.test/downloads/9.9.9.apk",
        cachedAppUpdateDownloadSha256 = "abc123",
        cachedAppUpdateReleaseNotes = "Cached release notes",
        cachedAppUpdatePublishedAt = "2026-08-30T00:00:00Z"
    )
}
