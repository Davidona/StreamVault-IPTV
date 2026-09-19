package com.streamvault.benchmark

import android.content.Intent
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.test.filters.LargeTest
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The maintained source for the app's baseline and startup profiles.
 *
 * Startup is intentionally limited to launching the ready Home shell. General navigation is
 * collected separately so it cannot accidentally inflate the startup profile.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Before
    fun verifySeededTargetBeforeCollection() {
        if (seededTargetVerified.compareAndSet(false, true)) {
            verifySeededReleaseTargetOutsideCollection()
        }
    }

    @Test
    fun startup() = baselineProfileRule.collect(
        packageName = RELEASE_TARGET_PACKAGE,
        includeInStartupProfile = true
    ) {
        pressHome()
        startTargetApp()
        assertDestination("Home")
    }

    @Test
    fun criticalJourneys() = baselineProfileRule.collect(
        packageName = RELEASE_TARGET_PACKAGE,
        includeInStartupProfile = false
    ) {
        openTopLevelDestination(
            label = "Home",
            destinationTimeoutMs = BASELINE_PROFILE_SEED_TIMEOUT_MS,
            categoryTimeoutMs = BASELINE_PROFILE_SEED_TIMEOUT_MS,
            targetPackage = RELEASE_TARGET_PACKAGE
        )
        swipeContent()

        openTopLevelDestination(
            label = "Live TV",
            destinationTimeoutMs = BASELINE_PROFILE_SEED_TIMEOUT_MS,
            categoryTimeoutMs = BASELINE_PROFILE_SEED_TIMEOUT_MS,
            targetPackage = RELEASE_TARGET_PACKAGE
        )
        waitForLiveCategoryAvailability(BASELINE_PROFILE_SEED_TIMEOUT_MS)
        devicePressDPadNavigation()

        openTopLevelDestination("Guide", targetPackage = RELEASE_TARGET_PACKAGE)
        devicePressDPadNavigation()

        navigateLiveAndOpenFocusedChannel(
            categoryTimeoutMs = BASELINE_PROFILE_SEED_TIMEOUT_MS,
            targetPackage = RELEASE_TARGET_PACKAGE
        )
        assertPlayerControlsAvailable(BASELINE_PROFILE_SEED_TIMEOUT_MS)
        devicePressDPadNavigation()
    }

    private fun verifySeededReleaseTargetOutsideCollection() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val launchIntent = context.packageManager
            .getLaunchIntentForPackage(RELEASE_TARGET_PACKAGE)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            ?: error(
                "Baseline profile target '$RELEASE_TARGET_PACKAGE' is not installed. " +
                    "Build/install the nonMinifiedRelease target before collection."
            )

        device.pressHome()
        context.startActivity(launchIntent)
        check(device.wait(Until.hasObject(By.text("Home")), BASELINE_PROFILE_SEED_TIMEOUT_MS)) {
            "Baseline profile target is not seeded with the Home shell. " +
                "Build the nonMinifiedRelease target with local.properties dev-seed fields."
        }

        repeat(40) {
            device.pressDPadUp()
        }
        repeat(10) {
            device.pressDPadLeft()
        }
        device.pressDPadRight()
        device.pressDPadCenter()
        check(device.wait(Until.hasObject(By.text("Live TV")), BASELINE_PROFILE_SEED_TIMEOUT_MS)) {
            "Baseline profile target launched, but Live TV was not reachable from the Home shell."
        }
        check(device.wait(Until.hasObject(By.text("All Channels")), BASELINE_PROFILE_SEED_TIMEOUT_MS)) {
            "Baseline profile target is not seeded with the All Channels category. " +
                "Check local.properties dev-seed fields and provider synchronization."
        }
    }

    private companion object {
        val seededTargetVerified = AtomicBoolean(false)
    }
}
