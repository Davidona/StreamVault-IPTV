package com.streamvault.player.stats

import com.streamvault.player.PlaybackState

/** Main-thread diagnostics. BUFFERING records an interruption, not proof of buffer depletion. */
internal class PlaybackBufferingTracker(private val nowMs: () -> Long) {
    // A renderer/track reconfiguration can enter BUFFERING while media remains buffered.
    // Keep counting that interruption without assigning a network or reserve cause.
    enum class Cause { STARTUP, SEEK, RECOVERY, REBUFFER }
    enum class Outcome { STARTED, RESUMED, CANCELLED }
    data class Event(val cause: Cause, val outcome: Outcome, val durationMs: Long)

    private var pendingCause: Cause? = Cause.STARTUP
    private var activeCause: Cause? = null
    private var startedAtMs = 0L

    fun reset(recovering: Boolean = false) {
        pendingCause = if (recovering) Cause.RECOVERY else Cause.STARTUP
        activeCause = null
    }

    fun onSeek() {
        pendingCause = Cause.SEEK
    }

    fun onRecovery() {
        pendingCause = Cause.RECOVERY
    }

    fun update(state: PlaybackState, playWhenReady: Boolean, playbackStarted: Boolean): List<Event> {
        val now = nowMs()
        val events = mutableListOf<Event>()
        if (activeCause != null && (state != PlaybackState.BUFFERING || !playWhenReady || pendingCause != null)) {
            events += Event(
                activeCause!!,
                if (state == PlaybackState.READY && playWhenReady && pendingCause == null) {
                    Outcome.RESUMED
                } else Outcome.CANCELLED,
                (now - startedAtMs).coerceAtLeast(0L)
            )
            activeCause = null
        }
        if (state == PlaybackState.BUFFERING && playWhenReady && activeCause == null) {
            val cause = pendingCause ?: if (playbackStarted) Cause.REBUFFER else Cause.STARTUP
            pendingCause = null
            activeCause = cause
            startedAtMs = now
            events += Event(cause, Outcome.STARTED, 0L)
        } else if (state == PlaybackState.READY) {
            pendingCause = null
        }
        return events
    }
}
