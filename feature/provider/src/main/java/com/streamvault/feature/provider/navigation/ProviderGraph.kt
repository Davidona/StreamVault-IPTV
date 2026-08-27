package com.streamvault.feature.provider.navigation

import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.streamvault.core.navigation.AppDestination
import com.streamvault.core.navigation.NavigationActions
import com.streamvault.feature.provider.api.ProviderBackupPreviewContent
import com.streamvault.feature.provider.setup.ProviderSetupScreen

fun NavGraphBuilder.registerProviderGraph(
    actions: NavigationActions,
    startupReady: Boolean,
    onStartupNavigationRequested: (AppDestination) -> Unit,
    backupPreviewContent: ProviderBackupPreviewContent,
) {
    composable(
        route = ProviderRoutePatterns.PROVIDER_SETUP,
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
            backupPreviewContent = backupPreviewContent,
            onProviderAdded = dropUnlessResumed {
                onStartupNavigationRequested(AppDestination.ProviderSetup())
            }
        )
    }
}
