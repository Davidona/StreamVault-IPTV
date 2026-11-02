package com.streamvault.domain.model.stremio

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

enum class StremioStreamKind {
    DIRECT,
    TORRENT,
    YOUTUBE,
    EXTERNAL,
    UNKNOWN,
    ARCHIVE,
    USENET
}

data class StremioProxyHeaders(
    val request: Map<String, String> = emptyMap(),
    val response: Map<String, String> = emptyMap()
)

data class StremioStreamBehaviorHints(
    val notWebReady: Boolean = false,
    val bingeGroup: String? = null,
    val filename: String? = null,
    val videoSize: Long? = null,
    val videoHash: String? = null,
    val proxyHeaders: StremioProxyHeaders? = null
)

data class StremioStream(
    val name: String? = null,
    val title: String? = null,
    val description: String? = null,
    val url: String? = null,
    val ytId: String? = null,
    val infoHash: String? = null,
    val fileIdx: Int? = null,
    val externalUrl: String? = null,
    val androidTvUrl: String? = null,
    val sources: List<String> = emptyList(),
    val subtitles: List<StremioSubtitle> = emptyList(),
    val behaviorHints: StremioStreamBehaviorHints? = null,
    val archive:StremioArchiveSource?=null,
    val nzbUrls:List<String> = emptyList(),
    val servers:List<String> = emptyList()
) {
    val kind: StremioStreamKind
        get() = when {
            !infoHash.isNullOrBlank() -> StremioStreamKind.TORRENT
            url?.let(::isMagnetScheme) == true -> StremioStreamKind.TORRENT
            !url.isNullOrBlank() -> StremioStreamKind.DIRECT
            archive?.urls?.isNotEmpty()==true -> StremioStreamKind.ARCHIVE
            nzbUrls.isNotEmpty() -> StremioStreamKind.USENET
            !ytId.isNullOrBlank() -> StremioStreamKind.YOUTUBE
            !externalUrl.isNullOrBlank() || !androidTvUrl.isNullOrBlank() -> StremioStreamKind.EXTERNAL
            else -> StremioStreamKind.UNKNOWN
        }
    override fun toString()="StremioStream(kind=$kind)"
}

/** Builds the URL passed to a scheme-owning playback plugin for this torrent stream. */
fun StremioStream.toMagnetUri(): String? {
    val hash = infoHash?.trim()?.takeIf(BTIH_PATTERN::matches)
    if (hash != null) {
        val query = mutableListOf("xt=urn%3Abtih%3A${encodeMagnetValue(hash)}")
        (behaviorHints?.filename ?: title ?: name)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.take(MAX_MAGNET_DISPLAY_NAME_LENGTH)
            ?.let { query += "dn=${encodeMagnetValue(it)}" }
        fileIdx?.takeIf { it >= 0 }?.let { query += "so=$it" }
        sources.asSequence()
            .mapNotNull(::trackerUrl)
            .distinct()
            .take(MAX_MAGNET_TRACKERS)
            .forEach { query += "tr=${encodeMagnetValue(it)}" }
        return "magnet:?${query.joinToString("&")}".takeIf { it.length <= MAX_MAGNET_URI_LENGTH }
    }

    val rawMagnet = url?.trim()?.takeIf {
        it.length <= MAX_MAGNET_URI_LENGTH && isValidMagnetUri(it)
    } ?: return null
    val selectionAlreadyPresent = rawMagnetQuery(URI(rawMagnet)).orEmpty()
        .split('&')
        .mapNotNull { parameter -> parameter.substringBefore('=').decodeMagnetValueOrNull() }
        .any { it.equals("so", ignoreCase = true) }
    val selectedFile = fileIdx?.takeIf { it >= 0 } ?: return rawMagnet
    return if (selectionAlreadyPresent) rawMagnet
    else "$rawMagnet&so=$selectedFile".takeIf { it.length <= MAX_MAGNET_URI_LENGTH }
}

private fun isMagnetScheme(value: String): Boolean =
    value.startsWith("magnet:", ignoreCase = true)

private fun isValidMagnetUri(value: String): Boolean = runCatching {
    val uri = URI(value)
    if (!uri.scheme.equals("magnet", ignoreCase = true) || uri.rawAuthority != null ||
        !uri.rawPath.isNullOrEmpty() || uri.rawFragment != null
    ) return@runCatching false
    rawMagnetQuery(uri)?.split('&')?.any { parameter ->
        val name = parameter.substringBefore('=').decodeMagnetValueOrNull()
        val valuePart = parameter.substringAfter('=', "").decodeMagnetValueOrNull()
        name.equals("xt", ignoreCase = true) &&
            valuePart?.startsWith("urn:btih:", ignoreCase = true) == true &&
            BTIH_PATTERN.matches(valuePart.substringAfterLast(':'))
    } == true
}.getOrDefault(false)

/** `magnet:` is an opaque URI, so Java exposes its query after `?` as the scheme-specific part. */
private fun rawMagnetQuery(uri: URI): String? =
    uri.rawSchemeSpecificPart?.takeIf { it.startsWith("?") }?.substring(1)

private fun trackerUrl(source: String): String? {
    val candidate = source.trim().let {
        if (it.startsWith("tracker:", ignoreCase = true)) it.substringAfter(':') else it
    }
    if (candidate.length > MAX_TRACKER_URL_LENGTH) return null
    val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
    if (uri.scheme?.lowercase() !in TRACKER_SCHEMES || uri.host.isNullOrBlank()) return null
    return candidate
}

private fun encodeMagnetValue(value: String): String =
    URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

private fun String.decodeMagnetValueOrNull(): String? =
    runCatching { URLDecoder.decode(this, Charsets.UTF_8.name()) }.getOrNull()

private val BTIH_PATTERN = Regex("(?i)(?:[0-9a-f]{40}|[a-z2-7]{32})")
private val TRACKER_SCHEMES = setOf("http", "https", "udp")
private const val MAX_MAGNET_DISPLAY_NAME_LENGTH = 512
private const val MAX_MAGNET_TRACKERS = 8
private const val MAX_TRACKER_URL_LENGTH = 512
private const val MAX_MAGNET_URI_LENGTH = 16_384

/** Opens only known media/web schemes; addon values never become executable intents. */
fun StremioStream.externalLaunchUrl(preferAndroidTv: Boolean = false): String? {
    val candidate = when {
        preferAndroidTv && !androidTvUrl.isNullOrBlank() -> androidTvUrl
        !externalUrl.isNullOrBlank() -> externalUrl
        !androidTvUrl.isNullOrBlank() -> androidTvUrl
        !ytId.isNullOrBlank() && ytId.matches(Regex("[A-Za-z0-9_-]{11}")) -> "https://www.youtube.com/watch?v=$ytId"
        url != null && !url.startsWith("http://", true) && !url.startsWith("https://", true) -> url
        else -> null
    } ?: return null
    val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
    return candidate.takeIf {
        uri.scheme?.lowercase() in setOf("http", "https", "stremio", "rtsp", "rtmp", "ftp", "ftps", "netflix", "nflx", "youtube", "vnd.youtube", "primevideo", "disneyplus", "vlc", "mpv") &&
            (!uri.host.isNullOrBlank() || uri.isOpaque && !uri.rawSchemeSpecificPart.isNullOrBlank() || uri.scheme.equals("stremio", true))
    }
}
