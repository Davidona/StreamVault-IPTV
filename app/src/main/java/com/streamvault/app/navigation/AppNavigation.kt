package com.streamvault.app.navigation

import android.net.Uri
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.streamvault.app.ui.screens.settings.SETTINGS_CATEGORY_DOWNLOADS
import com.streamvault.app.ui.screens.settings.SettingsScreen
import com.streamvault.app.ui.screens.welcome.WelcomeScreen
import com.streamvault.app.MainActivity
import java.io.Serializable
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine


private const val PLAYER_REQUEST_KEY = "player_request"
private const val TAG = "AppNavigation"

data class PlayerNavigationRequest(
    val streamUrl: String,
    val title: String,
    val channelId: String? = null,
    val internalId: Long = -1L,
    val categoryId: Long? = null,
    val providerId: Long? = null,
    val isVirtual: Boolean = false,
    val combinedProfileId: Long? = null,
    val combinedSourceFilterProviderId: Long? = null,
    val contentType: String = "LIVE",
    val artworkUrl: String? = null,
    val archiveStartMs: Long? = null,
    val archiveEndMs: Long? = null,
    val archiveTitle: String? = null,
    val returnRoute: String? = null,
    val seriesId: Long? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val episodeId: Long? = null
) : Serializable

object Routes {
    const val PROVIDER_SETUP = "provider_setup?providerId={providerId}&importUri={importUri}"
    const val HOME = "home"
    const val LIVE_TV = "live_tv"
    const val LIVE_TV_DESTINATION = "live_tv?categoryId={categoryId}"
    const val MOVIES = "movies"
    const val SERIES = "series"
    const val EPG = "epg"
    const val EPG_DESTINATION = "epg?categoryId={categoryId}&anchorTime={anchorTime}&favoritesOnly={favoritesOnly}"
    const val SETTINGS = "settings"
    const val SETTINGS_DESTINATION = "settings?backupUri={backupUri}"
    const val SETTINGS_DOWNLOADS = "downloads_settings"
    const val PLUGINS = "plugins"
    const val PLAYER = "player"
    const val SEARCH = "search"
    const val SEARCH_DESTINATION = "search?query={query}"
    const val MOVIE_DETAIL = "movie_detail/{movieId}?returnRoute={returnRoute}"
    const val SERIES_DETAIL = "series_detail/{seriesId}?returnRoute={returnRoute}"
    const val WELCOME = "welcome"
    const val PARENTAL_CONTROL_GROUPS = "parental_control_groups/{providerId}"
    const val MULTI_VIEW = "multi_view"


    fun providerSetup(providerId: Long? = null, importUri: String? = null): String {
        val encodedImportUri = Uri.encode(importUri ?: "")
        return "provider_setup?providerId=${providerId ?: -1L}&importUri=$encodedImportUri"
    }
    fun liveTv(categoryId: Long? = null) = if (categoryId == null) LIVE_TV else "$LIVE_TV?categoryId=$categoryId"
    fun epg(categoryId: Long? = null, anchorTime: Long? = null, favoritesOnly: Boolean? = null): String {
        val resolvedCategoryId = categoryId ?: -1L
        val resolvedAnchorTime = anchorTime ?: -1L
        val resolvedFavoritesOnly = favoritesOnly ?: false
        return "$EPG?categoryId=$resolvedCategoryId&anchorTime=$resolvedAnchorTime&favoritesOnly=$resolvedFavoritesOnly"
    }

    fun livePlayer(
        channel: Channel,
        categoryId: Long? = channel.categoryId,
        providerId: Long? = channel.providerId,
        isVirtual: Boolean = false,
        combinedProfileId: Long? = null,
        combinedSourceFilterProviderId: Long? = null,
        returnRoute: String? = null
    ): PlayerNavigationRequest {
        val effectiveCategoryId = categoryId ?: ChannelRepository.ALL_CHANNELS_ID
        return player(
            streamUrl = channel.streamUrl,
            title = channel.name,
            channelId = channel.epgChannelId,
            internalId = channel.id,
            categoryId = effectiveCategoryId,
            providerId = providerId,
            isVirtual = isVirtual,
            combinedProfileId = combinedProfileId,
            combinedSourceFilterProviderId = combinedSourceFilterProviderId,
            contentType = "LIVE",
            returnRoute = returnRoute
        )
    }

    fun moviePlayer(movie: Movie): PlayerNavigationRequest {
        return player(
            streamUrl = movie.streamUrl,
            title = movie.name,
            internalId = movie.id,
            categoryId = movie.categoryId,
            providerId = movie.providerId,
            contentType = "MOVIE",
            artworkUrl = movie.posterUrl ?: movie.backdropUrl
        )
    }

    fun episodePlayer(episode: Episode): PlayerNavigationRequest {
        return player(
            streamUrl = episode.streamUrl,
            title = "${episode.title} - S${episode.seasonNumber}E${episode.episodeNumber}",
            internalId = episode.id,
            providerId = episode.providerId,
            contentType = "SERIES_EPISODE",
            artworkUrl = episode.coverUrl,
            seriesId = episode.seriesId.takeIf { it > 0L },
            seasonNumber = episode.seasonNumber,
            episodeNumber = episode.episodeNumber,
            episodeId = episode.episodeId.takeIf { it > 0L }
        )
    }

    fun search(query: String? = null): String =
        if (query.isNullOrBlank()) SEARCH else "$SEARCH?query=${Uri.encode(query)}"

    fun settings(backupUri: String? = null): String =
        if (backupUri.isNullOrBlank()) SETTINGS else "$SETTINGS?backupUri=${Uri.encode(backupUri)}"

    fun player(
        streamUrl: String,
        title: String,
        channelId: String? = null,
        internalId: Long = -1L,
        categoryId: Long? = null,
        providerId: Long? = null,
        isVirtual: Boolean = false,
        combinedProfileId: Long? = null,
        combinedSourceFilterProviderId: Long? = null,
        contentType: String = "LIVE",
        artworkUrl: String? = null,
        archiveStartMs: Long? = null,
        archiveEndMs: Long? = null,
        archiveTitle: String? = null,
        returnRoute: String? = null,
        seriesId: Long? = null,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        episodeId: Long? = null
    ): PlayerNavigationRequest {
        return PlayerNavigationRequest(
            streamUrl = streamUrl,
            title = title,
            channelId = channelId,
            internalId = internalId,
            categoryId = categoryId,
            providerId = providerId,
            isVirtual = isVirtual,
            combinedProfileId = combinedProfileId,
            combinedSourceFilterProviderId = combinedSourceFilterProviderId,
            contentType = contentType,
            artworkUrl = artworkUrl,
            archiveStartMs = archiveStartMs,
            archiveEndMs = archiveEndMs,
            archiveTitle = archiveTitle,
            returnRoute = returnRoute,
            seriesId = seriesId,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            episodeId = episodeId
        )
    }

    fun movieDetail(movieId: Long, returnRoute: String? = null) =
        "movie_detail/$movieId?returnRoute=${Uri.encode(returnRoute ?: "")}"
    fun seriesDetail(seriesId: Long, returnRoute: String? = null) =
        "series_detail/$seriesId?returnRoute=${Uri.encode(returnRoute ?: "")}"
    fun parentalControlGroups(providerId: Long) = "parental_control_groups/$providerId"
}

/** Accepts app-supported media schemes while still rejecting obviously unsafe ones. */
private fun isStreamUrlSafe(url: String?): Boolean {
    if (url.isNullOrBlank()) return false
    val scheme = url.substringBefore("://").lowercase()
    return scheme in setOf("http", "https", "rtsp", "rtmp", "rtsps", "mms", "xtream", "stalker", "content", "file")
}

internal fun safePlayerNavigationRequest(request: PlayerNavigationRequest?): PlayerNavigationRequest? =
    request?.takeIf { isStreamUrlSafe(it.streamUrl) }

/** Navigate only when the current destination is fully resumed – prevents double-navigation during transitions. */
private fun NavHostController.navigateIfResumed(route: String, builder: NavOptionsBuilder.() -> Unit = {}): Boolean {
    if (currentBackStackEntry?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) != true) return false
    navigate(route, builder)
    return true
}

private suspend fun Lifecycle.awaitResumed() {
    if (currentState.isAtLeast(Lifecycle.State.RESUMED)) return
    suspendCancellableCoroutine { continuation ->
        lateinit var observer: LifecycleEventObserver
        observer = LifecycleEventObserver { _, _ ->
            when {
                currentState.isAtLeast(Lifecycle.State.RESUMED) -> {
                    removeObserver(observer)
                    if (continuation.isActive) continuation.resume(Unit)
                }
                currentState == Lifecycle.State.DESTROYED -> {
                    removeObserver(observer)
                    continuation.cancel()
                }
            }
        }
        addObserver(observer)
        continuation.invokeOnCancellation { removeObserver(observer) }
    }
}

private fun NavHostController.navigateToPlayer(request: PlayerNavigationRequest): Boolean {
    if (currentBackStackEntry?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) != true) return false
    currentBackStackEntry?.savedStateHandle?.set(PLAYER_REQUEST_KEY, request)
    navigate(Routes.PLAYER) { launchSingleTop = true }
    return true
}

private fun NavHostController.navigateToExternalPlayer(request: PlayerNavigationRequest): Boolean {
    if (currentBackStackEntry?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) != true) return false
    currentBackStackEntry?.savedStateHandle?.set(PLAYER_REQUEST_KEY, request)
    navigate(Routes.PLAYER) { launchSingleTop = true }
    return true
}

@Composable
fun AppNavigation(mainActivity: MainActivity) {
    val navController = rememberNavController()
    val currentBackStackEntry = navController.currentBackStackEntryAsState().value
    val externalNavigationRequest = mainActivity.externalNavigationRequestFlow.collectAsStateWithLifecycle().value

    LaunchedEffect(externalNavigationRequest, currentBackStackEntry) {
        val entry = currentBackStackEntry ?: return@LaunchedEffect
        entry.lifecycle.awaitResumed()
        when (val request = externalNavigationRequest) {
            is ExternalNavigationRequest.Player -> {
                if (navController.navigateToExternalPlayer(request.request)) {
                    mainActivity.clearExternalNavigationRequest()
                }
            }

            is ExternalNavigationRequest.Destination -> {
                if (navController.navigateIfResumed(request.destination.toRoute()) { launchSingleTop = true }) {
                    mainActivity.clearExternalNavigationRequest()
                }
            }

            is ExternalNavigationRequest.ImportM3u -> {
                if (navController.navigateIfResumed(Routes.providerSetup(importUri = request.uri)) { launchSingleTop = true }) {
                    mainActivity.clearExternalNavigationRequest()
                }
            }

            is ExternalNavigationRequest.ImportBackup -> {
                if (navController.navigateIfResumed(Routes.settings(backupUri = request.uri)) { launchSingleTop = true }) {
                    mainActivity.clearExternalNavigationRequest()
                }
            }

            is ExternalNavigationRequest.Search -> {
                if (navController.navigateIfResumed(Routes.search(request.query)) { launchSingleTop = true }) {
                    mainActivity.clearExternalNavigationRequest()
                }
            }

            null -> Unit
        }
    }

    // NAV-M02/NAV-H02: Single helper replacing repeated tab lambdas without serializing
    // each tab's full UI tree into saved state on every switch.
    fun tabNavigate(route: String) {
        val entry = navController.currentBackStackEntry ?: return
        if (!entry.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        val currentRoute = entry.destination?.route
        if (currentRoute == route || currentRoute?.startsWith("$route?") == true) return

        navController.navigate(route) {
            popUpTo(navController.graph.startDestinationId) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    NavHost(
        navController = navController,
        startDestination = Routes.WELCOME
    ) {
        composable(Routes.WELCOME) {
            WelcomeScreen(
                onNavigateToHome = dropUnlessResumed {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.WELCOME) { inclusive = true }
                    }
                },
                onNavigateToSetup = dropUnlessResumed {
                    navController.navigate(Routes.providerSetup()) {
                        popUpTo(Routes.WELCOME) { inclusive = true }
                    }
                }
            )
        }

        composable(
            route = Routes.PROVIDER_SETUP,
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
                onBack = { navController.popBackStack() },
                onProviderAdded = dropUnlessResumed {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.PROVIDER_SETUP) { inclusive = true }
                    }
                }
            )
        }
// ...

        composable(Routes.HOME) {
            DashboardScreen(
                onNavigate = { route -> tabNavigate(route) },
                onAddProvider = dropUnlessResumed {
                    navController.navigate(Routes.providerSetup(null))
                },
                onRecentChannelClick = { channel, combinedProfileId ->
                    navController.navigateToPlayer(
                        Routes.livePlayer(
                            channel = channel,
                            categoryId = com.streamvault.domain.model.VirtualCategoryIds.RECENT,
                            providerId = channel.providerId,
                            isVirtual = true,
                            combinedProfileId = combinedProfileId,
                            returnRoute = Routes.HOME
                        )
                    )
                },
                onFavoriteChannelClick = { channel, combinedProfileId ->
                    navController.navigateToPlayer(
                        Routes.livePlayer(
                            channel = channel,
                            categoryId = com.streamvault.domain.model.VirtualCategoryIds.FAVORITES,
                            providerId = channel.providerId,
                            isVirtual = true,
                            combinedProfileId = combinedProfileId,
                            returnRoute = Routes.HOME
                        )
                    )
                },
                onMovieClick = { movie ->
                    navController.navigateIfResumed(Routes.movieDetail(movie.id, Routes.HOME))
                },
                onSeriesClick = { series ->
                    navController.navigateIfResumed(Routes.seriesDetail(series.id, Routes.HOME))
                },
                onPlaybackHistoryClick = { history ->
                    val route = when (history.contentType) {
                        com.streamvault.domain.model.ContentType.LIVE -> {
                            Routes.player(
                                streamUrl = history.streamUrl,
                                title = history.title,
                                internalId = history.contentId,
                                providerId = history.providerId,
                                contentType = history.contentType.name,
                                returnRoute = Routes.HOME
                            )
                        }
                        com.streamvault.domain.model.ContentType.MOVIE -> {
                            Routes.player(
                                streamUrl = history.streamUrl,
                                title = history.title,
                                internalId = history.contentId,
                                providerId = history.providerId,
                                contentType = history.contentType.name,
                                returnRoute = Routes.HOME
                            )
                        }
                        com.streamvault.domain.model.ContentType.SERIES -> {
                            Routes.seriesDetail(history.contentId, Routes.HOME)
                        }
                        com.streamvault.domain.model.ContentType.SERIES_EPISODE -> {
                            Routes.player(
                                streamUrl = history.streamUrl,
                                title = history.title,
                                internalId = history.contentId,
                                providerId = history.providerId,
                                contentType = history.contentType.name,
                                returnRoute = Routes.HOME,
                                seriesId = history.seriesId,
                                seasonNumber = history.seasonNumber,
                                episodeNumber = history.episodeNumber
                            )
                        }
                    }
                    if (route is PlayerNavigationRequest) {
                        navController.navigateToPlayer(route)
                    } else {
                        navController.navigateIfResumed(route as String) { launchSingleTop = true }
                    }
                },
                currentRoute = Routes.HOME
            )
        }

        composable(
            route = Routes.LIVE_TV_DESTINATION,
            arguments = listOf(
                navArgument("categoryId") { type = NavType.LongType; defaultValue = -1L }
            )
        ) { backStackEntry ->
            val initialCategoryId = backStackEntry.arguments?.getLong("categoryId")?.takeIf { it != -1L }
            HomeScreen(
                onChannelClick = { channel, category, provider, combinedProfileId, combinedSourceFilterProviderId ->
                    navController.navigateToPlayer(
                        Routes.livePlayer(
                            channel = channel,
                            categoryId = category?.id,
                            providerId = provider?.id,
                            isVirtual = category?.isVirtual == true,
                            combinedProfileId = combinedProfileId,
                            combinedSourceFilterProviderId = combinedSourceFilterProviderId,
                            returnRoute = Routes.liveTv(category?.id)
                        )
                    )
                },
                onNavigate = { route -> tabNavigate(route) },
                currentRoute = Routes.LIVE_TV,
                initialCategoryId = initialCategoryId
            )
        }
// ... (rest of file)

        composable(Routes.MOVIES) {
            MoviesScreen(
                onMovieClick = { movie ->
                    navController.navigateIfResumed(Routes.movieDetail(movie.id, Routes.MOVIES))
                },
                onContinueWatchingPlay = { history ->
                    navController.navigateToPlayer(
                        history.toPlayerNavigationRequest().copy(returnRoute = Routes.MOVIES)
                    )
                },
                onNavigate = { route -> tabNavigate(route) },
                currentRoute = Routes.MOVIES
            )
        }

        composable(Routes.SERIES) {
            SeriesScreen(
                onSeriesClick = { seriesId ->
                    navController.navigateIfResumed(Routes.seriesDetail(seriesId, Routes.SERIES))
                },
                onNavigate = { route -> tabNavigate(route) },
                currentRoute = Routes.SERIES
            )
        }

        composable(
            route = Routes.EPG_DESTINATION,
            arguments = listOf(
                navArgument("categoryId") { type = NavType.LongType; defaultValue = -1L },
                navArgument("anchorTime") { type = NavType.LongType; defaultValue = -1L },
                navArgument("favoritesOnly") { type = NavType.BoolType; defaultValue = false }
            )
        ) { backStackEntry ->
            val epgCategoryId = backStackEntry.arguments?.getLong("categoryId")?.takeIf { it != -1L }
            val epgAnchorTime = backStackEntry.arguments?.getLong("anchorTime")?.takeIf { it != -1L }
            val epgFavoritesOnly = backStackEntry.arguments?.getBoolean("favoritesOnly") ?: false
            com.streamvault.app.ui.screens.epg.FullEpgScreen(
                currentRoute = Routes.EPG,
                initialCategoryId = epgCategoryId,
                initialAnchorTime = epgAnchorTime,
                initialFavoritesOnly = epgFavoritesOnly,
                onPlayChannel = { channel, categoryId, isVirtual, combinedProfileId, returnRoute ->
                    navController.navigateToPlayer(
                        Routes.livePlayer(
                            channel = channel,
                            categoryId = categoryId,
                            providerId = channel.providerId,
                            isVirtual = isVirtual,
                            combinedProfileId = combinedProfileId,
                            returnRoute = returnRoute
                        )
                    )
                },
                onPlayArchive = { channel, program, categoryId, isVirtual, combinedProfileId, returnRoute ->
                    if (!channel.isArchivePlayable(program)) {
                        return@FullEpgScreen
                    }
                    navController.navigateToPlayer(
                        Routes.player(
                            streamUrl = channel.streamUrl,
                            title = channel.name,
                            channelId = channel.epgChannelId,
                            internalId = channel.id,
                            categoryId = categoryId,
                            providerId = channel.providerId,
                            isVirtual = isVirtual,
                            combinedProfileId = combinedProfileId,
                            contentType = "LIVE",
                            archiveStartMs = program.startTime,
                            archiveEndMs = program.endTime,
                            archiveTitle = "${channel.name}: ${program.title}",
                            returnRoute = returnRoute
                        )
                    )
                },
                onNavigate = { route -> tabNavigate(route) }
            )
        }

        composable(
            route = Routes.SETTINGS_DESTINATION,
            arguments = listOf(
                navArgument("backupUri") { type = NavType.StringType; defaultValue = "" }
            )
        ) { backStackEntry ->
            val backupUri = backStackEntry.arguments?.getString("backupUri")?.takeIf { it.isNotBlank() }
            SettingsScreen(
                onNavigate = { route -> tabNavigate(route) },
                onAddProvider = dropUnlessResumed {
                    navController.navigate(Routes.providerSetup(null))
                },
                onEditProvider = { provider ->
                    navController.navigateIfResumed(Routes.providerSetup(provider.id))
                },
                onNavigateToParentalControl = { providerId ->
                    navController.navigateIfResumed(Routes.parentalControlGroups(providerId))
                },
                currentRoute = Routes.SETTINGS,
                initialBackupImportUri = backupUri
            )
        }

        composable(Routes.SETTINGS_DOWNLOADS) {
            SettingsScreen(
                onNavigate = { route -> tabNavigate(route) },
                onAddProvider = dropUnlessResumed {
                    navController.navigate(Routes.providerSetup(null))
                },
                onEditProvider = { provider ->
                    navController.navigateIfResumed(Routes.providerSetup(provider.id))
                },
                onNavigateToParentalControl = { providerId ->
                    navController.navigateIfResumed(Routes.parentalControlGroups(providerId))
                },
                currentRoute = Routes.SETTINGS_DOWNLOADS,
                initialSelectedCategory = SETTINGS_CATEGORY_DOWNLOADS
            )
        }

        composable(Routes.PLUGINS) {
            PluginsScreen(
                currentRoute = Routes.PLUGINS,
                onNavigate = { route -> tabNavigate(route) }
            )
        }

        composable(
            route = Routes.PARENTAL_CONTROL_GROUPS,
            arguments = listOf(
                navArgument("providerId") { type = NavType.LongType }
            )
        ) {
            com.streamvault.app.ui.screens.settings.parental.ParentalControlGroupScreen(
                currentRoute = Routes.SETTINGS,
                onNavigate = { route -> tabNavigate(route) },
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Routes.SEARCH_DESTINATION,
            arguments = listOf(
                navArgument("query") { type = NavType.StringType; defaultValue = "" }
            )
        ) { backStackEntry ->
            com.streamvault.app.ui.screens.search.SearchScreen(
                initialQuery = backStackEntry.arguments?.getString("query").orEmpty(),
                onChannelClick = { channel ->
                    navController.navigateToPlayer(
                        Routes.livePlayer(
                            channel = channel,
                            categoryId = channel.categoryId ?: ChannelRepository.ALL_CHANNELS_ID,
                            providerId = channel.providerId,
                            isVirtual = false,
                            returnRoute = Routes.search(backStackEntry.arguments?.getString("query").orEmpty())
                        )
                    )
                },
                onMovieClick = { movie ->
                     navController.navigateIfResumed(
                         Routes.movieDetail(movie.id, Routes.search(backStackEntry.arguments?.getString("query").orEmpty()))
                     )
                },
                onSeriesClick = { series ->
                     navController.navigateIfResumed(
                         Routes.seriesDetail(series.id, Routes.search(backStackEntry.arguments?.getString("query").orEmpty()))
                     )
                },
                onNavigate = { route -> tabNavigate(route) },
                currentRoute = Routes.SEARCH
            )
        }

        composable(route = Routes.PLAYER) { backStackEntry ->
            val playerRequest = backStackEntry.savedStateHandle.get<PlayerNavigationRequest>(PLAYER_REQUEST_KEY)
                ?: navController.previousBackStackEntry?.savedStateHandle?.get<PlayerNavigationRequest>(PLAYER_REQUEST_KEY)?.also {
                    backStackEntry.savedStateHandle[PLAYER_REQUEST_KEY] = it
                }
            val safePlayerRequest = safePlayerNavigationRequest(playerRequest)
            if (safePlayerRequest == null) {
                LaunchedEffect(playerRequest) {
                    Log.w(TAG, "Missing or invalid player request; returning to previous destination")
                    if (!navController.popBackStack()) {
                        navController.navigate(Routes.HOME) {
                            popUpTo(Routes.PLAYER) { inclusive = true }
                            launchSingleTop = true
                        }
                    }
                }
            } else {
                PlayerScreen(
                    streamUrl = safePlayerRequest.streamUrl,
                    title = safePlayerRequest.title,
                    epgChannelId = safePlayerRequest.channelId,
                    internalChannelId = safePlayerRequest.internalId,
                    categoryId = safePlayerRequest.categoryId,
                    providerId = safePlayerRequest.providerId,
                    isVirtual = safePlayerRequest.isVirtual,
                    combinedProfileId = safePlayerRequest.combinedProfileId,
                    combinedSourceFilterProviderId = safePlayerRequest.combinedSourceFilterProviderId,
                    contentType = safePlayerRequest.contentType,
                    artworkUrl = safePlayerRequest.artworkUrl,
                    archiveStartMs = safePlayerRequest.archiveStartMs,
                    archiveEndMs = safePlayerRequest.archiveEndMs,
                    archiveTitle = safePlayerRequest.archiveTitle,
                    returnRoute = safePlayerRequest.returnRoute,
                    seriesId = safePlayerRequest.seriesId,
                    seasonNumber = safePlayerRequest.seasonNumber,
                    episodeNumber = safePlayerRequest.episodeNumber,
                    episodeId = safePlayerRequest.episodeId,
                    onBack = {
                        val route = safePlayerRequest.returnRoute
                        if (!route.isNullOrBlank() && navController.popBackStack(route, false)) {
                            Unit
                        } else if (!route.isNullOrBlank()) {
                            navController.navigate(route) {
                                popUpTo(Routes.PLAYER) { inclusive = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        } else {
                            navController.popBackStack()
                        }
                    },
                    onNavigate = { route ->
                        navController.navigateIfResumed(route) {
                            launchSingleTop = true
                            if (route == Routes.MULTI_VIEW) {
                                popUpTo(Routes.PLAYER) { inclusive = true }
                            }
                        }
                    }
                )
            }
        }

        composable(
            route = Routes.MOVIE_DETAIL,
            arguments = listOf(
                navArgument("movieId") { type = NavType.LongType },
                navArgument("returnRoute") { type = NavType.StringType; defaultValue = "" }
            )
        ) { backStackEntry ->
            val returnRoute = backStackEntry.arguments?.getString("returnRoute").orEmpty().takeIf { it.isNotBlank() }
            val movieId = backStackEntry.arguments?.getLong("movieId") ?: -1L
            com.streamvault.app.ui.screens.movies.MovieDetailScreen(
                onPlay = { movie ->
                    navController.navigateToPlayer(
                        Routes.moviePlayer(movie).copy(
                            returnRoute = Routes.movieDetail(
                                movieId = movie.id.takeIf { it > 0L } ?: movieId,
                                returnRoute = returnRoute
                            )
                        )
                    )
                },
                onBack = {
                    if (!returnRoute.isNullOrBlank()) {
                        navController.navigate(returnRoute) {
                            popUpTo(backStackEntry.destination.route ?: Routes.MOVIE_DETAIL) { inclusive = true }
                            launchSingleTop = true
                        }
                    } else {
                        navController.popBackStack()
                    }
                }
            )
        }

        composable(
            route = Routes.SERIES_DETAIL,
            arguments = listOf(
                navArgument("seriesId") { type = NavType.LongType },
                navArgument("returnRoute") { type = NavType.StringType; defaultValue = "" }
            )
        ) { backStackEntry ->
            val returnRoute = backStackEntry.arguments?.getString("returnRoute").orEmpty().takeIf { it.isNotBlank() }
            val seriesId = backStackEntry.arguments?.getLong("seriesId") ?: -1L
            com.streamvault.app.ui.screens.series.SeriesDetailScreen(
                onEpisodeClick = { episode ->
                     navController.navigateToPlayer(
                         Routes.episodePlayer(episode).copy(
                             returnRoute = Routes.seriesDetail(
                                 seriesId = episode.seriesId.takeIf { it > 0L } ?: seriesId,
                                 returnRoute = returnRoute
                             )
                         )
                     )
                },
                onBack = {
                    if (!returnRoute.isNullOrBlank()) {
                        navController.navigate(returnRoute) {
                            popUpTo(backStackEntry.destination.route ?: Routes.SERIES_DETAIL) { inclusive = true }
                            launchSingleTop = true
                        }
                    } else {
                        navController.popBackStack()
                    }
                }
            )
        }

        composable(Routes.MULTI_VIEW) {
            MultiViewScreen(
                onBack = { navController.popBackStack() }
            )
        }
    }
}
