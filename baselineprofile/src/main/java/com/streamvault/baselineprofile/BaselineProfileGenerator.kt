package com.streamvault.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.UiDevice
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val PACKAGE_NAME = "com.streamvault.app"

/**
 * Generates a baseline profile for the app's hot paths: cold start plus D-pad
 * navigation across the top-level destinations (the TV remote journey).
 *
 * Run with a connected API 33+ device or emulator:
 *   ./gradlew :app:generateBaselineProfile
 *
 * The result is written to app/src/release/generated/baselineProfiles/ and
 * picked up automatically by release builds.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() {
        rule.collect(
            packageName = PACKAGE_NAME,
            includeInStartupProfile = true
        ) {
            pressHome()
            startActivityAndWait()
            device.waitForIdle()

            // Walk the top navigation with the D-pad the way a TV user does,
            // entering each top-level destination to exercise its composition.
            repeat(5) {
                device.dpadRightAndSettle()
                device.dpadCenterAndSettle()
            }

            // Scroll down and back inside the last destination to hit lazy-list
            // scrolling code paths.
            repeat(4) { device.dpadDownAndSettle() }
            repeat(4) { device.dpadUpAndSettle() }
        }
    }
}

private fun UiDevice.dpadRightAndSettle() {
    pressDPadRight()
    waitForIdle(1_500)
}

private fun UiDevice.dpadCenterAndSettle() {
    pressDPadCenter()
    waitForIdle(3_000)
}

private fun UiDevice.dpadDownAndSettle() {
    pressDPadDown()
    waitForIdle(1_000)
}

private fun UiDevice.dpadUpAndSettle() {
    pressDPadUp()
    waitForIdle(1_000)
}
