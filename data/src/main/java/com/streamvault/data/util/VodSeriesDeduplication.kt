package com.streamvault.data.util

import com.streamvault.domain.model.Series
import com.streamvault.domain.model.VodDuplicateConfidence
import com.streamvault.domain.model.VodDuplicateHandlingMode
import com.streamvault.domain.model.VodSeriesVariant
import com.streamvault.domain.model.VodVariantObservation
import com.streamvault.domain.model.VodVariantPreferenceMode
import com.streamvault.domain.util.BoundedExpiringCache
import java.text.Normalizer
import java.time.Year
import java.util.Locale

private val SERIES_QUALITY_TOKENS: List<Pair<String, Int>> = listOf(
    "8k" to 4320,
    "4320p" to 4320,
    "uhd" to 2160,
    "ultra hd" to 2160,
    "ultrahd" to 2160,
    "4k" to 2160,
    "2160p" to 2160,
    "2k" to 1440,
    "qhd" to 1440,
    "1440p" to 1440,
    "full hd" to 1080,
    "fullhd" to 1080,
    "fhd" to 1080,
    "1080p" to 1080,
    "1080i" to 1080,
    "hd" to 720,
    "720p" to 720,
    "576p" to 576,
    "540p" to 540,
    "hq" to 576,
    "sd" to 576,
    "480p" to 480
)

private val SERIES_QUALITY_BONUS_TOKENS: List<Pair<String, Int>> = listOf(
    "hdr10" to 80,
    "hdr" to 60,
    "dolby vision" to 120,
    "remux" to 80,
    "blu ray" to 60,
    "bluray" to 60,
    "web dl" to 30,
    "web-dl" to 30,
    "webrip" to 24,
    "hevc" to 18,
    "h265" to 18,
    "x265" to 18,
    "av1" to 22
)

private val SERIES_YEAR_REGEX = Regex("""(19|20)\d{2}""")
private val SERIES_YEAR_SUFFIX_REGEX = Regex("""\s*\((19|20)\d{2}\)\s*$""")
private val SERIES_QUALITY_CLEANUP_REGEX = Regex(
    """\b(8k|4320p|uhd|ultra\s*hd|4k|2160p|2k|qhd|1440p|full\s*hd|fullhd|fhd|1080p|1080i|hd|720p|576p|540p|hq|sd|480p|hdr10|hdr|dolby\s*vision|remux|blu\s*ray|bluray|web\s*dl|web-dl|webrip|hevc|h265|x265|av1)\b""",
    RegexOption.IGNORE_CASE
)
private val SERIES_NON_ALPHANUMERIC_REGEX = Regex("""[^a-z0-9]+""")
private val SERIES_NON_ASCII_REGEX = Regex("[^\\u0000-\\u007F]")
private val SERIES_COMBINING_MARKS_REGEX = Regex("\\p{Mn}+")

private data class SeriesDisplayYearCacheKey(
    val name: String,
    val releaseDate: String?
)

private val seriesDisplayYearCache = BoundedExpiringCache<SeriesDisplayYearCacheKey, Int>(
    maxEntries = 512,
    ttlMillis = 6L * 60L * 60L * 1000L
)
private val normalizedSeriesTitleCache = BoundedExpiringCache<String, String>(
    maxEntries = 1_024,
    ttlMillis = 6L * 60L * 60L * 1000L
)

data class SeriesPresentationSettings(
    val duplicateHandlingMode: VodDuplicateHandlingMode,
    val preferenceMode: VodVariantPreferenceMode,
    val preferredVariants: Map<String, Long> = emptyMap(),
    val observations: Map<Long, VodVariantObservation> = emptyMap()
)

fun buildPresentedSeries(
    series: List<Series>,
    settings: SeriesPresentationSettings
): List<Series> {
    if (settings.duplicateHandlingMode == VodDuplicateHandlingMode.SHOW_ALL || series.size < 2) {
        return series
    }

    return series
        .groupBy(::seriesLogicalGroupId)
        .values
        .flatMap { group ->
            val confidence = seriesDuplicateConfidence(group)
            if (!shouldGroupSeries(confidence, settings.duplicateHandlingMode) || group.size < 2) {
                group
            } else {
                listOf(buildGroupedSeries(group, confidence, settings))
            }
        }
}

fun seriesDuplicateConfidence(series: List<Series>): VodDuplicateConfidence {
    if (series.size < 2) return VodDuplicateConfidence.NONE
    val normalizedTitles = series.mapTo(mutableSetOf()) { normalizedSeriesTitle(it.name) }
    if (normalizedTitles.size != 1 || normalizedTitles.first().isBlank()) {
        return VodDuplicateConfidence.NONE
    }

    val tmdbIds = series.mapNotNull { it.tmdbId?.takeIf { id -> id > 0L } }.toSet()
    if (tmdbIds.size == 1 && tmdbIds.isNotEmpty()) {
        return VodDuplicateConfidence.EXACT
    }

    val years = series.mapNotNull(::seriesDisplayYear).toSet()
    if (years.size == 1 && years.isNotEmpty()) {
        return VodDuplicateConfidence.STRONG
    }

    val runtimes = series.mapNotNull { runtimeMinutes(it.episodeRunTime) }
    if (runtimes.size >= 2 && runtimes.maxOrNull().orZero() - runtimes.minOrNull().orZero() <= 5) {
        return VodDuplicateConfidence.LIKELY
    }

    return VodDuplicateConfidence.WEAK
}

fun selectPreferredSeriesVariant(
    series: List<Series>,
    preferenceMode: VodVariantPreferenceMode,
    preferredSeriesId: Long? = null,
    observations: Map<Long, VodVariantObservation> = emptyMap()
): Series? {
    if (series.isEmpty()) return null
    if (preferenceMode == VodVariantPreferenceMode.MANUAL_LAST_CHOICE) {
        preferredSeriesId?.let { preferredId ->
            series.firstOrNull { it.id == preferredId }?.let { return it }
        }
    }
    return series.maxWith(seriesVariantComparator(preferenceMode, observations))
}

fun seriesLogicalGroupId(series: Series): String {
    val tmdbId = series.tmdbId?.takeIf { it > 0L }
    if (tmdbId != null) return "series:${series.providerId}:tmdb:$tmdbId"
    return "series:${series.providerId}:${normalizedSeriesTitle(series.name)}:${seriesDisplayYear(series) ?: 0}"
}

private fun buildGroupedSeries(
    group: List<Series>,
    confidence: VodDuplicateConfidence,
    settings: SeriesPresentationSettings
): Series {
    val groupId = seriesLogicalGroupId(group.first())
    val preferredSeriesId = settings.preferredVariants["${group.first().providerId}|$groupId"]
    val selected = selectPreferredSeriesVariant(
        series = group,
        preferenceMode = settings.preferenceMode,
        preferredSeriesId = preferredSeriesId,
        observations = settings.observations
    ) ?: group.first()
    val orderedVariants = group
        .sortedWith(seriesVariantComparator(settings.preferenceMode, settings.observations).reversed())
        .let { sorted -> listOf(selected) + sorted.filterNot { it.id == selected.id } }
    val variants = orderedVariants.map(::toVodSeriesVariant)

    return selected.copy(
        isFavorite = group.any(Series::isFavorite),
        logicalGroupId = groupId,
        selectedVariantId = selected.id,
        variants = variants,
        duplicateConfidence = confidence,
        variantLabel = toVodSeriesVariant(selected).label
    )
}

private fun shouldGroupSeries(
    confidence: VodDuplicateConfidence,
    mode: VodDuplicateHandlingMode
): Boolean = when (mode) {
    VodDuplicateHandlingMode.SHOW_ALL -> false
    VodDuplicateHandlingMode.SMART -> confidence == VodDuplicateConfidence.EXACT || confidence == VodDuplicateConfidence.STRONG
    VodDuplicateHandlingMode.GROUPED -> confidence == VodDuplicateConfidence.EXACT ||
        confidence == VodDuplicateConfidence.STRONG ||
        confidence == VodDuplicateConfidence.LIKELY
}

private fun seriesVariantComparator(
    mode: VodVariantPreferenceMode,
    observations: Map<Long, VodVariantObservation>
): Comparator<Series> = when (mode) {
    VodVariantPreferenceMode.FORCE_LATEST -> compareBy<Series> { seriesRecencyScore(it) }
        .thenBy { seriesQualityScore(it) }
        .thenBy { seriesReliabilityScore(it, observations) }
        .thenBy { seriesMetadataScore(it) }
        .thenBy { it.rating }
        .thenBy { -it.id }
    VodVariantPreferenceMode.BEST_QUALITY -> compareBy<Series> { seriesQualityScore(it) }
        .thenBy { seriesReliabilityScore(it, observations) }
        .thenBy { seriesRecencyScore(it) }
        .thenBy { seriesMetadataScore(it) }
        .thenBy { it.rating }
        .thenBy { -it.id }
    VodVariantPreferenceMode.LATEST_BEST_QUALITY -> compareBy<Series> {
        seriesRecencyScore(it) + (seriesQualityScore(it).toLong() * 10L)
    }.thenBy { seriesReliabilityScore(it, observations) }
        .thenBy { seriesMetadataScore(it) }
        .thenBy { it.rating }
        .thenBy { -it.id }
    VodVariantPreferenceMode.MOST_RELIABLE -> compareBy<Series> { seriesReliabilityScore(it, observations) }
        .thenBy { seriesQualityScore(it) }
        .thenBy { seriesRecencyScore(it) }
        .thenBy { seriesMetadataScore(it) }
        .thenBy { it.rating }
        .thenBy { -it.id }
    VodVariantPreferenceMode.BALANCED,
    VodVariantPreferenceMode.MANUAL_LAST_CHOICE -> compareBy<Series> {
        (seriesQualityScore(it) * 3) +
            (seriesRecencyBucket(it) * 120) +
            seriesReliabilityScore(it, observations) +
            (seriesMetadataScore(it) * 8) +
            (it.rating * 10).toInt()
    }.thenBy { seriesRecencyScore(it) }
        .thenBy { -it.id }
}

private fun toVodSeriesVariant(series: Series): VodSeriesVariant = VodSeriesVariant(
    rawSeriesId = series.id,
    name = series.name,
    seriesId = series.seriesId,
    providerSeriesId = series.providerSeriesId,
    releaseDate = series.releaseDate,
    tmdbId = series.tmdbId,
    episodeRunTime = series.episodeRunTime,
    rating = series.rating,
    lastModified = series.lastModified,
    qualityScore = seriesQualityScore(series),
    recencyScore = seriesRecencyScore(series),
    reliabilityScore = 0,
    label = seriesVariantLabel(series)
)

private fun seriesQualityScore(series: Series): Int = seriesQualityScore(series.name)

private fun seriesRecencyScore(series: Series): Long =
    series.releaseDate?.filter(Char::isDigit)?.take(8)?.toLongOrNull()
        ?: seriesDisplayYear(series)?.toLong()
        ?: series.lastModified.takeIf { it > 0L }
        ?: 0L

private fun seriesReliabilityScore(series: Series, observations: Map<Long, VodVariantObservation>): Int {
    val observation = observations[series.id] ?: return 0
    val recentSuccessBonus = if (observation.lastSuccessfulAt >= observation.lastFailedAt) 40 else 0
    val recentFailurePenalty = if (observation.lastFailedAt > observation.lastSuccessfulAt) 120 else 0
    return (observation.successCount * 80) - (observation.failureCount * 120) + recentSuccessBonus - recentFailurePenalty
}

private fun seriesMetadataScore(series: Series): Int = listOfNotNull(
    series.posterUrl,
    series.backdropUrl,
    series.plot,
    series.cast,
    series.director,
    series.genre,
    series.releaseDate,
    series.episodeRunTime,
    series.tmdbId?.toString(),
    series.providerSeriesId
).count { it.isNotBlank() }

private fun seriesRecencyBucket(series: Series): Int {
    val year = seriesDisplayYear(series) ?: return 0
    val currentYear = Year.now().value
    return when {
        year >= currentYear -> 5
        year == currentYear - 1 -> 4
        year >= currentYear - 3 -> 3
        year >= currentYear - 7 -> 2
        else -> 1
    }
}

private fun normalizedSeriesTitle(value: String): String {
    normalizedSeriesTitleCache.get(value)?.let { return it }
    val withoutProviderPrefix = value.substringAfter(" - ", value)
    val withoutYearSuffix = SERIES_YEAR_SUFFIX_REGEX.replace(withoutProviderPrefix, "")
    val withoutQuality = SERIES_QUALITY_CLEANUP_REGEX.replace(withoutYearSuffix, " ")
    val normalized = if (SERIES_NON_ASCII_REGEX.containsMatchIn(withoutQuality)) {
        Normalizer.normalize(withoutQuality, Normalizer.Form.NFD)
            .replace(SERIES_COMBINING_MARKS_REGEX, "")
    } else {
        withoutQuality
    }
    val result = SERIES_NON_ALPHANUMERIC_REGEX.replace(normalized.lowercase(Locale.ROOT), "").trim()
    normalizedSeriesTitleCache.put(value, result)
    return result
}

private fun seriesDisplayYear(series: Series): Int? {
    val key = SeriesDisplayYearCacheKey(series.name, series.releaseDate)
    seriesDisplayYearCache.get(key)?.let { return it }
    val result = series.releaseDate?.filter(Char::isDigit)?.take(4)?.toIntOrNull()
        ?: SERIES_YEAR_REGEX.find(series.name)?.value?.toIntOrNull()
    if (result != null) seriesDisplayYearCache.put(key, result)
    return result
}

private fun seriesQualityScore(value: String): Int {
    val normalized = normalizeSeriesTokenText(value)
    var score = 0
    SERIES_QUALITY_TOKENS.forEach { (token, tokenScore) ->
        if (containsSeriesToken(normalized, token)) {
            score = maxOf(score, tokenScore)
        }
    }
    SERIES_QUALITY_BONUS_TOKENS.forEach { (token, bonus) ->
        if (containsSeriesToken(normalized, token)) {
            score += bonus
        }
    }
    return score
}

private fun seriesVariantLabel(series: Series): String {
    val normalized = normalizeSeriesTokenText(series.name)
    val parts = mutableListOf<String>()
    SERIES_QUALITY_TOKENS.firstOrNull { (token, _) -> containsSeriesToken(normalized, token) }?.let { (_, score) ->
        parts += when (score) {
            4320 -> "8K"
            2160 -> "4K"
            1440 -> "2K"
            1080 -> "1080p"
            720 -> "720p"
            else -> "SD"
        }
    }
    seriesDisplayYear(series)?.let { parts += it.toString() }
    return parts.distinct().joinToString(" ").ifBlank { "Version ${series.id}" }
}

private fun normalizeSeriesTokenText(value: String): String {
    val normalized = if (SERIES_NON_ASCII_REGEX.containsMatchIn(value)) {
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(SERIES_COMBINING_MARKS_REGEX, "")
    } else {
        value
    }
        .lowercase(Locale.ROOT)
        .replace(Regex("""[^a-z0-9]+"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()
    return " $normalized "
}

private fun containsSeriesToken(normalizedValue: String, token: String): Boolean {
    val normalizedToken = token.lowercase(Locale.ROOT)
        .replace(Regex("""[^a-z0-9]+"""), " ")
        .trim()
    return normalizedValue.contains(" $normalizedToken ")
}

private fun runtimeMinutes(value: String?): Int? = value
    ?.trim()
    ?.takeIf { it.isNotBlank() }
    ?.let { raw ->
        raw.toIntOrNull() ?: Regex("""\d+""").find(raw)?.value?.toIntOrNull()
    }

private fun Int?.orZero(): Int = this ?: 0
