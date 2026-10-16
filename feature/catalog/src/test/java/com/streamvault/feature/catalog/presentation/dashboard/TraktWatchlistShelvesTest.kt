package com.streamvault.feature.catalog.presentation.dashboard

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.Movie
import com.streamvault.domain.model.Series
import com.streamvault.domain.trakt.TraktWatchlistIdentity
import com.streamvault.domain.trakt.TraktWatchlistType
import org.junit.Test

class TraktWatchlistShelvesTest {

    @Test
    fun `movie shelf follows Trakt order and skips items absent from the active provider`() {
        val watchlist = listOf(
            TraktWatchlistIdentity(TraktWatchlistType.MOVIE, 200L),
            TraktWatchlistIdentity(TraktWatchlistType.SHOW, 400L),
            TraktWatchlistIdentity(TraktWatchlistType.MOVIE, 999L),
            TraktWatchlistIdentity(TraktWatchlistType.MOVIE, 100L)
        )
        val movieA = Movie(id = 1L, name = "First", tmdbId = 100L)
        val movieB = Movie(id = 2L, name = "Second", tmdbId = 200L)

        val result = orderedWatchlistItems(
            watchlistItems = watchlist,
            type = TraktWatchlistType.MOVIE,
            catalogItems = listOf(movieA, movieB),
            tmdbIdOf = Movie::tmdbId,
            idOf = Movie::id,
            limit = 12
        )

        assertThat(result).containsExactly(movieB, movieA).inOrder()
    }

    @Test
    fun `show shelf contains only shows and respects its item limit`() {
        val watchlist = listOf(
            TraktWatchlistIdentity(TraktWatchlistType.MOVIE, 10L),
            TraktWatchlistIdentity(TraktWatchlistType.SHOW, 30L),
            TraktWatchlistIdentity(TraktWatchlistType.SHOW, 20L)
        )
        val first = Series(id = 3L, name = "First", tmdbId = 30L)
        val second = Series(id = 2L, name = "Second", tmdbId = 20L)

        val result = orderedWatchlistItems(
            watchlistItems = watchlist,
            type = TraktWatchlistType.SHOW,
            catalogItems = listOf(second, first),
            tmdbIdOf = Series::tmdbId,
            idOf = Series::id,
            limit = 1
        )

        assertThat(result).containsExactly(first)
    }

    @Test
    fun `shelf ignores catalog rows without a usable TMDB id`() {
        val movie = Movie(id = 1L, name = "No match")

        val result = orderedWatchlistItems(
            watchlistItems = listOf(TraktWatchlistIdentity(TraktWatchlistType.MOVIE, 50L)),
            type = TraktWatchlistType.MOVIE,
            catalogItems = listOf(movie),
            tmdbIdOf = Movie::tmdbId,
            idOf = Movie::id,
            limit = 12
        )

        assertThat(result).isEmpty()
    }

    @Test
    fun `discovery shelf follows Trakt rank and skips titles absent from the provider`() {
        val first = Movie(id = 1L, name = "First", tmdbId = 603L)
        val second = Movie(id = 2L, name = "Second", tmdbId = 550L)

        val result = orderedDiscoveryItems(
            tmdbIds = listOf(550L, 999L, 603L),
            catalogItems = listOf(first, second),
            tmdbIdOf = Movie::tmdbId,
            idOf = Movie::id,
            limit = 10
        )

        assertThat(result).containsExactly(second, first).inOrder()
    }
}
