package com.streamvault.app.ui

import android.app.Application
import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import com.streamvault.app.MainActivity
import com.streamvault.app.benchmark.XtreamSyncBenchmarkEntryPoint
import com.streamvault.domain.model.*
import com.streamvault.domain.usecase.*
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in fixture capture. Preserves installed providers and restores preferences after review. */
@RunWith(AndroidJUnit4::class)
class FeaturedCardJourneyTest {
    @Test fun captureFeaturedCards() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("featuredReview") == "true")
        val settingsOnly = InstrumentationRegistry.getArguments().getString("settingsFocusOnly") == "true"
        val app = ApplicationProvider.getApplicationContext<Application>()
        check(app.packageName.endsWith(".debug"))
        val graph = EntryPointAccessors.fromApplication(app, XtreamSyncBenchmarkEntryPoint::class.java)
        val prefs = graph.preferences()
        val originalProvider = graph.providers().getActiveProvider().first()
        val originalTheme = prefs.appTheme.first()
        val originalLanguage = prefs.appLanguage.first()
        val originalLanding = prefs.appLandingDestination.first()
        val movieMode = prefs.movieFeaturedMode.first()
        val seriesMode = prefs.seriesFeaturedMode.first()
        val viewMode = prefs.vodViewMode.first()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val directory = File(app.getExternalFilesDir(null), "featured-review").apply { mkdirs() }
        var owned: Long? = null
        fun capture(name: String) {
            check(device.takeScreenshot(File(directory, "$name.png")))
            device.dumpWindowHierarchy(File(directory, "$name.xml"))
        }
        fun click(label: String) {
            fun find() = device.findObject(By.desc(label)) ?: device.findObject(By.text(label))
            repeat(4) {
                device.waitForIdle(1000)
                var target = device.wait(Until.findObject(By.desc(label)), 1500)
                    ?: device.wait(Until.findObject(By.text(label)), 1500)
                if (target == null) {
                    repeat(12) { device.pressDPadUp() }
                    for (direction in listOf(true, false)) {
                        repeat(14) {
                            if (target == null) {
                                if (direction) device.pressDPadRight() else device.pressDPadLeft()
                                SystemClock.sleep(100)
                                target = find()
                            }
                        }
                    }
                }
                try {
                    val point = checkNotNull(target) { "Missing navigation: $label" }.visibleCenter
                    device.executeShellCommand("input mouse tap ${point.x} ${point.y}")
                    SystemClock.sleep(400)
                    device.waitForIdle(1000)
                    return
                } catch (_: StaleObjectException) {
                    SystemClock.sleep(300)
                }
            }
            error("Navigation remained stale: $label")
        }
        try {
            val result = graph.setup().loginXtream(XtreamProviderSetupCommand(
                serverUrl = "http://10.0.2.2:8765", username = "featured", password = "fixture",
                name = "Featured Review", xtreamFastSyncEnabled = false))
            owned = when (result) {
                is ValidateAndAddProviderResult.Success -> result.provider.id
                is ValidateAndAddProviderResult.SavedWithWarning -> result.provider.id
                else -> error("Fixture setup failed")
            }
            val providerId = checkNotNull(owned)
            check(graph.providers().setActiveProvider(providerId).isSuccess)
            val movieId = graph.database().movieDao().insert(com.streamvault.data.local.entity.MovieEntity(
                name = "Featured Review Movie", providerId = providerId, streamId = 990001,
                releaseDate = "2026-11-01", year = "2026", addedAt = 1793491200,
                streamUrl = "http://10.0.2.2:8765/review.mp4", genre = "Drama", rating = 8f))
            val showId = graph.database().seriesDao().insert(com.streamvault.data.local.entity.SeriesEntity(
                name = "Featured Review Series", providerId = providerId, seriesId = 990002,
                releaseDate = "2026-11-01", lastModified = 1793491200, genre = "Drama", rating = 8f))
            val episode = graph.database().episodeDao().insert(com.streamvault.data.local.entity.EpisodeEntity(
                title = "Featured review episode", providerId = providerId, seriesId = showId,
                seasonNumber = 1, episodeNumber = 1, durationSeconds = 120,
                streamUrl = "http://10.0.2.2:8765/review.mp4"))
            check(graph.launcherHistory().recordPlayback(PlaybackHistory(
                contentId = movieId, providerId = providerId, contentType = ContentType.MOVIE,
                title = "Featured Review Movie", streamUrl = "http://10.0.2.2:8765/review.mp4",
                resumePositionMs = 30000, totalDurationMs = 120000,
                lastWatchedAt = System.currentTimeMillis())).isSuccess)
            check(graph.launcherHistory().recordPlayback(PlaybackHistory(
                contentId = episode, providerId = providerId, contentType = ContentType.SERIES_EPISODE,
                title = "Featured review episode", seriesId = showId, seasonNumber = 1, episodeNumber = 1,
                streamUrl = "http://10.0.2.2:8765/review.mp4", resumePositionMs = 30000,
                totalDurationMs = 120000, lastWatchedAt = System.currentTimeMillis())).isSuccess)
            prefs.setAppLanguage("en")
            prefs.setAppLandingDestination(AppLandingDestination.HOME)
            prefs.setVodViewMode(VodViewMode.MODERN.storageValue)
            prefs.setVodFeaturedMode(false, VodFeaturedMode.CONTINUE_WATCHING.name)
            prefs.setVodFeaturedMode(true, VodFeaturedMode.UP_NEXT.name)
            for (theme in listOf(AppTheme.CLASSIC_BLUE, AppTheme.LIGHT, AppTheme.AMOLED_BLACK)) {
                prefs.setAppTheme(theme)
                ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java)).use {
                    check(device.wait(Until.hasObject(By.text("Home")), 30000))
                    for ((label, route) in (if (settingsOnly) emptyList() else listOf("Movies" to "movies", "Series" to "series"))) {
                        prefs.setVodFeaturedMode(route == "series", if (route == "series") VodFeaturedMode.UP_NEXT.name else VodFeaturedMode.CONTINUE_WATCHING.name)
                        click(label)
                        check(device.wait(Until.hasObject(By.descStartsWith("streamvault.destination:$route")), 15000))
                        check(device.wait(Until.hasObject(By.text(if (route == "series") "Up next" else "Continue watching")), 15000)) { "Featured card missing: $route" }
                        capture("${theme.storageValue}_${route}_default")
                        click("Home")
                        prefs.setVodFeaturedMode(route == "series", VodFeaturedMode.LATEST_RELEASE.name)
                        click(label)
                        check(device.wait(Until.hasObject(By.text("Latest releases")), 15000))
                        capture("${theme.storageValue}_${route}_latest")
                        for ((mode, text) in listOf(
                            VodFeaturedMode.RECENTLY_ADDED to if (route == "series") "Recently updated" else "Recently added",
                            VodFeaturedMode.RANDOM to "Feeling lucky", VodFeaturedMode.FOR_YOU to "For you")) {
                            click("Home")
                            prefs.setVodFeaturedMode(route == "series", mode.name)
                            click(label)
                            check(device.wait(Until.hasObject(By.text(text)), 15000)) { "Mode unavailable: $route $mode" }
                            capture("${theme.storageValue}_${route}_${mode.name}")
                        }
                        click("Home")
                        prefs.setVodFeaturedMode(route == "series", VodFeaturedMode.LATEST_RELEASE.name)
                        click(label)
                        check(device.wait(Until.hasObject(By.text("Latest releases")), 15000))
                        device.pressDPadDown()
                        repeat(12) { index ->
                            capture("${theme.storageValue}_${route}_action_focus_$index")
                            device.pressDPadRight(); SystemClock.sleep(150)
                        }
                        // Capture the D-pad path including the featured card, action chips and content items.
                        repeat(6) { index ->
                            device.pressDPadDown(); SystemClock.sleep(150)
                            capture("${theme.storageValue}_${route}_focus_$index")
                        }
                        click("Home")
                        prefs.setVodFeaturedMode(route == "series", VodFeaturedMode.HIDDEN.name)
                        click(label)
                        check(device.wait(Until.gone(By.text("Latest releases")), 5000))
                        capture("${theme.storageValue}_${route}_hidden")
                        prefs.setVodFeaturedMode(route == "series", VodFeaturedMode.LATEST_RELEASE.name)
                    }
                    click("Settings")
                    SystemClock.sleep(1000)
                    capture("${theme.storageValue}_settings_default")
                    click("Movies & Series")
                    click("Library browsing")
                    SystemClock.sleep(1000)
                    capture("${theme.storageValue}_settings_library")
                    capture("${theme.storageValue}_settings_Movie_row_focused")
                    device.pressDPadDown(); SystemClock.sleep(300)
                    capture("${theme.storageValue}_settings_Series_row_focused")
                    device.pressDPadUp(); SystemClock.sleep(300)
                    for (kind in (if (settingsOnly) emptyList() else listOf("Movie", "Series"))) {
                        click("$kind featured card")
                        SystemClock.sleep(700)
                        repeat(8) { device.pressDPadUp() }
                        repeat(8) { index ->
                            capture("${theme.storageValue}_settings_${kind}_mode_focus_$index")
                            device.pressDPadDown(); SystemClock.sleep(150)
                        }
                        device.pressBack()
                    }
                    repeat(if (settingsOnly) 14 else 18) { index ->
                        capture("${theme.storageValue}_settings_row_focus_$index")
                        device.pressDPadDown(); SystemClock.sleep(150)
                    }
                }
            }
            for (language in (if (settingsOnly) emptyList() else listOf("ar", "fa", "iw"))) {
                prefs.setAppLanguage(language)
                SystemClock.sleep(1000)
                ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java)).use { scenario ->
                    SystemClock.sleep(2000)
                    var movies = ""
                    var series = ""
                    var settings = ""
                    var category = ""
                    var library = ""
                    var featured = ""
                    scenario.onActivity { activity ->
                        val locale = java.util.Locale.forLanguageTag(language)
                        val localized = activity.createConfigurationContext(android.content.res.Configuration(activity.resources.configuration).apply {
                            setLocale(locale)
                            setLayoutDirection(locale)
                        })
                        check(localized.resources.configuration.layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL)
                        movies = localized.getString(com.streamvault.feature.catalog.R.string.nav_movies)
                        series = localized.getString(com.streamvault.feature.catalog.R.string.nav_series)
                        settings = localized.getString(com.streamvault.feature.settings.R.string.nav_settings)
                        category = localized.getString(com.streamvault.feature.settings.R.string.settings_domain_movies)
                        library = localized.getString(com.streamvault.feature.settings.R.string.settings_page_vod_library)
                        featured = localized.getString(com.streamvault.feature.settings.R.string.featured_movies)
                    }
                    for ((label, route) in listOf(movies to "movies", series to "series")) {
                        click(label)
                        check(device.wait(Until.hasObject(By.descStartsWith("streamvault.destination:$route")), 15000))
                        SystemClock.sleep(1500)
                        capture("rtl_${language}_${route}")
                    }
                    click(settings); SystemClock.sleep(700)
                    click(category); SystemClock.sleep(700)
                    click(library); SystemClock.sleep(700)
                    capture("rtl_${language}_settings")
                    click(featured); SystemClock.sleep(700)
                    capture("rtl_${language}_dialog")
                    device.pressBack()
                }
            }
        } catch (failure: Throwable) {
            capture("failure")
            throw failure
        } finally {
            owned?.let { graph.providers().deleteProvider(it) }
            originalProvider?.let { graph.providers().setActiveProvider(it.id) }
            prefs.setAppLanguage(originalLanguage)
            prefs.setAppTheme(originalTheme)
            prefs.setAppLandingDestination(originalLanding)
            prefs.setVodViewMode(viewMode ?: VodViewMode.MODERN.storageValue)
            prefs.setVodFeaturedMode(false, VodFeaturedMode.fromStorage(movieMode).name)
            prefs.setVodFeaturedMode(true, VodFeaturedMode.fromStorage(seriesMode, true).name)
        }
    }
}

