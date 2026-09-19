package com.streamvault.feature.playback.navigation

import android.util.Log
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.streamvault.core.navigation.AppDestination
import com.streamvault.core.navigation.NavigationActions
import com.streamvault.core.navigation.NavigationOptions
import com.streamvault.core.navigation.PlayerNavigationRequest
import com.streamvault.feature.playback.api.PlaybackPlatformHost
import com.streamvault.feature.playback.multiview.MultiViewPlannerDialog
import com.streamvault.feature.playback.multiview.MultiViewScreen
import com.streamvault.feature.playback.player.PlayerScreen

private const val PLAYBACK_NAVIGATION_TAG = "PlaybackNavigation"

fun NavGraphBuilder.registerPlaybackGraph(
    actions: NavigationActions,
    platformHost: PlaybackPlatformHost?,
    consumePlayerRequest: (NavBackStackEntry) -> PlayerNavigationRequest?
) {
    composable(PlaybackRoutePatterns.PLAYER) { backStackEntry ->
        val playerRequest = consumePlayerRequest(backStackEntry)
        val safePlayerRequest = safePlayerNavigationRequest(playerRequest)
        if (safePlayerRequest == null) {
            LaunchedEffect(playerRequest) {
                Log.w(
                    PLAYBACK_NAVIGATION_TAG,
                    "Missing or invalid player request; returning to previous destination"
                )
                if (!actions.back()) {
                    actions.navigate(
                        AppDestination.Home,
                        NavigationOptions(
                            launchSingleTop = true,
                            popUpTo = AppDestination.Player,
                            inclusive = true
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
                onBack = { actions.returnTo(safePlayerRequest.returnDestination) },
                playbackPlatformHost = platformHost,
                splitScreenPlanner = { pendingChannel, onDismiss, onLaunch ->
                    MultiViewPlannerDialog(
                        pendingChannel = pendingChannel,
                        onDismiss = onDismiss,
                        onLaunch = onLaunch
                    )
                },
                onNavigate = { destination ->
                    actions.navigate(
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

    composable(PlaybackRoutePatterns.MULTI_VIEW) {
        MultiViewScreen(onBack = { actions.back() })
    }
}
