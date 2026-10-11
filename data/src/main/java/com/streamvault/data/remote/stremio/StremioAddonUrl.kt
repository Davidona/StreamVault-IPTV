package com.streamvault.data.remote.stremio

import com.streamvault.domain.model.stremio.StremioMediaType
import java.net.URI
import java.net.URLEncoder

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
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val upgraded = if (trimmed.startsWith("stremio://", ignoreCase = true)) {
            "https://" + trimmed.substring("stremio://".length)
        } else {
            trimmed
        }
        val uri = runCatching { URI(upgraded) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
        val authority = if (uri.port == -1) host else "$host:${uri.port}"
        val basePath = normalizedBasePath(uri.path.orEmpty())
        return StremioAddonLocation(
            baseUrl = "$scheme://$authority$basePath",
            query = uri.rawQuery.orEmpty()
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
