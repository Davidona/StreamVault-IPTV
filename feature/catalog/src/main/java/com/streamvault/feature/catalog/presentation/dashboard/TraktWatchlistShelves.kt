package com.streamvault.feature.catalog.presentation.dashboard

import com.streamvault.domain.trakt.TraktWatchlistIdentity
import com.streamvault.domain.trakt.TraktWatchlistType

/** Projects a Trakt list onto playable items in the active provider's catalog, preserving Trakt order. */
internal fun <T> orderedWatchlistItems(
    watchlistItems: Collection<TraktWatchlistIdentity>,
    type: TraktWatchlistType,
    catalogItems: List<T>,
    tmdbIdOf: (T) -> Long?,
    idOf: (T) -> Long,
    limit: Int
): List<T> {
    if (limit <= 0) return emptyList()

    val catalogByTmdbId = LinkedHashMap<Long, T>()
    catalogItems.forEach { item ->
        tmdbIdOf(item)
            ?.takeIf { it > 0L }
            ?.let { tmdbId ->
                if (!catalogByTmdbId.containsKey(tmdbId)) catalogByTmdbId[tmdbId] = item
            }
    }

    val resolvedIds = HashSet<Long>()
    return buildList {
        for (identity in watchlistItems) {
            if (identity.type != type) continue
            val item = catalogByTmdbId[identity.tmdbId] ?: continue
            if (resolvedIds.add(idOf(item))) add(item)
            if (size == limit) break
        }
    }
}

/** Projects a public Trakt discovery list onto provider items, preserving Trakt rank. */
internal fun <T> orderedDiscoveryItems(
    tmdbIds: List<Long>,
    catalogItems: List<T>,
    tmdbIdOf: (T) -> Long?,
    idOf: (T) -> Long,
    limit: Int,
): List<T> {
    if (limit <= 0) return emptyList()
    val catalogByTmdbId = LinkedHashMap<Long, T>()
    catalogItems.forEach { item ->
        tmdbIdOf(item)?.takeIf { it > 0L }?.let { tmdbId ->
            if (!catalogByTmdbId.containsKey(tmdbId)) catalogByTmdbId[tmdbId] = item
        }
    }
    val resolvedIds = HashSet<Long>()
    return buildList {
        for (tmdbId in tmdbIds) {
            val item = catalogByTmdbId[tmdbId] ?: continue
            if (resolvedIds.add(idOf(item))) add(item)
            if (size == limit) break
        }
    }
}
