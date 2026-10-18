package com.streamvault.feature.playback.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StremioMediaHeaderPolicyTest {
    @Test
    fun magnetUrlsDoNotRunDirectHttpHeaderValidation() {
        val result = stremioMediaHeaderPolicy(
            mediaUrl = "magnet:?xt=urn:btih:0123456789abcdef",
            suppliedHeaders = mapOf("Host" to "untrusted.example")
        )

        assertThat(result).isEqualTo(StremioMediaHeaderPolicy.NotApplicable)
    }

    @Test
    fun validDirectHttpHeadersRemainScopedToTheirOrigin() {
        val result = stremioMediaHeaderPolicy(
            mediaUrl = "https://media.example.com/video.m3u8",
            suppliedHeaders = mapOf("Authorization" to "Bearer token")
        )

        assertThat(result).isInstanceOf(StremioMediaHeaderPolicy.Scoped::class.java)
        assertThat((result as StremioMediaHeaderPolicy.Scoped).value.origin)
            .isEqualTo("https://media.example.com:443")
    }

    @Test
    fun invalidDirectHttpHeadersAreRejected() {
        val result = stremioMediaHeaderPolicy(
            mediaUrl = "https://media.example.com/video.m3u8",
            suppliedHeaders = mapOf("Host" to "other.example.com")
        )

        assertThat(result).isEqualTo(StremioMediaHeaderPolicy.Invalid)
    }
}
