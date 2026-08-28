package com.streamvault.app.navigation.graph

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.streamvault.app.navigation.AppRouteCodec
import com.streamvault.app.navigation.AppRoutePatterns
import com.streamvault.app.ui.screens.downloads.DownloadsScreen
import com.streamvault.app.ui.screens.plugins.PluginsScreen
import com.streamvault.core.navigation.AppDestination
import com.streamvault.core.navigation.NavigationActions

internal fun NavGraphBuilder.registerSystemGraph(
    actions: NavigationActions,
    onTopLevelDestinationRequested: (AppDestination) -> Unit
) {
    composable(AppRoutePatterns.DOWNLOADS) {
        DownloadsScreen(
            onNavigate = { route ->
                AppRouteCodec.decode(route)?.let(onTopLevelDestinationRequested)
            },
            currentRoute = AppRoutePatterns.DOWNLOADS
        )
    }

    composable(AppRoutePatterns.PLUGINS) {
        PluginsScreen(
            currentRoute = AppRoutePatterns.PLUGINS,
            onNavigate = { route ->
                AppRouteCodec.decode(route)?.let(onTopLevelDestinationRequested)
            }
        )
    }

}
