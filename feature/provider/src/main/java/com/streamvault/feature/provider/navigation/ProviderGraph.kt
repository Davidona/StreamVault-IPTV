package com.streamvault.feature.provider.navigation

import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.streamvault.core.navigation.AppDestination
import com.streamvault.core.navigation.NavigationActions
import com.streamvault.core.navigation.NavigationOptions
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
                // All create-provider protocols finish through this shared route.
                // Only edits return to the screen that launched setup.
                completeProviderSetup(actions, isEditing = providerId != null) {
                    onStartupNavigationRequested(AppDestination.ProviderSetup())
                }
            }
        )
    }
}

internal fun completeProviderSetup(
    actions: NavigationActions,
    isEditing: Boolean,
    onStartupNavigationRequested: () -> Unit
) {
    if (!isEditing) {
        actions.navigate(
            AppDestination.Home,
            NavigationOptions(
                launchSingleTop = true,
                popUpTo = AppDestination.ProviderSetup(),
                inclusive = true
            )
        )
    } else if (!actions.back()) {
        onStartupNavigationRequested()
    }
}
