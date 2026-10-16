package com.streamvault.feature.playback.player

import com.streamvault.core.navigation.StremioPlaybackReference
import com.streamvault.domain.model.stremio.StremioPlaybackHistory
import com.streamvault.domain.model.stremio.stremioPlaybackContentKey

internal fun buildStremioPlaybackHistorySnapshot(
    reference: StremioPlaybackReference,
    positionMs: Long,
    durationMs: Long,
    title: String,
    posterUrl: String?,
    watched: Boolean = false,
    lastWatchedAt: Long = System.currentTimeMillis()
): StremioPlaybackHistory? {
    if (positionMs < 0L || durationMs <= 0L) return null
    val contentKey = stremioPlaybackContentKey(
        mediaType = reference.mediaType,
        exactVideoId = reference.exactVideoId,
        parentSeriesId = reference.parentSeriesId,
        catalogOriginKey = reference.catalogOriginKey
    ) ?: return null
    return StremioPlaybackHistory(
        contentKey = contentKey,
        mediaType = reference.mediaType,
        exactVideoId = reference.exactVideoId,
        parentSeriesId = reference.parentSeriesId,
        title = title,
        posterUrl = posterUrl,
        resumePositionMs = if (watched) durationMs else positionMs.coerceAtMost(durationMs),
        totalDurationMs = durationMs,
        watched = watched,
        lastWatchedAt = lastWatchedAt,
        catalogOriginKey = reference.catalogOriginKey,
        streamOriginKeyHint = reference.streamOriginKey
    )
}
