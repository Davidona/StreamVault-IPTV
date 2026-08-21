package com.streamvault.app.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ChannelProgressTickerTest {

    @Test
    fun `progress is clamped to the program interval`() {
        assertThat(channelProgressFraction(90L, 100L, 200L)).isEqualTo(0f)
        assertThat(channelProgressFraction(150L, 100L, 200L)).isEqualTo(0.5f)
        assertThat(channelProgressFraction(250L, 100L, 200L)).isEqualTo(1f)
    }

    @Test
    fun `progress is zero for a non-positive duration`() {
        assertThat(channelProgressFraction(150L, 100L, 100L)).isEqualTo(0f)
        assertThat(channelProgressFraction(150L, 200L, 100L)).isEqualTo(0f)
    }
}
