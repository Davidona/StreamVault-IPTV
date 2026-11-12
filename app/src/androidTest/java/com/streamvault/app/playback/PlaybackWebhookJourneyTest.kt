package com.streamvault.app.playback

import android.app.Application
import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import com.google.gson.JsonParser
import com.streamvault.app.MainActivity
import com.streamvault.app.benchmark.XtreamSyncBenchmarkEntryPoint
import com.streamvault.core.navigation.AppDestination
import com.streamvault.core.navigation.PlayerNavigationRequest
import com.streamvault.data.local.entity.*
import com.streamvault.domain.model.*
import com.streamvault.domain.playback.events.*
import com.streamvault.domain.usecase.M3uProviderSetupCommand
import com.streamvault.domain.usecase.ValidateAndAddProviderResult
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in: dedicated emulator plus tools/playback_webhook_fixture.py on port 8877. */
@RunWith(AndroidJUnit4::class)
class PlaybackWebhookJourneyTest {
    private val receiverClient = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()
    @Test fun actualPlaybackEmitsEventsAndLiveKeepsAdvancing() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("webhookJourney") == "true")
        val app = ApplicationProvider.getApplicationContext<Application>()
        check(app.packageName.endsWith(".debug"))
        val graph = EntryPointAccessors.fromApplication(app, XtreamSyncBenchmarkEntryPoint::class.java)
        check(graph.providers().getProviders().first().isEmpty()) { "Use a fresh dedicated emulator" }
        val webhooks = graph.webhooks()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val directory = File(app.getExternalFilesDir(null), "webhook-playback").apply { mkdirs() }
        val original = webhooks.configuration.value
        var providerId: Long? = null
        try {
            val result = graph.setup().addM3u(M3uProviderSetupCommand("$BASE/playlist.m3u", "Webhook Acceptance", m3uVodClassificationEnabled = true))
            providerId = when (result) {
                is ValidateAndAddProviderResult.Success -> result.provider.id
                is ValidateAndAddProviderResult.SavedWithWarning -> result.provider.id
                else -> error("Fixture setup failed: ${result.javaClass.simpleName}")
            }
            val id = checkNotNull(providerId)
            graph.preferences().setLastActiveProviderId(id)
            graph.preferences().setAppLanguage("en")
            val database = graph.database()
            val channels = withTimeout(60_000) {
                var rows = database.channelDao().getByProviderSync(id)
                while (rows.size < 2) { delay(250); rows = database.channelDao().getByProviderSync(id) }
                rows
            }
            for (theme in listOf(AppTheme.CLASSIC_BLUE, AppTheme.LIGHT, AppTheme.AMOLED_BLACK)) {
                graph.preferences().setAppTheme(theme)
                ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_EXTERNAL_DESTINATION, AppDestination.Settings())).use {
                    val appLabel = app.getString(com.streamvault.feature.settings.R.string.settings_domain_app)
                    check(device.wait(Until.hasObject(By.text(appLabel)), 30_000))
                    val appTab = checkNotNull(device.findObject(By.text(appLabel))).visibleCenter
                    device.executeShellCommand("input mouse tap ${appTab.x} ${appTab.y}")
                    val title = app.getString(com.streamvault.feature.settings.R.string.webhook_title)
                    check(device.wait(Until.hasObject(By.textContains(title)), 15_000))
                    SystemClock.sleep(500)
                    check(device.takeScreenshot(File(directory, "${theme.storageValue}_overview_default.png")))
                    device.pressDPadRight()
                    focusLabel(device, title)
                    check(device.takeScreenshot(File(directory, "${theme.storageValue}_overview_focused.png")))
                    device.pressDPadCenter()
                    val enabled = app.getString(com.streamvault.feature.settings.R.string.webhook_enabled)
                    val add = app.getString(com.streamvault.feature.settings.R.string.webhook_add)
                    check(device.wait(Until.hasObject(By.text(add)), 15_000))
                    focusLabel(device, enabled)
                    check(device.takeScreenshot(File(directory, "${theme.storageValue}_page_focused.png")))
                    device.pressDPadUp()
                    SystemClock.sleep(300)
                    check(device.takeScreenshot(File(directory, "${theme.storageValue}_page_default.png")))
                    val addButton = checkNotNull(device.findObject(By.text(add))).visibleCenter
                    device.executeShellCommand("input mouse tap ${addButton.x} ${addButton.y}")
                    check(device.wait(Until.hasObject(By.clazz("android.widget.EditText")), 10_000))
                    SystemClock.sleep(300)
                    check(device.takeScreenshot(File(directory, "${theme.storageValue}_editor_focused.png")))
                    device.pressDPadUp()
                    SystemClock.sleep(300)
                    check(device.takeScreenshot(File(directory, "${theme.storageValue}_editor_default.png")))
                }
            }
            graph.preferences().setAppTheme(AppTheme.CLASSIC_BLUE)
            // Cold-start provider sync can replace its catalog. Insert synthetic VOD only
            // after startup/settings navigation, then verify the exact persisted identities.
            val movie = database.movieDao().insert(MovieEntity(name = "Webhook Movie", streamUrl = "$BASE/movie.mp4",
                containerExtension = "mp4", durationSeconds = 40, tmdbId = 603, providerId = id))
            val series = database.seriesDao().insert(SeriesEntity(name = "Webhook Series", tmdbId = 1396, providerId = id))
            val episode = database.episodeDao().insert(EpisodeEntity(title = "Webhook Episode", episodeNumber = 1, seasonNumber = 0,
                streamUrl = "$BASE/episode.mp4", containerExtension = "mp4", durationSeconds = 10, seriesId = series, providerId = id))
            check(database.movieDao().getById(movie)?.tmdbId == 603L)
            val native = PlaybackWebhookDestination(name = "Native fixture", url = "$BASE/hooks/native",
                headerName = "Authorization", headerValue = "Bearer fixture-token", policy = PlaybackEventPolicy(15_000, 90))
            webhooks.save(PlaybackWebhookConfiguration(true, listOf(native,
                native.copy(id = "yamtrack", name = "Yamtrack fixture", url = "$BASE/hooks/yamtrack", format = PlaybackWebhookFormat.YAMTRACK,
                    delivery = PlaybackWebhookDelivery.HISTORY),
                native.copy(id = "jellyfin", name = "Jellyfin fixture", url = "$BASE/hooks/jellyfin", format = PlaybackWebhookFormat.JELLYFIN,
                    delivery = PlaybackWebhookDelivery.HISTORY),
                native.copy(id = "unreachable", name = "Unavailable fixture", url = "http://10.0.2.2:8878/hook"))))
            fun launch(request: PlayerNavigationRequest) = ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_PLAYER_REQUEST, request))
            launch(PlayerNavigationRequest("$BASE/movie.mp4", "Webhook Movie", internalId = movie, providerId = id, contentType = "MOVIE")).use {
                waitForEvent("Webhook Movie", "start")
                device.pressKeyCode(android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                waitForEvent("Webhook Movie", "pause")
                device.pressKeyCode(android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                waitForEvent("Webhook Movie", "resume")
                waitForEvent("Webhook Movie", "progress")
                waitForEvent("Webhook Movie", "watched")
                waitForEvent("Webhook Movie", "stop")
            }
            launch(PlayerNavigationRequest("$BASE/episode.mp4", "Webhook Episode", internalId = episode, providerId = id,
                contentType = "SERIES_EPISODE", seriesId = series, seasonNumber = 0, episodeNumber = 1, episodeId = episode)).use {
                waitForEvent("Webhook Episode", "start")
                waitForEvent("Webhook Episode", "stop")
            }
            for (channel in channels.take(2)) {
                device.executeShellCommand("logcat -c")
                launch(PlayerNavigationRequest(channel.streamUrl, channel.name, internalId = channel.id, providerId = id, contentType = "LIVE")).use {
                    waitForEvent(channel.name, "start")
                    val stem = channel.name.replace(' ', '_')
                    repeat(61) { index ->
                        check(device.takeScreenshot(File(directory, "${stem}_${index.toString().padStart(2, '0')}.png")))
                        SystemClock.sleep(2000)
                    }
                    val session = device.executeShellCommand("dumpsys media_session")
                    File(directory, "${stem}_session.txt").writeText(session)
                    check((session.contains("state=PLAYING(3)") || session.contains("state=3,")) &&
                        session.contains("error=null")) { "Live session is not healthy" }
                    val log = device.executeShellCommand("logcat -d -v time")
                    File(directory, "${stem}_playback.log").writeText(log.lines().filter {
                        it.contains("first-frame-success") || it.contains("read-progress streamType=HLS") ||
                            it.contains("prepare resolvedStreamType=") || it.contains("Player stuck") || it.contains("fatal-error")
                    }.joinToString("\n"))
                    check(!log.contains("fatal-error") && !log.contains("Player stuck") && !log.contains("prepare resolvedStreamType=MPEG_TS_LIVE"))
                    check(log.contains("read-progress streamType=HLS") || log.contains("first-frame-success"))
                }
            }
            val received = readFinalEvents()
            File(directory, "events.json").writeText(received)
            val events = nativeEvents(received)
            val movieStop = events.first { it["event"].asString == "stop" && it["title"].asString == "Webhook Movie" }
            check(movieStop["watched"].asBoolean && movieStop["ids"].asJsonObject["tmdb"].asLong == 603L)
            val episodeStop = events.first { it["event"].asString == "stop" && it["title"].asString == "Webhook Episode" }
            check(episodeStop["series_ids"].asJsonObject["tmdb"].asLong == 1396L)
        } finally {
            webhooks.save(original)
            providerId?.let { graph.providers().deleteProvider(it) }
        }
    }

    private suspend fun waitForEvent(title: String, event: String) = withTimeout(60_000) {
        while (true) {
            val received = try { nativeEvents() } catch (_: java.io.IOException) { emptyList() }
            if (received.any { it["title"]?.asString == title && it["event"]?.asString == event }) break
            delay(250)
        }
    }
    private fun nativeEvents(received: String = readEvents()) = JsonParser.parseString(received).asJsonArray
        .map { it.asJsonObject }.filter { it["destination"].asString == "/hooks/native" }.map { it["payload"].asJsonObject }

    private fun readEvents(): String {
        return receiverClient.newCall(Request.Builder().url("$BASE/events").header("Connection", "close").build())
            .execute().use { response -> check(response.isSuccessful); checkNotNull(response.body).string() }
    }

    private suspend fun readFinalEvents(): String {
        repeat(5) { attempt ->
            try { return readEvents() }
            catch (failure: java.io.IOException) { if (attempt == 4) throw failure; delay(250) }
        }
        error("Receiver evidence unavailable")
    }

    private fun focusLabel(device: UiDevice, label: String) {
        repeat(30) {
            val focused = device.findObject(By.focused(true))
            if (focused?.text?.contains(label) == true || focused?.findObject(By.textContains(label)) != null) {
                SystemClock.sleep(300)
                return
            }
            device.pressDPadDown()
            SystemClock.sleep(100)
        }
        error("D-pad did not reach $label")
    }

    private companion object { const val BASE = "http://10.0.2.2:8877" }
}
