package com.streamvault.app.navigation.graph

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.streamvault.app.navigation.AppRouteCodec
import com.streamvault.app.navigation.AppRoutePatterns
import com.streamvault.app.navigation.playerNavigationRequest
import com.streamvault.app.navigation.toLivePlayerRequest
import com.streamvault.domain.playback.isArchivePlayable
import com.streamvault.app.ui.screens.epg.FullEpgScreen
import com.streamvault.app.ui.screens.home.HomeScreen
import com.streamvault.core.navigation.AppDestination
import com.streamvault.core.navigation.NavigationActions

internal fun NavGraphBuilder.registerLiveGraph(
    actions: NavigationActions,
    onTopLevelDestinationRequested: (AppDestination) -> Unit
) {
    composable(
        route = AppRoutePatterns.LIVE_TV_DESTINATION,
        arguments = listOf(
            navArgument("categoryId") { type = NavType.LongType; defaultValue = -1L }
        )
    ) { backStackEntry ->
        val initialCategoryId = backStackEntry.arguments?.getLong("categoryId")?.takeIf { it != -1L }
        HomeScreen(
            onChannelClick = { channel, category, provider, combinedProfileId, combinedSourceFilterProviderId ->
                actions.openPlayer(
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
            onNavigate = { route ->
                AppRouteCodec.decode(route)?.let(onTopLevelDestinationRequested)
            },
            currentRoute = AppRoutePatterns.LIVE_TV,
            initialCategoryId = initialCategoryId
        )
    }

    composable(
        route = AppRoutePatterns.EPG_DESTINATION,
        arguments = listOf(
            navArgument("categoryId") { type = NavType.LongType; defaultValue = -1L },
            navArgument("anchorTime") { type = NavType.LongType; defaultValue = -1L },
            navArgument("favoritesOnly") { type = NavType.BoolType; defaultValue = false }
        )
    ) { backStackEntry ->
        val epgCategoryId = backStackEntry.arguments?.getLong("categoryId")?.takeIf { it != -1L }
        val epgAnchorTime = backStackEntry.arguments?.getLong("anchorTime")?.takeIf { it != -1L }
        val epgFavoritesOnly = backStackEntry.arguments?.getBoolean("favoritesOnly") ?: false
        FullEpgScreen(
            currentRoute = AppRoutePatterns.EPG,
            initialCategoryId = epgCategoryId,
            initialAnchorTime = epgAnchorTime,
            initialFavoritesOnly = epgFavoritesOnly,
            onPlayChannel = { channel, categoryId, isVirtual, combinedProfileId, returnRoute ->
                actions.openPlayer(
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
                if (!channel.isArchivePlayable(program)) return@FullEpgScreen
                actions.openPlayer(
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
            onNavigate = { route ->
                AppRouteCodec.decode(route)?.let(onTopLevelDestinationRequested)
            }
        )
    }
}
