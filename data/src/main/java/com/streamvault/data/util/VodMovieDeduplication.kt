package com.streamvault.data.util

import com.streamvault.domain.model.Movie
import com.streamvault.domain.model.VodDuplicateConfidence
import com.streamvault.domain.model.VodDuplicateHandlingMode
import com.streamvault.domain.model.VodMovieVariant
import com.streamvault.domain.model.VodVariantObservation
import com.streamvault.domain.model.VodVariantPreferenceMode
import java.text.Normalizer
import java.time.Year
import java.util.Locale
import kotlin.math.abs

private val QUALITY_TOKENS: List<Pair<String, Int>> = listOf(
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
    "480p" to 480,
    "360p" to 360,
    "240p" to 240
)

private val QUALITY_BONUS_TOKENS: List<Pair<String, Int>> = listOf(
    "dolby vision" to 120,
    "hdr10" to 80,
    "hdr" to 60,
    "remux" to 80,
    "blu ray" to 60,
    "bluray" to 60,
    "bdrip" to 40,
    "web dl" to 30,
    "web-dl" to 30,
    "webrip" to 24,
    "hevc" to 18,
    "h265" to 18,
    "x265" to 18,
    "av1" to 22
)

private val EDITION_TOKENS: List<String> = listOf(
    "director's cut",
    "directors cut",
    "extended",
    "theatrical",
    "remastered",
    "uncut"
)

private val YEAR_REGEX = Regex("""(19|20)\d{2}""")
private val YEAR_SUFFIX_REGEX = Regex("""\s*\((19|20)\d{2}\)\s*$""")
private val QUALITY_CLEANUP_REGEX = Regex(
    """\b(8k|4320p|uhd|ultra\s*hd|4k|2160p|2k|qhd|1440p|full\s*hd|fullhd|fhd|1080p|1080i|hd|720p|576p|540p|hq|sd|480p|360p|240p|hdr10|hdr|dolby\s*vision|remux|blu\s*ray|bluray|bdrip|web\s*dl|web-dl|webrip|hevc|h265|x265|av1)\b""",
    RegexOption.IGNORE_CASE
)
private val NON_ALPHANUMERIC_REGEX = Regex("""[^a-z0-9]+""")

private val displayYearCache = java.util.concurrent.ConcurrentHashMap<String, Int?>(256)
private val normalizedTitleCache = java.util.concurrent.ConcurrentHashMap<String, String>(256)

data class MoviePresentationSettings(
    val duplicateHandlingMode: VodDuplicateHandlingMode,
    val preferenceMode: VodVariantPreferenceMode,
    val preferredVariants: Map<String, Long> = emptyMap(),
    val observations: Map<Long, VodVariantObservation> = emptyMap()
)

fun buildPresentedMovies(
    movies: List<Movie>,
    settings: MoviePresentationSettings
): List<Movie> {
    if (settings.duplicateHandlingMode == VodDuplicateHandlingMode.SHOW_ALL || movies.size < 2) {
        return movies
    }

    return movies
        .groupBy(::movieLogicalGroupId)
        .values
        .flatMap { group ->
            val confidence = movieDuplicateConfidence(group)
            if (!shouldGroupMovies(confidence, settings.duplicateHandlingMode) || group.size < 2) {
                group
            } else {
                listOf(buildGroupedMovie(group, confidence, settings))
            }
        }
}

fun movieDuplicateConfidence(movies: List<Movie>): VodDuplicateConfidence {
    if (movies.size < 2) return VodDuplicateConfidence.NONE
    val normalizedTitles = movies.mapTo(mutableSetOf()) { normalizedMovieTitle(it.name) }
    if (normalizedTitles.size != 1 || normalizedTitles.first().isBlank()) {
        return VodDuplicateConfidence.NONE
    }

    val tmdbIds = movies.mapNotNull { it.tmdbId?.takeIf { id -> id > 0L } }.toSet()
    if (tmdbIds.size == 1 && tmdbIds.isNotEmpty()) {
        return VodDuplicateConfidence.EXACT
    }

    val years = movies.mapNotNull(::movieDisplayYear).toSet()
    if (years.size == 1 && years.isNotEmpty()) {
        return VodDuplicateConfidence.STRONG
    }

    val durations = movies.mapNotNull { it.durationSeconds.takeIf { seconds -> seconds > 0 } }
    if (durations.size >= 2 && durations.maxOrNull().orZero() - durations.minOrNull().orZero() <= 180) {
        return VodDuplicateConfidence.LIKELY
    }

    return VodDuplicateConfidence.WEAK
}

fun selectPreferredMovieVariant(
    movies: List<Movie>,
    preferenceMode: VodVariantPreferenceMode,
    preferredMovieId: Long? = null,
    observations: Map<Long, VodVariantObservation> = emptyMap()
): Movie? {
    if (movies.isEmpty()) return null
    if (preferenceMode == VodVariantPreferenceMode.MANUAL_LAST_CHOICE) {
        preferredMovieId?.let { preferredId ->
            movies.firstOrNull { it.id == preferredId }?.let { return it }
        }
    }
    return movies.maxWith(movieVariantComparator(preferenceMode, observations))
}

fun movieQualityScore(movie: Movie): Int = movieQualityScore(
    listOf(movie.name, movie.containerExtension, movie.streamUrl).filterNotNull().joinToString(" ")
)

fun movieRecencyScore(movie: Movie): Long =
    movie.releaseDate?.filter(Char::isDigit)?.take(8)?.toLongOrNull()
        ?: movieDisplayYear(movie)?.toLong()
        ?: movie.addedAt.takeIf { it > 0L }
        ?: 0L

fun movieLogicalGroupId(movie: Movie): String {
    val tmdbId = movie.tmdbId?.takeIf { it > 0L }
    if (tmdbId != null) return "movie:${movie.providerId}:tmdb:$tmdbId"
    return "movie:${movie.providerId}:${normalizedMovieTitle(movie.name)}:${movieDisplayYear(movie) ?: 0}"
}

private fun buildGroupedMovie(
    group: List<Movie>,
    confidence: VodDuplicateConfidence,
    settings: MoviePresentationSettings
): Movie {
    val groupId = movieLogicalGroupId(group.first())
    val preferredMovieId = settings.preferredVariants["${group.first().providerId}|$groupId"]
    val selected = selectPreferredMovieVariant(
        movies = group,
        preferenceMode = settings.preferenceMode,
        preferredMovieId = preferredMovieId,
        observations = settings.observations
    ) ?: group.first()
    val orderedVariants = group
        .sortedWith(movieVariantComparator(settings.preferenceMode, settings.observations).reversed())
        .let { sorted -> listOf(selected) + sorted.filterNot { it.id == selected.id } }
    val variants = orderedVariants.map(::toVodMovieVariant)
    val favorite = group.any(Movie::isFavorite)
    val watchProgress = group.maxOf(Movie::watchProgress)
    val lastWatchedAt = group.maxOf(Movie::lastWatchedAt)

    return selected.copy(
        isFavorite = favorite,
        watchProgress = maxOf(selected.watchProgress, watchProgress),
        lastWatchedAt = maxOf(selected.lastWatchedAt, lastWatchedAt),
        logicalGroupId = groupId,
        selectedVariantId = selected.id,
        variants = variants,
        duplicateConfidence = confidence,
        variantLabel = toVodMovieVariant(selected).label
    )
}

private fun shouldGroupMovies(
    confidence: VodDuplicateConfidence,
    mode: VodDuplicateHandlingMode
): Boolean = when (mode) {
    VodDuplicateHandlingMode.SHOW_ALL -> false
    VodDuplicateHandlingMode.SMART -> confidence == VodDuplicateConfidence.EXACT || confidence == VodDuplicateConfidence.STRONG
    VodDuplicateHandlingMode.GROUPED -> confidence == VodDuplicateConfidence.EXACT ||
        confidence == VodDuplicateConfidence.STRONG ||
        confidence == VodDuplicateConfidence.LIKELY
}

private fun movieVariantComparator(
    mode: VodVariantPreferenceMode,
    observations: Map<Long, VodVariantObservation>
): Comparator<Movie> = when (mode) {
    VodVariantPreferenceMode.FORCE_LATEST -> compareBy<Movie> { movieRecencyScore(it) }
        .thenBy { movieQualityScore(it) }
        .thenBy { reliabilityScore(it, observations) }
        .thenBy { metadataScore(it) }
        .thenBy { it.rating }
        .thenBy { -it.id }
    VodVariantPreferenceMode.BEST_QUALITY -> compareBy<Movie> { movieQualityScore(it) }
        .thenBy { reliabilityScore(it, observations) }
        .thenBy { movieRecencyScore(it) }
        .thenBy { metadataScore(it) }
        .thenBy { it.rating }
        .thenBy { -it.id }
    VodVariantPreferenceMode.LATEST_BEST_QUALITY -> compareBy<Movie> {
        movieRecencyScore(it) + (movieQualityScore(it).toLong() * 10L)
    }.thenBy { reliabilityScore(it, observations) }
        .thenBy { metadataScore(it) }
        .thenBy { it.rating }
        .thenBy { -it.id }
    VodVariantPreferenceMode.MOST_RELIABLE -> compareBy<Movie> { reliabilityScore(it, observations) }
        .thenBy { movieQualityScore(it) }
        .thenBy { movieRecencyScore(it) }
        .thenBy { metadataScore(it) }
        .thenBy { it.rating }
        .thenBy { -it.id }
    VodVariantPreferenceMode.BALANCED,
    VodVariantPreferenceMode.MANUAL_LAST_CHOICE -> compareBy<Movie> {
        (movieQualityScore(it) * 3) +
            (recencyBucket(it) * 120) +
            reliabilityScore(it, observations) +
            (metadataScore(it) * 8) +
            (it.rating * 10).toInt()
    }.thenBy { movieRecencyScore(it) }
        .thenBy { -it.id }
}

private fun toVodMovieVariant(movie: Movie): VodMovieVariant = VodMovieVariant(
    rawMovieId = movie.id,
    name = movie.name,
    streamUrl = movie.streamUrl,
    streamId = movie.streamId,
    containerExtension = movie.containerExtension,
    releaseDate = movie.releaseDate,
    year = movie.year,
    durationSeconds = movie.durationSeconds,
    rating = movie.rating,
    addedAt = movie.addedAt,
    qualityScore = movieQualityScore(movie),
    recencyScore = movieRecencyScore(movie),
    reliabilityScore = 0,
    label = movieVariantLabel(movie)
)

private fun movieQualityScore(value: String): Int {
    val normalized = normalizeTokenText(value)
    var score = 0
    QUALITY_TOKENS.forEach { (token, tokenScore) ->
        if (containsToken(normalized, token)) {
            score = maxOf(score, tokenScore)
        }
    }
    QUALITY_BONUS_TOKENS.forEach { (token, bonus) ->
        if (containsToken(normalized, token)) {
            score += bonus
        }
    }
    return score
}

private fun movieVariantLabel(movie: Movie): String {
    val normalized = normalizeTokenText(movie.name)
    val parts = mutableListOf<String>()
    QUALITY_TOKENS.firstOrNull { (token, _) -> containsToken(normalized, token) }?.let { (token, score) ->
        parts += when (score) {
            4320 -> "8K"
            2160 -> "4K"
            1440 -> "2K"
            1080 -> "1080p"
            720 -> "720p"
            else -> token.uppercase(Locale.ROOT)
        }
    }
    QUALITY_BONUS_TOKENS.forEach { (token, _) ->
        if (containsToken(normalized, token)) {
            parts += token.split(' ', '-').joinToString(" ") { word -> word.replaceFirstChar(Char::uppercaseChar) }
        }
    }
    EDITION_TOKENS.firstOrNull { containsToken(normalized, it) }?.let { token ->
        parts += token.split(' ').joinToString(" ") { word -> word.replaceFirstChar(Char::uppercaseChar) }
    }
    movieDisplayYear(movie)?.let { parts += it.toString() }
    movie.containerExtension?.takeIf { it.isNotBlank() }?.let { parts += it.uppercase(Locale.ROOT) }
    return parts.distinct().joinToString(" ").ifBlank { "Version ${movie.id}" }
}

private val NON_ASCII_REGEX = Regex("[^\\u0000-\\u007F]")

private fun normalizedMovieTitle(value: String): String {
    normalizedTitleCache[value]?.let { return it }
    val withoutProviderPrefix = value.substringAfter(" - ", value)
    val withoutYearSuffix = YEAR_SUFFIX_REGEX.replace(withoutProviderPrefix, "")
    val withoutQuality = QUALITY_CLEANUP_REGEX.replace(withoutYearSuffix, " ")
    // Fast path: skip Normalizer.normalize() for pure ASCII strings — on API 25
    // the JNI call to ICU4C is very slow and most IPTV movie titles are ASCII.
    val normalized = if (NON_ASCII_REGEX.containsMatchIn(withoutQuality)) {
        Normalizer.normalize(withoutQuality, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
    } else {
        withoutQuality
    }
    val result = NON_ALPHANUMERIC_REGEX.replace(normalized.lowercase(Locale.ROOT), "").trim()
    normalizedTitleCache[value] = result
    return result
}

private fun movieDisplayYear(movie: Movie): Int? {
    val cacheKey = movie.name + "|" + movie.year + "|" + movie.releaseDate
    displayYearCache[cacheKey]?.let { return it }
    val result = movie.year?.trim()?.toIntOrNull()
        ?: movie.releaseDate?.filter(Char::isDigit)?.take(4)?.toIntOrNull()
        ?: YEAR_REGEX.find(movie.name)?.value?.toIntOrNull()
    // ConcurrentHashMap.put throws NPE on null values on API 25, so only cache non-null
    if (result != null) displayYearCache[cacheKey] = result
    return result
}

private fun reliabilityScore(movie: Movie, observations: Map<Long, VodVariantObservation>): Int {
    val observation = observations[movie.id] ?: return 0
    val recentSuccessBonus = if (observation.lastSuccessfulAt >= observation.lastFailedAt) 40 else 0
    val recentFailurePenalty = if (observation.lastFailedAt > observation.lastSuccessfulAt) 120 else 0
    return (observation.successCount * 80) - (observation.failureCount * 120) + recentSuccessBonus - recentFailurePenalty
}

private fun metadataScore(movie: Movie): Int = listOfNotNull(
    movie.posterUrl,
    movie.backdropUrl,
    movie.plot,
    movie.cast,
    movie.director,
    movie.genre,
    movie.releaseDate,
    movie.duration,
    movie.year,
    movie.tmdbId?.toString()
).count { it.isNotBlank() }

private fun recencyBucket(movie: Movie): Int {
    val year = movieDisplayYear(movie) ?: return 0
    val currentYear = Year.now().value
    return when {
        year >= currentYear -> 5
        year == currentYear - 1 -> 4
        year >= currentYear - 3 -> 3
        year >= currentYear - 7 -> 2
        else -> 1
    }
}

private fun normalizeTokenText(value: String): String {
    val normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .lowercase(Locale.ROOT)
        .replace(Regex("""[^a-z0-9]+"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()
    return " $normalized "
}

private fun containsToken(normalizedValue: String, token: String): Boolean {
    val normalizedToken = token.lowercase(Locale.ROOT)
        .replace(Regex("""[^a-z0-9]+"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()
    if (normalizedToken.isBlank()) return false
    return normalizedValue.contains(" $normalizedToken ")
}

private fun Int?.orZero(): Int = this ?: 0
