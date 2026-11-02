package com.streamvault.domain.model.stremio

import kotlin.math.abs

data class StremioPlaybackIdentity(val type: StremioMediaType, val id: String, val sessionKey: String)
data class StremioPlaybackEvent(
    val identity: StremioPlaybackIdentity, val action: String, val positionMs: Long, val durationMs: Long
) {
    fun extras() = listOf("action" to action, "currentTime" to positionMs.coerceAtLeast(0).toString(),
        "duration" to durationMs.coerceAtLeast(0).toString())
}

/** Produces state-transition/seek events, not periodic progress uploads. */
class StremioPlaybackEventTracker {
    private data class Sample(val identity: StremioPlaybackIdentity, val position: Long,
        val duration: Long, val playing: Boolean, val sampledAt: Long)
    private var previous: Sample? = null

    fun update(identity: StremioPlaybackIdentity?, positionMs: Long, durationMs: Long,
        playing: Boolean, ready: Boolean, ended: Boolean, nowMs: Long, speed: Float = 1f): List<StremioPlaybackEvent> = buildList {
        val old = previous
        if (old != null && (identity != old.identity || ended)) {
            add(StremioPlaybackEvent(old.identity, "stop", if (identity == old.identity) positionMs else old.position,
                if (identity == old.identity) durationMs else old.duration))
            previous = null
        }
        if (identity == null || ended) return@buildList
        val current = previous
        if (!ready) {
            if (current != null) previous = current.copy(position = positionMs, duration = durationMs, sampledAt = nowMs)
            return@buildList
        }
        if (current == null) {
            if (!playing) return@buildList
            add(StremioPlaybackEvent(identity, "start", positionMs, durationMs))
        } else {
            val expected = if (current.playing) ((nowMs - current.sampledAt).coerceAtLeast(0) * speed.coerceIn(0.1f, 16f)).toLong() else 0L
            val sought = abs(positionMs - current.position - expected) > 2_000L
            if (playing != current.playing || sought) {
                add(StremioPlaybackEvent(identity, if (playing) "start" else "pause", positionMs, durationMs))
            }
        }
        previous = Sample(identity, positionMs, durationMs, playing, nowMs)
    }

    fun close(): List<StremioPlaybackEvent> = previous?.let {
        previous = null
        listOf(StremioPlaybackEvent(it.identity, "stop", it.position, it.duration))
    }.orEmpty()
}
