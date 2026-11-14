package com.streamvault.data.repository

import com.streamvault.data.remote.subtitle.OpenSubtitlesApi
import com.streamvault.domain.model.ContentType
import java.util.Locale

/** Search by ID first; retry by a cleaned provider title only when no usable matches exist. */
internal fun subtitleSearchQueries(
    contentType: ContentType,
    title: String,
    year: Int?,
    tmdbId: Long?,
    parentTmdbId: Long?,
    seasonNumber: Int?,
    episodeNumber: Int?,
    language: String?,
): List<OpenSubtitlesApi.SearchQuery> {
    val languageCode = language?.trim()?.lowercase(Locale.ROOT)
        ?.takeIf { it.isNotBlank() && it != "auto" && it != "all" }
    val episode = contentType == ContentType.SERIES_EPISODE
    val base = OpenSubtitlesApi.SearchQuery(
        languages = languageCode,
        seasonNumber = seasonNumber.takeIf { episode },
        episodeNumber = episodeNumber.takeIf { episode },
        type = when (contentType) {
            ContentType.MOVIE -> "movie"
            ContentType.SERIES_EPISODE -> "episode"
            else -> null
        },
        orderBy = "download_count",
    )
    val id = (if (episode) parentTmdbId else tmdbId)?.takeIf { it > 0 }
    val cleaned = cleanSubtitleSearchTitle(title)
    return buildList {
        if (id != null) add(if (episode) base.copy(parentTmdbId = id) else base.copy(tmdbId = id))
        if (cleaned.isNotBlank()) {
            val searchYear = year?.takeIf { contentType == ContentType.MOVIE && it > 0 }
            add(base.copy(query = cleaned, year = searchYear))
            if (searchYear != null) add(base.copy(query = cleaned))
        }
    }
}

private fun cleanSubtitleSearchTitle(title: String): String {
    val cleaned = title.trim()
        .replace(Regex("^\\d+\\s+-\\s+"), "")
        .replace(Regex("(?i)\\s*\\[(?:multi[ ._-]*sub(?:titles?)?|dual[ ._-]*audio|subbed|dubbed|4k|uhd|fhd|hd|sd)\\]"), "")
        .replace(Regex("\\s*(?:-\\s*(?:19|20)\\d{2}|\\((?:19|20)\\d{2}\\))\\s*$"), "")
        .trim(' ', '-', '_', '.')
    return cleaned.ifBlank { title.trim() }
}
