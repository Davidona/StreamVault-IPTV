package com.streamvault.app.navigation

import com.streamvault.core.navigation.AppDestination
import com.streamvault.core.navigation.PlayerNavigationRequest
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.domain.model.ActiveLiveSource
import com.streamvault.domain.model.AppLandingDestination
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.VirtualCategoryIds
import com.streamvault.domain.repository.ChannelRepository
import com.streamvault.domain.repository.CombinedM3uRepository
import com.streamvault.domain.repository.FavoriteRepository
import com.streamvault.domain.repository.PlaybackHistoryRepository
import com.streamvault.domain.repository.ProviderRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.first

data class StartupNavigationTarget(
    val destination: AppDestination,
    val playerRequest: PlayerNavigationRequest? = null
)

class StartupNavigationResolver @Inject constructor(
    private val preferencesRepository: PreferencesRepository,
    private val combinedM3uRepository: CombinedM3uRepository,
    private val favoriteRepository: FavoriteRepository,
    private val playbackHistoryRepository: PlaybackHistoryRepository,
    private val channelRepository: ChannelRepository,
    private val providerRepository: ProviderRepository
) {
    fun destinationFor(landingDestination: AppLandingDestination): AppDestination =
        landingDestination.toAppDestination()

    suspend fun resolvePlayerRequest(
        landingDestination: AppLandingDestination
    ): PlayerNavigationRequest? = when (landingDestination) {
        AppLandingDestination.FIRST_FAVORITE_LIVE -> resolveFirstFavoriteStartupTarget()
        AppLandingDestination.LAST_WATCHED_LIVE -> resolveLastWatchedStartupTarget()
        else -> null
    }

    suspend fun resolve(landingDestination: AppLandingDestination): StartupNavigationTarget {
        return StartupNavigationTarget(
            destination = destinationFor(landingDestination),
            playerRequest = resolvePlayerRequest(landingDestination)
        )
    }

    private suspend fun resolveFirstFavoriteStartupTarget(): PlayerNavigationRequest? {
        if (!preferencesRepository.showFavoritesCategory.first()) return null
        val context = resolveLiveStartupContext() ?: return null
        val favorites = when (context) {
            is LiveStartupContext.Provider ->
                favoriteRepository.getFavorites(context.providerId, ContentType.LIVE).first()
            is LiveStartupContext.Combined ->
                favoriteRepository.getFavorites(context.providerIds, ContentType.LIVE).first()
        }.sortedBy { it.position }
        return resolveStartupChannelTarget(
            channelIds = favorites.map { it.contentId },
            sourceContext = context,
            virtualCategoryId = VirtualCategoryIds.FAVORITES
        )
    }

    private suspend fun resolveLastWatchedStartupTarget(): PlayerNavigationRequest? {
        val context = resolveLiveStartupContext() ?: return null
        val recentHistory = when (context) {
            is LiveStartupContext.Provider ->
                playbackHistoryRepository.getRecentlyWatchedByProvider(context.providerId, limit = 24).first()
            is LiveStartupContext.Combined ->
                playbackHistoryRepository.getRecentlyWatchedByProviders(context.providerIds.toSet(), limit = 24).first()
        }
        return resolveStartupChannelTarget(
            channelIds = recentHistory
                .filter { it.contentType == ContentType.LIVE }
                .sortedByDescending { it.lastWatchedAt }
                .map { it.contentId },
            sourceContext = context,
            virtualCategoryId = VirtualCategoryIds.RECENT
        )
    }

    private suspend fun resolveStartupChannelTarget(
        channelIds: List<Long>,
        sourceContext: LiveStartupContext,
        virtualCategoryId: Long
    ): PlayerNavigationRequest? {
        if (channelIds.isEmpty()) return null
        val hiddenChannelIdsByProvider = sourceContext.providerIds.associateWith { providerId ->
            preferencesRepository.getHiddenChannelIds(providerId).first()
        }
        for (channelId in channelIds.distinct()) {
            val channel = channelRepository.getChannel(channelId) ?: continue
            if (channel.providerId !in sourceContext.providerIds) continue
            if (channel.id in hiddenChannelIdsByProvider[channel.providerId].orEmpty()) continue
            return channel.toLivePlayerRequest(
                categoryId = virtualCategoryId,
                providerId = channel.providerId,
                isVirtual = true,
                combinedProfileId = (sourceContext as? LiveStartupContext.Combined)?.profileId,
                returnDestination = AppDestination.LiveTv()
            )
        }
        return null
    }

    private suspend fun resolveLiveStartupContext(): LiveStartupContext? = when (
        val activeSource = combinedM3uRepository.getActiveLiveSource().first()
    ) {
        is ActiveLiveSource.ProviderSource -> LiveStartupContext.Provider(activeSource.providerId)
        is ActiveLiveSource.CombinedM3uSource -> {
            val providerIds = combinedM3uRepository.getProfile(activeSource.profileId)
                ?.members
                .orEmpty()
                .filter { it.enabled }
                .map { it.providerId }
                .distinct()
            if (providerIds.isEmpty()) null else LiveStartupContext.Combined(activeSource.profileId, providerIds)
        }
        null -> providerRepository.getActiveProvider().first()?.id?.let(LiveStartupContext::Provider)
    }

    private sealed interface LiveStartupContext {
        val providerIds: List<Long>

        data class Provider(val providerId: Long) : LiveStartupContext {
            override val providerIds: List<Long> = listOf(providerId)
        }

        data class Combined(val profileId: Long, override val providerIds: List<Long>) : LiveStartupContext
    }
}

private fun AppLandingDestination.toAppDestination(): AppDestination = when (this) {
    AppLandingDestination.HOME -> AppDestination.Home
    AppLandingDestination.LIVE_TV,
    AppLandingDestination.FIRST_FAVORITE_LIVE,
    AppLandingDestination.LAST_WATCHED_LIVE -> AppDestination.LiveTv()
    AppLandingDestination.MOVIES -> AppDestination.Movies
    AppLandingDestination.SERIES -> AppDestination.Series
    AppLandingDestination.GUIDE -> AppDestination.Guide()
    AppLandingDestination.DOWNLOADS -> AppDestination.Downloads
    AppLandingDestination.PLUGINS -> AppDestination.Plugins
    AppLandingDestination.SETTINGS -> AppDestination.Settings()
}
