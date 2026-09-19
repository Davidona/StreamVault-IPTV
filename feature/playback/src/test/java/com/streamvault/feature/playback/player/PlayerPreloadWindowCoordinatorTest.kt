package com.streamvault.feature.playback.player

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.Episode
import com.streamvault.domain.model.Program
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.Season
import com.streamvault.domain.model.Series
import com.streamvault.domain.model.StreamInfo
import com.streamvault.player.PlayerPreloadContentType
import com.streamvault.player.PlayerPreloadItem
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class PlayerPreloadWindowCoordinatorTest {

    @Test
    fun `episode window resolves adjacent streams and keeps current between them`() = runTest {
        val previous = episode(id = 1L, number = 1)
        val currentEpisode = episode(id = 2L, number = 2)
        val next = episode(id = 3L, number = 3)
        val series = Series(
            id = 10L,
            name = "Example",
            seasons = listOf(
                Season(seasonNumber = 1, episodes = listOf(previous, currentEpisode, next))
            )
        )
        val resolver = mock<PlayerContentResolver>()
        val provider = mock<PlayerProviderCoordinator>()
        val preparation = mock<PlayerPreparationCoordinator>()
        whenever(
            resolver.resolvePlaybackStream(
                logicalUrl = any(),
                internalContentId = any(),
                providerId = any(),
                contentType = eq(ContentType.SERIES_EPISODE),
                currentTitle = any(),
                currentSeries = anyOrNull(),
                currentEpisode = anyOrNull()
            )
        ).thenAnswer { invocation ->
            PlayerPlaybackStreamResolution(
                streamInfo = StreamInfo(
                    url = invocation.getArgument<String>(0),
                    title = invocation.getArgument<String>(4)
                )
            )
        }
        whenever(preparation.prepareStreamForPreload(any()))
            .thenAnswer { invocation -> invocation.getArgument(0) }

        val coordinator = PlayerPreloadWindowCoordinator(resolver, provider, preparation)
        val current = PlayerPreloadItem(
            key = "old-current-key",
            streamInfo = StreamInfo("https://example.com/current.mp4"),
            contentType = PlayerPreloadContentType.VOD
        )

        val window = coordinator.buildEpisodeWindow(
            current = current,
            series = series,
            currentEpisode = currentEpisode,
            providerId = 7L,
            isCurrent = { true }
        )

        assertThat(window?.items?.map(PlayerPreloadItem::key))
            .containsExactly("episode:7:1", "episode:7:2", "episode:7:3")
            .inOrder()
        assertThat(window?.currentIndex).isEqualTo(1)
        assertThat(window?.items?.get(1)?.streamInfo?.url)
            .isEqualTo("https://example.com/current.mp4")
    }

    @Test
    fun `catch up window skips an unresolved neighbor without failing current`() = runTest {
        val channel = archiveChannel()
        val previous = program(1L, 30_000L, 40_000L)
        val selected = program(2L, 50_000L, 60_000L)
        val next = program(3L, 70_000L, 80_000L)
        val resolver = mock<PlayerContentResolver>()
        val provider = mock<PlayerProviderCoordinator>()
        val preparation = mock<PlayerPreparationCoordinator>()
        val requestedStreamIds = mutableListOf<Long>()
        whenever(provider.buildCatchUpUrls(any(), any(), any(), any()))
            .thenAnswer { invocation ->
                requestedStreamIds += invocation.getArgument<Long>(1)
                Result.success(listOf("https://example.com/catchup.m3u8"))
            }
        whenever(
            resolver.resolvePlaybackStream(
                logicalUrl = any(),
                internalContentId = any(),
                providerId = any(),
                contentType = eq(ContentType.LIVE),
                currentTitle = any(),
                currentSeries = anyOrNull(),
                currentEpisode = anyOrNull()
            )
        ).thenReturn(
            PlayerPlaybackStreamResolution(streamInfo = null, resolutionFailureMessage = "unavailable")
        ).thenReturn(
            PlayerPlaybackStreamResolution(
                streamInfo = StreamInfo("https://example.com/next.m3u8")
            )
        )
        whenever(preparation.prepareStreamForPreload(any()))
            .thenAnswer { invocation -> invocation.getArgument(0) }

        val coordinator = PlayerPreloadWindowCoordinator(resolver, provider, preparation)
        val current = PlayerPreloadItem(
            key = "catchup:7:1:50000:60000",
            streamInfo = StreamInfo("https://example.com/current.m3u8"),
            contentType = PlayerPreloadContentType.CATCH_UP
        )

        val window = coordinator.buildCatchUpWindow(
            current = current,
            selectedProgram = selected,
            channel = channel,
            timelinePrograms = listOf(previous, selected, next),
            providerId = 7L,
            isCurrent = { true }
        )

        assertThat(window?.items?.map(PlayerPreloadItem::key))
            .containsExactly("catchup:7:1:50000:60000", "catchup:7:1:70000:80000")
            .inOrder()
        assertThat(window?.currentIndex).isEqualTo(0)
        assertThat(requestedStreamIds).containsExactly(1L, 1L).inOrder()
    }

    @Test
    fun `stale session stops neighbor resolution before publishing a window`() = runTest {
        val previous = episode(id = 1L, number = 1)
        val currentEpisode = episode(id = 2L, number = 2)
        val series = Series(
            id = 10L,
            name = "Example",
            seasons = listOf(Season(seasonNumber = 1, episodes = listOf(previous, currentEpisode)))
        )
        val resolver = mock<PlayerContentResolver>()
        val provider = mock<PlayerProviderCoordinator>()
        val preparation = mock<PlayerPreparationCoordinator>()
        var isCurrent = true
        whenever(
            resolver.resolvePlaybackStream(
                logicalUrl = any(),
                internalContentId = any(),
                providerId = any(),
                contentType = eq(ContentType.SERIES_EPISODE),
                currentTitle = any(),
                currentSeries = anyOrNull(),
                currentEpisode = anyOrNull()
            )
        ).thenReturn(
            PlayerPlaybackStreamResolution(
                streamInfo = StreamInfo("https://example.com/previous.mp4")
            )
        )
        whenever(preparation.prepareStreamForPreload(any())).thenAnswer {
            isCurrent = false
            it.getArgument(0)
        }

        val window = PlayerPreloadWindowCoordinator(resolver, provider, preparation)
            .buildEpisodeWindow(
                current = PlayerPreloadItem(
                    key = "current",
                    streamInfo = StreamInfo("https://example.com/current.mp4"),
                    contentType = PlayerPreloadContentType.VOD
                ),
                series = series,
                currentEpisode = currentEpisode,
                providerId = 7L,
                isCurrent = { isCurrent }
            )

        assertThat(window).isNull()
    }

    @Test
    fun `last episode produces a current-only window`() = runTest {
        val lastEpisode = episode(id = 9L, number = 9)
        val series = Series(
            id = 10L,
            name = "Example",
            seasons = listOf(Season(seasonNumber = 1, episodes = listOf(lastEpisode)))
        )
        val resolver = mock<PlayerContentResolver>()
        val provider = mock<PlayerProviderCoordinator>()
        val preparation = mock<PlayerPreparationCoordinator>()
        val current = PlayerPreloadItem(
            key = "current",
            streamInfo = StreamInfo("https://example.com/current.mp4"),
            contentType = PlayerPreloadContentType.VOD
        )

        val window = PlayerPreloadWindowCoordinator(resolver, provider, preparation)
            .buildEpisodeWindow(
                current = current,
                series = series,
                currentEpisode = lastEpisode,
                providerId = 7L,
                isCurrent = { true }
            )

        assertThat(window?.items).containsExactly(
            current.copy(key = "episode:7:9")
        )
        assertThat(window?.currentIndex).isEqualTo(0)
    }

    private fun episode(id: Long, number: Int) = Episode(
        id = id,
        title = "Episode $id",
        episodeNumber = number,
        seasonNumber = 1,
        streamUrl = "https://example.com/$id.mp4",
        providerId = 7L,
        seriesId = 10L
    )

    private fun archiveChannel() = Channel(
        id = 1L,
        name = "Example TV",
        streamUrl = "xtream://provider/live/1",
        streamId = 42L,
        providerId = 7L,
        catchUpSupported = true,
        catchUpDays = 2
    )

    private fun program(id: Long, start: Long, end: Long) = Program(
        id = id,
        channelId = "channel-1",
        title = "Program $id",
        startTime = start,
        endTime = end,
        hasArchive = true
    )
}
