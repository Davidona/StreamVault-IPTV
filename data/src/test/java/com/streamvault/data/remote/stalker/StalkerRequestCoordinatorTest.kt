package com.streamvault.data.remote.stalker

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.StalkerRequestPriority
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class StalkerRequestCoordinatorTest {
    @Test
    fun configuredIndexWindowAdmitsTwoPagesButNoThirdPageUntilOneFinishes() = runTest {
        val coordinator = StalkerRequestCoordinator(
            portalStateStore = null,
            adaptiveIndexingEnabled = true,
            nowMillis = { testScheduler.currentTime },
            configuredIndexConcurrency = 2
        )
        val releasePages = CompletableDeferred<Unit>()
        val startedPages = AtomicInteger()
        val requests = (1..3).map { page ->
            async {
                coordinator.execute(
                    providerId = 91L,
                    priority = StalkerRequestPriority.BACKGROUND_INDEX,
                    descriptor = StalkerRequestDescriptor("MOVIE", "INDEX_PAGE", page = page)
                ) {
                    startedPages.incrementAndGet()
                    releasePages.await()
                }
            }
        }

        runCurrent()
        assertThat(startedPages.get()).isEqualTo(2)
        assertThat(requests[2].isCompleted).isFalse()

        releasePages.complete(Unit)
        advanceUntilIdle()
        requests.awaitAll()
        assertThat(startedPages.get()).isEqualTo(3)
    }

    @Test
    fun playbackPacingYieldsTheOnlyMetadataSlotAndCancellationCleansBothAdmissions() = runTest {
        val coordinator = StalkerRequestCoordinator(null, true) { testScheduler.currentTime }
        coordinator.recordFailure(79L, java.net.SocketTimeoutException())
        StalkerTrafficCoordinator.notePlaybackStarted(79L)
        try {
            val background = async {
                coordinator.execute(79L, StalkerRequestPriority.BACKGROUND_INDEX,
                    StalkerRequestDescriptor("MOVIE", "PAGE", page = 1)) {
                    coordinator.acquireNetworkPermit(79L, StalkerNetworkPriority.INDEX)
                    // The page-base probe's second HTTP call waits inside the same logical page.
                    coordinator.acquireNetworkPermit(79L, StalkerNetworkPriority.INDEX)
                }
            }
            runCurrent()
            assertThat(background.isCompleted).isFalse()
            val interactive = async {
                coordinator.execute(79L, StalkerRequestPriority.OPEN_CATEGORY,
                    StalkerRequestDescriptor("MOVIE", "CATEGORY", categoryKey = "42")) { "ready" }
            }
            runCurrent()
            assertThat(interactive.isCompleted).isTrue()
            assertThat(interactive.await()).isEqualTo("ready")
            background.cancel()
            background.join()
            assertThat(coordinator.snapshot(79L).active).isEqualTo(0)
            assertThat(coordinator.snapshot(79L).queued).isEqualTo(0)
            // A canceled pacing wait must also release the one-background-operation guard.
            val next = async {
                coordinator.execute(79L, StalkerRequestPriority.BACKGROUND_INDEX,
                    StalkerRequestDescriptor("MOVIE", "PAGE", page = 2)) { "resumed" }
            }
            runCurrent()
            assertThat(next.isCompleted).isTrue()
            assertThat(next.await()).isEqualTo("resumed")
        } finally { StalkerTrafficCoordinator.forgetProvider(79L) }
    }

    @Test
    fun healthyIndexWalkRampsToTwoPerSecondWithoutTokenBucketBottleneck() = runTest {
        val coordinator = StalkerRequestCoordinator(null, true) { testScheduler.currentTime }
        val times = (1..715).map {
            val permit = coordinator.acquireNetworkPermit(71L, StalkerNetworkPriority.INDEX)
            coordinator.recordIndexResponse(permit)
            testScheduler.currentTime
        }
        assertThat(times[1] - times[0]).isEqualTo(5_000L)
        assertThat(times.zipWithNext { a, b -> b - a }.minOrNull()).isEqualTo(500L)
        assertThat(times.takeLast(100).zipWithNext { a, b -> b - a }.toSet()).containsExactly(500L)
        // Virtual admission benchmark, excluding transport/DB work: 715 pages / 10k rows.
        assertThat(times.last()).isLessThan(600_000L)
        println("Stalker virtual admission benchmark: pages=715 elapsedMillis=${times.last()} tailIntervalMillis=500")
    }

    @Test
    fun stressSlowsOnlyItsProviderAndCannotRampAbovePreStressRate() = runTest {
        val coordinator = StalkerRequestCoordinator(null, true) { testScheduler.currentTime }
        val first = coordinator.acquireNetworkPermit(72L, StalkerNetworkPriority.INDEX)
        coordinator.recordIndexResponse(first, StalkerApiError.Server("busy", httpStatus = 503))
        val second = coordinator.acquireNetworkPermit(72L, StalkerNetworkPriority.INDEX)
        assertThat(second.admittedAt - first.admittedAt).isEqualTo(10_000L)
        repeat(60) {
            coordinator.recordIndexResponse(coordinator.acquireNetworkPermit(72L, StalkerNetworkPriority.INDEX))
        }
        val before = testScheduler.currentTime
        coordinator.acquireNetworkPermit(72L, StalkerNetworkPriority.INDEX)
        assertThat(testScheduler.currentTime - before).isEqualTo(10_000L)
        val other = coordinator.acquireNetworkPermit(73L, StalkerNetworkPriority.INDEX)
        val otherNext = coordinator.acquireNetworkPermit(73L, StalkerNetworkPriority.INDEX)
        assertThat(otherNext.admittedAt - other.admittedAt).isEqualTo(5_000L)
    }

    @Test
    fun playbackTrickleUsesTwentySecondsForEveryWireRequestAndInteractiveStillWins() = runTest {
        val coordinator = StalkerRequestCoordinator(null, true) { testScheduler.currentTime }
        StalkerTrafficCoordinator.notePlaybackStarted(74L)
        try {
            val first = coordinator.acquireNetworkPermit(74L, StalkerNetworkPriority.INDEX)
            val next = async { coordinator.acquireNetworkPermit(74L, StalkerNetworkPriority.INDEX) }
            runCurrent()
            val interactive = coordinator.acquireNetworkPermit(74L, StalkerNetworkPriority.INTERACTIVE)
            assertThat(testScheduler.currentTime).isEqualTo(0L)
            testScheduler.advanceTimeBy(21_000L)
            runCurrent()
            assertThat(next.isCompleted).isFalse()
            coordinator.releaseNetworkPermit(interactive)
            advanceUntilIdle()
            assertThat(next.await().admittedAt - first.admittedAt).isAtLeast(20_000L)
        } finally { StalkerTrafficCoordinator.forgetProvider(74L) }
    }

    @Test
    fun foregroundTrafficYieldsNoIndexCreditAndItsStressSlowsTheIndex() = runTest {
        val coordinator = StalkerRequestCoordinator(null, true) { testScheduler.currentTime }
        val foreground = coordinator.acquireNetworkPermit(77L, StalkerNetworkPriority.FOREGROUND)
        val background = async { coordinator.acquireNetworkPermit(77L, StalkerNetworkPriority.INDEX) }
        runCurrent()
        assertThat(background.isCompleted).isFalse()
        coordinator.recordIndexResponse(foreground, java.net.SocketTimeoutException())
        coordinator.releaseNetworkPermit(foreground)
        advanceUntilIdle()
        val first = background.await()
        val second = coordinator.acquireNetworkPermit(77L, StalkerNetworkPriority.INDEX)
        assertThat(second.admittedAt - first.admittedAt).isEqualTo(10_000L)
    }

    @Test
    fun cancelledInteractiveWaiterDoesNotStarveIndex() = runTest {
        val coordinator = StalkerRequestCoordinator(null, true) { testScheduler.currentTime }
        // Drain foreground credits, then exceed interactive borrowing so its next permit queues.
        repeat(8) { coordinator.acquireNetworkPermit(78L, StalkerNetworkPriority.FOREGROUND).also {
            coordinator.releaseNetworkPermit(it)
        } }
        repeat(2) { coordinator.acquireNetworkPermit(78L, StalkerNetworkPriority.INTERACTIVE).also {
            coordinator.releaseNetworkPermit(it)
        } }
        val interactive = async { coordinator.acquireNetworkPermit(78L, StalkerNetworkPriority.INTERACTIVE) }
        runCurrent()
        interactive.cancel()
        interactive.join()
        val index = coordinator.acquireNetworkPermit(78L, StalkerNetworkPriority.INDEX)
        assertThat(index.admittedAt).isEqualTo(3_000L)
    }

    @Test
    fun disabledPolicyPreservesFiveSecondIndexSpacing() = runTest {
        val coordinator = StalkerRequestCoordinator(null, false) { testScheduler.currentTime }
        val first = coordinator.acquireNetworkPermit(75L, StalkerNetworkPriority.INDEX)
        coordinator.recordIndexResponse(first)
        val next = coordinator.acquireNetworkPermit(75L, StalkerNetworkPriority.INDEX)
        assertThat(next.admittedAt - first.admittedAt).isEqualTo(5_000L)
    }

    @Test
    fun retryAfterBlocksAllLanesAndAllowsExactlyOneHalfOpenProbe() = runTest {
        val coordinator = StalkerRequestCoordinator(null, true) { testScheduler.currentTime }
        val first = coordinator.acquireNetworkPermit(76L, StalkerNetworkPriority.INDEX)
        val error = StalkerApiError.RateLimited(retryAfterMillis = 1_200_000L)
        coordinator.recordIndexResponse(first, error)
        coordinator.recordNetworkFailure(first, error)
        repeat(3) {
            try {
                coordinator.acquireNetworkPermit(76L, StalkerNetworkPriority.INTERACTIVE)
                error("Cooldown must reject locally")
            } catch (expected: StalkerApiError.RateLimited) {
                assertThat(expected.retryAfterMillis).isEqualTo(1_200_000L)
            }
        }
        testScheduler.advanceTimeBy(1_200_000L)
        val probe = coordinator.acquireNetworkPermit(76L, StalkerNetworkPriority.INDEX)
        assertThat(probe.halfOpenProbe).isTrue()
        try {
            coordinator.acquireNetworkPermit(76L, StalkerNetworkPriority.FOREGROUND)
            error("Only one probe may run")
        } catch (expected: StalkerApiError.RateLimited) {
            assertThat(expected.retryAfterMillis).isEqualTo(1_000L)
        }
        coordinator.recordNetworkSuccess(probe)
        coordinator.recordIndexResponse(probe)
        val next = coordinator.acquireNetworkPermit(76L, StalkerNetworkPriority.INDEX)
        assertThat(next.admittedAt - probe.admittedAt).isEqualTo(10_000L)
    }

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
