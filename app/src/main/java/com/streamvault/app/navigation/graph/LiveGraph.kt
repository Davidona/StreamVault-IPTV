package com.streamvault.app.navigation.graph

import androidx.navigation.NavGraphBuilder
import com.streamvault.app.navigation.AppRouteCodec
import com.streamvault.app.navigation.AppRoutePatterns
import com.streamvault.app.live.toAppArchivePlayerNavigationRequest
import com.streamvault.app.live.rememberAppLiveMultiViewPlannerContent
import com.streamvault.app.live.toAppPlayerNavigationRequest
import com.streamvault.app.ui.components.shell.AppNavigationChrome
import com.streamvault.app.ui.components.shell.AppScreenScaffold
import com.streamvault.app.ui.components.dialogs.AddToGroupDialog
import com.streamvault.core.navigation.AppDestination
import com.streamvault.core.navigation.NavigationActions
import com.streamvault.domain.playback.isArchivePlayable
import com.streamvault.feature.live.api.LiveArchivePlaybackRequest
import com.streamvault.feature.live.api.LiveChannelPlaybackRequest
import com.streamvault.feature.live.api.LiveAddToGroupDialogRequest
import com.streamvault.feature.live.home.LiveHomeScreen
import com.streamvault.feature.live.epg.LiveEpgScreen
import com.streamvault.feature.live.navigation.registerLiveGraph as registerFeatureLiveGraph

internal fun NavGraphBuilder.registerLiveGraph(
    actions: NavigationActions,
    onTopLevelDestinationRequested: (AppDestination) -> Unit,
) {
    registerFeatureLiveGraph(
        liveTvContent = { initialCategoryId, onPlaybackRequested, onNavigate ->
            val multiViewPlanner = rememberAppLiveMultiViewPlannerContent()
            LiveHomeScreen(
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
                scaffold = { route, title, subtitle, content ->
                    AppScreenScaffold(
                        currentRoute = route,
                        onNavigate = onNavigate,
                        title = title,
                        subtitle = subtitle,
                        navigationChrome = AppNavigationChrome.TopBar,
                        compactHeader = true,
                        showScreenHeader = false,
                        content = content,
                    )
                },
                multiViewPlanner = multiViewPlanner.content,
                addToGroupContent = { request: LiveAddToGroupDialogRequest ->
                    AddToGroupDialog(
                        contentTitle = request.contentTitle,
                        channel = request.channel,
                        groups = request.groups,
                        isFavorite = request.isFavorite,
                        memberOfGroups = request.memberOfGroups,
                        onDismiss = request.onDismiss,
                        onToggleFavorite = request.onToggleFavorite,
                        onAddToGroup = request.onAddToGroup,
                        onRemoveFromGroup = request.onRemoveFromGroup,
                        onCreateGroup = request.onCreateGroup,
                        isQueuedForSplitScreen = request.isQueuedForSplitScreen,
                        onOpenSplitScreenPlanner = request.onOpenSplitScreenPlanner,
                        onRemoveFromRecent = request.onRemoveFromRecent,
                        onHideChannel = request.onHideChannel,
                        onMoveToMovies = request.onMoveToMovies,
                        onMoveToSeries = request.onMoveToSeries,
                    )
                },
                isChannelQueuedForMultiView = multiViewPlanner.isChannelQueued,
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
            LiveEpgScreen(
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
                    if (!channel.isArchivePlayable(program)) return@LiveEpgScreen
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
                scaffold = { route, title, subtitle, topBarVisible, content ->
                    AppScreenScaffold(
                        currentRoute = route,
                        onNavigate = onNavigate,
                        title = title,
                        subtitle = subtitle,
                        navigationChrome = AppNavigationChrome.TopBar,
                        topBarVisible = topBarVisible,
                        compactHeader = true,
                        showScreenHeader = false,
                        content = content,
                    )
                },
            )
        },
        onPlaybackRequested = { request ->
            actions.openPlayer(request.toAppPlayerNavigationRequest())
        },
        onArchivePlaybackRequested = { request ->
            request.toAppArchivePlayerNavigationRequest()?.let(actions::openPlayer)
        },
        onNavigate = { route ->
            AppRouteCodec.decode(route)?.let(onTopLevelDestinationRequested)
        },
    )
}
