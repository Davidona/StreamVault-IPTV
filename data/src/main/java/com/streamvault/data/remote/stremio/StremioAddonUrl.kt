package com.streamvault.data.remote.stremio

import com.streamvault.domain.model.stremio.StremioMediaType
import java.net.URI
import java.net.URLEncoder
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class StremioAddonLocation(
    val baseUrl: String,
    val query: String = ""
) {
    val manifestUrl: String get() = withQuery("$baseUrl/manifest.json")

    fun endpointUrl(path: String): String = withQuery("$baseUrl$path")

    private fun withQuery(url: String): String = if (query.isBlank()) url else "$url?$query"
}

object StremioAddonUrl {
    private const val MANIFEST_SUFFIX = "/manifest.json"

    fun parse(raw: String): StremioAddonLocation? {
        val trimmed = raw.trimPastedUrlBoundaryCharacters()
        if (trimmed.isEmpty()) return null
        val upgraded = if (trimmed.startsWith("stremio://", ignoreCase = true)) {
            "https://" + trimmed.substring("stremio://".length)
        } else {
            trimmed
        }
        val httpUrl = upgraded.toHttpUrlOrNull() ?: return null
        val uri = runCatching { URI(upgraded) }.getOrNull()
        if (uri != null && uri.rawAuthority.isNullOrBlank()) return null
        val scheme = (uri?.scheme ?: httpUrl.scheme).lowercase()
        if (scheme != "http" && scheme != "https") return null
        val uriHost = uri?.host?.takeIf { it.isNotBlank() }
        val authority = if (uriHost != null) {
            if (uri.port == -1) uriHost else "$uriHost:${uri.port}"
        } else {
            val isDefaultPort = (httpUrl.scheme == "https" && httpUrl.port == 443) ||
                (httpUrl.scheme == "http" && httpUrl.port == 80)
            if (isDefaultPort) httpUrl.host else "${httpUrl.host}:${httpUrl.port}"
        }
        // Configured addon URLs can encode tenant/configuration bytes in the path.
        // Preserve those bytes so endpoints and install identity round-trip exactly.
        val basePath = normalizedBasePath(uri?.rawPath ?: httpUrl.encodedPath)
        return StremioAddonLocation(
            baseUrl = "$scheme://$authority$basePath",
            query = uri?.rawQuery ?: httpUrl.encodedQuery.orEmpty()
        )
    }

    fun catalogPath(
        type: StremioMediaType,
        catalogId: String,
        extras: List<Pair<String, String>>
    ): String = "/catalog/${encodeSegment(type.typeName)}/${encodeSegment(catalogId)}${extrasPath(extras)}.json"

    fun metaPath(type: StremioMediaType, id: String): String =
        "/meta/${encodeSegment(type.typeName)}/${encodeSegment(id)}.json"

    fun streamPath(type: StremioMediaType, id: String): String =
        "/stream/${encodeSegment(type.typeName)}/${encodeSegment(id)}.json"

    fun subtitlePath(
        type: StremioMediaType,
        id: String,
        extras: List<Pair<String, String>>
    ): String = "/subtitles/${encodeSegment(type.typeName)}/${encodeSegment(id)}${extrasPath(extras)}.json"

    private fun normalizedBasePath(path: String): String {
        val trimmed = path.trimEnd('/')
        return when {
            trimmed.isEmpty() || trimmed == "/" -> ""
            trimmed.endsWith(MANIFEST_SUFFIX, ignoreCase = true) -> trimmed.dropLast(MANIFEST_SUFFIX.length)
            else -> trimmed
        }
    }

    private fun extrasPath(extras: List<Pair<String, String>>): String =
        if (extras.isEmpty()) {
            ""
        } else {
            extras.joinToString(prefix = "/", separator = "&") { (key, value) ->
                "$key=${encodeSegment(value)}"
            }
        }

    private fun encodeSegment(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name())
            .replace("+", "%20")
            .replace("%3A", ":")
}

private fun String.trimPastedUrlBoundaryCharacters(): String {
    var start = 0
    var end = length
    while (start < end) {
        val codePoint = codePointAt(start)
        if (!codePoint.isPastedUrlBoundaryCharacter()) break
        start += Character.charCount(codePoint)
    }
    while (start < end) {
        val codePoint = codePointBefore(end)
        if (!codePoint.isPastedUrlBoundaryCharacter()) break
        end -= Character.charCount(codePoint)
    }
    return substring(start, end)
}

private fun Int.isPastedUrlBoundaryCharacter(): Boolean =
    Character.isWhitespace(this) ||
        Character.isSpaceChar(this) ||
        Character.getType(this) == Character.FORMAT.toInt()
