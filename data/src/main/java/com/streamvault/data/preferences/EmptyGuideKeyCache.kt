package com.streamvault.data.preferences

internal object EmptyGuideKeyCache {
    const val TTL_MILLIS = 24L * 60L * 60L * 1000L
    const val MAX_ENTRIES = 512
}

internal fun decodeEmptyGuideKeys(encoded: String?, now: Long): Map<String, Long> =
    encoded
        .orEmpty()
        .lineSequence()
        .mapNotNull { line ->
            val separator = line.indexOf('\t')
            if (separator <= 0) return@mapNotNull null
            val key = line.substring(0, separator).trim()
            val timestamp = line.substring(separator + 1).trim().toLongOrNull() ?: return@mapNotNull null
            if (key.isBlank() || timestamp <= 0L || now - timestamp >= EmptyGuideKeyCache.TTL_MILLIS) {
                return@mapNotNull null
            }
            key to timestamp
        }
        .toList()
        .sortedByDescending { it.second }
        .take(EmptyGuideKeyCache.MAX_ENTRIES)
        .toMap()

internal fun encodeEmptyGuideKeys(values: Map<String, Long>): String =
    values
        .filter { (key, timestamp) -> key.isNotBlank() && timestamp > 0L }
        .entries
        .sortedByDescending { it.value }
        .take(EmptyGuideKeyCache.MAX_ENTRIES)
        .joinToString("\n") { (key, timestamp) -> "$key\t$timestamp" }
