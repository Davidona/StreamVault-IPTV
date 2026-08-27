package com.streamvault.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.streamvault.app.playback.rememberPlaybackPlatformHost
import com.streamvault.core.navigation.AppDestination

@Composable
fun AppNavigation(
    coordinator: AppNavigationCoordinator,
    navController: NavHostController = rememberNavController()
) {
    val navigator = remember(navController) { NavControllerNavigator(navController) }
    val state by coordinator.state.collectAsStateWithLifecycle()
    val pending by coordinator.pendingCommand.collectAsStateWithLifecycle()
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val playbackPlatformHost = rememberPlaybackPlatformHost()

    NavigationCommandEffect(
        pending = pending,
        currentBackStackEntry = currentBackStackEntry,
        navigator = navigator,
        acknowledge = coordinator::acknowledge
    )
    DestinationResumedEffect(currentBackStackEntry, coordinator::onDestinationResumed)
    CatalogLayoutReconciliationEffect(
        state = state,
        currentBackStackEntry = currentBackStackEntry,
        onTopLevelDestinationRequested = coordinator::requestTopLevelNavigation
    )

    AppNavHost(
        navController = navController,
        actions = navigator,
        catalogDetailActions = navigator,
        payloads = navigator,
        playbackPlatformHost = playbackPlatformHost,
        startupReady = state.startupTarget != null,
        onStartupNavigationRequested = coordinator::requestStartupNavigation,
        onTopLevelDestinationRequested = coordinator::requestTopLevelNavigation
    )
}

@Composable
internal fun CatalogLayoutReconciliationEffect(
    state: AppNavigationState,
    currentBackStackEntry: NavBackStackEntry?,
    onTopLevelDestinationRequested: (AppDestination) -> Unit
) {
    LaunchedEffect(
        state.catalogLayout,
        state.lastSplitCatalogType,
        state.splitPreferenceReady,
        currentBackStackEntry?.destination?.route
    ) {
        val layout = state.catalogLayout ?: return@LaunchedEffect
        val currentDestination = currentBackStackEntry?.destination?.route
            ?.let(AppRouteCodec::decode)
            ?: return@LaunchedEffect
        val resolvedDestination = resolveCatalogDestination(
            layout = layout,
            requested = currentDestination,
            lastSplitCatalogType = state.lastSplitCatalogType,
            splitPreferenceReady = state.splitPreferenceReady
        )
        if (resolvedDestination != currentDestination) {
            onTopLevelDestinationRequested(resolvedDestination)
        }
    }
}

@Composable
internal fun NavigationCommandEffect(
    pending: PendingNavigationCommand?,
    currentBackStackEntry: NavBackStackEntry?,
    navigator: NavControllerNavigator,
    acknowledge: (Long) -> Unit
) {
    var executingCommandId by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(pending?.id, currentBackStackEntry) {
        val command = pending ?: return@LaunchedEffect
        val entry = currentBackStackEntry ?: return@LaunchedEffect
        if (executingCommandId == command.id) return@LaunchedEffect
        executingCommandId = command.id
        entry.lifecycle.awaitResumed()
        if (navigator.execute(command.command)) {
            acknowledge(command.id)
        } else {
            executingCommandId = null
        }
    }
}

@Composable
internal fun DestinationResumedEffect(
    currentBackStackEntry: NavBackStackEntry?,
    onDestinationResumed: (AppDestination) -> Unit
) {
    LaunchedEffect(currentBackStackEntry) {
        val entry = currentBackStackEntry ?: return@LaunchedEffect
        entry.lifecycle.awaitResumed()
        entry.destination.route
            ?.let(AppRouteCodec::decode)
            ?.let(onDestinationResumed)
    }
}
