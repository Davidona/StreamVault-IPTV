package com.streamvault.data.repository

import java.net.URI
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import com.streamvault.domain.provider.DEFAULT_M3U_CATCH_UP_QUERY_FORMAT

private val catchUpToken = Regex("\\$\\{([^{}]+)\\}|\\{([^{}]+)\\}")
private val compactJavaDateFormat = Regex("yyyyMMdd(?:HHmmss)?")

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
    val now = System.currentTimeMillis() / 1_000L
    val correctedStart = start + correctionSeconds
    val correctedEnd = maxOf(correctedStart + 1L, end + correctionSeconds)
    val correctedNow = now + correctionSeconds

    val fullSource = template.takeIf(::hasUrlScheme)
    val candidate = when {
        fullSource != null -> expandCatchUpTemplate(
            fullSource,
            correctedStart,
            correctedEnd,
            correctedNow,
            correctionSeconds
        )
        template.isNotBlank() && liveUrl.isNotBlank() && mode in APPEND_MODES ->
            appendCatchUpSource(liveUrl, expandCatchUpTemplate(template, correctedStart, correctedEnd, correctedNow, correctionSeconds))
        mode in SHIFT_MODES && liveUrl.isNotBlank() -> {
            val shiftSource = template.ifBlank { "?utc={utc}&lutc={lutc}" }
            appendCatchUpSource(liveUrl, expandCatchUpTemplate(shiftSource, correctedStart, correctedEnd, correctedNow, correctionSeconds))
        }
        mode in APPEND_MODES && liveUrl.isNotBlank() -> {
            fallbackTemplate.takeIf(String::isNotBlank)?.let { queryFormat ->
                appendCatchUpSource(
                    liveUrl,
                    expandCatchUpTemplate(queryFormat, correctedStart, correctedEnd, correctedNow, correctionSeconds)
                )
            }
        }
        mode in FLUSSONIC_MODES && liveUrl.isNotBlank() ->
            buildFlussonicCatchUpUrl(liveUrl, correctedStart, now)
        mode in XTREAM_MODES && liveUrl.isNotBlank() ->
            buildXtreamCatchUpUrl(liveUrl, correctedStart, correctedEnd)
        else -> null
    }
    return listOfNotNull(candidate?.takeIf(String::isNotBlank)).distinct()
}

private fun String.hasCatchUpIdPlaceholder(): Boolean = catchUpToken.findAll(this).any { match ->
    (match.groups[1]?.value ?: match.groups[2]?.value).equals("catchup-id", ignoreCase = true)
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
    return catchUpToken.replace(template) { match ->
        val rawSpecifier = match.groups[1]?.value ?: match.groups[2]?.value.orEmpty()
        val specifier = rawSpecifier.trim()
        if (specifier == "YmdHis") {
            return@replace formatTimestamp(start, "yyyyMMddHHmmss")
        }
        if (specifier in setOf("Y", "m", "d", "H", "M", "S")) {
            return@replace formatTimestamp(start, specifier)
        }
        val name = specifier.substringBefore(':').lowercase(Locale.ROOT)
        val format = specifier.substringAfter(':', "")
        when (name) {
            "utc", "start" -> formatTimestamp(start, format)
            "utcend", "end" -> formatTimestamp(end, format)
            "lutc", "now", "timestamp" -> formatTimestamp(now, format)
            "duration" -> {
                val divisor = format.toLongOrNull()?.takeIf { it > 0L } ?: 1L
                (durationSeconds / divisor).toString()
            }
            "duration_seconds" -> durationSeconds.toString()
            "duration_minutes" -> durationMinutes.toString()
            "offset" -> {
                val divisor = format.toLongOrNull()?.takeIf { it > 0L } ?: 1L
                ((now - start).coerceAtLeast(0L) / divisor).toString()
            }
            else -> match.value
        }
    }
}

private fun formatTimestamp(epochSeconds: Long, format: String): String {
    if (format.isBlank()) return epochSeconds.toString()
    val date = Instant.ofEpochSecond(epochSeconds).atZone(ZoneOffset.UTC)
    if (compactJavaDateFormat.matches(format)) {
        return runCatching { date.format(DateTimeFormatter.ofPattern(format, Locale.ROOT)) }
            .getOrDefault(epochSeconds.toString())
    }
    return buildString(format.length) {
        format.forEach { character ->
            append(
                when (character) {
                    'Y' -> date.format(DateTimeFormatter.ofPattern("yyyy", Locale.ROOT))
                    'm' -> date.format(DateTimeFormatter.ofPattern("MM", Locale.ROOT))
                    'd' -> date.format(DateTimeFormatter.ofPattern("dd", Locale.ROOT))
                    'H' -> date.format(DateTimeFormatter.ofPattern("HH", Locale.ROOT))
                    'M' -> date.format(DateTimeFormatter.ofPattern("mm", Locale.ROOT))
                    'S' -> date.format(DateTimeFormatter.ofPattern("ss", Locale.ROOT))
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

private fun buildFlussonicCatchUpUrl(liveUrl: String, start: Long, now: Long): String? {
    val uri = runCatching { URI(liveUrl) }.getOrNull() ?: return null
    if (uri.rawAuthority.isNullOrBlank()) return null
    val path = uri.rawPath?.takeIf(String::isNotBlank) ?: return null
    val lastSegment = path.substringAfterLast('/')
    val isHls = lastSegment.endsWith(".m3u8", ignoreCase = true)
    val extension = lastSegment.substringAfterLast('.', "ts").takeIf { it.isNotBlank() } ?: "ts"
    val streamPath = when {
        lastSegment.equals("index.m3u8", ignoreCase = true) -> path.substringBeforeLast('/')
        lastSegment.equals("index.ts", ignoreCase = true) -> path.substringBeforeLast('/')
        else -> path.substringBeforeLast('/').trimEnd('/') + "/" + lastSegment.substringBeforeLast('.', lastSegment)
    }.trimEnd('/')
    val archiveName = if (isHls) {
        "timeshift_rel-${(now - start).coerceAtLeast(0L)}.m3u8"
    } else {
        "timeshift_abs-$start.$extension"
    }
    return rebuildUri(uri, "$streamPath/$archiveName")
}

private fun buildXtreamCatchUpUrl(liveUrl: String, start: Long, end: Long): String? {
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
    val durationMinutes = ((end - start) / 60L).coerceAtLeast(1L)
    val startText = Instant.ofEpochSecond(start)
        .atZone(ZoneOffset.UTC)
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd:HH-mm", Locale.ROOT))
    val catchUpPath = (prefix + listOf("timeshift", username, password, durationMinutes.toString(), startText, "$streamId.$extension"))
        .joinToString(separator = "/", prefix = "/")
    return rebuildUri(uri, catchUpPath)
}

private fun rebuildUri(uri: URI, path: String): String = buildString {
    append(uri.scheme).append("://").append(uri.rawAuthority).append(path)
    uri.rawQuery?.let { append('?').append(it) }
    uri.rawFragment?.let { append('#').append(it) }
}

private fun hasUrlScheme(value: String): Boolean =
    Regex("^[A-Za-z][A-Za-z0-9+.-]*://").containsMatchIn(value)

private val APPEND_MODES = setOf("", "default", "append")
private val SHIFT_MODES = setOf("shift", "timeshift")
private val FLUSSONIC_MODES = setOf("fs", "flussonic")
private val XTREAM_MODES = setOf("xc", "xc60", "xtream", "xtream-codes")
