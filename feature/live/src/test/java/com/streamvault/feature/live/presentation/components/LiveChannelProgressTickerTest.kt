package com.streamvault.feature.live.presentation.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LiveChannelProgressTickerTest {
    @Test
    fun progressFraction_clampsToLiveProgramBounds() {
        assertThat(liveChannelProgressFraction(90L, 100L, 200L)).isEqualTo(0f)
        assertThat(liveChannelProgressFraction(150L, 100L, 200L)).isEqualTo(0.5f)
        assertThat(liveChannelProgressFraction(250L, 100L, 200L)).isEqualTo(1f)
    }

    @Test
    fun progressFraction_rejectsInvalidDurations() {
        assertThat(liveChannelProgressFraction(150L, 100L, 100L)).isEqualTo(0f)
        assertThat(liveChannelProgressFraction(150L, 200L, 100L)).isEqualTo(0f)
    }
}
