package com.streamvault.domain.model.stremio

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StremioMediaRequestHeadersTest {
    @Test
    fun `valid request headers are scoped to scheme host and port`() {
        val scoped = stremioMediaRequestHeaders(
            "https://Media.Example.test:443/path/master.m3u8?token=private",
            mapOf("Authorization" to "Bearer secret", "Referer" to "https://Media.Example.test/")
        )

        assertThat(scoped?.origin).isEqualTo("https://media.example.test:443")
        assertThat(scoped?.headers).containsEntry("Authorization", "Bearer secret")
    }

    @Test
    fun `rejects control characters forbidden transport headers and oversized header sets`() {
        assertThat(stremioMediaRequestHeaders("https://media.example.test/v.m3u8", mapOf("X-Test" to "ok\r\nInjected: yes")))
            .isNull()
        assertThat(stremioMediaRequestHeaders("https://media.example.test/v.m3u8", mapOf("Host" to "other.example.test")))
            .isNull()
        assertThat(stremioMediaRequestHeaders("https://media.example.test/v.m3u8", (1..33).associate { "X-$it" to "v" }))
            .isNull()
    }

    @Test
    fun `rejects non-http urls and urls with embedded credentials`() {
        assertThat(stremioMediaRequestHeaders("magnet:?xt=urn:btih:abcd", emptyMap())).isNull()
        assertThat(stremioMediaRequestHeaders("https://user:pass@media.example.test/video", emptyMap())).isNull()
    }

    @Test
    fun `canonicalizes IPv6 media origin and rejects duplicate case insensitive names`() {
        assertThat(stremioMediaRequestHeaders("http://[::1]/video", emptyMap())?.origin)
            .isEqualTo("http://[::1]:80")
        assertThat(
            stremioMediaRequestHeaders(
                "https://media.example.test/video",
                mapOf("Authorization" to "Bearer first", "authorization" to "Bearer second")
            )
        ).isNull()
    }
}
