package com.streamvault.data.remote.stalker

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.StalkerRequestPriority
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

class StalkerRequestCoordinatorTest {
    @Test
    fun backgroundNetworkRequestWaitsWhileInteractivePlaybackIsActive() = runTest {
        val coordinator = StalkerRequestCoordinator()
        val playback = coordinator.acquireNetworkPermit(
            21L,
            StalkerNetworkPriority.INTERACTIVE
        )
        val background = async {
            coordinator.acquireNetworkPermit(21L, StalkerNetworkPriority.BACKGROUND)
        }

        runCurrent()
        assertThat(background.isCompleted).isFalse()

        coordinator.releaseNetworkPermit(playback)
        advanceUntilIdle()
        assertThat(background.await().priority).isEqualTo(StalkerNetworkPriority.BACKGROUND)
    }

    @Test
    fun rateLimitBlocksAllProviderNetworkPermitsWithoutAnotherRequest() = runTest {
        val coordinator = StalkerRequestCoordinator()

        coordinator.recordFailure(
            providerId = 11L,
            error = StalkerApiError.RateLimited(retryAfterMillis = 60_000L)
        )

        val error = assertThrows(StalkerApiError.RateLimited::class.java) {
            kotlinx.coroutines.runBlocking { coordinator.acquireNetworkPermit(11L) }
        }
        assertThat(error.retryAfterMillis).isAtLeast(59_000L)

        // A different provider remains isolated and healthy.
        val otherProviderPermit = coordinator.acquireNetworkPermit(12L)
        assertThat(otherProviderPermit.providerId).isEqualTo(12L)
    }

    @Test
    fun duplicateRequestsShareOneExecution() = runTest {
        val coordinator = StalkerRequestCoordinator()
        val executions = AtomicInteger()
        val release = CompletableDeferred<Unit>()

        val first = async {
            coordinator.execute(
                7L,
                StalkerRequestPriority.OPEN_CATEGORY,
                StalkerRequestDescriptor("MOVIE", "CATEGORY_PAGE", categoryKey = "42", page = 1)
            ) {
                executions.incrementAndGet()
                release.await()
                "done"
            }
        }
        val second = async {
            coordinator.execute(
                7L,
                StalkerRequestPriority.OPEN_CATEGORY,
                StalkerRequestDescriptor("MOVIE", "CATEGORY_PAGE", categoryKey = "42", page = 1)
            ) {
                executions.incrementAndGet()
                "duplicate"
            }
        }
        runCurrent()
        assertThat(executions.get()).isEqualTo(1)

        release.complete(Unit)
        advanceUntilIdle()
        assertThat(first.await()).isEqualTo("done")
        assertThat(second.await()).isEqualTo("done")
    }

    @Test
    fun metadataConcurrencyIsCappedAtTwo() = runTest {
        val coordinator = StalkerRequestCoordinator()
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val release = CompletableDeferred<Unit>()

        val jobs = (1..4).map { page ->
            async {
                coordinator.execute(
                    9L,
                    StalkerRequestPriority.OPEN_CATEGORY,
                    StalkerRequestDescriptor("MOVIE", "CATEGORY_PAGE", categoryKey = "1", page = page)
                ) {
                    val current = active.incrementAndGet()
                    peak.updateAndGet { previous -> maxOf(previous, current) }
                    release.await()
                    active.decrementAndGet()
                }
            }
        }
        runCurrent()
        assertThat(peak.get()).isEqualTo(2)

        release.complete(Unit)
        advanceUntilIdle()
        jobs.forEach { it.await() }
        assertThat(peak.get()).isEqualTo(2)
    }

    @Test
    fun effectiveMetadataLimitDropsToOneAfterProviderStress() = runTest {
        val coordinator = StalkerRequestCoordinator()
        assertThat(coordinator.effectiveConcurrencyLimit(31L)).isEqualTo(2)

        coordinator.recordFailure(
            providerId = 31L,
            error = StalkerApiError.Transport("portal timed out", SocketTimeoutException())
        )

        assertThat(coordinator.effectiveConcurrencyLimit(31L)).isEqualTo(1)
    }

    @Test
    fun categoryAdmissionAtLimitOneFinishesBeforeBulkCanHoldTheSlot() = runTest {
        val coordinator = StalkerRequestCoordinator()
        coordinator.recordFailure(
            providerId = 32L,
            error = StalkerApiError.Transport("portal timed out", SocketTimeoutException())
        )
        val categoryStarted = CompletableDeferred<Unit>()
        val releaseCategory = CompletableDeferred<Unit>()
        val bulkStarted = CompletableDeferred<Unit>()

        val category = async {
            coordinator.execute(
                32L,
                StalkerRequestPriority.OPEN_CATEGORY,
                StalkerRequestDescriptor("LIVE", "GET_CATEGORIES")
            ) {
                categoryStarted.complete(Unit)
                releaseCategory.await()
            }
        }
        categoryStarted.await()
        val bulk = async {
            coordinator.execute(
                32L,
                StalkerRequestPriority.OPEN_CATEGORY,
                StalkerRequestDescriptor("LIVE", "STREAM_ALL")
            ) {
                bulkStarted.complete(Unit)
            }
        }
        runCurrent()
        assertThat(bulkStarted.isCompleted).isFalse()

        releaseCategory.complete(Unit)
        advanceUntilIdle()
        category.await()
        bulk.await()
        assertThat(bulkStarted.isCompleted).isTrue()
    }
}
