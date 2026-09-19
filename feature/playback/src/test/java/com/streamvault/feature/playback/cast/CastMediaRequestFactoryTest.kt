package com.streamvault.feature.playback.cast

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.model.StreamType
import com.streamvault.domain.model.DrmInfo
import com.streamvault.domain.model.DrmScheme
import com.streamvault.feature.playback.api.CastRewriteRequiredReason
import org.junit.Test

class CastMediaRequestFactoryTest {

    private val factory = CastMediaRequestFactory()

    @Test
    fun `maps adaptive and transport stream formats to cast MIME types`() {
        assertThat(request("https://example.test/live.m3u8")?.mimeType)
            .isEqualTo(CastMediaRequestFactory.MIME_HLS)
        assertThat(request("https://example.test/movie.mpd")?.mimeType)
            .isEqualTo(CastMediaRequestFactory.MIME_DASH)
        assertThat(request("https://example.test/channel.ism/manifest")?.mimeType)
            .isEqualTo(CastMediaRequestFactory.MIME_SMOOTH_STREAMING)
        assertThat(request("https://example.test/live.ts")?.mimeType)
            .isEqualTo(CastMediaRequestFactory.MIME_MPEG_TS)
    }

    @Test
    fun `maps container extension when URL has no useful suffix`() {
        assertThat(
            request(
                "https://example.test/playback?id=1",
                containerExtension = "m3u8"
            )?.mimeType
        ).isEqualTo(CastMediaRequestFactory.MIME_HLS)
    }

    @Test
    fun `maps progressive and unknown URLs to generic video MIME type`() {
        assertThat(request("https://example.test/movie.mp4")?.mimeType)
            .isEqualTo(CastMediaRequestFactory.MIME_VIDEO)
        assertThat(request("https://example.test/playback?id=1")?.mimeType)
            .isEqualTo(CastMediaRequestFactory.MIME_VIDEO)
    }

    @Test
    fun `rejects RTSP and RTMP streams`() {
        assertThat(request("rtsp://example.test/live")).isNull()
        assertThat(request("rtmp://example.test/live")).isNull()
        assertThat(request("https://example.test/live", streamType = StreamType.RTSP)).isNull()
    }

    @Test
    fun `reports DRM protected streams as unsupported`() {
        val result = factory.buildFromStreamInfo(
            streamInfo = StreamInfo(
                url = "https://example.test/movie.mpd",
                drmInfo = DrmInfo(
                    scheme = DrmScheme.WIDEVINE,
                    licenseUrl = "https://license.example.test/widevine"
                )
            ),
            title = "Movie",
            subtitle = null,
            artworkUrl = null,
            isLive = false,
            startPositionMs = 0L
        )

        assertThat(result).isEqualTo(
            CastMediaRequestBuildResult.Unsupported(CastMediaRequestUnsupportedReason.DRM_PROTECTED)
        )
    }

    @Test
    fun `marks streams with custom headers as requiring cast rewrite`() {
        val request = factory.fromStreamInfo(
            streamInfo = StreamInfo(
                url = "https://example.test/movie.m3u8",
                headers = mapOf("Cookie" to "session=abc")
            ),
            title = "Movie",
            subtitle = null,
            artworkUrl = null,
            isLive = false,
            startPositionMs = 0L
        )

        assertThat(request?.rewriteRequiredReason)
            .isEqualTo(CastRewriteRequiredReason.CUSTOM_HEADERS)
        assertThat(request?.requiresCastRewrite).isTrue()
        assertThat(request?.headers).containsEntry("Cookie", "session=abc")
    }

    @Test
    fun `marks streams with app-only playback context as requiring cast rewrite`() {
        assertThat(
            request(
                "https://example.test/movie.m3u8",
                userAgent = "StreamVault"
            )?.rewriteRequiredReason
        ).isEqualTo(CastRewriteRequiredReason.CUSTOM_USER_AGENT)
        assertThat(
            request(
                "https://example.test/movie.m3u8",
                userAgent = "StreamVault"
            )?.userAgent
        ).isEqualTo("StreamVault")

        assertThat(
            request(
                "https://example.test/movie.m3u8",
                proxyHost = "proxy.example.test",
                proxyPort = 8080
            )?.rewriteRequiredReason
        ).isEqualTo(CastRewriteRequiredReason.PROXY)
        assertThat(
            request(
                "https://example.test/movie.m3u8",
                proxyHost = "proxy.example.test",
                proxyPort = 8080
            )?.proxyPort
        ).isEqualTo(8080)

        assertThat(
            request(
                "https://example.test/movie.m3u8",
                allowInvalidSsl = true
            )?.rewriteRequiredReason
        ).isEqualTo(CastRewriteRequiredReason.INVALID_SSL)
    }

    @Test
    fun `marks local and device-only URLs as requiring cast rewrite`() {
        assertThat(request("content://com.streamvault/movie")?.rewriteRequiredReason)
            .isEqualTo(CastRewriteRequiredReason.LOCAL_URI)
        assertThat(request("file:///storage/emulated/0/movie.mp4")?.rewriteRequiredReason)
            .isEqualTo(CastRewriteRequiredReason.LOCAL_URI)
        assertThat(request("http://127.0.0.1:8080/movie.m3u8")?.rewriteRequiredReason)
            .isEqualTo(CastRewriteRequiredReason.LOCAL_URI)
        assertThat(request("http://10.0.2.2:8080/movie.m3u8")?.rewriteRequiredReason)
            .isEqualTo(CastRewriteRequiredReason.LOCAL_URI)
    }

    @Test
    fun `live casts always start at zero while VOD preserves progress`() {
        assertThat(
            factory.fromStreamInfo(
                streamInfo = StreamInfo(url = "https://example.test/live.m3u8"),
                title = "Live",
                subtitle = null,
                artworkUrl = null,
                isLive = true,
                startPositionMs = 45_000L
            )?.startPositionMs
        ).isEqualTo(0L)

        assertThat(
            factory.fromStreamInfo(
                streamInfo = StreamInfo(url = "https://example.test/movie.mp4"),
                title = "Movie",
                subtitle = null,
                artworkUrl = null,
                isLive = false,
                startPositionMs = 45_000L
            )?.startPositionMs
        ).isEqualTo(45_000L)
    }

    private fun request(
        url: String,
        streamType: StreamType = StreamType.UNKNOWN,
        containerExtension: String? = null,
        headers: Map<String, String> = emptyMap(),
        userAgent: String? = null,
        allowInvalidSsl: Boolean = false,
        proxyHost: String = "",
        proxyPort: Int? = null
    ) = factory.fromStreamInfo(
        streamInfo = StreamInfo(
            url = url,
            streamType = streamType,
            containerExtension = containerExtension,
            headers = headers,
            userAgent = userAgent,
            allowInvalidSsl = allowInvalidSsl,
            proxyHost = proxyHost,
            proxyPort = proxyPort
        ),
        title = "Title",
        subtitle = null,
        artworkUrl = null,
        isLive = false,
        startPositionMs = 0L
    )
}
