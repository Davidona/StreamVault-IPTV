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
import com.streamvault.domain.model.LegacyProvider
import com.streamvault.domain.model.PlaybackContentKind
import com.streamvault.domain.model.PlaybackHistory
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.repository.MovieRepository
import com.streamvault.domain.repository.ProviderRepository
import com.streamvault.feature.playback.player.PlayerViewModel
import com.streamvault.player.PlayerError
import com.streamvault.player.PlaybackState
import com.streamvault.player.PlayerEngine
import dagger.hilt.internal.GeneratedComponentManager
import java.io.File
import javax.inject.Provider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
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
class AdaptiveFallbackRuntimeTest {
    @get:Rule val compose = createAndroidComposeRule<com.streamvault.app.MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val device get() = UiDevice.getInstance(instrumentation)
    private val setup get() = File(app.filesDir, "adaptive-fallback-fixture-private.json")
    private fun enabled() = assumeTrue(InstrumentationRegistry.getArguments().getString("adaptiveFallbackFixture") == "true")
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
            device.takeScreenshot(File(app.filesDir, "adaptive-fallback-driver.png"))
            device.dumpWindowHierarchy(File(app.filesDir, "adaptive-fallback-driver.xml"))
            error("Timed out: $label")
        }
    }
    private fun click(text: String) {
        compose.waitForIdle()
        if (text.startsWith("V4 ")) {
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
    private fun dismissFixtureResumePrompt() {
        if (compose.onAllNodes(hasText("Start Over")).fetchSemanticsNodes().size == 1) {
            compose.onNodeWithText("Start Over").performSemanticsAction(SemanticsActions.OnClick) { it() }
        }
    }
    private fun engineFailure(engine: PlayerEngine): androidx.media3.common.PlaybackException? {
        var failure: androidx.media3.common.PlaybackException? = null
        instrumentation.runOnMainSync {
            val player=engine.javaClass.getDeclaredField("exoPlayer").apply { isAccessible=true }.get(engine) as? ExoPlayer
            failure=player?.playerError
        }
        return failure
    }
    private fun control(query: String) = java.net.URL("http://10.0.2.2:18784/control?$query").readText()

    @Test fun provisionIsolatedMovies() = runBlocking {
        enabled()
        val providers = component("providerRepositoryImplProvider") as ProviderRepository
        val prefs = component("preferencesRepositoryProvider") as PreferencesRepository
        val movies = component("movieRepositoryImplProvider") as MovieRepository
        val original = providers.getActiveProvider().first()?.id ?: error("Fixture requires an existing active provider")
        val last = prefs.lastActiveProviderId.first() ?: error("Fixture requires an existing last provider")
        val restore = File(app.filesDir, "adaptive-fallback-restore.json")
        if (!setup.exists()) {
            val context = prefs.javaClass.getDeclaredField("context").apply { isAccessible = true }.get(prefs) as Context
            @Suppress("UNCHECKED_CAST")
            val store = prefs.javaClass.getDeclaredMethod("getDataStore", Context::class.java).apply { isAccessible = true }
                .invoke(prefs, context) as DataStore<Preferences>
            val saved = store.data.first()[stringPreferencesKey("vod_variant_observations")]
            restore.writeText(JSONObject().put("vod_variant_observations", saved ?: JSONObject.NULL).toString())
        }
        check(restore.exists()) { "Original fixture preference snapshot required" }
        val id = if (setup.exists()) JSONObject(setup.readText()).getLong("id") else providers.addProvider(LegacyProvider(name = "V4 UI validation 20261006", type = ProviderType.XTREAM_CODES,
            serverUrl = "http://10.0.2.2:18784", username = "validation", password = "validation", maxConnections = 5,
            isActive = false, xtreamFastSyncEnabled = false)).getOrNull() ?: error("Fixture creation failed")
        check(providers.getProvider(id)?.name == "V4 UI validation 20261006")
        if (!setup.exists()) setup.writeText(JSONObject().put("id", id).put("active", original).put("last", last).toString())
        check(providers.refreshProviderData(id, force = true).isSuccess)
        val categories = withTimeout(60_000) { movies.getCategories(id).first { it.isNotEmpty() } }
        for (category in categories) movies.getMoviesByCategory(id, category.id).first()
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
        check(rows.all { it.name.startsWith("V4 ") })
        check(providers.setActiveProvider(id).isSuccess)
        prefs.setLastActiveProviderId(id)
        val remaining = rows.map { movies.getStreamInfo(it).getOrNull()!!.expirationTime!! - System.currentTimeMillis() }
        Log.i("AdaptiveFallbackFixture", "provisioned provider=$id movies=${rows.size} minimumResolvedLifetimeMs=${remaining.min()}")
        Unit
    }

    @Test fun cleanupOnlyIsolatedMovies() = runBlocking {
        enabled()
        val original = JSONObject(setup.readText())
        val providers = component("providerRepositoryImplProvider") as ProviderRepository
        val prefs = component("preferencesRepositoryProvider") as PreferencesRepository
        val id = original.getLong("id")
        check(providers.getProvider(id)?.name == "V4 UI validation 20261006")
        check(providers.deleteProvider(id).isSuccess)
        check(providers.setActiveProvider(original.getLong("active")).isSuccess)
        prefs.setLastActiveProviderId(original.getLong("last"))
        val context = prefs.javaClass.getDeclaredField("context").apply { isAccessible = true }.get(prefs) as Context
        @Suppress("UNCHECKED_CAST")
        val store = prefs.javaClass.getDeclaredMethod("getDataStore", Context::class.java).apply { isAccessible = true }
            .invoke(prefs, context) as DataStore<Preferences>
        val restore = File(app.filesDir, "adaptive-fallback-restore.json")
        val saved = JSONObject(restore.readText())
        store.edit { current ->
            current.remove(stringPreferencesKey("last_split_catalog_type_$id"))
            val key = stringPreferencesKey("vod_variant_observations")
            if (saved.isNull(key.name)) current.remove(key) else current[key] = saved.getString(key.name)
        }
        check(restore.delete())
        check(setup.delete())
        for (case in listOf("server", "unknown", "auth", "auth403", "limit", "drm-valid", "drm-invalid")) {
            for (suffix in listOf(".json", "-session.txt", ".png")) File(app.filesDir, "adaptive-fallback-$case$suffix").delete()
        }
        for (name in listOf("adaptive-fallback-driver.png", "adaptive-fallback-driver.xml", "adaptive-fallback-expired-retry.png")) File(app.filesDir, name).delete()
    }


    @Test fun brokenRepresentationThroughMoviesUi() {
        enabled()
        val case = InstrumentationRegistry.getArguments().getString("adaptiveFallbackCase") ?: "server"
        val code = when(case) { "server" -> 503; "unknown" -> 410; "auth" -> 401; "auth403" -> 403; "limit" -> 509; else -> error("Unsupported case") }
        val eligible = case in setOf("server", "unknown")
        control("reset=1")
        val engine = component("provideMainPlayerEngineProvider") as PlayerEngine
        click("Movies"); click("V4 Adaptive UI Fixture"); click("Play")
        lateinit var vm: PlayerViewModel
        await("normal player context") { var found=false; instrumentation.runOnMainSync { findPlayer(compose.activity.viewModelStore)?.let { vm=it; found=true } }; found }
        await("first frame") {
            dismissFixtureResumePrompt()
            engine.isPlaying.value && engine.playbackState.value == PlaybackState.READY && engine.playerStats.value.ttffMs > 0
        }
        await("normal player overlays gone") { dismissFixtureResumePrompt(); !vm.resumePrompt.value.show && device.findObject(By.textContains("Resolving playback")) == null }
        val title = getter(vm,"getCurrentTitle")
        val url = getter(vm,"getCurrentStreamUrl")
        check(engine.availableVideoTracks.value.any { it.id == com.streamvault.player.PLAYER_TRACK_AUTO_ID && it.isSelected }) {
            "V4 requires existing Auto selection; do not override a saved manual selection"
        }
        val errors = JSONArray()
        val statsListener = androidx.media3.exoplayer.analytics.PlaybackStatsListener(true, null)
        instrumentation.runOnMainSync {
            val player=engine.javaClass.getDeclaredField("exoPlayer").apply { isAccessible=true }.get(engine) as ExoPlayer
            player.addAnalyticsListener(statsListener)
            player.addAnalyticsListener(object: androidx.media3.exoplayer.analytics.AnalyticsListener {
                override fun onLoadError(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                    loadEventInfo: androidx.media3.exoplayer.source.LoadEventInfo,
                    mediaLoadData: androidx.media3.exoplayer.source.MediaLoadData,
                    error: java.io.IOException, wasCanceled: Boolean) {
                    errors.put(JSONObject().put("category",com.streamvault.player.playback.PlayerErrorClassifier.classify(error).name)
                        .put("httpStatus",(error as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.responseCode)
                        .put("requestedTrackId",mediaLoadData.trackFormat?.id).put("requestedHeight",mediaLoadData.trackFormat?.height)
                        .put("rendition",loadEventInfo.uri.path?.substringBeforeLast('/'))
                        .put("selectedHeight",player.videoFormat?.height).put("canceled",wasCanceled))
                }
            })
        }
        val samples=JSONArray()
        val began=SystemClock.elapsedRealtime()
        var failedHeight=0
        var failedAt=0L
        var alternate=false
        var terminalAt=0L
        Log.i("AdaptiveFallbackFixture","capture-ready case=$case")
        while(SystemClock.elapsedRealtime()-began < if(eligible) 130_000 else 90_000) {
            compose.waitForIdle()
            val elapsed=SystemClock.elapsedRealtime()-began
            val height=engine.videoFormat.value.height
            if(failedHeight==0 && elapsed>=15_000) {
                check(height in setOf(720,1080))
                failedHeight=height; failedAt=elapsed
                control("fail=${if(height==1080) "hd" else "sd"}&code=$code")
                Log.i("AdaptiveFallbackFixture","fault case=$case selectedHeight=$height status=$code elapsedMs=$elapsed")
            }
            if(failedHeight>0 && errors.length()>0 && height>0 && height!=failedHeight && engine.playbackState.value==PlaybackState.READY) alternate=true
            val failure=engineFailure(engine)
            if(failure!=null && !engine.isPlaying.value && terminalAt==0L) terminalAt=elapsed
            val sample=JSONObject().put("elapsedMs",elapsed).put("positionMs",engine.currentPosition.value)
                .put("state",engine.playbackState.value.name).put("height",height).put("bufferMs",engine.playerStats.value.bufferedDurationMs)
                .put("rebuffers",engine.playerStats.value.rebufferCount)
                .put("terminalErrorCode",failure?.errorCodeName)
                .put("terminalCategory",failure?.let { com.streamvault.player.playback.PlayerErrorClassifier.classify(it).name })
                .put("qualityAuto",engine.availableVideoTracks.value.any { it.id == com.streamvault.player.PLAYER_TRACK_AUTO_ID && it.isSelected })
            instrumentation.runOnMainSync {
                val player=engine.javaClass.getDeclaredField("exoPlayer").apply { isAccessible=true }.get(engine) as ExoPlayer
                sample.put("trackId",player.videoFormat?.id).put("bitrate",player.videoFormat?.bitrate)
                sample.put("renderedBuffers",player.videoDecoderCounters?.renderedOutputBufferCount)
                val selected = JSONArray()
                for (group in player.currentTracks.groups.filter { it.type == androidx.media3.common.C.TRACK_TYPE_VIDEO }) {
                    for (index in 0 until group.length) if (group.isTrackSelected(index)) {
                        val format=group.getTrackFormat(index)
                        selected.put(JSONObject().put("id",format.id).put("height",format.height).put("bitrate",format.bitrate))
                    }
                }
                sample.put("selectedRepresentations",selected)
            }
            samples.put(sample)
            val stats=statsListener.combinedPlaybackStats
            val logs=device.executeShellCommand("logcat -d -s Media3PlayerEngine:I")
            val prepares=logs.lineSequence().count { "prepare resolvedStreamType=HLS" in it }
            val report=JSONObject().put("case",case).put("failedHeight",failedHeight).put("faultElapsedMs",failedAt)
                .put("alternate",alternate).put("terminalElapsedMs",terminalAt).put("prepareCount",prepares)
                .put("samples",samples).put("loadErrors",errors).put("rebufferCountAfterFirstFrame",stats.totalRebufferCount)
                .put("rebufferDurationMsAfterFirstFrame",stats.totalRebufferTimeMs).put("maxRebufferMs",stats.maxRebufferTimeMs)
            File(app.filesDir,"adaptive-fallback-$case.json").writeText(report.toString())
            if(eligible) check(engine.playbackState.value!=PlaybackState.ERROR) { "Eligible fallback became terminal" }
            if(!eligible && terminalAt>0 && elapsed-terminalAt>=15_000) break
            SystemClock.sleep(500)
        }
        assertThat(errors.length()).isGreaterThan(0)
        assertThat(getter(vm,"getCurrentTitle")).isEqualTo(title)
        assertThat(getter(vm,"getCurrentStreamUrl")).isEqualTo(url)
        File(app.filesDir,"adaptive-fallback-$case-session.txt").writeText(device.executeShellCommand("dumpsys media_session"))
        val logs=device.executeShellCommand("logcat -d -s Media3PlayerEngine:I")
        assertThat(logs.lineSequence().count { "prepare resolvedStreamType=HLS" in it }).isEqualTo(1)
        if(eligible) { assertThat(alternate).isTrue(); assertThat(engine.playbackState.value).isEqualTo(PlaybackState.READY); assertThat(engine.isPlaying.value).isTrue() }
        else {
            assertThat(alternate).isFalse(); assertThat(engineFailure(engine)).isNotNull(); assertThat(engine.isPlaying.value).isFalse()
            assertThat(com.streamvault.player.playback.PlayerErrorClassifier.classify(engineFailure(engine)!!).name)
                .isEqualTo(if(case=="limit") "PROVIDER_LIMIT" else "HTTP_AUTH")
        }
        Log.i("AdaptiveFallbackFixture","complete case=$case alternate=$alternate terminalMs=$terminalAt prepares=1")
    }

    @Test fun provisionProtectedMovie() = runBlocking {
        enabled()
        val providers = component("providerRepositoryImplProvider") as ProviderRepository
        val prefs = component("preferencesRepositoryProvider") as PreferencesRepository
        val movies = component("movieRepositoryImplProvider") as MovieRepository
        val file = File(app.filesDir,"adaptive-fallback-drm-private.json")
        check(!file.exists()) { "Protected fixture already provisioned" }
        val original = providers.getActiveProvider().first()?.id ?: error("Active provider required")
        val last = prefs.lastActiveProviderId.first() ?: original
        val id = providers.addProvider(LegacyProvider(name="V4 protected UI validation 20261006",type=ProviderType.M3U,
            serverUrl="http://10.0.2.2:18784",m3uUrl="http://10.0.2.2:18784/drm-playlist.m3u",
            m3uVodClassificationEnabled=true,isActive=false)).getOrNull() ?: error("Protected provider creation failed")
        file.writeText(JSONObject().put("id",id).put("active",original).put("last",last).toString())
        check(providers.refreshProviderData(id,force=true).isSuccess)
        val rows = withTimeout(60_000) { movies.getMovies(id).first { it.size==1 } }
        check(rows.single().name=="V4 Protected Movie UI Fixture")
        val source = movies.getStreamInfo(rows.single()).getOrNull() ?: error("DRM source unavailable")
        check(source.drmInfo?.scheme==com.streamvault.domain.model.DrmScheme.CLEARKEY)
        check(providers.setActiveProvider(id).isSuccess)
        prefs.setLastActiveProviderId(id)
        Log.i("AdaptiveFallbackFixture","protected-provisioned provider=$id drm=CLEARKEY")
        Unit
    }

    @Test fun protectedMovieThroughNormalUi() {
        enabled()
        val mode=InstrumentationRegistry.getArguments().getString("adaptiveFallbackDrm") ?: "invalid"
        check(mode in setOf("valid","invalid"))
        control("reset=1&license=$mode")
        val engine=component("provideMainPlayerEngineProvider") as PlayerEngine
        click("Movies");click("V4 Protected Movie UI Fixture");click("Play")
        lateinit var vm: PlayerViewModel
        await("protected movie context") { var found=false; instrumentation.runOnMainSync { findPlayer(compose.activity.viewModelStore)?.let { vm=it;found=true } };found }
        var terminalError: PlayerError? = null
        if(mode=="valid") {
            await("encrypted first frame") { dismissFixtureResumePrompt(); engine.isPlaying.value && engine.playbackState.value==PlaybackState.READY && engine.playerStats.value.ttffMs>0 }
            assertThat(engine.videoFormat.value.height).isEqualTo(720)
        } else {
            terminalError = runBlocking {
                withTimeout(45_000) {
                    engine.error.filterNotNull().filter { it is PlayerError.DrmError }.first()
                }
            }
        }
        val title=getter(vm,"getCurrentTitle");val url=getter(vm,"getCurrentStreamUrl")
        val samples=JSONArray();val began=SystemClock.elapsedRealtime()
        while(SystemClock.elapsedRealtime()-began<20_000) {
            compose.waitForIdle()
            val failure=engineFailure(engine)
            samples.put(JSONObject().put("elapsedMs",SystemClock.elapsedRealtime()-began)
                .put("state",engine.playbackState.value.name).put("height",engine.videoFormat.value.height)
                .put("positionMs",engine.currentPosition.value).put("rebuffers",engine.playerStats.value.rebufferCount)
                .put("terminalErrorCode",failure?.errorCodeName)
                .put("terminalCategory",failure?.let { com.streamvault.player.playback.PlayerErrorClassifier.classify(it).name }))
            File(app.filesDir,"adaptive-fallback-drm-$mode.json").writeText(samples.toString())
            if(mode=="valid") check(engine.playbackState.value==PlaybackState.READY)
            else if (terminalError is PlayerError.DrmError) {
                // A terminal failure is sampled until the test ends; the player
                // must not be allowed to drift into an alternate rendition.
                check(!engine.isPlaying.value)
            }
            SystemClock.sleep(500)
        }
        assertThat(getter(vm,"getCurrentTitle")).isEqualTo(title)
        assertThat(getter(vm,"getCurrentStreamUrl")).isEqualTo(url)
        File(app.filesDir,"adaptive-fallback-drm-$mode-session.txt").writeText(device.executeShellCommand("dumpsys media_session"))
        val logs=device.executeShellCommand("logcat -d -s Media3PlayerEngine:I")
        assertThat(logs.lineSequence().count { "prepare resolvedStreamType=DASH" in it }).isEqualTo(1)
        if(mode=="invalid") {
            assertThat(logs).contains("fatal-error category=DRM")
            assertThat(terminalError).isInstanceOf(PlayerError.DrmError::class.java)
        }
        device.takeScreenshot(File(app.filesDir,"adaptive-fallback-drm-$mode.png"))
        Log.i("AdaptiveFallbackFixture","protected-complete mode=$mode prepares=1 state=${engine.playbackState.value}")
    }

    @Test fun cleanupProtectedMovie() = runBlocking {
        enabled()
        val file=File(app.filesDir,"adaptive-fallback-drm-private.json")
        val saved=JSONObject(file.readText())
        val providers=component("providerRepositoryImplProvider") as ProviderRepository
        val prefs=component("preferencesRepositoryProvider") as PreferencesRepository
        check(providers.getProvider(saved.getLong("id"))?.name=="V4 protected UI validation 20261006")
        check(providers.deleteProvider(saved.getLong("id")).isSuccess)
        check(providers.setActiveProvider(saved.getLong("active")).isSuccess)
        prefs.setLastActiveProviderId(saved.getLong("last"))
        check(file.delete())
    }
    @Test fun restoreOnlyFixtureCompatibilityLearning() {
        enabled()
        val file = File(app.filesDir, "adaptive-fallback-compatibility-private.json")
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
