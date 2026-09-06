package com.streamvault.domain.provider

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/** Stable internal playback identity shared by provider adapters and playback policy. */
enum class ProviderInternalStreamKind(val pathSegment: String) {
    LIVE("live"),
    MOVIE("movie"),
    SERIES("series")
}

data class ProviderInternalStreamToken(
    val providerId: Long,
    val kind: ProviderInternalStreamKind,
    val streamId: Long,
    val containerExtension: String? = null
)

object ProviderInternalStreamUrl {
    private const val SCHEME = "xtream"

    fun build(
        providerId: Long,
        kind: ProviderInternalStreamKind,
        streamId: Long,
        containerExtension: String? = null
    ): String {
        val extension = normalizeExtension(containerExtension)
        val query = extension?.let { "?ext=${encode(it)}" }.orEmpty()
        return "$SCHEME://$providerId/${kind.pathSegment}/$streamId$query"
    }

    fun parse(url: String?): ProviderInternalStreamToken? {
        if (url.isNullOrBlank()) return null
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (!uri.scheme.equals(SCHEME, ignoreCase = true)) return null
        val providerId = uri.authority?.toLongOrNull() ?: return null
        val pathSegments = uri.path
            ?.trim('/')
            ?.split('/')
            ?.filter(String::isNotBlank)
            .orEmpty()
        val kind = pathSegments.getOrNull(0)
            ?.let { segment -> ProviderInternalStreamKind.entries.firstOrNull { it.pathSegment == segment } }
            ?: return null
        val streamId = pathSegments.getOrNull(1)?.toLongOrNull() ?: return null
        val extension = parseQuery(uri.rawQuery)["ext"]?.let(::normalizeExtension)
        return ProviderInternalStreamToken(providerId, kind, streamId, extension)
    }

    fun isInternal(url: String?): Boolean = parse(url) != null

    private fun parseQuery(rawQuery: String?): Map<String, String> = rawQuery
        ?.takeIf(String::isNotBlank)
        ?.split('&')
        ?.mapNotNull { pair ->
            val key = pair.substringBefore('=', missingDelimiterValue = "")
                .takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            key to decode(pair.substringAfter('=', missingDelimiterValue = ""))
        }
        ?.toMap()
        .orEmpty()

    private fun normalizeExtension(value: String?): String? = value
        ?.trim()
        ?.removePrefix(".")
        ?.lowercase(Locale.ROOT)
        ?.takeIf { it.matches(Regex("[a-z0-9]{1,12}")) }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private fun decode(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8.name())
}
