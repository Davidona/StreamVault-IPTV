package com.streamvault.feature.playback.player

import com.google.common.truth.Truth.assertThat
import com.streamvault.core.navigation.StremioPlaybackReference
import org.junit.Test

class StremioPlaybackHistorySupportTest {
    @Test
    fun `history snapshot preserves exact episode identity and only progress metadata`() {
        val history = buildStremioPlaybackHistorySnapshot(
            reference = StremioPlaybackReference(
                mediaType = "series",
                exactVideoId = "tt12345:1:4",
                parentSeriesId = "tt12345",
                catalogOriginKey = "a".repeat(64),
                metaOriginKey = "b".repeat(64),
                streamOriginKey = "c".repeat(64)
            ),
            positionMs = 120_000L,
            durationMs = 600_000L,
            title = "Series · Episode 4",
            posterUrl = "https://images.example.test/episode.jpg",
            lastWatchedAt = 1_700_000_000_000L
        )

        assertThat(history?.contentKey).isEqualTo("stremio:global:series-episode:tt12345:1:4")
        assertThat(history?.exactVideoId).isEqualTo("tt12345:1:4")
        assertThat(history?.resumePositionMs).isEqualTo(120_000L)
        assertThat(history?.streamOriginKeyHint).isEqualTo("c".repeat(64))
    }

    @Test
    fun `completion snapshot marks watched and clamps progress to duration`() {
        val history = buildStremioPlaybackHistorySnapshot(
            reference = StremioPlaybackReference(
                mediaType = "movie",
                exactVideoId = "tt12345",
                catalogOriginKey = "a".repeat(64),
                streamOriginKey = "c".repeat(64)
            ),
            positionMs = 700_000L,
            durationMs = 600_000L,
            title = "Movie",
            posterUrl = null,
            watched = true,
            lastWatchedAt = 1L
        )

        assertThat(history?.watched).isTrue()
        assertThat(history?.resumePositionMs).isEqualTo(600_000L)
    }
}
