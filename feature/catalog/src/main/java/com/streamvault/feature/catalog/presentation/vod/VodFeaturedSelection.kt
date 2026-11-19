package com.streamvault.feature.catalog.presentation.vod

import com.streamvault.domain.model.*
import java.time.LocalDate
import java.util.Locale

/** Identity includes provider, so progress from another library never selects a title. */
data class FeaturedCandidate(
    val id: Long, val providerId: Long, val title: String, val plot: String?,
    val releaseDate: String?, val addedAt: Long, val genre: String?, val rating: Float,
    val tmdbId: Long?, val movie: Movie? = null, val series: Series? = null,
)
data class VodFeaturedSelection(
    val mode: VodFeaturedMode, val candidate: FeaturedCandidate,
    val history: PlaybackHistory? = null, val episode: Episode? = null,
)
internal fun releaseTimestamp(value: String?): Long = runCatching {
    val date = value.orEmpty().trim()
    if (date.length == 4) LocalDate.of(date.toInt(), 1, 1).toEpochDay()
    else LocalDate.parse(date.take(10)).toEpochDay()
}.getOrDefault(Long.MIN_VALUE)
internal fun selectFeatured(
    mode: VodFeaturedMode, candidates: List<FeaturedCandidate>, history: List<PlaybackHistory>,
    watchlist: Set<Long>, randomSeed: Int,
): VodFeaturedSelection? {
    if (mode == VodFeaturedMode.HIDDEN || candidates.isEmpty()) return null
    fun matches(item: FeaturedCandidate, row: PlaybackHistory) = item.providerId == row.providerId &&
        (item.id == (row.seriesId ?: row.contentId) ||
            item.movie?.variants?.any { it.rawMovieId == row.contentId } == true ||
            item.series?.variants?.any { it.rawSeriesId == (row.seriesId ?: row.contentId) } == true)
    val orderedHistory = history.sortedByDescending { it.lastWatchedAt }
    if (mode == VodFeaturedMode.CONTINUE_WATCHING || mode == VodFeaturedMode.UP_NEXT) {
        for (row in orderedHistory) {
            if (mode == VodFeaturedMode.CONTINUE_WATCHING &&
                (row.watchedStatus != PlaybackWatchedStatus.IN_PROGRESS ||
                    (row.totalDurationMs > 0 && row.resumePositionMs.toDouble() / row.totalDurationMs >= .9))) continue
            val item = candidates.firstOrNull { matches(it, row) } ?: continue
            return VodFeaturedSelection(mode, item, row)
        }
        return null
    }
    val unwatched = candidates.filter { item -> history.none { matches(item, it) } }
    fun genres(item: FeaturedCandidate) = item.genre.orEmpty().lowercase(Locale.ROOT)
        .split(',', '/', '|').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    val seeds = candidates.filter { it.tmdbId in watchlist || history.any { row -> matches(it, row) } }
    val affinity = seeds.flatMap { genres(it) }.groupingBy { it }.eachCount()
    val selected = when (mode) {
        VodFeaturedMode.LATEST_RELEASE -> candidates.filter { releaseTimestamp(it.releaseDate) != Long.MIN_VALUE }
            .maxWithOrNull(compareBy<FeaturedCandidate> { releaseTimestamp(it.releaseDate) }.thenBy { it.id })
        VodFeaturedMode.RECENTLY_ADDED -> candidates.filter { it.addedAt > 0 }
            .maxWithOrNull(compareBy<FeaturedCandidate> { it.addedAt }.thenBy { it.id })
        VodFeaturedMode.RANDOM -> unwatched.sortedBy { it.id }.shuffled(kotlin.random.Random(randomSeed)).firstOrNull()
        VodFeaturedMode.FOR_YOU -> unwatched.maxWithOrNull(compareBy<FeaturedCandidate> {
            genres(it).sumOf { genre -> affinity[genre] ?: 0 } * 10 + if (it.tmdbId in watchlist) 20 else 0
        }.thenBy { it.rating }.thenBy { it.id })
        else -> null
    } ?: return null
    return VodFeaturedSelection(mode, selected)
}

/** Season/episode order, never numeric database IDs. Unknown duration does not imply completion. */
internal fun nextFeaturedEpisode(series: Series, current: PlaybackHistory, history: List<PlaybackHistory>): Episode? {
    val completed = current.watchedStatus != PlaybackWatchedStatus.IN_PROGRESS ||
        current.totalDurationMs > 0 && current.resumePositionMs.toDouble() / current.totalDurationMs >= .9
    if (!completed) return null
    val season = current.seasonNumber ?: return null
    val number = current.episodeNumber ?: return null
    return series.seasons.flatMap { it.episodes }
        .filter { it.seasonNumber > 0 && (it.seasonNumber > season || it.seasonNumber == season && it.episodeNumber > number) }
        .sortedWith(compareBy<Episode> { it.seasonNumber }.thenBy { it.episodeNumber })
        .firstOrNull { episode -> history.none { row ->
            row.providerId == current.providerId && row.contentId == episode.id &&
                (row.watchedStatus != PlaybackWatchedStatus.IN_PROGRESS || row.totalDurationMs > 0 &&
                    row.resumePositionMs.toDouble() / row.totalDurationMs >= .9)
        } }
}
