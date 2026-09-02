package com.streamvault.app.navigation.graph

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.compose.ui.platform.LocalContext
import com.streamvault.app.navigation.AppNavigationPayloads
import com.streamvault.app.navigation.AppRouteCodec
import com.streamvault.app.navigation.AppRoutePatterns
import com.streamvault.app.navigation.CatalogDetailNavigationActions
import com.streamvault.app.navigation.toLivePlayerRequest
import com.streamvault.app.navigation.toPlayerNavigationRequest
import com.streamvault.app.ui.screens.search.SearchScreen
import com.streamvault.feature.catalog.api.CatalogNavigationChrome
import com.streamvault.feature.catalog.api.CatalogScaffoldContent
import com.streamvault.feature.catalog.presentation.movies.MoviesScreen
import com.streamvault.feature.catalog.presentation.movies.MovieDetailScreen
import com.streamvault.feature.catalog.presentation.series.SeriesScreen
import com.streamvault.feature.catalog.presentation.series.SeriesDetailScreen
import com.streamvault.feature.catalog.presentation.vod.VodScreen
import com.streamvault.app.ui.components.shell.AppNavigationChrome
import com.streamvault.app.ui.components.shell.AppScreenScaffold
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
            scaffold = catalogAppScaffold(
                currentRoute = AppRoutePatterns.MOVIES,
                onNavigate = { route -> AppRouteCodec.decode(route)?.let(onTopLevelDestinationRequested) }
            )
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
            scaffold = catalogAppScaffold(
                currentRoute = AppRoutePatterns.SERIES,
                onNavigate = { route -> AppRouteCodec.decode(route)?.let(onTopLevelDestinationRequested) }
            )
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
            scaffold = catalogAppScaffold(
                currentRoute = AppRoutePatterns.VOD,
                onNavigate = { route -> AppRouteCodec.decode(route)?.let(onTopLevelDestinationRequested) }
            )
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
        val platformHost = LocalContext.current as? com.streamvault.feature.catalog.api.CatalogPlatformHost
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
            onBack = { actions.returnTo(returnDestination) },
            platformHost = platformHost
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
        val platformHost = LocalContext.current as? com.streamvault.feature.catalog.api.CatalogPlatformHost
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
            onBack = { actions.returnTo(returnDestination) },
            platformHost = platformHost
        )
    }
}

private fun catalogAppScaffold(
    currentRoute: String,
    onNavigate: (String) -> Unit
): CatalogScaffoldContent = { _, title, subtitle, chrome, topBarVisible, compactHeader, showScreenHeader, content ->
    AppScreenScaffold(
        currentRoute = currentRoute,
        onNavigate = onNavigate,
        title = title,
        subtitle = subtitle,
        navigationChrome = when (chrome) {
            CatalogNavigationChrome.Rail -> AppNavigationChrome.Rail
            CatalogNavigationChrome.TopBar -> AppNavigationChrome.TopBar
        },
        topBarVisible = topBarVisible,
        compactHeader = compactHeader,
        showScreenHeader = showScreenHeader,
        content = content
    )
}
