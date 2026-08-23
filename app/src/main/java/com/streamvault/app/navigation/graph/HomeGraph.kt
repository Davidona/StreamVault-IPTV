package com.streamvault.app.navigation.graph

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.streamvault.app.navigation.AppRouteCodec
import com.streamvault.app.navigation.AppRoutePatterns
import com.streamvault.app.navigation.CatalogDetailNavigationActions
import com.streamvault.app.navigation.toPlayerNavigationRequest
import com.streamvault.app.navigation.toLivePlayerRequest
import com.streamvault.app.ui.screens.dashboard.DashboardScreen
import com.streamvault.core.navigation.AppDestination
import com.streamvault.core.navigation.NavigationActions
import com.streamvault.core.navigation.NavigationOptions
import com.streamvault.domain.model.ContentType

internal fun NavGraphBuilder.registerHomeGraph(
    actions: NavigationActions,
    catalogDetailActions: CatalogDetailNavigationActions,
    onTopLevelDestinationRequested: (AppDestination) -> Unit
) {
    composable(AppRoutePatterns.HOME) {
        DashboardScreen(
            onNavigate = { route ->
                AppRouteCodec.decode(route)?.let(onTopLevelDestinationRequested)
            },
            onAddProvider = {
                actions.navigate(AppDestination.ProviderSetup())
            },
            onRecentChannelClick = { channel, combinedProfileId ->
                actions.openPlayer(
                    channel.toLivePlayerRequest(
                        categoryId = com.streamvault.domain.model.VirtualCategoryIds.RECENT,
                        providerId = channel.providerId,
                        isVirtual = true,
                        combinedProfileId = combinedProfileId,
                        returnDestination = AppDestination.Home
                    )
                )
            },
            onFavoriteChannelClick = { channel, combinedProfileId ->
                actions.openPlayer(
                    channel.toLivePlayerRequest(
                        categoryId = com.streamvault.domain.model.VirtualCategoryIds.FAVORITES,
                        providerId = channel.providerId,
                        isVirtual = true,
                        combinedProfileId = combinedProfileId,
                        returnDestination = AppDestination.Home
                    )
                )
            },
            onMovieClick = { movie ->
                catalogDetailActions.openMovieDetail(movie, AppDestination.Home)
            },
            onSeriesClick = { series ->
                catalogDetailActions.openSeriesDetail(series, AppDestination.Home)
            },
            onPlaybackHistoryClick = { history ->
                when (history.contentType) {
                    ContentType.SERIES -> actions.navigate(
                        AppDestination.SeriesDetail(history.contentId, AppDestination.Home),
                        NavigationOptions(launchSingleTop = true)
                    )
                    ContentType.LIVE,
                    ContentType.MOVIE,
                    ContentType.VOD,
                    ContentType.SERIES_EPISODE -> actions.openPlayer(
                        history.toPlayerNavigationRequest(AppDestination.Home)
                    )
                }
            },
            currentRoute = AppRoutePatterns.HOME
        )
    }
}
