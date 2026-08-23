package com.streamvault.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavBackStackEntry
import com.streamvault.core.navigation.AppDestination
import com.streamvault.core.navigation.NavigationCommand
import com.streamvault.core.navigation.NavigationOptions

@Composable
internal fun ExternalNavigationHost(
    request: ExternalNavigationRequest?,
    currentBackStackEntry: NavBackStackEntry?,
    navigator: NavControllerNavigator,
    onRequestHandled: () -> Unit
) {
    LaunchedEffect(request, currentBackStackEntry) {
        val entry = currentBackStackEntry ?: return@LaunchedEffect
        entry.lifecycle.awaitResumed()
        when (request) {
            is ExternalNavigationRequest.Player -> {
                if (navigator.execute(NavigationCommand.OpenPlayer(request.request))) {
                    onRequestHandled()
                }
            }

            is ExternalNavigationRequest.Destination -> {
                val destination = AppRouteCodec.decode(request.destination.toRoute())
                    ?: AppDestination.Home
                if (navigator.execute(NavigationCommand.Navigate(destination, NavigationOptions(launchSingleTop = true)))) {
                    onRequestHandled()
                }
            }

            is ExternalNavigationRequest.ImportM3u -> {
                if (navigator.execute(NavigationCommand.Navigate(
                        AppDestination.ProviderSetup(importUri = request.uri),
                        NavigationOptions(launchSingleTop = true)
                    ))) {
                    onRequestHandled()
                }
            }

            is ExternalNavigationRequest.ImportBackup -> {
                if (navigator.execute(NavigationCommand.Navigate(
                        AppDestination.Settings(backupUri = request.uri),
                        NavigationOptions(launchSingleTop = true)
                    ))) {
                    onRequestHandled()
                }
            }

            is ExternalNavigationRequest.Search -> {
                if (navigator.execute(NavigationCommand.Navigate(
                        AppDestination.Search(request.query),
                        NavigationOptions(launchSingleTop = true)
                    ))) {
                    onRequestHandled()
                }
            }

            null -> Unit
        }
    }
}
