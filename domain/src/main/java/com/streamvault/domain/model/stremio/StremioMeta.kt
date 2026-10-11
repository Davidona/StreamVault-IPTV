package com.streamvault.domain.model.stremio

data class StremioVideo(
    val id: String,
    val title: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val released: String? = null,
    val thumbnail: String? = null,
    val overview: String? = null
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
    val videos: List<StremioVideo> = emptyList()
) {
    fun seasons(): List<Int> = videos.mapNotNull { it.season }.distinct().sorted()

    fun episodes(season: Int): List<StremioVideo> =
        videos.filter { it.season == season }.sortedBy { it.episode ?: Int.MAX_VALUE }

    fun episodeVideoId(season: Int, episode: Int): String? =
        videos.firstOrNull { it.season == season && it.episode == episode }?.id
}
