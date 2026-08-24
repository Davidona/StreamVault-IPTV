package com.streamvault.feature.playback.player

import com.google.common.truth.Truth.assertThat
import com.streamvault.player.PlayerRetryStatus
import org.junit.Test

class PlayerRetryNoticeMessageTest {

    @Test
    fun `sub second retry notice omits delay text`() {
        val message = buildRetryNoticeMessage(
            formatLabel = "HLS",
            status = PlayerRetryStatus(attempt = 1, maxAttempts = 10, delayMs = 500L)
        )

        assertThat(message).isEqualTo("Retrying HLS 1/10...")
    }

    @Test
    fun `one second retry notice includes seconds text`() {
        val message = buildRetryNoticeMessage(
            formatLabel = "HLS",
            status = PlayerRetryStatus(attempt = 2, maxAttempts = 10, delayMs = 1_000L)
        )

        assertThat(message).isEqualTo("Retrying HLS 2/10 in 1s...")
    }
}
