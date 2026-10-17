package com.streamvault.feature.provider.pairing

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertThrows
import org.junit.Test

class ProviderPairingHttpParserTest {
    @Test
    fun `reads UTF-8 body using byte content length`() {
        val body = "name=東京"
        val request = request(
            "POST /submit HTTP/1.1\r\nContent-Length: ${body.toByteArray(StandardCharsets.UTF_8).size}\r\n\r\n$body",
        )

        assertThat(request.method).isEqualTo("POST")
        assertThat(request.pathAndQuery).isEqualTo("/submit")
        assertThat(request.body).isEqualTo(body)
    }

    @Test
    fun `requires content length for POST`() {
        val error = assertThrows(ProviderPairingRequestException::class.java) {
            request("POST /submit HTTP/1.1\r\n\r\n")
        }

        assertThat(error.statusCode).isEqualTo(411)
    }

    @Test
    fun `rejects signed content length values`() {
        val error = assertThrows(ProviderPairingRequestException::class.java) {
            request("POST /submit HTTP/1.1\r\nContent-Length: +0\r\n\r\n")
        }

        assertThat(error.statusCode).isEqualTo(400)
    }

    @Test
    fun `rejects conflicting content lengths`() {
        val error = assertThrows(ProviderPairingRequestException::class.java) {
            request("POST /submit HTTP/1.1\r\nContent-Length: 0\r\nContent-Length: 0\r\n\r\n")
        }

        assertThat(error.statusCode).isEqualTo(400)
    }

    @Test
    fun `rejects oversized form body before reading it`() {
        val error = assertThrows(ProviderPairingRequestException::class.java) {
            request("POST /submit HTTP/1.1\r\nContent-Length: ${MAX_PAIRING_FORM_BYTES + 1}\r\n\r\n")
        }

        assertThat(error.statusCode).isEqualTo(413)
    }

    @Test
    fun `rejects transfer encoding to avoid ambiguous request framing`() {
        val error = assertThrows(ProviderPairingRequestException::class.java) {
            request("POST /submit HTTP/1.1\r\nTransfer-Encoding: chunked\r\nContent-Length: 0\r\n\r\n")
        }

        assertThat(error.statusCode).isEqualTo(400)
    }

    @Test
    fun `rejects truncated request body`() {
        val error = assertThrows(ProviderPairingRequestException::class.java) {
            request("POST /submit HTTP/1.1\r\nContent-Length: 5\r\n\r\nabc")
        }

        assertThat(error.statusCode).isEqualTo(400)
    }

    @Test
    fun `rejects malformed UTF-8 form body`() {
        val headers = "POST /submit HTTP/1.1\r\nContent-Length: 1\r\n\r\n"
            .toByteArray(StandardCharsets.UTF_8)
        val bytes = headers + byteArrayOf(0xC3.toByte())
        val error = assertThrows(ProviderPairingRequestException::class.java) {
            ProviderPairingHttpParser.read(ByteArrayInputStream(bytes))
        }

        assertThat(error.statusCode).isEqualTo(400)
    }

    @Test
    fun `rejects oversized header line`() {
        val oversizedValue = "x".repeat(4 * 1024 + 1)
        val error = assertThrows(ProviderPairingRequestException::class.java) {
            request("GET /pair HTTP/1.1\r\nX-Test: $oversizedValue\r\n\r\n")
        }

        assertThat(error.statusCode).isEqualTo(431)
    }

    private fun request(text: String): ParsedProviderPairingRequest =
        ProviderPairingHttpParser.read(ByteArrayInputStream(text.toByteArray(StandardCharsets.UTF_8)))
}
