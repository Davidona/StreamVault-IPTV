package com.streamvault.feature.playback.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SeekFeedbackModelTest {

    private val durationMs = 6_410_000L

    @Test
    fun `forward skip lands one step ahead of the engine position`() {
        val event = SeekTargetTracker().next(SeekDirection.FORWARD, 754_000L, durationMs, nowMs = 0L)!!

        assertThat(event.fromMs).isEqualTo(754_000L)
        assertThat(event.toMs).isEqualTo(764_000L)
        assertThat(event.appliedDeltaMs).isEqualTo(10_000L)
    }

    @Test
    fun `presses inside the window continue from the previous target, not the stale engine position`() {
        val tracker = SeekTargetTracker()
        tracker.next(SeekDirection.FORWARD, 754_000L, durationMs, nowMs = 0L)

        val second = tracker.next(SeekDirection.FORWARD, 754_000L, durationMs, nowMs = 300L)!!

        assertThat(second.fromMs).isEqualTo(764_000L)
        assertThat(second.toMs).isEqualTo(774_000L)
    }

    @Test
    fun `a press after the window reads the engine position again`() {
        val tracker = SeekTargetTracker()
        tracker.next(SeekDirection.FORWARD, 754_000L, durationMs, nowMs = 0L)

        val later = tracker.next(SeekDirection.FORWARD, 900_000L, durationMs, nowMs = SEEK_STACK_WINDOW_MS + 1)!!

        assertThat(later.fromMs).isEqualTo(900_000L)
    }

    @Test
    fun `backward skip near the start reports the clamped amount`() {
        val event = SeekTargetTracker().next(SeekDirection.BACKWARD, 4_000L, durationMs, nowMs = 0L)!!

        assertThat(event.toMs).isEqualTo(0L)
        assertThat(event.appliedDeltaMs).isEqualTo(4_000L)
    }

    @Test
    fun `skip that changes nothing produces no event`() {
        val tracker = SeekTargetTracker()

        assertThat(tracker.next(SeekDirection.BACKWARD, 0L, durationMs, nowMs = 0L)).isNull()
        assertThat(tracker.next(SeekDirection.FORWARD, durationMs, durationMs, nowMs = 10L)).isNull()
    }

    @Test
    fun `unknown duration does not clamp forward skips`() {
        val event = SeekTargetTracker().next(SeekDirection.FORWARD, 754_000L, durationMs = 0L, nowMs = 0L)!!

        assertThat(event.toMs).isEqualTo(764_000L)
    }

    @Test
    fun `feedback stacks while the direction holds and the window is open`() {
        val first = reduceSeekFeedback(null, event(SeekDirection.FORWARD, from = 754_000L, to = 764_000L), nowMs = 0L)
        val second = reduceSeekFeedback(first, event(SeekDirection.FORWARD, from = 764_000L, to = 774_000L), nowMs = 400L)

        assertThat(second.accumulatedMs).isEqualTo(20_000L)
        assertThat(second.stackStartMs).isEqualTo(754_000L)
        assertThat(second.toMs).isEqualTo(774_000L)
        assertThat(second.pulse).isEqualTo(2)
    }

    @Test
    fun `changing direction starts a new stack`() {
        val first = reduceSeekFeedback(null, event(SeekDirection.FORWARD, from = 754_000L, to = 764_000L), nowMs = 0L)
        val back = reduceSeekFeedback(first, event(SeekDirection.BACKWARD, from = 764_000L, to = 754_000L), nowMs = 200L)

        assertThat(back.accumulatedMs).isEqualTo(10_000L)
        assertThat(back.stackStartMs).isEqualTo(764_000L)
    }

    @Test
    fun `a press after the window starts a new stack`() {
        val first = reduceSeekFeedback(null, event(SeekDirection.FORWARD, from = 754_000L, to = 764_000L), nowMs = 0L)
        val later = reduceSeekFeedback(
            first,
            event(SeekDirection.FORWARD, from = 764_000L, to = 774_000L),
            nowMs = SEEK_STACK_WINDOW_MS + 1
        )

        assertThat(later.accumulatedMs).isEqualTo(10_000L)
    }

    private fun event(direction: SeekDirection, from: Long, to: Long) = SeekFeedbackEvent(
        direction = direction,
        appliedDeltaMs = kotlin.math.abs(to - from),
        fromMs = from,
        toMs = to,
        durationMs = durationMs
    )
}
