package com.streamvault.player.playback

import androidx.media3.common.C
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class InitialBitratePolicyTest {

    @Test
    fun `network fallback follows the connection class`() {
        assertThat(InitialBitratePolicy.estimate(C.NETWORK_TYPE_WIFI, recentEstimateBps = 0L))
            .isEqualTo(4_300_000L)
        assertThat(InitialBitratePolicy.estimate(C.NETWORK_TYPE_4G, recentEstimateBps = 0L))
            .isEqualTo(2_500_000L)
        assertThat(InitialBitratePolicy.estimate(C.NETWORK_TYPE_2G, recentEstimateBps = 0L))
            .isEqualTo(1_500_000L)
        assertThat(InitialBitratePolicy.estimate(C.NETWORK_TYPE_UNKNOWN, recentEstimateBps = 0L))
            .isEqualTo(1_000_000L)
    }

    @Test
    fun `recent bandwidth is discounted before being used for startup`() {
        assertThat(InitialBitratePolicy.estimate(C.NETWORK_TYPE_WIFI, recentEstimateBps = 4_000_000L))
            .isEqualTo(3_000_000L)
    }

    @Test
    fun `recent bandwidth is bounded to avoid an aggressive first selection`() {
        assertThat(InitialBitratePolicy.estimate(C.NETWORK_TYPE_WIFI, recentEstimateBps = 100_000L))
            .isEqualTo(250_000L)
        assertThat(InitialBitratePolicy.estimate(C.NETWORK_TYPE_WIFI, recentEstimateBps = 20_000_000L))
            .isEqualTo(8_000_000L)
    }
}
