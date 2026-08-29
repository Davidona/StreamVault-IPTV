package com.streamvault.app.navigation.graph

import androidx.navigation.NavGraphBuilder
import com.streamvault.app.navigation.AppRouteCodec
import com.streamvault.app.navigation.AppRoutePatterns
import com.streamvault.app.navigation.playerNavigationRequest
import com.streamvault.app.navigation.toLivePlayerRequest
import com.streamvault.app.ui.screens.epg.FullEpgScreen
import com.streamvault.app.ui.screens.home.HomeScreen
import com.streamvault.core.navigation.AppDestination
import com.streamvault.core.navigation.NavigationActions
import com.streamvault.domain.playback.isArchivePlayable
import com.streamvault.feature.live.api.LiveArchivePlaybackRequest
import com.streamvault.feature.live.api.LiveChannelPlaybackRequest
import com.streamvault.feature.live.navigation.registerLiveGraph as registerFeatureLiveGraph

internal fun NavGraphBuilder.registerLiveGraph(
    actions: NavigationActions,
    onTopLevelDestinationRequested: (AppDestination) -> Unit,
) {
    registerFeatureLiveGraph(
        liveTvContent = { initialCategoryId, onPlaybackRequested, onNavigate ->
            HomeScreen(
                onChannelClick = { channel, category, provider, combinedProfileId, combinedSourceFilterProviderId ->
                    onPlaybackRequested(
                        LiveChannelPlaybackRequest(
                            channel = channel,
                            categoryId = category?.id,
                            providerId = provider?.id,
                            isVirtual = category?.isVirtual == true,
                            combinedProfileId = combinedProfileId,
                            combinedSourceFilterProviderId = combinedSourceFilterProviderId,
                            returnRoute = AppRouteCodec.encode(AppDestination.LiveTv(category?.id)),
                        )
                    )
                },
                onOpenMultiView = { onNavigate(AppRoutePatterns.MULTI_VIEW) },
                onNavigate = onNavigate,
                currentRoute = AppRoutePatterns.LIVE_TV,
                initialCategoryId = initialCategoryId,
            )
        },
        epgContent = {
            initialCategoryId,
            initialAnchorTime,
            initialFavoritesOnly,
            onChannelPlaybackRequested,
            onArchivePlaybackRequested,
            onNavigate,
        ->
            FullEpgScreen(
                currentRoute = AppRoutePatterns.EPG,
                initialCategoryId = initialCategoryId,
                initialAnchorTime = initialAnchorTime,
                initialFavoritesOnly = initialFavoritesOnly,
                onPlayChannel = { channel, categoryId, isVirtual, combinedProfileId, returnRoute ->
                    onChannelPlaybackRequested(
                        LiveChannelPlaybackRequest(
                            channel = channel,
                            categoryId = categoryId,
                            providerId = channel.providerId,
                            isVirtual = isVirtual,
                            combinedProfileId = combinedProfileId,
                            combinedSourceFilterProviderId = null,
                            returnRoute = returnRoute,
                        )
                    )
                },
                onPlayArchive = { channel, program, categoryId, isVirtual, combinedProfileId, returnRoute ->
                    if (!channel.isArchivePlayable(program)) return@FullEpgScreen
                    onArchivePlaybackRequested(
                        LiveArchivePlaybackRequest(
                            channel = channel,
                            program = program,
                            categoryId = categoryId,
                            isVirtual = isVirtual,
                            combinedProfileId = combinedProfileId,
                            returnRoute = returnRoute,
                        )
                    )
                },
                onNavigate = onNavigate,
            )
        },
        onPlaybackRequested = { request ->
            actions.openPlayer(
                request.channel.toLivePlayerRequest(
                    categoryId = request.categoryId,
                    providerId = request.providerId,
                    isVirtual = request.isVirtual,
                    combinedProfileId = request.combinedProfileId,
                    combinedSourceFilterProviderId = request.combinedSourceFilterProviderId,
                    returnDestination = request.returnRoute?.let(AppRouteCodec::decode),
                )
            )
        },
        onArchivePlaybackRequested = { request ->
            actions.openPlayer(
                playerNavigationRequest(
                    streamUrl = request.channel.streamUrl,
                    title = request.channel.name,
                    channelId = request.channel.epgChannelId,
                    internalId = request.channel.id,
                    categoryId = request.categoryId,
                    providerId = request.channel.providerId,
                    isVirtual = request.isVirtual,
                    combinedProfileId = request.combinedProfileId,
                    contentType = "LIVE",
                    archiveStartMs = request.program.startTime,
                    archiveEndMs = request.program.endTime,
                    archiveTitle = "${request.channel.name}: ${request.program.title}",
                    returnDestination = request.returnRoute?.let(AppRouteCodec::decode),
                )
            )
        },
        onNavigate = { route ->
            AppRouteCodec.decode(route)?.let(onTopLevelDestinationRequested)
        },
    )
}
