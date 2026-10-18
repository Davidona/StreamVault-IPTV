package com.streamvault.feature.catalog.presentation.dashboard

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.AppHomeDashboardShelf
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.Movie
import com.streamvault.domain.model.Series
import org.junit.Test

class DashboardHomeShelvesTest {

    @Test
    fun `resolveVisibleDashboardShelves keeps enabled order and filters empty shelves`() {
        val uiState = DashboardUiState(
            homeDashboardShelves = listOf(
                AppHomeDashboardShelf.RECOMMENDED_MOVIES,
                AppHomeDashboardShelf.FAVORITE_CHANNELS,
                AppHomeDashboardShelf.RECENT_MOVIES
            ),
            favoriteChannels = listOf(Channel(id = 1L, name = "News")),
            recentMovies = listOf(Movie(id = 2L, name = "Fresh")),
            recommendedMovies = emptyList()
        )

        val visibleShelves = resolveVisibleDashboardShelves(uiState)

        assertThat(visibleShelves).containsExactly(
            AppHomeDashboardShelf.FAVORITE_CHANNELS,
            AppHomeDashboardShelf.RECENT_MOVIES
        ).inOrder()
    }

    @Test
    fun `resolveVisibleDashboardShelves supports optional shelves when they have content`() {
        val uiState = DashboardUiState(
            homeDashboardShelves = listOf(
                AppHomeDashboardShelf.TOP_RATED_MOVIES,
                AppHomeDashboardShelf.FAVORITE_MOVIES
            ),
            topRatedMovies = listOf(Movie(id = 10L, name = "Top Pick")),
            favoriteMovies = listOf(Movie(id = 11L, name = "Saved Pick"))
        )

        val visibleShelves = resolveVisibleDashboardShelves(uiState)

        assertThat(visibleShelves).containsExactly(
            AppHomeDashboardShelf.TOP_RATED_MOVIES,
            AppHomeDashboardShelf.FAVORITE_MOVIES
        ).inOrder()
    }

    @Test
    fun `resolveVisibleDashboardShelves shows Trakt watchlist shelves when they have content`() {
        val uiState = DashboardUiState(
            homeDashboardShelves = listOf(
                AppHomeDashboardShelf.TRAKT_WATCHLIST_MOVIES,
                AppHomeDashboardShelf.TRAKT_WATCHLIST_SERIES
            ),
            traktWatchlistMovies = listOf(Movie(id = 30L, name = "Watchlisted movie")),
            traktWatchlistSeries = listOf(Series(id = 31L, name = "Watchlisted show"))
        )

        assertThat(resolveVisibleDashboardShelves(uiState)).containsExactly(
            AppHomeDashboardShelf.TRAKT_WATCHLIST_MOVIES,
            AppHomeDashboardShelf.TRAKT_WATCHLIST_SERIES
        ).inOrder()
    }

    @Test
    fun `resolveVisibleDashboardShelves shows pinned categories when they have content`() {
        val uiState = DashboardUiState(
            homeDashboardShelves = listOf(AppHomeDashboardShelf.PINNED_CATEGORIES),
            pinnedMovieCategories = mapOf("Action" to listOf(Movie(id = 20L, name = "Pinned Pick")))
        )

        assertThat(resolveVisibleDashboardShelves(uiState))
            .containsExactly(AppHomeDashboardShelf.PINNED_CATEGORIES)
    }
}
