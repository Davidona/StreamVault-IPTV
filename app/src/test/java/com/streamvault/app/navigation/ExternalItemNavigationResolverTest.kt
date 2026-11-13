package com.streamvault.app.navigation

import com.google.common.truth.Truth.assertThat
import com.streamvault.core.navigation.AppDestination
import com.streamvault.core.navigation.ExternalItemAction
import com.streamvault.core.navigation.ExternalItemLink
import com.streamvault.core.navigation.ExternalItemType
import com.streamvault.core.navigation.NavigationCommand
import com.streamvault.core.navigation.PlaybackStartMode
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.Episode
import com.streamvault.domain.model.LegacyProvider
import com.streamvault.domain.model.Movie
import com.streamvault.domain.model.PlaybackHistory
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.Series
import com.streamvault.domain.repository.ChannelRepository
import com.streamvault.domain.repository.CategoryRepository
import com.streamvault.domain.repository.MovieRepository
import com.streamvault.domain.repository.PlaybackHistoryRepository
import com.streamvault.domain.repository.ProviderRepository
import com.streamvault.domain.repository.SeriesRepository
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class ExternalItemNavigationResolverTest {
    private val providers = mock<ProviderRepository>()
    private val movies = mock<MovieRepository>()
    private val series = mock<SeriesRepository>()
    private val channels = mock<ChannelRepository>()
    private val history = mock<PlaybackHistoryRepository>()
    private val preferences = mock<PreferencesRepository>()
    private val categories = mock<CategoryRepository>()
    private val resolver = ExternalItemNavigationResolver(providers, movies, series, channels, history, preferences, categories)
    private val provider = LegacyProvider(7, "TV", ProviderType.XTREAM_CODES, "https://provider.example")
    private val movie = Movie(42, "Movie", categoryId = 4, providerId = 7, streamUrl = "https://provider.example/movie")
    private val show = Series(43, "Show", categoryId = 5, providerId = 7, providerSeriesId = "remote-show")
    private val episode = Episode(44, "Episode", 2, 1, streamUrl = "https://provider.example/episode", seriesId = 43, providerId = 7)

    @Before fun setup() = runBlocking {
        whenever(providers.getActiveProvider()).thenReturn(flowOf(provider))
        whenever(movies.getMovie(42)).thenReturn(movie)
        whenever(series.getSeriesById(43)).thenReturn(show)
        whenever(series.getEpisodeById(44)).thenReturn(episode)
        whenever(categories.getCategories(7)).thenReturn(flowOf(listOf(
            Category(4, name = "Movies", type = ContentType.MOVIE), Category(5, name = "Shows", type = ContentType.SERIES))))
        whenever(preferences.getHiddenChannelIds(7)).thenReturn(flowOf(emptySet()))
        for (type in listOf(ContentType.MOVIE, ContentType.SERIES, ContentType.LIVE)) {
            whenever(preferences.getHiddenCategoryIds(7, type)).thenReturn(flowOf(emptySet()))
        }
    }

    @Test fun `movie details resolves only a matching catalogue record`() = runTest {
        assertDestination(resolver.resolve(link(ExternalItemType.MOVIE, 42, ExternalItemAction.DETAILS)),
            AppDestination.MovieDetail(42, AppDestination.Home))
    }

    @Test fun `movie playback carries stored identity and explicit start behavior`() = runTest {
        for ((action, mode) in listOf(ExternalItemAction.RESUME to PlaybackStartMode.RESUME,
            ExternalItemAction.PLAY to PlaybackStartMode.PROMPT, ExternalItemAction.START_OVER to PlaybackStartMode.START_OVER)) {
            val command = resolver.resolve(link(ExternalItemType.MOVIE, 42, action)) as NavigationCommand.OpenPlayer
            assertThat(command.request.streamUrl).isEqualTo(movie.streamUrl)
            assertThat(command.request.internalId).isEqualTo(movie.id)
            assertThat(command.request.providerId).isEqualTo(movie.providerId)
            assertThat(command.request.startMode).isEqualTo(mode)
            assertThat(command.request.returnDestination).isEqualTo(AppDestination.Home)
        }
    }

    @Test fun `inactive providers and mismatched item ownership cannot launch content`() = runTest {
        assertDestination(resolver.resolve(ExternalItemLink(ExternalItemType.MOVIE, 42, 8, ExternalItemAction.RESUME)), AppDestination.Home)
        verify(movies, never()).getMovie(any())
        whenever(movies.getMovie(42)).thenReturn(movie.copy(providerId = 8))
        assertDestination(resolver.resolve(link(ExternalItemType.MOVIE, 42, ExternalItemAction.RESUME)), AppDestination.Home)
    }

    @Test fun `deleted items and protected movies fall back home`() = runTest {
        assertDestination(resolver.resolve(link(ExternalItemType.MOVIE, 99, ExternalItemAction.RESUME)), AppDestination.Home)
        whenever(movies.getMovie(42)).thenReturn(movie.copy(isUserProtected = true))
        assertDestination(resolver.resolve(link(ExternalItemType.MOVIE, 42, ExternalItemAction.RESUME)), AppDestination.Home)
        whenever(movies.getMovie(42)).thenReturn(movie.copy(isAdult = true))
        assertDestination(resolver.resolve(link(ExternalItemType.MOVIE, 42, ExternalItemAction.DETAILS)), AppDestination.Home)
    }

    @Test fun `hidden and protected categories cannot be bypassed by public links`() = runTest {
        whenever(preferences.getHiddenCategoryIds(7, ContentType.MOVIE)).thenReturn(flowOf(setOf(4)))
        assertDestination(resolver.resolve(link(ExternalItemType.MOVIE, 42, ExternalItemAction.RESUME)), AppDestination.Home)
        whenever(preferences.getHiddenCategoryIds(7, ContentType.MOVIE)).thenReturn(flowOf(emptySet()))
        whenever(categories.getCategories(7)).thenReturn(flowOf(listOf(Category(4, name = "Locked", type = ContentType.MOVIE, isUserProtected = true))))
        assertDestination(resolver.resolve(link(ExternalItemType.MOVIE, 42, ExternalItemAction.RESUME)), AppDestination.Home)
    }

    @Test fun `series resume opens its latest unfinished exact episode`() = runTest {
        whenever(history.getLatestEpisodeHistoryForSeries(7, 43)).thenReturn(episodeHistory())
        val command = resolver.resolve(link(ExternalItemType.SERIES, 43, ExternalItemAction.RESUME)) as NavigationCommand.OpenPlayer
        assertThat(command.request.internalId).isEqualTo(44)
        assertThat(command.request.seriesId).isEqualTo(43)
        assertThat(command.request.seasonNumber).isEqualTo(1)
        assertThat(command.request.episodeNumber).isEqualTo(2)
        assertThat(command.request.remoteSeriesId).isEqualTo("remote-show")
        assertThat(command.request.startMode).isEqualTo(PlaybackStartMode.RESUME)
    }

    @Test fun `generic VOD categories retain their hidden and protection rules`() = runTest {
        whenever(categories.getCategories(7)).thenReturn(flowOf(listOf(Category(4, name = "VOD", type = ContentType.VOD))))
        whenever(preferences.getHiddenCategoryIds(7, ContentType.VOD)).thenReturn(flowOf(emptySet()))
        val request = link(ExternalItemType.MOVIE, 42, ExternalItemAction.RESUME)
        assertThat(resolver.resolve(request)).isInstanceOf(NavigationCommand.OpenPlayer::class.java)
        whenever(preferences.getHiddenCategoryIds(7, ContentType.VOD)).thenReturn(flowOf(setOf(4)))
        assertDestination(resolver.resolve(request), AppDestination.Home)
    }

    @Test fun `series without unfinished progress opens details instead of guessing an episode`() = runTest {
        val request = link(ExternalItemType.SERIES, 43, ExternalItemAction.RESUME)
        assertDestination(resolver.resolve(request), AppDestination.SeriesDetail(43, AppDestination.Home))
        whenever(history.getLatestEpisodeHistoryForSeries(7, 43)).thenReturn(episodeHistory().copy(resumePositionMs = 100_000))
        assertDestination(resolver.resolve(request), AppDestination.SeriesDetail(43, AppDestination.Home))
    }

    @Test fun `history cannot redirect a series link into a different series`() = runTest {
        whenever(history.getLatestEpisodeHistoryForSeries(7, 43)).thenReturn(episodeHistory())
        whenever(series.getEpisodeById(44)).thenReturn(episode.copy(seriesId = 99))
        assertDestination(resolver.resolve(link(ExternalItemType.SERIES, 43, ExternalItemAction.RESUME)),
            AppDestination.SeriesDetail(43, AppDestination.Home))
    }

    @Test fun `live links resolve saved channels and reject hidden channels`() = runTest {
        whenever(channels.getChannel(45)).thenReturn(Channel(45, "News", streamUrl = "https://provider.example/live", providerId = 7))
        val request = link(ExternalItemType.LIVE, 45, ExternalItemAction.PLAY)
        val command = resolver.resolve(request) as NavigationCommand.OpenPlayer
        assertThat(command.request.contentType).isEqualTo("LIVE")
        whenever(preferences.getHiddenChannelIds(7)).thenReturn(flowOf(setOf(45)))
        assertDestination(resolver.resolve(request), AppDestination.Home)
    }

    @Test fun `a provider switch during resolution cannot launch stale content`() = runTest {
        whenever(providers.getActiveProvider()).thenReturn(flowOf(provider), flowOf(provider.copy(id = 8)))
        assertDestination(resolver.resolve(link(ExternalItemType.MOVIE, 42, ExternalItemAction.RESUME)), AppDestination.Home)
    }

    @Test fun `hidden raw channels cannot be opened through an unhidden grouped representative`() = runTest {
        whenever(channels.getChannel(45)).thenReturn(Channel(46, "News", streamUrl = "https://provider.example/live", providerId = 7))
        whenever(preferences.getHiddenChannelIds(7)).thenReturn(flowOf(setOf(45)))
        assertDestination(resolver.resolve(link(ExternalItemType.LIVE, 45, ExternalItemAction.PLAY)), AppDestination.Home)
    }

    @Test fun `repeated launches of the same item have distinct playback requests`() = runTest {
        val link = link(ExternalItemType.MOVIE, 42, ExternalItemAction.START_OVER)
        val first = (resolver.resolve(link) as NavigationCommand.OpenPlayer).request
        val second = (resolver.resolve(link) as NavigationCommand.OpenPlayer).request
        assertThat(first.externalLaunchId).isNotNull()
        assertThat(second.externalLaunchId).isNotEqualTo(first.externalLaunchId)
    }

    private fun link(type: ExternalItemType, id: Long, action: ExternalItemAction) = ExternalItemLink(type, id, 7, action)
    private fun episodeHistory() = PlaybackHistory(contentId = 44, contentType = ContentType.SERIES_EPISODE,
        providerId = 7, title = "Episode", streamUrl = "", seriesId = 43,
        resumePositionMs = 30_000, totalDurationMs = 100_000)
    private fun assertDestination(command: NavigationCommand, destination: AppDestination) {
        assertThat((command as NavigationCommand.Navigate).destination).isEqualTo(destination)
    }
}
