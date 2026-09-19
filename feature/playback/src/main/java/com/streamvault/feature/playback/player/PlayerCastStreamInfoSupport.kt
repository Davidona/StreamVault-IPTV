package com.streamvault.feature.playback.player

import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.model.StreamType

internal fun selectPreferredVodCastStreamInfo(
    activeStreamInfo: StreamInfo?,
    activePlaybackUrl: String,
    fallbackStreamInfo: StreamInfo?
): StreamInfo? {
    val active = activeStreamInfo?.takeIf { it.url.isNotBlank() }
    val fallback = fallbackStreamInfo?.takeIf { it.url.isNotBlank() }
    if (active == null) return fallback
    if (fallback == null) return active

    val activeScore = active.castContextScore()
    val fallbackScore = fallback.castContextScore()
    return when {
        activeScore > fallbackScore -> active
        activeScore == fallbackScore && active.url == activePlaybackUrl.trim() -> active
        else -> fallback
    }
}

private fun StreamInfo.castContextScore(): Int {
    var score = 0
    if (headers.any { (name, value) -> name.isNotBlank() && value.isNotBlank() }) score += 4
    if (!userAgent.isNullOrBlank()) score += 2
    if (proxyHost.isNotBlank() || proxyPort != null) score += 2
    if (playbackTransportPolicy != null) score += 2
    if (allowInvalidSsl) score += 2
    if (drmInfo != null) score += 2
    if (streamType != StreamType.UNKNOWN) score += 1
    if (!containerExtension.isNullOrBlank()) score += 1
    if (expirationTime != null) score += 1
    return score
}
