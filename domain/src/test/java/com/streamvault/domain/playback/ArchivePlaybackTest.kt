package com.streamvault.domain.playback

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.Program
import org.junit.Test

class ArchivePlaybackTest {

    @Test
    fun `catch-up channel does not allow future program replay`() {
        val now = 1_000_000L
        val channel = Channel(
            id = 42L,
            name = "News",
            providerId = 7L,
            catchUpSupported = true,
            catchUpDays = 2,
            streamUrl = "xtream://7/live/42",
            streamId = 42L
        )
        val program = Program(
            channelId = "news",
            title = "Late Bulletin",
            startTime = now + 60_000L,
            endTime = now + 120_000L
        )

        assertThat(channel.isArchivePlayable(program, now)).isFalse()
    }

    @Test
    fun `catch-up channel allows completed program inside catch-up window`() {
        val now = 3 * 86_400_000L
        val channel = Channel(
            id = 42L,
            name = "News",
            providerId = 7L,
            catchUpSupported = true,
            catchUpDays = 2,
            streamUrl = "xtream://7/live/42",
            streamId = 42L
        )
        val program = Program(
            channelId = "news",
            title = "Morning Update",
            startTime = now - 86_400_000L,
            endTime = now - 30_000L
        )

        assertThat(channel.isArchivePlayable(program, now)).isTrue()
    }

    @Test
    fun `catch-up eligibility includes exact completion and window-start boundaries`() {
        val now = 3 * 86_400_000L
        val channel = Channel(
            id = 42L,
            name = "News",
            providerId = 7L,
            catchUpSupported = true,
            catchUpDays = 2,
            streamUrl = "xtream://7/live/42",
            streamId = 42L
        )
        val program = Program(
            channelId = "news",
            title = "Boundary Bulletin",
            startTime = now - (2 * 86_400_000L),
            endTime = now
        )

        assertThat(channel.isArchivePlayable(program, now)).isTrue()
    }

    @Test
    fun `provider archive flag keeps replay enabled with replay metadata`() {
        val channel = Channel(
            id = 42L,
            name = "News",
            providerId = 7L,
            streamUrl = "xtream://7/live/42",
            streamId = 42L
        )
        val program = Program(
            channelId = "news",
            title = "Curated Replay",
            startTime = 100_000L,
            endTime = 200_000L,
            hasArchive = true
        )

        assertThat(channel.isArchivePlayable(program, now = 150_000L)).isTrue()
    }

    @Test
    fun `provider archive flag is not playable without replay metadata`() {
        val channel = Channel(
            id = 42L,
            name = "News",
            providerId = 7L
        )
        val program = Program(
            channelId = "news",
            title = "Curated Replay",
            startTime = 100_000L,
            endTime = 200_000L,
            hasArchive = true
        )

        assertThat(channel.isArchivePlayable(program, now = 150_000L)).isFalse()
    }

    @Test
    fun `catch-up window excludes expired programs`() {
        val now = 5 * 86_400_000L
        val channel = Channel(
            id = 42L,
            name = "News",
            providerId = 7L,
            catchUpSupported = true,
            catchUpDays = 2,
            streamUrl = "xtream://7/live/42",
            streamId = 42L
        )
        val program = Program(
            channelId = "news",
            title = "Old Bulletin",
            startTime = now - (3 * 86_400_000L),
            endTime = now - (3 * 86_400_000L) + 60_000L
        )

        assertThat(channel.isArchivePlayable(program, now)).isFalse()
    }

    @Test
    fun `unknown catch-up window requires provider-marked archive program`() {
        val now = 5 * 86_400_000L
        val channel = Channel(
            id = 42L,
            name = "News",
            providerId = 7L,
            catchUpSupported = true,
            catchUpDays = 0,
            streamUrl = "stalker://7/live/42?cmd=ffmpeg%20http%3A%2F%2Flocalhost%2Fch%2F42_"
        )
        val ordinaryProgram = Program(
            channelId = "news",
            title = "Unmarked Bulletin",
            startTime = now - 60_000L,
            endTime = now - 30_000L
        )
        val archivedProgram = ordinaryProgram.copy(hasArchive = true)

        assertThat(channel.isArchivePlayable(ordinaryProgram, now)).isFalse()
        assertThat(channel.isArchivePlayable(archivedProgram, now)).isTrue()
    }

    @Test
    fun `capability classifies xtream stream id replay`() {
        val capability = Channel(
            id = 42L,
            name = "News",
            providerId = 7L,
            catchUpSupported = true,
            catchUpDays = 3,
            streamUrl = "xtream://7/live/42",
            streamId = 42L
        ).archivePlaybackCapability()

        assertThat(capability.mechanism).isEqualTo(ArchiveReplayMechanism.XTREAM_STREAM_ID)
        assertThat(capability.canBuildReplayCandidate).isTrue()
        assertThat(capability.windowDays).isEqualTo(3)
    }
}
