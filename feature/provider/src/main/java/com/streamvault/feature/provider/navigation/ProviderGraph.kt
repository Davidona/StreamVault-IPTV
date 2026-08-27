package com.streamvault.app.navigation.graph

import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.streamvault.app.navigation.AppRoutePatterns
import com.streamvault.app.ui.screens.provider.ProviderSetupScreen
import com.streamvault.core.navigation.AppDestination
import com.streamvault.core.navigation.NavigationActions

internal fun NavGraphBuilder.registerProviderGraph(
    actions: NavigationActions,
    startupReady: Boolean,
    onStartupNavigationRequested: (AppDestination) -> Unit
) {
    composable(
        route = AppRoutePatterns.PROVIDER_SETUP,
        arguments = listOf(
            navArgument("providerId") { type = NavType.LongType; defaultValue = -1L },
            navArgument("importUri") { type = NavType.StringType; defaultValue = "" }
        )
    ) { backStackEntry ->
        val providerId = backStackEntry.arguments?.getLong("providerId")?.takeIf { it != -1L }
        val importUri = backStackEntry.arguments?.getString("importUri")?.takeIf { it.isNotBlank() }
        ProviderSetupScreen(
            editProviderId = providerId,
            initialImportUri = importUri,
            onBack = { actions.back() },
            onProviderAdded = dropUnlessResumed {
                onStartupNavigationRequested(AppDestination.ProviderSetup())
            }
        )
    }
}
