package com.streamvault.data.repository

import java.net.URI
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import com.streamvault.domain.provider.DEFAULT_M3U_CATCH_UP_QUERY_FORMAT

private val catchUpToken = Regex("\\$\\{([^{}]+)\\}|\\{([^{}]+)\\}")
private val legacyCatchUpToken = Regex("\\$\\{\\((b|e)\\)([^}]+?)(?::(utc))?\\}")
private val javaDatePatternRun = Regex("([yMLwdDFuHkKhmsS])\\1+")

private const val DATE_LETTERS = "YmdHMS"
private const val MILLIS_PER_SECOND = 1_000L

internal fun buildM3uCatchUpUrls(
    source: String?,
    streamUrl: String?,
    catchUpType: String?,
    start: Long,
    end: Long,
    correctionSeconds: Int,
    fallbackTemplate: String = DEFAULT_M3U_CATCH_UP_QUERY_FORMAT
): List<String> {
    if (start <= 0L || end <= start) return emptyList()
    val liveUrl = streamUrl?.trim().orEmpty()
    val template = source?.trim().orEmpty()
    val mode = catchUpType?.trim()?.lowercase(Locale.ROOT).orEmpty()
    if (template.hasCatchUpIdPlaceholder()) return emptyList()
    val now = System.currentTimeMillis() / MILLIS_PER_SECOND
    val correctedStart = start + correctionSeconds
    val correctedEnd = maxOf(correctedStart + 1L, end + correctionSeconds)
    val correctedNow = now + correctionSeconds

    val fullSource = template.takeIf(::hasUrlScheme)
    val candidates = when {
        fullSource != null -> listOf(
            expandCatchUpTemplate(
                fullSource,
                correctedStart,
                correctedEnd,
                correctedNow,
                correctionSeconds
            )
        )
        template.isNotBlank() && liveUrl.isNotBlank() && mode in APPEND_MODES -> listOf(
            appendCatchUpSource(
                liveUrl,
                expandCatchUpTemplate(template, correctedStart, correctedEnd, correctedNow, correctionSeconds)
            )
        )
        mode in SHIFT_MODES && liveUrl.isNotBlank() -> {
            val shiftSource = template.ifBlank { "?utc={utc}&lutc={lutc}" }
            listOf(
                appendCatchUpSource(
                    liveUrl,
                    expandCatchUpTemplate(shiftSource, correctedStart, correctedEnd, correctedNow, correctionSeconds)
                )
            )
        }
        mode in APPEND_MODES && liveUrl.isNotBlank() -> listOfNotNull(
            fallbackTemplate.takeIf(String::isNotBlank)?.let { queryFormat ->
                appendCatchUpSource(
                    liveUrl,
                    expandCatchUpTemplate(queryFormat, correctedStart, correctedEnd, correctedNow, correctionSeconds)
                )
            }
        )
        mode.isFlussonicMode() && liveUrl.isNotBlank() -> listOfNotNull(
            buildFlussonicCatchUpUrl(liveUrl, correctedStart, correctedEnd)
        )
        mode in XTREAM_MODES && liveUrl.isNotBlank() ->
            buildXtreamCatchUpUrls(liveUrl, correctedStart, correctedEnd)
        else -> emptyList()
    }
    return candidates.filter(String::isNotBlank).distinct()
}

private fun String.hasCatchUpIdPlaceholder(): Boolean = catchUpToken.findAll(this).any { match ->
    val specifier = match.groups[1]?.value ?: match.groups[2]?.value.orEmpty()
    specifier.substringBefore(':').trim().equals("catchup-id", ignoreCase = true)
}

private fun expandCatchUpTemplate(
    template: String,
    start: Long,
    end: Long,
    now: Long,
    correctionSeconds: Int
): String {
    val durationSeconds = (end - start).coerceAtLeast(1L)
    val durationMinutes = (durationSeconds / 60L).coerceAtLeast(1L)
    val expandedLegacy = legacyCatchUpToken.replace(template) { match ->
        val seconds = if (match.groupValues[1] == "b") start else end
        val format = match.groupValues[2]
        if (format.equals("timestamp", ignoreCase = true)) {
            seconds.toString()
        } else {
            formatDateToken(seconds, format) ?: match.value
        }
    }
    return catchUpToken.replace(expandedLegacy) { match ->
        val specifier = (match.groups[1]?.value ?: match.groups[2]?.value.orEmpty()).trim()
        if (specifier.isEmpty()) {
            return@replace match.value
        }
        val name = specifier.substringBefore(':').lowercase(Locale.ROOT)
        val format = specifier.substringAfter(':', "")
        val instant = when (name) {
            "utc", "start", "start-timestamp", "utcstart" -> start
            "utcend", "end", "stop", "end-timestamp" -> end
            "lutc", "now", "timenow", "currenttime", "timestamp" -> now
            else -> null
        }
        if (instant != null) {
            return@replace if (format.isBlank()) {
                instant.toString()
            } else {
                formatDateToken(instant, format) ?: match.value
            }
        }
        when (name) {
            "duration" -> formatDivided(durationSeconds, format) ?: match.value
            "duration_seconds" -> durationSeconds.toString()
            "duration_minutes" -> durationMinutes.toString()
            "offset" -> formatDivided((now - start).coerceAtLeast(0L), format) ?: match.value
            else -> formatDateToken(start, specifier) ?: match.value
        }
    }
}

private fun formatDivided(seconds: Long, divisorText: String): String? = if (divisorText.isBlank()) {
    seconds.toString()
} else {
    divisorText.toLongOrNull()?.takeIf { it > 0L }?.let { divisor -> (seconds / divisor).toString() }
}

private fun formatDateToken(epochSeconds: Long, format: String): String? {
    if (format.isBlank()) return null
    if (format.equals("YmdHis", ignoreCase = true)) {
        return formatDateLetters(epochSeconds, "YmdHMS")
    }
    if (format.contains('y') || javaDatePatternRun.containsMatchIn(format)) {
        return runCatching {
            SimpleDateFormat(format, Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(Date(epochSeconds * MILLIS_PER_SECOND))
        }.getOrNull()
    }
    return format.takeIf(::isDateLetterFormat)?.let { formatDateLetters(epochSeconds, it) }
}

private fun isDateLetterFormat(format: String): Boolean {
    var hasDateLetter = false
    format.forEach { character ->
        if (!character.isLetter()) return@forEach
        if (character !in DATE_LETTERS) return false
        hasDateLetter = true
    }
    return hasDateLetter
}

private fun formatDateLetters(epochSeconds: Long, format: String): String {
    val date = Instant.ofEpochSecond(epochSeconds).atZone(ZoneOffset.UTC)
    fun two(value: Int) = value.toString().padStart(2, '0')
    return buildString(format.length) {
        format.forEach { character ->
            append(
                when (character) {
                    'Y' -> date.format(DateTimeFormatter.ofPattern("yyyy", Locale.ROOT))
                    'm' -> two(date.monthValue)
                    'd' -> two(date.dayOfMonth)
                    'H' -> two(date.hour)
                    'M' -> two(date.minute)
                    'S' -> two(date.second)
                    else -> character
                }
            )
        }
    }
}

private fun appendCatchUpSource(baseUrl: String, source: String): String {
    if (source.isBlank()) return baseUrl
    if (hasUrlScheme(source)) return source
    val fragmentIndex = baseUrl.indexOf('#')
    val base = if (fragmentIndex >= 0) baseUrl.substring(0, fragmentIndex) else baseUrl
    val fragment = if (fragmentIndex >= 0) baseUrl.substring(fragmentIndex) else ""
    val baseHasQuery = base.contains('?')
    val appended = when {
        source.startsWith('?') && baseHasQuery -> base + "&" + source.drop(1)
        source.startsWith('&') && baseHasQuery -> base + source
        source.startsWith('&') -> base + "?" + source.drop(1)
        source.startsWith('?') -> base + source
        baseHasQuery -> "$base&$source"
        else -> "$base?$source"
    }
    return appended + fragment
}

private fun buildFlussonicCatchUpUrl(liveUrl: String, start: Long, end: Long): String? {
    val uri = runCatching { URI(liveUrl) }.getOrNull() ?: return null
    if (uri.rawAuthority.isNullOrBlank()) return null
    val path = uri.rawPath?.takeIf(String::isNotBlank) ?: return null
    val lastSegment = path.substringAfterLast('/')
    val prefix = path.substringBeforeLast('/', "")
    val durationSeconds = (end - start).coerceAtLeast(1L)
    val archiveName = when {
        lastSegment.endsWith(".m3u8", ignoreCase = true) -> "timeshift_abs-$start.m3u8"
        lastSegment.equals("mpegts", ignoreCase = true) ||
            lastSegment.endsWith(".ts", ignoreCase = true) -> "archive-$start-$durationSeconds.ts"
        else -> return null
    }
    val archivePath = prefix.trimEnd('/') + "/" + archiveName
    return rebuildUri(uri, archivePath)
}

private data class XtreamLiveUrlParts(
    val uri: URI,
    val username: String,
    val password: String,
    val streamId: String,
    val extension: String,
    val prefixSegments: List<String>
)

private fun buildXtreamCatchUpUrls(liveUrl: String, start: Long, end: Long): List<String> {
    val parts = parseXtreamLiveUrl(liveUrl) ?: return emptyList()
    val durationMinutes = ((end - start) / 60L).coerceAtLeast(1L)
    val startText = Instant.ofEpochSecond(start)
        .atZone(ZoneOffset.UTC)
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd:HH-mm", Locale.ROOT))
    val path = (parts.prefixSegments +
        listOf("timeshift", parts.username, parts.password, durationMinutes.toString(), startText, "${parts.streamId}.${parts.extension}"))
        .joinToString(separator = "/", prefix = "/")
    val pathUrl = rebuildUri(parts.uri, path)
    val phpUrl = buildXtreamPhpUrl(parts, startText, durationMinutes)
    return listOf(phpUrl, pathUrl).distinct()
}

private fun parseXtreamLiveUrl(liveUrl: String): XtreamLiveUrlParts? {
    val uri = runCatching { URI(liveUrl) }.getOrNull() ?: return null
    if (uri.rawAuthority.isNullOrBlank()) return null
    val pathSegments = uri.rawPath.orEmpty().split('/').filter(String::isNotBlank)
    if (pathSegments.size < 3) return null

    val liveIndex = pathSegments.indexOfLast { it.equals("live", ignoreCase = true) }
    val credentialIndex = if (liveIndex >= 0) liveIndex + 1 else pathSegments.size - 3
    if (credentialIndex < 0 || pathSegments.size - credentialIndex < 3) return null
    val username = pathSegments[credentialIndex]
    val password = pathSegments[credentialIndex + 1]
    val streamToken = pathSegments[credentialIndex + 2]
    val streamId = streamToken.substringBeforeLast('.', streamToken)
    if (streamId.isBlank()) return null
    val extension = streamToken.substringAfterLast('.', "ts").takeIf(String::isNotBlank) ?: "ts"
    val prefix = pathSegments.take(if (liveIndex >= 0) liveIndex else credentialIndex)
    return XtreamLiveUrlParts(uri, username, password, streamId, extension, prefix)
}

private fun buildXtreamPhpUrl(
    parts: XtreamLiveUrlParts,
    startText: String,
    durationMinutes: Long
): String {
    val scriptPath = if (parts.prefixSegments.isEmpty()) {
        "/streaming/timeshift.php"
    } else {
        "/" + parts.prefixSegments.joinToString("/") + "/streaming/timeshift.php"
    }
    val query = listOf(
        "username" to encodeQueryValue(parts.username),
        "password" to encodeQueryValue(parts.password),
        "stream" to encodeQueryValue(parts.streamId),
        "start" to startText,
        "duration" to durationMinutes.toString()
    ).joinToString("&") { (key, value) -> "$key=$value" }
    return buildString {
        append(parts.uri.scheme).append("://").append(parts.uri.rawAuthority)
        append(scriptPath).append('?').append(query)
    }
}

private fun encodeQueryValue(value: String): String =
    URLEncoder.encode(value, Charsets.UTF_8.name())

private fun rebuildUri(uri: URI, path: String): String = buildString {
    append(uri.scheme).append("://").append(uri.rawAuthority).append(path)
    uri.rawQuery?.let { append('?').append(it) }
    uri.rawFragment?.let { append('#').append(it) }
}

private fun hasUrlScheme(value: String): Boolean =
    Regex("^[A-Za-z][A-Za-z0-9+.-]*://").containsMatchIn(value)

private fun String.isFlussonicMode(): Boolean = this == "fs" || startsWith("flussonic")

private val APPEND_MODES = setOf("", "default", "append")
private val SHIFT_MODES = setOf("shift", "timeshift")
private val XTREAM_MODES = setOf("xc", "xc60", "xtream", "xtream-codes")
