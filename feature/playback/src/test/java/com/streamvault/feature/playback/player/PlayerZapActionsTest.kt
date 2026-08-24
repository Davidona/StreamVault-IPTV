package com.streamvault.feature.playback.player

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.ProviderType
import org.junit.Test

class PlayerZapActionsTest {

    @Test
    fun `appendNumericChannelDigit keeps up to six digits`() {
        var buffer = ""

        (1..6).forEach { digit ->
            buffer = appendNumericChannelDigit(buffer, digit)
        }

        assertThat(buffer).isEqualTo("123456")
    }

    @Test
    fun `appendNumericChannelDigit restarts after sixth digit`() {
        var buffer = ""

        (1..6).forEach { digit ->
            buffer = appendNumericChannelDigit(buffer, digit)
        }
        buffer = appendNumericChannelDigit(buffer, 7)

        assertThat(buffer).isEqualTo("7")
    }

    @Test
    fun `releaseOutgoingLiveZapPlayback closes active fetchers before clearing preload`() {
        val calls = mutableListOf<String>()

        releaseOutgoingLiveZapPlayback(
            stopPlayback = { calls += "stopPlayback" },
            stopLiveTimeshift = { calls += "stopLiveTimeshift" },
            clearPreload = { calls += "clearPreload" }
        )

        assertThat(calls).containsExactly("stopPlayback", "stopLiveTimeshift", "clearPreload").inOrder()
    }

    @Test
    fun `shouldPreloadAdjacentChannel skips Stalker internal streams`() {
        val shouldPreload = shouldPreloadAdjacentChannel(
            streamUrl = "stalker://1/live/99?cmd=ffmpeg%20http%3A%2F%2Fportal.example.com%2Fch%2F99",
            providerType = ProviderType.STALKER_PORTAL,
            maxConnections = 1,
            preloadCoolingDown = false
        )

        assertThat(shouldPreload).isFalse()
    }

    @Test
    fun `shouldPreloadAdjacentChannel skips Xtream live streams`() {
        val shouldPreload = shouldPreloadAdjacentChannel(
            streamUrl = "http://cdn.example.com/live/stream.ts",
            providerType = ProviderType.XTREAM_CODES,
            maxConnections = 1,
            preloadCoolingDown = false
        )

        assertThat(shouldPreload).isFalse()
    }

    @Test
    fun `shouldPreloadAdjacentChannel allows Xtream streams with spare connection budget`() {
        val shouldPreload = shouldPreloadAdjacentChannel(
            streamUrl = "http://cdn.example.com/live/stream.ts",
            providerType = ProviderType.XTREAM_CODES,
            maxConnections = 2,
            preloadCoolingDown = false
        )

        assertThat(shouldPreload).isTrue()
    }

    @Test
    fun `shouldPreloadAdjacentChannel allows M3U streams`() {
        val shouldPreload = shouldPreloadAdjacentChannel(
            streamUrl = "http://cdn.example.com/live/stream.ts",
            providerType = ProviderType.M3U,
            maxConnections = 1,
            preloadCoolingDown = false
        )

        assertThat(shouldPreload).isTrue()
    }

    @Test
    fun `shouldPreloadAdjacentChannel skips streams while provider preload is cooling down`() {
        val shouldPreload = shouldPreloadAdjacentChannel(
            streamUrl = "http://cdn.example.com/live/stream.ts",
            providerType = ProviderType.XTREAM_CODES,
            maxConnections = 2,
            preloadCoolingDown = true
        )

        assertThat(shouldPreload).isFalse()
    }
}
