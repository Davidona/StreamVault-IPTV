package com.streamvault.app.ui

import android.app.Application
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.streamvault.app.MainActivity
import com.streamvault.app.benchmark.XtreamSyncBenchmarkEntryPoint
import com.streamvault.data.local.entity.CategoryEntity
import com.streamvault.data.local.entity.ChannelEntity
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.domain.model.*
import com.streamvault.domain.sync.AutomaticSyncSettings
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.util.regex.Pattern
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in synthetic browse fixture; restores the active provider and all changed preferences. */
@RunWith(AndroidJUnit4::class)
class LiveChannelBrowseJourneyTest {
    @Test fun captureCategoryCountAndControlFocus() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveBrowseReview") == "true")
        val app = ApplicationProvider.getApplicationContext<Application>()
        check(app.packageName.endsWith(".debug"))
        val graph = EntryPointAccessors.fromApplication(app, XtreamSyncBenchmarkEntryPoint::class.java)
        val prefs = graph.preferences()
        val liveSourcePrefs = prefs as PreferencesRepository
        val originalLiveSource = liveSourcePrefs.activeLiveSource.first()
        val originalProvider = graph.providers().getActiveProvider().first()
        val theme = prefs.appTheme.first()
        val language = prefs.appLanguage.first()
        val landing = prefs.appLandingDestination.first()
        val mode = prefs.liveTvChannelMode.first()
        val autoHide = prefs.liveTvAutoHideCategories.first()
        val device = UiDevice.getInstance(instrumentation)
        val output = File(app.getExternalFilesDir(null), "live-browse-review").apply { mkdirs() }
        val report = File(output, "focus-report.txt")
        report.writeText("")
        var owned: Long? = null
        fun capture(name: String) {
            check(device.takeScreenshot(File(output, "$name.png")))
            device.dumpWindowHierarchy(File(output, "$name.xml"))
        }
        fun clickCategory(label: String) {
            val point = checkNotNull(device.findObject(By.text(label))).visibleCenter
            device.executeShellCommand("input mouse tap ${point.x} ${point.y}")
        }
        fun focusableNodes(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = buildList {
            if (node.isVisibleToUser && node.isFocusable) add(node)
            for (i in 0 until node.childCount) node.getChild(i)?.let { addAll(focusableNodes(it)) }
        }
        fun nodeLabel(node: AccessibilityNodeInfo): String = buildList {
            node.text?.toString()?.takeIf { it.isNotBlank() }?.let(::add)
            node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let(::add)
            for (i in 0 until node.childCount) node.getChild(i)?.let { add(nodeLabel(it)) }
        }.filter(String::isNotBlank).joinToString(" | ")
        try {
            val result = graph.providers().addProvider(LegacyProvider(
                name = "Live Browse Review", type = ProviderType.M3U, serverUrl = "",
                m3uUrl = "http://127.0.0.1:9/unused.m3u", isActive = false))
            owned = (result as Result.Success).data
            val id = checkNotNull(owned)
            prefs.setAutomaticSyncSettings(id, AutomaticSyncSettings(enabled = false, live = false,
                movies = false, series = false, epg = false))
            graph.database().categoryDao().insertAll(listOf(
                CategoryEntity(providerId = id, categoryId = 12, name = "Large Review", type = ContentType.LIVE),
                CategoryEntity(providerId = id, categoryId = 13, name = "Small Review", type = ContentType.LIVE)))
            graph.database().channelDao().insertAll((1..533).map { index -> ChannelEntity(
                providerId = id, streamId = index.toLong(), name = "Review Channel %03d".format(index),
                number = index, categoryId = if (index <= 525) 12 else 13,
                streamUrl = "http://127.0.0.1:9/unused/$index.ts") })
            check(graph.providers().setActiveProvider(id).isSuccess)
            liveSourcePrefs.setActiveLiveSource(ActiveLiveSource.ProviderSource(id))
            prefs.setAppLanguage("en")
            prefs.setAppLandingDestination(AppLandingDestination.LIVE_TV)
            prefs.setLiveTvChannelMode("COMPACT")
            prefs.setLiveTvAutoHideCategories(false)
            for (palette in listOf(AppTheme.CLASSIC_BLUE, AppTheme.LIGHT, AppTheme.AMOLED_BLACK)) {
                prefs.setAppTheme(palette)
                ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java)).use {
                    check(device.wait(Until.hasObject(By.text("Large Review")), 30_000))
                    clickCategory("Large Review")
                    check(device.wait(Until.hasObject(By.text("525 channels")), 20_000)) {
                        capture("${palette.storageValue}_missing_count")
                        "Category total was not displayed"
                    }
                    SystemClock.sleep(500)
                    capture("${palette.storageValue}_default")
                    val nodes = focusableNodes(checkNotNull(instrumentation.uiAutomation.rootInActiveWindow))
                    var focused = 0
                    nodes.forEachIndexed { index, node ->
                        val label = nodeLabel(node)
                        if (node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) {
                            device.waitForIdle(1000)
                            SystemClock.sleep(200)
                            capture("${palette.storageValue}_focus_$index")
                            focused++
                            report.appendText("${palette.storageValue} focus_$index $label\n")
                        } else report.appendText("${palette.storageValue} UNTESTED focus_$index $label\n")
                    }
                    check(focused >= 3) { "Expected category controls and both search inputs" }
                    clickCategory("Small Review")
                    check(device.wait(Until.hasObject(By.text("8 channels")), 10_000))
                    capture("${palette.storageValue}_small_category")
                }
            }
            prefs.setAppTheme(AppTheme.CLASSIC_BLUE)
            for ((locale, countWord) in listOf("ar" to "قناة", "fa" to "کانال", "iw" to "ערוצים")) {
                prefs.setAppLanguage(locale)
                ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java)).use {
                    check(device.wait(Until.hasObject(By.text("Large Review")), 30_000))
                    clickCategory("Large Review")
                    val expectedCount = Pattern.compile("(?:525|۵۲۵|٥٢٥)\\s+" + Pattern.quote(countWord))
                    check(device.wait(Until.hasObject(By.text(expectedCount)), 20_000))
                    SystemClock.sleep(300)
                    capture("rtl_${locale}_default")
                }
            }
        } finally {
            prefs.setAppTheme(theme)
            prefs.setAppLanguage(language)
            prefs.setAppLandingDestination(landing)
            prefs.setLiveTvChannelMode(mode ?: "PRO")
            prefs.setLiveTvAutoHideCategories(autoHide)
            originalProvider?.let { graph.providers().setActiveProvider(it.id) }
            owned?.let { graph.providers().deleteProvider(it) }
            liveSourcePrefs.setActiveLiveSource(originalLiveSource)
        }
    }
}
