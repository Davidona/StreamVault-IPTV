package com.streamvault.feature.playback.player

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.StreamInfo
import org.junit.Test

class PlayerPlaybackUrlSupportTest {

    @Test
    fun `resolveTimeshiftStreamInfo prefers resolved metadata for restart`() {
        val resolved = StreamInfo(
            url = "https://resolved.example/live.m3u8",
            title = "Resolved title",
            headers = mapOf("Authorization" to "Bearer token"),
            userAgent = "StreamVaultTest",
            expirationTime = 123_456L
        )

        val result = resolveTimeshiftStreamInfo(
            streamInfoOverride = null,
            currentResolvedStreamInfo = resolved,
            currentResolvedPlaybackUrl = resolved.url,
            currentStreamUrl = "stalker://logical",
            playbackTitle = "Playback title",
            currentTitle = "Fallback title"
        )

        assertThat(result).isEqualTo(resolved)
        assertThat(result?.headers).containsEntry("Authorization", "Bearer token")
        assertThat(result?.userAgent).isEqualTo("StreamVaultTest")
        assertThat(result?.expirationTime).isEqualTo(123_456L)
    }

    @Test
    fun `resolveTimeshiftStreamInfo falls back to url when metadata is unavailable`() {
        val result = resolveTimeshiftStreamInfo(
            streamInfoOverride = null,
            currentResolvedStreamInfo = null,
            currentResolvedPlaybackUrl = " https://fallback.example/live.ts ",
            currentStreamUrl = "logical://stream",
            playbackTitle = "",
            currentTitle = "Fallback title"
        )

        assertThat(result).isEqualTo(
            StreamInfo(
                url = "https://fallback.example/live.ts",
                title = "Fallback title"
            )
        )
    }
}