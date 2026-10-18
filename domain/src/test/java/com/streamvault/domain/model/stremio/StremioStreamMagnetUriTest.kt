package com.streamvault.domain.model.stremio

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StremioStreamMagnetUriTest {
    @Test
    fun `adds file index to raw magnet when it has no selection`() {
        val magnet = StremioStream(
            url = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567",
            fileIdx = 4
        ).toMagnetUri()

        assertThat(magnet).isEqualTo(
            "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&so=4"
        )
    }

    @Test
    fun `preserves file selection already present in raw magnet`() {
        val magnet = StremioStream(
            url = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&so=2",
            fileIdx = 4
        ).toMagnetUri()

        assertThat(magnet).isEqualTo(
            "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&so=2"
        )
    }
}
