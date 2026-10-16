package com.streamvault.domain.model.stremio

/** Progress for one Stremio movie or exact episode request ID. Never contains a stream URL. */
data class StremioPlaybackHistory(
    val contentKey: String,
    val mediaType: String,
    val exactVideoId: String,
    val parentSeriesId: String? = null,
    val title: String,
    val posterUrl: String? = null,
    val resumePositionMs: Long = 0L,
    val totalDurationMs: Long = 0L,
    val watched: Boolean = false,
    val lastWatchedAt: Long = System.currentTimeMillis(),
    val catalogOriginKey: String,
    val streamOriginKeyHint: String? = null
)

/**
 * Creates the canonical history key. Only validated IMDb movie IDs and exact episode IDs
 * belonging to an IMDb parent are global; all other IDs remain scoped to the catalog addon.
 */
fun stremioPlaybackContentKey(
    mediaType: String,
    exactVideoId: String,
    parentSeriesId: String?,
    catalogOriginKey: String
): String? {
    if (exactVideoId.isBlank() || !catalogOriginKey.matches(Regex("[0-9a-fA-F]{64}"))) return null

    val typeKey = when (mediaType.lowercase()) {
        "movie" -> "movie"
        "series" -> "series-episode"
        else -> return null
    }
    val imdbId = Regex("tt[0-9]+")
    val isGlobal = when (typeKey) {
        "movie" -> imdbId.matches(exactVideoId)
        "series-episode" -> parentSeriesId?.let { parent ->
            imdbId.matches(parent) && Regex("${Regex.escape(parent)}:[0-9]+:[0-9]+").matches(exactVideoId)
        } == true
        else -> false
    }
    return if (isGlobal) {
        "stremio:global:$typeKey:$exactVideoId"
    } else {
        "stremio:addon:${catalogOriginKey.lowercase()}:$typeKey:$exactVideoId"
    }
}
