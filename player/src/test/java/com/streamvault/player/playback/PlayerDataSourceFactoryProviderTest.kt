package com.streamvault.player.playback

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.PlaybackTransportMode
import com.streamvault.domain.model.PlaybackTransportPolicy
import com.streamvault.domain.model.StalkerTransportOrigin
import org.junit.Test

class PlayerDataSourceFactoryProviderTest {
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
