package com.streamvault.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StreamInfoTest {

    @Test
    fun expirationTime_preserves_non_negative_value() {
        val streamInfo = StreamInfo(
            url = "https://cdn.example.com/video.m3u8?expires=1774017000",
            expirationTime = 1_774_017_000_000L
        )

        assertThat(streamInfo.expirationTime).isEqualTo(1_774_017_000_000L)
    }

    @Test
    fun drmInfo_preserves_license_configuration() {
        val drmInfo = DrmInfo(
            scheme = DrmScheme.WIDEVINE,
            licenseUrl = "https://license.example.com/wv",
            headers = mapOf("Authorization" to "Bearer token"),
            multiSession = true,
            forceDefaultLicenseUrl = true,
            playClearContentWithoutKey = true
        )

        val streamInfo = StreamInfo(
            url = "https://cdn.example.com/movie.mpd",
            streamType = StreamType.DASH,
            drmInfo = drmInfo
        )

        assertThat(streamInfo.drmInfo).isEqualTo(drmInfo)
        assertThat(streamInfo.drmInfo?.scheme).isEqualTo(DrmScheme.WIDEVINE)
        assertThat(streamInfo.drmInfo?.multiSession).isTrue()
        assertThat(streamInfo.drmInfo?.forceDefaultLicenseUrl).isTrue()
        assertThat(streamInfo.drmInfo?.playClearContentWithoutKey).isTrue()
    }

    @Test
    fun drmInfo_rejects_disallowed_license_url() {
        val error = try {
            DrmInfo(
                scheme = DrmScheme.WIDEVINE,
                licenseUrl = "javascript:alert(1)"
            )
            null
        } catch (e: IllegalArgumentException) {
            e
        }

        assertThat(error).isNotNull()
        assertThat(error!!.message).contains("allowed stream-entry URL scheme")
    }

    @Test
    fun drmInfo_accepts_redacted_static_clearkey_source() {
        val key = StaticClearKey(
            keyIdBase64Url = "ESIzRFVmd4iZqrvM3e7_8A",
            keyBase64Url = "_-7dzLuqmYh3ZlVEMyIRAA"
        )
        val license = StaticClearKeyLicense(
            keys = listOf(key),
            fingerprint = "sha256:test"
        )

        val drmInfo = DrmInfo(
            scheme = DrmScheme.CLEARKEY,
            staticClearKeyLicense = license
        )

        assertThat(drmInfo.licenseUrl).isEmpty()
        assertThat(drmInfo.staticClearKeyLicense).isEqualTo(license)
        assertThat(drmInfo.toString()).doesNotContain(key.keyBase64Url)
        assertThat(license.toString()).doesNotContain(key.keyBase64Url)
    }

    @Test
    fun drmInfo_rejects_multiple_license_sources() {
        val error = try {
            DrmInfo(
                scheme = DrmScheme.CLEARKEY,
                licenseUrl = "https://license.example.com/clearkey",
                staticClearKeyLicense = StaticClearKeyLicense(
                    keys = listOf(
                        StaticClearKey("ESIzRFVmd4iZqrvM3e7_8A", "_-7dzLuqmYh3ZlVEMyIRAA")
                    ),
                    fingerprint = "sha256:test"
                )
            )
            null
        } catch (e: IllegalArgumentException) {
            e
        }

        assertThat(error).isNotNull()
        assertThat(error!!.message).contains("exactly one")
    }

    @Test
    fun drmInfo_rejects_static_license_for_non_clearkey_scheme() {
        val error = try {
            DrmInfo(
                scheme = DrmScheme.WIDEVINE,
                staticClearKeyLicense = StaticClearKeyLicense(
                    keys = listOf(
                        StaticClearKey("ESIzRFVmd4iZqrvM3e7_8A", "_-7dzLuqmYh3ZlVEMyIRAA")
                    ),
                    fingerprint = "sha256:test"
                )
            )
            null
        } catch (e: IllegalArgumentException) {
            e
        }

        assertThat(error).isNotNull()
        assertThat(error!!.message).contains("ClearKey")
    }
}
