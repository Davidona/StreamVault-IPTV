package com.streamvault.data.parser

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.DrmScheme
import com.streamvault.domain.model.StreamType
import org.junit.Test

class M3uPlaybackMetadataTest {

    @Test
    fun `widevine URL metadata normalizes Kodi directives`() {
        val builder = M3uPlaybackMetadataBuilder()
        builder.applyDirective("#KODIPROP:inputstream.adaptive.manifest_type=mpd")
        builder.applyDirective("#KODIPROP:inputstream.adaptive.license_type=com.widevine.alpha")
        builder.applyDirective("#KODIPROP:inputstream.adaptive.license_key=https://tv.example/live/key/144?q=high")

        val metadata = builder.build()!!

        assertThat(metadata.manifestType).isEqualTo(StreamType.DASH)
        assertThat(metadata.drmScheme).isEqualTo(DrmScheme.WIDEVINE)
        assertThat(metadata.licenseUrl).isEqualTo("https://tv.example/live/key/144?q=high")
        assertThat(metadata.staticClearKeyLicense).isNull()
    }

    @Test
    fun `static ClearKey hex pair normalizes to unpadded base64url`() {
        val builder = M3uPlaybackMetadataBuilder()
        builder.applyDirective("#KODIPROP:inputstream.adaptive.manifest_type=mpd")
        builder.applyDirective("#KODIPROP:inputstream.adaptive.license_type=clearkey")
        builder.applyDirective(
            "#KODIPROP:inputstream.adaptive.license_key=" +
                "00112233445566778899aabbccddeeff:ffeeddccbbaa99887766554433221100"
        )

        val key = builder.build()!!.staticClearKeyLicense!!.keys.single()

        assertThat(key.keyIdBase64Url).isEqualTo("ABEiM0RVZneImaq7zN3u_w")
        assertThat(key.keyBase64Url).isEqualTo("_-7dzLuqmYh3ZlVEMyIRAA")
    }

    @Test
    fun `headers and vlc properties are merged with later values winning`() {
        val builder = M3uPlaybackMetadataBuilder()
        builder.applyDirective(
            "#KODIPROP:inputstream.adaptive.common_headers=User-Agent=Kodi%2F21&Referer=https%3A%2F%2Fone.example%2F"
        )
        builder.applyDirective(
            "#KODIPROP:inputstream.adaptive.stream_headers=Referer=https%3A%2F%2Ftwo.example%2F"
        )
        builder.applyDirective("#EXTVLCOPT:http-user-agent=Mozilla/5.0")
        builder.applyDirective("#EXTVLCOPT:http-referrer=https://three.example/")

        val metadata = builder.build()!!

        assertThat(metadata.commonHeaders["User-Agent"]).isEqualTo("Kodi/21")
        assertThat(metadata.streamHeaders["Referer"]).isEqualTo("https://two.example/")
        assertThat(metadata.userAgent).isEqualTo("Mozilla/5.0")
        assertThat(metadata.referer).isEqualTo("https://three.example/")
    }

    @Test
    fun `pipe formatted remote license key keeps URL and license headers`() {
        val builder = M3uPlaybackMetadataBuilder()
        builder.applyDirective("#KODIPROP:inputstream.adaptive.license_type=widevine")
        builder.applyDirective(
            "#KODIPROP:inputstream.adaptive.license_key=" +
                "https://license.example/key|Authorization=Bearer%20token&Content-Type=application%2Fjson|R{SSM}"
        )

        val metadata = builder.build()!!

        assertThat(metadata.licenseUrl).isEqualTo("https://license.example/key")
        assertThat(metadata.licenseHeaders["Authorization"]).isEqualTo("Bearer token")
        assertThat(metadata.licenseHeaders["Content-Type"]).isEqualTo("application/json")
    }

    @Test
    fun `codec round trip keeps normalized metadata`() {
        val builder = M3uPlaybackMetadataBuilder()
        builder.applyDirective("#KODIPROP:inputstream.adaptive.manifest_type=mpd")
        builder.applyDirective("#KODIPROP:inputstream.adaptive.license_type=org.w3.clearkey")
        builder.applyDirective(
            "#KODIPROP:inputstream.adaptive.license_key=" +
                "00112233445566778899aabbccddeeff:ffeeddccbbaa99887766554433221100"
        )
        builder.applyDirective("#EXTVLCOPT:http-user-agent=Mozilla/5.0")

        val original = builder.build()!!
        val decoded = M3uPlaybackMetadataCodec.decode(M3uPlaybackMetadataCodec.encode(original))

        assertThat(decoded).isEqualTo(original)
    }

    @Test
    fun `malformed static key is discarded without discarding headers`() {
        val builder = M3uPlaybackMetadataBuilder()
        builder.applyDirective("#KODIPROP:inputstream.adaptive.license_type=clearkey")
        builder.applyDirective("#KODIPROP:inputstream.adaptive.license_key=bad:key")
        builder.applyDirective("#EXTVLCOPT:http-user-agent=Mozilla/5.0")

        val metadata = builder.build()!!

        assertThat(metadata.staticClearKeyLicense).isNull()
        assertThat(metadata.userAgent).isEqualTo("Mozilla/5.0")
    }
}
