package com.streamvault.app

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import com.google.common.truth.Truth.assertThat

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AppStartupCoordinatorTest {

    @Test
    fun processMaintenanceStartsOnceWithoutAnActivity() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val processRuns = AtomicInteger(0)
        val coordinator = AppStartupCoordinator(
            processTasks = listOf(
                AppStartupTask("test-process") { processRuns.incrementAndGet() }
            ),
            tvTasks = emptyList(),
            scope = scope
        )

        coordinator.startProcessMaintenance()
        coordinator.startProcessMaintenance()
        advanceUntilIdle()

        assertThat(processRuns.get()).isEqualTo(1)
        scope.cancel()
    }

    @Test
    fun televisionTasksWaitForTheFirstUiFrameAndRunOnlyOnTelevision() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val tvRuns = AtomicInteger(0)
        val coordinator = AppStartupCoordinator(
            processTasks = emptyList(),
            tvTasks = listOf(
                AppStartupTask("test-tv") { tvRuns.incrementAndGet() }
            ),
            scope = scope
        )

        coordinator.onFirstUiFrameDrawn(isTelevision = false)
        advanceUntilIdle()
        assertThat(tvRuns.get()).isEqualTo(0)

        coordinator.onFirstUiFrameDrawn(isTelevision = true)
        coordinator.onFirstUiFrameDrawn(isTelevision = true)
        advanceUntilIdle()

        assertThat(tvRuns.get()).isEqualTo(1)
        scope.cancel()
    }

    @Test
    fun aFailedTaskDoesNotPreventRemainingTasks() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val remainingRuns = AtomicInteger(0)
        val coordinator = AppStartupCoordinator(
            processTasks = listOf(
                AppStartupTask("test-failure") { error("expected test failure") },
                AppStartupTask("test-remaining") { remainingRuns.incrementAndGet() }
            ),
            tvTasks = emptyList(),
            scope = scope
        )

        coordinator.startProcessMaintenance()
        advanceUntilIdle()

        assertThat(remainingRuns.get()).isEqualTo(1)
        scope.cancel()
    }

    @Test
    fun providerTasksResolveTheirDependencyOnlyWhenTheTaskStarts() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val resolutions = AtomicInteger(0)
        val provider = CountingProvider(resolutions, "expensive")
        val task = AppStartupCoordinator.providerTask(
            traceName = "test-provider",
            provider = provider,
            action = { /* resolution is the behavior under test */ }
        )
        val coordinator = AppStartupCoordinator(
            processTasks = listOf(task),
            tvTasks = emptyList(),
            scope = scope
        )

        assertThat(resolutions.get()).isEqualTo(0)
        coordinator.startProcessMaintenance()
        assertThat(resolutions.get()).isEqualTo(0)
        advanceUntilIdle()
        assertThat(resolutions.get()).isEqualTo(1)
        scope.cancel()
    }

    @Test
    fun asyncTracePairsBeginAndEndWithOneCookieAcrossSuspension() = runTest {
        val events = CopyOnWriteArrayList<TraceEvent>()
        val sink = RecordingTraceSink(events)

        runWithAsyncTrace("test-section", sink) {
            yield()
        }

        assertThat(events).containsExactly(
            TraceEvent.Begin(name = "test-section", cookie = events.first().cookie),
            TraceEvent.End(name = "test-section", cookie = events.first().cookie)
        ).inOrder()
    }

    private class CountingProvider<T>(
        private val resolutions: AtomicInteger,
        private val value: T
    ) : Provider<T> {
        override fun get(): T {
            resolutions.incrementAndGet()
            return value
        }
    }

    private sealed interface TraceEvent {
        val name: String
        val cookie: Int

        data class Begin(override val name: String, override val cookie: Int) : TraceEvent
        data class End(override val name: String, override val cookie: Int) : TraceEvent
    }

    private class RecordingTraceSink(
        private val events: MutableList<TraceEvent>
    ) : StartupTraceSink {
        override fun beginAsyncSection(name: String, cookie: Int) {
            events += TraceEvent.Begin(name, cookie)
        }

        override fun endAsyncSection(name: String, cookie: Int) {
            events += TraceEvent.End(name, cookie)
        }
    }
}
