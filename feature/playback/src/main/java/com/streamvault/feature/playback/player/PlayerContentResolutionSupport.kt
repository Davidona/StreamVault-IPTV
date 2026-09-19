package com.streamvault.feature.playback.player

import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.Episode
import com.streamvault.domain.model.Series
import com.streamvault.domain.model.StreamInfo

internal data class SeriesEpisodeResolution(
    val resolvedEpisode: Episode?,
    val nextEpisode: Episode?,
    val resolvedArtworkUrl: String?,
    val resolvedTitle: String?,
    val resolvedSeasonNumber: Int?,
    val resolvedEpisodeNumber: Int?
)

internal data class PlayerPlaybackStreamResolution(
    val streamInfo: StreamInfo?,
    val credentialFailureMessage: String? = null,
    val resolutionFailureMessage: String? = null
) {
    internal val failureMessage: String?
        get() = sequenceOf(credentialFailureMessage, resolutionFailureMessage)
            .firstOrNull { !it.isNullOrBlank() }
}

internal fun shouldUseStoredLiveStreamInfo(
    logicalUrl: String,
    storedStreamUrl: String
): Boolean {
    val requestedUrl = logicalUrl.trim()
    val storedUrl = storedStreamUrl.trim()
    return requestedUrl.isBlank() || requestedUrl == storedUrl
}

internal fun shouldStartLiveTimeshiftForStreamClass(streamClassLabel: String): Boolean =
    streamClassLabel != "Catch-up" && streamClassLabel != "MPEG-TS fallback"

internal fun buildSeriesEpisodeResolution(
    series: Series,
    episodeId: Long,
    seasonNumber: Int?,
    episodeNumber: Int?,
    currentContentType: ContentType,
    currentArtworkUrl: String?
): SeriesEpisodeResolution {
    val resolvedEpisode = resolveEpisode(series, episodeId, seasonNumber, episodeNumber)
    return SeriesEpisodeResolution(
        resolvedEpisode = resolvedEpisode,
        nextEpisode = resolvedEpisode?.let { findNextEpisode(series, it) },
        resolvedArtworkUrl = if (resolvedEpisode != null && currentContentType == ContentType.SERIES_EPISODE) {
            resolvedEpisode.coverUrl ?: currentArtworkUrl ?: series.posterUrl ?: series.backdropUrl
        } else {
            currentArtworkUrl
        },
        resolvedTitle = if (resolvedEpisode != null && currentContentType == ContentType.SERIES_EPISODE) {
            buildEpisodePlaybackTitle(resolvedEpisode)
        } else {
            null
        },
        resolvedSeasonNumber = resolvedEpisode?.seasonNumber ?: seasonNumber,
        resolvedEpisodeNumber = resolvedEpisode?.episodeNumber ?: episodeNumber
    )
}
// Provider-backed stream resolution lives in PlayerContentResolver.
