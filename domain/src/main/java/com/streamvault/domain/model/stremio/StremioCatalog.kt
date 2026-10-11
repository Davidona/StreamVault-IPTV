package com.streamvault.domain.model.stremio

data class StremioMetaPreview(
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
    val runtime: String? = null
)

data class StremioCatalogPage(
    val metas: List<StremioMetaPreview>,
    val hasMore: Boolean = false
)

data class StremioCatalogQuery(
    val search: String? = null,
    val genre: String? = null,
    val skip: Int? = null
) {
    fun toExtras(): List<Pair<String, String>> = buildList {
        search?.trim()?.takeIf { it.isNotEmpty() }?.let { add("search" to it) }
        genre?.trim()?.takeIf { it.isNotEmpty() }?.let { add("genre" to it) }
        skip?.takeIf { it > 0 }?.let { add("skip" to it.toString()) }
    }
}
