package com.streamvault.feature.playback.player

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.Episode
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.Season
import com.streamvault.domain.model.Series
import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.provider.PlayerPlaybackResolutionFailure
import com.streamvault.domain.provider.PlayerPlaybackResolver
import com.streamvault.domain.repository.ChannelRepository
import com.streamvault.domain.repository.MovieRepository
import com.streamvault.domain.repository.SeriesRepository
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class PlayerContentResolutionSupportTest {

    @Test
    fun shouldUseStoredLiveStreamInfo_usesStoredInfoForPrimaryLiveUrl() {
        val primaryUrl = "https://provider.test/live/61351.m3u8"

        assertThat(
            shouldUseStoredLiveStreamInfo(
                logicalUrl = primaryUrl,
                storedStreamUrl = primaryUrl
            )
        ).isTrue()
    }

    @Test
    fun shouldUseStoredLiveStreamInfo_bypassesStoredInfoForTransportFallback() {
        val primaryUrl = "https://provider.test/live/61351.m3u8"
        val fallbackUrl = "https://provider.test/live/61351.ts"

        assertThat(
            shouldUseStoredLiveStreamInfo(
                logicalUrl = fallbackUrl,
                storedStreamUrl = primaryUrl
            )
        ).isFalse()
    }

    @Test
    fun shouldStartLiveTimeshiftForStreamClass_skipsMpegTsFallback() {
        assertThat(shouldStartLiveTimeshiftForStreamClass("MPEG-TS fallback")).isFalse()
    }

    @Test
    fun shouldStartLiveTimeshiftForStreamClass_allowsPrimaryLivePlayback() {
        assertThat(shouldStartLiveTimeshiftForStreamClass("Primary")).isTrue()
    }

    @Test
    fun `playback stream resolution keeps its failure message with the result`() {
        val resolution = PlayerPlaybackStreamResolution(
            streamInfo = null,
            resolutionFailureMessage = "The provider returned no playable stream."
        )

        assertThat(resolution.failureMessage)
            .isEqualTo("The provider returned no playable stream.")
    }

    @Test
    fun `resolvePlayerPlaybackStreamInfo uses current episode playback identity`() = runBlocking {
        val episode = episode(id = 21L, stableEpisodeId = 321L)
        val expected = StreamInfo(
            url = "https://example.test/episode.m3u8",
            headers = mapOf("Cookie" to "session=abc")
        )
        val seriesRepository: SeriesRepository = mock()
        whenever(seriesRepository.getEpisodeStreamInfo(eq(episode))).thenReturn(Result.success(expected))

        val result = resolvePlayerPlaybackStreamInfo(
            logicalUrl = episode.streamUrl,
            internalContentId = 321L,
            providerId = episode.providerId,
            contentType = ContentType.SERIES_EPISODE,
            currentTitle = "Current Title",
            currentSeries = null,
            currentEpisode = episode,
            channelRepository = mock<ChannelRepository>(),
            movieRepository = mock<MovieRepository>(),
            seriesRepository = seriesRepository,
            playerPlaybackResolver = mock<PlayerPlaybackResolver>()
        )

        assertThat(result.streamInfo?.url).isEqualTo(expected.url)
        assertThat(result.streamInfo?.headers).containsEntry("Cookie", "session=abc")
        assertThat(result.streamInfo?.title).isEqualTo("Current Title")
    }

    @Test
    fun `resolvePlayerPlaybackStreamInfo finds series episode by playback identity`() = runBlocking {
        val episode = episode(id = 21L, stableEpisodeId = 321L)
        val expected = StreamInfo(
            url = "https://example.test/episode.m3u8",
            userAgent = "StreamVault"
        )
        val seriesRepository: SeriesRepository = mock()
        whenever(seriesRepository.getEpisodeStreamInfo(eq(episode))).thenReturn(Result.success(expected))

        val result = resolvePlayerPlaybackStreamInfo(
            logicalUrl = episode.streamUrl,
            internalContentId = 321L,
            providerId = episode.providerId,
            contentType = ContentType.SERIES_EPISODE,
            currentTitle = "Current Title",
            currentSeries = series(episode),
            currentEpisode = null,
            channelRepository = mock<ChannelRepository>(),
            movieRepository = mock<MovieRepository>(),
            seriesRepository = seriesRepository,
            playerPlaybackResolver = mock<PlayerPlaybackResolver>()
        )

        assertThat(result.streamInfo?.url).isEqualTo(expected.url)
        assertThat(result.streamInfo?.userAgent).isEqualTo("StreamVault")
        assertThat(result.streamInfo?.title).isEqualTo("Current Title")
    }

    @Test
    fun `resolvePlayerPlaybackStreamInfo preserves provider resolution failures`() = runBlocking {
        val resolver: PlayerPlaybackResolver = mock()
        whenever(
            resolver.resolveAndCommitMetadata(
                url = "xtream://7/live/61351?ext=m3u8",
                fallbackProviderId = 7L,
                fallbackStreamId = null,
                fallbackContentType = ContentType.LIVE,
                fallbackContainerExtension = null,
                preferStableUrl = false
            )
        ).thenReturn(
            Result.error(
                "stalker unavailable",
                PlayerPlaybackResolutionFailure("stalker unavailable")
            )
        )

        val result = resolvePlayerPlaybackStreamInfo(
            logicalUrl = "xtream://7/live/61351?ext=m3u8",
            internalContentId = 0L,
            providerId = 7L,
            contentType = ContentType.LIVE,
            currentTitle = "Current Title",
            currentSeries = null,
            currentEpisode = null,
            channelRepository = mock<ChannelRepository>(),
            movieRepository = mock<MovieRepository>(),
            seriesRepository = mock<SeriesRepository>(),
            playerPlaybackResolver = resolver
        )

        assertThat(result.streamInfo).isNull()
        assertThat(result.resolutionFailureMessage).isEqualTo("stalker unavailable")
    }

    private companion object {
        fun series(episode: Episode) = Series(
            id = 30L,
            name = "Series",
            providerId = episode.providerId,
            seasons = listOf(Season(seasonNumber = episode.seasonNumber, episodes = listOf(episode)))
        )

        fun episode(
            id: Long = 21L,
            stableEpisodeId: Long = 321L
        ) = Episode(
            id = id,
            title = "Episode 2",
            episodeNumber = 2,
            seasonNumber = 1,
            streamUrl = "https://example.test/direct.m3u8",
            providerId = 1L,
            seriesId = 30L,
            episodeId = stableEpisodeId
        )
    }
}
