package com.streamvault.player.playback

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.PlaybackContentKind
import org.junit.Test

class PlaybackContentKindResolverTest {

    @Test
    fun `explicit playback intent overrides the stream format`() {
        val cases = listOf(
            Triple(PlaybackContentKind.ON_DEMAND, ResolvedStreamType.HLS, false),
            Triple(PlaybackContentKind.CATCH_UP, ResolvedStreamType.HLS, false),
            Triple(PlaybackContentKind.LIVE, ResolvedStreamType.DASH, true),
            Triple(PlaybackContentKind.LIVE, ResolvedStreamType.MPEG_TS_LIVE, true)
        )

        cases.forEach { (kind, streamType, expected) ->
            assertThat(isLivePlayback(kind, streamType)).isEqualTo(expected)
        }
    }

    @Test
    fun `unknown intent retains legacy format classification`() {
        assertThat(isLivePlayback(PlaybackContentKind.UNKNOWN, ResolvedStreamType.HLS)).isTrue()
        assertThat(isLivePlayback(PlaybackContentKind.UNKNOWN, ResolvedStreamType.DASH)).isFalse()
    }

    @Test
    fun `hls live stall eligibility follows content intent`() {
        assertThat(isLivePlayback(PlaybackContentKind.ON_DEMAND, ResolvedStreamType.HLS)).isFalse()
        assertThat(isLivePlayback(PlaybackContentKind.LIVE, ResolvedStreamType.HLS)).isTrue()
        assertThat(isLivePlayback(PlaybackContentKind.UNKNOWN, ResolvedStreamType.HLS)).isTrue()
    }
}
