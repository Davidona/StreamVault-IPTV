package com.streamvault.data.remote.stalker

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.StalkerRequestPriority
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asContextElement
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
                    coordinator.acquireNetworkPermit(79L, StalkerNetworkPriority.INDEX).also { coordinator.releaseNetworkPermit(it) }
                    // The page-base probe's second HTTP call waits inside the same logical page.
                    coordinator.acquireNetworkPermit(79L, StalkerNetworkPriority.INDEX).also { coordinator.releaseNetworkPermit(it) }
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
    fun movieAndSeriesShareThreeWireSlotsAndGrowToEightWithoutTimedSpacing() = runTest {
        val coordinator = StalkerRequestCoordinator(null, true, { testScheduler.currentTime }, 8)
        val held = (1..3).map { coordinator.acquireNetworkPermit(71L, StalkerNetworkPriority.INDEX) }
        val fourth = async { coordinator.acquireNetworkPermit(71L, StalkerNetworkPriority.INDEX) }
        runCurrent()
        assertThat(fourth.isCompleted).isFalse()
        coordinator.releaseNetworkPermit(held.first())
        testScheduler.advanceTimeBy(100)
        runCurrent()
        coordinator.releaseNetworkPermit(fourth.await())
        held.drop(1).forEach { coordinator.releaseNetworkPermit(it) }
        repeat(100) {
            val permit = coordinator.acquireNetworkPermit(71L, StalkerNetworkPriority.INDEX)
            coordinator.recordIndexResponse(permit)
            coordinator.releaseNetworkPermit(permit)
        }
        assertThat(coordinator.effectiveIndexConcurrencyLimit(71L)).isEqualTo(8)
        val eight = (1..8).map { coordinator.acquireNetworkPermit(71L, StalkerNetworkPriority.INDEX) }
        assertThat(eight.map { it.admittedAt }.distinct()).hasSize(1)
        val ninth = async { coordinator.acquireNetworkPermit(71L, StalkerNetworkPriority.INDEX) }
        runCurrent()
        assertThat(ninth.isCompleted).isFalse()
        ninth.cancel(); ninth.join()
        eight.forEach { coordinator.releaseNetworkPermit(it) }
    }

    @Test
    fun stressHalvesOnlyItsProvidersWindowAndSuccessCanRelearn() = runTest {
        val coordinator = StalkerRequestCoordinator(null, true, { testScheduler.currentTime }, 8)
        val permit = coordinator.acquireNetworkPermit(72L, StalkerNetworkPriority.INDEX)
        coordinator.recordIndexResponse(permit, StalkerApiError.Server("busy", httpStatus = 500))
        coordinator.releaseNetworkPermit(permit)
        assertThat(coordinator.effectiveIndexConcurrencyLimit(72L)).isEqualTo(1)
        assertThat(coordinator.effectiveIndexConcurrencyLimit(73L)).isEqualTo(3)
        repeat(20) {
            val next = coordinator.acquireNetworkPermit(72L, StalkerNetworkPriority.INDEX)
            coordinator.recordIndexResponse(next)
            coordinator.releaseNetworkPermit(next)
        }
        assertThat(coordinator.effectiveIndexConcurrencyLimit(72L)).isEqualTo(2)
    }

    @Test
    fun playbackPausesIndexRequestsWhileInteractiveMetadataRemainsAvailable() = runTest {
        val coordinator = StalkerRequestCoordinator(null, true) { testScheduler.currentTime }
        StalkerTrafficCoordinator.notePlaybackStarted(74L)
        try {
            val next = async { coordinator.acquireNetworkPermit(74L, StalkerNetworkPriority.INDEX) }
            runCurrent()
            val interactive = coordinator.acquireNetworkPermit(74L, StalkerNetworkPriority.INTERACTIVE)
            assertThat(testScheduler.currentTime).isEqualTo(0L)
            testScheduler.advanceTimeBy(21_000L)
            runCurrent()
            assertThat(next.isCompleted).isFalse()
            coordinator.releaseNetworkPermit(interactive)
            StalkerTrafficCoordinator.forgetProvider(74L)
            advanceUntilIdle()
            val second = next.await()
            assertThat(second.admittedAt).isAtLeast(21_000L)
            coordinator.releaseNetworkPermit(second)
        } finally { StalkerTrafficCoordinator.forgetProvider(74L) }
    }

    @Test
    fun foregroundStressShrinksIndexAndBlocksNewPagesUntilForegroundCompletes() = runTest {
        val coordinator = StalkerRequestCoordinator(null, true, { testScheduler.currentTime }, 8)
        val foreground = coordinator.acquireNetworkPermit(77L, StalkerNetworkPriority.FOREGROUND)
        val background = async { coordinator.acquireNetworkPermit(77L, StalkerNetworkPriority.INDEX) }
        runCurrent()
        assertThat(background.isCompleted).isFalse()
        coordinator.recordIndexResponse(foreground, java.net.SocketTimeoutException())
        coordinator.releaseNetworkPermit(foreground)
        advanceUntilIdle()
        coordinator.releaseNetworkPermit(background.await())
        assertThat(coordinator.effectiveIndexConcurrencyLimit(77L)).isEqualTo(1)
    }

    @Test
    fun cancelledInteractiveWaiterDoesNotStarveIndexOrSpendForegroundCredits() = runTest {
        val coordinator = StalkerRequestCoordinator(null, true) { testScheduler.currentTime }
        repeat(8) { coordinator.acquireNetworkPermit(78L, StalkerNetworkPriority.FOREGROUND).also {
            coordinator.releaseNetworkPermit(it)
        } }
        repeat(2) { coordinator.acquireNetworkPermit(78L, StalkerNetworkPriority.INTERACTIVE).also {
            coordinator.releaseNetworkPermit(it)
        } }
        val interactive = async { coordinator.acquireNetworkPermit(78L, StalkerNetworkPriority.INTERACTIVE) }
        runCurrent()
        interactive.cancel(); interactive.join()
        val index = coordinator.acquireNetworkPermit(78L, StalkerNetworkPriority.INDEX)
        assertThat(index.admittedAt).isEqualTo(0L)
        coordinator.releaseNetworkPermit(index)
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
        val coordinator = StalkerRequestCoordinator(null, true, { testScheduler.currentTime }, 8)
        val first = coordinator.acquireNetworkPermit(76L, StalkerNetworkPriority.INDEX)
        val error = StalkerApiError.RateLimited(retryAfterMillis = 1_200_000L)
        coordinator.recordIndexResponse(first, error)
        coordinator.recordNetworkFailure(first, error)
        coordinator.releaseNetworkPermit(first)
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
        coordinator.releaseNetworkPermit(probe)
        assertThat(coordinator.effectiveIndexConcurrencyLimit(76L)).isEqualTo(1)
        val next = coordinator.acquireNetworkPermit(76L, StalkerNetworkPriority.INDEX)
        assertThat(next.admittedAt).isEqualTo(probe.admittedAt)
        coordinator.releaseNetworkPermit(next)
    }

    @Test
    fun fullIndexWindowLeavesMetadataSlotForInteractiveWork() = runTest {
        val coordinator = StalkerRequestCoordinator(null, true, { testScheduler.currentTime }, 8)
        val finish = CompletableDeferred<Unit>()
        val pages = (1..3).map { page -> async {
            coordinator.execute(81L, StalkerRequestPriority.BACKGROUND_INDEX,
                StalkerRequestDescriptor("MOVIE", "PAGE", page = page)) { finish.await() }
        } }
        runCurrent()
        assertThat(coordinator.snapshot(81L).active).isEqualTo(3)
        val interactive = async {
            coordinator.execute(81L, StalkerRequestPriority.OPEN_CATEGORY,
                StalkerRequestDescriptor("SERIES", "CATEGORY")) { "ready" }
        }
        runCurrent()
        assertThat(interactive.await()).isEqualTo("ready")
        finish.complete(Unit)
        pages.awaitAll()
    }

    @Test
    fun serverRetryAfterPausesEveryLaneAndFailedProbeKeepsCircuitClosed() = runTest {
        val coordinator = StalkerRequestCoordinator(null, true, { testScheduler.currentTime }, 8)
        val first = coordinator.acquireNetworkPermit(82L, StalkerNetworkPriority.INDEX)
        val error = StalkerApiError.Server("busy", httpStatus = 503, retryAfterMillis = 600_000L)
        coordinator.recordIndexResponse(first, error)
        coordinator.recordNetworkFailure(first, error)
        coordinator.releaseNetworkPermit(first)
        val rejected = runCatching {
            coordinator.acquireNetworkPermit(82L, StalkerNetworkPriority.INTERACTIVE)
        }.exceptionOrNull() as StalkerApiError.RateLimited
        assertThat(rejected.retryAfterMillis).isEqualTo(600_000L)
        testScheduler.advanceTimeBy(600_000L)
        val probe = coordinator.acquireNetworkPermit(82L, StalkerNetworkPriority.INDEX)
        coordinator.recordNetworkFailure(probe, StalkerApiError.Server("busy", httpStatus = 503))
        coordinator.releaseNetworkPermit(probe)
        val stillClosed = runCatching {
            coordinator.acquireNetworkPermit(82L, StalkerNetworkPriority.FOREGROUND)
        }.exceptionOrNull() as StalkerApiError.RateLimited
        assertThat(stillClosed.retryAfterMillis).isEqualTo(300_000L)
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
    @Test fun engineOwnedAutomaticIndexWaitsForReleaseWhileInteractiveRequestsContinue() = runTest {
        val owner=Any()
        val coordinator=StalkerRequestCoordinator(null,true) { testScheduler.currentTime }
        try {
            com.streamvault.domain.sync.AutomaticSyncActivity.started(owner,707)
            kotlinx.coroutines.withContext(
                com.streamvault.data.sync.AutomaticSyncExecution.automatic.asContextElement(true) +
                    com.streamvault.data.sync.AutomaticSyncExecution.policy.asContextElement(com.streamvault.domain.sync.AutomaticSyncSettings())
            ) {
                val pending=async { coordinator.acquireNetworkPermit(707,StalkerNetworkPriority.INDEX) }
                runCurrent()
                assertThat(pending.isCompleted).isFalse()
                val interactive=coordinator.acquireNetworkPermit(707,StalkerNetworkPriority.INTERACTIVE)
                coordinator.releaseNetworkPermit(interactive)
                com.streamvault.domain.sync.AutomaticSyncActivity.stopped(owner)
                advanceUntilIdle()
                coordinator.releaseNetworkPermit(pending.await())
            }
        } finally { com.streamvault.domain.sync.AutomaticSyncActivity.stopped(owner) }
    }
    @Test fun engineOwnedPlaybackDoesNotBlockManualOrOptOutIndexNetworkAdmission() = runTest {
        val owner=Any()
        val coordinator=StalkerRequestCoordinator(null,true) { testScheduler.currentTime }
        try {
            com.streamvault.domain.sync.AutomaticSyncActivity.started(owner,708)
            for (automatic in listOf(false,true)) kotlinx.coroutines.withContext(
                com.streamvault.data.sync.AutomaticSyncExecution.automatic.asContextElement(automatic) +
                    com.streamvault.data.sync.AutomaticSyncExecution.policy.asContextElement(com.streamvault.domain.sync.AutomaticSyncSettings(deferWhileBusy=!automatic))
            ) {
                val pending=async { coordinator.acquireNetworkPermit(708,StalkerNetworkPriority.INDEX) }
                runCurrent(); assertThat(pending.isCompleted).isTrue()
                coordinator.releaseNetworkPermit(pending.await())
            }
        } finally { com.streamvault.domain.sync.AutomaticSyncActivity.stopped(owner) }
    }
    @Test fun engineOwnedOtherProviderDoesNotBlockAutomaticIndexNetworkAdmission() = runTest {
        val owner=Any()
        val coordinator=StalkerRequestCoordinator(null,true) { testScheduler.currentTime }
        try {
            com.streamvault.domain.sync.AutomaticSyncActivity.started(owner,709)
            kotlinx.coroutines.withContext(com.streamvault.data.sync.AutomaticSyncExecution.automatic.asContextElement(true)) {
                val pending=async { coordinator.acquireNetworkPermit(710,StalkerNetworkPriority.INDEX) }
                runCurrent(); assertThat(pending.isCompleted).isTrue()
                coordinator.releaseNetworkPermit(pending.await())
            }
        } finally { com.streamvault.domain.sync.AutomaticSyncActivity.stopped(owner) }
    }
}
