package com.streamvault.feature.playback.player

import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.LiveChannelObservedQuality
import com.streamvault.domain.model.VodVariantObservation
import javax.inject.Inject

/**
 * Player-facing preferences boundary.
 *
 * The player should depend on the preferences it needs, not on the full
 * application preferences store. Keeping this adapter in the feature also
 * gives preference reads and writes one seam for future migration to a
 * player-specific settings source.
 */
class PlayerPreferencesCoordinator @Inject constructor(
    private val preferencesRepository: PreferencesRepository,
) {
    internal val appLanguage get() = preferencesRepository.appLanguage
    internal val autoPlayNextEpisode get() = preferencesRepository.autoPlayNextEpisode
    internal val defaultIdleStandbyTimerMinutes get() = preferencesRepository.defaultIdleStandbyTimerMinutes
    internal val defaultStopPlaybackTimerMinutes get() = preferencesRepository.defaultStopPlaybackTimerMinutes
    internal val liveChannelNumberingMode get() = preferencesRepository.liveChannelNumberingMode
    internal val liveVariantObservations get() = preferencesRepository.liveVariantObservations
    internal val parentalControlLevel get() = preferencesRepository.parentalControlLevel
    internal val playerAudioDecoderMode get() = preferencesRepository.playerAudioDecoderMode
    internal val playerAudioOutputPreference get() = preferencesRepository.playerAudioOutputPreference
    internal val playerAudioVideoOffsetMs get() = preferencesRepository.playerAudioVideoOffsetMs
    internal val playerCompatibilityMemoryEnabled get() = preferencesRepository.playerCompatibilityMemoryEnabled
    internal val playerControlsTimeoutSeconds get() = preferencesRepository.playerControlsTimeoutSeconds
    internal val playerDiagnosticsTimeoutSeconds get() = preferencesRepository.playerDiagnosticsTimeoutSeconds
    internal val playerEthernetMaxVideoHeight get() = preferencesRepository.playerEthernetMaxVideoHeight
    internal val playerExternalPlaybackMode get() = preferencesRepository.playerExternalPlaybackMode
    internal val playerFastRetryOnTransientFailures get() = preferencesRepository.playerFastRetryOnTransientFailures
    internal val playerLiveOverlayTimeoutSeconds get() = preferencesRepository.playerLiveOverlayTimeoutSeconds
    internal val playerLiveTranslationEnabled get() = preferencesRepository.playerLiveTranslationEnabled
    internal val playerLiveTranslationEndpoint get() = preferencesRepository.playerLiveTranslationEndpoint
    internal val playerMediaSessionEnabled get() = preferencesRepository.playerMediaSessionEnabled
    internal val playerMuted get() = preferencesRepository.playerMuted
    internal val playerNoticeTimeoutSeconds get() = preferencesRepository.playerNoticeTimeoutSeconds
    internal val playerPlaybackBufferMode get() = preferencesRepository.playerPlaybackBufferMode
    internal val playerPlaybackSpeed get() = preferencesRepository.playerPlaybackSpeed
    internal val playerSubtitleBackgroundColor get() = preferencesRepository.playerSubtitleBackgroundColor
    internal val playerSubtitleTextColor get() = preferencesRepository.playerSubtitleTextColor
    internal val playerSubtitleTextScale get() = preferencesRepository.playerSubtitleTextScale
    internal val playerSurfaceMode get() = preferencesRepository.playerSurfaceMode
    internal val playerTimeshiftBackend get() = preferencesRepository.playerTimeshiftBackend
    internal val playerTimeshiftDepthMinutes get() = preferencesRepository.playerTimeshiftDepthMinutes
    internal val playerTimeshiftEnabled get() = preferencesRepository.playerTimeshiftEnabled
    internal val playerVideoDecoderMode get() = preferencesRepository.playerVideoDecoderMode
    internal val playerVodHttpProtocolMode get() = preferencesRepository.playerVodHttpProtocolMode
    internal val playerWifiMaxVideoHeight get() = preferencesRepository.playerWifiMaxVideoHeight
    internal val preventStandbyDuringPlayback get() = preferencesRepository.preventStandbyDuringPlayback
    internal val preferredAudioLanguage get() = preferencesRepository.preferredAudioLanguage
    internal val remoteShortcutPreferences get() = preferencesRepository.remoteShortcutPreferences
    internal val vodVariantObservations get() = preferencesRepository.vodVariantObservations
    internal val zapAutoRevert get() = preferencesRepository.zapAutoRevert

    internal fun getAspectRatioForChannel(channelId: Long) =
        preferencesRepository.getAspectRatioForChannel(channelId)

    internal fun getHiddenCategoryIds(providerId: Long, type: ContentType) =
        preferencesRepository.getHiddenCategoryIds(providerId, type)

    internal fun getLastLiveCategoryId(providerId: Long) =
        preferencesRepository.getLastLiveCategoryId(providerId)

    internal fun observeAudioVideoOffsetForChannel(channelId: Long) =
        preferencesRepository.observeAudioVideoOffsetForChannel(channelId)

    internal suspend fun clearAudioVideoOffsetForChannel(channelId: Long) {
        preferencesRepository.clearAudioVideoOffsetForChannel(channelId)
    }

    internal suspend fun recordLiveVariantObservation(
        rawChannelId: Long,
        observedQuality: LiveChannelObservedQuality,
    ) {
        preferencesRepository.recordLiveVariantObservation(rawChannelId, observedQuality)
    }

    internal suspend fun recordVodVariantObservation(
        rawItemId: Long,
        observation: VodVariantObservation,
    ) {
        preferencesRepository.recordVodVariantObservation(rawItemId, observation)
    }

    internal suspend fun setAspectRatioForChannel(channelId: Long, ratio: String) {
        preferencesRepository.setAspectRatioForChannel(channelId, ratio)
    }

    internal suspend fun setAudioVideoOffsetForChannel(channelId: Long, offsetMs: Int) {
        preferencesRepository.setAudioVideoOffsetForChannel(channelId, offsetMs)
    }

    internal suspend fun setPlayerAudioVideoOffsetMs(offsetMs: Int) {
        preferencesRepository.setPlayerAudioVideoOffsetMs(offsetMs)
    }

    internal suspend fun setPlayerMuted(muted: Boolean) {
        preferencesRepository.setPlayerMuted(muted)
    }

    internal suspend fun setPlayerPlaybackSpeed(speed: Float) {
        preferencesRepository.setPlayerPlaybackSpeed(speed)
    }

    internal suspend fun setPreferredLiveVariant(
        providerId: Long,
        logicalGroupId: String,
        rawChannelId: Long,
    ) {
        preferencesRepository.setPreferredLiveVariant(providerId, logicalGroupId, rawChannelId)
    }
}
