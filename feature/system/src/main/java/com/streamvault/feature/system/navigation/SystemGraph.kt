package com.streamvault.feature.system.navigation

import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.streamvault.core.navigation.AppDestination
import com.streamvault.core.navigation.NavigationActions
import com.streamvault.core.navigation.NavigationOptions
import com.streamvault.feature.system.api.SystemScaffoldContent
import com.streamvault.feature.system.presentation.downloads.DownloadsScreen
import com.streamvault.feature.system.presentation.plugins.PluginsScreen
import com.streamvault.feature.system.presentation.welcome.WelcomeScreen

fun NavGraphBuilder.registerSystemGraph(
    actions: NavigationActions,
    startupReady: Boolean,
    onStartupNavigationRequested: (AppDestination) -> Unit,
    scaffold: SystemScaffoldContent,
) {
    composable(SystemRoutePatterns.WELCOME) {
        WelcomeScreen(
            onNavigateToHome = dropUnlessResumed {
                onStartupNavigationRequested(AppDestination.Welcome)
            },
            startupReady = startupReady,
            onNavigateToSetup = dropUnlessResumed {
                actions.navigate(
                    AppDestination.ProviderSetup(),
                    NavigationOptions(popUpTo = AppDestination.Welcome, inclusive = true),
                )
            },
        )
    }
    composable(SystemRoutePatterns.DOWNLOADS) {
        DownloadsScreen(scaffold = scaffold)
    }
    composable(SystemRoutePatterns.PLUGINS) {
        PluginsScreen(scaffold = scaffold)
    }
}
