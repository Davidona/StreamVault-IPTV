package com.streamvault.app.ui

import android.app.Application
import android.content.Context
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModelStore
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.StreamVaultDatabase
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.data.sync.ProviderSyncCommands
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.LegacyProvider
import com.streamvault.domain.model.PlaybackContentKind
import com.streamvault.domain.model.PlaybackHistory
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.repository.MovieRepository
import com.streamvault.domain.repository.ProviderRepository
import com.streamvault.feature.playback.player.PlayerViewModel
import com.streamvault.player.PlaybackState
import com.streamvault.player.PlayerEngine
import com.streamvault.app.MainActivity
import dagger.hilt.internal.GeneratedComponentManager
import java.io.File
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

/** Opt-in normal Movies UI acceptance for V12 sustained delivery shortage/restoration. */
@RunWith(AndroidJUnit4::class)
class V12BandwidthRuntimeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val device get() = UiDevice.getInstance(instrumentation)
    private val setup get() = File(app.filesDir, "v12-bandwidth-fixture-private.json")

    private fun enabled() = assumeTrue(
        InstrumentationRegistry.getArguments().getString("v12BandwidthFixture") == "true"
    )

    private fun component(name: String): Any {
        val generated = (app as GeneratedComponentManager<*>).generatedComponent()
        return (generated.javaClass.getDeclaredField(name).apply { isAccessible = true }
            .get(generated) as Provider<*>).get()!!
    }

    private fun await(label: String, timeout: Long = 45_000, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < end) {
            compose.waitForIdle()
            if (condition()) return
            SystemClock.sleep(100)
        }
        error("Timed out: $label")
    }

    private fun click(text: String) {
        compose.waitForIdle()
        if (text.startsWith("V12 ")) {
            compose.onNodeWithContentDescription(text).performSemanticsAction(SemanticsActions.OnClick) { it() }
        } else if (text == "Play") {
            val button = hasText("Play") or hasText("Resume from", substring = true)
            compose.waitUntil(10_000) { compose.onAllNodes(button).fetchSemanticsNodes().size == 1 }
            compose.onNode(button).performSemanticsAction(SemanticsActions.OnClick) { it() }
        } else {
            val node = device.wait(Until.findObject(By.textContains(text)), 5_000)
                ?: error("Missing UI: $text")
            val bounds = node.visibleBounds
            device.executeShellCommand("input mouse tap ${bounds.centerX()} ${bounds.centerY()}")
        }
        compose.waitForIdle()
    }

    private fun findPlayer(store: ViewModelStore): PlayerViewModel? {
        for (key in store.keys()) {
            val model = store[key] ?: continue
            if (model is PlayerViewModel) return model
            for (field in model.javaClass.declaredFields.filter { Map::class.java.isAssignableFrom(it.type) }) {
                val map = field.apply { isAccessible = true }.get(model) as? Map<*, *> ?: continue
                for (nested in map.values.filterIsInstance<ViewModelStore>()) findPlayer(nested)?.let { return it }
            }
        }
        return null
    }

    private fun getter(owner: Any, name: String): Any? = owner.javaClass.methods
        .single { it.name.substringBefore('$') == name && it.parameterCount == 0 }.invoke(owner)

    private fun control(rate: Long, label: String, reset: Boolean = false): JSONObject {
        val query = "rate=$rate&label=$label&reset=${if (reset) 1 else 0}"
        return JSONObject(java.net.URL("http://10.0.2.2:18786/control?$query").readText())
    }

    @Test fun provisionIsolatedMovies() {
        runBlocking {
        enabled()
        val providers = component("providerRepositoryImplProvider") as ProviderRepository
        val prefs = component("preferencesRepositoryProvider") as PreferencesRepository
        val movies = component("movieRepositoryImplProvider") as MovieRepository
        val original = providers.getActiveProvider().first()?.id ?: -1L
        val last = prefs.lastActiveProviderId.first() ?: -1L
        val restore = File(app.filesDir, "v12-bandwidth-restore.json")
        if (!setup.exists()) {
            val context = prefs.javaClass.getDeclaredField("context").apply { isAccessible = true }.get(prefs) as Context
            @Suppress("UNCHECKED_CAST")
            val store = prefs.javaClass.getDeclaredMethod("getDataStore", Context::class.java).apply { isAccessible = true }
                .invoke(prefs, context) as DataStore<Preferences>
            val snapshot = store.data.first()
            val saved = JSONObject()
            for (name in listOf("vod_variant_observations", "player_vod_track_global_preferences", "player_vod_track_preferences", "vod_duplicate_handling_mode")) {
                saved.put(name, snapshot[stringPreferencesKey(name)] ?: JSONObject.NULL)
            }
            restore.writeText(saved.toString())
        }
        check(restore.exists()) { "Original fixture preference snapshot required" }
        val id = if (setup.exists()) JSONObject(setup.readText()).getLong("id") else providers.addProvider(
            LegacyProvider(
                name = "V12 Bandwidth UI validation 20261007",
                type = ProviderType.XTREAM_CODES,
                serverUrl = "http://10.0.2.2:18786",
                username = "validation",
                password = "validation",
                maxConnections = 5,
                isActive = false,
                xtreamFastSyncEnabled = false,
            )
        ).getOrNull() ?: error("Fixture creation failed")
        check(providers.getProvider(id)?.name == "V12 Bandwidth UI validation 20261007")
        if (!setup.exists()) setup.writeText(JSONObject().put("id", id).put("active", original).put("last", last).toString())
        check(providers.refreshProviderData(id, force = true).isSuccess)
        val categories = withTimeout(60_000) { movies.getCategories(id).first { it.isNotEmpty() } }
        for (category in categories) movies.getMoviesByCategory(id, category.id).first()
        val sync = component("syncManagerProvider") as ProviderSyncCommands
        check(sync.processQueuedXtreamIndexJobs(id, section = ContentType.MOVIE, force = true).isSuccess)
        val rows = withTimeout(60_000) {
            movies.getMovies(id).first { candidates ->
                candidates.size == 3 && candidates.all { movie ->
                    val hydrated = movies.getMovieDetails(id, movie.id).getOrNull()
                    hydrated?.let { movies.getStreamInfo(it).getOrNull() }?.expirationTime
                        ?.let { it > System.currentTimeMillis() + 3_600_000 } == true
                }
            }
        }
        check(rows.map { it.name }.toSet() == setOf("V12 Adaptive Sustainable", "V12 Adaptive Unsustainable", "V12 Progressive Control"))
        check(providers.setActiveProvider(id).isSuccess)
        prefs.setLastActiveProviderId(id)
        prefs.setVodDuplicateHandlingMode(com.streamvault.domain.model.VodDuplicateHandlingMode.SHOW_ALL)
            Log.i("V12BandwidthFixture", "provisioned provider=$id movies=${rows.size}")
        }
    }

    @Test fun normalMoviesUiSustainedBandwidth() = runCase("sustainable", "V12 Adaptive Sustainable", adaptive = true)

    @Test fun normalMoviesUiUnsustainableBandwidth() = runCase("unsustainable", "V12 Adaptive Unsustainable", adaptive = true)

    @Test fun normalMoviesUiProgressiveBandwidth() = runCase("progressive", "V12 Progressive Control", adaptive = false)

    private fun runCase(case: String, title: String, adaptive: Boolean) {
        enabled()
        val requestedDuration = InstrumentationRegistry.getArguments().getString("v12DurationSeconds")?.toIntOrNull()?.coerceIn(360, 480) ?: 410
        val engine = component("provideMainPlayerEngineProvider") as PlayerEngine
        control(120_000_000, "$case-start", reset = true)
        click("Movies"); click(title); click("Play")
        lateinit var vm: PlayerViewModel
        await("player context") {
            var found = false
            instrumentation.runOnMainSync { findPlayer(compose.activity.viewModelStore)?.let { vm = it; found = true } }
            found
        }
        SystemClock.sleep(1_500)
        if (vm.resumePrompt.value.show) {
            if (compose.onAllNodes(hasText("Start Over")).fetchSemanticsNodes().isNotEmpty()) {
                compose.onNodeWithText("Start Over").performSemanticsAction(SemanticsActions.OnClick) { it() }
            } else {
                compose.onNodeWithText("Resume").performSemanticsAction(SemanticsActions.OnClick) { it() }
            }
        }
        await("first frame") { engine.isPlaying.value && engine.playbackState.value == PlaybackState.READY && engine.playerStats.value.ttffMs > 0 }
        device.wait(Until.findObject(By.text("Start Over")), 5_000)?.let {
            compose.onNodeWithText("Start Over").performSemanticsAction(SemanticsActions.OnClick) { it() }
        } ?: device.wait(Until.findObject(By.text("Resume")), 2_000)?.let {
            compose.onNodeWithText("Resume").performSemanticsAction(SemanticsActions.OnClick) { it() }
        }
        await("resume dialog dismissed") { !vm.resumePrompt.value.show && device.findObject(By.text("Resume Playback")) == null }
        await("resolving overlay cleared") { device.findObject(By.textContains("Resolving playback")) == null }
        val source = engine.javaClass.getDeclaredField("lastStreamInfo").apply { isAccessible = true }.get(engine) as StreamInfo
        assertThat(source.playbackContentKind).isEqualTo(PlaybackContentKind.ON_DEMAND)
        val logicalTitle = getter(vm, "getCurrentTitle")
        val logicalUrl = getter(vm, "getCurrentStreamUrl")
        val samples = JSONArray()
        val started = SystemClock.elapsedRealtime()
        var slowApplied = false
        var restored = false
        val end = started + requestedDuration * 1_000L
        while (SystemClock.elapsedRealtime() < end) {
            compose.waitForIdle()
            val elapsed = SystemClock.elapsedRealtime() - started
            if (elapsed >= 30_000 && !slowApplied) {
                control(if (case == "unsustainable") 600_000 else 3_200_000, "$case-shortage")
                slowApplied = true
            }
            if (elapsed >= 350_000 && !restored) {
                control(120_000_000, "$case-restored")
                restored = true
            }
            val stats = engine.playerStats.value
            val sample = JSONObject()
                .put("elapsedMs", elapsed)
                .put("positionMs", engine.currentPosition.value)
                .put("state", engine.playbackState.value.name)
                .put("playing", engine.isPlaying.value)
                .put("height", engine.videoFormat.value.height)
                .put("selectedBitrate", engine.videoFormat.value.bitrate)
                .put("bufferMs", stats.bufferedDurationMs)
                .put("rebuffers", stats.rebufferCount)
                .put("droppedFrames", stats.droppedFrames)
                .put("bandwidthEstimate", stats.bandwidthEstimate)
                .put("pssKb", Debug.getPss())
            instrumentation.runOnMainSync {
                val player = engine.javaClass.getDeclaredField("exoPlayer").apply { isAccessible = true }.get(engine) as ExoPlayer
                sample.put("timelineLive", player.isCurrentMediaItemLive)
                    .put("timelineDynamic", player.isCurrentMediaItemDynamic)
                    .put("videoWidth", player.videoFormat?.width)
                    .put("videoHeight", player.videoFormat?.height)
                    .put("trackOverrideCount", player.trackSelectionParameters.overrides.values.count { it.type == C.TRACK_TYPE_VIDEO })
                player.videoDecoderCounters?.let { counters ->
                    counters.ensureUpdated()
                    sample.put("renderedBuffers", counters.renderedOutputBufferCount)
                        .put("decoderDroppedBuffers", counters.droppedBufferCount)
                        .put("decoderSkippedBuffers", counters.skippedOutputBufferCount)
                }
            }
            samples.put(sample)
            File(app.filesDir, "v12-$case.json").writeText(samples.toString())
            check(engine.playbackState.value != PlaybackState.ERROR) { "V12 $case entered ERROR" }
            SystemClock.sleep(1_000)
        }
        check(slowApplied && restored) { "V12 phases were not both applied" }
        assertThat(samples.length()).isAtLeast(360)
        assertThat(getter(vm, "getCurrentTitle")).isEqualTo(logicalTitle)
        assertThat(getter(vm, "getCurrentStreamUrl")).isEqualTo(logicalUrl)
        val finalStats = engine.playerStats.value
        val history = vm.javaClass.methods.single { it.name.substringBefore('$') == "buildPlaybackHistorySnapshot" && it.parameterCount == 2 }
            .invoke(vm, engine.currentPosition.value, engine.duration.value) as PlaybackHistory
        assertThat(history.streamUrl).isEqualTo(logicalUrl)
        assertThat(engine.availableVideoTracks.value.count { it.isSelected }).isAtLeast(1)
        val logs = device.executeShellCommand("logcat -d -s Media3PlayerEngine:I")
        assertThat(logs).doesNotContain("fatal-error")
        assertThat(logs).doesNotContain("buffer-policy promote")
        val result = JSONObject()
            .put("case", case)
            .put("requestedDurationSeconds", requestedDuration)
            .put("sampleCount", samples.length())
            .put("firstFrameMs", engine.playerStats.value.ttffMs)
            .put("finalState", engine.playbackState.value.name)
            .put("finalIsPlaying", engine.isPlaying.value)
            .put("finalPositionMs", engine.currentPosition.value)
            .put("finalDurationMs", engine.duration.value)
            .put("finalBufferMs", finalStats.bufferedDurationMs)
            .put("finalRebufferCount", finalStats.rebufferCount)
            .put("finalDroppedFrames", finalStats.droppedFrames)
            .put("finalVideoBitrate", finalStats.videoBitrate)
            .put("finalBandwidthEstimate", finalStats.bandwidthEstimate)
            .put("finalHeight", finalStats.height)
            .put("logicalTitle", logicalTitle)
            .put("logicalUrl", logicalUrl)
            .put("historyContentId", history.contentId)
            .put("selectedTracks", JSONArray(engine.availableVideoTracks.value.map { JSONObject().put("id", it.id).put("name", it.name).put("selected", it.isSelected) }))
            .put("samples", samples)
        File(app.filesDir, "v12-$case.json").writeText(result.toString())
        Log.i("V12BandwidthFixture", "complete case=$case durationMs=${SystemClock.elapsedRealtime() - started} " +
            "samples=${samples.length()} finalState=${engine.playbackState.value} finalPlaying=${engine.isPlaying.value} " +
            "rebuffers=${finalStats.rebufferCount} drops=${finalStats.droppedFrames} historyId=${history.contentId}")
    }

    @Test fun cleanupOnlyIsolatedMovies() {
        runBlocking {
        enabled()
        val original = JSONObject(setup.readText())
        val providers = component("providerRepositoryImplProvider") as ProviderRepository
        val prefs = component("preferencesRepositoryProvider") as PreferencesRepository
        val id = original.getLong("id")
        if (providers.getProvider(id) != null) check(providers.deleteProvider(id).isSuccess)
        if (original.getLong("active") > 0L) check(providers.setActiveProvider(original.getLong("active")).isSuccess)
        prefs.setLastActiveProviderId(original.getLong("last"))
        val context = prefs.javaClass.getDeclaredField("context").apply { isAccessible = true }.get(prefs) as Context
        @Suppress("UNCHECKED_CAST")
        val store = prefs.javaClass.getDeclaredMethod("getDataStore", Context::class.java).apply { isAccessible = true }
            .invoke(prefs, context) as DataStore<Preferences>
        val saved = JSONObject(File(app.filesDir, "v12-bandwidth-restore.json").readText())
        store.edit { current ->
            current.remove(stringPreferencesKey("last_split_catalog_type_$id"))
            for (name in listOf("vod_variant_observations", "player_vod_track_global_preferences", "player_vod_track_preferences", "vod_duplicate_handling_mode")) {
                val key = stringPreferencesKey(name)
                if (saved.isNull(name)) current.remove(key) else current[key] = saved.getString(name)
            }
        }
        check(File(app.filesDir, "v12-bandwidth-restore.json").delete())
        check(setup.delete())
            for (case in listOf("sustainable", "unsustainable", "progressive")) File(app.filesDir, "v12-$case.json").delete()
        }
    }
}
