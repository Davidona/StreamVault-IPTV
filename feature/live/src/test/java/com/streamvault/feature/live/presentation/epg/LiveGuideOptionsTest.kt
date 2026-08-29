package com.streamvault.feature.live.presentation.epg

import com.google.common.truth.Truth.assertThat
import java.time.ZoneId
import org.junit.Test

class LiveGuideOptionsTest {
    @Test
    fun `selected guide day is derived from the lookback-adjusted window`() {
        val windowStart = 2 * 24 * 60 * 60 * 1000L + 5 * 60 * 60 * 1000L

        assertThat(liveGuideOptionsDayStart(windowStart, 60 * 60 * 1000L, ZoneId.of("UTC")))
            .isEqualTo(2 * 24 * 60 * 60 * 1000L)
    }
}
