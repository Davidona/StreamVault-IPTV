package com.streamvault.data.remote.xtream

import com.streamvault.domain.model.ContentType
import com.streamvault.domain.util.StreamEntryUrlPolicy
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

internal object XtreamUrlCodec {
    private const val UTF_8_NAME = "UTF-8"
    private val encodeWithCharsetMethod = runCatching {
        URLEncoder::class.java.getMethod(
            "encode",
            String::class.java,
            java.nio.charset.Charset::class.java
        )
    }.getOrNull()
    private val decodeWithCharsetMethod = runCatching {
        URLDecoder::class.java.getMethod(
            "decode",
            String::class.java,
            java.nio.charset.Charset::class.java
        )
    }.getOrNull()

    fun encode(value: String): String {
        val encoded = encodeWithCharsetMethod
            ?.let { method ->
                runCatching { method.invoke(null, value, Charsets.UTF_8) as? String }.getOrNull()
            }
            ?: URLEncoder.encode(value, UTF_8_NAME)
        return encoded.replace("+", "%20")
    }

    fun decode(value: String): String {
        return decodeWithCharsetMethod
            ?.let { method ->
                runCatching { method.invoke(null, value, Charsets.UTF_8) as? String }.getOrNull()
            }
            ?: URLDecoder.decode(value, UTF_8_NAME)
    }
}

enum class XtreamStreamKind(val pathSegment: String) {
    LIVE("live"),
    MOVIE("movie"),
    SERIES("series")
}

data class XtreamStreamToken(
    val providerId: Long,
    val kind: XtreamStreamKind,
    val streamId: Long,
    val containerExtension: String? = null,
    val directSource: String? = null
)

object XtreamUrlFactory {
    private const val INTERNAL_SCHEME = "xtream"
    private val queryCredentialRegex = Regex("([?&](?:username|password)=)[^&\\s]+", RegexOption.IGNORE_CASE)
    private val liveMovieSeriesPathRegex = Regex(
        """(https?://[^\s/]+(?:/[^\s/?#]+)*)/(live|movie|series)/[^/\s?]+/[^/\s?]+(/[^\s?#]*)?""",
        RegexOption.IGNORE_CASE
    )
    private val timeshiftPathRegex = Regex(
        """(https?://[^\s/]+(?:/[^\s/?#]+)*)/timeshift/[^/\s?]+/[^/\s?]+(/[^\s?#]*)?""",
        RegexOption.IGNORE_CASE
    )
    private val timeshiftsPathRegex = Regex(
        """(https?://[^\s/]+(?:/[^\s/?#]+)*)/timeshifts/[^/\s?]+/[^/\s?]+(/[^\s?#]*)?""",
        RegexOption.IGNORE_CASE
    )

    fun buildPlayerApiUrl(
        serverUrl: String,
        username: String,
        password: String,
        action: String? = null,
        extraQueryParams: Map<String, String?> = emptyMap()
    ): String {
        return buildUrl(
            serverUrl = serverUrl,
            path = "player_api.php",
            queryParams = buildList {
                add("username" to username)
                add("password" to password)
                action?.let { add("action" to it) }
                extraQueryParams.forEach { (key, value) -> value?.let { add(key to it) } }
            }
        )
    }

    fun buildXmltvUrl(serverUrl: String, username: String, password: String): String {
        return buildUrl(
            serverUrl = serverUrl,
            path = "xmltv.php",
            queryParams = listOf(
                "username" to username,
                "password" to password
            )
        )
    }

    fun buildPlaybackUrl(
        serverUrl: String,
        username: String,
        password: String,
        kind: XtreamStreamKind,
        streamId: Long,
        containerExtension: String? = null
    ): String {
        val normalizedExtension = normalizeContainerExtension(containerExtension)
        val ext = when (kind) {
            XtreamStreamKind.LIVE -> normalizedExtension ?: "ts"
            XtreamStreamKind.MOVIE, XtreamStreamKind.SERIES -> normalizedExtension ?: "mp4"
        }
        return serverUrl.trimEnd('/') + "/${kind.pathSegment}/" +
            encodePathSegment(username) + "/" +
            encodePathSegment(password) + "/" +
            encodePathSegment(streamId.toString()) + "." + encodePathSegment(ext)
    }

    fun buildCatchUpUrl(
        serverUrl: String,
        username: String,
        password: String,
        durationMinutes: Long,
        formattedStart: String,
        streamId: Long,
        containerExtension: String? = null
    ): String {
        val ext = normalizeContainerExtension(containerExtension) ?: "ts"
        return serverUrl.trimEnd('/') + "/timeshift/" +
            encodePathSegment(username) + "/" +
            encodePathSegment(password) + "/" +
            encodePathSegment(durationMinutes.toString()) + "/" +
            encodePathSegment(formattedStart) + "/" +
            encodePathSegment(streamId.toString()) + "." + encodePathSegment(ext)
    }

    fun buildCatchUpShiftUrl(
        serverUrl: String,
        username: String,
        password: String,
        durationMinutes: Long,
        formattedStart: String,
        streamId: Long,
        containerExtension: String? = null
    ): String {
        val ext = normalizeContainerExtension(containerExtension) ?: "ts"
        return serverUrl.trimEnd('/') + "/timeshifts/" +
            encodePathSegment(username) + "/" +
            encodePathSegment(password) + "/" +
            encodePathSegment(durationMinutes.toString()) + "/" +
            encodePathSegment(streamId.toString()) + "/" +
            encodePathSegment(formattedStart) + "." + encodePathSegment(ext)
    }

    fun buildCatchUpPhpUrl(
        serverUrl: String,
        username: String,
        password: String,
        durationMinutes: Long,
        formattedStart: String,
        streamId: Long,
        containerExtension: String? = null,
        includeExtension: Boolean = true,
        path: String = "streaming/timeshift.php"
    ): String {
        val normalizedExtension = normalizeContainerExtension(containerExtension)
        return buildUrl(
            serverUrl = serverUrl,
            path = path,
            queryParams = buildList {
                add("username" to username)
                add("password" to password)
                add("stream" to streamId.toString())
                add("start" to formattedStart)
                add("duration" to durationMinutes.toString())
                if (includeExtension) {
                    normalizedExtension?.let { add("extension" to it) }
                }
            }
        )
    }

    fun buildCatchUpUrls(
        serverUrl: String,
        username: String,
        password: String,
        durationMinutes: Long,
        formattedStart: String,
        streamId: Long,
        containerExtensions: List<String>
    ): List<String> {
        val extensions = containerExtensions
            .mapNotNull(::normalizeContainerExtension)
            .ifEmpty { listOf("ts") }
            .distinct()

        return extensions.flatMap { extension ->
            listOf(
                buildCatchUpUrl(
                    serverUrl = serverUrl,
                    username = username,
                    password = password,
                    durationMinutes = durationMinutes,
                    formattedStart = formattedStart,
                    streamId = streamId,
                    containerExtension = extension
                ),
                buildCatchUpShiftUrl(
                    serverUrl = serverUrl,
                    username = username,
                    password = password,
                    durationMinutes = durationMinutes,
                    formattedStart = formattedStart,
                    streamId = streamId,
                    containerExtension = extension
                ),
                buildCatchUpPhpUrl(
                    serverUrl = serverUrl,
                    username = username,
                    password = password,
                    durationMinutes = durationMinutes,
                    formattedStart = formattedStart,
                    streamId = streamId,
                    containerExtension = extension,
                    includeExtension = true,
                    path = "streaming/timeshift.php"
                ),
                buildCatchUpPhpUrl(
                    serverUrl = serverUrl,
                    username = username,
                    password = password,
                    durationMinutes = durationMinutes,
                    formattedStart = formattedStart,
                    streamId = streamId,
                    containerExtension = extension,
                    includeExtension = false,
                    path = "streaming/timeshift.php"
                ),
                buildCatchUpPhpUrl(
                    serverUrl = serverUrl,
                    username = username,
                    password = password,
                    durationMinutes = durationMinutes,
                    formattedStart = formattedStart,
                    streamId = streamId,
                    containerExtension = extension,
                    includeExtension = true,
                    path = "timeshift.php"
                ),
                buildCatchUpPhpUrl(
                    serverUrl = serverUrl,
                    username = username,
                    password = password,
                    durationMinutes = durationMinutes,
                    formattedStart = formattedStart,
                    streamId = streamId,
                    containerExtension = extension,
                    includeExtension = false,
                    path = "timeshift.php"
                )
            )
        }.distinct()
    }

    /** Matches only archive URLs generated for this provider and channel, retaining their time range. */
    fun isGeneratedCatchUpUrl(
        url: String,
        serverUrl: String,
        username: String,
        password: String,
        streamId: Long
    ): Boolean = runCatching {
        val prefix = serverUrl.trimEnd('/') + "/"
        if (!url.startsWith(prefix) || !StreamEntryUrlPolicy.isAllowed(url)) return@runCatching false
        val relative = URI(url.removePrefix(prefix))
        val segments = relative.rawPath.split('/')
        val duration: Long
        val start: String
        val extension: String
        when (segments.firstOrNull()) {
            "timeshift", "timeshifts" -> {
                if (segments.size != 6) return@runCatching false
                duration = segments[3].toLongOrNull() ?: return@runCatching false
                val file = XtreamUrlCodec.decode(segments[5])
                extension = normalizeContainerExtension(file.substringAfterLast('.', "")) ?: return@runCatching false
                start = if (segments[0] == "timeshift") XtreamUrlCodec.decode(segments[4]) else file.substringBeforeLast('.')
            }
            "timeshift.php", "streaming" -> {
                if (relative.rawPath !in listOf("timeshift.php", "streaming/timeshift.php")) return@runCatching false
                val query = parseQuery(relative.rawQuery)
                duration = query["duration"]?.toLongOrNull() ?: return@runCatching false
                start = query["start"] ?: return@runCatching false
                extension = query["extension"]?.let(::normalizeContainerExtension) ?: "ts"
            }
            else -> return@runCatching false
        }
        if (duration <= 0 || !start.matches(Regex("\\d{4}-\\d{2}-\\d{2}:\\d{2}-\\d{2}"))) return@runCatching false
        url in buildCatchUpUrls(serverUrl, username, password, duration, start, streamId, listOf(extension))
    }.getOrDefault(false)

    fun buildInternalStreamUrl(
        providerId: Long,
        kind: XtreamStreamKind,
        streamId: Long,
        containerExtension: String? = null,
        directSource: String? = null
    ): String {
        val normalizedExtension = normalizeContainerExtension(containerExtension)
        val normalizedDirectSource = normalizeDirectSource(directSource)
        val queryParameters = buildList {
            normalizedExtension?.let { add("ext=${encodeQueryComponent(it)}") }
            normalizedDirectSource?.let { add("src=${encodeQueryComponent(it)}") }
        }
        val query = queryParameters.takeIf { it.isNotEmpty() }
            ?.joinToString(prefix = "?", separator = "&")
            .orEmpty()
        return "$INTERNAL_SCHEME://$providerId/${kind.pathSegment}/$streamId$query"
    }

    fun parseInternalStreamUrl(url: String?): XtreamStreamToken? {
        if (url.isNullOrBlank()) return null
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (!uri.scheme.equals(INTERNAL_SCHEME, ignoreCase = true)) return null
        val providerId = uri.authority?.toLongOrNull() ?: return null
        val pathSegments = uri.path
            ?.trim('/')
            ?.split('/')
            ?.filter { it.isNotBlank() }
            .orEmpty()
        val kind = pathSegments.getOrNull(0)?.let(::kindFromPathSegment) ?: return null
        val streamId = pathSegments.getOrNull(1)?.toLongOrNull() ?: return null
        val query = parseQuery(uri.rawQuery)
        val ext = query["ext"]?.let(::normalizeContainerExtension)
        val directSource = query["src"]?.let(::normalizeDirectSource)
        return XtreamStreamToken(providerId, kind, streamId, ext, directSource)
    }

    fun kindForContentType(contentType: ContentType): XtreamStreamKind? = when (contentType) {
        ContentType.LIVE -> XtreamStreamKind.LIVE
        ContentType.VOD -> null
        ContentType.MOVIE -> XtreamStreamKind.MOVIE
        ContentType.SERIES_EPISODE -> XtreamStreamKind.SERIES
        ContentType.SERIES -> null
    }

    fun sanitizePersistedStreamUrl(url: String, providerId: Long): String {
        val parsed = parseCredentialedStreamUrl(url, providerId) ?: return url
        return buildInternalStreamUrl(
            providerId = parsed.providerId,
            kind = parsed.kind,
            streamId = parsed.streamId,
            containerExtension = parsed.containerExtension,
            directSource = parsed.directSource
        )
    }

    fun isInternalStreamUrl(url: String?): Boolean = parseInternalStreamUrl(url) != null

    fun sanitizeLogMessage(message: String): String {
        val queryRedacted = queryCredentialRegex.replace(message) { match ->
            match.groupValues[1] + "<redacted>"
        }
        val pathRedacted = liveMovieSeriesPathRegex.replace(queryRedacted) { match ->
            val prefix = match.groupValues[1]
            val type = match.groupValues[2]
            val suffix = match.groupValues[3]
            "$prefix/$type/<redacted>/<redacted>$suffix"
        }
        return timeshiftPathRegex.replace(pathRedacted) { match ->
            val prefix = match.groupValues[1]
            val suffix = match.groupValues[2]
            "$prefix/timeshift/<redacted>/<redacted>$suffix"
        }
            .let { redacted ->
                timeshiftsPathRegex.replace(redacted) { match ->
                    val prefix = match.groupValues[1]
                    val suffix = match.groupValues[2]
                    "$prefix/timeshifts/<redacted>/<redacted>$suffix"
                }
            }
    }

    internal fun parseCredentialedStreamUrl(url: String, providerId: Long): XtreamStreamToken? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val pathSegments = uri.path
            ?.trim('/')
            ?.split('/')
            ?.filter { it.isNotBlank() }
            .orEmpty()
        val kind = pathSegments.getOrNull(0)?.let(::kindFromPathSegment) ?: return null
        if (pathSegments.size < 4) return null
        val fileSegment = pathSegments[3]
        val dotIndex = fileSegment.lastIndexOf('.')
        val streamId = fileSegment.substring(0, dotIndex.takeIf { it > 0 } ?: fileSegment.length).toLongOrNull() ?: return null
        val ext = if (dotIndex > 0 && dotIndex < fileSegment.lastIndex) {
            normalizeContainerExtension(fileSegment.substring(dotIndex + 1))
        } else {
            null
        }
        return XtreamStreamToken(providerId, kind, streamId, ext, directSource = null)
    }

    private fun kindFromPathSegment(segment: String): XtreamStreamKind? = when (segment.lowercase()) {
        XtreamStreamKind.LIVE.pathSegment -> XtreamStreamKind.LIVE
        XtreamStreamKind.MOVIE.pathSegment -> XtreamStreamKind.MOVIE
        XtreamStreamKind.SERIES.pathSegment -> XtreamStreamKind.SERIES
        else -> null
    }

    private fun buildUrl(
        serverUrl: String,
        path: String,
        queryParams: List<Pair<String, String>>
    ): String {
        val query = queryParams.joinToString("&") { (key, value) ->
            "${encodeQueryComponent(key)}=${encodeQueryComponent(value)}"
        }
        return serverUrl.trimEnd('/') + "/$path?$query"
    }

    private fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        return rawQuery.split('&')
            .mapNotNull { pair ->
                val key = pair.substringBefore('=', missingDelimiterValue = "")
                    .takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val value = pair.substringAfter('=', missingDelimiterValue = "")
                decodeQueryComponent(key) to decodeQueryComponent(value)
            }
            .toMap()
    }

    private fun encodePathSegment(value: String): String = encodeQueryComponent(value)

    private fun encodeQueryComponent(value: String): String {
        return XtreamUrlCodec.encode(value)
    }

    private fun decodeQueryComponent(value: String): String {
        return XtreamUrlCodec.decode(value)
    }

    private fun normalizeContainerExtension(containerExtension: String?): String? {
        return containerExtension
            ?.trim()
            ?.removePrefix(".")
            ?.lowercase(Locale.ROOT)
            // Allow-list: lowercase alphanumeric only, 1–12 chars.
            // Rejects path traversal chars, query chars, and control characters.
            ?.takeIf { it.matches(Regex("[a-z0-9]{1,12}")) }
    }

    private fun normalizeDirectSource(directSource: String?): String? {
        val trimmed = directSource?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        // Cap length before storing in internal URLs/database to prevent oversized tokens.
        if (trimmed.length > 8192) return null
        // Validate the value is a permitted stream URL; rejected values are dropped here
        // rather than surfaced as a playback error later.
        return trimmed.takeIf { StreamEntryUrlPolicy.isAllowed(it) }
    }
}
