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
import com.streamvault.core.navigation.AppDestination
import com.streamvault.core.navigation.NavigationOptions
import com.streamvault.core.navigation.NavigationCommand
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.Episode
import com.streamvault.domain.model.Movie
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
import com.streamvault.domain.model.ActiveLiveSource
import com.streamvault.domain.model.Series
import com.streamvault.domain.model.VirtualCategoryIds
import java.io.Serializable
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine


@Composable
internal fun AppNavigationGraph(
    navController: NavHostController,
    navigator: NavControllerNavigator,
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
                    navigator.navigateIfResumed(
                        AppDestination.ProviderSetup(),
                        NavigationOptions(popUpTo = AppDestination.Welcome, inclusive = true)
                    )
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
                onBack = { navigator.back() },
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
                    navigator.navigateIfResumed(AppDestination.ProviderSetup())
                },
                onRecentChannelClick = { channel, combinedProfileId ->
                    navigator.openPlayer(
                        channel.toLivePlayerRequest(
                            categoryId = com.streamvault.domain.model.VirtualCategoryIds.RECENT,
                            providerId = channel.providerId,
                            isVirtual = true,
                            combinedProfileId = combinedProfileId,
                            returnDestination = AppDestination.Home
                        )
                    )
                },
                onFavoriteChannelClick = { channel, combinedProfileId ->
                    navigator.openPlayer(
                        channel.toLivePlayerRequest(
                            categoryId = com.streamvault.domain.model.VirtualCategoryIds.FAVORITES,
                            providerId = channel.providerId,
                            isVirtual = true,
                            combinedProfileId = combinedProfileId,
                            returnDestination = AppDestination.Home
                        )
                    )
                },
                onMovieClick = { movie ->
                    navigator.openMovieDetail(movie, AppDestination.Home)
                },
                onSeriesClick = { series ->
                    navigator.openSeriesDetail(series, AppDestination.Home)
                },
                onPlaybackHistoryClick = { history ->
                    val route = when (history.contentType) {
                        com.streamvault.domain.model.ContentType.LIVE -> {
                            history.toPlayerNavigationRequest(AppDestination.Home)
                        }
                        com.streamvault.domain.model.ContentType.MOVIE,
                        com.streamvault.domain.model.ContentType.VOD -> {
                            history.toPlayerNavigationRequest(AppDestination.Home)
                        }
                        com.streamvault.domain.model.ContentType.SERIES -> {
                            Routes.seriesDetail(history.contentId, Routes.HOME)
                        }
                        com.streamvault.domain.model.ContentType.SERIES_EPISODE -> {
                            history.toPlayerNavigationRequest(AppDestination.Home)
                        }
                    }
                    if (route is PlayerNavigationRequest) {
                        navigator.openPlayer(route)
                    } else {
                        AppRouteCodec.decode(route as String)?.let { destination ->
                            navigator.navigateIfResumed(
                                destination,
                                NavigationOptions(launchSingleTop = true)
                            )
                        }
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
                    navigator.openPlayer(
                        channel.toLivePlayerRequest(
                            categoryId = category?.id,
                            providerId = provider?.id,
                            isVirtual = category?.isVirtual == true,
                            combinedProfileId = combinedProfileId,
                            combinedSourceFilterProviderId = combinedSourceFilterProviderId,
                            returnDestination = AppDestination.LiveTv(category?.id)
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
                    navigator.openMovieDetail(movie, AppDestination.Movies)
                },
                onContinueWatchingPlay = { history ->
                    navigator.openPlayer(
                        history.toPlayerNavigationRequest().copy(returnDestination = AppDestination.Movies)
                    )
                },
                onNavigate = { route -> tabNavigate(route) },
                currentRoute = Routes.MOVIES
            )
        }

        composable(Routes.SERIES) {
            SeriesScreen(
                onSeriesClick = { series ->
                    navigator.openSeriesDetail(series, AppDestination.Series)
                },
                onSeriesIdClick = { seriesId ->
                    navigator.navigateIfResumed(
                        AppDestination.SeriesDetail(seriesId, AppDestination.Series),
                        NavigationOptions(launchSingleTop = true)
                    )
                },
                onNavigate = { route -> tabNavigate(route) },
                currentRoute = Routes.SERIES
            )
        }

        composable(Routes.VOD) {
            VodScreen(
                onMovieClick = { movie ->
                    navigator.openMovieDetail(movie, AppDestination.Vod)
                },
                onSeriesClick = { series ->
                    navigator.openSeriesDetail(series, AppDestination.Vod)
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
                    navigator.openPlayer(
                        channel.toLivePlayerRequest(
                            categoryId = categoryId,
                            providerId = channel.providerId,
                            isVirtual = isVirtual,
                            combinedProfileId = combinedProfileId,
                            returnDestination = AppRouteCodec.decode(returnRoute)
                        )
                    )
                },
                onPlayArchive = { channel, program, categoryId, isVirtual, combinedProfileId, returnRoute ->
                    if (!channel.isArchivePlayable(program)) {
                        return@FullEpgScreen
                    }
                    navigator.openPlayer(
                        playerNavigationRequest(
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
                            returnDestination = AppRouteCodec.decode(returnRoute)
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
                    navigator.navigateIfResumed(AppDestination.ProviderSetup())
                },
                onEditProvider = { provider ->
                    navigator.navigateIfResumed(
                        AppDestination.ProviderSetup(providerId = provider.id),
                        NavigationOptions(launchSingleTop = true)
                    )
                },
                onNavigateToParentalControl = { providerId ->
                    navigator.navigateIfResumed(
                        AppDestination.ParentalControlGroups(providerId),
                        NavigationOptions(launchSingleTop = true)
                    )
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
                onBack = { navigator.back() }
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
                    navigator.openPlayer(
                        channel.toLivePlayerRequest(
                            categoryId = channel.categoryId,
                            providerId = channel.providerId,
                            isVirtual = false,
                            returnDestination = AppDestination.Search(backStackEntry.arguments?.getString("query").orEmpty())
                        )
                    )
                },
                onMovieClick = { movie ->
                     navigator.openMovieDetail(
                         movie,
                         AppDestination.Search(backStackEntry.arguments?.getString("query").orEmpty())
                     )
                },
                onSeriesClick = { series ->
                     navigator.openSeriesDetail(
                         series,
                         AppDestination.Search(backStackEntry.arguments?.getString("query").orEmpty())
                     )
                },
                onNavigate = { route -> tabNavigate(route) },
                currentRoute = Routes.SEARCH
            )
        }

        composable(route = Routes.PLAYER) { backStackEntry ->
            val playerRequest = navigator.consumePlayerRequest(backStackEntry)
            val safePlayerRequest = safePlayerNavigationRequest(playerRequest)
            if (safePlayerRequest == null) {
                LaunchedEffect(playerRequest) {
                    Log.w(APP_NAVIGATION_TAG, "Missing or invalid player request; returning to previous destination")
                    if (!navigator.execute(NavigationCommand.Back)) {
                        navigator.execute(
                            NavigationCommand.Navigate(
                                AppDestination.Home,
                                NavigationOptions(
                                    launchSingleTop = true,
                                    popUpTo = AppDestination.Player,
                                    inclusive = true
                                )
                            )
                        )
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
                    returnDestination = safePlayerRequest.returnDestination,
                    seriesId = safePlayerRequest.seriesId,
                    seasonNumber = safePlayerRequest.seasonNumber,
                    episodeNumber = safePlayerRequest.episodeNumber,
                    episodeId = safePlayerRequest.episodeId,
                    onBack = { navigator.returnTo(safePlayerRequest.returnDestination) },
                    onNavigate = { destination ->
                        navigator.navigateIfResumed(
                            destination,
                            NavigationOptions(
                                launchSingleTop = true,
                                popUpTo = AppDestination.Player.takeIf { destination == AppDestination.MultiView },
                                inclusive = destination == AppDestination.MultiView
                            )
                        )
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
            navigator.consumeMoviePresentationHint(backStackEntry)
            val returnRoute = backStackEntry.arguments?.getString("returnRoute").orEmpty().takeIf { it.isNotBlank() }
            val returnDestination = returnRoute?.let(AppRouteCodec::decode)
            val movieId = backStackEntry.arguments?.getLong("movieId") ?: -1L
            com.streamvault.app.ui.screens.movies.MovieDetailScreen(
                onPlay = { movie ->
                    navigator.openPlayer(
                        movie.toPlayerNavigationRequest(
                            returnDestination = AppDestination.MovieDetail(
                                movieId = movie.id.takeIf { it > 0L } ?: movieId,
                                returnDestination = returnDestination
                            )
                        )
                    )
                },
                onBack = { navigator.returnTo(returnDestination) }
            )
        }

        composable(
            route = Routes.SERIES_DETAIL,
            arguments = listOf(
                navArgument("seriesId") { type = NavType.LongType },
                navArgument("returnRoute") { type = NavType.StringType; defaultValue = "" }
            )
        ) { backStackEntry ->
            navigator.consumeSeriesPresentationHint(backStackEntry)
            val returnRoute = backStackEntry.arguments?.getString("returnRoute").orEmpty().takeIf { it.isNotBlank() }
            val returnDestination = returnRoute?.let(AppRouteCodec::decode)
            val seriesId = backStackEntry.arguments?.getLong("seriesId") ?: -1L
            com.streamvault.app.ui.screens.series.SeriesDetailScreen(
                onEpisodeClick = { episode ->
                     navigator.openPlayer(
                         episode.toPlayerNavigationRequest(
                             returnDestination = AppDestination.SeriesDetail(
                                 seriesId = episode.seriesId.takeIf { it > 0L } ?: seriesId,
                                 returnDestination = returnDestination
                             )
                         )
                     )
                },
                onBack = { navigator.returnTo(returnDestination) }
            )
        }

        composable(Routes.MULTI_VIEW) {
            MultiViewScreen(
                onBack = { navigator.back() }
            )
        }
    }}

