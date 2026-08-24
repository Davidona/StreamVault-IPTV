package com.streamvault.app.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import com.streamvault.app.navigation.graph.registerCatalogGraph
import com.streamvault.app.navigation.graph.registerHomeGraph
import com.streamvault.app.navigation.graph.registerLiveGraph
import com.streamvault.app.navigation.graph.registerPlayerGraph
import com.streamvault.app.navigation.graph.registerProviderGraph
import com.streamvault.app.navigation.graph.registerSystemGraph
import com.streamvault.app.navigation.graph.registerWelcomeGraph
import com.streamvault.core.navigation.AppDestination
import com.streamvault.core.navigation.NavigationActions
import com.streamvault.feature.playback.api.PlaybackPlatformHost

@Composable
internal fun AppNavHost(
    navController: NavHostController,
    actions: NavigationActions,
    catalogDetailActions: CatalogDetailNavigationActions,
    payloads: AppNavigationPayloads,
    playbackPlatformHost: PlaybackPlatformHost?,
    startupReady: Boolean,
    onStartupNavigationRequested: (popUpTo: AppDestination) -> Unit,
    onTopLevelDestinationRequested: (AppDestination) -> Unit
) {
    NavHost(
        navController = navController,
        startDestination = AppRoutePatterns.WELCOME
    ) {
        registerWelcomeGraph(actions, startupReady, onStartupNavigationRequested)
        registerProviderGraph(actions, startupReady, onStartupNavigationRequested)
        registerHomeGraph(actions, catalogDetailActions, onTopLevelDestinationRequested)
        registerLiveGraph(actions, onTopLevelDestinationRequested)
        registerCatalogGraph(
            actions,
            catalogDetailActions,
            payloads,
            onTopLevelDestinationRequested
        )
        registerPlayerGraph(actions, payloads)
        registerSystemGraph(actions, onTopLevelDestinationRequested)
    }
}
