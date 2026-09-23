package com.streamvault.player.playback

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.PlaybackContentKind
import com.streamvault.domain.model.StreamInfo
import org.junit.Test

class PlayerTimeoutProfileTest {

    @Test
    fun `hls live selects live timeout profile`() {
        assertThat(
            PlayerTimeoutProfile.resolve(
                streamInfo = StreamInfo(url = "http://example.com/live/1.m3u8"),
                resolvedStreamType = ResolvedStreamType.HLS,
                preload = false
            )
        ).isEqualTo(PlayerTimeoutProfile.LIVE)
    }

    @Test
    fun `preload always selects preload profile`() {
        assertThat(
            PlayerTimeoutProfile.resolve(
                streamInfo = StreamInfo(url = "http://example.com/movie.mp4"),
                resolvedStreamType = ResolvedStreamType.PROGRESSIVE,
                preload = true
            )
        ).isEqualTo(PlayerTimeoutProfile.PRELOAD)
    }

    @Test
    fun `progressive file selects progressive timeout profile`() {
        val profile =
            PlayerTimeoutProfile.resolve(
                streamInfo = StreamInfo(url = "http://example.com/movie.mp4"),
                resolvedStreamType = ResolvedStreamType.PROGRESSIVE,
                preload = false
            )

        assertThat(profile).isEqualTo(PlayerTimeoutProfile.PROGRESSIVE)
        assertThat(profile.connectTimeoutMs).isAtMost(5_000L)
        assertThat(profile.readTimeoutMs).isEqualTo(10_000L)
    }

    @Test
    fun `explicit hls vod selects vod timeout profile`() {
        val profile = PlayerTimeoutProfile.resolve(
            streamInfo = StreamInfo(
                url = "https://example.com/live/movie.m3u8",
                playbackContentKind = PlaybackContentKind.ON_DEMAND
            ),
            resolvedStreamType = ResolvedStreamType.HLS,
            preload = false
        )

        assertThat(profile).isEqualTo(PlayerTimeoutProfile.VOD)
        assertThat(profile.readTimeoutMs).isEqualTo(45_000L)
    }

    @Test
    fun `explicit live dash selects live timeout profile`() {
        assertThat(
            PlayerTimeoutProfile.resolve(
                streamInfo = StreamInfo(
                    url = "https://example.com/channel.mpd",
                    playbackContentKind = PlaybackContentKind.LIVE
                ),
                resolvedStreamType = ResolvedStreamType.DASH,
                preload = false
            )
        ).isEqualTo(PlayerTimeoutProfile.LIVE)
    }

    @Test
    fun `unknown intent keeps hls live timeout behavior`() {
        assertThat(
            PlayerTimeoutProfile.resolve(
                streamInfo = StreamInfo(url = "https://example.com/live/channel.m3u8"),
                resolvedStreamType = ResolvedStreamType.HLS,
                preload = false
            )
        ).isEqualTo(PlayerTimeoutProfile.LIVE)
    }
}
