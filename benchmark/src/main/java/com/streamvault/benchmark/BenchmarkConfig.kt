package com.streamvault.benchmark

import android.os.SystemClock
import android.app.Instrumentation
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.util.regex.Pattern

internal const val RELEASE_TARGET_PACKAGE = "com.streamvault.app"
internal const val SEEDED_DEBUG_PACKAGE = "com.streamvault.app.debug"
private const val SEEDED_DEBUG_ACTIVITY = "$SEEDED_DEBUG_PACKAGE/com.streamvault.app.MainActivity"
internal const val BENCHMARK_ITERATIONS = 5
internal const val STARTUP_BENCHMARK_ITERATIONS = 10
internal const val UI_TIMEOUT_MS = 20_000L
internal const val BASELINE_PROFILE_SEED_TIMEOUT_MS = 120_000L
private const val LIVE_CATEGORY_TIMEOUT_MS = 20_000L
private const val DPAD_SETTLE_MS = 40L
private val SEEDED_TOP_LEVEL_DESTINATIONS = listOf(
    "Home",
    "Live TV",
    "Movies",
    "Series",
    "Downloads",
    "Guide",
    "Search",
    "Plugins",
    "Settings"
)
private val DESTINATION_ROUTE_TOKENS = mapOf(
    "Home" to "home",
    "Live TV" to "live_tv",
    "Movies" to "movies",
    "Series" to "series",
    "Downloads" to "downloads",
    "Guide" to "epg",
    "Search" to "search",
    "Plugins" to "plugins",
    "Settings" to "settings"
)

private val instrumentation: Instrumentation
    get() = InstrumentationRegistry.getInstrumentation()

private val device: UiDevice
    get() = UiDevice.getInstance(instrumentation)

internal fun MacrobenchmarkScope.startTargetApp() {
    pressHome()
    startActivityAndWait()
}

/** Starts the separately seeded debug fixture used by the interaction journeys. */
internal fun MacrobenchmarkScope.startSeededDebugApp() {
    pressHome()
    device.executeShellCommand("am force-stop $SEEDED_DEBUG_PACKAGE")
    device.executeShellCommand("am start -W -n $SEEDED_DEBUG_ACTIVITY")
    device.waitForIdle()
}

internal fun MacrobenchmarkScope.openTopLevelDestination(
    label: String,
    destinationTimeoutMs: Long = UI_TIMEOUT_MS,
    categoryTimeoutMs: Long = LIVE_CATEGORY_TIMEOUT_MS
) {
    startSeededDebugApp()
    navigateToTopLevelDestination(label)
    assertDestination(label, destinationTimeoutMs)
    if (label == "Live TV") {
        waitForLiveCategoryAvailability(categoryTimeoutMs)
    }
}

internal fun MacrobenchmarkScope.assertDestination(
    label: String,
    timeoutMs: Long = UI_TIMEOUT_MS
) {
    val routeToken = DESTINATION_ROUTE_TOKENS[label]
        ?: error("Unknown seeded top-level destination '$label'.")
    check(device.wait(Until.hasObject(By.desc("streamvault.destination:$routeToken")), timeoutMs)) {
        "Expected the app route for destination '$label'. " +
            "Install a release-like APK with a development provider before running this journey."
    }
}

/** Verifies the release-like target once before baseline-profile collection begins. */
internal fun MacrobenchmarkScope.verifySeededReleaseTarget() {
    startTargetApp()
    assertDestination("Home")
    navigateToTopLevelDestination("Live TV")
    assertDestination("Live TV")
    waitForLiveCategoryAvailability()
}

internal fun MacrobenchmarkScope.assertPlayerControlsAvailable() {
    // Media3 may still be showing the controller after the fullscreen transition. Only send an
    // OK press when the overlay is absent; pressing OK while it is already visible would hide it
    // again. PLAYBACK is the stable label for the app's visible player-control surface; the
    // transport label itself varies between Play and Pause.
    if (!device.hasObject(By.text("PLAYBACK")) && !device.hasObject(By.text("MUTE"))) {
        device.pressDPadCenter()
    }
    check(device.wait(Until.hasObject(By.text("PLAYBACK")), UI_TIMEOUT_MS)) {
        "Expected visible player controls after opening a seeded live channel."
    }
}

internal fun MacrobenchmarkScope.navigateToTopLevelDestination(label: String) {
    val destinationIndex = SEEDED_TOP_LEVEL_DESTINATIONS.indexOf(label)
    check(destinationIndex >= 0) {
        "Unknown seeded top-level destination '$label'."
    }

    // Home is also the app's ACTION_VIEW landing destination. Sending that existing activity an
    // external navigation intent is deterministic even when a previous content surface retained
    // TV focus; it preserves the process and cache while changing only the route.
    if (label == "Home") {
        device.executeShellCommand("am start -W -a android.intent.action.VIEW -n $SEEDED_DEBUG_ACTIVITY")
        device.waitForIdle()
        return
    }

    // TopNavigationBar is a TV focus surface. Reset focus to the first item before moving to
    // the requested destination; this also works when the previous iteration left focus in the
    // category/content list or the app retained a different top-level route.
    repeat(40) { pressDPad { device.pressDPadUp() } }
    repeat(10) { pressDPad { device.pressDPadLeft() } }
    repeat(destinationIndex) { pressDPad { device.pressDPadRight() } }
    pressDPad { device.pressDPadCenter() }
}

private inline fun pressDPad(action: () -> Unit) {
    action()
    SystemClock.sleep(DPAD_SETTLE_MS)
}

internal fun MacrobenchmarkScope.waitForLiveCategoryAvailability(
    timeoutMs: Long = LIVE_CATEGORY_TIMEOUT_MS
) {
    check(device.wait(Until.hasObject(By.text("All Channels")), timeoutMs)) {
        "Expected the Live TV category list to expose the seeded All Channels category."
    }
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

internal fun MacrobenchmarkScope.navigateLiveAndOpenFocusedChannel(
    categoryTimeoutMs: Long = LIVE_CATEGORY_TIMEOUT_MS
) {
    openTopLevelDestination("Live TV", categoryTimeoutMs = categoryTimeoutMs)
    waitForLiveCategoryAvailability(categoryTimeoutMs)

    // The seeded provider exposes Favorites and Recent before All Channels. The Live TV route
    // resets focus to the top navigation, so two D-pad-down steps reach the named seeded row.
    // Keep this as TV input rather than clicking a coordinate so the profile follows the real
    // remote-control path.
    check(device.wait(Until.hasObject(By.text("All Channels")), categoryTimeoutMs)) {
        "Expected the seeded All Channels category to be present before opening a channel."
    }
    repeat(2) {
        device.pressDPadDown()
        SystemClock.sleep(DPAD_SETTLE_MS)
    }
    device.pressDPadCenter()
    check(device.wait(Until.hasObject(By.textContains("channels in view")), categoryTimeoutMs)) {
        "Expected All Channels selection to expose its channel pane."
    }
    check(device.wait(Until.hasObject(By.text(Pattern.compile("\\d{2}\\s+.+"))), categoryTimeoutMs)) {
        "Expected All Channels to expose at least one navigable channel row."
    }
    device.waitForIdle()
    SystemClock.sleep(DPAD_SETTLE_MS)
    device.pressDPadRight()
    SystemClock.sleep(DPAD_SETTLE_MS)
    device.pressDPadCenter()
    check(device.wait(Until.hasObject(By.text("Press OK again to open this channel")), categoryTimeoutMs)) {
        "Expected the selected live channel preview before opening fullscreen playback."
    }
    device.pressDPadCenter()
    device.waitForIdle()
}
