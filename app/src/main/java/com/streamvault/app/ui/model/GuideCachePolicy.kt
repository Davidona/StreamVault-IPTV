package com.streamvault.app.ui.model

import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.Program

/**
 * Shared policy for on-demand guide hydration (get_short_epg / now-playing fallback).
 *
 * Both the EPG grid and the Home now-playing strip issue paced portal requests per channel.
 * To avoid re-fetching channels that are known to carry no guide data, the requestable
 * filter below must be applied by every caller:
 *  - Portal EPG ids that are shared placeholders (e.g. Ministra's "glotv") never carry a
 *    per-channel schedule; they must never be fetched or displayed as if they had data.
 *  - Keys whose on-demand fetch recently returned empty are skipped until the TTL elapses
 *    (in-memory for the session, and durably per provider across launches).
 */
object GuideCachePolicy {
    /** Portal EPG ids that are shared placeholders with no per-channel schedule. */
    val EPG_SENTINEL_KEYS: Set<String> = setOf("glotv")

    /** Re-probe a channel whose on-demand fetch returned empty after this long. */
    const val GUIDE_EMPTY_KEY_TTL_MILLIS: Long = 24 * 60 * 60 * 1000L

    fun isSentinelKey(lookupKey: String): Boolean = lookupKey in EPG_SENTINEL_KEYS

    /** Whether a durable empty-key record is still inside its TTL (i.e. skip the fetch). */
    fun isEmptyKeyFresh(
        persistedEmptyAt: Map<String, Long>,
        lookupKey: String,
        now: Long
    ): Boolean = persistedEmptyAt[lookupKey]?.let { now - it < GUIDE_EMPTY_KEY_TTL_MILLIS } == true

    /**
     * Channels worth spending a paced on-demand guide request on. A channel is requestable
     * when it has a guide lookup key that is not a sentinel placeholder, is not remembered
     * as empty (session or durable cache), and has no programs already in the snapshot.
     *
     * @param sessionEmptyKeys keys that returned empty this session (never re-probed)
     * @param persistedEmptyAt durable empty-key cache per provider: lookup key -> last empty epoch millis
     * @param existingProgramsByChannel programs already present for the window (by lookup key)
     */
    fun requestableChannels(
        channels: List<Channel>,
        sessionEmptyKeys: Set<String>,
        persistedEmptyAt: Map<String, Long>,
        existingProgramsByChannel: Map<String, List<Program>>,
        now: Long = System.currentTimeMillis()
    ): List<Channel> = channels.filter { channel ->
        val lookupKey = channel.guideLookupKey()
        lookupKey != null &&
            lookupKey !in sessionEmptyKeys &&
            lookupKey !in EPG_SENTINEL_KEYS &&
            !isEmptyKeyFresh(persistedEmptyAt, lookupKey, now) &&
            channel.streamId > 0L &&
            existingProgramsByChannel[lookupKey].isNullOrEmpty()
    }

    /** Sentinel keys present in the given channels; record them as empty without a request. */
    fun sentinelKeysOf(channels: List<Channel>): Set<String> =
        channels.mapNotNull(Channel::guideLookupKey)
            .filter { it in EPG_SENTINEL_KEYS }
            .toSet()
}