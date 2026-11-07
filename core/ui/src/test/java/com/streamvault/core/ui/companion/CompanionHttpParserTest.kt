package com.streamvault.core.ui.companion

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import org.junit.Assert.assertThrows
import org.junit.Test

class CompanionHttpParserTest {
    private fun request(body: String, declaredLength: Int = body.toByteArray(Charsets.UTF_8).size): ByteArrayInputStream {
        val header = "POST /save HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: $declaredLength\r\n\r\n"
        return ByteArrayInputStream(header.toByteArray(Charsets.ISO_8859_1) + body.toByteArray(Charsets.UTF_8))
    }

    @Test fun `ordering allows a bounded large draft without changing provider setup limits`() {
        val body = "x".repeat(MAX_COMPANION_BODY_BYTES + 1)
        assertThat(assertThrows(CompanionRequestException::class.java) { CompanionHttpParser.read(request(body)) }.statusCode)
            .isEqualTo(413)
        assertThat(CompanionHttpParser.read(request(body), MAX_ORDERING_BODY_BYTES).body).isEqualTo(body)
        assertThat(assertThrows(CompanionRequestException::class.java) {
            CompanionHttpParser.read(request("", MAX_ORDERING_BODY_BYTES + 1), MAX_ORDERING_BODY_BYTES)
        }.statusCode).isEqualTo(413)
    }

    @Test fun `duplicate headers and incomplete request bodies are rejected`() {
        val duplicate = "POST /save HTTP/1.1\r\nContent-Length: 0\r\nContent-Length: 0\r\n\r\n"
        assertThat(assertThrows(CompanionRequestException::class.java) {
            CompanionHttpParser.read(ByteArrayInputStream(duplicate.toByteArray()))
        }.statusCode).isEqualTo(400)
        assertThat(assertThrows(CompanionRequestException::class.java) { CompanionHttpParser.read(request("abc", 4)) }.statusCode)
            .isEqualTo(400)
    }
}

