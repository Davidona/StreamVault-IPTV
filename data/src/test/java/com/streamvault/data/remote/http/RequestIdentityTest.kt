package com.streamvault.data.remote.http

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.LegacyProvider as Provider
import com.streamvault.domain.model.ProviderType
import okhttp3.Request
import org.junit.Test

class RequestIdentityTest {

    @Test
    fun `withRequestProfile does not overwrite explicit user agent`() {
        val request = Request.Builder()
            .url("https://example.test/playlist.m3u")
            .header(USER_AGENT_HEADER, "ExplicitAgent/1.0")
            .build()
            .withRequestProfile(HttpRequestProfile(userAgent = "FallbackAgent/1.0"))

        assertThat(request.header(USER_AGENT_HEADER)).isEqualTo("ExplicitAgent/1.0")
    }

    @Test
    fun `OpenSubtitles user agent follows app name and version format`() {
        assertThat(buildOpenSubtitlesUserAgent("1.2.3")).isEqualTo("StreamVault v1.2.3")
        assertThat(buildOpenSubtitlesUserAgent(null)).isEqualTo("StreamVault vdev")
    }

    @Test
    fun `safeRequestIdentitySummary redacts sensitive headers`() {
        val request = Request.Builder()
            .url("https://example.test/epg.xml")
            .header(USER_AGENT_HEADER, "StreamVault/1.0")
            .header("Authorization", "Bearer secret")
            .header("Cookie", "session=secret")
            .header("Referer", "https://portal.example.test")
            .build()

        val summary = request.safeRequestIdentitySummary(
            HttpRequestProfile(ownerTag = "provider:7/epg")
        )

        assertThat(summary).contains("owner=provider:7/epg")
        assertThat(summary).contains("userAgent=StreamVault/1.0")
        assertThat(summary).contains("Referer")
        assertThat(summary).doesNotContain("Authorization")
        assertThat(summary).doesNotContain("Cookie")
        assertThat(summary).doesNotContain("secret")
    }

    @Test
    fun `toGenericRequestProfile maps persisted header overrides`() {
        val provider = Provider(
            name = "Example",
            type = ProviderType.M3U,
            serverUrl = "https://example.test",
            httpUserAgent = "ProviderAgent/2.0",
            httpHeaders = "Referer: https://portal.example.test | Accept-Language: en-US"
        )

        val profile = provider.toGenericRequestProfile(ownerTag = "provider:9/m3u")

        assertThat(profile.ownerTag).isEqualTo("provider:9/m3u")
        assertThat(profile.userAgent).isEqualTo("ProviderAgent/2.0")
        assertThat(profile.headers).containsExactly(
            "Referer", "https://portal.example.test",
            "Accept-Language", "en-US"
        )
    }
}
