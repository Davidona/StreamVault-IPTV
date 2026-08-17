package com.streamvault.benchmark

import android.app.Instrumentation
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until

internal const val RELEASE_TARGET_PACKAGE = "com.streamvault.app"
internal const val SEEDED_DEBUG_PACKAGE = "com.streamvault.app.debug"
internal const val BENCHMARK_ITERATIONS = 5
internal const val UI_TIMEOUT_MS = 7_000L

private val instrumentation: Instrumentation
    get() = InstrumentationRegistry.getInstrumentation()

private val device: UiDevice
    get() = UiDevice.getInstance(instrumentation)

internal fun MacrobenchmarkScope.startTargetApp() {
    pressHome()
    startActivityAndWait()
}

internal fun MacrobenchmarkScope.openTopLevelDestination(label: String) {
    startTargetApp()
    check(device.wait(Until.hasObject(By.text(label)), UI_TIMEOUT_MS)) {
        "Expected seeded app shell destination '$label'. " +
            "Install a release-like APK with a development provider before running this journey."
    }
    device.findObject(By.text(label)).click()
    device.waitForIdle()
}

internal fun MacrobenchmarkScope.swipeContent(repetitions: Int = 3) {
    val width = device.displayWidth
    val height = device.displayHeight
    repeat(repetitions) {
        device.swipe(
            width / 2,
            (height * 0.78f).toInt(),
            width / 2,
            (height * 0.22f).toInt(),
            24
        )
    }
}

internal fun MacrobenchmarkScope.navigateLiveAndOpenFocusedChannel() {
    openTopLevelDestination("Live TV")
    device.pressDPadDown()
    device.pressDPadCenter()
    device.waitForIdle()
}
