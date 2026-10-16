package com.streamvault.feature.playback.player

import com.streamvault.domain.model.Episode
import com.streamvault.domain.model.Series
import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.model.PlaybackContentKind
import com.streamvault.player.playback.ResolvedStreamType
import com.streamvault.player.playback.StreamTypeResolver
import java.util.Locale
import kotlin.math.abs

internal data class EpisodeFallbackSource(
    val series: Series,
    val episode: Episode,
    val streamInfo: StreamInfo,
    val codecHint: String?,
    val heightHint: Int?,
    val positionMs: Long,
    val durationMs: Long
)

internal fun selectEpisodeFallbackSeries(source: EpisodeFallbackSource, rawVariants: List<Series>): Series? {
    val height = sourceHeight(source) ?: return null
    if (!eligibleSource(source)) return null
    if (rawVariants.none { it.id == source.series.id && sameSeries(source.series, it) }) return null
    return rawVariants.filter { candidate ->
        candidate.id != source.series.id && sameSeries(source.series, candidate) &&
            qualityHeight(candidate.name)?.let { it <= height } == true &&
            codec(candidate.name) in setOf(null, "avc") && !ambiguousCodec(candidate.name)
    }.sortedWith(compareByDescending<Series> { qualityHeight(it.name) }.thenBy { it.id }).firstOrNull()
}

internal fun verifyEpisodeFallback(source: EpisodeFallbackSource, candidateDetails: Series): Episode? {
    if (!eligibleSource(source) || candidateDetails.id == source.series.id || !sameSeries(source.series, candidateDetails)) return null
    val identity = explicitIdentity(source.episode) ?: return null
    if (!belongs(source.episode, source.series)) return null
    val originals = source.series.seasons.flatMap { it.episodes }.filter {
        it.seasonNumber == identity.first && it.episodeNumber == identity.second
    }
    if (originals.size != 1 || originals.single().id != source.episode.id ||
        explicitIdentity(originals.single()) != identity) return null
    val matches = candidateDetails.seasons.flatMap { it.episodes }.filter {
        it.seasonNumber == identity.first && it.episodeNumber == identity.second
    }
    val episode = matches.singleOrNull() ?: return null
    if (!belongs(episode, candidateDetails) || explicitIdentity(episode) != identity ||
        titleKey(episode.title) != titleKey(source.episode.title)) return null
    if (codec(episode.title) != "avc" || ambiguousCodec(episode.title) ||
        ambiguousCodec(candidateDetails.name) || codec(candidateDetails.name) == "hevc") return null
    val episodeHeight = qualityHeight(episode.title)
    val seriesHeight = qualityHeight(candidateDetails.name)
    if (ambiguousQuality(episode.title) || ambiguousQuality(candidateDetails.name)) return null
    if (episodeHeight != null && seriesHeight != null && episodeHeight != seriesHeight) return null
    val height = episodeHeight ?: seriesHeight ?: return null
    if (height > (sourceHeight(source) ?: return null)) return null
    val candidateDuration = episode.durationSeconds.toLong() * 1000L
    if (candidateDuration <= 0 || source.durationMs <= 0 || source.episode.durationSeconds <= 0 ||
        abs(source.durationMs - candidateDuration) > 2000L ||
        abs(source.episode.durationSeconds.toLong() * 1000L - candidateDuration) > 2000L ||
        source.positionMs < 0 || source.positionMs > candidateDuration - 15000L) return null
    return episode
}

private fun eligibleSource(source: EpisodeFallbackSource): Boolean =
    source.streamInfo.playbackContentKind == PlaybackContentKind.ON_DEMAND &&
        StreamTypeResolver.resolve(source.streamInfo) == ResolvedStreamType.PROGRESSIVE &&
        source.series.id > 0 && source.series.providerId > 0 && source.series.tmdbId?.let { it > 0 } == true &&
        sourceHeight(source) != null &&
        (if (!source.codecHint.isNullOrBlank()) codec(source.codecHint) else codec(source.episode.title)) == "hevc" &&
        verifiedSourceEpisode(source)

private fun verifiedSourceEpisode(source: EpisodeFallbackSource): Boolean {
    val identity = explicitIdentity(source.episode) ?: return false
    if (!belongs(source.episode, source.series)) return false
    val matches = source.series.seasons.flatMap { it.episodes }.filter {
        it.seasonNumber == identity.first && it.episodeNumber == identity.second
    }
    return matches.size == 1 && matches.single().id == source.episode.id && explicitIdentity(matches.single()) == identity
}

private fun sourceHeight(source: EpisodeFallbackSource): Int? =
    source.heightHint?.takeIf { it > 0 } ?: qualityHeight(source.episode.title) ?: qualityHeight(source.series.name)

private fun belongs(episode: Episode, series: Series) =
    episode.id > 0 && episode.seriesId == series.id && episode.providerId == series.providerId

private fun sameSeries(source: Series, candidate: Series) =
    source.providerId > 0 && candidate.providerId == source.providerId && source.tmdbId?.let { it > 0 } == true &&
        candidate.tmdbId == source.tmdbId && titleKey(candidate.name) == titleKey(source.name)

private val identityPattern = Regex("(?i)(?<![a-z0-9])s(\\d{1,3})e(\\d{1,3})(?!\\d)")
private val extraIdentityPattern = Regex("(?i)(?:s\\d+|e\\d+|[-–/&,;+]\\s*\\d+|\\band\\s+\\d+)")
private fun explicitIdentity(episode: Episode): Pair<Int, Int>? {
    val match = identityPattern.findAll(episode.title).toList().singleOrNull() ?: return null
    if (extraIdentityPattern.containsMatchIn(episode.title.removeRange(match.range))) return null
    val season = match.groupValues[1].toIntOrNull() ?: return null
    val number = match.groupValues[2].toIntOrNull() ?: return null
    return (season to number).takeIf { season > 0 && number > 0 && season == episode.seasonNumber && number == episode.episodeNumber }
}

private val codecAvc = Regex("(?i)(?<![a-z0-9])(?:avc|h[ ._-]*264|x264|avc1)(?![a-z0-9])")
private val codecHevc = Regex("(?i)(?<![a-z0-9])(?:hevc|h[ ._-]*265|x265|hev1|hvc1)(?![a-z0-9])")
private fun ambiguousCodec(text: String) = codecAvc.containsMatchIn(text) && codecHevc.containsMatchIn(text)
private fun codec(text: String): String? = when {
    ambiguousCodec(text) -> "ambiguous"
    codecHevc.containsMatchIn(text) -> "hevc"
    codecAvc.containsMatchIn(text) -> "avc"
    else -> null
}
private val qualityPatterns = listOf(
    "(?:8k|4320p)" to 4320, "(?:4k|uhd|2160[pi]?)" to 2160,
    "(?:2k|qhd|1440p)" to 1440, "(?:fhd|full\\s*hd|1080[pi]?)" to 1080,
    "720p" to 720, "576p" to 576, "540p" to 540, "480p" to 480, "360p" to 360, "240p" to 240
).map { (pattern, height) -> Regex("(?i)(?<![a-z0-9])$pattern(?![a-z0-9])") to height }
private fun qualityHeights(text: String) = qualityPatterns.filter { it.first.containsMatchIn(text) }.map { it.second }.distinct()
private fun qualityHeight(text: String) = qualityHeights(text).singleOrNull()
private fun ambiguousQuality(text: String) = qualityHeights(text).size > 1
private fun titleKey(text: String): String {
    var stripped = codecAvc.replace(codecHevc.replace(text, " "), " ")
    qualityPatterns.forEach { stripped = it.first.replace(stripped, " ") }
    return stripped.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
}
