package com.streamvault.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController

@Composable
internal fun ExternalNavigationHost(
    request: ExternalNavigationRequest?,
    currentBackStackEntry: NavBackStackEntry?,
    navController: NavHostController,
    onRequestHandled: () -> Unit
) {
    LaunchedEffect(request, currentBackStackEntry) {
        val entry = currentBackStackEntry ?: return@LaunchedEffect
        entry.lifecycle.awaitResumed()
        when (request) {
            is ExternalNavigationRequest.Player -> {
                if (navController.navigateToExternalPlayer(request.request)) {
                    onRequestHandled()
                }
            }

            is ExternalNavigationRequest.Destination -> {
                if (navController.navigateIfResumed(request.destination.toRoute()) { launchSingleTop = true }) {
                    onRequestHandled()
                }
            }

            is ExternalNavigationRequest.ImportM3u -> {
                if (navController.navigateIfResumed(Routes.providerSetup(importUri = request.uri)) { launchSingleTop = true }) {
                    onRequestHandled()
                }
            }

            is ExternalNavigationRequest.ImportBackup -> {
                if (navController.navigateIfResumed(Routes.settings(backupUri = request.uri)) { launchSingleTop = true }) {
                    onRequestHandled()
                }
            }

            is ExternalNavigationRequest.Search -> {
                if (navController.navigateIfResumed(Routes.search(request.query)) { launchSingleTop = true }) {
                    onRequestHandled()
                }
            }

            null -> Unit
        }
    }
}
