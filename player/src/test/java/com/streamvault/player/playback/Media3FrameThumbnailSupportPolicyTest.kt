package com.streamvault.player.playback

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.model.StreamType
import org.junit.Test

class Media3FrameThumbnailSupportPolicyTest {

    @Test
    fun `supports non-live video stream types`() {
        val supportedTypes = listOf(
            StreamType.PROGRESSIVE,
            StreamType.HLS,
            StreamType.DASH,
            StreamType.SMOOTH_STREAMING
        )

        supportedTypes.forEach { streamType ->
            val request = FrameThumbnailRequest(
                streamInfo = StreamInfo(
                    url = "https://example.test/video",
                    streamType = streamType
                ),
                isLive = false
            )

            assertThat(supportsMedia3FrameThumbnail(request)).isTrue()
        }
    }

    @Test
    fun `rejects live unsupported drm and malformed thumbnail requests`() {
        val requests = listOf(
            FrameThumbnailRequest(
                streamInfo = StreamInfo(
                    url = "https://example.test/live.m3u8",
                    streamType = StreamType.HLS
                ),
                isLive = true
            ),
            FrameThumbnailRequest(
                streamInfo = StreamInfo(
                    url = "rtsp://example.test/video",
                    streamType = StreamType.RTSP
                ),
                isLive = false
            ),
            FrameThumbnailRequest(
                streamInfo = StreamInfo(
                    url = "https://example.test/video.ts",
                    streamType = StreamType.MPEG_TS
                ),
                isLive = false
            ),
            FrameThumbnailRequest(
                streamInfo = StreamInfo(
                    url = "https://example.test/video",
                    streamType = StreamType.UNKNOWN,
                    drmInfo = com.streamvault.domain.model.DrmInfo(
                        scheme = com.streamvault.domain.model.DrmScheme.WIDEVINE,
                        licenseUrl = "https://example.test/license"
                    )
                ),
                isLive = false
            ),
            FrameThumbnailRequest(
                streamInfo = StreamInfo(
                    url = "https://example.test/video",
                    streamType = StreamType.UNKNOWN
                ),
                isLive = false
            )
        )

        requests.forEach { request ->
            assertThat(supportsMedia3FrameThumbnail(request)).isFalse()
        }
    }
}
