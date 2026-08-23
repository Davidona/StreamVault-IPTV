package com.streamvault.app.navigation.graph

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.streamvault.app.navigation.AppNavigationPayloads
import com.streamvault.app.navigation.AppRouteCodec
import com.streamvault.app.navigation.AppRoutePatterns
import com.streamvault.app.navigation.CatalogDetailNavigationActions
import com.streamvault.app.navigation.toLivePlayerRequest
import com.streamvault.app.navigation.toPlayerNavigationRequest
import com.streamvault.app.ui.screens.movies.MovieDetailScreen
import com.streamvault.app.ui.screens.movies.MoviesScreen
import com.streamvault.app.ui.screens.search.SearchScreen
import com.streamvault.app.ui.screens.series.SeriesDetailScreen
import com.streamvault.app.ui.screens.series.SeriesScreen
import com.streamvault.app.ui.screens.vod.VodScreen
import com.streamvault.core.navigation.AppDestination
import com.streamvault.core.navigation.NavigationActions
import com.streamvault.core.navigation.NavigationOptions

internal fun NavGraphBuilder.registerCatalogGraph(
    actions: NavigationActions,
    catalogDetailActions: CatalogDetailNavigationActions,
    payloads: AppNavigationPayloads,
    onTopLevelDestinationRequested: (AppDestination) -> Unit
) {
    composable(AppRoutePatterns.MOVIES) {
        MoviesScreen(
            onMovieClick = { movie ->
                catalogDetailActions.openMovieDetail(movie, AppDestination.Movies)
            },
            onContinueWatchingPlay = { history ->
                actions.openPlayer(
                    history.toPlayerNavigationRequest().copy(returnDestination = AppDestination.Movies)
                )
            },
            onNavigate = { route ->
                AppRouteCodec.decode(route)?.let(onTopLevelDestinationRequested)
            },
            currentRoute = AppRoutePatterns.MOVIES
        )
    }

    composable(AppRoutePatterns.SERIES) {
        SeriesScreen(
            onSeriesClick = { series ->
                catalogDetailActions.openSeriesDetail(series, AppDestination.Series)
            },
            onSeriesIdClick = { seriesId ->
                actions.navigate(
                    AppDestination.SeriesDetail(seriesId, AppDestination.Series),
                    NavigationOptions(launchSingleTop = true)
                )
            },
            onNavigate = { route ->
                AppRouteCodec.decode(route)?.let(onTopLevelDestinationRequested)
            },
            currentRoute = AppRoutePatterns.SERIES
        )
    }

    composable(AppRoutePatterns.VOD) {
        VodScreen(
            onMovieClick = { movie ->
                catalogDetailActions.openMovieDetail(movie, AppDestination.Vod)
            },
            onSeriesClick = { series ->
                catalogDetailActions.openSeriesDetail(series, AppDestination.Vod)
            },
            onNavigate = { route ->
                AppRouteCodec.decode(route)?.let(onTopLevelDestinationRequested)
            },
            currentRoute = AppRoutePatterns.VOD
        )
    }

    composable(
        route = AppRoutePatterns.SEARCH_DESTINATION,
        arguments = listOf(
            navArgument("query") { type = NavType.StringType; defaultValue = "" }
        )
    ) { backStackEntry ->
        val query = backStackEntry.arguments?.getString("query").orEmpty()
        SearchScreen(
            initialQuery = query,
            onChannelClick = { channel ->
                actions.openPlayer(
                    channel.toLivePlayerRequest(
                        categoryId = channel.categoryId,
                        providerId = channel.providerId,
                        isVirtual = false,
                        returnDestination = AppDestination.Search(query)
                    )
                )
            },
            onMovieClick = { movie ->
                catalogDetailActions.openMovieDetail(movie, AppDestination.Search(query))
            },
            onSeriesClick = { series ->
                catalogDetailActions.openSeriesDetail(series, AppDestination.Search(query))
            },
            onNavigate = { route ->
                AppRouteCodec.decode(route)?.let(onTopLevelDestinationRequested)
            },
            currentRoute = AppRoutePatterns.SEARCH
        )
    }

    composable(
        route = AppRoutePatterns.MOVIE_DETAIL,
        arguments = listOf(
            navArgument("movieId") { type = NavType.LongType },
            navArgument("returnRoute") { type = NavType.StringType; defaultValue = "" }
        )
    ) { backStackEntry ->
        payloads.consumeMoviePresentationHint(backStackEntry)
        val returnDestination = backStackEntry.arguments?.getString("returnRoute")
            .orEmpty()
            .takeIf { it.isNotBlank() }
            ?.let(AppRouteCodec::decode)
        val movieId = backStackEntry.arguments?.getLong("movieId") ?: -1L
        MovieDetailScreen(
            onPlay = { movie ->
                actions.openPlayer(
                    movie.toPlayerNavigationRequest(
                        returnDestination = AppDestination.MovieDetail(
                            movieId = movie.id.takeIf { it > 0L } ?: movieId,
                            returnDestination = returnDestination
                        )
                    )
                )
            },
            onBack = { actions.returnTo(returnDestination) }
        )
    }

    composable(
        route = AppRoutePatterns.SERIES_DETAIL,
        arguments = listOf(
            navArgument("seriesId") { type = NavType.LongType },
            navArgument("returnRoute") { type = NavType.StringType; defaultValue = "" }
        )
    ) { backStackEntry ->
        payloads.consumeSeriesPresentationHint(backStackEntry)
        val returnDestination = backStackEntry.arguments?.getString("returnRoute")
            .orEmpty()
            .takeIf { it.isNotBlank() }
            ?.let(AppRouteCodec::decode)
        val seriesId = backStackEntry.arguments?.getLong("seriesId") ?: -1L
        SeriesDetailScreen(
            onEpisodeClick = { episode ->
                actions.openPlayer(
                    episode.toPlayerNavigationRequest(
                        returnDestination = AppDestination.SeriesDetail(
                            seriesId = episode.seriesId.takeIf { it > 0L } ?: seriesId,
                            returnDestination = returnDestination
                        )
                    )
                )
            },
            onBack = { actions.returnTo(returnDestination) }
        )
    }
}
