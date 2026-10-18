package com.streamvault.app.ui

import android.app.Application
import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.streamvault.app.MainActivity
import com.streamvault.app.benchmark.XtreamSyncBenchmarkEntryPoint
import com.streamvault.domain.model.AppLandingDestination
import com.streamvault.domain.model.AppTheme
import com.streamvault.domain.usecase.ValidateAndAddProviderResult
import com.streamvault.domain.usecase.XtreamProviderSetupCommand
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in production-screen visual review against tools/catalog_xtream_fixture.py.
 * Requires an empty debug install; removes only the provider created by this journey.
 * These captures review UI, and do not claim to validate media playback.
 */
@RunWith(AndroidJUnit4::class)
class BrowseVisualJourneyTest {
    @Test
    fun captureProductionScreensAcrossThemes() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("visualRefresh") == "true")
        val app = ApplicationProvider.getApplicationContext<Application>()
        check(app.packageName.endsWith(".debug"))
        val graph = EntryPointAccessors.fromApplication(app, XtreamSyncBenchmarkEntryPoint::class.java)
        check(graph.providers().getProviders().first().isEmpty()) { "Use an empty debug install" }
        val prefs = graph.preferences()
        val originalTheme = prefs.appTheme.first()
        val originalLanding = prefs.appLandingDestination.first()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val directory = File(app.getExternalFilesDir(null), "visual-refresh").apply { mkdirs() }
        var ownedProviderId: Long? = null
        try {
            val result = graph.setup().loginXtream(XtreamProviderSetupCommand(
                serverUrl = "http://10.0.2.2:8765", username = "visual", password = "fixture",
                name = "Visual Review", xtreamFastSyncEnabled = false,
            ))
            ownedProviderId = when (result) {
                is ValidateAndAddProviderResult.Success -> result.provider.id
                is ValidateAndAddProviderResult.SavedWithWarning -> result.provider.id
                else -> error("Local fixture setup failed: ${result.javaClass.simpleName}")
            }
            prefs.setAppLandingDestination(AppLandingDestination.HOME)
            for (theme in AppTheme.entries) {
                prefs.setAppTheme(theme)
                ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java)).use {
                    check(device.wait(Until.hasObject(By.text("Home")), 30_000))
                    SystemClock.sleep(2_000)
                    capture(device, directory, "${theme.storageValue}_home")
                    for ((label, route) in listOf(
                        "Movies" to "movies", "Series" to "series", "Live TV" to "live_tv",
                        "Guide" to "epg", "Search" to "search", "Downloads" to "downloads",
                        "Plugins" to "plugins",
                    )) {
                        val tab = device.findObject(By.desc(label)) ?: device.findObject(By.text(label))
                        clickPointer(device, checkNotNull(tab) { "Navigation item missing: $label" })
                        check(device.wait(Until.hasObject(By.descStartsWith("streamvault.destination:$route")), 15_000)) {
                            "Navigation did not reach $route"
                        }
                        SystemClock.sleep(2_000)
                        capture(device, directory, "${theme.storageValue}_$route")
                        if (label == "Live TV") {
                            repeat(2) {
                                if (!device.hasObject(By.descContains("Fixture News"))) {
                                    val category = device.findObjects(By.text("All Channels")).first {
                                        generateSequence(it) { node -> node.parent }.any { node -> node.isClickable }
                                    }
                                    clickPointer(device, category)
                                    device.wait(Until.hasObject(By.descContains("Fixture News")), 5_000)
                                }
                            }
                            check(device.hasObject(By.descContains("Fixture News"))) { "Live channel row did not appear" }
                            capture(device, directory, "${theme.storageValue}_live_tv_channels")
                        }
                        if (label == "Movies" || label == "Series") {
                            val fixtureName = if (label == "Movies") "Fixture Movie One" else "Fixture Series One"
                            check(device.wait(Until.hasObject(By.descStartsWith(fixtureName)), 15_000))
                            clickPointer(device, device.findObject(By.descStartsWith(fixtureName)))
                            check(device.wait(Until.hasObject(By.text("Back to library")), 15_000))
                            SystemClock.sleep(2_000)
                            capture(device, directory, "${theme.storageValue}_${route}_detail")
                            clickPointer(device, device.findObject(By.text("Back to library")))
                            check(device.wait(Until.hasObject(By.descStartsWith("streamvault.destination:$route")), 15_000))
                        }
                    }
                    // TV pointer activation uses a mouse source, matching the app's TV contract.
                    clickPointer(device, device.findObject(By.desc("Home")))
                    check(device.wait(Until.hasObject(By.desc("streamvault.destination:home")), 15_000))
                    clickPointer(device, device.findObject(By.desc("Home")))
                    device.pressDPadRight()
                    device.pressDPadCenter()
                    check(device.wait(Until.hasObject(By.descStartsWith("streamvault.destination:live_tv")), 15_000))
                    SystemClock.sleep(1_000)
                    capture(device, directory, "${theme.storageValue}_remote_focus")
                }
            }
        } finally {
            ownedProviderId?.let { graph.providers().deleteProvider(it) }
            prefs.setAppTheme(originalTheme)
            prefs.setAppLandingDestination(originalLanding)
        }
    }

    private fun clickPointer(device: UiDevice, target: UiObject2) {
        val center = target.visibleCenter
        device.executeShellCommand("input mouse tap ${center.x} ${center.y}")
    }

    private fun capture(device: UiDevice, directory: File, name: String) {
        check(device.takeScreenshot(File(directory, "$name.png")))
        device.dumpWindowHierarchy(File(directory, "$name.xml"))
    }
}
