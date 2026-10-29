package com.streamvault.player.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StartupRefreshGateTest {
    @Test
    fun `drain completion requires a stable quiet interval`() {
        val gate = StartupRefreshGate()
        gate.begin(100L)

        assertThat(gate.evaluate(100L, canResume = true, hasActiveTransfers = false))
            .isEqualTo(StartupRefreshGate.Action.WAIT)
        assertThat(gate.evaluate(599L, canResume = true, hasActiveTransfers = false))
            .isEqualTo(StartupRefreshGate.Action.WAIT)
        assertThat(gate.isPending).isTrue()
        assertThat(gate.evaluate(600L, canResume = true, hasActiveTransfers = false))
            .isEqualTo(StartupRefreshGate.Action.READY)
        assertThat(gate.isPending).isFalse()
    }

    @Test
    fun `new transfer during quiet interval restarts the quiet clock`() {
        val gate = StartupRefreshGate()
        gate.begin(0L)

        assertThat(gate.evaluate(0L, canResume = true, hasActiveTransfers = false))
            .isEqualTo(StartupRefreshGate.Action.WAIT)
        assertThat(gate.evaluate(499L, canResume = true, hasActiveTransfers = false))
            .isEqualTo(StartupRefreshGate.Action.WAIT)
        assertThat(gate.evaluate(500L, canResume = true, hasActiveTransfers = true))
            .isEqualTo(StartupRefreshGate.Action.WAIT)
        assertThat(gate.evaluate(700L, canResume = true, hasActiveTransfers = false))
            .isEqualTo(StartupRefreshGate.Action.WAIT)
        assertThat(gate.evaluate(1_199L, canResume = true, hasActiveTransfers = false))
            .isEqualTo(StartupRefreshGate.Action.WAIT)
        assertThat(gate.evaluate(1_200L, canResume = true, hasActiveTransfers = false))
            .isEqualTo(StartupRefreshGate.Action.READY)
    }

    @Test
    fun `active transfers give up at the drain deadline`() {
        val gate = StartupRefreshGate(drainTimeoutMs = 25_000L, quietPeriodMs = 500L)
        gate.begin(1_000L)

        assertThat(gate.evaluate(25_999L, canResume = true, hasActiveTransfers = true))
            .isEqualTo(StartupRefreshGate.Action.WAIT)
        assertThat(gate.evaluate(26_000L, canResume = true, hasActiveTransfers = true))
            .isEqualTo(StartupRefreshGate.Action.GIVE_UP)
        assertThat(gate.isPending).isFalse()
        assertThat(gate.evaluate(30_000L, canResume = true, hasActiveTransfers = false))
            .isEqualTo(StartupRefreshGate.Action.WAIT)
    }

    @Test
    fun `pause or output loss suspends drain clock but preserves pending refresh`() {
        val gate = StartupRefreshGate(drainTimeoutMs = 25_000L, quietPeriodMs = 500L)
        gate.begin(0L)

        assertThat(gate.evaluate(20_000L, canResume = false, hasActiveTransfers = true))
            .isEqualTo(StartupRefreshGate.Action.WAIT)
        assertThat(gate.isPending).isTrue()
        assertThat(gate.evaluate(1_000_000L, canResume = false, hasActiveTransfers = true))
            .isEqualTo(StartupRefreshGate.Action.WAIT)

        // The bounded drain budget starts only when playback/output can resume.
        assertThat(gate.evaluate(1_000_000L, canResume = true, hasActiveTransfers = true))
            .isEqualTo(StartupRefreshGate.Action.WAIT)
        assertThat(gate.evaluate(1_024_999L, canResume = true, hasActiveTransfers = true))
            .isEqualTo(StartupRefreshGate.Action.WAIT)
        assertThat(gate.evaluate(1_025_000L, canResume = true, hasActiveTransfers = true))
            .isEqualTo(StartupRefreshGate.Action.GIVE_UP)
    }

    @Test
    fun `not resumable without transfers pauses both timeout and quiet interval`() {
        val gate = StartupRefreshGate(drainTimeoutMs = 1_000L, quietPeriodMs = 500L)
        gate.begin(0L)

        assertThat(gate.evaluate(900L, canResume = false, hasActiveTransfers = false))
            .isEqualTo(StartupRefreshGate.Action.WAIT)
        assertThat(gate.evaluate(50_000L, canResume = true, hasActiveTransfers = false))
            .isEqualTo(StartupRefreshGate.Action.WAIT)
        assertThat(gate.evaluate(50_499L, canResume = true, hasActiveTransfers = false))
            .isEqualTo(StartupRefreshGate.Action.WAIT)
        assertThat(gate.evaluate(50_500L, canResume = true, hasActiveTransfers = false))
            .isEqualTo(StartupRefreshGate.Action.READY)
    }

    @Test
    fun `begin replaces previous timing state and clear cancels pending refresh`() {
        val gate = StartupRefreshGate(drainTimeoutMs = 1_000L, quietPeriodMs = 100L)
        gate.begin(0L)
        assertThat(gate.evaluate(900L, canResume = true, hasActiveTransfers = true))
            .isEqualTo(StartupRefreshGate.Action.WAIT)

        gate.begin(10_000L)
        assertThat(gate.evaluate(10_999L, canResume = true, hasActiveTransfers = true))
            .isEqualTo(StartupRefreshGate.Action.WAIT)
        gate.clear()
        assertThat(gate.isPending).isFalse()
        assertThat(gate.evaluate(20_000L, canResume = true, hasActiveTransfers = false))
            .isEqualTo(StartupRefreshGate.Action.WAIT)
    }
}
