package com.streamvault.app.navigation

import android.net.Uri
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation.NavHostController
import androidx.navigation.NavOptionsBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.streamvault.app.ui.model.isArchivePlayable
import com.streamvault.core.navigation.PlayerNavigationRequest
import com.streamvault.core.navigation.NavigationCommand
import com.streamvault.core.navigation.NavigationOptions
import com.streamvault.core.navigation.AppDestination
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.Episode
import com.streamvault.domain.model.Movie
import com.streamvault.domain.repository.ChannelRepository
import com.streamvault.app.ui.screens.dashboard.DashboardScreen
import com.streamvault.app.ui.screens.multiview.MultiViewScreen
import com.streamvault.app.ui.screens.home.HomeScreen
import com.streamvault.app.ui.screens.movies.MoviesScreen
import com.streamvault.app.ui.screens.player.PlayerScreen
import com.streamvault.app.ui.screens.plugins.PluginsScreen
import com.streamvault.app.ui.screens.provider.ProviderSetupScreen
import com.streamvault.app.ui.screens.series.SeriesScreen
import com.streamvault.app.ui.screens.vod.VodScreen
import com.streamvault.app.ui.screens.settings.SettingsScreen
import com.streamvault.app.ui.screens.welcome.WelcomeScreen
import com.streamvault.app.ui.screens.downloads.DownloadsScreen
import com.streamvault.app.MainActivity
import com.streamvault.domain.model.AppLandingDestination
import com.streamvault.domain.model.AppTopLevelDestination
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.CatalogLayout
import com.streamvault.domain.model.MovieDetailPresentationHint
import com.streamvault.domain.model.ActiveLiveSource
import com.streamvault.domain.model.Series
import com.streamvault.domain.model.SeriesDetailPresentationHint
import com.streamvault.domain.model.VirtualCategoryIds
import java.io.Serializable
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine


@Composable
fun AppNavigation(mainActivity: MainActivity) {
    val navController = rememberNavController()
    val navigator = remember(navController) { NavControllerNavigator(navController) }
    val currentBackStackEntry = navController.currentBackStackEntryAsState().value
    val activeProvider = mainActivity.providerRepository.getActiveProvider()
        .collectAsStateWithLifecycle(initialValue = null)
        .value
    var lastSplitCatalogType by remember(activeProvider?.id) { mutableStateOf(ContentType.MOVIE) }
    var loadedSplitPreferenceProviderId by remember { mutableStateOf<Long?>(null) }
    val navigationScope = rememberCoroutineScope()
    LaunchedEffect(activeProvider?.id) {
        val providerId = activeProvider?.id ?: return@LaunchedEffect
        loadedSplitPreferenceProviderId = null
        lastSplitCatalogType = mainActivity.preferencesRepository
            .getLastSplitCatalogType(providerId)
            .first()
        loadedSplitPreferenceProviderId = providerId
    }
    val externalNavigationRequest = mainActivity.externalNavigationRequestFlow.collectAsStateWithLifecycle().value
    val topLevelDestinations = mainActivity.preferencesRepository.appTopLevelDestinations
        .collectAsStateWithLifecycle(initialValue = AppTopLevelDestination.defaultOrder)
        .value
    val appLandingDestination = mainActivity.preferencesRepository.appLandingDestination
        .collectAsStateWithLifecycle(initialValue = null)
        .value
    val resolvedLandingDestination = appLandingDestination?.let { landingDestination ->
        AppTopLevelDestination.resolveLandingDestination(
            preferred = landingDestination,
            destinations = topLevelDestinations
        )
    }
    // Route to land on when leaving the Welcome screen. For the "first favorite" / "last watched"
    // landings this resolves to the Live TV tab (via toAppRoute); the channel itself is opened on top
    // afterwards (see startupPlayerRequest below). This is computed immediately with no channel
    // lookup, so Welcome is never held open long enough for a quick Back press to fall through the
    // start destination and exit the app.
    val startupRoute: String? = resolvedLandingDestination?.toAppRoute()

    // Deferred auto-play request for the live landings. Resolved off the Welcome screen so the app
    // stays interactive on Live TV while the channel is looked up.
    val startupPlayerRequest by produceState<PlayerNavigationRequest?>(
        initialValue = null,
        resolvedLandingDestination
    ) {
        val landing = resolvedLandingDestination
        value = if (landing != null && requiresResolvedStartupTarget(landing)) {
            resolveStartupPlayerRequest(mainActivity, landing)
        } else {
            null
        }
    }
    var startupPlayerHandled by remember { mutableStateOf(false) }

    fun navigateToStartupTarget(popUpRoute: String): Boolean {
        val route = startupRoute ?: return false
        val destination = AppRouteCodec.decode(route) ?: return false
        val popUpDestination = AppRouteCodec.decode(popUpRoute)
        return navigator.navigateIfResumed(
            destination,
            NavigationOptions(popUpTo = popUpDestination, inclusive = true)
        )
    }

    // Once the live landing has placed us on the Live TV tab, open the resolved channel on top of it.
    // Opening on top of Live TV (instead of replacing it) means Back from the player returns into the
    // app rather than exiting. Guarded so it fires once, and only while still on the freshly-landed
    // Live TV tab, to avoid hijacking navigation after the user has started interacting.
    LaunchedEffect(startupPlayerRequest, currentBackStackEntry) {
        if (startupPlayerHandled) return@LaunchedEffect
        val request = startupPlayerRequest ?: return@LaunchedEffect
        val entry = currentBackStackEntry ?: return@LaunchedEffect
        val route = entry.destination?.route
        if (route != Routes.LIVE_TV_DESTINATION && route != Routes.LIVE_TV) return@LaunchedEffect
        entry.lifecycle.awaitResumed()
        if (navigator.execute(NavigationCommand.OpenPlayer(request))) {
            startupPlayerHandled = true
        }
    }

    ExternalNavigationHost(
        request = externalNavigationRequest,
        currentBackStackEntry = currentBackStackEntry,
        navigator = navigator,
        onRequestHandled = mainActivity::clearExternalNavigationRequest
    )

    // NAV-M02/NAV-H02: Single helper replacing repeated tab lambdas without serializing
    // each tab's full UI tree into saved state on every switch.
    fun tabNavigate(route: String) {
        val entry = navController.currentBackStackEntry ?: return
        if (!entry.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        val currentRoute = entry.destination?.route
        val provider = activeProvider
        if (
            provider?.catalogLayout == com.streamvault.domain.model.CatalogLayout.SPLIT &&
            route in setOf(Routes.MOVIES, Routes.SERIES)
        ) {
            val selectedType = if (route == Routes.SERIES) ContentType.SERIES else ContentType.MOVIE
            lastSplitCatalogType = selectedType
            navigationScope.launch {
                mainActivity.preferencesRepository.setLastSplitCatalogType(provider.id, selectedType)
            }
        }
        val resolvedRoute = resolveCatalogRoute(
            layout = provider?.catalogLayout,
            requestedRoute = route,
            lastSplitCatalogType = lastSplitCatalogType,
            splitPreferenceReady = provider == null || loadedSplitPreferenceProviderId == provider.id
        )
        if (currentRoute == resolvedRoute || currentRoute?.startsWith("$resolvedRoute?") == true) return

        AppRouteCodec.decode(resolvedRoute)?.let { destination ->
            navigator.navigateIfResumed(
                destination,
                NavigationOptions(
                    launchSingleTop = true,
                    restoreState = true,
                    saveState = true,
                    popUpTo = AppDestination.Welcome
                )
            )
        }
    }

    LaunchedEffect(activeProvider?.id, activeProvider?.catalogLayout, currentBackStackEntry?.destination?.route) {
        val provider = activeProvider ?: return@LaunchedEffect
        val route = currentBackStackEntry?.destination?.route ?: return@LaunchedEffect
        val resolvedRoute = resolveCatalogRoute(
            layout = provider.catalogLayout,
            requestedRoute = route,
            lastSplitCatalogType = lastSplitCatalogType,
            splitPreferenceReady = loadedSplitPreferenceProviderId == provider.id
        )
        if (resolvedRoute != route) {
            tabNavigate(resolvedRoute)
        }
    }

    AppNavigationGraph(
        navController = navController,
        navigator = navigator,
        startupRoute = startupRoute,
        navigateToStartupTarget = { popUpRoute -> navigateToStartupTarget(popUpRoute) },
        tabNavigate = { route -> tabNavigate(route) }
    )
}
