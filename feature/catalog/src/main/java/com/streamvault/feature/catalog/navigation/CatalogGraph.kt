package com.streamvault.feature.catalog.navigation

import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.streamvault.core.navigation.AppDestination
import com.streamvault.core.navigation.NavigationActions
import com.streamvault.core.navigation.NavigationOptions
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.Episode
import com.streamvault.domain.model.Movie
import com.streamvault.domain.model.PlaybackHistory
import com.streamvault.domain.model.Series
import com.streamvault.feature.catalog.api.CatalogChannelPlaybackContext
import com.streamvault.feature.catalog.api.CatalogDashboardShelfCustomizationContent
import com.streamvault.feature.catalog.api.CatalogPlatformHost
import com.streamvault.feature.catalog.api.CatalogScaffoldContent
import com.streamvault.feature.catalog.presentation.dashboard.DashboardScreen
import com.streamvault.feature.catalog.presentation.movies.MovieDetailScreen
import com.streamvault.feature.catalog.presentation.movies.MoviesScreen
import com.streamvault.feature.catalog.presentation.search.SearchScreen
import com.streamvault.feature.catalog.presentation.series.SeriesDetailScreen
import com.streamvault.feature.catalog.presentation.series.SeriesScreen
import com.streamvault.feature.catalog.presentation.vod.VodScreen

/**
 * Registers every Catalog-owned destination without depending on the app navigation
 * implementation. App composition supplies platform, payload, player, and shell seams.
 */
fun NavGraphBuilder.registerCatalogGraph(
    actions: NavigationActions,
    platformHost: CatalogPlatformHost?,
    scaffold: CatalogScaffoldContent,
    dashboardShelfCustomizationContent: CatalogDashboardShelfCustomizationContent,
    onTopLevelDestinationRequested: (AppDestination) -> Unit,
    onOpenMovieDetail: (Movie, AppDestination) -> Unit,
    onOpenSeriesDetail: (Series, AppDestination) -> Unit,
    onPlayChannel: (Channel, CatalogChannelPlaybackContext) -> Unit,
    onPlayMovie: (Movie, AppDestination) -> Unit,
    onPlayEpisode: (Episode, AppDestination) -> Unit,
    onPlayHistory: (PlaybackHistory, AppDestination) -> Unit,
    consumeMoviePresentationHint: (NavBackStackEntry) -> Unit,
    consumeSeriesPresentationHint: (NavBackStackEntry) -> Unit,
    decodeDestination: (String) -> AppDestination? = ::decodeCatalogReturnDestination,
) {
    composable(CatalogRoutePatterns.HOME) {
        DashboardScreen(
            onDestinationRequested = onTopLevelDestinationRequested,
            onAddProvider = { actions.navigate(AppDestination.ProviderSetup()) },
            onRecentChannelClick = { channel, combinedProfileId ->
                onPlayChannel(
                    channel,
                    CatalogChannelPlaybackContext(
                        categoryId = com.streamvault.domain.model.VirtualCategoryIds.RECENT,
                        providerId = channel.providerId,
                        isVirtual = true,
                        combinedProfileId = combinedProfileId,
                        returnDestination = AppDestination.Home,
                    )
                )
            },
            onFavoriteChannelClick = { channel, combinedProfileId ->
                onPlayChannel(
                    channel,
                    CatalogChannelPlaybackContext(
                        categoryId = com.streamvault.domain.model.VirtualCategoryIds.FAVORITES,
                        providerId = channel.providerId,
                        isVirtual = true,
                        combinedProfileId = combinedProfileId,
                        returnDestination = AppDestination.Home,
                    )
                )
            },
            onMovieClick = { movie -> onOpenMovieDetail(movie, AppDestination.Home) },
            onSeriesClick = { series -> onOpenSeriesDetail(series, AppDestination.Home) },
            onPlaybackHistoryClick = { history -> onPlayHistory(history, AppDestination.Home) },
            scaffold = scaffold,
            dashboardShelfCustomizationContent = dashboardShelfCustomizationContent,
        )
    }

    composable(CatalogRoutePatterns.MOVIES) {
        MoviesScreen(
            onMovieClick = { movie -> onOpenMovieDetail(movie, AppDestination.Movies) },
            onContinueWatchingPlay = { history -> onPlayHistory(history, AppDestination.Movies) },
            scaffold = scaffold,
        )
    }

    composable(CatalogRoutePatterns.SERIES) {
        SeriesScreen(
            onSeriesClick = { series -> onOpenSeriesDetail(series, AppDestination.Series) },
            onSeriesIdClick = { seriesId ->
                actions.navigate(
                    AppDestination.SeriesDetail(seriesId, AppDestination.Series),
                    NavigationOptions(launchSingleTop = true)
                )
            },
            scaffold = scaffold,
        )
    }

    composable(CatalogRoutePatterns.VOD) {
        VodScreen(
            onMovieClick = { movie -> onOpenMovieDetail(movie, AppDestination.Vod) },
            onSeriesClick = { series -> onOpenSeriesDetail(series, AppDestination.Vod) },
            scaffold = scaffold,
        )
    }

    composable(
        route = CatalogRoutePatterns.SEARCH_DESTINATION,
        arguments = listOf(
            navArgument("query") { type = NavType.StringType; defaultValue = "" }
        )
    ) { backStackEntry ->
        val query = backStackEntry.arguments?.getString("query").orEmpty()
        SearchScreen(
            initialQuery = query,
            onChannelClick = { channel ->
                onPlayChannel(
                    channel,
                    CatalogChannelPlaybackContext(
                        categoryId = channel.categoryId,
                        providerId = channel.providerId,
                        isVirtual = false,
                        combinedProfileId = null,
                        returnDestination = AppDestination.Search(query),
                    )
                )
            },
            onMovieClick = { movie -> onOpenMovieDetail(movie, AppDestination.Search(query)) },
            onSeriesClick = { series -> onOpenSeriesDetail(series, AppDestination.Search(query)) },
            scaffold = scaffold,
        )
    }

    composable(
        route = CatalogRoutePatterns.MOVIE_DETAIL,
        arguments = listOf(
            navArgument("movieId") { type = NavType.LongType },
            navArgument("returnRoute") { type = NavType.StringType; defaultValue = "" },
        )
    ) { backStackEntry ->
        consumeMoviePresentationHint(backStackEntry)
        val returnDestination = backStackEntry.arguments?.getString("returnRoute")
            .orEmpty()
            .takeIf(String::isNotBlank)
            ?.let(decodeDestination)
        val movieId = backStackEntry.arguments?.getLong("movieId") ?: -1L
        MovieDetailScreen(
            onPlay = { movie ->
                onPlayMovie(
                    movie,
                    AppDestination.MovieDetail(
                        movieId = movie.id.takeIf { it > 0L } ?: movieId,
                        returnDestination = returnDestination,
                    )
                )
            },
            onOpenRelatedMovie = { relatedMovie ->
                openRelatedMovieDetail(
                    relatedMovie = relatedMovie,
                    currentMovieId = movieId,
                    currentReturnDestination = returnDestination,
                    onOpenMovieDetail = onOpenMovieDetail,
                )
            },
            onBack = { actions.returnTo(returnDestination) },
            platformHost = platformHost,
        )
    }

    composable(
        route = CatalogRoutePatterns.SERIES_DETAIL,
        arguments = listOf(
            navArgument("seriesId") { type = NavType.LongType },
            navArgument("returnRoute") { type = NavType.StringType; defaultValue = "" },
        )
    ) { backStackEntry ->
        consumeSeriesPresentationHint(backStackEntry)
        val returnDestination = backStackEntry.arguments?.getString("returnRoute")
            .orEmpty()
            .takeIf(String::isNotBlank)
            ?.let(decodeDestination)
        val seriesId = backStackEntry.arguments?.getLong("seriesId") ?: -1L
        SeriesDetailScreen(
            onEpisodeClick = { episode ->
                onPlayEpisode(
                    episode,
                    AppDestination.SeriesDetail(
                        seriesId = episode.seriesId.takeIf { it > 0L } ?: seriesId,
                        returnDestination = returnDestination,
                    )
                )
            },
            onBack = { actions.returnTo(returnDestination) },
            platformHost = platformHost,
        )
    }
}

internal fun openRelatedMovieDetail(
    relatedMovie: Movie,
    currentMovieId: Long,
    currentReturnDestination: AppDestination?,
    onOpenMovieDetail: (Movie, AppDestination) -> Unit,
) {
    onOpenMovieDetail(
        relatedMovie,
        AppDestination.MovieDetail(
            movieId = currentMovieId,
            returnDestination = currentReturnDestination,
        )
    )
}

private fun decodeCatalogReturnDestination(route: String): AppDestination? {
    val path = route.substringBefore('?')
    return when (path) {
        CatalogRoutePatterns.HOME -> AppDestination.Home
        CatalogRoutePatterns.MOVIES -> AppDestination.Movies
        CatalogRoutePatterns.SERIES -> AppDestination.Series
        CatalogRoutePatterns.VOD -> AppDestination.Vod
        CatalogRoutePatterns.SEARCH -> AppDestination.Search(
            route.substringAfter("query=", "").takeIf(String::isNotBlank)
        )
        else -> null
    }
}
