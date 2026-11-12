package com.streamvault.domain.playback.events

import java.time.Instant
import java.util.UUID

enum class PlaybackEventType { START, PAUSE, RESUME, PROGRESS, WATCHED, STOP }
enum class PlaybackMediaType { MOVIE, EPISODE, LIVE }
enum class PlaybackEventPhase { PLAYING, PAUSED, BUFFERING, STOPPED, ENDED, FAILED }
enum class PlaybackStopReason { USER, REPLACED, COMPLETED, ERROR }

/** IDs always identify their own entity. Show IDs belong in [PlaybackMedia.seriesIds]. */
data class PlaybackExternalIds(val tmdb: Long? = null, val imdb: String? = null, val tvdb: Long? = null) {
    init {
        require(tmdb == null || tmdb > 0)
        require(tvdb == null || tvdb > 0)
        require(imdb == null || imdb.matches(Regex("tt[0-9]+")))
    }
}

/** Contains deliberately no source URL, credentials, or artwork URL. */
data class PlaybackMedia(
    val type: PlaybackMediaType,
    val title: String,
    val series: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val ids: PlaybackExternalIds = PlaybackExternalIds(),
    val seriesIds: PlaybackExternalIds = PlaybackExternalIds(),
) {
    init {
        require(season == null || season >= 0)
        require(episode == null || episode > 0)
    }
}

data class PlaybackEventPolicy(val progressIntervalMs: Long = 30_000, val watchedPercent: Int = 90) {
    init {
        require(progressIntervalMs in setOf(0L, 15_000L, 30_000L, 60_000L))
        require(watchedPercent in 50..100)
    }
}

/** Generation increases only when the logical media session changes, not on engine recovery. */
data class PlaybackObservation(
    val generation: Long,
    val media: PlaybackMedia,
    val phase: PlaybackEventPhase,
    val positionMs: Long,
    val durationMs: Long?,
    val elapsedRealtimeMs: Long,
    val timestamp: Instant,
    val discontinuity: Boolean = false,
)

/** Frozen before dispatch; retries must reuse this event and its ID. */
data class PlaybackEvent(
    val eventId: String,
    val sessionId: String,
    val sequence: Long,
    val event: PlaybackEventType,
    val timestamp: Instant,
    val media: PlaybackMedia,
    val positionMs: Long,
    val durationMs: Long?,
    val playedMs: Long,
    val watched: Boolean,
    val stopReason: PlaybackStopReason? = null,
) {
    val progressPercent: Double?
        get() = durationMs?.takeIf { it > 0 }?.let {
            (positionMs.toDouble() / it * 100).coerceIn(0.0, 100.0)
        }

    companion object { const val SCHEMA_VERSION = 1 }
}

/** Pure, single-owner state machine. No I/O, account dependency, or playback-engine dependency. */
class PlaybackEventPlanner(
    private val policy: PlaybackEventPolicy = PlaybackEventPolicy(),
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private var latest: PlaybackObservation? = null
    private var sessionId = ""
    private var sequence = 0L
    private var started = false
    private var paused = false
    private var closed = false
    private var watched = false
    private var playedMs = 0L
    private var lastProgressMs = 0L

    fun observe(observation: PlaybackObservation): List<PlaybackEvent> {
        val previous = latest
        if (previous != null && observation.generation < previous.generation) return emptyList()
        val events = mutableListOf<PlaybackEvent>()
        val changed = previous == null || observation.generation > previous.generation
        if (changed) {
            if (started && !closed && previous != null) {
                events += event(previous.copy(timestamp = observation.timestamp), PlaybackEventType.STOP,
                    PlaybackStopReason.REPLACED)
            }
            sessionId = newId()
            sequence = 0
            started = false
            paused = false
            closed = false
            watched = false
            playedMs = 0
            lastProgressMs = observation.elapsedRealtimeMs
        } else {
            if (closed || observation.elapsedRealtimeMs < previous!!.elapsedRealtimeMs) return emptyList()
            // Account only confirmed continuous playback intervals. Never count a seek jump,
            // a pause/buffer interval, or the unobserved tail of a replaced session.
            if (started && previous.phase == PlaybackEventPhase.PLAYING && !observation.discontinuity &&
                observation.positionMs > previous.positionMs) {
                playedMs += observation.elapsedRealtimeMs - previous.elapsedRealtimeMs
            }
        }
        latest = observation
        when (observation.phase) {
            PlaybackEventPhase.PLAYING -> {
                if (!started) {
                    started = true
                    lastProgressMs = observation.elapsedRealtimeMs
                    events += event(observation, PlaybackEventType.START)
                } else if (paused) {
                    paused = false
                    lastProgressMs = observation.elapsedRealtimeMs
                    events += event(observation, PlaybackEventType.RESUME)
                }
            }
            PlaybackEventPhase.PAUSED -> if (started && !paused) {
                paused = true
                events += event(observation, PlaybackEventType.PAUSE)
            }
            PlaybackEventPhase.BUFFERING -> Unit
            else -> Unit
        }
        val duration = observation.durationMs?.takeIf { it > 0 }
        val advanced = !changed && previous?.phase == PlaybackEventPhase.PLAYING &&
            !observation.discontinuity && observation.positionMs > previous.positionMs
        if (started && !watched && advanced && observation.media.type != PlaybackMediaType.LIVE &&
            duration != null && observation.positionMs.toDouble() / duration * 100 >= policy.watchedPercent) {
            watched = true
            events += event(observation, PlaybackEventType.WATCHED)
        }
        if (started && observation.phase == PlaybackEventPhase.PLAYING && policy.progressIntervalMs > 0 &&
            observation.elapsedRealtimeMs - lastProgressMs >= policy.progressIntervalMs) {
            lastProgressMs = observation.elapsedRealtimeMs
            events += event(observation, PlaybackEventType.PROGRESS)
        }
        val reason = when (observation.phase) {
            PlaybackEventPhase.STOPPED -> PlaybackStopReason.USER
            PlaybackEventPhase.ENDED -> PlaybackStopReason.COMPLETED
            PlaybackEventPhase.FAILED -> PlaybackStopReason.ERROR
            else -> null
        }
        if (reason != null) {
            closed = true
            if (started) events += event(observation, PlaybackEventType.STOP, reason)
        }
        return events
    }

    private fun event(observation: PlaybackObservation, type: PlaybackEventType,
        reason: PlaybackStopReason? = null): PlaybackEvent {
        val live = observation.media.type == PlaybackMediaType.LIVE
        return PlaybackEvent(newId(), sessionId, ++sequence, type, observation.timestamp, observation.media,
            if (live) playedMs else observation.positionMs.coerceAtLeast(0),
            observation.durationMs?.takeIf { !live && it > 0 }, playedMs, watched, reason)
    }
}
