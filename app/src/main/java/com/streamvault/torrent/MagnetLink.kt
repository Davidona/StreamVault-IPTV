package com.streamvault.torrent

import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class MagnetLink(
    val rawUri: String,
    val infoHash: String,
    val displayName: String?,
    val selectedFileIndex: Int?,
    val trackers: List<String>
) {
    /** Keeps only the magnet fields the engine understands; ignores arbitrary web seeds and source URLs. */
    fun toEngineUri(): String = buildString {
        append("magnet:?xt=urn%3Abtih%3A")
        append(infoHash)
        displayName?.let { append("&dn=").append(encodeQueryValue(it)) }
        selectedFileIndex?.let { append("&so=").append(it) }
        trackers.forEach { append("&tr=").append(encodeQueryValue(it)) }
    }

    private fun encodeQueryValue(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
}

object MagnetLinkParser {
    private const val MAX_URI_LENGTH = 16_384
    private const val MAX_TRACKERS = 32
    private const val MAX_TRACKER_LENGTH = 2_048
    private const val MAX_DISPLAY_NAME_LENGTH = 256
    private val hexHash = Regex("^[0-9a-fA-F]{40}$")
    private val base32Hash = Regex("^[A-Z2-7]{32}$", RegexOption.IGNORE_CASE)

    fun parse(raw: String): MagnetLink? = runCatching {
        val normalized = raw.trim()
        require(normalized.length in 1..MAX_URI_LENGTH)
        val uri = URI(normalized)
        require(uri.scheme.equals("magnet", ignoreCase = true))
        require(uri.rawAuthority == null && uri.rawPath.isNullOrEmpty() && uri.rawFragment == null)

        // Java URI treats magnet:?query as an opaque URI, so its rawQuery property is null.
        val schemeSpecificPart = uri.rawSchemeSpecificPart.orEmpty()
        require(schemeSpecificPart.startsWith("?"))
        val rawQuery = schemeSpecificPart.substring(1)
        val parameters = rawQuery.split('&').filter(String::isNotEmpty).mapNotNull { part ->
            val separator = part.indexOf('=')
            if (separator <= 0) return@mapNotNull null
            val key = decode(part.substring(0, separator))?.lowercase() ?: return@mapNotNull null
            val value = decode(part.substring(separator + 1)) ?: return@mapNotNull null
            key to value
        }
        val hash = parameters.asSequence()
            .filter { it.first == "xt" }
            .map { it.second }
            .firstNotNullOfOrNull(::parseBtih)
            ?: throw IllegalArgumentException("Magnet link does not contain a supported BitTorrent info hash")

        val selections = parameters.filter { it.first == "so" }.map { (_, value) ->
            value.toIntOrNull()?.takeIf { it >= 0 } ?: throw IllegalArgumentException("Invalid selected file index")
        }.distinct()
        require(selections.size <= 1) { "Magnet link has conflicting file selections" }

        val trackers = parameters.asSequence()
            .filter { it.first == "tr" }
            .map { it.second }
            .filter(::isSafeTracker)
            .distinct()
            .take(MAX_TRACKERS)
            .toList()
        val displayName = parameters.firstOrNull { it.first == "dn" }?.second
            ?.filterNot(Char::isISOControl)
            ?.trim()
            ?.take(MAX_DISPLAY_NAME_LENGTH)
            ?.takeIf(String::isNotEmpty)

        MagnetLink(
            rawUri = normalized,
            infoHash = hash.uppercase(),
            displayName = displayName,
            selectedFileIndex = selections.firstOrNull(),
            trackers = trackers
        )
    }.getOrNull()

    private fun isSafeTracker(value: String): Boolean {
        if (value.length !in 1..MAX_TRACKER_LENGTH) return false
        return runCatching {
            val tracker = URI(value)
            val scheme = tracker.scheme?.lowercase()
            val host = tracker.host ?: return@runCatching false
            if (scheme !in setOf("udp", "http", "https") ||
                tracker.rawUserInfo != null || tracker.rawFragment != null ||
                tracker.port > 65_535 || tracker.port == 0
            ) return@runCatching false

            val normalizedHost = host.trim('[', ']').trimEnd('.').lowercase()
            if (normalizedHost == "localhost" ||
                normalizedHost.endsWith(".localhost") ||
                normalizedHost.endsWith(".local") ||
                normalizedHost.endsWith(".internal") ||
                normalizedHost.endsWith(".lan")
            ) return@runCatching false

            val looksLikeIp = normalizedHost.contains(':') ||
                normalizedHost.all { it.isDigit() || it == '.' }
            if (!looksLikeIp) return@runCatching true

            val address = InetAddress.getByName(normalizedHost.substringBefore('%'))
            val bytes = address.address
            val uniqueLocalIpv6 = address is Inet6Address &&
                ((bytes[0].toInt() and 0xFE) == 0xFC)
            !address.isAnyLocalAddress &&
                !address.isLoopbackAddress &&
                !address.isLinkLocalAddress &&
                !address.isSiteLocalAddress &&
                !address.isMulticastAddress &&
                !uniqueLocalIpv6
        }.getOrDefault(false)
    }

    private fun parseBtih(value: String): String? {
        val prefix = "urn:btih:"
        if (!value.startsWith(prefix, ignoreCase = true)) return null
        val hash = value.substring(prefix.length)
        if (hexHash.matches(hash)) return hash

        if (!base32Hash.matches(hash)) return null
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val bytes = ByteArray(20)
        var buffer = 0
        var bitsInBuffer = 0
        var outputIndex = 0
        for (character in hash.uppercase()) {
            val value = alphabet.indexOf(character)
            if (value < 0) return null
            buffer = (buffer shl 5) or value
            bitsInBuffer += 5
            if (bitsInBuffer >= 8) {
                bitsInBuffer -= 8
                if (outputIndex < bytes.size) bytes[outputIndex++] = (buffer shr bitsInBuffer).toByte()
            }
        }
        if (outputIndex != bytes.size || bitsInBuffer != 0) return null
        return bytes.joinToString("") { "%02X".format(it) }
    }

    private fun decode(value: String): String? = runCatching {
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    }.getOrNull()
}
