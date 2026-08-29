package com.streamvault.feature.live.presentation.epg

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LiveGuideTimelineTest {
    @Test
    fun `timeline markers include aligned markers and window end`() {
        assertThat(liveGuideTimelineMarkers(10 * MINUTE_MS, 80 * MINUTE_MS, HALF_HOUR_MS))
            .containsExactly(0L, 30 * MINUTE_MS, 60 * MINUTE_MS, 80 * MINUTE_MS)
            .inOrder()
    }

    @Test
    fun `timeline markers do not duplicate an aligned window end`() {
        assertThat(liveGuideTimelineMarkers(0L, 60 * MINUTE_MS, HALF_HOUR_MS))
            .containsExactly(0L, 30 * MINUTE_MS, 60 * MINUTE_MS)
            .inOrder()
    }

    private companion object {
        const val MINUTE_MS = 60_000L
        const val HALF_HOUR_MS = 30 * MINUTE_MS
    }
}
