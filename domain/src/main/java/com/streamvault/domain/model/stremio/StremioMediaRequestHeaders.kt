package com.streamvault.domain.model.stremio

import java.net.URI

data class StremioMediaRequestHeaders(
    val origin: String,
    val headers: Map<String, String>
)

/** Validates Stremio proxyHeaders.request and binds them to the direct stream's HTTP origin. */
fun stremioMediaRequestHeaders(
    mediaUrl: String,
    suppliedHeaders: Map<String, String>?
): StremioMediaRequestHeaders? {
    val origin = stremioHttpOrigin(mediaUrl) ?: return null
    val headers = suppliedHeaders.orEmpty()
    if (headers.size > MAX_HEADER_COUNT) return null
    var totalBytes = 0
    val safeHeaders = linkedMapOf<String, String>()
    val normalizedNames = mutableSetOf<String>()
    headers.forEach { (rawName, value) ->
        val name = rawName.trim()
        val normalizedName = name.lowercase()
        if (!HEADER_NAME.matches(name) || normalizedName in FORBIDDEN_HEADERS ||
            !normalizedNames.add(normalizedName)
        ) return null
        if (value.length > MAX_HEADER_VALUE_LENGTH || value.any { it == '\r' || it == '\n' || it.code < 0x20 && it != '\t' }) {
            return null
        }
        totalBytes += name.toByteArray(Charsets.UTF_8).size + value.toByteArray(Charsets.UTF_8).size
        if (totalBytes > MAX_HEADER_BYTES) return null
        safeHeaders[name] = value
    }
    return StremioMediaRequestHeaders(origin = origin, headers = safeHeaders.toMap())
}

/** Canonical scheme/host/port tuple. Paths, queries, and credentials never enter the scope key. */
fun stremioHttpOrigin(url: String): String? = runCatching {
    val uri = URI(url)
    val scheme = uri.scheme?.lowercase() ?: return null
    if (scheme != "http" && scheme != "https") return null
    val host = uri.host?.trimStart('[')?.trimEnd(']')?.lowercase() ?: return null
    if (uri.rawUserInfo != null) return null
    val port = uri.port.takeIf { it >= 0 } ?: if (scheme == "https") 443 else 80
    val formattedHost = if (host.contains(':')) "[$host]" else host
    "$scheme://$formattedHost:$port"
}.getOrNull()

private const val MAX_HEADER_COUNT = 32
private const val MAX_HEADER_VALUE_LENGTH = 8_192
private const val MAX_HEADER_BYTES = 16_384
private val HEADER_NAME = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,128}")
private val FORBIDDEN_HEADERS = setOf(
    "connection", "content-length", "host", "keep-alive", "proxy-authenticate",
    "proxy-authorization", "te", "trailer", "transfer-encoding", "upgrade"
)
