package com.streamvault.player.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioRendererRecoveryPolicyTest {
    @Test fun duplicateCallbacksAndRepeatedFailuresAreBounded() {
        val policy = AudioRendererRecoveryPolicy()

        assertTrue(policy.shouldRecover("channel-a", 1_000L))
        assertFalse(policy.shouldRecover("channel-a", 1_001L))
        assertTrue(policy.shouldRecover("channel-a", 16_000L))
        assertFalse(policy.shouldRecover("channel-a", 32_000L))
    }

    @Test fun changingMediaOrResettingEngineRestoresBudget() {
        val policy = AudioRendererRecoveryPolicy(maxAttemptsPerMedia = 1)

        assertTrue(policy.shouldRecover("channel-a", 1_000L))
        assertFalse(policy.shouldRecover("channel-a", 20_000L))
        assertTrue(policy.shouldRecover("channel-b", 20_001L))
        policy.reset()
        assertTrue(policy.shouldRecover("channel-b", 20_002L))
    }

    @Test fun stablePlaybackRestoresBudgetForLongRunningLiveChannel() {
        val policy = AudioRendererRecoveryPolicy(maxAttemptsPerMedia = 1)

        assertTrue(policy.shouldRecover("channel-a", 1_000L))
        assertFalse(policy.shouldRecover("channel-a", 100_000L))
        assertTrue(policy.shouldRecover("channel-a", 301_000L))
    }
}
