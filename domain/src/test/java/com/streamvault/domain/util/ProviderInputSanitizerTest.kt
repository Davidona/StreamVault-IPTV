package com.streamvault.domain.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ProviderInputSanitizerTest {

    @Test
    fun `normalizeProviderName trims collapses whitespace and strips controls`() {
        val value = ProviderInputSanitizer.normalizeProviderName("  My\n\tProvider\u0000   Name  ")

        assertThat(value).isEqualTo("MyProvider Name")
    }

    @Test
    fun `sanitizeUrlForEditing truncates and preserves visible characters`() {
        val longInput = "https://example.com/" + "a".repeat(ProviderInputSanitizer.MAX_URL_LENGTH + 50)

        val sanitized = ProviderInputSanitizer.sanitizeUrlForEditing(longInput)

        assertThat(sanitized.length).isEqualTo(ProviderInputSanitizer.MAX_URL_LENGTH)
        assertThat(sanitized).startsWith("https://example.com/")
    }

    @Test
    fun `validateUrl rejects whitespace`() {
        assertThat(ProviderInputSanitizer.validateUrl("https://example.com/a b.m3u"))
            .isEqualTo("URLs cannot contain spaces or line breaks.")
        assertThat(ProviderInputSanitizer.validateUrl("https://example.com/a.m3u")).isNull()
    }

}
