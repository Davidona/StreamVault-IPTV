package com.streamvault.data.remote.xtream

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

internal fun parseXtreamRetryAfter(value: String?, nowMs: Long = System.currentTimeMillis()): Long? {
    val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    text.toLongOrNull()?.let { seconds ->
        if (seconds < 0) return null
        return seconds.coerceAtMost(Long.MAX_VALUE / 1000) * 1000
    }
    return runCatching {
        (ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - nowMs)
            .coerceAtLeast(0)
    }.getOrNull()
}
