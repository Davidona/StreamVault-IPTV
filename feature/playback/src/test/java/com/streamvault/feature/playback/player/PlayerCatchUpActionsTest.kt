package com.streamvault.feature.playback.player

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.PlaybackContentKind
import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.model.StreamType
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PlayerCatchUpActionsTest {

    @Test
    fun `resolveCatchUpStreamInfo uses shared playback resolver metadata and marks catch up intent`() = runTest {
        var requestedUrl: String? = null
        var requestedContentType: ContentType? = null
        val resolved = StreamInfo(
            url = "https://provider.example/archive.ts",
            title = null,
            headers = mapOf("Authorization" to "Bearer token"),
            userAgent = "StreamVaultTest",
            streamType = StreamType.HLS,
            containerExtension = "m3u8",
            expirationTime = 123_456L,
            playbackContentKind = PlaybackContentKind.LIVE
        )

        val result = resolveCatchUpStreamInfo(
            candidateUrl = "https://provider.example/archive.ts",
            title = "Replay title",
            currentContentId = 42L,
            currentProviderId = 7L
        ) { url, _, _, contentType ->
            requestedUrl = url
            requestedContentType = contentType
            resolved
        }

        assertThat(requestedUrl).isEqualTo("https://provider.example/archive.ts")
        assertThat(requestedContentType).isEqualTo(ContentType.LIVE)
        assertThat(result).isEqualTo(
            resolved.copy(title = "Replay title", playbackContentKind = PlaybackContentKind.CATCH_UP)
        )
        assertThat(result?.playbackContentKind).isEqualTo(PlaybackContentKind.CATCH_UP)
        assertThat(result?.headers).containsEntry("Authorization", "Bearer token")
        assertThat(result?.userAgent).isEqualTo("StreamVaultTest")
        assertThat(result?.expirationTime).isEqualTo(123_456L)
    }
}
