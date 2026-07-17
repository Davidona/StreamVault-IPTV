@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.streamvault.app.ui.screens.settings

import com.streamvault.app.ui.model.LiveTvChannelMode
import com.streamvault.app.ui.model.LiveTvQuickFilterVisibilityMode
import com.streamvault.app.ui.model.VodViewMode
import com.streamvault.domain.model.AppHomeDashboardShelf
import com.streamvault.domain.model.AppLandingDestination
import com.streamvault.domain.model.AppTopLevelDestination
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.domain.model.AppTimeFormat
import com.streamvault.domain.model.AudioOutputPreference
import com.streamvault.domain.model.ChannelNumberingMode
import com.streamvault.domain.model.DecoderMode
import com.streamvault.domain.model.ExternalPlaybackMode
import com.streamvault.domain.model.GroupedChannelLabelMode
import com.streamvault.domain.model.LiveChannelGroupingMode
import com.streamvault.domain.model.LiveVariantPreferenceMode
import com.streamvault.domain.model.PlaybackBufferMode
import com.streamvault.domain.model.TimeshiftBackendPreference
import com.streamvault.domain.model.VodDuplicateHandlingMode
import com.streamvault.domain.model.VodHttpProtocolMode
import com.streamvault.domain.model.VodVariantPreferenceMode
import com.streamvault.domain.model.VirtualCategoryIds
import com.streamvault.domain.repository.ProviderRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

internal fun observeSettingsPreferenceSnapshot(
    providerRepository: ProviderRepository,
    activeProviderIdFlow: Flow<Long?>,
    preferencesRepository: PreferencesRepository
): Flow<SettingsPreferenceSnapshot> {
    return combine(
        providerRepository.getProviders(),
        activeProviderIdFlow,
        preferencesRepository.parentalControlLevel,
        preferencesRepository.hasParentalPin
    ) { providers, activeId, level, hasParentalPin ->
        SettingsPreferenceSnapshot(
            providers = providers,
            activeProviderId = activeId,
            parentalControlLevel = level,
            hasParentalPin = hasParentalPin,
            appLanguage = "system",
            appLandingDestination = AppLandingDestination.HOME,
            appTopLevelDestinations = AppTopLevelDestination.defaultOrder,
            appHomeDashboardShelves = AppHomeDashboardShelf.defaultOrder,
            appTimeFormat = AppTimeFormat.SYSTEM,
            preferredAudioLanguage = "auto",
            playerMediaSessionEnabled = true,
            playerFastRetryOnTransientFailures = false,
            playerAudioDecoderMode = DecoderMode.AUTO,
            playerVideoDecoderMode = DecoderMode.AUTO,
            playerPlaybackBufferMode = PlaybackBufferMode.AUTO,
            playerAudioOutputPreference = AudioOutputPreference.AUTO,
            playerCompatibilityMemoryEnabled = true,
            playerSurfaceMode = com.streamvault.domain.model.PlayerSurfaceMode.AUTO,
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
            showLiveSourceSwitcher = false,
            showFavoritesCategory = true,
            showAllChannelsCategory = true,
            showRecentChannelsCategory = true,
            remoteShortcutPreferences = com.streamvault.domain.model.RemoteShortcutPreferences(),
            liveTvCategoryFilters = emptyList(),
            liveTvQuickFilterVisibilityMode = LiveTvQuickFilterVisibilityMode.ALWAYS_VISIBLE,
            hideDecorativeLiveRows = true,
            liveChannelNumberingMode = ChannelNumberingMode.GROUP,
            liveChannelGroupingMode = LiveChannelGroupingMode.RAW_VARIANTS,
            groupedChannelLabelMode = GroupedChannelLabelMode.HYBRID,
            liveVariantPreferenceMode = LiveVariantPreferenceMode.BALANCED,
            vodViewMode = VodViewMode.MODERN,
            vodInfiniteScroll = true,
            vodDuplicateHandlingMode = VodDuplicateHandlingMode.GROUPED,
            vodVariantPreferenceMode = VodVariantPreferenceMode.BALANCED,
            guideDefaultCategoryId = VirtualCategoryIds.FAVORITES,
            guideDefaultCategoryOptions = emptyList(),
            preventStandbyDuringPlayback = true,
            zapAutoRevert = true,
            autoPlayNextEpisode = true,
            autoCheckAppUpdates = true,
            autoDownloadAppUpdates = false,
            lastAppUpdateCheckAt = null,
            cachedAppUpdateVersionName = null,
            cachedAppUpdateVersionCode = null,
            cachedAppUpdateReleaseUrl = null,
            cachedAppUpdateDownloadUrl = null,
            cachedAppUpdateDownloadSha256 = null,
            cachedAppUpdateReleaseNotes = "",
            cachedAppUpdatePublishedAt = null
        )
    }.combine(preferencesRepository.appLanguage) { snapshot, language ->
        snapshot.copy(appLanguage = language)
    }.combine(preferencesRepository.appLandingDestination) { snapshot, destination ->
        snapshot.copy(appLandingDestination = destination)
    }.combine(preferencesRepository.appTopLevelDestinations) { snapshot, destinations ->
        snapshot.copy(appTopLevelDestinations = destinations)
    }.combine(preferencesRepository.appHomeDashboardShelves) { snapshot, shelves ->
        snapshot.copy(appHomeDashboardShelves = shelves)
    }.combine(preferencesRepository.appTimeFormat) { snapshot, timeFormat ->
        snapshot.copy(appTimeFormat = timeFormat)
    }.combine(preferencesRepository.preferredAudioLanguage) { snapshot, preferredAudioLanguage ->
        snapshot.copy(preferredAudioLanguage = preferredAudioLanguage ?: "auto")
    }.combine(preferencesRepository.playerMediaSessionEnabled) { snapshot, mediaSessionEnabled ->
        snapshot.copy(playerMediaSessionEnabled = mediaSessionEnabled)
    }.combine(preferencesRepository.playerFastRetryOnTransientFailures) { snapshot, enabled ->
        snapshot.copy(playerFastRetryOnTransientFailures = enabled)
    }.combine(preferencesRepository.playerAudioDecoderMode) { snapshot, decoderMode ->
        snapshot.copy(playerAudioDecoderMode = decoderMode)
    }.combine(preferencesRepository.playerVideoDecoderMode) { snapshot, decoderMode ->
        snapshot.copy(playerVideoDecoderMode = decoderMode)
    }.combine(preferencesRepository.playerPlaybackBufferMode) { snapshot, bufferMode ->
        snapshot.copy(playerPlaybackBufferMode = bufferMode)
    }.combine(preferencesRepository.playerAudioOutputPreference) { snapshot, audioOutputPreference ->
        snapshot.copy(playerAudioOutputPreference = audioOutputPreference)
    }.combine(preferencesRepository.playerCompatibilityMemoryEnabled) { snapshot, enabled ->
        snapshot.copy(playerCompatibilityMemoryEnabled = enabled)
    }.combine(preferencesRepository.playerSurfaceMode) { snapshot, surfaceMode ->
        snapshot.copy(playerSurfaceMode = surfaceMode)
    }.combine(preferencesRepository.playerVodHttpProtocolMode) { snapshot, protocolMode ->
        snapshot.copy(playerVodHttpProtocolMode = protocolMode)
    }.combine(preferencesRepository.playerPlaybackSpeed) { snapshot, playerPlaybackSpeed ->
        snapshot.copy(playerPlaybackSpeed = playerPlaybackSpeed)
    }.combine(preferencesRepository.playerExternalPlaybackMode) { snapshot, externalMode ->
        snapshot.copy(playerExternalPlaybackMode = externalMode)
    }.combine(preferencesRepository.playerAudioVideoSyncEnabled) { snapshot, enabled ->
        snapshot.copy(playerAudioVideoSyncEnabled = enabled)
    }.combine(preferencesRepository.playerAudioVideoOffsetMs) { snapshot, playerAudioVideoOffsetMs ->
        snapshot.copy(playerAudioVideoOffsetMs = playerAudioVideoOffsetMs)
    }.combine(preferencesRepository.multiViewCenterTwoSlotLayout) { snapshot, centerTwoSlotLayout ->
        snapshot.copy(centerTwoSlotMultiviewLayout = centerTwoSlotLayout)
    }.combine(preferencesRepository.multiViewRespectProviderConnectionLimit) { snapshot, respectLimit ->
        snapshot.copy(multiViewRespectProviderConnectionLimit = respectLimit)
    }.combine(preferencesRepository.playerControlsTimeoutSeconds) { snapshot, timeoutSeconds ->
        snapshot.copy(playerControlsTimeoutSeconds = timeoutSeconds)
    }.combine(preferencesRepository.playerLiveOverlayTimeoutSeconds) { snapshot, timeoutSeconds ->
        snapshot.copy(playerLiveOverlayTimeoutSeconds = timeoutSeconds)
    }.combine(preferencesRepository.playerNoticeTimeoutSeconds) { snapshot, timeoutSeconds ->
        snapshot.copy(playerNoticeTimeoutSeconds = timeoutSeconds)
    }.combine(preferencesRepository.playerDiagnosticsTimeoutSeconds) { snapshot, timeoutSeconds ->
        snapshot.copy(playerDiagnosticsTimeoutSeconds = timeoutSeconds)
    }.combine(preferencesRepository.playerSubtitleTextScale) { snapshot, subtitleTextScale ->
        snapshot.copy(subtitleTextScale = subtitleTextScale)
    }.combine(preferencesRepository.playerSubtitleTextColor) { snapshot, subtitleTextColor ->
        snapshot.copy(subtitleTextColor = subtitleTextColor)
    }.combine(preferencesRepository.playerSubtitleBackgroundColor) { snapshot, subtitleBackgroundColor ->
        snapshot.copy(subtitleBackgroundColor = subtitleBackgroundColor)
    }.combine(preferencesRepository.playerLiveTranslationEnabled) { snapshot, enabled ->
        snapshot.copy(playerLiveTranslationEnabled = enabled)
    }.combine(preferencesRepository.playerLiveTranslationEndpoint) { snapshot, endpoint ->
        snapshot.copy(playerLiveTranslationEndpoint = endpoint)
    }.combine(preferencesRepository.playerWifiMaxVideoHeight) { snapshot, wifiMaxVideoHeight ->
        snapshot.copy(wifiMaxVideoHeight = wifiMaxVideoHeight)
    }.combine(preferencesRepository.playerEthernetMaxVideoHeight) { snapshot, ethernetMaxVideoHeight ->
        snapshot.copy(ethernetMaxVideoHeight = ethernetMaxVideoHeight)
    }.combine(preferencesRepository.playerTimeshiftEnabled) { snapshot, enabled ->
        snapshot.copy(playerTimeshiftEnabled = enabled)
    }.combine(preferencesRepository.playerTimeshiftDepthMinutes) { snapshot, depthMinutes ->
        snapshot.copy(playerTimeshiftDepthMinutes = depthMinutes)
    }.combine(preferencesRepository.playerTimeshiftBackend) { snapshot, backend ->
        snapshot.copy(playerTimeshiftBackend = backend)
    }.combine(preferencesRepository.defaultStopPlaybackTimerMinutes) { snapshot, minutes ->
        snapshot.copy(defaultStopPlaybackTimerMinutes = minutes)
    }.combine(preferencesRepository.defaultIdleStandbyTimerMinutes) { snapshot, minutes ->
        snapshot.copy(defaultIdleStandbyTimerMinutes = minutes)
    }.combine(preferencesRepository.lastSpeedTestMegabits) { snapshot, lastSpeedTestMegabits ->
        snapshot.copy(lastSpeedTestMegabits = lastSpeedTestMegabits)
    }.combine(preferencesRepository.lastSpeedTestTimestamp) { snapshot, lastSpeedTestTimestamp ->
        snapshot.copy(lastSpeedTestTimestamp = lastSpeedTestTimestamp)
    }.combine(preferencesRepository.lastSpeedTestTransport) { snapshot, lastSpeedTestTransport ->
        snapshot.copy(lastSpeedTestTransport = lastSpeedTestTransport)
    }.combine(preferencesRepository.lastSpeedTestRecommendedHeight) { snapshot, lastSpeedTestRecommendedHeight ->
        snapshot.copy(lastSpeedTestRecommendedHeight = lastSpeedTestRecommendedHeight)
    }.combine(preferencesRepository.lastSpeedTestEstimated) { snapshot, lastSpeedTestEstimated ->
        snapshot.copy(lastSpeedTestEstimated = lastSpeedTestEstimated)
    }.combine(preferencesRepository.isIncognitoMode) { snapshot, incognito ->
        snapshot.copy(isIncognitoMode = incognito)
    }.combine(preferencesRepository.useXtreamTextClassification) { snapshot, useTextClass ->
        snapshot.copy(useXtreamTextClassification = useTextClass)
    }.combine(preferencesRepository.xtreamBase64TextCompatibility) { snapshot, compatibilityEnabled ->
        snapshot.copy(xtreamBase64TextCompatibility = compatibilityEnabled)
    }.combine(preferencesRepository.liveTvChannelMode) { snapshot, liveTvChannelMode ->
        snapshot.copy(liveTvChannelMode = LiveTvChannelMode.fromStorage(liveTvChannelMode))
    }.combine(preferencesRepository.showLiveSourceSwitcher) { snapshot, showLiveSourceSwitcher ->
        snapshot.copy(showLiveSourceSwitcher = showLiveSourceSwitcher)
    }.combine(preferencesRepository.showFavoritesCategory) { snapshot, showFavoritesCategory ->
        snapshot.copy(showFavoritesCategory = showFavoritesCategory)
    }.combine(preferencesRepository.showAllChannelsCategory) { snapshot, showAllChannelsCategory ->
        snapshot.copy(showAllChannelsCategory = showAllChannelsCategory)
    }.combine(preferencesRepository.showRecentChannelsCategory) { snapshot, showRecentChannelsCategory ->
        snapshot.copy(showRecentChannelsCategory = showRecentChannelsCategory)
    }.combine(preferencesRepository.remoteShortcutPreferences) { snapshot, remoteShortcutPreferences ->
        snapshot.copy(remoteShortcutPreferences = remoteShortcutPreferences)
    }.combine(preferencesRepository.liveTvCategoryFilters) { snapshot, liveTvCategoryFilters ->
        snapshot.copy(liveTvCategoryFilters = liveTvCategoryFilters)
    }.combine(preferencesRepository.liveTvQuickFilterVisibility) { snapshot, visibilityMode ->
        snapshot.copy(
            liveTvQuickFilterVisibilityMode = LiveTvQuickFilterVisibilityMode.fromStorage(visibilityMode)
        )
    }.combine(preferencesRepository.hideDecorativeLiveRows) { snapshot, hideDecorativeLiveRows ->
        snapshot.copy(hideDecorativeLiveRows = hideDecorativeLiveRows)
    }.combine(preferencesRepository.liveChannelNumberingMode) { snapshot, liveChannelNumberingMode ->
        snapshot.copy(liveChannelNumberingMode = liveChannelNumberingMode)
    }.combine(preferencesRepository.liveChannelGroupingMode) { snapshot, liveChannelGroupingMode ->
        snapshot.copy(liveChannelGroupingMode = liveChannelGroupingMode)
    }.combine(preferencesRepository.groupedChannelLabelMode) { snapshot, groupedChannelLabelMode ->
        snapshot.copy(groupedChannelLabelMode = groupedChannelLabelMode)
    }.combine(preferencesRepository.liveVariantPreferenceMode) { snapshot, liveVariantPreferenceMode ->
        snapshot.copy(liveVariantPreferenceMode = liveVariantPreferenceMode)
    }.combine(preferencesRepository.vodViewMode) { snapshot, vodViewMode ->
        snapshot.copy(vodViewMode = VodViewMode.fromStorage(vodViewMode))
    }.combine(preferencesRepository.vodInfiniteScroll) { snapshot, vodInfiniteScroll ->
        snapshot.copy(vodInfiniteScroll = vodInfiniteScroll)
    }.combine(preferencesRepository.vodDuplicateHandlingMode) { snapshot, vodDuplicateHandlingMode ->
        snapshot.copy(vodDuplicateHandlingMode = vodDuplicateHandlingMode)
    }.combine(preferencesRepository.vodVariantPreferenceMode) { snapshot, vodVariantPreferenceMode ->
        snapshot.copy(vodVariantPreferenceMode = vodVariantPreferenceMode)
    }.combine(preferencesRepository.guideDefaultCategoryId) { snapshot, guideDefaultCategoryId ->
        snapshot.copy(guideDefaultCategoryId = guideDefaultCategoryId ?: VirtualCategoryIds.FAVORITES)
    }.combine(preferencesRepository.preventStandbyDuringPlayback) { snapshot, preventStandby ->
        snapshot.copy(preventStandbyDuringPlayback = preventStandby)
    }.combine(preferencesRepository.zapAutoRevert) { snapshot, zapAutoRevert ->
        snapshot.copy(zapAutoRevert = zapAutoRevert)
    }.combine(preferencesRepository.autoPlayNextEpisode) { snapshot, autoPlayNextEpisode ->
        snapshot.copy(autoPlayNextEpisode = autoPlayNextEpisode)
    }.combine(preferencesRepository.autoCheckAppUpdates) { snapshot, autoCheckAppUpdates ->
        snapshot.copy(autoCheckAppUpdates = autoCheckAppUpdates)
    }.combine(preferencesRepository.autoDownloadAppUpdates) { snapshot, autoDownloadAppUpdates ->
        snapshot.copy(autoDownloadAppUpdates = autoDownloadAppUpdates)
    }.combine(preferencesRepository.lastAppUpdateCheckTimestamp) { snapshot, lastAppUpdateCheckAt ->
        snapshot.copy(lastAppUpdateCheckAt = lastAppUpdateCheckAt)
    }.combine(preferencesRepository.cachedAppUpdateVersionName) { snapshot, versionName ->
        snapshot.copy(cachedAppUpdateVersionName = versionName)
    }.combine(preferencesRepository.cachedAppUpdateVersionCode) { snapshot, versionCode ->
        snapshot.copy(cachedAppUpdateVersionCode = versionCode)
    }.combine(preferencesRepository.cachedAppUpdateReleaseUrl) { snapshot, releaseUrl ->
        snapshot.copy(cachedAppUpdateReleaseUrl = releaseUrl)
    }.combine(preferencesRepository.cachedAppUpdateDownloadUrl) { snapshot, downloadUrl ->
        snapshot.copy(cachedAppUpdateDownloadUrl = downloadUrl)
    }.combine(preferencesRepository.cachedAppUpdateDownloadSha256) { snapshot, downloadSha256 ->
        snapshot.copy(cachedAppUpdateDownloadSha256 = downloadSha256)
    }.combine(preferencesRepository.cachedAppUpdateReleaseNotes) { snapshot, releaseNotes ->
        snapshot.copy(cachedAppUpdateReleaseNotes = releaseNotes)
    }.combine(preferencesRepository.cachedAppUpdatePublishedAt) { snapshot, publishedAt ->
        snapshot.copy(cachedAppUpdatePublishedAt = publishedAt)
    }
}
