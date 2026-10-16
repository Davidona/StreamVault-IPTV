package com.streamvault.feature.stremio.navigation

import androidx.navigation.NavHostController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.compose.ui.res.stringResource
import com.streamvault.core.navigation.AppDestination
import com.streamvault.core.navigation.NavigationActions
import com.streamvault.core.navigation.PlayerNavigationRequest
import com.streamvault.feature.stremio.api.StremioScaffoldContent
import com.streamvault.feature.stremio.presentation.AddonsScreen
import com.streamvault.feature.stremio.presentation.StremioItemDetailScreen
import com.streamvault.feature.stremio.presentation.StremioVisibilityHost
import com.streamvault.feature.stremio.R

fun NavGraphBuilder.registerStremioGraph(
    navController: NavHostController,
    actions: NavigationActions,
    onTopLevelDestinationRequested: (AppDestination) -> Unit,
    scaffold: StremioScaffoldContent
) {
    composable(StremioRoutePatterns.ADDONS) {
        StremioVisibilityHost(
            scaffold = scaffold,
            onOpenSettings = { actions.navigate(AppDestination.Settings()) },
            title = stringResource(R.string.extensions_title),
            showExtensionsTabs = true,
            onNavigateToPlugins = { onTopLevelDestinationRequested(AppDestination.Plugins) },
            content = { accessState ->
                AddonsScreen(
                    scaffold = scaffold,
                    showContinueWatching = accessState.showContinueWatching,
                    onNavigateToPlugins = { onTopLevelDestinationRequested(AppDestination.Plugins) },
                    onOpenItem = { addonId, preview ->
                        navController.navigate(StremioRoutePatterns.itemRoute(addonId, preview))
                    },
                    onOpenHistory = { history, originAddonId ->
                        navController.navigate(StremioRoutePatterns.historyRoute(originAddonId, history))
                    }
                )
            }
        )
    }
    composable(
        route = StremioRoutePatterns.ITEM,
        arguments = listOf(
            navArgument("originAddonId") { type = NavType.LongType },
            navArgument("type") { type = NavType.StringType },
            navArgument("exactId") { type = NavType.StringType },
            navArgument("name") { type = NavType.StringType },
            navArgument("poster") { type = NavType.StringType; defaultValue = "" },
            navArgument("description") { type = NavType.StringType; defaultValue = "" },
            navArgument("releaseInfo") { type = NavType.StringType; defaultValue = "" },
            navArgument("replayVideoId") { type = NavType.StringType; defaultValue = "" },
            navArgument("replayOriginKey") { type = NavType.StringType; defaultValue = "" },
            navArgument("replayRequiresOrigin") { type = NavType.BoolType; defaultValue = false }
        )
    ) {
        StremioVisibilityHost(
            scaffold = scaffold,
            onOpenSettings = { actions.navigate(AppDestination.Settings()) },
            title = stringResource(R.string.stremio_title),
            content = {
                StremioItemDetailScreen(
                    scaffold = scaffold,
                    onPlay = { handoff ->
                        actions.openPlayer(
                            PlayerNavigationRequest(
                                streamUrl = handoff.streamUrl,
                                title = handoff.title,
                                contentType = handoff.contentType,
                                artworkUrl = handoff.artworkUrl,
                                returnDestination = AppDestination.Addons,
                                stremioPlaybackReference = handoff.reference,
                                stremioMediaRequestHeaders = handoff.mediaRequestHeaders,
                                stremioInlineSubtitles = handoff.inlineSubtitles
                            )
                        )
                    }
                )
            }
        )
    }
}
