package com.streamvault.player.playback

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.PlaybackContentKind
import com.streamvault.player.PlaybackState
import org.junit.Test

class PlaybackStallRecoveryPolicyTest {
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

    @Test
    fun `live ready stalls reconnect the current stream`() {
        assertThat(
            shouldReconnectLiveStall(
                playbackState = PlaybackState.READY,
                resolvedStreamType = ResolvedStreamType.MPEG_TS_LIVE,
                recoveryAttempt = 1
            )
        ).isTrue()
    }

    @Test
    fun `live ready stalls stop reconnecting after first recovery attempt`() {
        assertThat(
            shouldReconnectLiveStall(
                playbackState = PlaybackState.READY,
                resolvedStreamType = ResolvedStreamType.MPEG_TS_LIVE,
                recoveryAttempt = 2
            )
        ).isFalse()
    }

    @Test
    fun `vod ready stalls do not reconnect as live streams`() {
        assertThat(
            shouldReconnectLiveStall(
                playbackState = PlaybackState.READY,
                resolvedStreamType = ResolvedStreamType.PROGRESSIVE,
                recoveryAttempt = 1
            )
        ).isFalse()
    }

    @Test
    fun `vod hls stalls do not reconnect through the live recovery path`() {
        assertThat(
            shouldReconnectLiveStall(
                playbackState = PlaybackState.READY,
                resolvedStreamType = ResolvedStreamType.HLS,
                recoveryAttempt = 1,
                playbackContentKind = PlaybackContentKind.ON_DEMAND
            )
        ).isFalse()
    }
}
