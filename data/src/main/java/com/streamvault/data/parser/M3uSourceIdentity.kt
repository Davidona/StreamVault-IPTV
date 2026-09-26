package com.streamvault.data.parser

import com.streamvault.data.local.entity.ChannelEntity
import com.streamvault.data.local.entity.MovieEntity
import java.net.URI
import java.security.MessageDigest
import java.util.Locale

/** Stable, destination-independent identity for an M3U entry. */
internal object M3uSourceIdentity {
    private val volatileQueryKeys = setOf("token", "auth", "password", "username")

    fun fromEntry(providerId: Long, entry: M3uParser.M3uEntry): String =
        hash(providerId, entry.tvgId ?: entry.tvgName, entry.url, entry.name)

    fun stableLongId(providerId: Long, entry: M3uParser.M3uEntry): Long =
        stableLong(providerId, entry.tvgId ?: entry.tvgName, entry.url, entry.name)

    fun fromChannel(channel: ChannelEntity): String =
        hash(channel.providerId, channel.epgChannelId, channel.streamUrl, channel.name)

    fun fromMovie(movie: MovieEntity): String =
        hash(movie.providerId, null, movie.streamUrl, movie.name)

    fun groupKey(groupTitle: String?): String = normalize(groupTitle.orEmpty())

    private fun hash(providerId: Long, externalId: String?, url: String, title: String): String {
        val identity = identity(providerId, externalId, url, title)
        val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(Locale.ROOT, byte) }
    }

    private fun stableLong(providerId: Long, externalId: String?, url: String, title: String): Long {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(identity(providerId, externalId, url, title).toByteArray(Charsets.UTF_8))
        var result = 0L
        repeat(8) { index -> result = (result shl 8) or (digest[index].toLong() and 0xff) }
        return (result and Long.MAX_VALUE).coerceAtLeast(1L)
    }

    private fun identity(providerId: Long, externalId: String?, url: String, title: String): String {
        val normalizedExternalId = externalId?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return if (normalizedExternalId.isNotBlank()) {
            // Keep tvg-id/tvg-name stable across title and signed-token refreshes while
            // still distinguishing multi-source streams that share the same EPG tvg-id.
            "$providerId|external=$normalizedExternalId|url=${canonicalUrl(url, stripVolatileQueryParams = true)}"
        } else {
            "$providerId|url=${canonicalUrl(url)}|title=${normalize(title)}"
        }
    }

    private fun canonicalUrl(url: String, stripVolatileQueryParams: Boolean = false): String = runCatching {
        val parsed = URI(url)
        buildString {
            append(parsed.scheme?.lowercase(Locale.ROOT).orEmpty())
            append("://")
            val host = parsed.host?.lowercase(Locale.ROOT)
            if (host != null) {
                append(host)
                parsed.port.takeIf { it > 0 }?.let { append(':').append(it) }
            } else {
                // Single-label hex hashes starting with a digit (e.g. acestream://<40-hex>)
                // are parsed by java.net.URI as rawAuthority with host == null.
                append(parsed.rawAuthority?.lowercase(Locale.ROOT).orEmpty())
            }
            append(parsed.rawPath.orEmpty())
            val query = if (stripVolatileQueryParams) {
                parsed.rawQuery
                    ?.split('&')
                    ?.filter { pair ->
                        pair.substringBefore('=').lowercase(Locale.ROOT) !in volatileQueryKeys
                    }
                    ?.takeIf { it.isNotEmpty() }
                    ?.joinToString("&")
            } else {
                parsed.rawQuery
            }
            query?.takeIf { it.isNotEmpty() }?.let { append('?').append(it) }
        }
    }.getOrElse { url.substringBefore('#') }

    private fun normalize(value: String): String = value
        .trim()
        .lowercase(Locale.ROOT)
        .replace(Regex("\\s+"), " ")
}
