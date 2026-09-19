package com.streamvault.feature.playback.navigation

import com.streamvault.core.navigation.PlayerNavigationRequest

private val supportedStreamSchemes = setOf(
    "http",
    "https",
    "rtsp",
    "rtmp",
    "rtsps",
    "mms",
    "xtream",
    "stalker",
    "content",
    "file"
)

/** Accept app-supported media schemes while rejecting obviously unsafe values. */
private fun isStreamUrlSafe(url: String?): Boolean {
    if (url.isNullOrBlank()) return false
    val scheme = url.substringBefore("://").lowercase()
    return scheme in supportedStreamSchemes
}

internal fun safePlayerNavigationRequest(request: PlayerNavigationRequest?): PlayerNavigationRequest? =
    request?.takeIf { isStreamUrlSafe(it.streamUrl) }
