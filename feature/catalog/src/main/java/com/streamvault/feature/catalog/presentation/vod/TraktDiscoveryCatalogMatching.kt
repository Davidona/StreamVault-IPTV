package com.streamvault.feature.catalog.presentation.vod

/** Resolves Trakt-ranked TMDB IDs to matching playable entries in the active provider catalog. */
internal suspend fun <T> orderedTraktDiscoveryItems(
    tmdbIds: List<Long>,
    loadByTmdbIds: suspend (List<Long>) -> List<T>,
    tmdbIdOf: (T) -> Long?,
    idOf: (T) -> Long,
): List<T> {
    val rankedIds = tmdbIds.asSequence().filter { it > 0L }.distinct().toList()
    if (rankedIds.isEmpty()) return emptyList()

    val catalogByTmdbId = loadByTmdbIds(rankedIds).groupBy(tmdbIdOf)
    val resolvedIds = HashSet<Long>()

    return buildList {
        for (tmdbId in rankedIds) {
            val match = catalogByTmdbId[tmdbId]?.firstOrNull()
            if (match != null && resolvedIds.add(idOf(match))) add(match)
        }
    }
}
