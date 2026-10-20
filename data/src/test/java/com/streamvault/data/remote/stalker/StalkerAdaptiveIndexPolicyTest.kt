package com.streamvault.data.remote.stalker

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StalkerAdaptiveIndexPolicyTest {
    @Test
    fun healthyRampRequiresBothSamplesAndTimeAndStopsAtTwoRequestsPerSecond() {
        val policy = StalkerAdaptiveIndexPolicy()
        repeat(100) { policy.success(0L, 100L, false) }
        assertThat(policy.intervalMillis).isEqualTo(5_000L)
        policy.success(15_000L, 100L, false)
        assertThat(policy.intervalMillis).isEqualTo(3_750L)
        repeat(1_000) { policy.success(30_000L + it * 1_000L, 100L, false) }
        assertThat(policy.intervalMillis).isEqualTo(500L)
    }

    @Test
    fun stressPermanentlyLowersLearnedCeilingAndBoundsRepeatedBackoff() {
        val policy = StalkerAdaptiveIndexPolicy(1_000L)
        policy.stress()
        repeat(1_000) { policy.success(it * 2_000L, 100L, false) }
        assertThat(policy.intervalMillis).isEqualTo(2_000L)
        assertThat(policy.minimumIntervalMillis).isEqualTo(2_000L)
        repeat(100) { policy.stress() }
        assertThat(policy.intervalMillis).isEqualTo(30_000L)
    }

    @Test
    fun playbackAndFailuresBreakHealthyStreakAndSlowResponsesBackOff() {
        val policy = StalkerAdaptiveIndexPolicy()
        repeat(11) { policy.success(it * 2_000L, 100L, false) }
        policy.success(30_000L, 100L, true)
        policy.success(32_000L, 100L, false)
        assertThat(policy.intervalMillis).isEqualTo(5_000L)
        policy.resetHealth()
        policy.success(60_000L, 2_000L, false)
        assertThat(policy.intervalMillis).isEqualTo(10_000L)
    }
}
