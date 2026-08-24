package com.streamvault.feature.playback.player

import javax.inject.Inject

/**
 * Owns retry guards and provider preload cooldowns for the player feature.
 *
 * Recovery actions still live beside the ViewModel until their host callbacks
 * are fully separated, but their mutable attempt state no longer belongs to
 * the UI model or leaks across playback sessions.
 */
class PlayerRecoveryCoordinator @Inject constructor() {
    private var sessionId: Long = Long.MIN_VALUE
    private var softwareDecoderRetryUsed = false
    private var avcMovieVariantRetryUsed = false
    private var xtreamAuthRefreshRetryUsed = false
    private val livePreloadCooldownProviderIds = mutableSetOf<Long>()
    private val triedStreamUrls = mutableSetOf<String>()
    private val failedStreamCounts = mutableMapOf<String, Int>()

    internal fun beginSession(newSessionId: Long) {
        if (sessionId == newSessionId) return
        sessionId = newSessionId
        xtreamAuthRefreshRetryUsed = false
        resetPreparationAttempts()
        // A new preparation/recovery transition is not a new root playback identity. Keep
        // attempted and failed URLs until an explicit root playback change clears them.
    }

    internal fun resetPreparationAttempts() {
        softwareDecoderRetryUsed = false
        avcMovieVariantRetryUsed = false
    }

    internal var retriedWithSoftwareDecoder: Boolean
        get() = softwareDecoderRetryUsed
        set(value) {
            softwareDecoderRetryUsed = value
        }

    internal var retriedWithAvcMovieVariant: Boolean
        get() = avcMovieVariantRetryUsed
        set(value) {
            avcMovieVariantRetryUsed = value
        }

    internal fun canRetryXtreamAuthRefresh(): Boolean = !xtreamAuthRefreshRetryUsed

    internal fun markXtreamAuthRefreshRetried() {
        xtreamAuthRefreshRetryUsed = true
    }

    internal fun isLivePreloadCoolingDown(providerId: Long): Boolean =
        providerId in livePreloadCooldownProviderIds

    internal fun markLivePreloadCoolingDown(providerId: Long): Boolean =
        livePreloadCooldownProviderIds.add(providerId)

    internal fun streamAttemptSnapshot(): Set<String> = triedStreamUrls.toSet()

    internal fun failedStreamSnapshot(): Map<String, Int> = failedStreamCounts.toMap()

    internal fun markStreamAttempt(streamUrl: String) {
        if (streamUrl.isNotBlank()) triedStreamUrls.add(streamUrl)
    }

    internal fun clearStreamAttempts() {
        triedStreamUrls.clear()
        failedStreamCounts.clear()
    }

    internal fun markStreamFailure(streamUrl: String) {
        if (streamUrl.isBlank()) return
        failedStreamCounts[streamUrl] = (failedStreamCounts[streamUrl] ?: 0) + 1
    }
}
