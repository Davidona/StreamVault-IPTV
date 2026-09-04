package com.streamvault.app.navigation.graph

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.streamvault.app.navigation.AppRouteCodec
import com.streamvault.app.navigation.AppRoutePatterns
import com.streamvault.app.ui.screens.plugins.PluginsScreen
import com.streamvault.app.ui.components.shell.AppNavigationChrome
import com.streamvault.app.ui.components.shell.AppScreenScaffold
import com.streamvault.core.navigation.AppDestination
import com.streamvault.core.navigation.NavigationActions
import com.streamvault.feature.system.api.SystemScaffoldContent
import com.streamvault.feature.system.presentation.downloads.DownloadsScreen

internal fun NavGraphBuilder.registerSystemGraph(
    actions: NavigationActions,
    onTopLevelDestinationRequested: (AppDestination) -> Unit
) {
    composable(AppRoutePatterns.DOWNLOADS) {
        DownloadsScreen(
            scaffold = appSystemScaffold(onTopLevelDestinationRequested)
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

private fun appSystemScaffold(
    onTopLevelDestinationRequested: (AppDestination) -> Unit
): SystemScaffoldContent = { currentDestination, title, subtitle, compactHeader, showScreenHeader, content ->
    AppScreenScaffold(
        currentRoute = AppRouteCodec.encode(currentDestination),
        onNavigate = { route -> AppRouteCodec.decode(route)?.let(onTopLevelDestinationRequested) },
        title = title,
        subtitle = subtitle,
        navigationChrome = AppNavigationChrome.TopBar,
        compactHeader = compactHeader,
        showScreenHeader = showScreenHeader,
        content = content
    )
}
