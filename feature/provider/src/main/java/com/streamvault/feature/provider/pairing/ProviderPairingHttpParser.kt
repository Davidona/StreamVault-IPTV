package com.streamvault.feature.provider.pairing

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Locale

internal const val MAX_PAIRING_FORM_BYTES = 24 * 1024

internal data class ParsedProviderPairingRequest(
    val method: String,
    val pathAndQuery: String,
    val headers: Map<String, String>,
    val body: String,
)

internal class ProviderPairingRequestException(val statusCode: Int) : Exception()

internal object ProviderPairingHttpParser {
    private const val MAX_HEADER_BYTES = 16 * 1024
    private const val MAX_HEADER_COUNT = 64
    private const val MAX_REQUEST_LINE_BYTES = 4 * 1024
    private const val MAX_HEADER_LINE_BYTES = 4 * 1024

    fun read(input: InputStream): ParsedProviderPairingRequest {
        val budget = HeaderByteBudget()
        val requestLine = readLine(input, budget, MAX_REQUEST_LINE_BYTES)
            ?: throw ProviderPairingRequestException(400)
        val requestParts = requestLine.trim().split(WHITESPACE, limit = 3)
        if (requestParts.size != 3 || !requestParts[2].startsWith("HTTP/1.")) {
            throw ProviderPairingRequestException(400)
        }

        val headers = LinkedHashMap<String, String>()
        var headerCount = 0
        while (true) {
            val line = readLine(input, budget, MAX_HEADER_LINE_BYTES)
                ?: throw ProviderPairingRequestException(400)
            if (line.isEmpty()) break
            if (++headerCount > MAX_HEADER_COUNT) throw ProviderPairingRequestException(431)

            val separator = line.indexOf(':')
            if (separator <= 0) throw ProviderPairingRequestException(400)
            val key = line.substring(0, separator).trim().lowercase(Locale.US)
            val value = line.substring(separator + 1).trim()
            if (key.isBlank() || key in headers) throw ProviderPairingRequestException(400)
            headers[key] = value
        }

        if (!headers["transfer-encoding"].isNullOrBlank()) {
            throw ProviderPairingRequestException(400)
        }

        val method = requestParts[0].uppercase(Locale.US)
        val body = if (method == "POST") {
            val contentLengthText = headers["content-length"]
                ?: throw ProviderPairingRequestException(411)
            if (contentLengthText.isEmpty() || contentLengthText.any { it !in '0'..'9' }) {
                throw ProviderPairingRequestException(400)
            }
            val contentLength = contentLengthText.toLongOrNull()
                ?: throw ProviderPairingRequestException(400)
            if (contentLength > MAX_PAIRING_FORM_BYTES) throw ProviderPairingRequestException(413)
            readBody(input, contentLength.toInt())
        } else {
            ""
        }

        return ParsedProviderPairingRequest(
            method = method,
            pathAndQuery = requestParts[1],
            headers = headers,
            body = body,
        )
    }

    private fun readLine(input: InputStream, budget: HeaderByteBudget, maxLineBytes: Int): String? {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val next = input.read()
            if (next < 0) {
                if (bytes.size() == 0) return null
                throw ProviderPairingRequestException(400)
            }
            budget.consume()
            if (next == '\n'.code) break
            if (bytes.size() >= maxLineBytes) throw ProviderPairingRequestException(431)
            bytes.write(next)
        }

        val line = bytes.toByteArray()
        val contentLength = if (line.lastOrNull() == '\r'.code.toByte()) line.size - 1 else line.size
        return String(line, 0, contentLength, StandardCharsets.ISO_8859_1)
    }

    private fun readBody(input: InputStream, contentLength: Int): String {
        val bytes = ByteArray(contentLength)
        var offset = 0
        while (offset < bytes.size) {
            val count = input.read(bytes, offset, bytes.size - offset)
            if (count < 0) throw ProviderPairingRequestException(400)
            if (count == 0) continue
            offset += count
        }
        return try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            throw ProviderPairingRequestException(400)
        }
    }

    private class HeaderByteBudget {
        private var consumed = 0

        fun consume() {
            if (++consumed > MAX_HEADER_BYTES) throw ProviderPairingRequestException(431)
        }
    }

    private val WHITESPACE = Regex("\\s+")
}
