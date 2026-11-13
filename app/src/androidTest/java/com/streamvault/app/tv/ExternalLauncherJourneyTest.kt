package com.streamvault.app.tv

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.streamvault.app.MainActivity
import com.streamvault.app.benchmark.XtreamSyncBenchmarkEntryPoint
import com.streamvault.data.local.entity.EpisodeEntity
import com.streamvault.data.local.entity.MovieEntity
import com.streamvault.data.local.entity.SeriesEntity
import com.streamvault.domain.model.AppTheme
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.PlaybackHistory
import com.streamvault.domain.usecase.M3uProviderSetupCommand
import com.streamvault.domain.usecase.ValidateAndAddProviderResult
import com.streamvault.player.PlaybackState
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in, fresh TV AVD; tools/playback_webhook_fixture.py --port 8879 with a 120s movie.mp4. */
@RunWith(AndroidJUnit4::class)
class ExternalLauncherJourneyTest {
    @Test fun publishedLinksResumeExactItemsAndPublicLinksWorkAcrossThemes() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("launcherLinksFixture") == "true")
        val app = ApplicationProvider.getApplicationContext<Application>()
        check(app.packageName.endsWith(".debug"))
        val graph = EntryPointAccessors.fromApplication(app, XtreamSyncBenchmarkEntryPoint::class.java)
        check(graph.providers().getProviders().first().isEmpty()) { "Use a fresh dedicated AVD" }
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val directory = File(app.getExternalFilesDir(null), "launcher-links").apply { mkdirs() }
        val added = graph.setup().addM3u(M3uProviderSetupCommand("$BASE/playlist.m3u", "Launcher Fixture"))
        val provider = when (added) {
            is ValidateAndAddProviderResult.Success -> added.provider.id
            is ValidateAndAddProviderResult.SavedWithWarning -> added.provider.id
            else -> error("Local fixture setup failed: ${added.javaClass.simpleName}")
        }
        graph.preferences().setLastActiveProviderId(provider)
        graph.preferences().setAppLanguage("en")
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                check(device.wait(Until.hasObject(By.pkg(app.packageName)), 30_000))
                delay(4_000)
            }
            val db = graph.database()
            val movie = db.movieDao().insert(MovieEntity(name = "Launcher Fixture Movie", providerId = provider,
                streamUrl = "$BASE/movie.mp4", containerExtension = "mp4", durationSeconds = 120))
            val show = db.seriesDao().insert(SeriesEntity(name = "Launcher Fixture Series", providerId = provider))
            val episode = db.episodeDao().insert(EpisodeEntity(title = "Launcher Fixture Episode", providerId = provider,
                seriesId = show, seasonNumber = 1, episodeNumber = 1, streamUrl = "$BASE/movie.mp4",
                containerExtension = "mp4", durationSeconds = 120))
            val saved = PlaybackHistory(contentId = movie, providerId = provider, contentType = ContentType.MOVIE,
                title = "Launcher Fixture Movie", streamUrl = "$BASE/movie.mp4", resumePositionMs = 30_000,
                totalDurationMs = 120_000, lastWatchedAt = System.currentTimeMillis())
            check(graph.launcherHistory().recordPlayback(saved).isSuccess)
            graph.launcherWatchNext().refreshWatchNext()
            graph.launcherRecommendations().refreshRecommendations(force = true)
            val published = app.contentResolver.query(Uri.parse("content://android.media.tv/watch_next_program"),
                arrayOf("title", "intent_uri"), null, null, null)!!.use { cursor ->
                var result: String? = null
                while (cursor.moveToNext()) if (cursor.getString(0) == saved.title) result = cursor.getString(1)
                checkNotNull(result) { "Fixture was not published to Watch Next" }
            }
            val restored = Intent.parseUri(published, Intent.URI_INTENT_SCHEME)
            check(restored.data?.host == "movie" && restored.data?.lastPathSegment == movie.toString())
            check(restored.data?.getQueryParameter("provider") == provider.toString())
            check(!published.contains(BASE)) { "Launcher link leaked a playback URL" }
            val engine = graph.launcherPlayerEngine()
            suspend fun playingAt(minimum: Long, maximum: Long) = withTimeout(40_000) {
                while (engine.playbackState.value != PlaybackState.READY || !engine.isPlaying.value ||
                    engine.currentPosition.value !in minimum..maximum) delay(100)
            }
            fun launch(type: String, id: Long, action: String): ActivityScenario<MainActivity> =
                ActivityScenario.launch(Intent(Intent.ACTION_VIEW,
                    Uri.parse("streamvault://$type/$id?provider=$provider&action=$action")).setPackage(app.packageName))

            ActivityScenario.launch<MainActivity>(restored).use { scenario ->
                playingAt(30_000, 45_000)
                File(directory, "resume.txt").writeText("movie=$movie provider=$provider position=${engine.currentPosition.value} playing=${engine.isPlaying.value}\n")
                fun startOver() = scenario.onActivity { activity ->
                    activity.startActivity(Intent(Intent.ACTION_VIEW,
                        Uri.parse("streamvault://movie/$movie?provider=$provider&action=start_over")).setPackage(app.packageName))
                }
                startOver()
                playingAt(0, 10_000)
                withTimeout(15_000) { while (engine.currentPosition.value < 5_000) delay(100) }
                startOver()
                playingAt(0, 3_000)
                File(directory, "warm-start-over.txt").writeText("PASS: two consecutive onNewIntent start_over links reset the same movie\n")
                // ActivityScenario matches lifecycle events against its original launch intent.
                // MainActivity intentionally updates Activity.intent on each external launch.
                scenario.onActivity { it.intent = restored }
            }
            check(graph.launcherHistory().recordPlayback(saved.copy(contentId = episode, contentType = ContentType.SERIES_EPISODE,
                title = "Launcher Fixture Episode", seriesId = show, seasonNumber = 1, episodeNumber = 1,
                resumePositionMs = 40_000, lastWatchedAt = System.currentTimeMillis())).isSuccess)
            launch("series", show, "resume").use {
                playingAt(40_000, 55_000)
                File(directory, "series-resume.txt").writeText("series=$show episode=$episode position=${engine.currentPosition.value}\n")
            }
            for (theme in listOf(AppTheme.CLASSIC_BLUE, AppTheme.LIGHT, AppTheme.AMOLED_BLACK)) {
                graph.preferences().setAppTheme(theme)
                for ((type, id, title) in listOf(Triple("movie", movie, "Launcher Fixture Movie"),
                    Triple("series", show, "Launcher Fixture Series"))) {
                    launch(type, id, "details").use {
                        check(device.wait(Until.hasObject(By.text(title)), 20_000))
                        delay(750)
                        check(device.takeScreenshot(File(directory, "${theme.storageValue}_${type}_default.png")))
                        repeat(3) { device.pressDPadUp(); delay(100) }
                        delay(300)
                        check(device.findObject(By.focused(true)) != null)
                        check(device.takeScreenshot(File(directory, "${theme.storageValue}_${type}_focused.png")))
                    }
                }
                launch("movie", movie, "resume").use {
                    playingAt(1_000, 100_000)
                    delay(4_000)
                    check(device.takeScreenshot(File(directory, "${theme.storageValue}_player_default.png")))
                    device.pressDPadCenter()
                    delay(250)
                    device.pressDPadRight()
                    delay(250)
                    check(device.takeScreenshot(File(directory, "${theme.storageValue}_player_focused.png")))
                }
            }
            File(directory, "result.txt").writeText("PASS: public links, published Watch Next URI, direct resume, start over, series episode resume, 18 theme screenshots\n")
        } finally {
            graph.preferences().setAppTheme(AppTheme.CLASSIC_BLUE)
            graph.providers().deleteProvider(provider)
        }
    }

    private companion object { const val BASE = "http://10.0.2.2:8879" }
}
