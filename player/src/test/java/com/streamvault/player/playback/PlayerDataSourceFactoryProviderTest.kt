package com.streamvault.player.playback

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.PlaybackTransportMode
import com.streamvault.domain.model.PlaybackTransportPolicy
import com.streamvault.domain.model.StalkerTransportOrigin
import org.junit.Test
import okhttp3.Request
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.Interceptor
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class PlayerDataSourceFactoryProviderTest {
    @Test
    fun `Stremio credentials attach only to the selected media origin`() {
        val headers = mapOf("Authorization" to "Bearer private", "Referer" to "https://media.example/entry")
        val sameOrigin = originScopedPlaybackRequest(
            Request.Builder().url("https://media.example/segment/1.ts").build(),
            "https://media.example:443",
            headers
        )
        val redirected = originScopedPlaybackRequest(
            Request.Builder().url("https://cdn.example/segment/1.ts").header("Authorization", "Bearer private").build(),
            "https://media.example:443",
            headers
        )

        assertThat(sameOrigin.header("Authorization")).isEqualTo("Bearer private")
        assertThat(sameOrigin.header("Referer")).isEqualTo("https://media.example/entry")
        assertThat(redirected.header("Authorization")).isNull()
        assertThat(redirected.header("Referer")).isNull()
    }

    @Test
    fun `Stremio origin matching handles IPv6 hosts`() {
        val request = Request.Builder().url("http://[::1]/segment.ts").build()

        assertThat(playbackRequestOrigin(request.url)).isEqualTo("http://[::1]:80")
    }

    @Test
    fun `Stremio credentials are removed on a cross-origin redirect`() {
        val mediaServer = MockWebServer()
        val cdnServer = MockWebServer()
        mediaServer.start()
        cdnServer.start()
        try {
            mediaServer.enqueue(
                MockResponse().setResponseCode(302).addHeader("Location", cdnServer.url("/segment.ts"))
            )
            cdnServer.enqueue(MockResponse().setResponseCode(200).setBody("segment"))
            val mediaOrigin = "http://${mediaServer.hostName}:${mediaServer.port}"
            val inheritedLogs = mutableListOf<String>()
            val baseClient = OkHttpClient.Builder()
                .followRedirects(false)
                .addInterceptor(Interceptor { chain ->
                    inheritedLogs += chain.request().url.toString()
                    chain.proceed(chain.request())
                })
                .build()
            val client = baseClient.newBuilder()
                .applyStremioMediaRequestPolicy(
                    origin = mediaOrigin,
                    headers = mapOf("Authorization" to "Bearer private")
                )
                .build()

            client.newCall(Request.Builder().url(mediaServer.url("/master.m3u8")).build()).execute().use { response: Response ->
                assertThat(response.code).isEqualTo(200)
            }

            assertThat(mediaServer.takeRequest().getHeader("Authorization")).isEqualTo("Bearer private")
            assertThat(cdnServer.takeRequest().getHeader("Authorization")).isNull()
            assertThat(inheritedLogs).isEmpty()
        } finally {
            mediaServer.shutdown()
            cdnServer.shutdown()
        }
    }

    @Test
    fun `effective playback request properties inject explicit user agent`() {
        val headers = effectivePlaybackRequestProperties(
            headers = mapOf("Referer" to "https://portal.example.com/c/"),
            userAgent = "CustomAgent/9.0"
        )

        assertThat(headers).containsExactly(
            "Referer", "https://portal.example.com/c/",
            "User-Agent", "CustomAgent/9.0"
        )
    }

    @Test
    fun `effective playback request properties replace case insensitive user agent header`() {
        val headers = effectivePlaybackRequestProperties(
            headers = mapOf(
                "user-agent" to "StreamVault/1.0.12-beta",
                "Origin" to "https://portal.example.com"
            ),
            userAgent = "CustomAgent/9.0"
        )

        assertThat(headers).containsExactly(
            "Origin", "https://portal.example.com",
            "User-Agent", "CustomAgent/9.0"
        )
    }

    @Test
    fun `effective playback request properties preserve headers when user agent blank`() {
        val original = linkedMapOf(
            "User-Agent" to "ExistingAgent/1.0",
            "Referer" to "https://portal.example.com/c/"
        )

        val headers = effectivePlaybackRequestProperties(
            headers = original,
            userAgent = "  "
        )

        assertThat(headers).isEqualTo(original)
    }

    @Test
    fun `effective playback request properties keep non user agent headers intact`() {
        val headers = effectivePlaybackRequestProperties(
            headers = mapOf(
                "Cookie" to "mac=00%3A11",
                "X-User-Agent" to "Model: MAG322; Link: Ethernet"
            ),
            userAgent = "StalkerAgent/1.0"
        )

        assertThat(headers).containsExactly(
            "Cookie", "mac=00%3A11",
            "X-User-Agent", "Model: MAG322; Link: Ethernet",
            "User-Agent", "StalkerAgent/1.0"
        )
    }

    @Test
    fun `read stats wrap only live stream types`() {
        assertThat(shouldWrapDataSourceReadStats(ResolvedStreamType.HLS)).isTrue()
        assertThat(shouldWrapDataSourceReadStats(ResolvedStreamType.MPEG_TS_LIVE)).isTrue()
        assertThat(shouldWrapDataSourceReadStats(ResolvedStreamType.PROGRESSIVE)).isFalse()
        assertThat(shouldWrapDataSourceReadStats(ResolvedStreamType.DASH)).isFalse()
    }

    @Test
    fun `accepted HTTP transport permits provider cross-origin HTTP redirect`() {
        val policy = PlaybackTransportPolicy(
            mode = PlaybackTransportMode.USER_ACCEPTED_HTTP,
            origin = StalkerTransportOrigin("http", "portal.example.com", 80),
            allowCrossOriginHttpRedirects = true
        )

        assertThat(
            allowsPlaybackCrossOriginHttpRedirect(
                source = policy.origin,
                target = StalkerTransportOrigin("http", "198.51.100.7", 80),
                policy = policy
            )
        ).isTrue()
    }

    @Test
    fun `cross-origin redirect permission does not weaken HTTPS transport`() {
        val policy = PlaybackTransportPolicy(
            mode = PlaybackTransportMode.USER_ACCEPTED_UNVERIFIED_HTTPS,
            origin = StalkerTransportOrigin("https", "portal.example.com", 443),
            spkiSha256 = "sha256/test"
        )

        assertThat(
            allowsPlaybackCrossOriginHttpRedirect(
                source = policy.origin,
                target = StalkerTransportOrigin("http", "198.51.100.7", 80),
                policy = policy
            )
        ).isFalse()
    }
}
