package com.streamvault.app.ui.screens.dashboard

import com.streamvault.domain.model.AppHomeDashboardShelf

internal fun resolveVisibleDashboardShelves(
    uiState: DashboardUiState
): List<AppHomeDashboardShelf> = AppHomeDashboardShelf
    .normalizeForStorage(uiState.homeDashboardShelves)
    .filter(uiState::hasContentFor)

internal fun DashboardUiState.hasContentFor(shelf: AppHomeDashboardShelf): Boolean = when (shelf) {
    AppHomeDashboardShelf.FAVORITE_CHANNELS -> favoriteChannels.isNotEmpty()
    AppHomeDashboardShelf.RECENT_CHANNELS -> recentChannels.isNotEmpty()
    AppHomeDashboardShelf.LIVE_SHORTCUTS -> liveShortcuts.isNotEmpty()
    AppHomeDashboardShelf.CONTINUE_WATCHING -> continueWatching.isNotEmpty()
    AppHomeDashboardShelf.PINNED_CATEGORIES -> pinnedMovieCategories.isNotEmpty() || pinnedSeriesCategories.isNotEmpty()
    AppHomeDashboardShelf.RECENT_MOVIES -> recentMovies.isNotEmpty()
    AppHomeDashboardShelf.RECENT_SERIES -> recentSeries.isNotEmpty()
    AppHomeDashboardShelf.FAVORITE_MOVIES -> favoriteMovies.isNotEmpty()
    AppHomeDashboardShelf.FAVORITE_SERIES -> favoriteSeries.isNotEmpty()
    AppHomeDashboardShelf.CONTINUE_WATCHING_MOVIES -> continueWatchingMovies.isNotEmpty()
    AppHomeDashboardShelf.CONTINUE_WATCHING_SERIES -> continueWatchingSeriesItems.isNotEmpty()
    AppHomeDashboardShelf.TOP_RATED_MOVIES -> topRatedMovies.isNotEmpty()
    AppHomeDashboardShelf.RECOMMENDED_MOVIES -> recommendedMovies.isNotEmpty()
}
