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
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.performScrollToIndex
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
import com.streamvault.domain.repository.ChannelRepository
import com.streamvault.domain.repository.EpgRepository
import com.streamvault.domain.model.Program
import com.streamvault.domain.model.LiveStreamFormatMode
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

/** Opt-in normal archive UI validation using an isolated provider and private local media. */
@RunWith(AndroidJUnit4::class)
class CatchUpRuntimeTest {
    @get:Rule val compose = createAndroidComposeRule<com.streamvault.app.MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val device get() = UiDevice.getInstance(instrumentation)
    private val setup get() = File(app.filesDir, "catch-up-fixture-private.json")
    private fun enabled() = assumeTrue(InstrumentationRegistry.getArguments().getString("catchUpFixture") == "true")
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
            device.takeScreenshot(File(app.filesDir, "catch-up-driver.png"))
            device.dumpWindowHierarchy(File(app.filesDir, "catch-up-driver.xml"))
            error("Timed out: $label")
        }
    }
    private fun click(text: String) {
        compose.waitForIdle()
        if (text.startsWith("Progressive ")) {
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
    private fun findPlayers(store: ViewModelStore): List<PlayerViewModel> {
        val players = mutableListOf<PlayerViewModel>()
        for (key in store.keys()) {
            val model = store[key] ?: continue
            if (model is PlayerViewModel) players.add(model)
            for (field in model.javaClass.declaredFields.filter { Map::class.java.isAssignableFrom(it.type) }) {
                val map = field.apply { isAccessible = true }.get(model) as? Map<*, *> ?: continue
                for (nested in map.values.filterIsInstance<ViewModelStore>()) players.addAll(findPlayers(nested))
            }
        }
        return players.distinct()
    }
    private fun getter(owner: Any, name: String): Any? = owner.javaClass.methods
        .single { it.name.substringBefore('$') == name && it.parameterCount == 0 }.invoke(owner)
    private fun control(fail: Boolean) = java.net.URL("http://10.0.2.2:18772/control?failBeta=$fail").readText()

    @Suppress("UNCHECKED_CAST")
    private fun store(prefs: PreferencesRepository): DataStore<Preferences> {
        val context = prefs.javaClass.getDeclaredField("context").apply { isAccessible = true }.get(prefs) as Context
        return prefs.javaClass.getDeclaredMethod("getDataStore", Context::class.java).apply { isAccessible = true }
            .invoke(prefs, context) as DataStore<Preferences>
    }

    @Test fun provisionIsolatedArchive() = runBlocking {
        enabled()
        val providers = component("providerRepositoryImplProvider") as ProviderRepository
        val prefs = component("preferencesRepositoryProvider") as PreferencesRepository
        val channels = component("channelRepositoryImplProvider") as ChannelRepository
        val restore = File(app.filesDir, "catch-up-restore.json")
        if (!setup.exists()) {
            val original = store(prefs).data.first()
            val saved = JSONObject()
            for (name in listOf("vod_variant_observations", "player_vod_track_global_preferences", "player_vod_track_preferences", "vod_duplicate_handling_mode")) {
                saved.put(name, original[stringPreferencesKey(name)] ?: JSONObject.NULL)
            }
            restore.writeText(saved.toString())
        }
        check(restore.exists())
        val active = providers.getActiveProvider().first()?.id ?: -1L
        val last = prefs.lastActiveProviderId.first() ?: -1L
        val id = if (setup.exists()) JSONObject(setup.readText()).getLong("id") else
            providers.addProvider(LegacyProvider(name = "V2 Archive validation 20261003", type = ProviderType.XTREAM_CODES,
                serverUrl = "http://10.0.2.2:18772", username = "validation", password = "validation",
                maxConnections = 5, allowedOutputFormats = listOf("m3u8"), preferredLiveStreamFormat = LiveStreamFormatMode.HLS,
                isActive = false, xtreamFastSyncEnabled = false)).getOrNull() ?: error("Fixture creation failed")
        check(providers.getProvider(id)?.name == "V2 Archive validation 20261003")
        if (!setup.exists()) setup.writeText(JSONObject().put("id", id).put("active", active).put("last", last).toString())
        check(providers.refreshProviderData(id, force = true).isSuccess)
        val sync = component("syncManagerProvider") as ProviderSyncCommands
        check(sync.processQueuedXtreamIndexJobs(id, section = ContentType.LIVE, force = true).isSuccess)
        val rows = withTimeout(60_000) { channels.getChannels(id).first { it.size == 1 } }
        check(rows.single().name == "V2 Archive Channel")
        check(rows.single().catchUpSupported && rows.single().catchUpDays == 3)
        check(providers.setActiveProvider(id).isSuccess)
        prefs.setLastActiveProviderId(id)
        Log.i("CatchUpFixture", "provisioned provider=$id channels=1")
        Unit
    }

    private fun player(): PlayerViewModel {
        lateinit var vm: PlayerViewModel
        await("player context") {
            var found = false
            instrumentation.runOnMainSync {
                val candidates = findPlayers(compose.activity.viewModelStore)
                candidates.singleOrNull {
                    getter(it, "getCurrentTitle") == "V2 Archive Channel" &&
                        it.playerEngine.isPlaying.value && it.playerEngine.playbackState.value == PlaybackState.READY
                }?.let { vm = it; found = true }
            }
            found
        }
        return vm
    }

    private fun openArchive(name: String): PlayerViewModel {
        await("fixture channel row") {
            compose.onAllNodesWithContentDescription("V2 Archive Channel", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("V2 Archive Channel", substring = true)
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        SystemClock.sleep(800)
        // Pro mode's first click previews; its second click uses the ordinary preview handoff.
        if (compose.onAllNodesWithContentDescription("V2 Archive Channel", substring = true).fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithContentDescription("V2 Archive Channel", substring = true)
                .performSemanticsAction(SemanticsActions.OnClick) { it() }
        }
        val vm = player()
        await("fixture guide") { vm.programHistory.value.count { it.title.startsWith("V2 Program ") } == 3 && vm.currentProgram.value?.title == "V2 Current Program" }
        await("setup channel ready") { vm.playerEngine.isPlaying.value && vm.playerEngine.playbackState.value == PlaybackState.READY }
        // LIVE center opens channel information; Menu opens the ordinary transport controls.
        if (!vm.showControls.value) device.pressKeyCode(android.view.KeyEvent.KEYCODE_MENU)
        await("live transport controls") { vm.showControls.value }
        val archive = app.getString(com.streamvault.feature.playback.R.string.player_archive)
        val archiveButton = hasText(archive) and hasClickAction()
        if (compose.onAllNodes(archiveButton).fetchSemanticsNodes().isEmpty()) {
            compose.onAllNodes(hasScrollToIndexAction())[0].performScrollToIndex(6)
        }
        await("archive control") { compose.onAllNodes(archiveButton).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(archiveButton).performSemanticsAction(SemanticsActions.OnClick) { it() }
        await("archive picker") { compose.onAllNodes(hasText("V2 Program $name")).fetchSemanticsNodes().isNotEmpty() }
        // The picker delays interaction on Compose's test clock, not the decoder's wall clock.
        compose.mainClock.advanceTimeBy(650)
        compose.waitForIdle()
        compose.onNodeWithText("V2 Program $name").performSemanticsAction(SemanticsActions.OnClick) { it() }
        await("archive picker dismissed") {
            compose.onAllNodes(hasText(app.getString(com.streamvault.feature.playback.R.string.player_archive_title))).fetchSemanticsNodes().isEmpty()
        }
        await("selected catch-up ready") {
            val engine = vm.playerEngine
            val source = engine.javaClass.getDeclaredField("lastStreamInfo").apply { isAccessible = true }.get(engine) as? StreamInfo
            source?.playbackContentKind == PlaybackContentKind.CATCH_UP &&
                getter(vm, "getCurrentTitle") == "V2 Archive Channel: V2 Program $name" &&
                engine.isPlaying.value && engine.playbackState.value == PlaybackState.READY && engine.videoFormat.value.height == 720 && engine.playerStats.value.ttffMs > 0
        }
        val engine = vm.playerEngine
        val physicalSource = engine.javaClass.getDeclaredField("lastStreamInfo").apply { isAccessible = true }.get(engine) as StreamInfo
        // A CATCH_UP label alone is insufficient: the provider must retain the selected archive URL.
        assertThat(physicalSource.url).isEqualTo(getter(vm, "getCurrentStreamUrl"))
        assertThat(engine.currentPosition.value).isLessThan(15_000)
        val context = getter(vm, "getPlayerPlaybackContextCoordinator")!!
        val selected = getter(context, "getSelectedCatchUpProgram") as Program
        val expected = JSONObject(java.net.URL("http://10.0.2.2:18772/fixture-state").readText()).getJSONArray("programs")
        val window = (0 until expected.length()).map(expected::getJSONObject).single { it.getString("title") == "V2 Program $name" }
        assertThat(selected.title).isEqualTo("V2 Program $name")
        assertThat(selected.startTime).isEqualTo(window.getLong("start_timestamp") * 1000)
        assertThat(selected.endTime).isEqualTo(window.getLong("stop_timestamp") * 1000)
        assertThat(engine.duration.value).isAtLeast(239_000)
        assertThat(engine.duration.value).isAtMost(242_000)
        Log.i("CatchUpFixture", "selected program=$name start=${selected.startTime} end=${selected.endTime} position=${engine.currentPosition.value} intent=CATCH_UP")
        return vm
    }

    private fun sample(engine: PlayerEngine, name: String, duration: Long) {
        val began = SystemClock.elapsedRealtime()
        val samples = JSONArray()
        Log.i("CatchUpFixture", "capture-ready case=${name.lowercase()} intent=CATCH_UP ttffMs=${engine.playerStats.value.ttffMs}")
        while (SystemClock.elapsedRealtime() - began < duration) {
            compose.waitForIdle()
            val source = engine.javaClass.getDeclaredField("lastStreamInfo").apply { isAccessible = true }.get(engine) as StreamInfo
            assertThat(source.playbackContentKind).isEqualTo(PlaybackContentKind.CATCH_UP)
            val stats = engine.playerStats.value
            val row = JSONObject().put("elapsedMs", SystemClock.elapsedRealtime() - began)
                .put("positionMs", engine.currentPosition.value).put("state", engine.playbackState.value.name)
                .put("height", engine.videoFormat.value.height).put("bufferMs", stats.bufferedDurationMs)
                .put("rebuffers", stats.rebufferCount).put("drops", stats.droppedFrames).put("pssKb", Debug.getPss())
            instrumentation.runOnMainSync {
                val player = engine.javaClass.getDeclaredField("exoPlayer").apply { isAccessible = true }.get(engine) as ExoPlayer
                assertThat(player.isCurrentMediaItemLive).isFalse()
                assertThat(player.isCurrentMediaItemDynamic).isFalse()
                row.put("timelineLive", false).put("timelineDynamic", false)
                player.videoDecoderCounters?.let {
                    it.ensureUpdated()
                    row.put("renderedBuffers", it.renderedOutputBufferCount).put("decoderDroppedBuffers", it.droppedBufferCount)
                }
            }
            check(engine.playbackState.value != PlaybackState.ERROR)
            samples.put(row)
            File(app.filesDir, "catch-up-${name.lowercase()}.json").writeText(samples.toString())
            SystemClock.sleep(1_000)
        }
        val logs = device.executeShellCommand("logcat -d -s Media3PlayerEngine:I")
        assertThat(logs).doesNotContain("live-recovery selected")
        assertThat(logs).doesNotContain("buffer-policy promote")
    }

    private fun backToChannel() {
        var returned = false
        for (attempt in 0 until 5) {
            device.pressBack(); SystemClock.sleep(500); compose.waitForIdle()
            if (compose.onAllNodesWithContentDescription("V2 Archive Channel", substring = true).fetchSemanticsNodes().isNotEmpty()) {
                returned = true; break
            }
        }
        check(returned) { "Normal Back did not return to fixture channel" }
    }

    @Test fun normalUiCatchUpAndAdjacentPrograms() {
        enabled()
        control(false)
        click("Live TV")
        await("fixture category") { compose.onAllNodes(hasText("V2 Archive Validation")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("V2 Archive Validation").performSemanticsAction(SemanticsActions.OnClick) { it() }
        val vm = openArchive("Beta")
        val engine = vm.playerEngine
        val title = getter(vm, "getCurrentTitle")
        val url = getter(vm, "getCurrentStreamUrl")
        sample(engine, "Beta", 180_000)
        // Seek outside the currently buffered region while the fixture briefly fails segment reads.
        control(true)
        try {
            instrumentation.runOnMainSync { engine.seekTo(60_000); engine.play() }
            await("seek outage buffers", 15_000) { engine.playbackState.value == PlaybackState.BUFFERING }
            SystemClock.sleep(1_500)
        } finally { control(false) }
        await("finite seek recovery", 45_000) {
            engine.isPlaying.value && engine.playbackState.value == PlaybackState.READY && engine.currentPosition.value in 59_000..75_000
        }
        val position = engine.currentPosition.value
        await("recovered progression") { engine.currentPosition.value > position + 5_000 }
        assertThat(getter(vm, "getCurrentTitle")).isEqualTo(title)
        assertThat(getter(vm, "getCurrentStreamUrl")).isEqualTo(url)
        val history = vm.javaClass.methods.single { it.name.substringBefore('$') == "buildPlaybackHistorySnapshot" && it.parameterCount == 2 }
            .invoke(vm, engine.currentPosition.value, engine.duration.value) as PlaybackHistory
        assertThat(history.title).isEqualTo(title)
        assertThat(history.streamUrl).isEqualTo(url)
        assertThat(history.resumePositionMs).isAtLeast(59_000)
        val selected = getter(getter(vm, "getPlayerPlaybackContextCoordinator")!!, "getSelectedCatchUpProgram") as Program
        assertThat(selected.title).isEqualTo("V2 Program Beta")
        Log.i("CatchUpFixture", "recovery-complete program=Beta position=${engine.currentPosition.value} historyTitleRetained=true")
        backToChannel()
        val older = openArchive("Alpha")
        val olderEngine = older.playerEngine
        val olderPosition = olderEngine.currentPosition.value
        await("earlier program progresses") { olderEngine.currentPosition.value > olderPosition + 5_000 }
        assertThat(getter(older, "getCurrentStreamUrl")).isNotEqualTo(url)
        backToChannel()
        val newer = openArchive("Gamma")
        val newerEngine = newer.playerEngine
        assertThat(getter(newer, "getCurrentStreamUrl")).isNotEqualTo(url)
        sample(newerEngine, "Gamma", 145_000)
        assertThat(getter(newer, "getCurrentTitle")).isEqualTo("V2 Archive Channel: V2 Program Gamma")
        val logs = device.executeShellCommand("logcat -d -s Media3PlayerEngine:I PlayerVM:I")
        assertThat(logs).doesNotContain("fatal-error")
        assertThat(logs).doesNotContain("Player stuck")
        assertThat(logs).doesNotContain("live-recovery selected")
        assertThat(logs).doesNotContain("prepare resolvedStreamType=MPEG_TS_LIVE")
        Log.i("CatchUpFixture", "complete catchUp=true earlier=true later=true position=${newerEngine.currentPosition.value}")
    }

    @Test fun restoreOnlyFixtureLearning() = runBlocking {
        enabled()
        val file = File(app.filesDir, "catch-up-learning-private.json")
        val saved = JSONObject(file.readText())
        val prefs = component("preferencesRepositoryProvider") as PreferencesRepository
        val observations = saved.getJSONObject("liveObservations")
        store(prefs).edit { current ->
            val key = stringPreferencesKey("live_variant_observations")
            val expected = if (observations.isNull("expected")) null else observations.getString("expected")
            check(current[key] == expected)
            if (observations.isNull("original")) current.remove(key) else current[key] = observations.getString("original")
        }
        val changes = saved.getJSONArray("compatibility")
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
        Unit
    }

    @Test fun cleanupOnlyIsolatedArchive() = runBlocking {
        enabled()
        val original = JSONObject(setup.readText())
        val providers = component("providerRepositoryImplProvider") as ProviderRepository
        val prefs = component("preferencesRepositoryProvider") as PreferencesRepository
        val id = original.getLong("id")
        check(providers.getProvider(id)?.name == "V2 Archive validation 20261003")
        check(providers.deleteProvider(id).isSuccess)
        check(providers.setActiveProvider(original.getLong("active")).isSuccess)
        prefs.setLastActiveProviderId(original.getLong("last"))
        val restore = File(app.filesDir, "catch-up-restore.json")
        val saved = JSONObject(restore.readText())
        store(prefs).edit { current ->
            for (name in listOf("vod_variant_observations", "player_vod_track_global_preferences", "player_vod_track_preferences", "vod_duplicate_handling_mode")) {
                val key = stringPreferencesKey(name)
                if (saved.isNull(name)) current.remove(key) else current[key] = saved.getString(name)
            }
            current.asMap().keys.filter { it.name in listOf("last_split_catalog_type_$id", "last_live_category_id_$id") }
                .forEach { current.remove(it) }
        }
        check(restore.delete()); check(setup.delete())
        for (name in listOf("catch-up-beta.json", "catch-up-gamma.json", "catch-up-driver.png", "catch-up-driver.xml")) File(app.filesDir, name).delete()
        Unit
    }
}
