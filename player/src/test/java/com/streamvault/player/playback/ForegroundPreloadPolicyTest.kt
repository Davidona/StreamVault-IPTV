package com.streamvault.player.playback

import com.google.common.truth.Truth.assertThat
import com.streamvault.player.PlaybackState
import org.junit.Test

class ForegroundPreloadPolicyTest {
    @Test fun `startup cannot borrow bandwidth before foreground has headroom`() {
        assertThat(shouldAllowNeighborPreload(false, PlaybackState.READY, true, 1_000, false)).isFalse()
        assertThat(shouldAllowNeighborPreload(false, PlaybackState.READY, true, 9_999, false)).isFalse()
        assertThat(shouldAllowNeighborPreload(false, PlaybackState.READY, true, 10_000, false)).isTrue()
    }

    @Test fun `buffer depletion suspends reads and recovery requires new headroom`() {
        assertThat(shouldAllowNeighborPreload(true, PlaybackState.READY, true, 5_001, false)).isTrue()
        assertThat(shouldAllowNeighborPreload(true, PlaybackState.READY, true, 5_000, false)).isFalse()
        assertThat(shouldAllowNeighborPreload(false, PlaybackState.READY, true, 7_000, false)).isFalse()
    }

    @Test fun `buffering recovery pause and inactive states override a full buffer`() {
        for (state in PlaybackState.entries.filter { it != PlaybackState.READY }) {
            assertThat(shouldAllowNeighborPreload(true, state, true, 60_000, false)).isFalse()
        }
        assertThat(shouldAllowNeighborPreload(true, PlaybackState.READY, false, 60_000, false)).isFalse()
        assertThat(shouldAllowNeighborPreload(true, PlaybackState.READY, true, 60_000, true)).isFalse()
    }

    @Test fun `provider connection limit disables neighbor reads for this window`() {
        assertThat(
            shouldAllowNeighborPreload(false, PlaybackState.READY, true, 60_000, false, providerLimitBlocked = true)
        ).isFalse()
    }
}
