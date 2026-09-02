package com.streamvault.app.navigation.graph

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.streamvault.app.navigation.AppRouteCodec
import com.streamvault.app.navigation.AppRoutePatterns
import com.streamvault.app.navigation.CatalogDetailNavigationActions
import com.streamvault.app.navigation.toPlayerNavigationRequest
import com.streamvault.app.navigation.toLivePlayerRequest
import com.streamvault.app.ui.components.shell.AppNavigationChrome
import com.streamvault.app.ui.components.shell.AppScreenScaffold
import com.streamvault.feature.catalog.api.CatalogDashboardShelfCustomizationContent
import com.streamvault.feature.catalog.api.CatalogNavigationChrome
import com.streamvault.feature.catalog.api.CatalogScaffoldContent
import com.streamvault.feature.catalog.presentation.dashboard.DashboardScreen
import com.streamvault.feature.settings.presentation.DashboardShelfCustomizationDialog
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
            onDestinationRequested = onTopLevelDestinationRequested,
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
            scaffold = homeScaffold(onTopLevelDestinationRequested),
            dashboardShelfCustomizationContent = homeShelfCustomizationContent()
        )
    }
}

private fun homeScaffold(
    onTopLevelDestinationRequested: (AppDestination) -> Unit
): CatalogScaffoldContent = { _, title, subtitle, chrome, topBarVisible, compactHeader, showScreenHeader, content ->
    AppScreenScaffold(
        currentRoute = AppRoutePatterns.HOME,
        onNavigate = { route ->
            AppRouteCodec.decode(route)?.let(onTopLevelDestinationRequested)
        },
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

private fun homeShelfCustomizationContent(): CatalogDashboardShelfCustomizationContent =
    { currentShelves, onDismiss, onSave ->
        DashboardShelfCustomizationDialog(
            currentShelves = currentShelves,
            onDismiss = onDismiss,
            onSave = onSave
        )
    }
