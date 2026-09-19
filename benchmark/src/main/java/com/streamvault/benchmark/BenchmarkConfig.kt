package com.streamvault.benchmark

import android.os.SystemClock
import android.app.Instrumentation
import android.view.KeyEvent
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.util.regex.Pattern

internal const val RELEASE_TARGET_PACKAGE = "com.streamvault.app"
internal const val SEEDED_DEBUG_PACKAGE = "com.streamvault.app.debug"
private const val RELEASE_TARGET_ACTIVITY = "$RELEASE_TARGET_PACKAGE/com.streamvault.app.MainActivity"
private const val SEEDED_DEBUG_ACTIVITY = "$SEEDED_DEBUG_PACKAGE/com.streamvault.app.MainActivity"
private const val CLEAR_TASK_NEW_TASK_FLAGS = "0x10008000"
internal const val BENCHMARK_ITERATIONS = 5
internal const val STARTUP_BENCHMARK_ITERATIONS = 10
internal const val UI_TIMEOUT_MS = 20_000L
internal const val BASELINE_PROFILE_SEED_TIMEOUT_MS = 120_000L
private const val LIVE_CATEGORY_TIMEOUT_MS = 20_000L
private const val DPAD_SETTLE_MS = 40L
private const val PLAYER_CONTROL_PROBE_INTERVAL_MS = 1_000L
private const val PLAYER_CONTROL_RETRY_SETTLE_MS = 250L
private val PLAYER_CONTROL_LABEL_PATTERN = Pattern.compile(
    "(?i)^(playback|mute|unmute|play|pause|dvr|live)$"
)
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

/** Ensures independent cold-start iterations begin with the release target stopped. */
internal fun MacrobenchmarkScope.stopTargetApp() {
    device.executeShellCommand("am force-stop $RELEASE_TARGET_PACKAGE")
}

internal fun MacrobenchmarkScope.startTargetApp() {
    pressHome()
    startActivityAndWait()
    device.waitForIdle()
}

/** Starts the separately seeded debug fixture used by the interaction journeys. */
internal fun MacrobenchmarkScope.startSeededDebugApp() {
    pressHome()
    device.executeShellCommand("am force-stop $SEEDED_DEBUG_PACKAGE")
    device.executeShellCommand("am start -W -n $SEEDED_DEBUG_ACTIVITY")
    device.waitForIdle()
}

/**
 * Brings the seeded debug fixture to the foreground without killing its process.
 *
 * Re-entry measurements use this setup so the application-lifetime category cache can survive
 * between iterations while the measured block still owns the first Live TV subscription.
 */
internal fun MacrobenchmarkScope.startSeededDebugAppPreservingProcess() {
    pressHome()
    device.executeShellCommand(
        "am start -W -f $CLEAR_TASK_NEW_TASK_FLAGS -n $SEEDED_DEBUG_ACTIVITY"
    )
    device.waitForIdle()
}

/** Recreates the seeded Activity task without stopping the application process or its cache. */
internal fun MacrobenchmarkScope.restartSeededDebugTaskPreservingProcess() {
    device.executeShellCommand(
        "am start -W -a android.intent.action.VIEW -f $CLEAR_TASK_NEW_TASK_FLAGS -n $SEEDED_DEBUG_ACTIVITY"
    )
    device.waitForIdle()
}

internal fun MacrobenchmarkScope.openTopLevelDestination(
    label: String,
    destinationTimeoutMs: Long = UI_TIMEOUT_MS,
    categoryTimeoutMs: Long = LIVE_CATEGORY_TIMEOUT_MS,
    targetPackage: String = SEEDED_DEBUG_PACKAGE
) {
    if (targetPackage == RELEASE_TARGET_PACKAGE) {
        startTargetApp()
    } else {
        check(targetPackage == SEEDED_DEBUG_PACKAGE) {
            "Unsupported benchmark target package '$targetPackage'."
        }
        startSeededDebugApp()
    }
    navigateToTopLevelDestination(label, targetPackage)
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
    navigateToTopLevelDestination("Live TV", targetPackage = RELEASE_TARGET_PACKAGE)
    assertDestination("Live TV")
    waitForLiveCategoryAvailability()
}

internal fun MacrobenchmarkScope.assertPlayerControlsAvailable(
    timeoutMs: Long = UI_TIMEOUT_MS
) {
    val playbackSelector = By.text(PLAYER_CONTROL_LABEL_PATTERN)
    val playbackDescriptionSelector = By.desc(PLAYER_CONTROL_LABEL_PATTERN)
    val deadline = SystemClock.uptimeMillis() + timeoutMs

    // Opening a live channel can spend several seconds in HLS preparation/recovery. DPAD_CENTER
    // intentionally opens the live channel-info overlay, so use the app's MENU shortcut to show
    // playback controls. Probe in short cycles because the first MENU event can arrive while the
    // fullscreen transition is still handing focus to the player root. The guard before every
    // press prevents toggling an already-visible overlay back off.
    while (SystemClock.uptimeMillis() < deadline) {
        if (device.hasObject(playbackSelector) || device.hasObject(playbackDescriptionSelector)) {
            return
        }

        device.pressKeyCode(KeyEvent.KEYCODE_MENU)
        val remainingMs = (deadline - SystemClock.uptimeMillis()).coerceAtLeast(1L)
        val probeMs = minOf(PLAYER_CONTROL_PROBE_INTERVAL_MS, remainingMs)
        if (device.wait(Until.hasObject(playbackSelector), probeMs) ||
            device.hasObject(playbackDescriptionSelector)
        ) {
            return
        }
        SystemClock.sleep(PLAYER_CONTROL_RETRY_SETTLE_MS)
    }

    check(false) { "Expected visible player controls after opening a seeded live channel." }
}

internal fun MacrobenchmarkScope.navigateToTopLevelDestination(
    label: String,
    targetPackage: String = SEEDED_DEBUG_PACKAGE
) {
    val destinationIndex = SEEDED_TOP_LEVEL_DESTINATIONS.indexOf(label)
    check(destinationIndex >= 0) {
        "Unknown seeded top-level destination '$label'."
    }

    // Home is also the app's ACTION_VIEW landing destination. Sending that existing activity an
    // external navigation intent is deterministic even when a previous content surface retained
    // TV focus; it preserves the process and cache while changing only the route.
    if (label == "Home") {
        val targetActivity = when (targetPackage) {
            RELEASE_TARGET_PACKAGE -> RELEASE_TARGET_ACTIVITY
            SEEDED_DEBUG_PACKAGE -> SEEDED_DEBUG_ACTIVITY
            else -> error("Unsupported benchmark target package '$targetPackage'.")
        }
        device.executeShellCommand("am start -W -a android.intent.action.VIEW -n $targetActivity")
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
    categoryTimeoutMs: Long = LIVE_CATEGORY_TIMEOUT_MS,
    targetPackage: String = SEEDED_DEBUG_PACKAGE
) {
    openTopLevelDestination(
        "Live TV",
        categoryTimeoutMs = categoryTimeoutMs,
        targetPackage = targetPackage
    )
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
