package com.streamvault.app.navigation

import com.streamvault.core.navigation.PlayerNavigationRequest
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


/** Navigate only when the current destination is fully resumed – prevents double-navigation during transitions. */
internal fun NavHostController.navigateIfResumed(route: String, builder: NavOptionsBuilder.() -> Unit = {}): Boolean {
    if (currentBackStackEntry?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) != true) return false
    navigate(route, builder)
    return true
}

internal suspend fun Lifecycle.awaitResumed() {
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

internal fun NavHostController.navigateToPlayer(request: PlayerNavigationRequest): Boolean {
    if (currentBackStackEntry?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) != true) return false
    currentBackStackEntry?.savedStateHandle?.set(PLAYER_REQUEST_KEY, request)
    navigate(Routes.PLAYER) { launchSingleTop = true }
    return true
}

internal fun NavHostController.navigateToMovieDetail(movie: Movie, returnRoute: String? = null): Boolean {
    if (currentBackStackEntry?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) != true) return false
    currentBackStackEntry?.savedStateHandle?.set(MOVIE_DETAIL_PRESENTATION_HINT_KEY, movie.toMovieDetailPresentationHint())
    navigate(Routes.movieDetail(movie.id, returnRoute))
    return true
}

private fun Movie.toMovieDetailPresentationHint(): MovieDetailPresentationHint? {
    if (variants.isEmpty()) return null
    return MovieDetailPresentationHint(
        providerId = providerId,
        logicalGroupId = logicalGroupId,
        variants = variants,
        duplicateConfidence = duplicateConfidence
    )
}

internal fun NavHostController.navigateToSeriesDetail(series: Series, returnRoute: String? = null): Boolean {
    if (currentBackStackEntry?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) != true) return false
    currentBackStackEntry?.savedStateHandle?.set(SERIES_DETAIL_PRESENTATION_HINT_KEY, series.toSeriesDetailPresentationHint())
    navigate(Routes.seriesDetail(series.id, returnRoute))
    return true
}

private fun Series.toSeriesDetailPresentationHint(): SeriesDetailPresentationHint? {
    if (variants.isEmpty()) return null
    return SeriesDetailPresentationHint(
        providerId = providerId,
        logicalGroupId = logicalGroupId,
        variants = variants,
        duplicateConfidence = duplicateConfidence
    )
}

internal fun NavHostController.navigateToExternalPlayer(request: PlayerNavigationRequest): Boolean {
    if (currentBackStackEntry?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) != true) return false
    currentBackStackEntry?.savedStateHandle?.set(PLAYER_REQUEST_KEY, request)
    navigate(Routes.PLAYER) { launchSingleTop = true }
    return true
}

internal fun AppLandingDestination.toAppRoute(): String = when (this) {
    AppLandingDestination.HOME -> Routes.HOME
    AppLandingDestination.LIVE_TV -> Routes.LIVE_TV
    AppLandingDestination.FIRST_FAVORITE_LIVE -> Routes.LIVE_TV
    AppLandingDestination.LAST_WATCHED_LIVE -> Routes.LIVE_TV
    AppLandingDestination.MOVIES -> Routes.MOVIES
    AppLandingDestination.SERIES -> Routes.SERIES
    AppLandingDestination.GUIDE -> Routes.EPG
    AppLandingDestination.DOWNLOADS -> Routes.DOWNLOADS
    AppLandingDestination.PLUGINS -> Routes.PLUGINS
    AppLandingDestination.SETTINGS -> Routes.SETTINGS
}

internal fun AppTopLevelDestination.toAppRoute(): String = when (this) {
    AppTopLevelDestination.HOME -> Routes.HOME
    AppTopLevelDestination.LIVE_TV -> Routes.LIVE_TV
    AppTopLevelDestination.MOVIES -> Routes.MOVIES
    AppTopLevelDestination.SERIES -> Routes.SERIES
    AppTopLevelDestination.DOWNLOADS -> Routes.DOWNLOADS
    AppTopLevelDestination.GUIDE -> Routes.EPG
    AppTopLevelDestination.SEARCH -> Routes.SEARCH
    AppTopLevelDestination.PLUGINS -> Routes.PLUGINS
    AppTopLevelDestination.SETTINGS -> Routes.SETTINGS
}


