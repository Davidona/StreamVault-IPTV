package com.streamvault.feature.live.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.streamvault.feature.live.api.LiveArchivePlaybackRequest
import com.streamvault.feature.live.api.LiveChannelPlaybackRequest
import com.streamvault.feature.live.api.LiveEpgContent
import com.streamvault.feature.live.api.LiveTvContent

/**
 * Registers the Live destinations and owns their argument parsing. The host supplies content and
 * receives typed playback/navigation intents; no root controller or app route codec is required.
 */
fun NavGraphBuilder.registerLiveGraph(
    liveTvContent: LiveTvContent,
    epgContent: LiveEpgContent,
    onPlaybackRequested: (LiveChannelPlaybackRequest) -> Unit,
    onArchivePlaybackRequested: (LiveArchivePlaybackRequest) -> Unit,
    onNavigate: (String) -> Unit,
) {
    composable(
        route = LiveRoutePatterns.LIVE_TV_DESTINATION,
        arguments = listOf(
            navArgument("categoryId") { type = NavType.LongType; defaultValue = -1L },
        ),
    ) { backStackEntry ->
        val initialCategoryId = backStackEntry.arguments
            ?.getLong("categoryId")
            ?.takeIf { it != -1L }
        liveTvContent(
            initialCategoryId,
            onPlaybackRequested,
            onNavigate,
        )
    }

    composable(
        route = LiveRoutePatterns.EPG_DESTINATION,
        arguments = listOf(
            navArgument("categoryId") { type = NavType.LongType; defaultValue = -1L },
            navArgument("anchorTime") { type = NavType.LongType; defaultValue = -1L },
            navArgument("favoritesOnly") { type = NavType.BoolType; defaultValue = false },
        ),
    ) { backStackEntry ->
        val initialCategoryId = backStackEntry.arguments
            ?.getLong("categoryId")
            ?.takeIf { it != -1L }
        val initialAnchorTime = backStackEntry.arguments
            ?.getLong("anchorTime")
            ?.takeIf { it != -1L }
        val initialFavoritesOnly = backStackEntry.arguments
            ?.getBoolean("favoritesOnly")
            ?: false
        epgContent(
            initialCategoryId,
            initialAnchorTime,
            initialFavoritesOnly,
            onPlaybackRequested,
            onArchivePlaybackRequested,
            onNavigate,
        )
    }
}
