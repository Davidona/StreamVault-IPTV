package com.streamvault.app.navigation

import androidx.lifecycle.Lifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavOptionsBuilder
import com.streamvault.core.navigation.AppDestination
import com.streamvault.core.navigation.NavigationActions
import com.streamvault.core.navigation.NavigationCommand
import com.streamvault.core.navigation.NavigationOptions
import com.streamvault.core.navigation.PlayerNavigationRequest
import com.streamvault.domain.model.Movie
import com.streamvault.domain.model.MovieDetailPresentationHint
import com.streamvault.domain.model.Series
import com.streamvault.domain.model.SeriesDetailPresentationHint

internal class NavControllerNavigator(
    private val navController: NavHostController
) : NavigationActions, CatalogDetailNavigationActions, AppNavigationPayloads {

    fun execute(command: NavigationCommand): Boolean = when (command) {
        is NavigationCommand.Navigate -> navigateIfResumed(command.destination, command.options)
        is NavigationCommand.OpenPlayer -> openPlayerIfResumed(command.request)
        NavigationCommand.Back -> back()
        is NavigationCommand.ReturnTo -> returnTo(command.destination)
    }

    override fun navigate(destination: AppDestination, options: NavigationOptions) {
        navigateIfResumed(destination, options)
    }

    override fun openPlayer(request: PlayerNavigationRequest) {
        openPlayerIfResumed(request)
    }

    override fun back(): Boolean = navController.popBackStack()

    override fun returnTo(destination: AppDestination?): Boolean {
        val route = destination?.let(AppRouteCodec::encode)
        val hasReturnTarget = !route.isNullOrBlank()
        val hasTargetInBackStack = hasReturnTarget && hasBackStackEntry(route!!)
        return when (planReturnNavigation(hasReturnTarget, hasTargetInBackStack, navController.previousBackStackEntry != null)) {
            ReturnNavigationPlan.PopToTarget -> navController.popBackStack(route!!, false)
            ReturnNavigationPlan.NavigateToTarget -> navigateIfResumed(
                destination!!,
                NavigationOptions(
                    launchSingleTop = true,
                    restoreState = true,
                    popUpTo = AppDestination.Player,
                    inclusive = true
                )
            )
            ReturnNavigationPlan.PopPrevious -> navController.popBackStack()
            ReturnNavigationPlan.NavigateHome -> navigateIfResumed(
                AppDestination.Home,
                NavigationOptions(
                    launchSingleTop = true,
                    restoreState = true,
                    popUpTo = AppDestination.Player,
                    inclusive = true
                )
            )
        }
    }

    internal fun navigateIfResumed(
        destination: AppDestination,
        options: NavigationOptions = NavigationOptions()
    ): Boolean {
        if (navController.currentBackStackEntry?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) != true) {
            return false
        }
        navController.navigate(AppRouteCodec.encode(destination)) {
            apply(options)
        }
        return true
    }

    override fun openMovieDetail(movie: Movie, returnDestination: AppDestination?) {
        if (!isResumed()) return
        navController.currentBackStackEntry?.savedStateHandle?.set(
            MOVIE_DETAIL_PRESENTATION_HINT_KEY,
            movie.toMovieDetailPresentationHint()
        )
        navigateIfResumed(AppDestination.MovieDetail(movie.id, returnDestination))
    }

    override fun openSeriesDetail(series: Series, returnDestination: AppDestination?) {
        if (!isResumed()) return
        navController.currentBackStackEntry?.savedStateHandle?.set(
            SERIES_DETAIL_PRESENTATION_HINT_KEY,
            series.toSeriesDetailPresentationHint()
        )
        navigateIfResumed(AppDestination.SeriesDetail(series.id, returnDestination))
    }

    override fun consumePlayerRequest(entry: NavBackStackEntry): PlayerNavigationRequest? =
        entry.savedStateHandle.get<PlayerNavigationRequest>(PLAYER_REQUEST_KEY)
            ?: navController.previousBackStackEntry?.savedStateHandle
                ?.get<PlayerNavigationRequest>(PLAYER_REQUEST_KEY)
                ?.also { entry.savedStateHandle[PLAYER_REQUEST_KEY] = it }

    override fun consumeMoviePresentationHint(entry: NavBackStackEntry): MovieDetailPresentationHint? =
        entry.savedStateHandle.get<MovieDetailPresentationHint>(MOVIE_DETAIL_PRESENTATION_HINT_KEY)
            ?: navController.previousBackStackEntry?.savedStateHandle
                ?.get<MovieDetailPresentationHint>(MOVIE_DETAIL_PRESENTATION_HINT_KEY)
                ?.also { entry.savedStateHandle[MOVIE_DETAIL_PRESENTATION_HINT_KEY] = it }

    override fun consumeSeriesPresentationHint(entry: NavBackStackEntry): SeriesDetailPresentationHint? =
        entry.savedStateHandle.get<SeriesDetailPresentationHint>(SERIES_DETAIL_PRESENTATION_HINT_KEY)
            ?: navController.previousBackStackEntry?.savedStateHandle
                ?.get<SeriesDetailPresentationHint>(SERIES_DETAIL_PRESENTATION_HINT_KEY)
                ?.also { entry.savedStateHandle[SERIES_DETAIL_PRESENTATION_HINT_KEY] = it }

    private fun openPlayerIfResumed(request: PlayerNavigationRequest): Boolean {
        if (!isResumed()) return false
        navController.currentBackStackEntry?.savedStateHandle?.set(PLAYER_REQUEST_KEY, request)
        navController.navigate(AppRouteCodec.encode(AppDestination.Player)) {
            launchSingleTop = true
        }
        return true
    }

    private fun hasBackStackEntry(route: String): Boolean =
        runCatching { navController.getBackStackEntry(route) }.isSuccess

    private fun isResumed(): Boolean =
        navController.currentBackStackEntry?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true

    private fun NavOptionsBuilder.apply(options: NavigationOptions) {
        launchSingleTop = options.launchSingleTop
        restoreState = options.restoreState
        options.popUpTo?.let { destination ->
            popUpTo(AppRouteCodec.encode(destination)) {
                inclusive = options.inclusive
                saveState = options.saveState
            }
        }
    }
}
