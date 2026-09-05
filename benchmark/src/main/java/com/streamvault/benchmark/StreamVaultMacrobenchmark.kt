package com.streamvault.benchmark

import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalMetricApi::class)
class StreamVaultMacrobenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun coldStartupNoCompilation() = benchmarkRule.measureRepeated(
        packageName = RELEASE_TARGET_PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = CompilationMode.None(),
        startupMode = StartupMode.COLD,
        iterations = STARTUP_BENCHMARK_ITERATIONS,
        setupBlock = {
            stopTargetApp()
        }
    ) {
        startTargetApp()
    }

    @Test
    fun coldStartupWithBaselineProfile() = benchmarkRule.measureRepeated(
        packageName = RELEASE_TARGET_PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = CompilationMode.Partial(
            baselineProfileMode = BaselineProfileMode.Require
        ),
        startupMode = StartupMode.COLD,
        iterations = STARTUP_BENCHMARK_ITERATIONS,
        setupBlock = {
            stopTargetApp()
        }
    ) {
        startTargetApp()
    }

    @Test
    fun dashboardVerticalScroll() = benchmarkRule.measureRepeated(
        packageName = SEEDED_DEBUG_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        startupMode = StartupMode.WARM,
        iterations = BENCHMARK_ITERATIONS,
        setupBlock = {
            openTopLevelDestination("Home")
        }
    ) {
        swipeContent()
    }

    @Test
    fun liveTvCategoryAndChannelNavigation() = benchmarkRule.measureRepeated(
        packageName = SEEDED_DEBUG_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        startupMode = StartupMode.WARM,
        iterations = BENCHMARK_ITERATIONS,
        setupBlock = {
            openTopLevelDestination("Live TV")
            waitForLiveCategoryAvailability()
        }
    ) {
        devicePressDPadNavigation()
    }

    @Test
    fun liveTvCategoryReentryCategoryBuild() = benchmarkRule.measureRepeated(
        packageName = SEEDED_DEBUG_PACKAGE,
        metrics = listOf(
            // Interaction journeys launch the separately seeded .debug package while the
            // macrobenchmark's tested APK remains the release package. Keep these trace metrics
            // process-agnostic so the lifecycle sections from that fixture are observable.
            TraceSectionMetric(
                sectionName = "StreamVault.CategoryFlow.Build",
                mode = TraceSectionMetric.Mode.Count,
                label = "categoryBuildCount"
            ),
            TraceSectionMetric(
                sectionName = "StreamVault.CategoryFlow.UpstreamStart",
                mode = TraceSectionMetric.Mode.Count,
                label = "categoryUpstreamStartCount"
            ),
            TraceSectionMetric(
                sectionName = "StreamVault.CategoryFlow.UpstreamStop",
                mode = TraceSectionMetric.Mode.Count,
                label = "categoryUpstreamStopCount"
            ),
            FrameTimingMetric()
        ),
        startupMode = StartupMode.WARM,
        iterations = BENCHMARK_ITERATIONS,
        setupBlock = {
            startSeededDebugAppPreservingProcess()
            navigateToTopLevelDestination("Home")
            assertDestination("Home")
        }
    ) {
        // Enter Live TV inside the measured block so the initial category subscription and any
        // later upstream restart are included in the trace window. The setup leaves the process
        // alive, allowing the application-lifetime cache to survive between iterations.
        navigateToTopLevelDestination("Live TV")
        assertDestination("Live TV")
        waitForLiveCategoryAvailability()
        restartSeededDebugTaskPreservingProcess()
        assertDestination("Home")
        navigateToTopLevelDestination("Live TV")
        assertDestination("Live TV")
        waitForLiveCategoryAvailability()
    }

    @Test
    fun epgHorizontalAndVerticalNavigation() = benchmarkRule.measureRepeated(
        packageName = SEEDED_DEBUG_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        startupMode = StartupMode.WARM,
        iterations = BENCHMARK_ITERATIONS,
        setupBlock = {
            openTopLevelDestination("Guide")
        }
    ) {
        devicePressDPadNavigation()
    }

    @Test
    fun playerControlsOpenAndNavigate() = benchmarkRule.measureRepeated(
        packageName = SEEDED_DEBUG_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        startupMode = StartupMode.WARM,
        iterations = BENCHMARK_ITERATIONS,
        setupBlock = {
            navigateLiveAndOpenFocusedChannel()
        }
    ) {
        devicePressDPadNavigation()
    }

    @Test
    fun settingsScrollAndDialogNavigation() = benchmarkRule.measureRepeated(
        packageName = SEEDED_DEBUG_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        startupMode = StartupMode.WARM,
        iterations = BENCHMARK_ITERATIONS,
        setupBlock = {
            openTopLevelDestination("Settings")
        }
    ) {
        swipeContent()
    }
}

internal fun devicePressDPadNavigation() {
    val device = androidx.test.uiautomator.UiDevice.getInstance(
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
    )
    repeat(3) {
        device.pressDPadRight()
        device.pressDPadDown()
        device.pressDPadLeft()
        device.pressDPadUp()
    }
}
