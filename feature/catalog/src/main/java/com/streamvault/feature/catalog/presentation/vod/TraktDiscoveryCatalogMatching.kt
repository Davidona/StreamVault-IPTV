package com.streamvault.feature.catalog.presentation.vod

import com.streamvault.domain.trakt.TraktDiscoveryItem
import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.CancellationException

/** Resolves Trakt-ranked titles to playable entries when provider records have no TMDB id. */
internal suspend fun <T> orderedTraktDiscoveryItems(
    entries: List<TraktDiscoveryItem>,
    tmdbIds: List<Long>,
    loadByTmdbIds: suspend (List<Long>) -> List<T>,
    searchByTitle: suspend (String) -> List<T>,
    tmdbIdOf: (T) -> Long?,
    titleOf: (T) -> String,
    yearOf: (T) -> Int?,
    idOf: (T) -> Long,
): List<T> {
    val rankedEntries = entries.ifEmpty {
        tmdbIds.map { TraktDiscoveryItem(tmdbId = it, title = "") }
    }
    val catalogItems = loadByTmdbIds(rankedEntries.mapNotNull(TraktDiscoveryItem::tmdbId))
    val catalogByTmdbId = catalogItems.groupBy(tmdbIdOf)
    val resolvedIds = HashSet<Long>()

    return buildList {
        for (entry in rankedEntries) {
            val exactMatch = entry.tmdbId?.let(catalogByTmdbId::get)?.firstOrNull()
            val match = exactMatch ?: entry.title.takeIf(String::isNotBlank)?.let { title ->
                val candidates = try {
                    searchByTitle(title)
                } catch (failure: CancellationException) {
                    throw failure
                } catch (_: Exception) {
                    emptyList()
                }
                findTraktTitleMatch(entry, candidates, titleOf, yearOf)
            }
            if (match != null && resolvedIds.add(idOf(match))) add(match)
        }
    }
}

internal fun traktDiscoveryCatalogYear(year: String?, releaseDate: String?, title: String): Int? =
    year?.toIntOrNull()
        ?: releaseDate?.take(4)?.toIntOrNull()
        ?: TRAILING_YEAR_VALUE_REGEX.find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()

private fun <T> findTraktTitleMatch(
    entry: TraktDiscoveryItem,
    candidates: List<T>,
    titleOf: (T) -> String,
    yearOf: (T) -> Int?,
): T? {
    val targetTitle = normalizeTraktTitle(entry.title)
    if (targetTitle.isBlank()) return null
    val titleMatches = candidates.filter { normalizeTraktTitle(titleOf(it)) == targetTitle }
    if (entry.year == null) return titleMatches.firstOrNull()

    val matchingYear = titleMatches.firstOrNull { yearOf(it) == entry.year }
    if (matchingYear != null) return matchingYear

    // Some providers omit the year. Accept a unique title match only when its year is unknown.
    return titleMatches.filter { yearOf(it) == null }.singleOrNull()
}

private fun normalizeTraktTitle(value: String): String {
    var title = value.trim()
    var prefixesRemoved = 0
    while (prefixesRemoved < MAX_PROVIDER_PREFIXES) {
        val prefix = PROVIDER_PREFIX_REGEX.find(title) ?: break
        val label = prefix.groupValues[1].lowercase(Locale.ROOT)
        if (label !in PROVIDER_PREFIXES && !label.matches(SHORT_LANGUAGE_CODE_REGEX)) break
        title = prefix.groupValues[2]
        prefixesRemoved++
    }
    title = TRAILING_YEAR_REGEX.replace(title, " ")
    title = QUALITY_TAG_REGEX.replace(title, " ")
    val asciiFolded = Normalizer.normalize(title, Normalizer.Form.NFD)
        .replace(COMBINING_MARKS_REGEX, "")
        .lowercase(Locale.ROOT)
    return NON_ALPHANUMERIC_REGEX.replace(asciiFolded, "")
}

private const val MAX_PROVIDER_PREFIXES = 3
private val PROVIDER_PREFIX_REGEX = Regex("""^([\p{L}]{2,12})\s+-\s+(.+)$""")
private val SHORT_LANGUAGE_CODE_REGEX = Regex("""[a-z]{2,3}""")
private val PROVIDER_PREFIXES = setOf(
    "arabic", "bengali", "chinese", "english", "french", "german", "hindi", "indonesian",
    "italian", "japanese", "kannada", "korean", "malayalam", "marathi", "multi", "portuguese",
    "russian", "spanish", "tamil", "telugu", "thai", "turkish", "urdu",
)
private val TRAILING_YEAR_REGEX = Regex("""\s*(?:[-–—|·]\s*|\()((?:19|20)\d{2})\)?\s*$""")
private val TRAILING_YEAR_VALUE_REGEX = Regex("""(?:[-–—|·(]\s*)((?:19|20)\d{2})\)?\s*$""")
private val QUALITY_TAG_REGEX = Regex(
    """\b(?:8k|4320p|uhd|4k|2160p|2k|1440p|1080p|720p|576p|540p|480p|360p|hdr10?|hevc|h265|x265|multi[- ]audio|dual[- ]audio)\b""",
    RegexOption.IGNORE_CASE,
)
private val COMBINING_MARKS_REGEX = Regex("\\p{Mn}+")
private val NON_ALPHANUMERIC_REGEX = Regex("[^\\p{L}\\p{N}]+")
