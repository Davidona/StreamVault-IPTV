package com.streamvault.app.ui

import android.app.Application
import android.os.SystemClock
import android.util.Log
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.test.uiautomator.StaleObjectException
import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.LegacyProvider
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.PlaybackHistory
import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.model.VodDuplicateHandlingMode
import com.streamvault.domain.repository.ProviderRepository
import com.streamvault.domain.repository.SeriesRepository
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.data.local.dao.SeriesDao
import com.streamvault.data.local.StreamVaultDatabase
import com.streamvault.feature.playback.player.PlayerViewModel
import com.streamvault.player.PlaybackState
import com.streamvault.player.PlayerEngine
import com.streamvault.player.PlayerError
import com.streamvault.player.PLAYER_TRACK_AUTO_ID
import dagger.hilt.internal.GeneratedComponentManager
import java.io.File
import java.net.URI
import org.json.JSONObject
import javax.inject.Provider
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in local, finite-media episode acceptance. Injected errors prove wiring only. */
@RunWith(AndroidJUnit4::class)
class EpisodeFallbackRuntimeTest {
    @get:Rule val compose = createAndroidComposeRule<com.streamvault.app.MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val device get() = UiDevice.getInstance(instrumentation)
    private val setupFile get() = File(app.filesDir, "episode-fallback-fixture-private.txt")
    private fun enabled() = assumeTrue(InstrumentationRegistry.getArguments().getString("episodeFallbackFixture") == "true")
    private fun component(name: String): Any {
        val c = (app as GeneratedComponentManager<*>).generatedComponent()
        return (c.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(c) as Provider<*>).get()!!
    }
    private fun await(label: String, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 30_000
        while (!condition() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(100)
        if (!condition()) {
            device.takeScreenshot(File(app.filesDir, "episode-fallback-driver.png"))
            device.dumpWindowHierarchy(File(app.filesDir, "episode-fallback-driver.xml"))
        }
        check(condition()) { "Timed out: $label" }
    }
    private fun click(text: String) {
        compose.waitForIdle()
        if (text.endsWith("Fixture")) {
            compose.onNodeWithText(text, substring = true).performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.waitForIdle()
            return
        }
        var node = device.wait(Until.findObject(By.textContains(text)), 3_000)
        if (text.startsWith("S01E")) {
            if (node == null) compose.onAllNodes(hasScrollAction()).onFirst()
                .performScrollToNode(hasText(text, substring = true))
            compose.onNodeWithText(text, substring = true).performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.waitForIdle()
            return
        }
        var bounds: android.graphics.Rect? = null
        repeat(3) {
            if (bounds == null) try {
                bounds = (device.wait(Until.findObject(By.textContains(text)), 3_000)
                    ?: error("Missing fixture UI: $text")).visibleBounds
            } catch (_: StaleObjectException) { device.waitForIdle() }
        }
        val b = bounds ?: error("Fixture UI remained stale: $text")
        device.executeShellCommand("input mouse tap ${b.centerX()} ${b.centerY()}")
        device.waitForIdle(); compose.waitForIdle()
    }
    private fun findPlayer(store: ViewModelStore): PlayerViewModel? {
        for (key in store.keys()) {
            val model = store[key] ?: continue
            if (model is PlayerViewModel) return model
            for (field in model.javaClass.declaredFields) {
                if (!Map::class.java.isAssignableFrom(field.type)) continue
                val map = field.apply { isAccessible = true }.get(model) as? Map<*, *> ?: continue
                for (nested in map.values.filterIsInstance<ViewModelStore>()) findPlayer(nested)?.let { return it }
            }
        }
        return null
    }
    private fun getter(vm: Any, prefix: String): Any? = vm.javaClass.methods
        .single { it.name.substringBefore('$') == prefix && it.parameterCount == 0 }.invoke(vm)

    @Test fun provisionIsolatedFixture() = runBlocking {
        enabled()
        val providers = component("providerRepositoryImplProvider") as ProviderRepository
        val prefs = component("preferencesRepositoryProvider") as PreferencesRepository
        val series = component("seriesRepositoryImplProvider") as SeriesRepository
        val oldActive = providers.getActiveProvider().first()?.id ?: -1L
        val oldLast = prefs.lastActiveProviderId.first() ?: -1L
        val oldMode = prefs.vodDuplicateHandlingMode.first()
        val id = if (setupFile.exists()) setupFile.readLines()[0].toLong() else providers.addProvider(LegacyProvider(name = "Episode fallback validation 20261002",
            type = ProviderType.XTREAM_CODES, serverUrl = "http://10.0.2.2:18768", username = "validation", password = "validation",
            isActive = false, maxConnections = 1, xtreamFastSyncEnabled = false)).getOrNull() ?: error("Fixture creation failed")
        check(providers.getProvider(id)?.name == "Episode fallback validation 20261002")
        if (!setupFile.exists()) setupFile.writeText("$id\n$oldActive\n$oldLast\n${oldMode.name}\n")
        check(providers.refreshProviderData(id, force = true).isSuccess) { "Fixture series sync failed" }
        for (category in series.getCategories(id).first()) series.getSeriesByCategory(id, category.id).first()
        val rows = withTimeout(60_000) { series.getSeries(id).first { it.isNotEmpty() } }
        check(rows.isNotEmpty())
        check(providers.setActiveProvider(id).isSuccess)
        prefs.setLastActiveProviderId(id)
        prefs.setVodDuplicateHandlingMode(VodDuplicateHandlingMode.GROUPED)
        println("EpisodeFixture: isolated provider $id provisioned; catalogue rows=${rows.size}")
    }

    @Test fun normalEpisodeUiRecoveryAndSustainedPlayback() {
        enabled()
        val engine = component("provideMainPlayerEngineProvider") as PlayerEngine
        fun source() = engine.javaClass.getDeclaredField("lastStreamInfo").apply { isAccessible = true }.get(engine) as? StreamInfo
        fun sourcePath() = source()?.url?.let { URI(it).path }
        fun inject() {
            @Suppress("UNCHECKED_CAST")
            val errors = engine.javaClass.getDeclaredField("_error").apply { isAccessible = true }.get(engine) as MutableSharedFlow<PlayerError?>
            instrumentation.runOnMainSync { check(errors.tryEmit(PlayerError.DecoderError("Episode fixture injected decoder failure"))) }
        }
        click("Series")
        click("Episode Resilience Fixture")
        click("S01E02")
        await("HEVC episode startup") { sourcePath()?.endsWith("/50102.mp4") == true && engine.availableAudioTracks.value.size >= 2 }
        lateinit var vm: PlayerViewModel
        instrumentation.runOnMainSync { vm = findPlayer(compose.activity.viewModelStore) ?: error("Player ViewModel not found") }
        SystemClock.sleep(1_500) // Preparation completes before its history lookup/dialog.
        compose.waitForIdle()
        if (vm.resumePrompt.value.show) {
            compose.onNodeWithText("Resume").performSemanticsAction(SemanticsActions.OnClick) { it() }
            await("resume prompt dismissed") { !vm.resumePrompt.value.show }
        }
        val logicalEpisode = vm.currentEpisode.value ?: error("Logical episode missing")
        val logicalSeries = vm.currentSeries.value ?: error("Logical series missing")
        val title = getter(vm, "getCurrentTitle")
        val url = getter(vm, "getCurrentStreamUrl")
        Log.i("EpisodeFixture", "logical provider=${logicalSeries.providerId} series=${logicalSeries.id} episode=${logicalEpisode.id} season=${logicalEpisode.seasonNumber} number=${logicalEpisode.episodeNumber} metadataDuration=${logicalEpisode.durationSeconds}")
        instrumentation.runOnMainSync { engine.seekTo(60_000); engine.play() }
        await("seek ready") { engine.currentPosition.value in 59_000..65_000 && engine.playbackState.value == PlaybackState.READY }
        instrumentation.runOnMainSync { engine.pause() }
        if (!vm.showControls.value) device.pressDPadCenter()
        repeat(3) {
            if (!vm.showControls.value) { device.pressDPadCenter(); SystemClock.sleep(200) }
        }
        if (!vm.showControls.value) device.executeShellCommand("input mouse tap ${device.displayWidth / 2} ${device.displayHeight / 2}")
        device.waitForIdle(); compose.waitForIdle()
        device.takeScreenshot(File(app.filesDir, "episode-fallback-driver.png"))
        device.dumpWindowHierarchy(File(app.filesDir, "episode-fallback-driver.xml"))
        Log.i("EpisodeFixture", "driver controls=${vm.showControls.value} resume=${vm.resumePrompt.value.show}")
        val spanish = engine.availableAudioTracks.value.first { it.language in listOf("es", "spa") }.name
        val english = engine.availableSubtitleTracks.value.first { it.language in listOf("en", "eng") }.name
        compose.onNodeWithContentDescription("Audio").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText(spanish, substring = true).performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithContentDescription("Subs").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText(english, substring = true).performSemanticsAction(SemanticsActions.OnClick) { it() }
        fun languages() = engine.availableAudioTracks.value.any { it.isSelected && it.language in listOf("es", "spa") } &&
            engine.availableSubtitleTracks.value.any { it.isSelected && it.language in listOf("en", "eng") }
        await("selected languages", ::languages)
        device.pressBack() // Close the subtitle chooser before observing video frames.
        compose.waitForIdle()
        inject()
        val recovery = getter(vm, "getPlayerRecoveryCoordinator") ?: error("Recovery state unavailable")
        await("software retry") { getter(recovery, "getRetriedWithSoftwareDecoder") == true }
        assertThat(sourcePath()).endsWith("/50102.mp4")
        await("software retry ready") { engine.playbackState.value == PlaybackState.READY &&
            engine.playerStats.value.videoCodec.contains("hevc", ignoreCase = true) && engine.videoFormat.value.height == 1080 }
        val position = engine.currentPosition.value
        Log.i("EpisodeFixture", "source identity name=${logicalSeries.name} tmdb=${logicalSeries.tmdbId} episodes=${logicalSeries.seasons.flatMap { it.episodes }.map { listOf(it.id,it.seriesId,it.episodeNumber,it.title) }} codec=${engine.playerStats.value.videoCodec} height=${engine.videoFormat.value.height} duration=${engine.duration.value} position=$position")
        val before = device.executeShellCommand("logcat -d -s Media3PlayerEngine:I")
        val prepares = before.lineSequence().count { "prepare resolvedStreamType" in it }
        inject()
        SystemClock.sleep(1_000)
        Log.i("EpisodeFixture", "candidate-outcome notice=${vm.playerNotice.value?.message} codec=${engine.playerStats.value.videoCodec} duration=${engine.duration.value}")
        if (sourcePath()?.endsWith("/50202.mp4") != true) runBlocking {
            val repository = component("seriesRepositoryImplProvider") as SeriesRepository
            val candidateId = logicalSeries.variants.first { it.rawSeriesId != logicalSeries.id }.rawSeriesId
            val candidate = repository.getSeriesDetails(logicalSeries.providerId, candidateId).getOrNull()
            Log.i("EpisodeFixture", "candidate identity id=${candidate?.id} name=${candidate?.name} tmdb=${candidate?.tmdbId} episodes=${candidate?.seasons?.flatMap { it.episodes }?.map { listOf(it.id,it.seriesId,it.episodeNumber,it.title,it.durationSeconds) }}")
        }
        await("physical AVC source") { sourcePath()?.endsWith("/50202.mp4") == true }
        await("first replacement resume") { engine.isPlaying.value && engine.currentPosition.value >= position - 2_000 && languages() }
        assertThat(engine.currentPosition.value).isAtMost(position + 2_000)
        assertThat(vm.currentEpisode.value?.id).isEqualTo(logicalEpisode.id)
        assertThat(vm.currentSeries.value?.id).isEqualTo(logicalSeries.id)
        assertThat(getter(vm, "getCurrentTitle")).isEqualTo(title)
        assertThat(getter(vm, "getCurrentStreamUrl")).isEqualTo(url)
        assertThat(logicalSeries.seasons.flatMap { it.episodes }.map { it.episodeNumber }).containsAtLeast(1, 2, 3)
        val after = device.executeShellCommand("logcat -d -s Media3PlayerEngine:I")
        assertThat(after.lineSequence().count { "prepare resolvedStreamType" in it } - prepares).isEqualTo(1)
        Log.i("EpisodeFixture", "capture-ready logicalSeries=${logicalSeries.id} logicalEpisode=${logicalEpisode.id} physicalRemote=50202 position=$position")
        val end = SystemClock.elapsedRealtime() + 130_000
        val startPosition = engine.currentPosition.value
        var previousState = engine.playbackState.value
        var rebufferTransitions = 0
        var sampledBufferingMs = 0L
        val initialDrops = engine.playerStats.value.droppedFrames
        while (SystemClock.elapsedRealtime() < end) {
            assertThat(sourcePath()).endsWith("/50202.mp4")
            assertThat(vm.currentEpisode.value?.id).isEqualTo(logicalEpisode.id)
            val state = engine.playbackState.value
            if (state == PlaybackState.BUFFERING) {
                sampledBufferingMs += 500
                if (previousState != state) rebufferTransitions++
            }
            previousState = state
            SystemClock.sleep(500)
        }
        assertThat(engine.currentPosition.value).isGreaterThan(startPosition + 100_000)
        assertThat(engine.playbackState.value).isEqualTo(PlaybackState.READY)
        assertThat(languages()).isTrue()
        val history = vm.javaClass.methods.single { it.name.substringBefore('$') == "buildPlaybackHistorySnapshot" && it.parameterCount == 2 }
            .invoke(vm, engine.currentPosition.value, engine.duration.value) as PlaybackHistory
        assertThat(history.contentId).isEqualTo(logicalEpisode.id)
        assertThat(history.resumePositionMs).isAtLeast(startPosition + 100_000)
        assertThat(history.seriesId).isEqualTo(logicalSeries.id)
        assertThat(history.streamUrl).isEqualTo(url)
        Log.i("EpisodeFixture", "capture-stats rebufferTransitions=$rebufferTransitions sampledBufferingMs=$sampledBufferingMs drops=$initialDrops->${engine.playerStats.value.droppedFrames} duration=${engine.duration.value} historyContentId=${history.contentId} historyPosition=${history.resumePositionMs} stalls=${engine.playerStats.value.videoStallCount}")
        val renewal = Class.forName("com.streamvault.feature.playback.player.PlayerLifecycleActionsKt").methods
            .single { it.name == "startTokenRenewalMonitoring" && it.parameterCount == 3 }
        instrumentation.runOnMainSync { renewal.invoke(null, vm, System.currentTimeMillis() + 1000, null) }
        SystemClock.sleep(2_000)
        assertThat(sourcePath()).endsWith("/50202.mp4")
        val renewed = getter(vm, "getCurrentResolvedStreamInfo") as StreamInfo
        assertThat(URI(renewed.url).path).endsWith("/50202.mp4")
        assertThat(renewed.title).isEqualTo(title)
        assertThat(device.executeShellCommand("logcat -d -s PlayerVM:I")).contains("token-renewal request-changed=false")
        val beforeRepeat = device.executeShellCommand("logcat -d -s Media3PlayerEngine:I")
        inject(); SystemClock.sleep(5_000)
        assertThat(sourcePath()).endsWith("/50202.mp4")
        assertThat(device.executeShellCommand("logcat -d -s Media3PlayerEngine:I").lineSequence().count { "prepare resolvedStreamType" in it })
            .isEqualTo(beforeRepeat.lineSequence().count { "prepare resolvedStreamType" in it })
        Log.i("EpisodeFixture", "positive-complete retained-logical-identity languages navigation single-prepare repeat-error-no-prepare")
    }

    @Test fun cleanupOnlyFixture() = runBlocking {
        enabled()
        val values = setupFile.readLines()
        val providers = component("providerRepositoryImplProvider") as ProviderRepository
        val prefs = component("preferencesRepositoryProvider") as PreferencesRepository
        val id = values[0].toLong()
        providers.getProvider(id)?.let { fixture ->
            check(fixture.name == "Episode fallback validation 20261002")
            check(providers.deleteProvider(id).isSuccess)
        }
        if (values[1].toLong() > 0) check(providers.setActiveProvider(values[1].toLong()).isSuccess)
        prefs.setLastActiveProviderId(values[2].toLong())
        prefs.setVodDuplicateHandlingMode(VodDuplicateHandlingMode.valueOf(values[3]))
        val restoreFile = File(app.filesDir, "episode-fallback-restore.json")
        check(restoreFile.exists()) { "Original playback preference values required for cleanup" }
        val original = JSONObject(restoreFile.readText())
        val context = prefs.javaClass.getDeclaredField("context").apply { isAccessible = true }.get(prefs) as Context
        @Suppress("UNCHECKED_CAST")
        val store = prefs.javaClass.getDeclaredMethod("getDataStore", Context::class.java).apply { isAccessible = true }
            .invoke(prefs, context) as DataStore<Preferences>
        store.edit { current ->
            current.remove(stringPreferencesKey("last_split_catalog_type_$id"))
            for (name in listOf("player_vod_track_global_preferences", "player_vod_track_preferences", "vod_duplicate_handling_mode")) {
                val key = stringPreferencesKey(name)
                if (original.isNull(name)) current.remove(key) else current[key] = original.getString(name)
            }
        }
        check(setupFile.delete())
        check(restoreFile.delete())
        for (name in listOf("episode-fallback-driver.png", "episode-fallback-driver.xml")) File(app.filesDir, name).delete()
        println("EpisodeFixture: temporary provider removed; previous provider and grouping restored")
    }

    @Test fun restoreOnlyFixtureGroupingPreference() = runBlocking {
        enabled()
        val prefs = component("preferencesRepositoryProvider") as PreferencesRepository
        val restoreFile = File(app.filesDir, "episode-fallback-restore.json")
        val original = JSONObject(restoreFile.readText())
        val context = prefs.javaClass.getDeclaredField("context").apply { isAccessible = true }.get(prefs) as Context
        @Suppress("UNCHECKED_CAST")
        val store = prefs.javaClass.getDeclaredMethod("getDataStore", Context::class.java).apply { isAccessible = true }
            .invoke(prefs, context) as DataStore<Preferences>
        store.edit { current ->
            val name = "vod_duplicate_handling_mode"
            val key = stringPreferencesKey(name)
            if (original.isNull(name)) current.remove(key) else current[key] = original.getString(name)
        }
        check(restoreFile.delete())
    }

    @Test fun cleanupOnlyFixtureCompatibilityResult() {
        enabled()
        val file = File(app.filesDir, "episode-fallback-compatibility.json")
        val expected = JSONObject(file.readText())
        check(expected.getLong("last_failed_at") == 0L)
        check(expected.getLong("last_succeeded_at") == 1790946020112L)
        val database = (component("provideDatabaseProvider") as StreamVaultDatabase).openHelper.writableDatabase
        database.beginTransaction()
        try {
            database.query("SELECT * FROM playback_compatibility_records WHERE id = ?", arrayOf<Any>(expected.getLong("id"))).use { cursor ->
                check(cursor.moveToFirst())
                for (column in cursor.columnNames) check(cursor.getString(cursor.getColumnIndexOrThrow(column)) == expected.get(column).toString())
                check(!cursor.moveToNext())
            }
            database.execSQL("DELETE FROM playback_compatibility_records WHERE id = ?", arrayOf<Any>(expected.getLong("id")))
            database.setTransactionSuccessful()
        } finally { database.endTransaction() }
        check(file.delete())
    }

    @Test fun normalEpisodeUiNegativeControl() {
        enabled()
        val case = InstrumentationRegistry.getArguments().getString("episodeFallbackCase") ?: error("Missing control case")
        check(case in setOf("wrong", "missing", "saved", "manual", "cancel"))
        val prefix = when (case) { "wrong" -> "Wrong Episode Fixture"; "missing", "cancel" -> "Missing Metadata Fixture"; else -> "Episode Resilience Fixture" }
        val physical = when (case) { "wrong" -> "51202.mp4"; "missing", "cancel" -> "52202.mp4"; else -> "50202.mp4" }
        val engine = component("provideMainPlayerEngineProvider") as PlayerEngine
        val prefs = component("preferencesRepositoryProvider") as PreferencesRepository
        // Fixture URLs expire; force only this fixture's source metadata to refresh.
        val sourceRemote = when (case) { "wrong" -> "511"; "missing", "cancel" -> "521"; else -> "501" }
        val repository = component("seriesRepositoryImplProvider")
        val fixtureDao = repository.javaClass.getDeclaredField("seriesDao").apply { isAccessible = true }.get(repository) as SeriesDao
        runBlocking {
            val row = fixtureDao.getByProviderSeriesId(setupFile.readLines()[0].toLong(), sourceRemote) ?: error("Missing fixture source")
            check(row.name.startsWith(prefix))
            fixtureDao.update(row.copy(detailHydratedAt = 0L, cacheState = "SUMMARY_ONLY"))
        }
        fun inject() {
            @Suppress("UNCHECKED_CAST")
            val errors = engine.javaClass.getDeclaredField("_error").apply { isAccessible = true }.get(engine) as MutableSharedFlow<PlayerError?>
            instrumentation.runOnMainSync { check(errors.tryEmit(PlayerError.DecoderError("Episode fixture control $case"))) }
        }
        click("Series"); click("Browse Full Series Library"); click(prefix); click("S01E02")
        await("control player context") { var found = false; instrumentation.runOnMainSync { found = findPlayer(compose.activity.viewModelStore) != null }; found }
        lateinit var vm: PlayerViewModel
        instrumentation.runOnMainSync { vm = findPlayer(compose.activity.viewModelStore) ?: error("Missing player") }
        SystemClock.sleep(1_500)
        if (vm.resumePrompt.value.show) {
            compose.onNodeWithText("Resume").performSemanticsAction(SemanticsActions.OnClick) { it() }
            await("control resume dismissed") { !vm.resumePrompt.value.show }
        }
        await("control startup") { engine.playbackState.value == PlaybackState.READY && engine.availableAudioTracks.value.isNotEmpty() }
        val series = vm.currentSeries.value ?: error("Missing series")
        instrumentation.runOnMainSync { engine.seekTo(60_000) }
        await("control seek") { engine.currentPosition.value >= 59_000 }
        inject()
        val recovery = getter(vm, "getPlayerRecoveryCoordinator")!!
        await("control software retry") { getter(recovery, "getRetriedWithSoftwareDecoder") == true }
        await("control software ready") { engine.playbackState.value == PlaybackState.READY && engine.currentPosition.value >= 59_000 &&
            engine.playerStats.value.videoCodec.contains("hevc", ignoreCase = true) }
        try {
            if (case == "saved") runBlocking { prefs.setPreferredVodVariant(series.providerId, series.logicalGroupId, series.id) }
            if (case == "manual") {
                val track = engine.availableVideoTracks.value.first { it.id != PLAYER_TRACK_AUTO_ID }
                instrumentation.runOnMainSync { engine.selectVideoTrack(track.id) }
                await("manual video") { engine.availableVideoTracks.value.none { it.id == PLAYER_TRACK_AUTO_ID && it.isSelected } }
            }
            if (case == "cancel") {
                // Repeat the suspended network lookup even after a prior driver failure.
                val repository = component("seriesRepositoryImplProvider")
                val dao = repository.javaClass.getDeclaredField("seriesDao").apply { isAccessible = true }.get(repository) as SeriesDao
                runBlocking {
                    val candidate = dao.getByProviderSeriesId(series.providerId, "522") ?: error("Missing fixture candidate")
                    check(candidate.name.startsWith("Missing Metadata Fixture"))
                    dao.update(candidate.copy(detailHydratedAt = 0L, cacheState = "SUMMARY_ONLY"))
                }
                java.net.URL("http://10.0.2.2:18768/control?delay=522").readText()
            }
            val before = device.executeShellCommand("logcat -d -s Media3PlayerEngine:I")
            val count = before.lineSequence().count { "prepare resolvedStreamType" in it && physical in it }
            inject()
            if (case == "cancel") {
                val state = getter(vm, "getEpisodeSourceState")!!
                await("candidate lookup reserved") { getter(state, "getAttempted") == true }
                val oldToken = getter(state, "getToken")
                SystemClock.sleep(300)
                repeat(6) {
                    if (getter(state, "getToken") == oldToken) {
                        device.pressBack()
                        compose.waitForIdle()
                        if (device.findObject(By.text("Close playback?")) != null) {
                            compose.onNodeWithText("Yes").performSemanticsAction(SemanticsActions.OnClick) { it() }
                            compose.waitForIdle()
                        }
                        SystemClock.sleep(100)
                    }
                }
                await("lookup session canceled") { getter(state, "getToken") != oldToken }
            }
            SystemClock.sleep(6_000)
            val after = device.executeShellCommand("logcat -d -s Media3PlayerEngine:I")
            assertThat(after.lineSequence().count { "prepare resolvedStreamType" in it && physical in it }).isEqualTo(count)
            Log.i("EpisodeFixture", "negative-complete case=$case provider=${series.providerId} logicalSeries=${series.id} logicalEpisode=${vm.currentEpisode.value?.id} sourceRemote=${sourceRemote}02 candidateRemote=${physical.removeSuffix(".mp4")} identity=S01E02 candidatePrepareDelta=0")
        } finally {
            if (case == "saved") runBlocking { prefs.clearPreferredVodVariant(series.providerId, series.logicalGroupId) }
            if (case == "cancel") java.net.URL("http://10.0.2.2:18768/control?delay=0").readText()
        }
    }
}
