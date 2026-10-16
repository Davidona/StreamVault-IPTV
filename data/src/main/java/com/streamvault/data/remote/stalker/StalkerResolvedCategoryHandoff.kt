package com.streamvault.data.remote.stalker

import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.CatalogLayout
import com.streamvault.domain.model.StalkerConfig
import com.google.gson.Gson
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** One-shot, process-local reuse of successful layout probes, never authentication authority. */
internal object StalkerResolvedCategoryHandoff {
    private const val TTL_MILLIS = 60_000L
    private const val MAX_ENTRIES = 256
    private val pending = ConcurrentHashMap<String, Snapshot>()
    private val gson = Gson()

    data class Snapshot(
        val session: StalkerSession,
        val categories: Map<ContentType, List<StalkerCategoryRecord>>,
        val createdAt: Long = System.currentTimeMillis(),
        val layout: CatalogLayout = CatalogLayout.UNKNOWN
    ) {
        fun matches(current: StalkerSession, now: Long = System.currentTimeMillis()): Boolean =
            now - createdAt in 0 until TTL_MILLIS && !session.isExpired(now) &&
                session.loadUrl == current.loadUrl && session.token == current.token &&
                session.authEpoch == current.authEpoch && session.sessionScopeKey == current.sessionScopeKey
    }

    fun key(providerId: Long, generation: Long, config: StalkerConfig): String {
        // Credentials/configuration participate in matching, but are not retained in map keys.
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(gson.toJson(config).toByteArray(Charsets.UTF_8))
        val hex = "0123456789abcdef"
        return "$providerId/$generation/" + buildString(digest.size * 2) {
            digest.forEach { append(hex[(it.toInt() and 255) ushr 4]); append(hex[it.toInt() and 15]) }
        }
    }

    fun publish(key: String, snapshot: Snapshot) {
        val now = System.currentTimeMillis()
        pending.entries.removeIf { now - it.value.createdAt >= TTL_MILLIS }
        pending[key] = snapshot.copy(categories = snapshot.categories.mapValues { it.value.toList() })
        while (pending.size > MAX_ENTRIES) pending.keys.firstOrNull()?.let(pending::remove) ?: break
    }

    fun take(key: String, session: StalkerSession, layout: CatalogLayout = CatalogLayout.UNKNOWN): Snapshot? =
        pending.remove(key)?.takeIf { it.matches(session) && (layout == CatalogLayout.UNKNOWN || it.layout == layout) }

    fun invalidate(key: String) { pending.remove(key) }
    fun clearProvider(providerId: Long) { pending.keys.filter { it.startsWith("$providerId/") }.forEach(pending::remove) }
    fun clear() { pending.clear() }
}
