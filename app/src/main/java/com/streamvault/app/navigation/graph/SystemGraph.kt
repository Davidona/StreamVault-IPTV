package com.streamvault.app.navigation.graph

import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.streamvault.app.navigation.AppRouteCodec
import com.streamvault.app.navigation.AppRoutePatterns
import com.streamvault.app.ui.screens.downloads.DownloadsScreen
import com.streamvault.app.ui.screens.plugins.PluginsScreen
import com.streamvault.app.ui.screens.settings.SettingsScreen
import com.streamvault.feature.settings.parental.ParentalControlGroupScreen
import com.streamvault.core.navigation.AppDestination
import com.streamvault.core.navigation.NavigationActions
import com.streamvault.core.navigation.NavigationOptions

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

    composable(
        route = AppRoutePatterns.SETTINGS_DESTINATION,
        arguments = listOf(
            navArgument("backupUri") { type = NavType.StringType; defaultValue = "" }
        )
    ) { backStackEntry ->
        val backupUri = backStackEntry.arguments?.getString("backupUri")?.takeIf { it.isNotBlank() }
        SettingsScreen(
            onNavigate = { route ->
                AppRouteCodec.decode(route)?.let(onTopLevelDestinationRequested)
            },
            onAddProvider = dropUnlessResumed {
                actions.navigate(AppDestination.ProviderSetup())
            },
            onEditProvider = { provider ->
                actions.navigate(
                    AppDestination.ProviderSetup(providerId = provider.id),
                    NavigationOptions(launchSingleTop = true)
                )
            },
            onNavigateToParentalControl = { providerId ->
                actions.navigate(
                    AppDestination.ParentalControlGroups(providerId),
                    NavigationOptions(launchSingleTop = true)
                )
            },
            currentRoute = AppRoutePatterns.SETTINGS,
            initialBackupImportUri = backupUri
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

    composable(
        route = AppRoutePatterns.PARENTAL_CONTROL_GROUPS,
        arguments = listOf(
            navArgument("providerId") { type = NavType.LongType }
        )
    ) {
        ParentalControlGroupScreen(
            currentRoute = AppRoutePatterns.SETTINGS,
            onNavigate = { route ->
                AppRouteCodec.decode(route)?.let(onTopLevelDestinationRequested)
            },
            onBack = { actions.back() }
        )
    }
}
