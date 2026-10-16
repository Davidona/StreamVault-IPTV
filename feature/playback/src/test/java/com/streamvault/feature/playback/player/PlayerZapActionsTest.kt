package com.streamvault.feature.playback.player

import com.google.common.truth.Truth.assertThat
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

}
