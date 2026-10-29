package com.streamvault.app.tv

import android.content.ContentValues
import android.media.tv.TvContract
import android.media.tv.TvView
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.streamvault.app.MainActivity
import com.streamvault.data.local.StreamVaultDatabase
import com.streamvault.data.local.entity.ChannelEntity
import com.streamvault.data.local.entity.ProviderEntity
import com.streamvault.domain.model.ProviderType
import dagger.hilt.internal.GeneratedComponentManager
import javax.inject.Provider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Explicitly opted-in loopback fixtures, tuned through the real Android TV framework. */
@RunWith(AndroidJUnit4::class)
class TvInputPlaybackRuntimeTest {
    @Test
    fun fixtureCleanupIsComplete() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("tvInputCleanupAudit") == "true")
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val component = (app as GeneratedComponentManager<*>).generatedComponent()
        val db = (component.javaClass.getDeclaredField("provideDatabaseProvider").apply {
            isAccessible = true
        }.get(component) as Provider<*>).get() as StreamVaultDatabase
        val counts = JSONObject()
        for ((name, query) in listOf(
            "providers" to "SELECT COUNT(*) FROM providers WHERE name LIKE 'TIF fixture %'",
            "configs" to "SELECT COUNT(*) FROM provider_configs WHERE identity_key LIKE 'tif-fixture-%'",
            "channels" to "SELECT COUNT(*) FROM channels WHERE name LIKE 'TIF fixture %'"
        )) {
            db.openHelper.readableDatabase.query(query).use { cursor ->
                check(cursor.moveToFirst())
                counts.put(name, cursor.getInt(0))
            }
        }
        app.contentResolver.query(TvContract.buildChannelsUriForInput(
            "${app.packageName}/com.streamvault.app.tvinput.StreamVaultTvInputService"),
            arrayOf(TvContract.Channels.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
            var owned = 0
            while (cursor.moveToNext()) if (cursor.getString(0)?.startsWith("TIF fixture ") == true) owned++
            counts.put("frameworkChannels", owned)
        }
        app.filesDir.resolve("tif-cleanup-audit.json").writeText(counts.toString(2))
        for (key in listOf("providers", "configs", "channels", "frameworkChannels")) {
            assertTrue("Fixture rows remain: $counts", counts.getInt(key) == 0)
        }
    }

    @Test
    fun tuneFixtureChannels() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("tvInputFixture") == "true")
        val runId = requireNotNull(args.getString("runId"))
        require(runId.matches(Regex("[A-Za-z0-9_-]{1,40}")))
        val urls = requireNotNull(args.getString("fixtureUrls")).split(',')
        urls.forEach { require(Uri.parse(it).host in setOf("127.0.0.1", "localhost")) }
        val duration = args.getString("durationSeconds")?.toLong() ?: 130L
        require(duration in 10L..180L)
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val component = (app as GeneratedComponentManager<*>).generatedComponent()
        val db = (component.javaClass.getDeclaredField("provideDatabaseProvider").apply {
            isAccessible = true
        }.get(component) as Provider<*>).get() as StreamVaultDatabase
        val inputId = "${app.packageName}/com.streamvault.app.tvinput.StreamVaultTvInputService"
        val preferences = (component.javaClass.getDeclaredField("preferencesRepositoryProvider").apply {
            isAccessible = true
        }.get(component) as Provider<*>).get() as com.streamvault.data.preferences.PreferencesRepository
        @Suppress("UNCHECKED_CAST")
        val preferenceStore = preferences.javaClass.getDeclaredMethod("getPreferencesDataStore").apply {
            isAccessible = true
        }.invoke(preferences) as DataStore<Preferences>
        val originalPreferences = preferenceStore.data.first()
        val originalAutomaticConfiguration = preferences.automaticSyncConfiguration.first()
        val providerId = db.providerDao().insert(ProviderEntity(
            name = "TIF fixture $runId", type = ProviderType.M3U, isActive = false
        ))
        val previousAutomaticOverride = originalAutomaticConfiguration.providers[providerId]
        val rows = mutableListOf<Uri>()
        val events = JSONArray()
        val available = AtomicBoolean(false)
        val terminal = AtomicBoolean(false)
        val availableCount = AtomicInteger(0)
        val expectTerminal = args.getString("expectTerminal") == "true"
        val expectEofRecovery = args.getString("expectEofRecovery") == "true"
        val expectEofTerminal = args.getString("expectEofTerminal") == "true"
        val observeOutage = args.getString("observeOutage") == "true"
        val verifyOwnership = args.getString("tvInputVerifyOwnership") == "true"
        val surfaceCycle = args.getString("tvInputSurfaceCycle") == "true"
        val surfaceCallbackDelayMs = args.getString("tvInputSurfaceCallbackDelayMs")?.toLong() ?: 0L
        require(surfaceCallbackDelayMs in 0L..250L)
        val releaseWaitMs = args.getString("tvInputReleaseWaitMs")?.toLong() ?: 0L
        require(releaseWaitMs in 0L..5_000L)
        var currentIndex = -1
        fun event(name: String, detail: String = "") {
            val row = JSONObject().put("elapsedMs", SystemClock.elapsedRealtime())
                .put("channelIndex", currentIndex).put("event", name).put("detail", detail)
            synchronized(events) { events.put(row) }
            Log.i("TvInputFixture", row.toString())
        }
        var scenario: ActivityScenario<MainActivity>? = null
        lateinit var view: TvView
        var viewInitialized = false
        var workerProbe: com.streamvault.app.ui.PlaybackWorkerAdmissionProbe? = null
        var catalogProbe: com.streamvault.app.ui.PlaybackInflightCatalogProbe? = null
        var guideProbe: com.streamvault.app.ui.PlaybackInflightEpgProbe? = null
        var indexProbe: com.streamvault.app.ui.PlaybackIndexWorkProbe? = null
        var playbackProviderId = providerId
        // The normal catalog publisher removes non-active provider channels. Hold
        // its existing serialization lock while these temporary framework rows exist.
        val catalogMutex = com.streamvault.app.tvinput.TvInputChannelSyncManager::class.java
            .getDeclaredField("syncMutex").apply { isAccessible = true }.get(null)
            as kotlinx.coroutines.sync.Mutex
        catalogMutex.lock()
        try {
            // The fixture config contains a playback URL, not a catalog playlist. Keep app-wide
            // automatic provider scans from importing it while this disposable provider exists.
            preferences.setAutomaticSyncSettings(providerId,
                com.streamvault.domain.sync.AutomaticSyncSettings(enabled = false, live = false,
                    movies = false, series = false, epg = false))
            event("automatic-sync-isolated")
            db.providerSnapshotDao().commitConfiguration(com.streamvault.data.local.entity.ProviderConfigEntity(
                providerId = providerId, type = ProviderType.M3U, schemaVersion = 1,
                configurationGeneration = 1, identityKey = "tif-fixture-$runId",
                encryptedConfigJson = com.google.gson.Gson().toJson(
                    com.streamvault.domain.model.M3uConfig(playlistUrl = urls.first())),
                updatedAt = System.currentTimeMillis()
            ))
            urls.forEachIndexed { index, url ->
                val id = db.channelDao().insert(ChannelEntity(name = "TIF fixture $index",
                    providerId = providerId, streamId = index + 1L, streamUrl = url))
                rows += requireNotNull(app.contentResolver.insert(TvContract.Channels.CONTENT_URI,
                    ContentValues().apply {
                        put(TvContract.Channels.COLUMN_INPUT_ID, inputId)
                        put(TvContract.Channels.COLUMN_TYPE, TvContract.Channels.TYPE_OTHER)
                        put(TvContract.Channels.COLUMN_DISPLAY_NUMBER, "990${index}")
                        put(TvContract.Channels.COLUMN_DISPLAY_NAME, "TIF fixture $index")
                        put(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_DATA, "$providerId:$id")
                    }))
            }
            scenario = ActivityScenario.launch(MainActivity::class.java)
            scenario.onActivity { activity ->
                view = TvView(activity)
                viewInitialized = true
                view.setCallback(object : TvView.TvInputCallback() {
                    override fun onVideoAvailable(inputId: String) {
                        availableCount.incrementAndGet()
                        available.set(true); event("available")
                    }
                    override fun onVideoUnavailable(inputId: String, reason: Int) {
                        available.set(false); event("unavailable", reason.toString())
                        if (reason == android.media.tv.TvInputManager.VIDEO_UNAVAILABLE_REASON_UNKNOWN) terminal.set(true)
                    }
                    override fun onConnectionFailed(inputId: String) { event("connection-failed") }
                    override fun onDisconnected(inputId: String) { event("disconnected") }
                    override fun onChannelRetuned(inputId: String, channelUri: Uri) { event("retuned", channelUri.toString()) }
                })
                // TvView secures its SurfaceView by default. Only these explicitly
                // opted-in, unencrypted loopback fixtures permit screenshot capture.
                (view.getChildAt(0) as android.view.SurfaceView).setSecure(false)
                activity.setContentView(view)
            }
            // Admit and checkpoint actual imports while idle, before the TV tune
            // acquires playback ownership. Each probe owns only its fixture jobs.
            catalogProbe = com.streamvault.app.ui.PlaybackInflightCatalogProbe.start(app, args, runId) { row ->
                event("catalog-probe", row.toString())
            }
            guideProbe = com.streamvault.app.ui.PlaybackInflightEpgProbe.start(app, component, args, runId) { row ->
                event("guide-probe", row.toString())
            }
            indexProbe = com.streamvault.app.ui.PlaybackIndexWorkProbe.start(app, args, runId) { row ->
                event("index-probe", row.toString())
            }
            rows.forEachIndexed { index, uri ->
                indexProbe?.navigation(urls[index])?.let { navigation ->
                    playbackProviderId = checkNotNull(navigation.providerId)
                    check(navigation.internalId > 0L)
                    check(app.contentResolver.update(uri, ContentValues().apply {
                        put(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_DATA,
                            "$playbackProviderId:${navigation.internalId}")
                    }, null, null) == 1)
                    event("framework-provider-linked", JSONObject().put("providerId",playbackProviderId)
                        .put("internalId",navigation.internalId).toString())
                }
                scenario.onActivity {
                    currentIndex = index
                    available.set(false)
                    terminal.set(false)
                    availableCount.set(0)
                    event("tune")
                    view.tune(inputId, uri)
                }
                val deadline = SystemClock.elapsedRealtime() + 150_000L
                while (!available.get() && !terminal.get() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(100)
                if (expectTerminal) {
                    assertTrue("Expected bounded terminal startup failure", terminal.get() && !available.get())
                    if (verifyOwnership) assertTrue("Terminal TV input retained ownership",
                        !com.streamvault.domain.sync.AutomaticSyncActivity.isProviderPlaybackActive(playbackProviderId))
                    event("terminal-observed")
                    Thread.sleep(5_000L)
                    return@forEachIndexed
                }
                assertTrue("TV input never reported video available for fixture $index", available.get())
                if (verifyOwnership) {
                    assertTrue("TV input missing provider ownership",
                        com.streamvault.domain.sync.AutomaticSyncActivity.isProviderPlaybackActive(playbackProviderId))
                    event("ownership-context", JSONObject().put("providerId", playbackProviderId)
                        .put("global", com.streamvault.domain.sync.AutomaticSyncActivity.hasPlayback)
                        .put("provider", true).toString())
                }
                if (expectEofRecovery) {
                    event("initial-video-available")
                    val recoveryDeadline = SystemClock.elapsedRealtime() + 90_000L
                    while (availableCount.get() < 2 && !terminal.get() && SystemClock.elapsedRealtime() < recoveryDeadline) Thread.sleep(100)
                    assertTrue("Expected one EOF recovery before steady capture", availableCount.get() == 2 && available.get())
                    event("eof-recovery-observed")
                }
                if (surfaceCycle) {
                    val recovered = cycleTvViewSurface(
                        scenario = checkNotNull(scenario),
                        view = view,
                        available = available,
                        terminal = terminal,
                        availableCount = availableCount,
                        providerId = playbackProviderId,
                        checkOwnership = verifyOwnership,
                        callbackDelayMs = surfaceCallbackDelayMs,
                        emit = ::event
                    )
                    assertTrue("TV input did not recover after the diagnostic surface cycle", recovered)
                }
                catalogProbe?.playbackReady()
                guideProbe?.playbackReady()
                indexProbe?.verifyPlaybackContext(playbackProviderId, "TV_INPUT")
                indexProbe?.playbackReady()
                event("capture-begin")
                workerProbe = com.streamvault.app.ui.PlaybackWorkerAdmissionProbe.start(app, args, runId) { row ->
                    event("worker-probe", row.toString())
                }
                val end = SystemClock.elapsedRealtime() + duration * 1_000L
                while (SystemClock.elapsedRealtime() < end) Thread.sleep(100)
                if (expectEofTerminal) {
                    assertTrue("Repeated EOF must become unavailable after exactly one recovery",
                        terminal.get() && !available.get() && availableCount.get() == 2)
                    event("eof-terminal-observed")
                } else if (observeOutage) {
                    assertTrue("Expected under-rate source to remain unavailable at observation end", !available.get())
                    event("outage-observed", "terminal=${terminal.get()} availableCount=${availableCount.get()}")
                } else {
                    assertTrue("TV input stopped reporting available for fixture $index", available.get())
                }
                event("capture-end")
                workerProbe?.finishWhilePlaying()
                catalogProbe?.finishWhilePlaying()
                guideProbe?.finishWhilePlaying()
                indexProbe?.finishWhilePlaying()
            }
        } finally {
            try {
                event("tvview-reset-start")
                scenario?.onActivity { if (viewInitialized) view.reset() }
                event("tvview-reset-return")
                if (releaseWaitMs > 0L) {
                    event("release-wait-start", "timeoutMs=$releaseWaitMs")
                    val releaseStatus = waitForPlaybackRelease(playbackProviderId, releaseWaitMs)
                    event("release-wait-finished", releaseStatus)
                }
                event("scenario-close-start")
                scenario?.close()
                event("scenario-close-return")
                if (verifyOwnership) {
                    val releaseDeadline = SystemClock.elapsedRealtime() + 10_000L
                    while (com.streamvault.domain.sync.AutomaticSyncActivity.isProviderPlaybackActive(playbackProviderId) &&
                        SystemClock.elapsedRealtime() < releaseDeadline) Thread.sleep(50)
                    assertTrue("Released TV input retained ownership",
                        !com.streamvault.domain.sync.AutomaticSyncActivity.isProviderPlaybackActive(playbackProviderId))
                    event("ownership-released")
                }
            } finally {
                try {
                    try {
                        finishOwnedProbes(workerProbe, guideProbe, catalogProbe, indexProbe)
                    } finally {
                        rows.forEach { app.contentResolver.delete(it, null, null) }
                        db.openHelper.writableDatabase.execSQL(
                            "DELETE FROM provider_configs WHERE provider_id = ?", arrayOf(providerId))
                        db.providerDao().delete(providerId)
                    }
                } finally {
                    try {
                        restoreFixturePreferences(preferences, providerId, previousAutomaticOverride,
                            preferenceStore, originalPreferences)
                        event("automatic-sync-restored")
                        app.filesDir.resolve("tif-$runId.json").writeText(events.toString(2))
                    } finally { catalogMutex.unlock() }
                }
            }
        }
    }
    private fun cycleTvViewSurface(
        scenario: ActivityScenario<MainActivity>,
        view: TvView,
        available: AtomicBoolean,
        terminal: AtomicBoolean,
        availableCount: AtomicInteger,
        providerId: Long,
        checkOwnership: Boolean,
        callbackDelayMs: Long,
        emit: (String, String) -> Unit
    ): Boolean {
        val previousAvailableCount = availableCount.get()
        if (callbackDelayMs > 0L) Thread.sleep(1_000L)
        scenario.onActivity {
            emit("surface-cycle-hide-start", "previousAvailableCount=$previousAvailableCount")
            view.visibility = View.INVISIBLE
            if (callbackDelayMs > 0L) {
                emit("surface-callback-delay-start", "delayMs=$callbackDelayMs")
                Thread.sleep(callbackDelayMs)
                emit("surface-callback-delay-end", "delayMs=$callbackDelayMs")
            }
            emit("surface-cycle-hidden", "")
        }
        Thread.sleep(1_500L)
        scenario.onActivity {
            view.visibility = View.VISIBLE
            emit("surface-cycle-shown", "")
        }
        // Readiness may stay READY across a surface-only cycle, so a new availability
        // callback is diagnostic evidence rather than a recovery requirement.
        Thread.sleep(2_000L)
        if (!available.get() && !terminal.get()) {
            val deadline = SystemClock.elapsedRealtime() + 15_000L
            while (!available.get() && !terminal.get() && SystemClock.elapsedRealtime() < deadline) {
                Thread.sleep(100L)
            }
        }
        val playbackOwned = com.streamvault.domain.sync.AutomaticSyncActivity
            .isProviderPlaybackActive(providerId)
        val freshAvailableCallback = availableCount.get() > previousAvailableCount
        val recovered = available.get() && !terminal.get() && (!checkOwnership || playbackOwned)
        emit(
            "surface-cycle-result",
            JSONObject()
                .put("previousAvailableCount", previousAvailableCount)
                .put("availableCount", availableCount.get())
                .put("freshAvailableCallback", freshAvailableCallback)
                .put("available", available.get())
                .put("terminal", terminal.get())
                .put("playbackOwned", playbackOwned)
                .put("ownershipRequired", checkOwnership)
                .put("recovered", recovered)
                .toString()
        )
        return recovered
    }

    private fun waitForPlaybackRelease(providerId: Long, timeoutMs: Long): String {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (com.streamvault.domain.sync.AutomaticSyncActivity.isProviderPlaybackActive(providerId) &&
            SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(50L)
        }
        val stillActive = com.streamvault.domain.sync.AutomaticSyncActivity
            .isProviderPlaybackActive(providerId)
        return JSONObject()
            .put("timeoutMs", timeoutMs)
            .put("playbackOwned", stillActive)
            .put("released", !stillActive)
            .toString()
    }

    private suspend fun finishOwnedProbes(
        worker: com.streamvault.app.ui.PlaybackWorkerAdmissionProbe?,
        guide: com.streamvault.app.ui.PlaybackInflightEpgProbe?,
        catalog: com.streamvault.app.ui.PlaybackInflightCatalogProbe?,
        index: com.streamvault.app.ui.PlaybackIndexWorkProbe?
    ) {
        try {
            worker?.finishAfterPlayback()
            guide?.finishAfterPlayback()
            catalog?.finishAfterPlayback()
            index?.finishAfterPlayback()
        } finally {
            try { index?.close() } finally { catalog?.close() }
        }
    }

    private suspend fun restoreFixturePreferences(
        preferences: com.streamvault.data.preferences.PreferencesRepository,
        providerId: Long,
        previousOverride: com.streamvault.domain.sync.AutomaticSyncSettings?,
        store: DataStore<Preferences>,
        original: Preferences
    ) {
        preferences.setAutomaticSyncSettings(providerId, previousOverride)
        store.edit { values ->
            fun <T> restore(key: Preferences.Key<T>) {
                val old = original[key]
                if (old == null) values.remove(key) else values[key] = old
            }
            restore(stringPreferencesKey("automatic_sync_status_$providerId"))
            restore(longPreferencesKey("catalog_sync_upgrade_started_$providerId"))
            restore(intPreferencesKey("catalog_sync_engine_$providerId"))
        }
    }

}
