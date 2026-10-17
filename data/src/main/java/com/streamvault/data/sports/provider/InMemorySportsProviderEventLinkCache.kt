package com.streamvault.data.sports.provider

import com.streamvault.domain.sports.provider.ProviderEventLink
import com.streamvault.domain.sports.provider.SportsEventMergePolicy
import com.streamvault.domain.sports.provider.SportsProviderEventLinkCache
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Small bounded process cache. Provider IDs are always namespaced by account instance. */
@Singleton
class InMemorySportsProviderEventLinkCache @Inject constructor() : SportsProviderEventLinkCache {
    private val mutex = Mutex()
    private val entries = object : LinkedHashMap<Key, ProviderEventLink>(INITIAL_CAPACITY, LOAD_FACTOR, true) {}

    override suspend fun get(
        providerInstanceId: String,
        providerEventId: String,
        now: Instant,
        expectedLocalFingerprint: String?,
        providerEventStart: Instant?,
    ): ProviderEventLink? = mutex.withLock {
        val key = Key(providerInstanceId, providerEventId)
        val link = entries[key] ?: return@withLock null
        val linkedEventStart = link.eventStart
        val invalid =
            Duration.between(link.linkedAt, now).let { !it.isNegative && it > CACHE_TTL } ||
                (expectedLocalFingerprint != null && expectedLocalFingerprint != link.localEventFingerprint) ||
                (providerEventStart != null && linkedEventStart != null &&
                    distance(linkedEventStart, providerEventStart) > MAX_EVENT_START_DRIFT)
        if (invalid) {
            entries.remove(key)
            null
        } else link
    }

    override suspend fun put(link: ProviderEventLink) = mutex.withLock {
        if (link.confidence < SportsEventMergePolicy.MIN_EVENT_IDENTITY_CONFIDENCE) return@withLock
        val key = Key(link.providerInstanceId, link.providerEventId)
        // A reused provider event ID cannot silently retain a link to a different local event.
        entries[key] = link.copy(consecutiveFailures = 0)
        while (entries.size > MAX_ENTRIES) entries.entries.iterator().run { next(); remove() }
    }

    override suspend fun recordFailure(providerInstanceId: String, providerEventId: String): Boolean = mutex.withLock {
        val key = Key(providerInstanceId, providerEventId)
        val link = entries[key] ?: return@withLock false
        val failures = link.consecutiveFailures + 1
        if (failures >= MAX_CONSECUTIVE_FAILURES) {
            entries.remove(key)
            true
        } else {
            entries[key] = link.copy(consecutiveFailures = failures)
            false
        }
    }

    override suspend fun invalidate(providerInstanceId: String, providerEventId: String) {
        mutex.withLock { entries.remove(Key(providerInstanceId, providerEventId)) }
    }

    private data class Key(val providerInstanceId: String, val providerEventId: String)

    private fun distance(left: Instant, right: Instant): Duration =
        if (left.isBefore(right)) Duration.between(left, right) else Duration.between(right, left)

    companion object {
        private const val INITIAL_CAPACITY = 128
        private const val LOAD_FACTOR = 0.75f
        private const val MAX_ENTRIES = 10_000
        private const val MAX_CONSECUTIVE_FAILURES = 3
        private val CACHE_TTL: Duration = Duration.ofDays(7)
        private val MAX_EVENT_START_DRIFT: Duration = Duration.ofMinutes(60)
    }
}
