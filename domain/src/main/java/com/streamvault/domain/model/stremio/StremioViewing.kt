package com.streamvault.domain.model.stremio

import java.time.Instant

fun StremioPlaybackHistory.viewingGroupKey(): String =
    if (parentSeriesId == null) contentKey else "${if (contentKey.startsWith("stremio:global:")) "global" else catalogOriginKey}:${mediaType}:${parentSeriesId}"

fun latestStremioItems(history: List<StremioPlaybackHistory>): List<StremioPlaybackHistory> =
    history.sortedByDescending { it.lastWatchedAt }.distinctBy { it.viewingGroupKey() }

fun StremioVideo.releaseInstant(): Instant? = released?.let { value ->
    runCatching { Instant.parse(value) }.getOrNull()
        ?: runCatching { java.time.OffsetDateTime.parse(value).toInstant() }.getOrNull()
        ?: runCatching { java.time.LocalDate.parse(value).atStartOfDay(java.time.ZoneOffset.UTC).toInstant() }.getOrNull()
}

/** Never guesses episode IDs or advances into an unreleased/unknown episode. */
fun nextUnwatchedStremioVideo(meta: StremioMetaDetail, currentId: String, watchedIds: Set<String>, now: Instant): StremioVideo? {
    if (meta.type != StremioMediaType.SERIES || meta.isLive || meta.hasScheduledVideos) return null
    val ordered = meta.videos.distinctBy { it.id }.sortedWith(compareBy({ it.season ?: Int.MAX_VALUE }, { it.episode ?: Int.MAX_VALUE }))
    val index = ordered.indexOfFirst { it.id == currentId }
    if (index < 0) return null
    return ordered.drop(index + 1).firstOrNull { it.id !in watchedIds }?.takeIf {
        it.releaseInstant()?.let { release -> release <= now } ?: it.released.isNullOrBlank()
    }
}
