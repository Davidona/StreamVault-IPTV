package com.streamvault.player.stats

import com.google.common.truth.Truth.assertThat
import com.streamvault.player.PlaybackState
import com.streamvault.player.stats.PlaybackBufferingTracker.Cause
import com.streamvault.player.stats.PlaybackBufferingTracker.Outcome
import org.junit.Test

class PlaybackBufferingTrackerTest {
    private var now = 0L
    private val tracker = PlaybackBufferingTracker { now }

    private fun update(state: PlaybackState, started: Boolean = true, requested: Boolean = true) =
        tracker.update(state, requested, started)

    @Test fun `startup and a seek are not rebuffers`() {
        assertThat(update(PlaybackState.BUFFERING, started = false).single().cause).isEqualTo(Cause.STARTUP)
        now = 1_200
        val opened = update(PlaybackState.READY).single()
        assertThat(opened.durationMs).isEqualTo(1_200)
        assertThat(opened.outcome).isEqualTo(Outcome.RESUMED)
        tracker.onSeek()
        assertThat(update(PlaybackState.BUFFERING).single().cause).isEqualTo(Cause.SEEK)
    }

    @Test fun `rebuffer is counted once and measures the entire interruption`() {
        update(PlaybackState.READY)
        assertThat(update(PlaybackState.BUFFERING).single().cause).isEqualTo(Cause.REBUFFER)
        now = 1_000
        assertThat(update(PlaybackState.BUFFERING)).isEmpty()
        now = 4_500
        val resumed = update(PlaybackState.READY).single()
        assertThat(resumed.cause).isEqualTo(Cause.REBUFFER)
        assertThat(resumed.outcome).isEqualTo(Outcome.RESUMED)
        assertThat(resumed.durationMs).isEqualTo(4_500)
    }

    @Test fun `paused buffering does not start an interruption and pause ends active measurement`() {
        update(PlaybackState.READY)
        assertThat(update(PlaybackState.BUFFERING, requested = false)).isEmpty()
        update(PlaybackState.BUFFERING)
        now = 600
        val cancelled = update(PlaybackState.BUFFERING, requested = false).single()
        assertThat(cancelled.outcome).isEqualTo(Outcome.CANCELLED)
        assertThat(cancelled.durationMs).isEqualTo(600)
        now = 60_000
        assertThat(update(PlaybackState.READY, requested = false)).isEmpty()
    }

    @Test fun `seek in an existing interruption cancels rebuffer rather than claiming recovery`() {
        update(PlaybackState.READY)
        update(PlaybackState.BUFFERING)
        now = 700
        tracker.onSeek()
        val events = update(PlaybackState.BUFFERING)
        assertThat(events.map { it.outcome }).containsExactly(Outcome.CANCELLED, Outcome.STARTED).inOrder()
        assertThat(events.last().cause).isEqualTo(Cause.SEEK)
        now = 900
        assertThat(update(PlaybackState.READY).single().durationMs).isEqualTo(200)
    }

    @Test fun `source preparation and in-place recovery are separated from rebuffers`() {
        tracker.reset(recovering = true)
        assertThat(update(PlaybackState.BUFFERING).single().cause).isEqualTo(Cause.RECOVERY)
        update(PlaybackState.READY)
        tracker.onRecovery()
        assertThat(update(PlaybackState.BUFFERING).single().cause).isEqualTo(Cause.RECOVERY)
        tracker.reset()
        assertThat(update(PlaybackState.BUFFERING, started = false).single().cause).isEqualTo(Cause.STARTUP)
    }

    @Test fun `error and stop do not claim playback resumed`() {
        for (terminal in listOf(PlaybackState.ERROR, PlaybackState.IDLE, PlaybackState.ENDED)) {
            tracker.reset()
            update(PlaybackState.READY)
            update(PlaybackState.BUFFERING)
            assertThat(update(terminal).single().outcome).isEqualTo(Outcome.CANCELLED)
        }
    }
}
