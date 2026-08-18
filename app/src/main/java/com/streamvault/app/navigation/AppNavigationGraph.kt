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
internal fun AppNavigationGraph(
    navController: NavHostController,
    startupRoute: String?,
    navigateToStartupTarget: (String) -> Boolean,
    tabNavigate: (String) -> Unit
) {
    NavHost(
        navController = navController,
        startDestination = Routes.WELCOME
    ) {
        composable(Routes.WELCOME) {
            WelcomeScreen(
                onNavigateToHome = dropUnlessResumed {
                    navigateToStartupTarget(Routes.WELCOME)
                },
                startupReady = startupRoute != null,
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
                    navigateToStartupTarget(Routes.PROVIDER_SETUP)
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
                    navController.navigateToMovieDetail(movie, Routes.HOME)
                },
                onSeriesClick = { series ->
                    navController.navigateToSeriesDetail(series, Routes.HOME)
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
                        com.streamvault.domain.model.ContentType.MOVIE,
                        com.streamvault.domain.model.ContentType.VOD -> {
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
                    navController.navigateToMovieDetail(movie, Routes.MOVIES)
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
                onSeriesClick = { series ->
                    navController.navigateToSeriesDetail(series, Routes.SERIES)
                },
                onSeriesIdClick = { seriesId ->
                    navController.navigateIfResumed(Routes.seriesDetail(seriesId, Routes.SERIES))
                },
                onNavigate = { route -> tabNavigate(route) },
                currentRoute = Routes.SERIES
            )
        }

        composable(Routes.VOD) {
            VodScreen(
                onMovieClick = { movie ->
                    navController.navigateToMovieDetail(movie, Routes.VOD)
                },
                onSeriesClick = { series ->
                    navController.navigateToSeriesDetail(series, Routes.VOD)
                },
                onNavigate = { route -> tabNavigate(route) },
                currentRoute = Routes.VOD
            )
        }

        composable(Routes.DOWNLOADS) {
            DownloadsScreen(
                onNavigate = { route -> tabNavigate(route) },
                currentRoute = Routes.DOWNLOADS
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
                     navController.navigateToMovieDetail(
                         movie,
                         Routes.search(backStackEntry.arguments?.getString("query").orEmpty())
                     )
                },
                onSeriesClick = { series ->
                     navController.navigateToSeriesDetail(
                         series,
                         Routes.search(backStackEntry.arguments?.getString("query").orEmpty())
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
                    Log.w(APP_NAVIGATION_TAG, "Missing or invalid player request; returning to previous destination")
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
                            // Popped back to the exact route already in the backstack (same VM, handoff works)
                            Unit
                        } else if (!route.isNullOrBlank()) {
                            // Nothing left to pop — navigate to the return route or home as a last resort
                            navController.navigate(route) {
                                popUpTo(Routes.PLAYER) { inclusive = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        } else if (!navController.popBackStack()) {
                            navController.navigate(Routes.HOME) {
                                popUpTo(Routes.PLAYER) { inclusive = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                        // else: plain popBackStack() succeeded — returns to existing Guide entry, preserving EpgViewModel
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
            val moviePresentationHint = backStackEntry.savedStateHandle.get<MovieDetailPresentationHint>(MOVIE_DETAIL_PRESENTATION_HINT_KEY)
                ?: navController.previousBackStackEntry?.savedStateHandle?.get<MovieDetailPresentationHint>(MOVIE_DETAIL_PRESENTATION_HINT_KEY)?.also {
                    backStackEntry.savedStateHandle[MOVIE_DETAIL_PRESENTATION_HINT_KEY] = it
                }
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
            val seriesPresentationHint = backStackEntry.savedStateHandle.get<SeriesDetailPresentationHint>(SERIES_DETAIL_PRESENTATION_HINT_KEY)
                ?: navController.previousBackStackEntry?.savedStateHandle?.get<SeriesDetailPresentationHint>(SERIES_DETAIL_PRESENTATION_HINT_KEY)?.also {
                    backStackEntry.savedStateHandle[SERIES_DETAIL_PRESENTATION_HINT_KEY] = it
                }
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
    }}

