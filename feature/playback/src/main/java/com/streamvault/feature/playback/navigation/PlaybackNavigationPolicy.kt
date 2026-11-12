package com.streamvault.feature.playback.navigation

import com.streamvault.core.navigation.AppDestination
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
    "file",
    "magnet"
)

/** Accept app-supported media schemes while rejecting obviously unsafe values. */
private fun isStreamUrlSafe(url: String?): Boolean {
    if (url.isNullOrBlank()) return false
    val scheme = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")
        .find(url)
        ?.groupValues
        ?.getOrNull(1)
        ?.lowercase()
        ?: return false
    return scheme in supportedStreamSchemes
}

internal fun safePlayerNavigationRequest(request: PlayerNavigationRequest?): PlayerNavigationRequest? =
    request?.takeIf { isStreamUrlSafe(it.streamUrl) }

/** Keep an existing detail route, or reconstruct it for direct catalog/history playback. */
internal fun playbackCompletionDestination(request: PlayerNavigationRequest): AppDestination? {
    val origin = request.returnDestination
    if (origin is AppDestination.MovieDetail || origin is AppDestination.SeriesDetail) return origin
    if (request.stremioPlaybackReference != null) return origin
    return when (request.contentType.uppercase()) {
        "MOVIE", "VOD" -> request.internalId.takeIf { it > 0L }
            ?.let { AppDestination.MovieDetail(it, origin) } ?: origin
        "SERIES_EPISODE" -> request.seriesId?.takeIf { it > 0L }
            ?.let { AppDestination.SeriesDetail(it, origin) } ?: origin
        else -> origin
    }
}
