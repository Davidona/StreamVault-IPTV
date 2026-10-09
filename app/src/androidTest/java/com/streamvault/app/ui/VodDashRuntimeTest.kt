package com.streamvault.app.ui

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performSemanticsAction
import androidx.lifecycle.ViewModelStore
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.google.common.truth.Truth.assertThat
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.data.local.StreamVaultDatabase
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

/** Opt-in normal Movies UI validation using an isolated provider and private local media. */
@RunWith(AndroidJUnit4::class)
class VodDashRuntimeTest {
    @get:Rule val compose = createAndroidComposeRule<com.streamvault.app.MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val device get() = UiDevice.getInstance(instrumentation)
    private val setup get() = File(app.filesDir, "vod-dash-fixture-private.json")
    private fun enabled() = assumeTrue(InstrumentationRegistry.getArguments().getString("vodDashFixture") == "true")
    private fun component(name: String): Any {
        val c = (app as GeneratedComponentManager<*>).generatedComponent()
        return (c.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(c) as Provider<*>).get()!!
    }
    private fun await(label: String, timeout: Long = 45_000, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < end) {
            compose.waitForIdle()
            if (condition()) return
            SystemClock.sleep(100)
        }
        if (!condition()) {
            device.takeScreenshot(File(app.filesDir, "vod-dash-driver.png"))
            device.dumpWindowHierarchy(File(app.filesDir, "vod-dash-driver.xml"))
            error("Timed out: $label")
        }
    }
    private fun click(text: String) {
        compose.waitForIdle()
        if (text.startsWith("DASH ")) {
            compose.onNodeWithContentDescription(text).performSemanticsAction(SemanticsActions.OnClick) { it() }
        } else if (text == "Play") {
            // Movie facts can put either Play or Resume below the visible TV viewport.
            val button = hasText("Play") or hasText("Resume from", substring = true)
            compose.waitUntil(10_000) { compose.onAllNodes(button).fetchSemanticsNodes().size == 1 }
            compose.onNode(button).performSemanticsAction(SemanticsActions.OnClick) { it() }
        } else {
            val node = device.wait(Until.findObject(By.textContains(text)), 5_000)
                ?: (if (text == "Play") device.wait(Until.findObject(By.textStartsWith("Resume from")), 2_000) else null)
                ?: error("Missing UI: $text")
            val b = node.visibleBounds
            device.executeShellCommand("input mouse tap ${b.centerX()} ${b.centerY()}")
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
    private fun control(rate: Long) = java.net.URL("http://10.0.2.2:18770/control?rate=$rate").readText()

    @Test fun provisionIsolatedMovies() = runBlocking {
        enabled()
        val providers = component("providerRepositoryImplProvider") as ProviderRepository
        val prefs = component("preferencesRepositoryProvider") as PreferencesRepository
        val movies = component("movieRepositoryImplProvider") as MovieRepository
        val original = providers.getActiveProvider().first()?.id ?: -1L
        val last = prefs.lastActiveProviderId.first() ?: -1L
        val restore = File(app.filesDir, "vod-dash-restore.json")
        if (!setup.exists()) {
            val context = prefs.javaClass.getDeclaredField("context").apply { isAccessible = true }.get(prefs) as Context
            @Suppress("UNCHECKED_CAST")
            val store = prefs.javaClass.getDeclaredMethod("getDataStore", Context::class.java).apply { isAccessible = true }
                .invoke(prefs, context) as DataStore<Preferences>
            val saved = store.data.first()[stringPreferencesKey("vod_variant_observations")]
            restore.writeText(JSONObject().put("vod_variant_observations", saved ?: JSONObject.NULL).toString())
        }
        check(restore.exists()) { "Original fixture preference snapshot required" }
        val id = if (setup.exists()) JSONObject(setup.readText()).getLong("id") else providers.addProvider(LegacyProvider(name = "DASH UI validation 20261003", type = ProviderType.XTREAM_CODES,
            serverUrl = "http://10.0.2.2:18770", username = "validation", password = "validation", maxConnections = 5,
            isActive = false, xtreamFastSyncEnabled = false)).getOrNull() ?: error("Fixture creation failed")
        check(providers.getProvider(id)?.name == "DASH UI validation 20261003")
        if (!setup.exists()) setup.writeText(JSONObject().put("id", id).put("active", original).put("last", last).toString())
        check(providers.refreshProviderData(id, force = true).isSuccess)
        val categories = withTimeout(60_000) { movies.getCategories(id).first { it.isNotEmpty() } }
        for (category in categories) movies.getMoviesByCategory(id, category.id).first()
        // Fixture provisioning is separate from background-worker acceptance. The worker can
        // defer on the emulator's transient low-memory flag; import only this fixture section.
        val sync = component("syncManagerProvider") as ProviderSyncCommands
        check(sync.processQueuedXtreamIndexJobs(id, section = ContentType.MOVIE, force = true).isSuccess)
        // Refresh/import is asynchronous: an existing catalogue can still have three stale rows.
        val rows = withTimeout(60_000) {
            movies.getMovies(id).first { candidates ->
                candidates.size == 3 && candidates.all { movie ->
                    val hydrated = movies.getMovieDetails(id, movie.id).getOrNull()
                    val resolved = hydrated?.let { movies.getStreamInfo(it).getOrNull() }
                    resolved?.expirationTime?.let { it > System.currentTimeMillis() + 3_600_000 } == true
                }
            }
        }
        check(rows.all { it.name.startsWith("DASH ") })
        check(providers.setActiveProvider(id).isSuccess)
        prefs.setLastActiveProviderId(id)
        val remaining = rows.map { movies.getStreamInfo(it).getOrNull()!!.expirationTime!! - System.currentTimeMillis() }
        Log.i("VodDashFixture", "provisioned provider=$id movies=${rows.size} minimumResolvedLifetimeMs=${remaining.min()}")
        Unit
    }

    @Test fun normalMoviesUiPlayback() {
        enabled()
        val case = InstrumentationRegistry.getArguments().getString("vodDashCase") ?: "adaptive"
        check(case in setOf("adaptive", "direct4k", "same1080"))
        val title = when (case) { "direct4k" -> "DASH Direct 4K UI Fixture"; "same1080" -> "DASH Same 1080 UI Fixture"; else -> "DASH Adaptive UI Fixture" }
        val engine = component("provideMainPlayerEngineProvider") as PlayerEngine
        control(if (case == "adaptive") 4_000_000 else 120_000_000)
        click("Movies"); click(title); click("Play")
        lateinit var vm: PlayerViewModel
        await("player context") { var found = false; instrumentation.runOnMainSync { findPlayer(compose.activity.viewModelStore)?.let { vm = it; found = true } }; found }
        SystemClock.sleep(1_500)
        if (vm.resumePrompt.value.show) compose.onNodeWithText("Resume").performSemanticsAction(SemanticsActions.OnClick) { it() }
        await("first frame") { engine.isPlaying.value && engine.playbackState.value == PlaybackState.READY && engine.playerStats.value.ttffMs > 0 }
        // History/detail resolution can publish a resume prompt after first frame.
        device.wait(Until.findObject(By.text("Resume")), 5_000)?.let {
            compose.onNodeWithText("Resume").performSemanticsAction(SemanticsActions.OnClick) { action -> action() }
        }
        await("resume dialog dismissed") { !vm.resumePrompt.value.show && device.findObject(By.text("Resume Playback")) == null }
        await("playback after resume dialog") { engine.isPlaying.value && engine.playbackState.value == PlaybackState.READY }
        await("resolving overlay cleared") { device.findObject(By.textContains("Resolving playback")) == null }
        if (engine.duration.value > 195_000 && engine.duration.value - engine.currentPosition.value < 195_000) {
            val resumed = engine.currentPosition.value
            instrumentation.runOnMainSync { engine.seekTo(10_000); engine.play() }
            await("enough finite content for sustained capture") {
                engine.currentPosition.value in 9_000..20_000 && engine.isPlaying.value && engine.playbackState.value == PlaybackState.READY
            }
            Log.i("VodDashFixture", "capture-position-normalized resumedMs=$resumed targetMs=10000 reason=finite-window")
        }
        fun source() = engine.javaClass.getDeclaredField("lastStreamInfo").apply { isAccessible = true }.get(engine) as StreamInfo
        assertThat(source().playbackContentKind).isEqualTo(PlaybackContentKind.ON_DEMAND)
        val logicalTitle = getter(vm, "getCurrentTitle")
        val logicalUrl = getter(vm, "getCurrentStreamUrl")
        Log.i("VodDashFixture", "capture-ready case=$case intent=${source().playbackContentKind} ttffMs=${engine.playerStats.value.ttffMs} resumeDialog=false resolvingOverlay=false")
        val samples = JSONArray()
        // Leave room for slow 4K ADB captures while keeping the 240-second fixture finite.
        val end = SystemClock.elapsedRealtime() + 180_000L
        val began = SystemClock.elapsedRealtime()
        var high = false; var low = false; var downAfterHigh = false
        var fasterApplied = false; var slowerApplied = false
        while (SystemClock.elapsedRealtime() < end) {
            compose.waitForIdle()
            check(device.findObject(By.text("Resume Playback")) == null)
            val elapsed = SystemClock.elapsedRealtime() - began
            if (case == "adaptive" && elapsed >= 20_000 && !fasterApplied) {
                control(120_000_000); fasterApplied = true
            }
            if (case == "adaptive" && elapsed >= 80_000 && !slowerApplied) {
                control(4_000_000); slowerApplied = true
            }
            val stats = engine.playerStats.value
            val h = engine.videoFormat.value.height
            if (h in 1..720) { low = true; if (high) downAfterHigh = true }
            if (h >= 1080) high = true
            val sample = JSONObject().put("elapsedMs", elapsed).put("positionMs", engine.currentPosition.value)
                .put("state", engine.playbackState.value.name).put("height", h).put("bufferMs", stats.bufferedDurationMs)
                .put("rebuffers", stats.rebufferCount).put("drops", stats.droppedFrames)
                .put("javaAllocatedBytes", Debug.getRuntimeStat("art.gc.bytes-allocated"))
                .put("pssKb", Debug.getPss())
            instrumentation.runOnMainSync {
                val player = engine.javaClass.getDeclaredField("exoPlayer").apply { isAccessible = true }.get(engine) as ExoPlayer
                assertThat(player.isCurrentMediaItemLive).isFalse()
                assertThat(player.isCurrentMediaItemDynamic).isFalse()
                sample.put("timelineLive", player.isCurrentMediaItemLive).put("timelineDynamic", player.isCurrentMediaItemDynamic)
                    .put("videoWidth", player.videoFormat?.width).put("videoHeight", player.videoFormat?.height)
                player.videoDecoderCounters?.let { counters ->
                    counters.ensureUpdated()
                    sample.put("renderedBuffers", counters.renderedOutputBufferCount)
                        .put("decoderDroppedBuffers", counters.droppedBufferCount)
                        .put("decoderSkippedBuffers", counters.skippedOutputBufferCount)
                        .put("decoderCounterInstance", System.identityHashCode(counters))
                }
            }
            samples.put(sample)
            File(app.filesDir, "vod-dash-$case.json").writeText(samples.toString())
            check(engine.playbackState.value != PlaybackState.ERROR)
            SystemClock.sleep(1_000)
        }
        if (case == "adaptive") {
            assertThat(fasterApplied).isTrue(); assertThat(slowerApplied).isTrue()
            assertThat(low).isTrue(); assertThat(high).isTrue(); assertThat(downAfterHigh).isTrue()
        }
        else assertThat(engine.videoFormat.value.height).isEqualTo(if (case == "direct4k") 2160 else 1080)
        control(120_000_000)
        instrumentation.runOnMainSync { engine.seekTo(60_000); engine.play() }
        await("seek resume") { engine.currentPosition.value in 59_000..70_000 && engine.isPlaying.value && engine.playbackState.value == PlaybackState.READY }
        val seekPosition = engine.currentPosition.value
        await("position progresses after seek") { engine.currentPosition.value >= seekPosition + 3_000 }
        assertThat(getter(vm, "getCurrentTitle")).isEqualTo(logicalTitle)
        assertThat(getter(vm, "getCurrentStreamUrl")).isEqualTo(logicalUrl)
        val history = vm.javaClass.methods.single { it.name.substringBefore('$') == "buildPlaybackHistorySnapshot" && it.parameterCount == 2 }
            .invoke(vm, engine.currentPosition.value, engine.duration.value) as PlaybackHistory
        assertThat(history.streamUrl).isEqualTo(logicalUrl)
        assertThat(history.resumePositionMs).isAtLeast(59_000)
        val renewal = Class.forName("com.streamvault.feature.playback.player.PlayerLifecycleActionsKt").methods.single { it.name == "startTokenRenewalMonitoring" && it.parameterCount == 3 }
        instrumentation.runOnMainSync { renewal.invoke(null, vm, System.currentTimeMillis() + 1000, null) }
        SystemClock.sleep(2_500)
        assertThat(device.executeShellCommand("logcat -d -s PlayerVM:I")).contains("token-renewal request-changed=false")
        val logs = device.executeShellCommand("logcat -d -s Media3PlayerEngine:I")
        assertThat(logs.lineSequence().count { "prepare resolvedStreamType=DASH" in it }).isEqualTo(1)
        assertThat(logs).doesNotContain("fatal-error")
        assertThat(logs).doesNotContain("buffer-policy promote")
        Log.i("VodDashFixture", "complete case=$case low=$low high=$high downAfterHigh=$downAfterHigh position=${engine.currentPosition.value} prepares=1 historyId=${history.contentId}")
    }

    @Test fun cleanupOnlyIsolatedMovies() = runBlocking {
        enabled()
        val original = JSONObject(setup.readText())
        val providers = component("providerRepositoryImplProvider") as ProviderRepository
        val prefs = component("preferencesRepositoryProvider") as PreferencesRepository
        val id = original.getLong("id")
        check(providers.getProvider(id)?.name == "DASH UI validation 20261003")
        check(providers.deleteProvider(id).isSuccess)
        check(providers.setActiveProvider(original.getLong("active")).isSuccess)
        prefs.setLastActiveProviderId(original.getLong("last"))
        val context = prefs.javaClass.getDeclaredField("context").apply { isAccessible = true }.get(prefs) as Context
        @Suppress("UNCHECKED_CAST")
        val store = prefs.javaClass.getDeclaredMethod("getDataStore", Context::class.java).apply { isAccessible = true }
            .invoke(prefs, context) as DataStore<Preferences>
        val restore = File(app.filesDir, "vod-dash-restore.json")
        val saved = JSONObject(restore.readText())
        store.edit { current ->
            current.remove(stringPreferencesKey("last_split_catalog_type_$id"))
            val key = stringPreferencesKey("vod_variant_observations")
            if (saved.isNull(key.name)) current.remove(key) else current[key] = saved.getString(key.name)
        }
        check(restore.delete())
        check(setup.delete())
        for (case in listOf("adaptive", "direct4k", "same1080")) File(app.filesDir, "vod-dash-$case.json").delete()
        for (name in listOf("vod-dash-driver.png", "vod-dash-driver.xml", "vod-dash-expired-retry.png")) File(app.filesDir, name).delete()
    }

    @Test fun expiredFixtureRetryRetainsIdentityWithoutPreparing() = runBlocking {
        enabled()
        val providers = component("providerRepositoryImplProvider") as ProviderRepository
        val movies = component("movieRepositoryImplProvider") as MovieRepository
        val id = JSONObject(setup.readText()).getLong("id")
        check(providers.getProvider(id)?.name == "DASH UI validation 20261003")
        java.net.URL("http://10.0.2.2:18770/control?rate=120000000&ttl=-60").readText()
        try {
            check(providers.refreshProviderData(id, force = true).isSuccess)
            val sync = component("syncManagerProvider") as ProviderSyncCommands
            check(sync.processQueuedXtreamIndexJobs(id, section = ContentType.MOVIE, force = true).isSuccess)
            withTimeout(60_000) {
                movies.getMovies(id).first { rows ->
                    rows.size == 3 && rows.all { movie ->
                        movies.getStreamInfo(movie).getOrNull()?.expirationTime?.let { it < System.currentTimeMillis() } == true
                    }
                }
            }
            click("Movies"); click("DASH Adaptive UI Fixture"); click("Play")
            val banner = "This stream's subscription has expired."
            await("expired source message") { device.findObject(By.textContains(banner)) != null }
            lateinit var vm: PlayerViewModel
            await("expired player context") {
                var found = false
                instrumentation.runOnMainSync { findPlayer(compose.activity.viewModelStore)?.let { vm = it; found = true } }
                found
            }
            val title = getter(vm, "getCurrentTitle")
            val url = getter(vm, "getCurrentStreamUrl")
            val contentId = getter(vm, "getCurrentContentId")
            assertThat(title).isEqualTo("DASH Adaptive UI Fixture")
            val generation = getter(vm, "getPrepareRequestVersion") as Long
            val priorNotice = vm.playerNotice.value
            click("Retry")
            await("Retry begins a new logical playback request") { (getter(vm, "getPrepareRequestVersion") as Long) > generation }
            await("Retry completes with a new terminal expiry notice") {
                vm.playerNotice.value?.message?.contains(banner) == true &&
                    vm.playerNotice.value !== priorNotice
            }
            await("expired source remains terminal after Retry") { device.findObject(By.textContains(banner)) != null }
            assertThat(getter(vm, "getCurrentTitle")).isEqualTo(title)
            assertThat(getter(vm, "getCurrentStreamUrl")).isEqualTo(url)
            assertThat(getter(vm, "getCurrentContentId")).isEqualTo(contentId)
            val engine = component("provideMainPlayerEngineProvider") as PlayerEngine
            assertThat(engine.isPlaying.value).isFalse()
            val logs = device.executeShellCommand("logcat -d -s Media3PlayerEngine:I")
            assertThat(logs).doesNotContain("prepare resolvedStreamType=")
            assertThat(logs).doesNotContain("first-frame-success")
            device.takeScreenshot(File(app.filesDir, "vod-dash-expired-retry.png"))
            Log.i("VodDashFixture", "expired-retry-complete logicalIdentityRetained=true prepares=0")
        } finally {
            java.net.URL("http://10.0.2.2:18770/control?rate=120000000&ttl=7200").readText()
        }
        Unit
    }

    @Test fun restoreOnlyFixtureCompatibilityLearning() {
        enabled()
        val file = File(app.filesDir, "vod-dash-compatibility-private.json")
        val changes = JSONArray(file.readText())
        val database = (component("provideDatabaseProvider") as StreamVaultDatabase).openHelper.writableDatabase
        database.beginTransaction()
        try {
            for (index in 0 until changes.length()) {
                val change = changes.getJSONObject(index)
                val expected = change.getJSONObject("expected")
                val id = expected.getLong("id")
                database.query("SELECT * FROM playback_compatibility_records WHERE id = ?", arrayOf<Any>(id)).use { cursor ->
                    check(cursor.moveToFirst())
                    for (column in cursor.columnNames) {
                        val actual = cursor.getString(cursor.getColumnIndexOrThrow(column))
                        check(actual == if (expected.isNull(column)) null else expected.get(column).toString())
                    }
                    check(!cursor.moveToNext())
                }
                database.execSQL("DELETE FROM playback_compatibility_records WHERE id = ?", arrayOf<Any>(id))
                if (!change.isNull("original")) {
                    val original = change.getJSONObject("original")
                    val columns = original.keys().asSequence().toList()
                    check(columns.all { it.matches(Regex("[a-z_]+")) })
                    database.execSQL("INSERT INTO playback_compatibility_records (${columns.joinToString()}) VALUES (${columns.joinToString { "?" }})",
                        columns.map { if (original.isNull(it)) null else original.get(it).toString() }.toTypedArray())
                }
            }
            database.setTransactionSuccessful()
        } finally { database.endTransaction() }
        check(file.delete())
    }
}
