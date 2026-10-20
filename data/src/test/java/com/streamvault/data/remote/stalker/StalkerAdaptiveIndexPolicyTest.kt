package com.streamvault.data.remote.stalker

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StalkerAdaptiveIndexPolicyTest {
    @Test
    fun twentySuccessfulResponsesAddOneSlotUpToEight() {
        val policy = StalkerAdaptiveIndexPolicy()
        assertThat(policy.concurrency).isEqualTo(3)
        repeat(19) { policy.success(false) }
        assertThat(policy.concurrency).isEqualTo(3)
        policy.success(false)
        assertThat(policy.concurrency).isEqualTo(4)
        repeat(1_000) { policy.success(false) }
        assertThat(policy.concurrency).isEqualTo(8)
    }

    @Test
    fun stressHalvesWindowToOneAndHealthyResponsesCanRecover() {
        val policy = StalkerAdaptiveIndexPolicy(8)
        policy.stress()
        assertThat(policy.concurrency).isEqualTo(4)
        repeat(100) { policy.stress() }
        assertThat(policy.concurrency).isEqualTo(1)
        repeat(20) { policy.success(false) }
        assertThat(policy.concurrency).isEqualTo(2)
    }

    @Test
    fun playbackAndFailuresBreakHealthyStreak() {
        val policy = StalkerAdaptiveIndexPolicy()
        repeat(19) { policy.success(false) }
        policy.success(true)
        policy.success(false)
        assertThat(policy.concurrency).isEqualTo(3)
        policy.resetHealth()
        repeat(19) { policy.success(false) }
        assertThat(policy.concurrency).isEqualTo(3)
    }
}
