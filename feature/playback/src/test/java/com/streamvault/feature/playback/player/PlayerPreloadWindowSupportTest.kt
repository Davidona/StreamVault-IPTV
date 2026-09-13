package com.streamvault.feature.playback.player

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.Episode
import com.streamvault.domain.model.Program
import com.streamvault.domain.model.Season
import com.streamvault.domain.model.Series
import org.junit.Test

class PlayerPreloadWindowSupportTest {

    @Test
    fun `catch-up timeline includes selected program and sorts the bounded guide slices`() {
        val selected = program(id = 2L, start = 2_000L, end = 3_000L)

        val timeline = buildCatchUpPreloadTimeline(
            programHistory = listOf(program(id = 1L, start = 1_000L, end = 2_000L)),
            currentProgram = program(id = 3L, start = 3_000L, end = 4_000L),
            upcomingPrograms = listOf(program(id = 4L, start = 4_000L, end = 5_000L)),
            selectedProgram = selected
        )

        assertThat(timeline.map { it.id }).containsExactly(1L, 2L, 3L, 4L).inOrder()
    }

    @Test
    fun `refresh fingerprint changes when current stream or neighbor keys change`() {
        val stream = com.streamvault.domain.model.StreamInfo(url = "https://example.com/current.m3u8")
        val baseline = buildPreloadWindowRefreshFingerprint(
            contentType = com.streamvault.player.PlayerPreloadContentType.VOD,
            providerId = 7L,
            currentKey = "current",
            neighborKeys = listOf("previous", "next"),
            currentStreamInfo = stream
        )

        assertThat(
            buildPreloadWindowRefreshFingerprint(
                contentType = com.streamvault.player.PlayerPreloadContentType.VOD,
                providerId = 7L,
                currentKey = "current",
                neighborKeys = listOf("previous", "other"),
                currentStreamInfo = stream
            )
        ).isNotEqualTo(baseline)
        assertThat(
            buildPreloadWindowRefreshFingerprint(
                contentType = com.streamvault.player.PlayerPreloadContentType.VOD,
                providerId = 7L,
                currentKey = "current",
                neighborKeys = listOf("previous", "next"),
                currentStreamInfo = stream.copy(url = "https://example.com/refreshed.m3u8")
            )
        ).isNotEqualTo(baseline)
    }

    @Test
    fun `episode neighbors cross season boundaries in playback order`() {
        val first = episode(id = 1, season = 1, number = 1)
        val second = episode(id = 2, season = 1, number = 2)
        val third = episode(id = 3, season = 2, number = 1)
        val series = Series(
            id = 10L,
            name = "Example",
            seasons = listOf(
                Season(seasonNumber = 2, episodes = listOf(third)),
                Season(seasonNumber = 1, episodes = listOf(second, first))
            )
        )

        val neighbors = buildEpisodePreloadNeighbors(series, third)

        assertThat(neighbors?.map(Episode::id)).containsExactly(2L, 3L).inOrder()
    }

    @Test
    fun `episode neighbor helper handles first and last episodes`() {
        val first = episode(id = 1, season = 1, number = 1)
        val second = episode(id = 2, season = 1, number = 2)
        val series = Series(
            id = 10L,
            name = "Example",
            seasons = listOf(Season(seasonNumber = 1, episodes = listOf(first, second)))
        )

        assertThat(buildEpisodePreloadNeighbors(series, first)?.map(Episode::id))
            .containsExactly(1L, 2L)
            .inOrder()
        assertThat(buildEpisodePreloadNeighbors(series, second)?.map(Episode::id))
            .containsExactly(1L, 2L)
            .inOrder()
    }

    @Test
    fun `catch up neighbors are chronological and archive playable`() {
        val channel = archiveChannel()
        val previous = program(id = 1L, start = 30_000L, end = 40_000L, hasArchive = true)
        val selected = program(id = 2L, start = 50_000L, end = 60_000L, hasArchive = true)
        val future = program(id = 3L, start = 130_000L, end = 140_000L, hasArchive = false)
        val next = program(id = 4L, start = 90_000L, end = 100_000L, hasArchive = true)

        val neighbors = buildCatchUpPreloadNeighbors(
            selectedProgram = selected,
            channel = channel,
            timelinePrograms = listOf(next, future, selected, previous),
            now = 120_000L
        )

        assertThat(neighbors?.map(Program::id)).containsExactly(1L, 2L, 4L).inOrder()
    }

    @Test
    fun `catch up neighbor helper returns null when selected program is absent`() {
        val channel = archiveChannel()
        val selected = program(id = 2L, start = 50_000L, end = 60_000L, hasArchive = true)

        assertThat(
            buildCatchUpPreloadNeighbors(
                selectedProgram = selected,
                channel = channel,
                timelinePrograms = emptyList(),
                now = 120_000L
            )
        ).isNull()
    }

    @Test
    fun `preload keys are stable and do not contain stream urls`() {
        val episodeKey = episodePreloadKey(providerId = 7L, episode = episode(episodeId = 42L))
        val catchUpKey = catchUpPreloadKey(
            providerId = 7L,
            channel = archiveChannel(id = 8L),
            program = program(id = 9L, start = 50_000L, end = 60_000L, hasArchive = true)
        )

        assertThat(episodeKey).isEqualTo("episode:7:42")
        assertThat(catchUpKey).isEqualTo("catchup:7:8:50000:60000")
        assertThat(episodeKey).doesNotContain("http")
        assertThat(catchUpKey).doesNotContain("http")
    }

    private fun episode(
        id: Long = 1L,
        season: Int = 1,
        number: Int = 1,
        episodeId: Long = 0L
    ) = Episode(
        id = id,
        title = "Episode $id",
        episodeNumber = number,
        seasonNumber = season,
        streamUrl = "https://example.com/$id.mp4",
        episodeId = episodeId
    )

    private fun archiveChannel(id: Long = 1L) = Channel(
        id = id,
        name = "Example TV",
        streamUrl = "xtream://provider/live/$id",
        streamId = id,
        providerId = 7L,
        catchUpSupported = true,
        catchUpDays = 2
    )

    private fun program(id: Long, start: Long, end: Long, hasArchive: Boolean = false) = Program(
        id = id,
        channelId = "channel-1",
        title = "Program $id",
        startTime = start,
        endTime = end,
        hasArchive = hasArchive
    )
}
