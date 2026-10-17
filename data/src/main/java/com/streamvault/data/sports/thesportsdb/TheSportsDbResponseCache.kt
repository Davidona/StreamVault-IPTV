package com.streamvault.data.sports.thesportsdb

import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** Small, process-local cache. Keys are account/query identities and never contain credentials. */
@Singleton
class TheSportsDbResponseCache @Inject constructor() {
    private data class Entry(val value: Any, val expiresAt: Instant)

    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)

    @Synchronized
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(key: String, now: Instant): T? {
        val entry = entries[key] ?: return null
        if (!now.isBefore(entry.expiresAt)) {
            entries.remove(key)
            return null
        }
        return entry.value as T
    }

    @Synchronized
    fun put(key: String, value: Any, ttl: Duration, now: Instant) {
        require(!ttl.isNegative && !ttl.isZero)
        entries[key] = Entry(value, now.plus(ttl))
        while (entries.size > MAX_ENTRIES) {
            val eldest = entries.entries.iterator()
            if (!eldest.hasNext()) break
            eldest.next()
            eldest.remove()
        }
    }

    companion object {
        private const val MAX_ENTRIES = 128
    }
}
