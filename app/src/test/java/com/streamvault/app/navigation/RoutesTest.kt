package com.streamvault.app.navigation

import com.google.common.truth.Truth.assertThat
import com.streamvault.core.navigation.AppDestination
import com.streamvault.domain.model.AppLandingDestination
import com.streamvault.domain.model.AppTopLevelDestination
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.Episode
import com.streamvault.domain.model.Movie
import com.streamvault.domain.repository.ChannelRepository
import org.junit.Test

class RoutesTest {

    @Test
    fun `liveTv route supports category deep links`() {
        assertThat(Routes.liveTv()).isEqualTo(Routes.LIVE_TV)
        assertThat(Routes.liveTv(42L)).isEqualTo("${Routes.LIVE_TV}?categoryId=42")
    }

    @Test
    fun `livePlayer preserves playback context`() {
        val request = Channel(
                id = 42L,
                name = "News HD",
                streamUrl = "https://example.com/live.m3u8",
                epgChannelId = "news.hd",
                categoryId = 9L,
                providerId = 7L
            ).toLivePlayerRequest(
            categoryId = 9L,
            providerId = 7L,
            isVirtual = false
        )

        assertThat(request.internalId).isEqualTo(42L)
        assertThat(request.categoryId).isEqualTo(9L)
        assertThat(request.providerId).isEqualTo(7L)
        assertThat(request.channelId).isEqualTo("news.hd")
        assertThat(request.contentType).isEqualTo("LIVE")
        assertThat(request.isVirtual).isFalse()
    }

    @Test
    fun `livePlayer falls back to all channels when category is missing`() {
        val request = Channel(
                id = 42L,
                name = "Sports",
                streamUrl = "https://example.com/sports.m3u8",
                providerId = 3L
            ).toLivePlayerRequest(
            categoryId = null,
            providerId = 3L
        )

        assertThat(request.categoryId).isEqualTo(ChannelRepository.ALL_CHANNELS_ID)
        assertThat(request.providerId).isEqualTo(3L)
    }

    @Test
    fun `moviePlayer preserves provider and category context`() {
        val request = Movie(
                id = 15L,
                name = "Film",
                streamUrl = "https://example.com/movie.mp4",
                categoryId = 21L,
                providerId = 5L
            ).toPlayerNavigationRequest()

        assertThat(request.internalId).isEqualTo(15L)
        assertThat(request.categoryId).isEqualTo(21L)
        assertThat(request.providerId).isEqualTo(5L)
        assertThat(request.contentType).isEqualTo("MOVIE")
    }

    @Test
    fun `episodePlayer preserves episode playback context`() {
        val request = Episode(
                id = 33L,
                title = "Pilot",
                episodeNumber = 1,
                seasonNumber = 1,
                streamUrl = "https://example.com/episode.mp4",
                providerId = 11L
            ).toPlayerNavigationRequest()

        assertThat(request.internalId).isEqualTo(33L)
        assertThat(request.providerId).isEqualTo(11L)
        assertThat(request.contentType).isEqualTo("SERIES_EPISODE")
    }

    @Test
    fun `player request supports archive playback context`() {
        val request = playerNavigationRequest(
            streamUrl = "https://example.com/live.m3u8",
            title = "News HD",
            internalId = 42L,
            providerId = 7L,
            contentType = "LIVE",
            archiveStartMs = 1_700_000_000_000L,
            archiveEndMs = 1_700_000_360_000L,
            archiveTitle = "News HD: Morning Show"
        )

        assertThat(request.archiveStartMs).isEqualTo(1_700_000_000_000L)
        assertThat(request.archiveEndMs).isEqualTo(1_700_000_360_000L)
        assertThat(request.archiveTitle).isEqualTo("News HD: Morning Show")
    }

    @Test
    fun `player request preserves typed return destination back to guide`() {
        val returnDestination = AppDestination.Guide(
            categoryId = 9L,
            anchorTimeMs = 1_700_000_360_000L,
            favoritesOnly = false
        )
        val request = Channel(
                id = 42L,
                name = "Sports",
                streamUrl = "https://example.com/live.m3u8",
                providerId = 7L
            ).toLivePlayerRequest(
            categoryId = 9L,
            providerId = 7L,
            returnDestination = returnDestination
        )

        assertThat(request.returnDestination).isEqualTo(returnDestination)
    }

    @Test
    fun `epg route preserves guide context`() {
        val route = Routes.epg(
            categoryId = 21L,
            anchorTime = 1_700_000_000_000L,
            favoritesOnly = true
        )

        assertThat(route).isEqualTo(
            "epg?categoryId=21&anchorTime=1700000000000&favoritesOnly=true"
        )
    }

    @Test
    fun `app landing destinations map to routes`() {
        assertThat(AppLandingDestination.HOME.toAppRoute()).isEqualTo(Routes.HOME)
        assertThat(AppLandingDestination.LIVE_TV.toAppRoute()).isEqualTo(Routes.LIVE_TV)
        assertThat(AppLandingDestination.FIRST_FAVORITE_LIVE.toAppRoute()).isEqualTo(Routes.LIVE_TV)
        assertThat(AppLandingDestination.LAST_WATCHED_LIVE.toAppRoute()).isEqualTo(Routes.LIVE_TV)
        assertThat(AppLandingDestination.MOVIES.toAppRoute()).isEqualTo(Routes.MOVIES)
        assertThat(AppLandingDestination.SERIES.toAppRoute()).isEqualTo(Routes.SERIES)
        assertThat(AppLandingDestination.GUIDE.toAppRoute()).isEqualTo(Routes.EPG)
        assertThat(AppLandingDestination.DOWNLOADS.toAppRoute()).isEqualTo(Routes.DOWNLOADS)
        assertThat(AppLandingDestination.PLUGINS.toAppRoute()).isEqualTo(Routes.PLUGINS)
        assertThat(AppLandingDestination.SETTINGS.toAppRoute()).isEqualTo(Routes.SETTINGS)
    }

    @Test
    fun `top level destinations map to routes including search`() {
        assertThat(AppTopLevelDestination.HOME.toAppRoute()).isEqualTo(Routes.HOME)
        assertThat(AppTopLevelDestination.LIVE_TV.toAppRoute()).isEqualTo(Routes.LIVE_TV)
        assertThat(AppTopLevelDestination.MOVIES.toAppRoute()).isEqualTo(Routes.MOVIES)
        assertThat(AppTopLevelDestination.SERIES.toAppRoute()).isEqualTo(Routes.SERIES)
        assertThat(AppTopLevelDestination.DOWNLOADS.toAppRoute()).isEqualTo(Routes.DOWNLOADS)
        assertThat(AppTopLevelDestination.GUIDE.toAppRoute()).isEqualTo(Routes.EPG)
        assertThat(AppTopLevelDestination.SEARCH.toAppRoute()).isEqualTo(Routes.SEARCH)
        assertThat(AppTopLevelDestination.PLUGINS.toAppRoute()).isEqualTo(Routes.PLUGINS)
        assertThat(AppTopLevelDestination.SETTINGS.toAppRoute()).isEqualTo(Routes.SETTINGS)
    }

    @Test
    fun `top level destinations resolve landing to a visible fallback`() {
        val resolved = AppTopLevelDestination.resolveLandingDestination(
            preferred = AppLandingDestination.MOVIES,
            destinations = listOf(AppTopLevelDestination.HOME, AppTopLevelDestination.SETTINGS)
        )

        assertThat(resolved).isEqualTo(AppLandingDestination.HOME)
    }

    @Test
    fun `live tv landing options include startup player modes when live tv is visible`() {
        val available = AppTopLevelDestination.availableLandingDestinations(
            listOf(AppTopLevelDestination.HOME, AppTopLevelDestination.LIVE_TV, AppTopLevelDestination.SETTINGS)
        )

        assertThat(available).containsAtLeast(
            AppLandingDestination.LIVE_TV,
            AppLandingDestination.FIRST_FAVORITE_LIVE,
            AppLandingDestination.LAST_WATCHED_LIVE
        )
    }

    @Test
    fun `startup player modes fall back when live tv is not visible`() {
        val resolved = AppTopLevelDestination.resolveLandingDestination(
            preferred = AppLandingDestination.FIRST_FAVORITE_LIVE,
            destinations = listOf(AppTopLevelDestination.HOME, AppTopLevelDestination.SETTINGS)
        )

        assertThat(resolved).isEqualTo(AppLandingDestination.HOME)
    }

    @Test
    fun `player route preserves return route back to guide`() {
        val request = Channel(
            id = 42L,
            name = "News HD",
            streamUrl = "https://example.com/live.m3u8",
            epgChannelId = "news.hd",
            categoryId = 9L,
            providerId = 7L
        ).toLivePlayerRequest(
            categoryId = 9L,
            providerId = 7L,
            returnDestination = AppDestination.Guide(
                categoryId = 9L,
                anchorTimeMs = 1_700_000_360_000L,
                favoritesOnly = false
            )
        )

        assertThat(request.returnDestination).isEqualTo(
            AppDestination.Guide(9L, 1_700_000_360_000L, false)
        )
    }
}
