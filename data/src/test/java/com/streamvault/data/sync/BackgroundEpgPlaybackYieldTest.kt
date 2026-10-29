package com.streamvault.data.sync

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.ProviderType
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test

class BackgroundEpgPlaybackYieldTest {
    @Test fun `playback cancels and joins only the import before returning deferred`() = runTest {
        val active = MutableStateFlow(false)
        val entered = CompletableDeferred<Unit>()
        var closed = false
        val result = async {
            runGuideWithPlaybackYield(true, active) {
                try { entered.complete(Unit); awaitCancellation() }
                finally { closed = true }
            }
        }
        entered.await()
        active.value = true
        assertThat(result.await()).isEqualTo(ProviderWorkflowOutcome.Deferred)
        assertThat(closed).isTrue()
    }

    @Test fun `manual and incremental Stalker work never enter playback interruption`() = runTest {
        assertThat(canYieldInflightGuide(ProviderType.M3U, true)).isFalse()
        assertThat(canYieldInflightGuide(ProviderType.STALKER_PORTAL, false)).isFalse()
        assertThat(canYieldInflightGuide(ProviderType.XTREAM_CODES, false)).isFalse()
        assertThat(canYieldInflightGuide(ProviderType.M3U, false)).isTrue()
        val result = runGuideWithPlaybackYield(false, flow { error("Must not observe playback") }) {
            ProviderWorkflowOutcome.Success()
        }
        assertThat(result).isInstanceOf(ProviderWorkflowOutcome.Success::class.java)
    }

    @Test fun `opted out or idle work finishes normally and failures retain their type`() = runTest {
        assertThat(runGuideWithPlaybackYield(true, flowOf(false)) {
            ProviderWorkflowOutcome.Success()
        }).isInstanceOf(ProviderWorkflowOutcome.Success::class.java)
        val failure = IOException("fixture")
        try {
            runGuideWithPlaybackYield(true, flowOf(false)) { throw failure }
            error("Expected the source failure")
        } catch (actual: IOException) { assertThat(actual.message).isEqualTo(failure.message) }
    }

    @Test fun `external worker cancellation remains cancellation rather than a deferred outcome`() = runTest {
        val entered = CompletableDeferred<Unit>()
        var returned = false
        var closed = false
        val job = launch {
            runGuideWithPlaybackYield(true, flowOf(false)) {
                try { entered.complete(Unit); awaitCancellation() }
                finally { closed = true }
            }
            returned = true
        }
        entered.await()
        job.cancelAndJoin()
        assertThat(returned).isFalse()
        assertThat(closed).isTrue()
    }
}
