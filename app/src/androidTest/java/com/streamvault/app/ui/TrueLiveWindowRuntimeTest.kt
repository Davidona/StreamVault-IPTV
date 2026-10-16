package com.streamvault.app.ui

import android.app.Application
import android.util.Log
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModelStore
import androidx.media3.common.C
import androidx.media3.common.Player as Media3Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.PlaybackStats
import androidx.media3.exoplayer.analytics.PlaybackStatsListener
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.google.common.truth.Truth.assertThat
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.data.sync.ProviderSyncCommands
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.LegacyProvider
import com.streamvault.domain.model.LiveStreamFormatMode
import com.streamvault.domain.model.PlaybackContentKind
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.repository.ChannelRepository
import com.streamvault.domain.repository.ProviderRepository
import com.streamvault.feature.playback.player.PlayerViewModel
import com.streamvault.player.PlaybackState
import com.streamvault.player.PlayerEngine
import dagger.hilt.internal.GeneratedComponentManager
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Provider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in normal Live TV validation of a genuine dynamic HLS window expiry. */
@RunWith(AndroidJUnit4::class)
class TrueLiveWindowRuntimeTest {
    @get:Rule val compose = createAndroidComposeRule<com.streamvault.app.MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val device get() = UiDevice.getInstance(instrumentation)
    private val setup get() = File(app.filesDir, "v5-dynamic-live-fixture-private.json")
    private val output get() = File(app.filesDir, "v5-dynamic-live-private")

    private fun enabled() = assumeTrue(InstrumentationRegistry.getArguments().getString("v5Fixture") == "true")

    private fun component(name: String): Any {
        val generated = (app as GeneratedComponentManager<*>).generatedComponent()
        return (generated.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(generated) as Provider<*>).get()!!
    }

    private fun await(label: String, timeoutMs: Long = 45_000, condition: () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            compose.waitForIdle()
            if (condition()) return
            android.os.SystemClock.sleep(100)
        }
        device.takeScreenshot(File(app.filesDir, "v5-dynamic-live-driver.png"))
        device.dumpWindowHierarchy(File(app.filesDir, "v5-dynamic-live-driver.xml"))
        error("Timed out: $label")
    }

    private fun click(text: String) {
        compose.waitForIdle()
        val node = device.wait(Until.findObject(By.textContains(text)), 8_000)
            ?: error("Missing UI text: $text")
        val bounds = node.visibleBounds
        device.executeShellCommand("input mouse tap ${bounds.centerX()} ${bounds.centerY()}")
        compose.waitForIdle()
    }

    private fun findPlayers(store: ViewModelStore): List<PlayerViewModel> {
        val found = mutableListOf<PlayerViewModel>()
        for (key in store.keys()) {
            val model = store[key] ?: continue
            if (model is PlayerViewModel) found += model
            for (field in model.javaClass.declaredFields.filter { Map::class.java.isAssignableFrom(it.type) }) {
                val values = field.apply { isAccessible = true }.get(model) as? Map<*, *> ?: continue
                values.values.filterIsInstance<ViewModelStore>().forEach { found += findPlayers(it) }
            }
        }
        return found.distinct()
    }

    private fun getter(owner: Any, name: String): Any? = owner.javaClass.methods
        .single { it.name.substringBefore('$') == name && it.parameterCount == 0 }.invoke(owner)

    private fun control(query: String): JSONObject = JSONObject(java.net.URL("http://10.0.2.2:18785/control?$query").readText())
    private fun events(): JSONObject = JSONObject(java.net.URL("http://10.0.2.2:18785/events").readText())

    private fun liveOffset(engine: PlayerEngine): Long = AtomicLong(C.TIME_UNSET.toLong()).also { result ->
        instrumentation.runOnMainSync {
            val player = engine.javaClass.getDeclaredField("exoPlayer").apply { isAccessible = true }.get(engine) as? Media3Player
            if (player?.isCommandAvailable(Media3Player.COMMAND_GET_CURRENT_MEDIA_ITEM) == true) {
                result.set(player.currentLiveOffset)
            }
        }
    }.get()

    private fun playerPosition(engine: PlayerEngine): Long = AtomicLong(C.TIME_UNSET.toLong()).also { result ->
        instrumentation.runOnMainSync {
            val player = engine.javaClass.getDeclaredField("exoPlayer").apply { isAccessible = true }
                .get(engine) as? Media3Player
            if (player != null) result.set(player.currentPosition)
        }
    }.get()

    private fun playerError(engine: PlayerEngine): androidx.media3.common.PlaybackException? {
        var result: androidx.media3.common.PlaybackException? = null
        instrumentation.runOnMainSync {
            result = engine.javaClass.getDeclaredField("exoPlayer").apply { isAccessible = true }
                .get(engine).let { it as? ExoPlayer }?.playerError
        }
        return result
    }

    private fun hash(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    private fun engineFor(title: String): PlayerViewModel {
        var result: PlayerViewModel? = null
        await("player context $title") {
            instrumentation.runOnMainSync {
                findPlayers(compose.activity.viewModelStore).firstOrNull { getter(it, "getCurrentTitle") == title }?.let {
                    result = it
                }
            }
            result != null
        }
        return checkNotNull(result)
    }

    private fun openChannel(name: String): PlayerViewModel {
        await("Live TV route") { device.findObject(By.textContains("Live TV")) != null }
        click("Live TV")
        await("V5 category") { compose.onAllNodesWithText("V5 Dynamic Live", substring = true).fetchSemanticsNodes().isNotEmpty() }
        click("V5 Dynamic Live")
        await("channel row $name") { compose.onAllNodesWithContentDescription(name, substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription(name, substring = true).performSemanticsAction(SemanticsActions.OnClick) { it() }
        android.os.SystemClock.sleep(800)
        if (compose.onAllNodesWithContentDescription(name, substring = true).fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithContentDescription(name, substring = true).performSemanticsAction(SemanticsActions.OnClick) { it() }
        }
        val vm = engineFor(name)
        await("dynamic first frame $name") {
            val engine = vm.playerEngine
            engine.isPlaying.value && engine.playbackState.value == PlaybackState.READY &&
                engine.playerStats.value.ttffMs > 0 && liveOffset(engine) != C.TIME_UNSET.toLong()
        }
        instrumentation.runOnMainSync {
            val player = vm.playerEngine.javaClass.getDeclaredField("exoPlayer").apply { isAccessible = true }
                .get(vm.playerEngine) as ExoPlayer
            check(player.isCurrentMediaItemLive) { "V5 source was not live" }
            check(player.isCurrentMediaItemDynamic) { "V5 source was not dynamic" }
        }
        return vm
    }

    private fun capture(engine: PlayerEngine, label: String, durationFrames: Int = 61): JSONObject {
        val folder = File(output, label).apply { mkdirs() }
        val hashes = JSONArray()
        val samples = JSONArray()
        val started = android.os.SystemClock.elapsedRealtime()
        repeat(durationFrames) { index ->
            compose.waitForIdle()
            val frame = File(folder, "frame_%02d.png".format(index))
            check(device.takeScreenshot(frame)) { "Could not capture $frame" }
            hashes.put(hash(frame))
            samples.put(JSONObject().put("frame", index).put("elapsedMs", android.os.SystemClock.elapsedRealtime() - started)
                .put("positionMs", playerPosition(engine)).put("state", engine.playbackState.value.name)
                .put("playing", engine.isPlaying.value).put("liveOffsetMs", liveOffset(engine))
                .put("speed", engine.playbackSpeed.value).put("bufferedMs", engine.playerStats.value.bufferedDurationMs)
                .put("rebufferCount", engine.playerStats.value.rebufferCount).put("droppedFrames", engine.playerStats.value.droppedFrames)
                .put("width", engine.playerStats.value.width).put("height", engine.playerStats.value.height))
            if (index < durationFrames - 1) android.os.SystemClock.sleep(2_000)
        }
        val values = (0 until hashes.length()).map { hashes.getString(it) }
        val report = JSONObject().put("label", label).put("frameCount", durationFrames)
            .put("durationMs", android.os.SystemClock.elapsedRealtime() - started)
            .put("uniqueHashes", values.toSet().size).put("adjacentChanges", values.zipWithNext().count { it.first != it.second })
            .put("positionStartMs", samples.getJSONObject(0).getLong("positionMs"))
            .put("positionEndMs", samples.getJSONObject(samples.length() - 1).getLong("positionMs"))
            .put("hashes", hashes).put("samples", samples)
        File(folder, "summary.json").writeText(report.toString(2))
        return report
    }

    private fun ensureProvisioned() = runBlocking {
        val providers = component("providerRepositoryImplProvider") as ProviderRepository
        val prefs = component("preferencesRepositoryProvider") as PreferencesRepository
        val channels = component("channelRepositoryImplProvider") as ChannelRepository
        val original = providers.getActiveProvider().first()?.id
        val last = prefs.lastActiveProviderId.first()
        val id = if (setup.exists()) JSONObject(setup.readText()).getLong("id") else providers.addProvider(
            LegacyProvider(name = "V5 Dynamic Live validation 20261007", type = ProviderType.XTREAM_CODES,
                serverUrl = "http://10.0.2.2:18785", username = "v5-fixture", password = "v5-fixture", maxConnections = 5,
                allowedOutputFormats = listOf("m3u8"), preferredLiveStreamFormat = LiveStreamFormatMode.HLS,
                isActive = false, xtreamFastSyncEnabled = false)
        ).getOrNull() ?: error("V5 fixture provider creation failed")
        check(providers.getProvider(id)?.name == "V5 Dynamic Live validation 20261007")
        if (!setup.exists()) setup.writeText(JSONObject().put("id", id)
            .put("active", original ?: JSONObject.NULL).put("last", last ?: JSONObject.NULL).toString(2))
        check(providers.refreshProviderData(id, force = true).isSuccess)
        val sync = component("syncManagerProvider") as ProviderSyncCommands
        check(sync.processQueuedXtreamIndexJobs(id, section = ContentType.LIVE, force = true).isSuccess)
        val rows = withTimeout(60_000) { channels.getChannels(id).first { it.size == 2 } }
        check(rows.map { it.name }.toSet() == setOf("V5 Dynamic Live Alpha", "V5 Dynamic Live Beta"))
        check(providers.setActiveProvider(id).isSuccess)
        prefs.setLastActiveProviderId(id)
        println("V5 provisioned provider=$id channels=${rows.size}")
    }

    @Test
    fun provisionIsolatedDynamicLive() = runBlocking {
        enabled()
        ensureProvisioned()
    }

    @Test
    fun dynamicLiveWindowExpiryThroughNormalUi() {
        enabled()
        if (!setup.exists()) ensureProvisioned()
        output.deleteRecursively()
        output.mkdirs()
        control("reset=1")
        val alpha = openChannel("V5 Dynamic Live Alpha")
        val alphaEngine = alpha.playerEngine
        val alphaStats = PlaybackStatsListener(true, null)
        instrumentation.runOnMainSync {
            alphaEngine.javaClass.getDeclaredField("exoPlayer").apply { isAccessible = true }
                .get(alphaEngine).let { it as ExoPlayer }.addAnalyticsListener(alphaStats)
        }
        val alphaFirstOffset = liveOffset(alphaEngine)
        val alphaPrepareBefore = device.executeShellCommand("logcat -d -s Media3PlayerEngine:I")
            .lineSequence().count { "prepare resolvedStreamType=HLS" in it }
        android.os.SystemClock.sleep(10_000)
        val alphaHealthyOffset = liveOffset(alphaEngine)
        val jump = control("jump=1&channel=1001")
        val beforePosition = playerPosition(alphaEngine)
        var sawBehind = false
        var recoveredOffset = C.TIME_UNSET.toLong()
        var recoveredPosition = beforePosition
        val deadline = android.os.SystemClock.elapsedRealtime() + 60_000
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            val logs = device.executeShellCommand("logcat -d -s Media3PlayerEngine:I")
            val error = playerError(alphaEngine)
            sawBehind = sawBehind || logs.contains("category=LIVE_WINDOW") ||
                error?.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW
            if (sawBehind && alphaEngine.isPlaying.value && alphaEngine.playbackState.value == PlaybackState.READY) {
                recoveredOffset = liveOffset(alphaEngine)
                recoveredPosition = playerPosition(alphaEngine)
                if (recoveredOffset != C.TIME_UNSET.toLong() && recoveredOffset in 0..20_000 && recoveredPosition > beforePosition + 2_000) break
            }
            android.os.SystemClock.sleep(500)
        }
        check(sawBehind) { "Dynamic playlist jump did not produce behind-live-window evidence" }
        check(alphaEngine.isPlaying.value && alphaEngine.playbackState.value == PlaybackState.READY)
        check(recoveredOffset in 0..20_000) { "Recovery did not target the live default: $recoveredOffset" }
        check(recoveredPosition >= 0L) { "Recovery position was unavailable: $recoveredPosition" }
        val alphaReport = capture(alphaEngine, "alpha-window-recovery")

        device.pressBack()
        await("V5 category after Alpha") { compose.onAllNodesWithText("V5 Dynamic Live", substring = true).fetchSemanticsNodes().isNotEmpty() }
        control("reset=1")
        val beta = openChannel("V5 Dynamic Live Beta")
        val betaEngine = beta.playerEngine
        val betaStats = PlaybackStatsListener(true, null)
        instrumentation.runOnMainSync {
            betaEngine.javaClass.getDeclaredField("exoPlayer").apply { isAccessible = true }
                .get(betaEngine).let { it as ExoPlayer }.addAnalyticsListener(betaStats)
        }
        val betaReport = capture(betaEngine, "beta-dynamic-control")

        val logs = device.executeShellCommand("logcat -d -v time")
        val session = device.executeShellCommand("dumpsys media_session")
        val prepareCount = logs.lineSequence().count { "prepare resolvedStreamType=HLS" in it }
        val report = JSONObject().put("alphaFirstLiveOffsetMs", alphaFirstOffset)
            .put("alphaHealthyLiveOffsetMs", alphaHealthyOffset).put("alphaRecoveredLiveOffsetMs", recoveredOffset)
            .put("alphaPrepareCountBeforeJump", alphaPrepareBefore).put("prepareCountTotal", prepareCount)
            .put("windowJump", jump).put("alpha", alphaReport).put("beta", betaReport)
            .put("alphaRebufferCount", alphaEngine.playerStats.value.rebufferCount)
            .put("alphaRebufferDurationMs", alphaStats.combinedPlaybackStats.totalRebufferTimeMs)
            .put("betaRebufferCount", betaEngine.playerStats.value.rebufferCount)
            .put("betaRebufferDurationMs", betaStats.combinedPlaybackStats.totalRebufferTimeMs)
            .put("logs", logs).put("session", session)
        File(output, "v5-final-report.json").writeText(report.toString(2))
        File(output, "v5-session.txt").writeText(session)
        File(output, "v5-logcat.txt").writeText(logs)
        val relevant = logs.lineSequence().filter {
            it.contains("prepare resolvedStreamType") || it.contains("LIVE_WINDOW") ||
                it.contains("fatal-error") || it.contains("Player stuck") || it.contains("MPEG_TS_LIVE") ||
                it.contains("first-frame-success") || it.contains("read-progress")
        }.joinToString("\n")
        File(output, "v5-relevant-log.txt").writeText(relevant)
        assertThat(prepareCount).isAtMost(3)
        assertThat(logs).doesNotContain("MPEG_TS_LIVE")
        assertThat(logs).doesNotContain("live-ts-fallback")
        assertThat(logs).doesNotContain("fatal-error")
        assertThat(logs).doesNotContain("Player stuck")
        assertThat(session).contains("com.streamvault.app.debug")
        Log.i("V5DynamicLive", "complete behindLiveWindow=$sawBehind recoveredOffsetMs=$recoveredOffset prepares=$prepareCount " +
            "alphaRebuffers=${alphaEngine.playerStats.value.rebufferCount} betaRebuffers=${betaEngine.playerStats.value.rebufferCount}")
        // Leave the report readable through `run-as` before the connected-test
        // teardown removes the app data and isolated fixture provider.
        android.os.SystemClock.sleep(10_000)
        cleanupIsolatedDynamicLive()
    }

    @Test
    fun cleanupIsolatedDynamicLive() = runBlocking {
        enabled()
        val saved = JSONObject(setup.readText())
        val providers = component("providerRepositoryImplProvider") as ProviderRepository
        val prefs = component("preferencesRepositoryProvider") as PreferencesRepository
        val id = saved.getLong("id")
        check(providers.getProvider(id)?.name == "V5 Dynamic Live validation 20261007")
        check(providers.deleteProvider(id).isSuccess)
        if (!saved.isNull("active")) check(providers.setActiveProvider(saved.getLong("active")).isSuccess)
        if (!saved.isNull("last")) prefs.setLastActiveProviderId(saved.getLong("last"))
        check(setup.delete())
        File(app.filesDir, "v5-dynamic-live-driver.png").delete()
        File(app.filesDir, "v5-dynamic-live-driver.xml").delete()
        Unit
    }
}
