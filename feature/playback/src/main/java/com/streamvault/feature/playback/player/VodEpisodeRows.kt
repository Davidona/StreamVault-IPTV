package com.streamvault.feature.playback.player

import com.streamvault.domain.model.Episode
import com.streamvault.domain.model.Season

/** Seasons without episodes are not offered: there is nothing to play in them. */
internal fun vodEpisodeSeasons(seasons: List<Season>): List<Season> = seasons.filter { it.episodes.isNotEmpty() }

/** Season to show first: the one playing, else the first one. [seasons] must come from [vodEpisodeSeasons]. */
internal fun vodEpisodeInitialSeason(seasons: List<Season>, currentSeasonNumber: Int?): Int? =
    seasons.firstOrNull { it.seasonNumber == currentSeasonNumber }?.seasonNumber ?: seasons.firstOrNull()?.seasonNumber

/** Row to focus when the panel opens: the playing episode when it is in the shown season, else the first. */
internal fun vodEpisodeTargetIndex(episodes: List<Episode>, currentEpisodeId: Long): Int =
    episodes.indexOfFirst { it.id == currentEpisodeId }.coerceAtLeast(0)
