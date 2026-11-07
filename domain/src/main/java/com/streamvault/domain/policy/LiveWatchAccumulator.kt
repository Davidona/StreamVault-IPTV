package com.streamvault.domain.policy

import com.streamvault.domain.model.LiveWatchDelta
import com.streamvault.domain.model.LiveWatchTarget
import java.util.UUID

/** Monotonic samples count only actual playback. Call with no target at session boundaries. */
class LiveWatchAccumulator(private val sessionIdFactory: () -> String = { UUID.randomUUID().toString() }) {
    private data class Pending(val target: LiveWatchTarget, val day: Long, var ms: Long = 0, var lastAt: Long = 0)
    private var previousTarget: LiveWatchTarget? = null
    private var previousPlaying = false
    private var previousPrivate = false
    private var previousElapsed: Long? = null
    private var sessionId = ""
    private var qualifiedMs = 0L
    private var sequence = 0L
    private val pending = linkedMapOf<Triple<Long, Long, Long>, Pending>()

    fun sample(target: LiveWatchTarget?, isPlaying: Boolean, incognito: Boolean,
        elapsedMs: Long, wallTimeMs: Long): List<LiveWatchDelta> {
        val previous = previousTarget
        val elapsed = previousElapsed
        val output = mutableListOf<LiveWatchDelta>()
        if (incognito != previousPrivate || (elapsed != null && elapsedMs < elapsed)) {
            resetSession()
        } else if (!incognito && previous != null && previousPlaying && elapsed != null) {
            // A suspended sampler cannot establish arbitrarily long playback intervals.
            val delta = (elapsedMs - elapsed).coerceIn(0, MAX_SAMPLE_GAP_MS)
            if (delta > 0) {
                var remaining = delta
                var at = wallTimeMs - delta
                while (remaining > 0) {
                    val day = Math.floorDiv(at, DAY_MS)
                    val part = minOf(remaining, (day + 1) * DAY_MS - at)
                    val bucket = pending.getOrPut(Triple(previous.contentId, previous.streamId, day)) { Pending(previous, day) }
                    bucket.ms += part
                    bucket.lastAt = at + part
                    at += part
                    remaining -= part
                }
                qualifiedMs += delta
            }
        }
        val changedChannel = previous?.providerId != target?.providerId || previous?.logicalKey != target?.logicalKey
        if (!incognito && qualifiedMs >= QUALIFY_MS &&
            (changedChannel || !isPlaying || pending.values.sumOf { it.ms } >= FLUSH_MS)) {
            pending.values.filter { it.ms > 0 }.forEach { part ->
                output += LiveWatchDelta(part.target, sessionId, ++sequence, part.ms, part.lastAt, part.day)
            }
            pending.clear()
        }
        if (changedChannel) resetSession()
        if (!incognito && target != null && sessionId.isEmpty()) sessionId = sessionIdFactory()
        previousTarget = target
        previousPlaying = isPlaying && target != null && !incognito
        previousPrivate = incognito
        previousElapsed = elapsedMs
        return output
    }

    private fun resetSession() {
        sessionId = ""
        qualifiedMs = 0
        sequence = 0
        pending.clear()
    }

    companion object {
        const val QUALIFY_MS = 10_000L
        const val FLUSH_MS = 5_000L
        const val MAX_SAMPLE_GAP_MS = 10_000L
        private const val DAY_MS = 86_400_000L
    }
}
