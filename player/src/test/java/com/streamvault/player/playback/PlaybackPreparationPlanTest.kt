package com.streamvault.player.playback

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.PlaybackContentKind
import com.streamvault.domain.model.StreamInfo
import org.junit.Test

class PlaybackPreparationPlanTest {

    @Test
    fun `refreshed stream info recomputes transport contract`() {
        val initial = buildPlaybackPreparationPlan(
            streamInfo = StreamInfo(url = "https://example.com/live/channel.m3u8"),
            preload = false,
            playbackStarted = { true }
        )

        val refreshed = buildPlaybackPreparationPlan(
            streamInfo = StreamInfo(url = "https://example.com/live/channel.ts", containerExtension = "ts"),
            preload = false,
            playbackStarted = { true }
        )

        assertThat(initial.resolvedStreamType).isEqualTo(ResolvedStreamType.HLS)
        assertThat(initial.timeoutProfile).isEqualTo(PlayerTimeoutProfile.LIVE)
        assertThat(refreshed.resolvedStreamType).isEqualTo(ResolvedStreamType.MPEG_TS_LIVE)
        assertThat(refreshed.timeoutProfile).isEqualTo(PlayerTimeoutProfile.LIVE)
        assertThat(refreshed.retryContext).isEqualTo(
            PlaybackRetryContext(
                resolvedStreamType = ResolvedStreamType.MPEG_TS_LIVE,
                timeoutProfile = PlayerTimeoutProfile.LIVE
            )
        )
    }

    @Test
    fun `preload plan uses preload timeout profile`() {
        val preloadPlan = buildPlaybackPreparationPlan(
            streamInfo = StreamInfo(url = "https://example.com/movie.mp4"),
            preload = true,
            playbackStarted = { false }
        )

        assertThat(preloadPlan.resolvedStreamType).isEqualTo(ResolvedStreamType.PROGRESSIVE)
        assertThat(preloadPlan.timeoutProfile).isEqualTo(PlayerTimeoutProfile.PRELOAD)
    }

    @Test
    fun `explicit hls vod intent reaches retry context`() {
        val plan = buildPlaybackPreparationPlan(
            streamInfo = StreamInfo(
                url = "https://example.com/live/movie.m3u8",
                playbackContentKind = PlaybackContentKind.ON_DEMAND
            ),
            preload = false,
            playbackStarted = { false }
        )

        assertThat(plan.retryContext.playbackContentKind).isEqualTo(PlaybackContentKind.ON_DEMAND)
        assertThat(plan.retryContext.isLive).isFalse()
        assertThat(plan.timeoutProfile).isEqualTo(PlayerTimeoutProfile.VOD)
    }
}
