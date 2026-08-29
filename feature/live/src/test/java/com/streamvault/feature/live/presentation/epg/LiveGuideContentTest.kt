package com.streamvault.feature.live.presentation.epg

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LiveGuideContentTest {
    @Test
    fun `initial loading takes precedence when no channels are available`() {
        assertThat(
            liveGuideContentMode(
                isInitialLoading = true,
                hasChannels = false,
                hasMessage = false
            )
        ).isEqualTo(LiveGuideContentMode.INITIAL_LOADING)
    }

    @Test
    fun `message takes precedence over the grid`() {
        assertThat(
            liveGuideContentMode(
                isInitialLoading = false,
                hasChannels = false,
                hasMessage = true
            )
        ).isEqualTo(LiveGuideContentMode.MESSAGE)
    }

    @Test
    fun `available channels render the grid`() {
        assertThat(
            liveGuideContentMode(
                isInitialLoading = false,
                hasChannels = true,
                hasMessage = false
            )
        ).isEqualTo(LiveGuideContentMode.GRID)
    }
}
