package com.streamvault.domain.model.stremio

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StremioPlaybackHistoryIdentityTest {
    private val originA = "a".repeat(64)
    private val originB = "b".repeat(64)

    @Test
    fun `validated IMDb movie identity is shared across addons`() {
        val keyA = stremioPlaybackContentKey("movie", "tt12345", null, originA)
        val keyB = stremioPlaybackContentKey("movie", "tt12345", null, originB)

        assertThat(keyA).isEqualTo("stremio:global:movie:tt12345")
        assertThat(keyB).isEqualTo(keyA)
    }

    @Test
    fun `IMDb episode identity uses the exact video id and IMDb parent`() {
        assertThat(stremioPlaybackContentKey("series", "tt12345:1:2", "tt12345", originA))
            .isEqualTo("stremio:global:series-episode:tt12345:1:2")
    }

    @Test
    fun `custom IDs are scoped to catalog origin`() {
        val keyA = stremioPlaybackContentKey("movie", "vendor:movie:7", null, originA)
        val keyB = stremioPlaybackContentKey("movie", "vendor:movie:7", null, originB)

        assertThat(keyA).isNotEqualTo(keyB)
        assertThat(keyA).isEqualTo("stremio:addon:${originA}:movie:vendor:movie:7")
    }

    @Test
    fun `invalid IMDb shaped and unsupported media IDs stay namespaced or unavailable`() {
        assertThat(stremioPlaybackContentKey("movie", "tt12abc", null, originA))
            .isEqualTo("stremio:addon:${originA}:movie:tt12abc")
        assertThat(stremioPlaybackContentKey("channel", "tt12345", null, originA)).isNull()
        assertThat(stremioPlaybackContentKey("movie", "tt12345", null, "bad-origin")).isNull()
    }

    @Test
    fun `episode under a custom parent does not become globally shared`() {
        assertThat(stremioPlaybackContentKey("series", "custom:1:2", "custom", originA))
            .isEqualTo("stremio:addon:${originA}:series-episode:custom:1:2")
    }

    @Test
    fun `nonstandard video id under an IMDb parent remains origin scoped`() {
        assertThat(stremioPlaybackContentKey("series", "tt12345:cut:director", "tt12345", originA))
            .isEqualTo("stremio:addon:${originA}:series-episode:tt12345:cut:director")
    }
}
