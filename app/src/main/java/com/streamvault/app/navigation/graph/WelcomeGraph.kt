package com.streamvault.app.navigation.graph

import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.streamvault.app.navigation.AppRoutePatterns
import com.streamvault.feature.system.presentation.welcome.WelcomeScreen
import com.streamvault.core.navigation.AppDestination
import com.streamvault.core.navigation.NavigationActions
import com.streamvault.core.navigation.NavigationOptions

internal fun NavGraphBuilder.registerWelcomeGraph(
    actions: NavigationActions,
    startupReady: Boolean,
    onStartupNavigationRequested: (AppDestination) -> Unit
) {
    composable(AppRoutePatterns.WELCOME) {
        WelcomeScreen(
            onNavigateToHome = dropUnlessResumed {
                onStartupNavigationRequested(AppDestination.Welcome)
            },
            startupReady = startupReady,
            onNavigateToSetup = dropUnlessResumed {
                actions.navigate(
                    AppDestination.ProviderSetup(),
                    NavigationOptions(popUpTo = AppDestination.Welcome, inclusive = true)
                )
            }
        )
    }
}
