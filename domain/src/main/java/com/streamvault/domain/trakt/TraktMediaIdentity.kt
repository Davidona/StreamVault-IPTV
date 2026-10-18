package com.streamvault.domain.trakt

import com.streamvault.domain.model.Episode
import com.streamvault.domain.model.Movie
import com.streamvault.domain.model.Series

/** Provider-independent identity; titles, URLs, and provider stream IDs are never identifiers. */
sealed interface TraktMediaIdentity {
    data class MovieId(val tmdbId: Long) : TraktMediaIdentity {
        init { require(tmdbId > 0) }
    }

    /** The TMDB ID belongs to the show, not the episode. Season zero represents specials. */
    data class EpisodeId(val showTmdbId: Long, val season: Int, val number: Int) : TraktMediaIdentity {
        init {
            require(showTmdbId > 0)
            require(season >= 0)
            require(number > 0)
        }
    }
}

/** Missing or inconsistent metadata means no automatic tracking, rather than a title guess. */
object TraktMediaIdentityResolver {
    fun movie(movie: Movie): TraktMediaIdentity.MovieId? =
        movie.tmdbId?.takeIf { it > 0 }?.let(TraktMediaIdentity::MovieId)

    fun episode(series: Series, episode: Episode): TraktMediaIdentity.EpisodeId? {
        val showId = series.tmdbId?.takeIf { it > 0 } ?: return null
        if (series.id <= 0 || episode.seriesId != series.id || episode.providerId != series.providerId ||
            episode.episodeNumber <= 0 || episode.seasonNumber < 0) return null
        return TraktMediaIdentity.EpisodeId(showId, episode.seasonNumber, episode.episodeNumber)
    }
}
