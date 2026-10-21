package com.streamvault.data.sync

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.ProviderType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Test

class BackgroundEpgExecutionTest {
    @Test
    fun `Stalker guide runs while catalog lane is occupied and guide requests still serialize`() = runTest {
        val locks = ProviderWorkLockRegistry()
        val catalogEntered = CompletableDeferred<Unit>()
        val guideEntered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val catalog = async {
            locks.withProviderLock(1L) { catalogEntered.complete(Unit); release.await() }
        }
        catalogEntered.await()
        val guide = async {
            executeBackgroundEpg(ProviderType.STALKER_PORTAL, workflow = { error("Catalog workflow must not own guide") }) {
                locks.withStalkerGuideLock(1L) {
                    guideEntered.complete(Unit)
                    release.await()
                    ProviderWorkflowOutcome.Success()
                }
            }
        }
        guideEntered.await()
        val secondGuideEntered = CompletableDeferred<Unit>()
        val secondGuide = async { locks.withStalkerGuideLock(1L) { secondGuideEntered.complete(Unit) } }
        testScheduler.runCurrent()
        assertThat(secondGuideEntered.isCompleted).isFalse()
        assertThat(locks.runWhenNoWorkActive { true }).isFalse()
        release.complete(Unit)
        catalog.await()
        assertThat(guide.await()).isEqualTo(ProviderWorkflowDisposition.SUCCEEDED)
        secondGuide.await()
        assertThat(locks.isAnyWorkActiveOrWaiting()).isFalse()
    }

    @Test
    fun `Stalker guide can take longer than sixty seconds and maps failures independently`() = runTest {
        val results = listOf(
            ProviderWorkflowOutcome.Success() to ProviderWorkflowDisposition.SUCCEEDED,
            ProviderWorkflowOutcome.Failure("NETWORK", null, retryable = true) to ProviderWorkflowDisposition.RETRY,
            ProviderWorkflowOutcome.Failure("INVALID", null, retryable = false) to ProviderWorkflowDisposition.FAILED
        )
        for ((outcome, expected) in results) {
            assertThat(executeBackgroundEpg(ProviderType.STALKER_PORTAL, { error("Unexpected catalog lease") }) {
                delay(90_000)
                outcome
            }).isEqualTo(expected)
        }
    }

    @Test
    fun `other providers keep their workflow admission`() = runTest {
        var ran = false
        val result = executeBackgroundEpg(ProviderType.XTREAM_CODES, workflow = { ProviderWorkflowDisposition.BUSY }) {
            ran = true
            ProviderWorkflowOutcome.Success()
        }
        assertThat(result).isEqualTo(ProviderWorkflowDisposition.BUSY)
        assertThat(ran).isFalse()
    }
}
