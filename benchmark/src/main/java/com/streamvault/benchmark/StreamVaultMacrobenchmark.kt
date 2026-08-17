package com.streamvault.benchmark

import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class StreamVaultMacrobenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun coldStartup() = benchmarkRule.measureRepeated(
        packageName = RELEASE_TARGET_PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        startupMode = StartupMode.COLD,
        iterations = BENCHMARK_ITERATIONS
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
        }
    ) {
        devicePressDPadNavigation()
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

private fun devicePressDPadNavigation() {
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
