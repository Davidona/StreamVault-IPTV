package com.streamvault.player.playback

import java.net.URI

internal object PlaybackLogSanitizer {
    fun sanitizeOrigin(url: String?): String {
        if (url.isNullOrBlank()) return "<unknown>"
        val uri = runCatching { URI(url) }.getOrNull() ?: return "<malformed>"
        val scheme = uri.scheme?.lowercase()?.takeIf { it == "http" || it == "https" }
            ?: return "<unsupported>"
        val host = uri.host?.takeIf(String::isNotBlank) ?: return "<unknown-host>"
        val port = if (uri.port > 0) ":${uri.port}" else ""
        return "$scheme://$host$port"
    }

    fun sanitizeUrl(url: String?): String {
        if (url.isNullOrBlank()) return "<unknown>"
        val uri = runCatching { URI(url) }.getOrNull() ?: return "<malformed>"
        val host = uri.host ?: "<unknown-host>"
        val sanitizedPath = sanitizePath(uri.path.orEmpty())
        return if (sanitizedPath.isBlank()) host else "$host$sanitizedPath"
    }

    fun sanitizeMessage(message: String?): String {
        if (message.isNullOrBlank()) return "unknown"
        return credentialQueryRegex.replace(message) { match ->
            "${match.groupValues[1]}<redacted>"
        }.let(::sanitizePathSegments)
    }

    private fun sanitizePath(path: String): String = sanitizePathSegments(path).take(120)

    private fun sanitizePathSegments(value: String): String {
        return opaqueSegmentPathRegex.replace(
            credentialPathRegex.replace(value) { match ->
                "${match.groupValues[1]}${match.groupValues[2]}/<redacted>/<redacted>${match.groupValues[3]}"
            }
        ) { match ->
            "${match.groupValues[1]}${match.groupValues[2]}/<redacted>${match.groupValues[3]}"
        }
    }

    private val credentialQueryRegex = Regex("""([?&](?:username|password|token|auth)=)[^&#\s]+""", RegexOption.IGNORE_CASE)
    private val credentialPathRegex =
        Regex("""(/)(live|movie|series|timeshift|timeshifts)/[^/\s]+/[^/\s]+([^?\s#]*)""", RegexOption.IGNORE_CASE)
    private val opaqueSegmentPathRegex =
        Regex("""(/)(r)/[^/\s]+([^?\s#]*)""", RegexOption.IGNORE_CASE)
}
