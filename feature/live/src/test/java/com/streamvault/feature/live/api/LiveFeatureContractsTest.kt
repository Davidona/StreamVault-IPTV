package com.streamvault.feature.live.api

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LiveFeatureContractsTest {
    @Test
    fun previewOrigins_areStableForHostAdapters() {
        assertThat(LivePreviewOrigin.values().toList())
            .containsExactly(LivePreviewOrigin.HOME, LivePreviewOrigin.GUIDE)
            .inOrder()
    }

    @Test
    fun multiViewStatus_defaultsToEmptyFourSlotSurface() {
        assertThat(LiveMultiViewStatus()).isEqualTo(LiveMultiViewStatus(channelCount = 0, slotCapacity = 4))
    }
}
