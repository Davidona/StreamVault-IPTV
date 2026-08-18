package com.streamvault.app.navigation

import com.streamvault.app.MainActivity
import com.streamvault.domain.model.ActiveLiveSource
import com.streamvault.domain.model.AppLandingDestination
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.VirtualCategoryIds
import kotlinx.coroutines.flow.first

internal suspend fun resolveStartupPlayerRequest(
    mainActivity: MainActivity,
    landingDestination: AppLandingDestination
): PlayerNavigationRequest? = when (landingDestination) {
    AppLandingDestination.FIRST_FAVORITE_LIVE -> resolveFirstFavoriteStartupTarget(mainActivity)
    AppLandingDestination.LAST_WATCHED_LIVE -> resolveLastWatchedStartupTarget(mainActivity)
    else -> null
}

private suspend fun resolveFirstFavoriteStartupTarget(
    mainActivity: MainActivity
): PlayerNavigationRequest? {
    if (!mainActivity.preferencesRepository.showFavoritesCategory.first()) return null
    val context = resolveLiveStartupContext(mainActivity) ?: return null
    val favorites = when (context) {
        is LiveStartupContext.Provider -> mainActivity.favoriteRepository.getFavorites(context.providerId, ContentType.LIVE).first()
        is LiveStartupContext.Combined -> mainActivity.favoriteRepository.getFavorites(context.providerIds, ContentType.LIVE).first()
    }.sortedBy { it.position }
    return resolveStartupChannelTarget(
        mainActivity = mainActivity,
        channelIds = favorites.map { it.contentId },
        sourceContext = context,
        virtualCategoryId = VirtualCategoryIds.FAVORITES
    )
}

private suspend fun resolveLastWatchedStartupTarget(
    mainActivity: MainActivity
): PlayerNavigationRequest? {
    val context = resolveLiveStartupContext(mainActivity) ?: return null
    val recentHistory = when (context) {
        is LiveStartupContext.Provider -> mainActivity.playbackHistoryRepository.getRecentlyWatchedByProvider(context.providerId, limit = 24).first()
        is LiveStartupContext.Combined -> mainActivity.playbackHistoryRepository.getRecentlyWatchedByProviders(context.providerIds.toSet(), limit = 24).first()
    }
    return resolveStartupChannelTarget(
        mainActivity = mainActivity,
        channelIds = recentHistory
            .filter { it.contentType == ContentType.LIVE }
            .sortedByDescending { it.lastWatchedAt }
            .map { it.contentId },
        sourceContext = context,
        virtualCategoryId = VirtualCategoryIds.RECENT
    )
}

private suspend fun resolveStartupChannelTarget(
    mainActivity: MainActivity,
    channelIds: List<Long>,
    sourceContext: LiveStartupContext,
    virtualCategoryId: Long
): PlayerNavigationRequest? {
    if (channelIds.isEmpty()) return null
    val hiddenChannelIdsByProvider = sourceContext.providerIds.associateWith { providerId ->
        mainActivity.preferencesRepository.getHiddenChannelIds(providerId).first()
    }
    for (channelId in channelIds.distinct()) {
        val channel = mainActivity.channelRepository.getChannel(channelId) ?: continue
        if (channel.providerId !in sourceContext.providerIds) continue
        if (channel.id in hiddenChannelIdsByProvider[channel.providerId].orEmpty()) continue
        return Routes.livePlayer(
            channel = channel,
            categoryId = virtualCategoryId,
            providerId = channel.providerId,
            isVirtual = true,
            combinedProfileId = (sourceContext as? LiveStartupContext.Combined)?.profileId,
            returnRoute = Routes.LIVE_TV
        )
    }
    return null
}

private suspend fun resolveLiveStartupContext(
    mainActivity: MainActivity
): LiveStartupContext? {
    return when (val activeSource = mainActivity.combinedM3uRepository.getActiveLiveSource().first()) {
        is ActiveLiveSource.ProviderSource -> LiveStartupContext.Provider(activeSource.providerId)
        is ActiveLiveSource.CombinedM3uSource -> {
            val providerIds = mainActivity.combinedM3uRepository.getProfile(activeSource.profileId)
                ?.members
                .orEmpty()
                .filter { it.enabled }
                .map { it.providerId }
                .distinct()
            if (providerIds.isEmpty()) null else LiveStartupContext.Combined(activeSource.profileId, providerIds)
        }
        null -> {
            mainActivity.providerRepository.getActiveProvider().first()?.id?.let { providerId ->
                LiveStartupContext.Provider(providerId)
            }
        }
    }
}

private sealed interface LiveStartupContext {
    val providerIds: List<Long>

    data class Provider(val providerId: Long) : LiveStartupContext {
        override val providerIds: List<Long> = listOf(providerId)
    }

    data class Combined(val profileId: Long, override val providerIds: List<Long>) : LiveStartupContext
}

