package com.streamvault.app.navigation

import com.streamvault.core.navigation.AppDestination
import com.streamvault.core.navigation.ExternalItemAction
import com.streamvault.core.navigation.ExternalItemLink
import com.streamvault.core.navigation.ExternalItemType
import com.streamvault.core.navigation.NavigationCommand
import com.streamvault.core.navigation.NavigationOptions
import com.streamvault.core.navigation.PlaybackStartMode
import com.streamvault.core.navigation.PlayerNavigationRequest
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.PlaybackWatchedStatus
import com.streamvault.domain.repository.ChannelRepository
import com.streamvault.domain.repository.CategoryRepository
import com.streamvault.domain.repository.MovieRepository
import com.streamvault.domain.repository.PlaybackHistoryRepository
import com.streamvault.domain.repository.ProviderRepository
import com.streamvault.domain.repository.SeriesRepository
import com.streamvault.domain.util.isPlaybackComplete
import javax.inject.Inject
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Resolve only trusted catalogue records, never playback fields supplied by the caller. */
class ExternalItemNavigationResolver @Inject constructor(
    private val providers: ProviderRepository,
    private val movies: MovieRepository,
    private val series: SeriesRepository,
    private val channels: ChannelRepository,
    private val history: PlaybackHistoryRepository,
    private val preferences: PreferencesRepository,
    private val categories: CategoryRepository
) {
    suspend fun resolve(link: ExternalItemLink): NavigationCommand = withContext(Dispatchers.IO) {
        // Detail screens use the active catalogue. Never switch providers implicitly from a link.
        if (providers.getActiveProvider().first()?.id != link.providerId) return@withContext home()
        val command = when (link.type) {
            ExternalItemType.MOVIE -> resolveMovie(link)
            ExternalItemType.SERIES -> resolveSeries(link)
            ExternalItemType.EPISODE -> resolveEpisode(link)
            ExternalItemType.LIVE -> resolveLive(link)
        }
        // A provider can change while Room/network work is suspended.
        if (providers.getActiveProvider().first()?.id == link.providerId) command ?: home() else home()
    }

    private suspend fun resolveMovie(link: ExternalItemLink): NavigationCommand? {
        val movie = movies.getMovie(link.id)?.takeIf { it.providerId == link.providerId } ?: return null
        if (!accessible(link, ContentType.MOVIE, movie.categoryId, movie.isAdult || movie.isUserProtected)) return null
        return if (link.action == ExternalItemAction.DETAILS) {
            destination(AppDestination.MovieDetail(movie.id, AppDestination.Home))
        } else player(movie.toPlayerNavigationRequest(AppDestination.Home), link.action)
    }

    private suspend fun resolveSeries(link: ExternalItemLink): NavigationCommand? {
        val show = series.getSeriesById(link.id)?.takeIf { it.providerId == link.providerId } ?: return null
        if (!accessible(link, ContentType.SERIES, show.categoryId, show.isAdult || show.isUserProtected)) return null
        val details = destination(AppDestination.SeriesDetail(show.id, AppDestination.Home))
        if (link.action == ExternalItemAction.DETAILS) return details
        val last = history.getLatestEpisodeHistoryForSeries(link.providerId, show.id) ?: return details
        if (last.watchedStatus != PlaybackWatchedStatus.IN_PROGRESS || last.resumePositionMs <= 0 ||
            isPlaybackComplete(last.resumePositionMs, last.totalDurationMs)) return details
        return resolveEpisode(ExternalItemLink(ExternalItemType.EPISODE, last.contentId,
            link.providerId, ExternalItemAction.RESUME), expectedSeriesId = show.id) ?: details
    }

    private suspend fun resolveEpisode(link: ExternalItemLink, expectedSeriesId: Long? = null): NavigationCommand? {
        val episode = series.getEpisodeById(link.id)?.takeIf { it.providerId == link.providerId } ?: return null
        if (expectedSeriesId != null && episode.seriesId != expectedSeriesId) return null
        val show = series.getSeriesById(episode.seriesId)?.takeIf { it.providerId == link.providerId } ?: return null
        if (!accessible(link, ContentType.SERIES, show.categoryId,
                show.isAdult || show.isUserProtected)) return null
        return player(episode.toPlayerNavigationRequest(AppDestination.Home,
            show.providerSeriesId ?: show.seriesId.takeIf { it > 0 }?.toString()), link.action)
    }

    private suspend fun resolveLive(link: ExternalItemLink): NavigationCommand? {
        val hiddenIds = preferences.getHiddenChannelIds(link.providerId).first()
        if (link.id in hiddenIds) return null
        val channel = channels.getChannel(link.id)?.takeIf { it.providerId == link.providerId } ?: return null
        if (channel.id in hiddenIds) return null
        if (!accessible(link, ContentType.LIVE, channel.categoryId, channel.isAdult || channel.isUserProtected)) return null
        return player(channel.toLivePlayerRequest(returnDestination = AppDestination.Home), link.action)
    }

    private suspend fun accessible(link: ExternalItemLink, type: ContentType, categoryId: Long?,
        protected: Boolean): Boolean {
        // External launches never inherit a PIN unlock from an unrelated in-app browsing session.
        if (protected || categoryId in preferences.getHiddenCategoryIds(link.providerId, type).first()) return false
        if (categoryId == null) return true
        // Use raw category protection, not a browsing projection which may hide locked rows or
        // clear their lock flags for a currently unlocked session.
        val stored = categories.getCategories(link.providerId).first().filter { it.id == categoryId }
        val category = stored.firstOrNull { it.type == type }
            ?: stored.firstOrNull { type != ContentType.LIVE && it.type == ContentType.VOD }
            ?: return false
        if (category.type != type && categoryId in preferences.getHiddenCategoryIds(link.providerId, category.type).first()) return false
        return !category.isAdult && !category.isUserProtected
    }

    private fun player(request: PlayerNavigationRequest, action: ExternalItemAction): NavigationCommand =
        NavigationCommand.OpenPlayer(request.copy(startMode = when (action) {
            ExternalItemAction.RESUME -> PlaybackStartMode.RESUME
            ExternalItemAction.START_OVER -> PlaybackStartMode.START_OVER
            else -> PlaybackStartMode.PROMPT
        }, externalLaunchId = launchIds.incrementAndGet()))

    private fun destination(target: AppDestination) =
        NavigationCommand.Navigate(target, NavigationOptions(launchSingleTop = true))

    private fun home() = destination(AppDestination.Home)

    private companion object { val launchIds = AtomicLong() }
}
