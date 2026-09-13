package com.streamvault.feature.playback.player

import com.streamvault.domain.model.Episode
import com.streamvault.domain.model.Series

internal fun resolveEpisode(
    series: Series,
    episodeId: Long,
    seasonNumber: Int?,
    episodeNumber: Int?
): Episode? {
    val episodes = series.seasons
        .sanitizedForPlayer()
        .sortedBy { it.seasonNumber }
        .flatMap { season -> season.episodes.sortedBy { it.episodeNumber } }
    return episodes.firstOrNull {
        it.id == episodeId || it.matchesPlaybackEpisode(episodeId, seasonNumber, episodeNumber)
    } ?: episodes.firstOrNull {
        it.seasonNumber == seasonNumber && it.episodeNumber == episodeNumber
    }
}

internal fun findNextEpisode(series: Series, episode: Episode): Episode? {
    val orderedEpisodes = orderedEpisodesForPlayer(series)
    val currentIndex = orderedEpisodes.indexOfFirst {
        it.id == episode.id ||
            it.playbackEpisodeIdentity() == episode.playbackEpisodeIdentity() ||
            (it.seasonNumber == episode.seasonNumber && it.episodeNumber == episode.episodeNumber)
    }
    return orderedEpisodes.getOrNull(currentIndex + 1)
}

internal fun findPreviousEpisode(series: Series, episode: Episode): Episode? {
    val orderedEpisodes = orderedEpisodesForPlayer(series)
    val currentIndex = orderedEpisodes.indexOfFirst {
        it.id == episode.id ||
            it.playbackEpisodeIdentity() == episode.playbackEpisodeIdentity() ||
            (it.seasonNumber == episode.seasonNumber && it.episodeNumber == episode.episodeNumber)
    }
    return orderedEpisodes.getOrNull(currentIndex - 1)
}

internal fun orderedEpisodesForPlayer(series: Series): List<Episode> = series.seasons
    .sanitizedForPlayer()
    .sortedBy { it.seasonNumber }
    .flatMap { season -> season.episodes.sortedBy { it.episodeNumber } }

internal fun Episode.playbackEpisodeIdentity(): Long =
    episodeId.takeIf { it > 0L } ?: id

internal fun Episode.matchesPlaybackEpisode(
    requestedEpisodeId: Long,
    requestedSeasonNumber: Int?,
    requestedEpisodeNumber: Int?
): Boolean {
    val identity = playbackEpisodeIdentity()
    return (requestedEpisodeId > 0L && identity == requestedEpisodeId) ||
        (requestedSeasonNumber != null &&
            requestedEpisodeNumber != null &&
            seasonNumber == requestedSeasonNumber &&
            episodeNumber == requestedEpisodeNumber)
}

internal fun buildEpisodePlaybackTitle(episode: Episode): String =
    "${episode.title} - S${episode.seasonNumber}E${episode.episodeNumber}"
