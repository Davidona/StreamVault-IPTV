package com.streamvault.feature.playback.navigation

import com.google.common.truth.Truth.assertThat
import com.streamvault.core.navigation.AppDestination
import com.streamvault.core.navigation.PlayerNavigationRequest
import org.junit.Test

class PlaybackNavigationPolicyTest {

    @Test
    fun `rejects missing blank and unsupported playback requests`() {
        assertThat(safePlayerNavigationRequest(null)).isNull()
        assertThat(
            safePlayerNavigationRequest(
                PlayerNavigationRequest(streamUrl = "", title = "Blank")
            )
        ).isNull()
        assertThat(
            safePlayerNavigationRequest(
                PlayerNavigationRequest(streamUrl = "javascript:alert(1)", title = "Unsafe")
            )
        ).isNull()
    }

    @Test
    fun `accepts every supported playback scheme without changing request fields`() {
        val schemes = listOf(
            "http",
            "https",
            "rtsp",
            "rtmp",
            "rtsps",
            "mms",
            "xtream",
            "stalker",
            "content",
            "file"
        )

        schemes.forEach { scheme ->
            val request = PlayerNavigationRequest(
                streamUrl = "$scheme://example.test/live",
                title = "Channel",
                channelId = "channel-7",
                internalId = 7L,
                categoryId = 8L,
                providerId = 9L,
                isVirtual = true,
                combinedProfileId = 10L,
                combinedSourceFilterProviderId = 11L,
                contentType = "SERIES_EPISODE",
                artworkUrl = "https://example.test/poster.jpg",
                archiveStartMs = 12L,
                archiveEndMs = 13L,
                archiveTitle = "Archive",
                seriesId = 14L,
                seasonNumber = 2,
                episodeNumber = 3,
                episodeId = 15L
            )

            assertThat(safePlayerNavigationRequest(request)).isSameInstanceAs(request)
        }
    }

    @Test
    fun `completion preserves the exact originating detail route including logical variants`() {
        val origins = listOf(
            AppDestination.MovieDetail(42, AppDestination.Search("fixture")),
            AppDestination.SeriesDetail(73, AppDestination.Series)
        )
        origins.forEach { origin ->
            val request = PlayerNavigationRequest("file:///fixture.mp4", "Fixture",
                contentType = "MOVIE", internalId = 99, returnDestination = origin)
            assertThat(playbackCompletionDestination(request)).isSameInstanceAs(origin)
        }
    }

    @Test
    fun `direct movie completion opens its detail and retains its parent destination`() {
        listOf("MOVIE", "VOD").forEach { type ->
            val request = PlayerNavigationRequest("file:///fixture.mp4", "Fixture",
                contentType = type, internalId = 42, returnDestination = AppDestination.Home)
            assertThat(playbackCompletionDestination(request))
                .isEqualTo(AppDestination.MovieDetail(42, AppDestination.Home))
        }
    }

    @Test
    fun `direct episode completion opens the parent series rather than an episode detail`() {
        val request = PlayerNavigationRequest("file:///fixture.mp4", "Fixture",
            contentType = "SERIES_EPISODE", internalId = 99, seriesId = 73,
            returnDestination = AppDestination.Home)
        assertThat(playbackCompletionDestination(request))
            .isEqualTo(AppDestination.SeriesDetail(73, AppDestination.Home))
    }

    @Test
    fun `completion without a catalog identity keeps the launch screen`() {
        listOf("MOVIE", "SERIES_EPISODE").forEach { type ->
            val request = PlayerNavigationRequest("file:///fixture.mp4", "Fixture",
                contentType = type, internalId = -1, seriesId = 0,
                returnDestination = AppDestination.Downloads)
            assertThat(playbackCompletionDestination(request)).isEqualTo(AppDestination.Downloads)
        }
    }

    @Test
    fun `completion with no known destination allows the existing back-stack fallback`() {
        val request = PlayerNavigationRequest("file:///fixture.mp4", "Fixture", contentType = "MOVIE")
        assertThat(playbackCompletionDestination(request)).isNull()
    }

    @Test
    fun `other playback kinds retain their launch destination even with a numeric id`() {
        val request = PlayerNavigationRequest("file:///fixture.mp4", "Fixture",
            contentType = "LIVE", internalId = 42, returnDestination = AppDestination.Guide())
        assertThat(playbackCompletionDestination(request)).isEqualTo(AppDestination.Guide())
    }

    @Test
    fun `addon playback retains its origin instead of inventing a local catalog route`() {
        val request = PlayerNavigationRequest("file:///fixture.mp4", "Fixture",
            contentType = "MOVIE", internalId = 42,
            stremioPlaybackReference = com.streamvault.core.navigation.StremioPlaybackReference(
                mediaType = "movie", exactVideoId = "fixture", catalogOriginKey = "fixture",
                streamOriginKey = "fixture"))
        assertThat(playbackCompletionDestination(request)).isNull()
    }

}
