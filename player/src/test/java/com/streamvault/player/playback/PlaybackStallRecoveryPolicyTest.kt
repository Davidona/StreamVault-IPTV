package com.streamvault.player.playback

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.PlaybackContentKind
import org.junit.Test

class PlaybackStallRecoveryPolicyTest {
    @Test
    fun `pending Media3 load retry defers app level stall recovery`() {
        assertThat(
            shouldDeferAppStallRecovery(
                activeMediaLoadRetry = true,
                sourceRetryPending = false
            )
        ).isTrue()
    }

    @Test
    fun `scheduled source retry defers app level stall recovery`() {
        assertThat(
            shouldDeferAppStallRecovery(
                activeMediaLoadRetry = false,
                sourceRetryPending = true
            )
        ).isTrue()
    }

    @Test
    fun `stall recovery is eligible when no retry owns the source`() {
        assertThat(
            shouldDeferAppStallRecovery(
                activeMediaLoadRetry = false,
                sourceRetryPending = false
            )
        ).isFalse()
    }

    @Test
    fun `ready stalls are recovered for live transport streams`() {
        assertThat(shouldRecoverReadyStalls(ResolvedStreamType.MPEG_TS_LIVE)).isTrue()
    }

    @Test
    fun `position advancing ready stalls are not recovered for live streams`() {
        assertThat(shouldRecoverPositionAdvancingReadyStalls(ResolvedStreamType.MPEG_TS_LIVE)).isFalse()
        assertThat(shouldRecoverPositionAdvancingReadyStalls(ResolvedStreamType.HLS)).isFalse()
        assertThat(shouldRecoverPositionAdvancingReadyStalls(ResolvedStreamType.PROGRESSIVE)).isTrue()
    }

    @Test
    fun `frame silent ready stalls are recovered for live streams`() {
        assertThat(shouldRecoverFrameSilentReadyStalls(ResolvedStreamType.MPEG_TS_LIVE)).isTrue()
        assertThat(shouldRecoverFrameSilentReadyStalls(ResolvedStreamType.HLS)).isTrue()
        assertThat(shouldRecoverFrameSilentReadyStalls(ResolvedStreamType.PROGRESSIVE)).isFalse()
    }

    @Test
    fun `frame silent hls recovery uses content intent and keeps unknown live behavior`() {
        assertThat(
            shouldRecoverFrameSilentReadyStalls(ResolvedStreamType.HLS, PlaybackContentKind.ON_DEMAND)
        ).isFalse()
        assertThat(
            shouldRecoverFrameSilentReadyStalls(ResolvedStreamType.HLS, PlaybackContentKind.LIVE)
        ).isTrue()
        assertThat(
            shouldRecoverFrameSilentReadyStalls(ResolvedStreamType.HLS, PlaybackContentKind.UNKNOWN)
        ).isTrue()
    }

}
