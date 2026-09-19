package com.streamvault.feature.playback.player

import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.Episode
import com.streamvault.domain.model.PlaybackHistory

internal fun buildPlaybackHistorySnapshot(
    positionMs: Long,
    durationMs: Long,
    currentContentId: Long,
    currentProviderId: Long,
    currentContentType: ContentType,
    currentTitle: String,
    currentArtworkUrl: String?,
    currentStreamUrl: String,
    currentSeriesId: Long?,
    currentEpisode: Episode?,
    currentSeasonNumber: Int?,
    currentEpisodeNumber: Int?,
    lastWatchedAt: Long = System.currentTimeMillis()
): PlaybackHistory? {
    if (positionMs < 0 || durationMs <= 0 || currentContentId == -1L || currentProviderId == -1L) {
        return null
    }
    return PlaybackHistory(
        contentId = currentContentId,
        contentType = currentContentType,
        providerId = currentProviderId,
        title = currentTitle,
        posterUrl = currentArtworkUrl,
        streamUrl = currentStreamUrl,
        resumePositionMs = positionMs,
        totalDurationMs = durationMs,
        lastWatchedAt = lastWatchedAt,
        seriesId = currentSeriesId,
        seasonNumber = currentEpisode?.seasonNumber ?: currentSeasonNumber,
        episodeNumber = currentEpisode?.episodeNumber ?: currentEpisodeNumber
    )
}