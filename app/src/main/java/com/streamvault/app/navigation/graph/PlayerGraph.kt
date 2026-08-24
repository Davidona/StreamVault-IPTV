package com.streamvault.app.navigation.graph

import android.util.Log
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.streamvault.app.navigation.APP_NAVIGATION_TAG
import com.streamvault.app.navigation.AppNavigationPayloads
import com.streamvault.app.navigation.AppRoutePatterns
import com.streamvault.app.navigation.safePlayerNavigationRequest
import com.streamvault.app.ui.screens.multiview.MultiViewScreen
import com.streamvault.app.ui.screens.player.PlayerScreen
import com.streamvault.core.navigation.AppDestination
import com.streamvault.core.navigation.NavigationActions
import com.streamvault.core.navigation.NavigationOptions
import com.streamvault.feature.playback.api.PlaybackPlatformHost

internal fun NavGraphBuilder.registerPlayerGraph(
    actions: NavigationActions,
    payloads: AppNavigationPayloads,
    playbackPlatformHost: PlaybackPlatformHost?
) {
    composable(AppRoutePatterns.PLAYER) { backStackEntry ->
        val playerRequest = payloads.consumePlayerRequest(backStackEntry)
        val safePlayerRequest = safePlayerNavigationRequest(playerRequest)
        if (safePlayerRequest == null) {
            LaunchedEffect(playerRequest) {
                Log.w(APP_NAVIGATION_TAG, "Missing or invalid player request; returning to previous destination")
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
                playbackPlatformHost = playbackPlatformHost,
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

    composable(AppRoutePatterns.MULTI_VIEW) {
        MultiViewScreen(onBack = { actions.back() })
    }
}
