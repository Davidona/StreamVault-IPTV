package com.streamvault.feature.playback.player

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.model.StreamType
import org.junit.Test

class PlayerCastStreamInfoSupportTest {

    @Test
    fun `prefers active stream info when it carries richer playback context`() {
        val active = StreamInfo(
            url = "https://example.test/active.m3u8",
            headers = mapOf("Cookie" to "session=abc"),
            userAgent = "StreamVault",
            streamType = StreamType.HLS
        )
        val fallback = StreamInfo(
            url = "https://example.test/repo.m3u8",
            streamType = StreamType.HLS
        )

        val selected = selectPreferredVodCastStreamInfo(
            activeStreamInfo = active,
            activePlaybackUrl = active.url,
            fallbackStreamInfo = fallback
        )

        assertThat(selected).isEqualTo(active)
    }

    @Test
    fun `prefers active stream info on equal richness when it matches the active playback url`() {
        val active = StreamInfo(
            url = "https://example.test/active.m3u8",
            streamType = StreamType.HLS
        )
        val fallback = StreamInfo(
            url = "https://example.test/repo.m3u8",
            streamType = StreamType.HLS
        )

        val selected = selectPreferredVodCastStreamInfo(
            activeStreamInfo = active,
            activePlaybackUrl = active.url,
            fallbackStreamInfo = fallback
        )

        assertThat(selected).isEqualTo(active)
    }

    @Test
    fun `keeps fallback stream info when it is richer than the active one`() {
        val active = StreamInfo(
            url = "https://example.test/active.mp4",
            streamType = StreamType.PROGRESSIVE
        )
        val fallback = StreamInfo(
            url = "https://example.test/repo.m3u8",
            headers = mapOf("Cookie" to "session=abc"),
            proxyHost = "proxy.example.test",
            proxyPort = 8080,
            streamType = StreamType.HLS
        )

        val selected = selectPreferredVodCastStreamInfo(
            activeStreamInfo = active,
            activePlaybackUrl = active.url,
            fallbackStreamInfo = fallback
        )

        assertThat(selected).isEqualTo(fallback)
    }
}
