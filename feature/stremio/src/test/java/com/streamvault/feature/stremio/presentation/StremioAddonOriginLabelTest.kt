package com.streamvault.feature.stremio.presentation

import org.junit.Assert.assertEquals
import org.junit.Test

class StremioAddonOriginLabelTest {
    @Test
    fun `omits configured path query fragment and user info`() {
        val label = stremioAddonOriginLabel(
            "https://user:secret@example.com:8443/config/private-token/manifest.json?key=query-secret#fragment-secret"
        )

        assertEquals("https://example.com:8443", label)
    }

    @Test
    fun `omits default port`() {
        assertEquals("https://example.com", stremioAddonOriginLabel("https://example.com:443/path/manifest.json"))
    }

    @Test
    fun `brackets ipv6 hosts`() {
        assertEquals("http://[2001:db8::1]:8080", stremioAddonOriginLabel("http://[2001:db8::1]:8080/a/manifest.json"))
    }

    @Test
    fun `returns null for malformed endpoint`() {
        assertEquals(null, stremioAddonOriginLabel("not a URL with secrets"))
    }
}
