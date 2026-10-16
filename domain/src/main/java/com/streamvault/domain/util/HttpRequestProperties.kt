package com.streamvault.domain.util

/** Applies an explicit User-Agent with the same precedence to every HTTP playback request. */
fun effectiveHttpRequestProperties(
    headers: Map<String, String>,
    userAgent: String?
): Map<String, String> {
    val normalizedUserAgent = userAgent?.trim().orEmpty()
    if (normalizedUserAgent.isBlank()) return headers

    return buildMap(headers.size + 1) {
        headers.forEach { (name, value) ->
            if (!name.equals("User-Agent", ignoreCase = true)) put(name, value)
        }
        put("User-Agent", normalizedUserAgent)
    }
}
