package com.streamvault.data.parser

import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.Movie
import com.streamvault.domain.util.ChannelNormalizer
import com.streamvault.domain.util.StreamEntryUrlPolicy
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Robust M3U parser that handles real-world malformed playlists.
 * Parses line-by-line in a streaming fashion to handle large files.
 *
 * Supports:
 * - #EXTM3U header
 * - #EXTINF tags with attributes
 * - Group-title for categories
 * - TVG attributes (tvg-id, tvg-name, tvg-logo, tvg-chno)
 * - Tokenized / expiring URLs
 * - Broken / malformed entries (skipped gracefully)
 */
class M3uParser {

    data class M3uHeader(
        val tvgUrls: List<String> = emptyList(),
        val userAgent: String? = null
    ) {
        val tvgUrl: String? get() = tvgUrls.firstOrNull()
    }

    data class M3uEntry(
        val name: String,
        val groupTitle: String,
        val tvgId: String?,
        val tvgName: String?,
        val tvgLogo: String?,
        val tvgChno: Int?,
        val tvgLanguage: String?,
        val tvgCountry: String?,
        val catchUp: String?,
        val catchUpDays: Int?,
        val catchUpSource: String?,
        val timeshift: String?,
        val url: String,
        val userAgent: String? = null,
        val rating: String? = null,
        val year: String? = null,
        val genre: String? = null,
        val durationSeconds: Int? = null,
        val playbackMetadata: M3uPlaybackMetadata? = null
    )

    data class ParseResult(
        val header: M3uHeader,
        val entries: List<M3uEntry>
    )

    fun parse(inputStream: InputStream, declaredCharset: Charset? = null): ParseResult {
        val source = playlistLines(inputStream, declaredCharset)
        val entries = mutableListOf<M3uEntry>()
        var header = M3uHeader()
        var pendingExtinf: ParsedExtinf? = null
        var pendingPlaybackMetadata: M3uPlaybackMetadataBuilder? = null

        // lineSequence() streams the InputStream one line at a time — only a single
        // String is allocated per iteration; prior lines are immediately eligible for GC.
        // This keeps memory flat regardless of playlist size (e.g. 500 MB feeds).
        source.use {
            source.lines
                .map { sanitizeLine(it) }
                .filter { it.isNotEmpty() }
                .forEach { line ->
                    when {
                        line.startsWith("#EXTM3U", ignoreCase = true) -> {
                            header = parseHeader(line)
                        }
                        line.startsWith("#EXTINF", ignoreCase = true) -> {
                            pendingExtinf = parseExtinf(line)
                            pendingPlaybackMetadata = pendingExtinf?.let { M3uPlaybackMetadataBuilder() }
                        }
                        line.startsWith("#") -> {
                            pendingPlaybackMetadata?.applyDirective(line)
                            pendingExtinf = pendingExtinf?.let { applyPendingDirective(it, line) }
                        }
                        pendingExtinf != null -> {
                            parseEntry(
                                pendingExtinf!!,
                                line,
                                header.userAgent,
                                pendingPlaybackMetadata?.build()
                            )?.let(entries::add)
                            pendingExtinf = null
                            pendingPlaybackMetadata = null
                        }
                        else -> {
                            // Non-comment, non-URL line with no pending EXTINF — skip
                        }
                    }
                }
        }

        return ParseResult(header, entries)
    }

    suspend fun parseStreaming(
        inputStream: InputStream,
        onHeader: suspend (M3uHeader) -> Unit = {},
        onEntry: suspend (M3uEntry) -> Unit,
        onInvalidEntry: suspend () -> Unit = {},
        declaredCharset: Charset? = null
    ) {
        val source = playlistLines(inputStream, declaredCharset)
        var header = M3uHeader()
        var pendingExtinf: ParsedExtinf? = null
        var pendingPlaybackMetadata: M3uPlaybackMetadataBuilder? = null

        // Use a for-loop over lineSequence() rather than .forEach{} because onHeader and
        // onEntry are suspend lambdas. Sequence.forEach is not an inline function, so the
        // compiler cannot allow coroutine suspension inside it. A for-loop over the same
        // sequence is fully coroutine-compatible and preserves the one-line-at-a-time
        // memory profile.
        source.use {
            for (rawLine in source.lines) {
                val line = sanitizeLine(rawLine)
                if (line.isEmpty()) continue

                when {
                    line.startsWith("#EXTM3U", ignoreCase = true) -> {
                        if (pendingExtinf != null) {
                            onInvalidEntry()
                            pendingExtinf = null
                            pendingPlaybackMetadata = null
                        }
                        header = parseHeader(line)
                        onHeader(header)
                    }
                    line.startsWith("#EXTINF", ignoreCase = true) -> {
                        if (pendingExtinf != null) {
                            onInvalidEntry()
                        }
                        pendingExtinf = parseExtinf(line)
                        pendingPlaybackMetadata = pendingExtinf?.let { M3uPlaybackMetadataBuilder() }
                    }
                    line.startsWith("#") -> {
                        pendingPlaybackMetadata?.applyDirective(line)
                        pendingExtinf = pendingExtinf?.let { applyPendingDirective(it, line) }
                    }
                    pendingExtinf != null -> {
                        parseEntry(
                            pendingExtinf!!,
                            line,
                            header.userAgent,
                            pendingPlaybackMetadata?.build()
                        )
                            ?.let { onEntry(it) }
                            ?: onInvalidEntry()
                        pendingExtinf = null
                        pendingPlaybackMetadata = null
                    }
                    else -> {
                        // Non-comment, non-URL line with no pending EXTINF — skip
                    }
                }
            }
            if (pendingExtinf != null) {
                onInvalidEntry()
                pendingPlaybackMetadata = null
            }
        }
    }

    fun parseToChannels(inputStream: InputStream, providerId: Long): List<Channel> {
        return parse(inputStream).entries.mapIndexed { index, entry ->
            Channel(
                id = index.toLong() + 1, // Will be replaced by stableId in SyncManager
                name = entry.name,
                logoUrl = entry.tvgLogo,
                groupTitle = entry.groupTitle,
                epgChannelId = entry.tvgId ?: entry.tvgName,
                number = entry.tvgChno ?: (index + 1),
                streamUrl = entry.url,
                catchUpSupported = entry.supportsCatchUp(),
                catchUpDays = entry.catchUpDays ?: 0,
                catchUpSource = entry.catchUpSource,
                providerId = providerId,
                logicalGroupId = ChannelNormalizer.getLogicalGroupId(entry.name, providerId)
            )
        }
    }

    private fun parseEntry(
        extinf: ParsedExtinf,
        url: String,
        globalUserAgent: String?,
        playbackMetadata: M3uPlaybackMetadata?
    ): M3uEntry? {
        if (url.isBlank() || extinf.name.isBlank()) return null
        if (!isAllowedStreamUrl(url)) return null
        return M3uEntry(
            name = extinf.name.take(500),
            groupTitle = (extinf.groupTitle ?: "Uncategorized").take(200),
            tvgId = extinf.tvgId,
            tvgName = extinf.tvgName,
            tvgLogo = extinf.tvgLogo,
            tvgChno = extinf.tvgChno?.toIntOrNull()?.takeIf { it in 1..99999 },
            tvgLanguage = extinf.tvgLanguage,
            tvgCountry = extinf.tvgCountry,
            catchUp = extinf.catchUp,
            catchUpDays = extinf.catchUpDays?.toIntOrNull()?.takeIf { it in 0..365 },
            catchUpSource = extinf.catchUpSource,
            timeshift = extinf.timeshift,
            url = url,
            userAgent = extinf.userAgent ?: playbackMetadata?.userAgent ?: globalUserAgent,
            rating = extinf.rating,
            year = extinf.year,
            genre = extinf.genre,
            durationSeconds = extinf.durationSeconds,
            playbackMetadata = playbackMetadata
        )
    }

    private fun M3uEntry.supportsCatchUp(): Boolean {
        return !catchUp.isNullOrBlank() || !catchUpSource.isNullOrBlank() || !timeshift.isNullOrBlank()
    }

    private fun applyPendingDirective(extinf: ParsedExtinf, line: String): ParsedExtinf {
        val extGrpTitle = parseStandaloneGroupTitle(line) ?: return extinf
        return extinf.copy(groupTitle = extGrpTitle)
    }

    private fun sanitizeLine(rawLine: String): String =
        rawLine.removePrefix("\uFEFF").trim()

    private data class ParsedExtinf(
        val name: String,
        val durationSeconds: Int?,
        val tvgId: String?,
        val tvgName: String?,
        val tvgLogo: String?,
        val groupTitle: String?,
        val tvgChno: String?,
        val tvgLanguage: String?,
        val tvgCountry: String?,
        val catchUp: String?,
        val catchUpDays: String?,
        val catchUpSource: String?,
        val timeshift: String?,
        val userAgent: String?,
        val rating: String?,
        val year: String?,
        val genre: String?
    )

    private fun parseHeader(line: String): M3uHeader {
        val attributes = parseAttributes(line, line.indexOf(' '))
        return M3uHeader(
            tvgUrls = extractHeaderEpgUrls(attributes),
            userAgent = attributes["user-agent"]
        )
    }

    private fun extractHeaderEpgUrls(attributes: Map<String, String>): List<String> {
        val rawValue = headerEpgAttributes
            .firstNotNullOfOrNull { key -> attributes[key]?.takeIf { it.isNotBlank() } }
            ?: return emptyList()

        return rawValue
            .split(',')
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(MAX_HEADER_EPG_URLS)
            .toList()
    }

    /**
     * Charset precedence is BOM, validated HTTP charset, then strict UTF-8 per line with a
     * Windows-1252 fallback. Windows-1252 preserves ISO-8859-1 text in the printable 0xA0-0xFF
     * range while also handling the legacy punctuation commonly found in provider playlists.
     */
    private fun playlistLines(inputStream: InputStream, declaredCharset: Charset?): PlaylistLineSource {
        val buffered = inputStream as? BufferedInputStream ?: BufferedInputStream(inputStream, 64 * 1024)
        buffered.mark(3)
        val first = buffered.read()
        val second = buffered.read()
        val third = buffered.read()
        buffered.reset()
        val (bomLength, bomCharset) = when {
            first == 0xEF && second == 0xBB && third == 0xBF -> 3 to Charsets.UTF_8
            first == 0xFF && second == 0xFE -> 2 to Charsets.UTF_16LE
            first == 0xFE && second == 0xFF -> 2 to Charsets.UTF_16BE
            else -> 0 to null
        }
        repeat(bomLength) { buffered.read() }
        val charset = bomCharset ?: validatedPlaylistCharset(declaredCharset)
        return if (charset != null) {
            val reader = BufferedReader(InputStreamReader(buffered, charset))
            PlaylistLineSource(reader, reader.lineSequence())
        } else {
            PlaylistLineSource(buffered, legacyFallbackLines(buffered))
        }
    }

    private fun validatedPlaylistCharset(charset: Charset?): Charset? {
        val normalized = charset?.name()?.uppercase() ?: return null
        return charset.takeIf {
            normalized in setOf(
                "UTF-8",
                "UTF-16LE",
                "UTF-16BE",
                "WINDOWS-1252",
                "ISO-8859-1",
                "US-ASCII"
            )
        }
    }

    private fun legacyFallbackLines(input: InputStream): Sequence<String> = sequence {
        val line = ByteArrayOutputStream(256)
        val utf8Decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)

        fun decodeLine(): String {
            val bytes = line.toByteArray().let { raw ->
                if (raw.lastOrNull() == '\r'.code.toByte()) raw.copyOf(raw.size - 1) else raw
            }
            return try {
                utf8Decoder.reset().decode(ByteBuffer.wrap(bytes)).toString()
            } catch (_: CharacterCodingException) {
                String(bytes, WINDOWS_1252)
            }
        }

        while (true) {
            when (val next = input.read()) {
                -1 -> {
                    if (line.size() > 0) yield(decodeLine())
                    break
                }
                '\n'.code -> {
                    yield(decodeLine())
                    line.reset()
                }
                else -> line.write(next)
            }
        }
    }

    private class PlaylistLineSource(
        private val closeable: Closeable,
        val lines: Sequence<String>
    ) : Closeable {
        override fun close() = closeable.close()
    }

    private fun parseStandaloneGroupTitle(line: String): String? {
        if (!line.startsWith(EXT_GRP_TAG, ignoreCase = true)) return null
        val suffix = line.substring(EXT_GRP_TAG.length).trimStart()
        val rawValue = when {
            suffix.startsWith(":") || suffix.startsWith("=") -> suffix.substring(1)
            else -> suffix
        }
        return rawValue.trim().trim('"').takeIf { it.isNotEmpty() }
    }

    private fun parseExtinf(line: String): ParsedExtinf? {
        val colonIndex = line.indexOf(':')
        if (colonIndex < 0 || colonIndex == line.lastIndex) return null
        val payload = line.substring(colonIndex + 1).trim()
        if (payload.isEmpty()) return null

        val commaIndex = findFirstUnquotedComma(payload)
        if (commaIndex < 0) return null

        val metadata = payload.substring(0, commaIndex).trim()
        val name = payload.substring(commaIndex + 1).trim()
        if (name.isEmpty()) return null

        val attributes = parseAttributes(metadata, findAttributeStart(metadata))
        return ParsedExtinf(
            name = name,
            durationSeconds = extractDuration(metadata),
            tvgId = attributes["tvg-id"],
            tvgName = attributes["tvg-name"],
            tvgLogo = attributes["tvg-logo"],
            groupTitle = attributes["group-title"],
            tvgChno = attributes["tvg-chno"],
            tvgLanguage = attributes["tvg-language"],
            tvgCountry = attributes["tvg-country"],
            catchUp = attributes["catchup"],
            catchUpDays = attributes["catchup-days"],
            catchUpSource = attributes["catchup-source"],
            timeshift = attributes["timeshift"],
            userAgent = attributes["user-agent"],
            rating = attributes["rating"],
            year = attributes["year"],
            genre = attributes["genre"]
        )
    }

    private fun extractDuration(metadata: String): Int? {
        val attributeStart = findAttributeStart(metadata)
        val durationEnd = if (attributeStart >= 0) attributeStart else metadata.length
        return metadata.substring(0, durationEnd).trim().toIntOrNull()
    }

    private fun findAttributeStart(metadata: String): Int {
        val length = metadata.length
        var index = 0
        while (index < length && !metadata[index].isWhitespace()) {
            index++
        }
        while (index < length && metadata[index].isWhitespace()) {
            index++
        }
        return if (index < length) index else -1
    }

    private fun findFirstUnquotedComma(value: String): Int {
        var inQuotes = false
        var i = 0
        while (i < value.length) {
            when {
                // Backslash escape inside a quoted string: skip the next character entirely
                value[i] == '\\' && inQuotes && i + 1 < value.length -> i++
                value[i] == '"' -> inQuotes = !inQuotes
                value[i] == ',' && !inQuotes -> return i
            }
            i++
        }
        return -1
    }

    private fun String.indexOfFirst(startIndex: Int, predicate: (Char) -> Boolean): Int {
        for (i in startIndex until length) {
            if (predicate(this[i])) return i
        }
        return -1
    }

    private fun parseAttributes(content: String, startIndex: Int): Map<String, String> {
        val attributes = mutableMapOf<String, String>()
        if (startIndex < 0 || startIndex >= content.length) {
            return attributes
        }

        val length = content.length
        var index = startIndex
        while (index < length) {
            while (index < length && content[index].isWhitespace()) {
                index++
            }
            if (index >= length) {
                break
            }

            val keyStart = index
            while (index < length && !content[index].isWhitespace() && content[index] != '=') {
                index++
            }
            if (index <= keyStart) {
                break
            }
            val key = content.substring(keyStart, index).lowercase()

            while (index < length && content[index].isWhitespace()) {
                index++
            }
            if (index >= length || content[index] != '=') {
                while (index < length && !content[index].isWhitespace()) {
                    index++
                }
                continue
            }
            index++

            while (index < length && content[index].isWhitespace()) {
                index++
            }
            if (index >= length) {
                break
            }

            val value = if (content[index] == '"') {
                index++
                val sb = StringBuilder()
                while (index < length && content[index] != '"') {
                    if (content[index] == '\\' && index + 1 < length) {
                        // Consume the backslash and include the next character literally
                        index++
                        sb.append(content[index])
                    } else {
                        sb.append(content[index])
                    }
                    index++
                }
                if (index < length && content[index] == '"') {
                    index++
                }
                sb.toString()
            } else {
                // Unquoted value — consume until the next known attribute key or end
                val valueStart = index
                var end = index
                while (end < length) {
                    if (content[end].isWhitespace()) {
                        val nextTokenStart = content.indexOfFirst(end) { !it.isWhitespace() }
                        if (nextTokenStart < 0) break
                        val eqPos = content.indexOf('=', nextTokenStart)
                        if (eqPos > nextTokenStart) {
                            val candidateKey = content.substring(nextTokenStart, eqPos).trim().lowercase()
                            if (candidateKey.isNotEmpty() && !candidateKey.any { it.isWhitespace() } && candidateKey in knownAttributes) {
                                break
                            }
                        }
                        end++
                    } else {
                        end++
                    }
                }
                index = end
                content.substring(valueStart, end).trim()
            }

            if (key.isNotBlank()) {
                attributes[key] = value
            }
        }

        return attributes
    }

    /**
     * Returns true if [url] is an acceptable stream entry URL.
     *
     * Delegates to the shared [StreamEntryUrlPolicy] (which detects control-separator
     * injection up to two encoding layers and enforces the allowed-scheme set) and
     * additionally rejects URLs that exceed the maximum safe length.
     */
    private fun isAllowedStreamUrl(url: String): Boolean {
        val trimmed = url.trim()
        if (trimmed.length > 8192) return false
        return StreamEntryUrlPolicy.isAllowed(trimmed)
    }

    companion object {
        private const val EXT_GRP_TAG = "#EXTGRP"

        private val headerEpgAttributes = listOf(
            "x-tvg-url",
            "url-tvg",
            "tvg-url",
            "url-xml"
        )
        private const val MAX_HEADER_EPG_URLS = 8
        private val WINDOWS_1252: Charset = Charset.forName("windows-1252")

        private val vodClassifier = M3uVodClassifier()

        /** Exposed for importers and tests; classification rules live in one deterministic source. */
        fun isVodEntry(entry: M3uEntry): Boolean = vodClassifier.classify(entry).isVod

        val knownAttributes = setOf(
            "tvg-id",
            "tvg-name",
            "tvg-logo",
            "group-title",
            "tvg-chno",
            "tvg-language",
            "tvg-country",
            "catchup",
            "catchup-days",
            "catchup-source",
            "timeshift",
            "user-agent",
            "rating",
            "year",
            "genre"
        )
    }
}
