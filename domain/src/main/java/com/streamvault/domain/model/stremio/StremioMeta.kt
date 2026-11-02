package com.streamvault.domain.model.stremio

data class StremioVideo(
    val id: String,
    val title: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val released: String? = null,
    val thumbnail: String? = null,
    val overview: String? = null,
    /** null requests stream addons; an explicit list is authoritative, including an empty list. */
    val streams: List<StremioStream>? = null,
    val startTime: String? = null,
    val endTime: String? = null
)

data class StremioMetaDetail(
    val id: String,
    val type: StremioMediaType,
    val name: String,
    val poster: String? = null,
    val background: String? = null,
    val logo: String? = null,
    val description: String? = null,
    val releaseInfo: String? = null,
    val imdbRating: String? = null,
    val genres: List<String> = emptyList(),
    val runtime: String? = null,
    val cast: List<String> = emptyList(),
    val director: List<String> = emptyList(),
    val videos: List<StremioVideo> = emptyList(),
    val defaultVideoId: String? = null,
    val isLive: Boolean = false,
    val hasScheduledVideos: Boolean = false,
    val posterShape: String = "poster",
    val links: List<StremioMetaLink> = emptyList()
) {
    fun seasons(): List<Int> = videos.mapNotNull { it.season }.distinct().sorted()

    fun episodes(season: Int): List<StremioVideo> =
        videos.filter { it.season == season }.sortedBy { it.episode ?: Int.MAX_VALUE }

    fun episodeVideoId(season: Int, episode: Int): String? =
        videos.firstOrNull { it.season == season && it.episode == episode }?.id
}

fun StremioMetaDetail.toPreview() = StremioMetaPreview(
    id = id, type = type, name = name, poster = poster, background = background,
    logo = logo, description = description, releaseInfo = releaseInfo,
    imdbRating = imdbRating, genres = genres, runtime = runtime, posterShape = posterShape
)

data class StremioMetaLink(val name: String, val category: String, val url: String)

fun StremioMetaDetail.nextVideoAfter(exactVideoId: String, now: java.time.Instant = java.time.Instant.now()): StremioVideo? {
    if (type != StremioMediaType.SERIES || isLive || hasScheduledVideos) return null
    val ordered = videos.distinctBy { it.id }.sortedWith(compareBy(
        { it.season ?: Int.MAX_VALUE }, { it.episode ?: Int.MAX_VALUE }
    ))
    val index = ordered.indexOfFirst { it.id == exactVideoId }
    if (index < 0) return null
    val next = ordered.getOrNull(index + 1) ?: return null
    return next.takeIf { it.releaseInstant()?.let { released -> released <= now } ?: it.released.isNullOrBlank() }
}
