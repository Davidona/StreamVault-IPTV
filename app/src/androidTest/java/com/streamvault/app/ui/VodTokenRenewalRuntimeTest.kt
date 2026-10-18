package com.streamvault.app.ui

import android.app.Application
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.google.common.truth.Truth.assertThat
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.domain.model.LegacyProvider
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
import java.net.URL
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

/** Opt-in normal Movies UI validation for changed authenticated playback requests. */
@RunWith(AndroidJUnit4::class)
class VodTokenRenewalRuntimeTest {
    @get:Rule val compose = createAndroidComposeRule<com.streamvault.app.MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val device get() = UiDevice.getInstance(instrumentation)
    private val setup get() = File(app.filesDir, "v11-renewal-fixture-private.json")

    private fun enabled() = assumeTrue(InstrumentationRegistry.getArguments().getString("v11Fixture") == "true")
    private fun component(name: String): Any {
        val graph = (app as GeneratedComponentManager<*>).generatedComponent()
        return (graph.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(graph) as Provider<*>).get()!!
    }

    private fun await(label: String, timeoutMs: Long = 45_000, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < end) {
            compose.waitForIdle()
            if (condition()) return
            SystemClock.sleep(100)
        }
        if (!condition()) {
            device.takeScreenshot(File(app.filesDir, "v11-renewal-driver.png"))
            device.dumpWindowHierarchy(File(app.filesDir, "v11-renewal-driver.xml"))
            error("Timed out: $label")
        }
    }

    private fun click(text: String) {
        compose.waitForIdle()
        if (text == "Play") {
            val playOrResume = hasText("Play") or hasText("Resume from", substring = true)
            compose.waitUntil(10_000) { compose.onAllNodes(playOrResume).fetchSemanticsNodes().size == 1 }
            compose.onNode(playOrResume).performSemanticsAction(SemanticsActions.OnClick) { it() }
        } else {
            val node = device.wait(Until.findObject(By.textContains(text)), 5_000)
                ?: device.wait(Until.findObject(By.descContains(text)), 5_000)
                ?: error("Missing UI: $text")
            val bounds = node.visibleBounds
            device.executeShellCommand("input mouse tap ${bounds.centerX()} ${bounds.centerY()}")
        }
        compose.waitForIdle()
    }

    private fun navigateToMoviesTab() {
        repeat(4) {
            compose.waitForIdle()
            if (device.wait(Until.findObject(By.text("Movies")), 100) != null) return
            device.pressBack()
            SystemClock.sleep(250)
        }
        error("Movies tab did not become visible after backing out of the prior screen")
    }

    private fun findPlayer(store: ViewModelStore): PlayerViewModel? {
        for (key in store.keys()) {
            val model = store[key] ?: continue
            if (model is PlayerViewModel) return model
            for (field in model.javaClass.declaredFields.filter { Map::class.java.isAssignableFrom(it.type) }) {
                val nestedStores = (field.apply { isAccessible = true }.get(model) as? Map<*, *>)
                    ?.values?.filterIsInstance<ViewModelStore>().orEmpty()
                for (nested in nestedStores) findPlayer(nested)?.let { return it }
            }
        }
        return null
    }

    private fun getter(owner: Any, name: String): Any? = owner.javaClass.methods
        .single { it.name.substringBefore('$') == name && it.parameterCount == 0 }.invoke(owner)

    private fun source(engine: PlayerEngine): StreamInfo = engine.javaClass
        .getDeclaredField("lastStreamInfo").apply { isAccessible = true }.get(engine) as StreamInfo

    private fun providerId(): Long = JSONObject(setup.readText()).getLong("id")

    private fun control(accepted: String, reset: Boolean = false) {
        val resetArg = if (reset) "&reset=1" else ""
        URL("http://10.0.2.2:18774/control?accept=$accepted$resetArg").readText()
    }

    private fun serverState(): JSONObject = JSONObject(URL("http://10.0.2.2:18774/events").readText())

    private fun logs(tag: String): String = device.executeShellCommand("logcat -d -s $tag:I")

    private fun applyCredential(provider: LegacyProvider, credential: String): LegacyProvider = when (credential) {
        "old" -> provider.copy(username = "old-user", password = "old-pass")
        "next" -> provider.copy(username = "next-user", password = "next-pass")
        "third" -> provider.copy(username = "third-user", password = "third-pass")
        "invalid" -> provider.copy(username = "invalid-user", password = "invalid-pass")
        else -> error("Unknown fixture credential state")
    }

    private suspend fun updateCredential(credential: String) {
        val providers = component("providerRepositoryImplProvider") as ProviderRepository
        val provider = providers.getProvider(providerId()) ?: error("V11 fixture provider disappeared")
        check(provider.name == FIXTURE_NAME) { "Refusing to update a provider not owned by this fixture" }
        check(providers.updateProvider(applyCredential(provider, credential)).isSuccess)
    }

    private fun startRenewal(vm: PlayerViewModel, expiryAtMs: Long, checkAtMs: Long) {
        val function = Class.forName("com.streamvault.feature.playback.player.PlayerLifecycleActionsKt")
            .methods.single { it.name == "startTokenRenewalMonitoring" && it.parameterCount == 3 }
        instrumentation.runOnMainSync { function.invoke(null, vm, expiryAtMs, checkAtMs) }
    }

    private fun history(vm: PlayerViewModel, engine: PlayerEngine): PlaybackHistory = vm.javaClass.methods
        .single { it.name.substringBefore('$') == "buildPlaybackHistorySnapshot" && it.parameterCount == 2 }
        .invoke(vm, engine.currentPosition.value, engine.duration.value) as PlaybackHistory

    private fun enterPlayback(title: String, engine: PlayerEngine): PlayerViewModel {
        navigateToMoviesTab()
        click("Movies")
        await("movie catalog row: $title") {
            device.wait(Until.findObject(By.textContains(title)), 100) != null ||
                device.wait(Until.findObject(By.descContains(title)), 100) != null
        }
        click(title)
        click("Play")
        lateinit var vm: PlayerViewModel
        await("player context") {
            var found = false
            instrumentation.runOnMainSync {
                findPlayer(compose.activity.viewModelStore)?.let { vm = it; found = true }
            }
            found
        }
        SystemClock.sleep(1_500)
        if (vm.resumePrompt.value.show) {
            compose.onNodeWithText("Resume").performSemanticsAction(SemanticsActions.OnClick) { it() }
        }
        await("first frame") {
            engine.isPlaying.value && engine.playbackState.value == PlaybackState.READY &&
                engine.playerStats.value.ttffMs > 0
        }
        device.wait(Until.findObject(By.text("Resume")), 5_000)?.let {
            compose.onNodeWithText("Resume").performSemanticsAction(SemanticsActions.OnClick) { it() }
        }
        await("resume prompt dismissed") { !vm.resumePrompt.value.show && engine.isPlaying.value }
        return vm
    }

    private fun selectProgressiveLanguages(engine: PlayerEngine) {
        await("Spanish and English subtitle tracks") {
            engine.availableAudioTracks.value.any { it.language in listOf("es", "spa") } &&
                engine.availableSubtitleTracks.value.any { it.language in listOf("en", "eng") }
        }
        device.pressDPadCenter()
        compose.waitForIdle()
        val spanish = engine.availableAudioTracks.value.first { it.language in listOf("es", "spa") }.name
        val english = engine.availableSubtitleTracks.value.first { it.language in listOf("en", "eng") }.name
        compose.onNodeWithContentDescription("Audio").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText(spanish, substring = true).performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithContentDescription("Subs").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText(english, substring = true).performSemanticsAction(SemanticsActions.OnClick) { it() }
        await("selected fixture languages") {
            engine.availableAudioTracks.value.any { it.isSelected && it.language in listOf("es", "spa") } &&
                engine.availableSubtitleTracks.value.any { it.isSelected && it.language in listOf("en", "eng") }
        }
    }

    private fun seekToNonzero(engine: PlayerEngine): Long {
        instrumentation.runOnMainSync { engine.seekTo(60_000); engine.play() }
        await("nonzero seek resumes") {
            engine.currentPosition.value in 59_000..70_000 && engine.isPlaying.value &&
                engine.playbackState.value == PlaybackState.READY
        }
        return engine.currentPosition.value
    }

    private fun mediaEvents(state: JSONObject): List<JSONObject> =
        state.getJSONArray("events").let { rows -> (0 until rows.length()).map { rows.getJSONObject(it) } }

    @Test
    fun provisionIsolatedProvider() = runBlocking {
        enabled()
        control("old", reset = true)
        val providers = component("providerRepositoryImplProvider") as ProviderRepository
        val prefs = component("preferencesRepositoryProvider") as PreferencesRepository
        val movies = component("movieRepositoryImplProvider") as MovieRepository
        if (setup.exists()) {
            val existingId = JSONObject(setup.readText()).getLong("id")
            if (providers.getProvider(existingId)?.name != FIXTURE_NAME) setup.delete()
        }

        if (!setup.exists()) {
            val originalActive = providers.getActiveProvider().first()?.id ?: -1L
            val originalLast = prefs.lastActiveProviderId.first() ?: -1L
            val context = prefs.javaClass.getDeclaredField("context").apply { isAccessible = true }.get(prefs) as Context
            @Suppress("UNCHECKED_CAST")
            val store = prefs.javaClass.getDeclaredMethod("getDataStore", Context::class.java).apply { isAccessible = true }
                .invoke(prefs, context) as DataStore<Preferences>
            val snapshot = store.data.first()
            val savedPreferences = JSONObject()
            for (key in PREFERENCE_KEYS) {
                savedPreferences.put(key, snapshot[stringPreferencesKey(key)] ?: JSONObject.NULL)
            }
            val id = providers.addProvider(
                LegacyProvider(
                    name = FIXTURE_NAME,
                    type = ProviderType.XTREAM_CODES,
                    serverUrl = FIXTURE_SERVER,
                    username = "old-user",
                    password = "old-pass",
                    maxConnections = 5,
                    isActive = false,
                    xtreamFastSyncEnabled = false
                )
            ).getOrNull() ?: error("V11 fixture provider creation failed")
            setup.writeText(
                JSONObject().put("id", id).put("active", originalActive).put("last", originalLast)
                    .put("preferences", savedPreferences).toString()
            )
        }

        val id = providerId()
        check(providers.getProvider(id)?.name == FIXTURE_NAME)
        check(providers.refreshProviderData(id, force = true).isSuccess)
        withTimeout(60_000) { movies.getCategories(id).first { it.isNotEmpty() } }
        val sync = component("syncManagerProvider") as com.streamvault.data.sync.ProviderSyncCommands
        check(sync.processQueuedXtreamIndexJobs(id, section = com.streamvault.domain.model.ContentType.MOVIE, force = true).isSuccess)
        val rows = withTimeout(60_000) {
            movies.getMovies(id).first { items -> items.size == 2 && items.all { it.name.startsWith("V11 ") } }
        }
        check(rows.map { it.name }.toSet() == setOf(HLS_TITLE, PROGRESSIVE_TITLE))
        check(providers.setActiveProvider(id).isSuccess)
        prefs.setLastActiveProviderId(id)
        control("old", reset = true)
        Log.i("VodTokenRenewal", "provisioned provider=$id movies=${rows.size} maxConnections=5")
        Unit
    }

    @Test
    fun changedAuthenticatedRequestRenewsCurrentItem() = runBlocking {
        enabled()
        val case = InstrumentationRegistry.getArguments().getString("v11Case") ?: "adaptive"
        check(case in setOf("adaptive", "progressive"))
        updateCredential("old")
        control("old", reset = true)
        device.executeShellCommand("logcat -c")

        val engine = component("provideMainPlayerEngineProvider") as PlayerEngine
        val title = if (case == "adaptive") HLS_TITLE else PROGRESSIVE_TITLE
        val vm = enterPlayback(title, engine)

        if (case == "adaptive") {
            await("adaptive variants") { engine.availableVideoTracks.value.any { it.name.contains("1080p", ignoreCase = true) } }
            val manual = engine.availableVideoTracks.value.first { it.name.contains("1080p", ignoreCase = true) }
            Log.i("VodTokenRenewal", "manual-video-selection id=${manual.id} name=${manual.name}")
            instrumentation.runOnMainSync { engine.selectVideoTrack(manual.id) }
            await("manual 1080p selection") {
                engine.videoFormat.value.height == 1080 &&
                    engine.availableVideoTracks.value.any { it.isSelected && it.name.contains("1080p", ignoreCase = true) }
            }
        } else {
            selectProgressiveLanguages(engine)
        }

        val logicalTitle = getter(vm, "getCurrentTitle")
        val logicalUrl = getter(vm, "getCurrentStreamUrl")
        val contentId = history(vm, engine).contentId
        val positionBefore = seekToNonzero(engine)
        val oldPhysicalUrl = source(engine).url
        assertThat(oldPhysicalUrl).contains("/movie/old-user/old-pass/")

        updateCredential("next")
        control("old,next")
        val handoffStartedAt = SystemClock.elapsedRealtime()
        val priorFirstFrames = logs("Media3PlayerEngine").split("first-frame-success").size - 1
        startRenewal(vm, System.currentTimeMillis() + 60_000L, System.currentTimeMillis())

        await("new physical credential URL") { source(engine).url.contains("/movie/next-user/next-pass/") }
        await("new authenticated media request") {
            mediaEvents(serverState()).any { it.optString("event") == "open" && it.optString("credential") == "next" }
        }
        await("first frame after credential renewal", 30_000) {
            logs("Media3PlayerEngine").split("first-frame-success").size - 1 > priorFirstFrames
        }
        await("playback advances after credential renewal") {
            engine.playbackState.value == PlaybackState.READY && engine.isPlaying.value &&
                engine.currentPosition.value >= positionBefore + 2_000
        }
        val handoffMs = SystemClock.elapsedRealtime() - handoffStartedAt
        val afterHistory = history(vm, engine)
        assertThat(getter(vm, "getCurrentTitle")).isEqualTo(logicalTitle)
        assertThat(getter(vm, "getCurrentStreamUrl")).isEqualTo(logicalUrl)
        assertThat(afterHistory.streamUrl).isEqualTo(logicalUrl)
        assertThat(afterHistory.contentId).isEqualTo(contentId)
        assertThat(afterHistory.resumePositionMs).isAtLeast(59_000)
        assertThat(engine.currentPosition.value).isAtLeast(positionBefore - 1_000)
        assertThat(source(engine).url).isNotEqualTo(oldPhysicalUrl)

        if (case == "adaptive") {
            Log.i(
                "VodTokenRenewal",
                "adaptive-track-state height=${engine.videoFormat.value.height} tracks=" +
                    engine.availableVideoTracks.value.joinToString("|") { "${it.name}:${it.id}:${it.isSelected}" }
            )
            assertThat(engine.videoFormat.value.height).isEqualTo(1080)
            assertThat(engine.availableVideoTracks.value.any {
                it.isSelected && it.name.contains("1080p", ignoreCase = true)
            }).isTrue()
        } else {
            assertThat(engine.availableAudioTracks.value.any { it.isSelected && it.language in listOf("es", "spa") }).isTrue()
            assertThat(engine.availableSubtitleTracks.value.any { it.isSelected && it.language in listOf("en", "eng") }).isTrue()
        }

        // After handoff, retire the old credential at the server and observe that the player
        // makes no more requests with it.
        control("next")
        SystemClock.sleep(2_000)
        val finalServer = serverState()
        val events = mediaEvents(finalServer)
        assertThat(events.any { it.optString("event") == "open" && it.optString("credential") == "old" }).isTrue()
        assertThat(events.any { it.optString("event") == "open" && it.optString("credential") == "next" }).isTrue()
        val active = finalServer.getJSONObject("active")
        val activeNames = active.names() ?: JSONArray()
        assertThat((0 until activeNames.length()).map { active.getJSONObject(activeNames.getString(it)).optString("credential") })
            .doesNotContain("old")
        assertThat(finalServer.getInt("peakActive")).isAtMost(5)
        val vmLogs = logs("PlayerVM")
        assertThat(vmLogs).contains("token-renewal request-changed=true")
        assertThat(vmLogs.split("token-renewal request-changed=true").size - 1).isEqualTo(1)
        val engineLogs = logs("Media3PlayerEngine")
        assertThat(engineLogs.split("renew-url resolvedStreamType=").size - 1).isEqualTo(1)
        assertThat(engineLogs).doesNotContain("fatal-error")
        Log.i("VodTokenRenewal", "handoff case=$case positionBefore=$positionBefore positionAfter=${engine.currentPosition.value} interruptionObservedMs=$handoffMs oldRequestRetired=true peakActive=${finalServer.getInt("peakActive")} renewals=1")
        Unit
    }

    @Test
    fun pendingRenewalIsCancelledWhenNavigationChangesContent() = runBlocking {
        enabled()
        updateCredential("old")
        control("old,next,third", reset = true)
        device.executeShellCommand("logcat -c")
        val engine = component("provideMainPlayerEngineProvider") as PlayerEngine
        val first = enterPlayback(HLS_TITLE, engine)
        val scheduledAt = System.currentTimeMillis() + 7_000L
        startRenewal(first, scheduledAt + 60_000L, scheduledAt)

        updateCredential("next")
        device.pressBack()
        SystemClock.sleep(250)
        device.pressBack()
        await("movie detail with a second title") {
            device.wait(Until.findObject(By.desc(PROGRESSIVE_TITLE)), 100) != null
        }
        compose.onNodeWithContentDescription(PROGRESSIVE_TITLE)
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        click("Play")
        lateinit var second: PlayerViewModel
        await("second player context") {
            var found = false
            instrumentation.runOnMainSync {
                findPlayer(compose.activity.viewModelStore)?.let { second = it; found = true }
            }
            found && getter(second, "getCurrentTitle") == PROGRESSIVE_TITLE
        }
        await("second source ready") { engine.isPlaying.value && engine.playbackState.value == PlaybackState.READY }
        assertThat(source(engine).url).contains("/movie/next-user/next-pass/")
        val secondLogicalUrl = getter(second, "getCurrentStreamUrl")
        updateCredential("third")

        val remaining = (scheduledAt + 4_000L - System.currentTimeMillis()).coerceAtLeast(0L)
        SystemClock.sleep(remaining)
        await("second playback remains healthy") { engine.isPlaying.value && engine.playbackState.value == PlaybackState.READY }
        assertThat(getter(second, "getCurrentTitle")).isEqualTo(PROGRESSIVE_TITLE)
        assertThat(getter(second, "getCurrentStreamUrl")).isEqualTo(secondLogicalUrl)
        assertThat(source(engine).url).contains("/movie/next-user/next-pass/")
        assertThat(logs("Media3PlayerEngine")).doesNotContain("renew-url resolvedStreamType=")
        Log.i("VodTokenRenewal", "navigation-cancellation oldSessionRenewal=cancelled secondItemRetained=true")
        Unit
    }

    @Test
    fun invalidReplacementCredentialsStopRenewalAttempts() = runBlocking {
        enabled()
        updateCredential("old")
        control("old", reset = true)
        device.executeShellCommand("logcat -c")
        val engine = component("provideMainPlayerEngineProvider") as PlayerEngine
        val vm = enterPlayback(PROGRESSIVE_TITLE, engine)
        selectProgressiveLanguages(engine)
        val logicalTitle = getter(vm, "getCurrentTitle")
        val logicalUrl = getter(vm, "getCurrentStreamUrl")
        seekToNonzero(engine)

        updateCredential("invalid")
        startRenewal(vm, System.currentTimeMillis() + 60_000L, System.currentTimeMillis())
        control("none")
        await("invalid physical credential URL") { source(engine).url.contains("/movie/invalid-user/invalid-pass/") }
        await("invalid credentials rejected") {
            mediaEvents(serverState()).any {
                it.optString("event") == "media" && it.optString("credential") == "invalid" && it.optInt("status") == 401
            }
        }
        await("terminal authentication classification", 30_000) {
            logs("Media3PlayerEngine").contains("fatal-error category=HTTP_AUTH reason=terminal-auth")
        }
        val initialInvalidAttempts = mediaEvents(serverState()).count {
            it.optString("event") == "media" && it.optString("credential") == "invalid" && it.optInt("status") == 401
        }
        SystemClock.sleep(10_000)
        val finalEvents = mediaEvents(serverState())
        val finalInvalidAttempts = finalEvents.count {
            it.optString("event") == "media" && it.optString("credential") == "invalid" && it.optInt("status") == 401
        }
        assertThat(finalInvalidAttempts).isEqualTo(initialInvalidAttempts)
        assertThat(getter(vm, "getCurrentTitle")).isEqualTo(logicalTitle)
        assertThat(getter(vm, "getCurrentStreamUrl")).isEqualTo(logicalUrl)
        assertThat(logs("PlayerVM").split("token-renewal request-changed=true").size - 1).isEqualTo(1)
        assertThat(logs("Media3PlayerEngine").split("renew-url resolvedStreamType=").size - 1).isEqualTo(1)
        Log.i("VodTokenRenewal", "invalid-credentials terminal=true invalidRequests=$finalInvalidAttempts renewalAttempts=1 retryWindowMs=10000")
        Unit
    }

    @Test
    fun cleanupOnlyIsolatedProvider() = runBlocking {
        enabled()
        check(setup.exists()) { "V11 fixture setup record is missing" }
        val original = JSONObject(setup.readText())
        val providers = component("providerRepositoryImplProvider") as ProviderRepository
        val prefs = component("preferencesRepositoryProvider") as PreferencesRepository
        val fixtureId = original.getLong("id")
        check(providers.getProvider(fixtureId)?.name == FIXTURE_NAME)
        check(providers.deleteProvider(fixtureId).isSuccess)
        val originalActive = original.getLong("active")
        if (originalActive > 0L) check(providers.setActiveProvider(originalActive).isSuccess)
        val originalLast = original.getLong("last")
        if (originalLast > 0L) prefs.setLastActiveProviderId(originalLast)

        val context = prefs.javaClass.getDeclaredField("context").apply { isAccessible = true }.get(prefs) as Context
        @Suppress("UNCHECKED_CAST")
        val store = prefs.javaClass.getDeclaredMethod("getDataStore", Context::class.java).apply { isAccessible = true }
            .invoke(prefs, context) as DataStore<Preferences>
        val savedPreferences = original.getJSONObject("preferences")
        store.edit { values ->
            for (key in PREFERENCE_KEYS) {
                val saved = savedPreferences.opt(key)
                if (saved == null || saved === JSONObject.NULL) values.remove(stringPreferencesKey(key))
                else values[stringPreferencesKey(key)] = saved.toString()
            }
        }
        check(providers.getProvider(fixtureId) == null)
        if (originalActive > 0L) check(providers.getActiveProvider().first()?.id == originalActive)
        if (originalLast > 0L) check(prefs.lastActiveProviderId.first() == originalLast)
        setup.delete()
        File(app.filesDir, "v11-renewal-driver.png").delete()
        File(app.filesDir, "v11-renewal-driver.xml").delete()
        Log.i("VodTokenRenewal", "cleanup fixtureProvider=$fixtureId restoredActive=$originalActive restoredLast=$originalLast")
        Unit
    }

    private companion object {
        const val FIXTURE_NAME = "V11 auth rollover UI fixture 20261004"
        const val FIXTURE_SERVER = "http://10.0.2.2:18774"
        const val HLS_TITLE = "V11 HLS Adaptive Fixture"
        const val PROGRESSIVE_TITLE = "V11 Progressive Tracks Fixture"
        val PREFERENCE_KEYS = listOf(
            "player_vod_track_global_preferences",
            "player_vod_track_preferences",
            "vod_duplicate_handling_mode",
            "vod_variant_observations"
        )
    }
}
