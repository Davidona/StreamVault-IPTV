package com.streamvault.feature.playback.player

import com.streamvault.domain.playback.events.PlaybackEventPhase
import com.streamvault.player.PlaybackState
import org.junit.Assert.*
import org.junit.Test

class PlayerWebhookSupportTest {
    @Test fun `engine recovery is buffering rather than a terminal stop or pause`() {
        listOf(PlaybackState.IDLE, PlaybackState.ERROR, PlaybackState.BUFFERING).forEach {
            assertEquals(PlaybackEventPhase.BUFFERING, webhookPlaybackPhase(it, false))
        }
        assertEquals(PlaybackEventPhase.PLAYING, webhookPlaybackPhase(PlaybackState.READY, true))
        assertEquals(PlaybackEventPhase.PAUSED, webhookPlaybackPhase(PlaybackState.READY, false))
        assertEquals(PlaybackEventPhase.ENDED, webhookPlaybackPhase(PlaybackState.ENDED, false))
    }
    @Test fun `only exact IMDb identities are exported`() {
        assertEquals("tt0903747", webhookImdbId("tt0903747"))
        listOf("tt0903747:1:2", "https://example.org/tt0903747", "tt", "1396", "tt123?token=secret").forEach {
            assertNull(webhookImdbId(it))
        }
    }
}
