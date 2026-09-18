package com.streamvault.data.repository

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.parser.M3uPlaybackMetadataBuilder
import com.streamvault.data.parser.M3uPlaybackMetadataCodec
import com.streamvault.domain.model.DrmScheme
import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.model.StreamType
import org.junit.Test

class M3uPlaybackStreamInfoTest {

    @Test
    fun `remote license metadata enriches resolved stream`() {
        val builder = M3uPlaybackMetadataBuilder()
        builder.applyDirective("#KODIPROP:inputstream.adaptive.manifest_type=mpd")
        builder.applyDirective("#KODIPROP:inputstream.adaptive.license_type=widevine")
        builder.applyDirective("#KODIPROP:inputstream.adaptive.license_key=https://license.example/key")
        builder.applyDirective("#KODIPROP:inputstream.adaptive.license_headers=Authorization=Bearer%20token")
        builder.applyDirective("#EXTVLCOPT:http-referrer=https://origin.example/")

        val result = StreamInfo(
            url = "https://stream.example/channel.mpd",
            streamType = StreamType.UNKNOWN,
            headers = mapOf("X-Base" to "one")
        ).withM3uPlaybackMetadata(M3uPlaybackMetadataCodec.encode(builder.build()!!))

        assertThat(result.streamType).isEqualTo(StreamType.DASH)
        assertThat(result.headers["X-Base"]).isEqualTo("one")
        assertThat(result.drmInfo?.scheme).isEqualTo(DrmScheme.WIDEVINE)
        assertThat(result.drmInfo?.licenseUrl).isEqualTo("https://license.example/key")
        assertThat(result.drmInfo?.headers?.get("Authorization")).isEqualTo("Bearer token")
        assertThat(result.headers["Referer"]).isEqualTo("https://origin.example/")
    }

    @Test
    fun `static clearKey metadata produces local drm configuration`() {
        val builder = M3uPlaybackMetadataBuilder()
        builder.applyDirective("#KODIPROP:inputstream.adaptive.license_type=clearkey")
        builder.applyDirective(
            "#KODIPROP:inputstream.adaptive.license_key=" +
                "00112233445566778899aabbccddeeff:ffeeddccbbaa99887766554433221100"
        )

        val result = StreamInfo(url = "https://stream.example/channel.mpd")
            .withM3uPlaybackMetadata(M3uPlaybackMetadataCodec.encode(builder.build()!!))

        assertThat(result.drmInfo?.scheme).isEqualTo(DrmScheme.CLEARKEY)
        assertThat(result.drmInfo?.licenseUrl).isEmpty()
        assertThat(result.drmInfo?.staticClearKeyLicense?.keys).hasSize(1)
    }
}
