package com.streamvault.app.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.work.WorkManager
import androidx.work.WorkInfo
import com.streamvault.app.benchmark.XtreamSyncBenchmarkEntryPoint
import com.streamvault.data.local.entity.*
import com.streamvault.data.sync.XtreamIndexWorker
import com.streamvault.data.sync.StalkerIndexWorker
import com.streamvault.domain.model.*
import com.streamvault.domain.sync.AutomaticSyncActivity
import com.streamvault.domain.sync.AutomaticSyncSettings
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.net.URL
import java.util.concurrent.TimeUnit

/** Opt-in real automatic movie/series index work; only a newly allocated fixture provider. */
internal class PlaybackIndexWorkProbe private constructor(
    private val app: Application, private val base: String, private val run: String,
    private val section: ContentType, private val emit: (JSONObject) -> Unit,
    private val providerKind: String = "xtream"
) {
    private val graph = EntryPointAccessors.fromApplication(app, XtreamSyncBenchmarkEntryPoint::class.java)
    private val work = WorkManager.getInstance(app)
    private var id = 0L
    private var sameProvider = false
    private var deferWhileBusy = true
    private var expectDeferred = false
    private var injectSignal = false
    private var signalInjected = false
    private fun setTestSignal(active: Boolean) {
        val type=Class.forName("com.streamvault.data.remote.stalker.StalkerTrafficCoordinator")
        type.getDeclaredMethod(if(active) "notePlaybackStarted" else "notePlaybackStopped",java.lang.Long.TYPE)
            .invoke(type.getField("INSTANCE").get(null),id)
        signalInjected=active
        emit(JSONObject().put("event","index-test-signal").put("active",active).put("trafficPlaybackActive",trafficActive()))
    }
    suspend fun navigation(url: String): com.streamvault.core.navigation.PlayerNavigationRequest? {
        if (!sameProvider) return null
        val streamId = Uri.parse(url).lastPathSegment!!.substringBefore('.').toLong() - 1000L
        val row = graph.database().channelDao().getByProviderSync(id).single { it.streamId == streamId }
        val extension = Uri.parse(url).lastPathSegment!!.substringAfterLast('.').lowercase()
        require(extension in setOf("ts", "m3u8"))
        val logical = com.streamvault.data.remote.stalker.StalkerUrlFactory.buildInternalStreamUrl(id,
            com.streamvault.data.remote.stalker.StalkerStreamKind.LIVE,streamId,"ffmpeg $url",extension)
        graph.database().channelDao().updateAll(listOf(row.copy(streamUrl = logical)))
        return com.streamvault.core.navigation.PlayerNavigationRequest(streamUrl=logical,title="Local index provider fixture",
            channelId=streamId.toString(),internalId=row.id,providerId=id,contentType="LIVE")
    }
    private fun trafficActive(): Boolean {
        val type=Class.forName("com.streamvault.data.remote.stalker.StalkerTrafficCoordinator")
        return type.getDeclaredMethod("isPlaybackActive",java.lang.Long.TYPE).invoke(type.getField("INSTANCE").get(null),id) as Boolean
    }
    fun verifyPlaybackContext(providerId: Long, playbackPath: String = "MAIN") {
        if (!sameProvider) return
        check(providerId==id && AutomaticSyncActivity.hasPlayback)
        emit(JSONObject().put("event","index-same-provider-context").put("providerId",id)
            .put(if (playbackPath == "TV_INPUT") "frameworkProviderId" else "viewModelProviderId",providerId)
            .put("playbackPath",playbackPath).put("globalPlaybackOwned",true).put("trafficPlaybackActive",trafficActive()))
        if (injectSignal) setTestSignal(true)
        if (expectDeferred) check(trafficActive())
    }
    suspend fun finishAfterPlayback() {
        if (!expectDeferred) return
        withTimeout(15_000) { while (AutomaticSyncActivity.hasPlayback) delay(100) }
        if (signalInjected) setTestSignal(false)
        check(!trafficActive())
        withTimeout(90_000) { while (count()!=20_000 || graph.database().stalkerIndexJobDao().get(id,section.name)?.state!=StalkerIndexState.COMPLETE) delay(200) }
        emit(JSONObject().put("event","index-idle-resumed").put("rows",count()).put("trafficPlaybackActive",trafficActive()))
    }
    @Suppress("UNCHECKED_CAST")
    private val store = graph.preferences().javaClass.getDeclaredMethod("getPreferencesDataStore")
        .apply { isAccessible = true }.invoke(graph.preferences()) as DataStore<Preferences>
    private lateinit var original: Preferences
    private var oldOverride: AutomaticSyncSettings? = null
    private var originSnapshot: Map<String, Any?> = emptyMap()
    private val originKeys get() = listOf("stalker_$id", "stalker_split_queue_$id", "stalker_policy_$id")
    private val policy get() = AutomaticSyncSettings(live = false, movies = section == ContentType.MOVIE,
        series = section == ContentType.SERIES, epg = false, deferWhileBusy = deferWhileBusy)
    private suspend fun control(path: String) = withContext(Dispatchers.IO) {
        URL("$base/$path").openConnection().apply { connectTimeout = 5000; readTimeout = 5000 }
            .getInputStream().use { it.bufferedReader().readText() }
    }
    private suspend fun count(): Int {
        if (providerKind != "stalker") return graph.database().xtreamContentIndexDao().countActiveRows(id, section.name)
        val table = if (section == ContentType.MOVIE) "movies" else "series"
        return withContext(Dispatchers.IO) {
            graph.database().openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table WHERE provider_id = ?", arrayOf(id)).use {
                check(it.moveToFirst()); it.getInt(0)
            }
        }
    }
    private suspend fun info() = withContext(Dispatchers.IO) {
        work.getWorkInfosForUniqueWork("provider-automatic-index-$id").get(30, TimeUnit.SECONDS)
    }
    suspend fun playbackReady() {
        check(AutomaticSyncActivity.hasPlayback)
        control("release")
        emit(JSONObject().put("event", "index-released").put("section", section.name).put("playbackOwned", true))
    }
    suspend fun finishWhilePlaying() {
        check(AutomaticSyncActivity.hasPlayback)
        val job = graph.database().xtreamIndexJobDao().get(id, section.name)
        val stalkerJob = if (providerKind == "stalker") graph.database().stalkerIndexJobDao().get(id, section.name) else null
        val jobState = stalkerJob?.state?.name ?: job?.state
        val rows = count()
        emit(JSONObject().put("event", "index-observed").put("providerId", id).put("section", section.name)
            .put("rows", rows).put("summaryRows", graph.database().xtreamContentIndexDao().countActiveRows(id, section.name)).put("jobState", jobState).put("cursor", stalkerJob?.nextCategoryIndex ?: job?.nextCategoryIndex)
            .put("indexedRows", stalkerJob?.indexedRows ?: job?.indexedRows).put("workStates", info().joinToString { it.state.name })
            .put("playbackOwned", true))
        if (expectDeferred) {
            check(rows in 1 until 20_000 && trafficActive()) { "Index did not defer: rows=$rows state=$jobState" }
            emit(JSONObject().put("event","index-deferred-verified").put("rows",rows).put("jobState",jobState))
            return
        }
        check(rows == 20_000 && jobState == if (providerKind == "stalker") "COMPLETE" else "SUCCESS") {
            "Index fixture incomplete: rows=$rows state=$jobState"
        }
    }
    suspend fun verifyPauseResume(engine: com.streamvault.player.PlayerEngine) {
        check(expectDeferred && !injectSignal)
        withContext(Dispatchers.Main) { engine.pause() }
        withTimeout(5_000) { while (engine.isPlaying.value) delay(50) }
        check(AutomaticSyncActivity.hasPlayback && trafficActive())
        emit(JSONObject().put("event","index-owner-paused").put("trafficPlaybackActive",true).put("globalPlaybackOwned",true))
        withContext(Dispatchers.Main) { engine.play() }
        withTimeout(5_000) { while (!trafficActive() || !engine.isPlaying.value) delay(50) }
        emit(JSONObject().put("event","index-owner-resumed").put("trafficPlaybackActive",true))
    }
    suspend fun close() {
        if (id == 0L) return
        if (signalInjected) setTestSignal(false)
        work.cancelUniqueWork("provider-automatic-index-$id").result.get(30, TimeUnit.SECONDS)
        if (providerKind == "stalker") work.cancelUniqueWork("provider-stalker-index-$id").result.get(30, TimeUnit.SECONDS)
        check(graph.providers().deleteProvider(id) is Result.Success)
        graph.preferences().setAutomaticSyncSettings(id, oldOverride)
        store.edit { values ->
            fun <T> restore(key: Preferences.Key<T>) {
                val prior = original[key]
                if (prior == null) values.remove(key) else values[key] = prior
            }
            restore(stringPreferencesKey("automatic_sync_status_$id"))
            restore(longPreferencesKey("catalog_sync_upgrade_started_$id"))
            restore(intPreferencesKey("catalog_sync_engine_$id"))
        }
        if (providerKind == "stalker") {
            val origins = app.getSharedPreferences("automatic_sync_work", Context.MODE_PRIVATE)
            origins.edit().apply { originKeys.forEach { key -> when (val old = originSnapshot[key]) {
                is Boolean -> putBoolean(key, old)
                is String -> putString(key, old)
                else -> remove(key)
            } } }.commit()
        }
        check(graph.providers().getProvider(id) == null)
        emit(JSONObject().put("event", "index-cleanup").put("providerId", id))
    }
    companion object {
        suspend fun start(app: Application, args: Bundle, run: String, emit: (JSONObject) -> Unit): PlaybackIndexWorkProbe? {
            val base = args.getString("indexFixtureBase") ?: return null
            require(app.packageName.endsWith(".debug") && Uri.parse(base).scheme == "http" &&
                Uri.parse(base).host in setOf("127.0.0.1", "localhost"))
            require(listOf("catalogFixtureBase", "inflightFixtureBase", "workerFixtureBase", "importFixtureBase").all { args.getString(it) == null })
            val section = ContentType.valueOf(requireNotNull(args.getString("indexSection")))
            require(section in setOf(ContentType.MOVIE, ContentType.SERIES))
            val kind = args.getString("indexProviderKind") ?: "xtream"
            require(kind in setOf("xtream", "stalker"))
            check(!AutomaticSyncActivity.hasPlayback)
            val probe = PlaybackIndexWorkProbe(app, base, run, section, emit, kind)
            probe.sameProvider=args.getString("indexSameProvider")=="true"
            probe.deferWhileBusy=args.getString("indexDeferWhileBusy")!="false"
            probe.expectDeferred=args.getString("indexExpectDeferred")=="true"
            probe.injectSignal=args.getString("indexInjectSignal")=="true"
            require(!probe.injectSignal || probe.expectDeferred)
            require(!probe.sameProvider || kind=="stalker")
            require(!probe.expectDeferred || probe.sameProvider)
            probe.original = probe.store.data.first()
            try {
                probe.control("reset")
                val type = if (kind == "stalker") ProviderType.STALKER_PORTAL else ProviderType.XTREAM_CODES
                probe.id = probe.graph.database().providerDao().insert(ProviderEntity(
                    name = "In-flight catalog fixture index $run", type = type, isActive = false))
                if (kind == "stalker") {
                    // Match production scheduler origin metadata before startup continuation observes jobs.
                    val origins = app.getSharedPreferences("automatic_sync_work", Context.MODE_PRIVATE)
                    probe.originSnapshot = probe.originKeys.associateWith { origins.all[it] }
                    check(origins.edit().putBoolean("stalker_${probe.id}", true).commit())
                }
                probe.oldOverride = probe.graph.preferences().automaticSyncConfiguration.first().providers[probe.id]
                probe.graph.preferences().setAutomaticSyncSettings(probe.id, probe.policy.copy(enabled = false))
                val config: ProviderConfiguration = if (kind == "xtream") XtreamConfig(base, "bench_$run", "fixture",
                    epgSyncMode = ProviderEpgSyncMode.SKIP) else StalkerConfig(base,
                    StalkerDeviceIdentity("02:00:00:00:00:78"), epgSyncMode = ProviderEpgSyncMode.SKIP,
                    catalogMode = StalkerCatalogMode.BACKGROUND_INDEX,
                    transportGrant = StalkerTransportGrant(StalkerTransportMode.USER_ACCEPTED_HTTP,
                        StalkerTransportOrigin("http", "127.0.0.1", Uri.parse(base).port), consentedAt = System.currentTimeMillis()))
                probe.graph.database().providerSnapshotDao().commitConfiguration(ProviderConfigEntity(
                    providerId = probe.id, type = type, schemaVersion = 1,
                    configurationGeneration = 1, identityKey = "catalog-fixture-index-$run",
                    encryptedConfigJson = com.google.gson.Gson().toJson(config), updatedAt = System.currentTimeMillis()))
                probe.graph.database().categoryDao().insertAll((1L..4L).map {
                    CategoryEntity(categoryId = it, name = "Index fixture $it", type = section, providerId = probe.id)
                })
                if (probe.sameProvider) probe.graph.database().channelDao().insertAll((1L..2L).map {
                    ChannelEntity(name="Index live fixture $it",streamId=it,providerId=probe.id,streamUrl="$base/unplayed/$it.ts")
                })
                val now = System.currentTimeMillis()
                (probe.graph.preferences() as com.streamvault.data.preferences.PreferencesRepository)
                    .completeCatalogSyncUpgrade(probe.id)
                probe.graph.syncMetadata().updateMetadata(SyncMetadata(probe.id,
                    lastMovieSync = now, lastMovieSuccess = now, lastSeriesSync = now, lastSeriesSuccess = now))
                if (kind == "xtream") probe.graph.database().xtreamIndexJobDao().upsert(XtreamIndexJobEntity(
                    providerId = probe.id, section = section.name, state = "QUEUED", totalCategories = 4, updatedAt = now))
                else probe.graph.database().stalkerIndexJobDao().upsert(StalkerIndexJobEntity(
                    providerId = probe.id, section = section, state = StalkerIndexState.QUEUED, totalCategories = 4, updatedAt = now))
                app.filesDir.resolve("index-$run-owned.json").writeText(JSONObject().put("providerId", probe.id).toString())
                probe.graph.preferences().setAutomaticSyncSettings(probe.id, probe.policy)
                if (kind == "xtream") XtreamIndexWorker.enqueue(app, probe.id, section.name, force = false, automatic = true)
                else StalkerIndexWorker.enqueue(app, probe.id, section.name, force = false, automatic = true)
                withTimeout(40_000) {
                    while (!JSONObject(probe.control("gate")).getBoolean("held") || probe.count() < 500) delay(100)
                }
                check(probe.info().any { it.state == WorkInfo.State.RUNNING }) { "Automatic index not running: ${probe.info().joinToString { it.state.name }}" }
                emit(JSONObject().put("event", "index-admitted-idle").put("providerId", probe.id)
                    .put("section", section.name).put("rows", probe.count()).put("playbackOwned", false))
                return probe
            } catch (error: Throwable) { probe.close(); throw error }
        }
    }
}
