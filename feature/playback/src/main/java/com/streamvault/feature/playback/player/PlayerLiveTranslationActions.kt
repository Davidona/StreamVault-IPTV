package com.streamvault.feature.playback.player

import androidx.lifecycle.viewModelScope
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.ProviderType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Recomputes whether real-time translation can be offered for the current stream. The Settings >
 * Playback toggle only makes the feature *available*; it no longer starts translation on its own.
 * Translation only runs once the user picks "Real-time translation" from the subtitle ("Subs")
 * menu. If the feature stops being available (preference turned off, or the channel is not a
 * supported live stream) any running session is stopped.
 */
internal suspend fun PlayerViewModel.refreshLiveTranslationAvailability() {
    val enabled = playerPreferencesCoordinator.playerLiveTranslationEnabled.first()
    val provider = if (currentProviderId > 0L) playerProviderCoordinator.getProvider(currentProviderId) else null
    val available = shouldEnableLiveTranslationSession(enabled, currentContentType, provider?.type)
    _liveTranslationAvailable.value = available
    if (!available && liveTranslationActive.value) {
        stopLiveTranslationSession()
    }
}

/** Invoked when the user selects "Real-time translation" from the subtitle menu. */
internal fun PlayerViewModel.activateLiveTranslation() {
    viewModelScope.launch {
        _liveTranslationActive.value = true
        evaluateLiveTranslationSession()
    }
}

/** Invoked when the user picks a regular subtitle track or "Off" from the subtitle menu. */
internal fun PlayerViewModel.deactivateLiveTranslation() {
    if (!liveTranslationActive.value && !playerTranslationCoordinator.isActive) return
    stopLiveTranslationSession()
}

internal suspend fun PlayerViewModel.evaluateLiveTranslationSession() {
    if (!liveTranslationActive.value) {
        stopLiveTranslationSession(clearActiveState = false)
        return
    }
    if (currentProviderId <= 0L) {
        stopLiveTranslationSession()
        return
    }
    val enabled = playerPreferencesCoordinator.playerLiveTranslationEnabled.first()
    val provider = playerProviderCoordinator.getProvider(currentProviderId)
    if (!shouldEnableLiveTranslationSession(enabled, currentContentType, provider?.type)) {
        stopLiveTranslationSession()
        return
    }
    if (currentResolvedStreamInfo == null) {
        stopLiveTranslationSession()
        return
    }
    val endpoint = playerPreferencesCoordinator.playerLiveTranslationEndpoint.first()
    val sessionScope = playbackSessionScope() ?: return
    playerTranslationCoordinator.start(
        scope = sessionScope,
        playerEngine = playerEngine,
        endpoint = endpoint,
        logicalUrl = currentStreamUrl,
        providerId = currentProviderId,
        contentId = currentContentId,
        onSourceLanguageDetected = { language ->
            _liveTranslationDetectedLanguage.value = language
        },
        onError = { message ->
            showPlayerNotice(message = message, recoveryType = PlayerRecoveryType.NETWORK)
        }
    )
}

internal fun PlayerViewModel.stopLiveTranslationSession(clearActiveState: Boolean = true) {
    playerTranslationCoordinator.stop()
    playerEngine.clearInjectedSubtitleCues()
    _liveTranslationDetectedLanguage.value = null
    if (clearActiveState) {
        _liveTranslationActive.value = false
    }
}

internal fun shouldEnableLiveTranslationSession(
    enabledPreference: Boolean,
    contentType: ContentType,
    providerType: ProviderType?
): Boolean {
    return enabledPreference &&
        contentType == ContentType.LIVE &&
        (providerType == ProviderType.XTREAM_CODES || providerType == ProviderType.M3U)
}
