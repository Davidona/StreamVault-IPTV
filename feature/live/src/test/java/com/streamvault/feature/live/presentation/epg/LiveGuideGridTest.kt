package com.streamvault.feature.live.presentation.epg

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.Channel
import org.junit.Test

class LiveGuideGridTest {
    @Test
    fun `initial focus index resolves a requested channel`() {
        val channels = listOf(
            Channel(id = 10L, name = "News"),
            Channel(id = 20L, name = "Sports")
        )

        assertThat(liveGuideInitialFocusIndex(channels, 20L)).isEqualTo(1)
    }

    @Test
    fun `initial focus index falls back to the first channel`() {
        val channels = listOf(Channel(id = 10L, name = "News"))

        assertThat(liveGuideInitialFocusIndex(channels, 99L)).isEqualTo(0)
        assertThat(liveGuideInitialFocusIndex(channels, null)).isEqualTo(0)
    }
}
