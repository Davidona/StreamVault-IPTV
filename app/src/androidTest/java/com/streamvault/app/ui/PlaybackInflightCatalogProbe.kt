package com.streamvault.app.ui

import android.app.Application
import android.net.Uri
import android.os.Bundle
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.work.*
import com.streamvault.app.benchmark.XtreamSyncBenchmarkEntryPoint
import com.streamvault.data.sync.ProviderSyncWorker
import com.streamvault.domain.model.*
import com.streamvault.domain.sync.AutomaticSyncActivity
import com.streamvault.domain.sync.AutomaticSyncSettings
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.net.URL
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Held local response proves automatic work was admitted before viewing started. */
internal class PlaybackInflightCatalogProbe private constructor(
    private val app: Application, private val base: String, private val run: String,
    private val kind: String, private val emit: (JSONObject) -> Unit
) {
    private val graph = EntryPointAccessors.fromApplication(app, XtreamSyncBenchmarkEntryPoint::class.java)
    private val work = WorkManager.getInstance(app)
    private var id = 0L
    private var workId: UUID? = null
    private var ownedRequestId: UUID? = null
    @Suppress("UNCHECKED_CAST")
    private val store = graph.preferences().javaClass.getDeclaredMethod("getPreferencesDataStore")
        .apply { isAccessible = true }.invoke(graph.preferences()) as DataStore<Preferences>
    private lateinit var original: Preferences
    private var previousOverride: AutomaticSyncSettings? = null
    private var originSnapshot: Map<String, Any?> = emptyMap()
    private val originKeys get() = listOf("${kind}_$id", "${kind}_split_queue_$id", "${kind}_policy_$id")
    private var expectYield = false
    private var deferWhileBusy = true
    private var prestageTarget = 0
    private fun stagedRows(): Int = graph.database().openHelper.readableDatabase.query(
        "SELECT COUNT(*) FROM channel_import_stage WHERE provider_id = ?", arrayOf<Any>(id)
    ).use { it.moveToFirst(); it.getInt(0) }
    private suspend fun workflowPhase(): String {
        val dao = graph.database().providerWorkflowDao()
        val workflow = dao.getWorkflow(id) ?: return "MISSING"
        return dao.getPhases(id, workflow.generation).firstOrNull { it.phase.name == "PRIMARY_CATALOG" }?.state?.name.orEmpty()
    }
    private suspend fun control(path: String): String = withContext(Dispatchers.IO) {
        URL("$base/$path").openConnection().apply { connectTimeout = 5_000; readTimeout = 5_000 }
            .getInputStream().use { it.bufferedReader().readText() }
    }
    private suspend fun info() = withContext(Dispatchers.IO) {
        work.getWorkInfoById(checkNotNull(workId)).get(30, TimeUnit.SECONDS)!!
    }
    suspend fun playbackReady() {
        check(AutomaticSyncActivity.hasPlayback)
        if (!expectYield) check(info().state == WorkInfo.State.RUNNING)
        control("release")
        emit(JSONObject().put("event", "catalog-released").put("kind", kind).put("playbackOwned", true))
    }
    suspend fun finishWhilePlaying() {
        check(AutomaticSyncActivity.hasPlayback)
        val state = info()
        val count = graph.database().channelDao().getByProvider(id).first().size
        val retainedSeed = graph.database().channelDao().getByProvider(id).first().any { it.streamId == 999_999L }
        emit(JSONObject().put("event", "catalog-observed").put("kind", kind)
            .put("workState", state.state.name).put("rows", count)
            .put("retainedSeed", retainedSeed).put("fixtureRows", count - if (retainedSeed) 1 else 0)
            .put("outcome", graph.preferences().automaticSyncStatuses.first()[id]?.outcome?.name)
            .put("playbackOwned", true))
        if (expectYield) {
            val workflow = checkNotNull(graph.database().providerWorkflowDao().getWorkflow(id))
            val metadata = graph.syncMetadata().getMetadata(id)
            val committed = graph.database().channelDao().getByProvider(id).first()
            check(count == 1 && committed.single().streamId == 999_999L && stagedRows() == 0 &&
                workflow.leaseToken == null && workflow.leaseExpiresAt == null &&
                (metadata?.lastLiveSuccess ?: 0L) == 0L && (metadata?.liveCount ?: 0) == 0 &&
                graph.preferences().automaticSyncStatuses.first()[id]?.outcome?.name == "WAITING_PLAYBACK") {
                "Deferred catalog not safely retained: rows=$count staged=${stagedRows()} phase=${workflowPhase()}"
            }
            emit(JSONObject().put("event", "catalog-yield-verified").put("rows", count)
                .put("stagedRows", stagedRows()).put("phase", workflowPhase())
                .put("leaseReleased", true).put("liveFreshnessUnchanged", true))
            return
        }
        check(state.state == WorkInfo.State.SUCCEEDED && count - (if (retainedSeed) 1 else 0) == 20_000) {
            "Catalog fixture did not finish: state=${state.state} rows=$count"
        }
    }
    suspend fun finishAfterPlayback() {
        if (!expectYield) return
        withTimeout(15_000) { while (AutomaticSyncActivity.hasPlayback) delay(100) }
        work.cancelUniqueWork("provider-automatic-check-$id").result.get(30, TimeUnit.SECONDS)
        ProviderSyncWorker.enqueueAutomaticCheck(app, id,
            AutomaticSyncSettings(movies = false, series = false, epg = false))
        withTimeout(90_000) {
            while (graph.database().channelDao().getByProvider(id).first().size != 20_000 || workflowPhase() != "SUCCEEDED") delay(200)
        }
        check(stagedRows() == 0)
        emit(JSONObject().put("event", "catalog-idle-resumed").put("rows", 20_000).put("phase", workflowPhase()))
    }
    suspend fun close() {
        if (id == 0L) return
        // Cancel only this fixture's worker; await its completion before deleting its rows.
        ownedRequestId?.let { work.cancelWorkById(it).result.get(30, TimeUnit.SECONDS) }
        graph.sync().cancelStalkerIndexSync(id)
        check(graph.providers().deleteProvider(id) is Result.Success)
        graph.preferences().setAutomaticSyncSettings(id, previousOverride)
        store.edit { values ->
            fun <T> restore(key: Preferences.Key<T>) {
                val prior = original[key]
                if (prior == null) values.remove(key) else values[key] = prior
            }
            restore(stringPreferencesKey("automatic_sync_status_$id"))
            restore(longPreferencesKey("catalog_sync_upgrade_started_$id"))
            restore(intPreferencesKey("catalog_sync_engine_$id"))
        }
        app.getSharedPreferences("automatic_sync_work", android.content.Context.MODE_PRIVATE).edit().apply {
            originKeys.forEach { key -> when (val old = originSnapshot[key]) {
                is Boolean -> putBoolean(key, old)
                is String -> putString(key, old)
                else -> remove(key)
            } }
        }.commit().also { check(it) }
        check(graph.providers().getProvider(id) == null)
        emit(JSONObject().put("event", "catalog-cleanup").put("providerId", id))
    }
    companion object {
        suspend fun start(app: Application, args: Bundle, run: String,
                          emit: (JSONObject) -> Unit): PlaybackInflightCatalogProbe? {
            val base = args.getString("catalogFixtureBase") ?: return null
            val kind = requireNotNull(args.getString("catalogKind"))
            require(kind in setOf("xtream", "stalker") && app.packageName.endsWith(".debug"))
            require(Uri.parse(base).scheme == "http" && Uri.parse(base).host in setOf("127.0.0.1", "localhost"))
            require(listOf("inflightFixtureBase", "importFixtureBase", "workerFixtureBase").all { args.getString(it) == null })
            check(!AutomaticSyncActivity.hasPlayback)
            val probe = PlaybackInflightCatalogProbe(app, base, run, kind, emit)
            probe.expectYield = args.getString("catalogExpectYield") == "true"
            probe.deferWhileBusy = args.getString("catalogDefer") != "false"
            probe.prestageTarget = args.getString("catalogPrestageRows")?.toInt() ?: 0
            require(probe.prestageTarget in 0..20_000)
            probe.original = probe.store.data.first()
            val originalConfiguration = probe.graph.preferences().automaticSyncConfiguration.first()
            try {
                probe.control("reset")
                // Let the application-launch scan finish before publishing this fixture.
                // Otherwise it can claim our provider ahead of the explicitly tracked request.
                delay(2_000)
                withTimeout(30_000) {
                    while (probe.work.getWorkInfosByTag(ProviderSyncWorker::class.java.name)
                        .get(30, TimeUnit.SECONDS).any { it.state == WorkInfo.State.RUNNING }) delay(100)
                }
                val type = if (kind == "xtream") ProviderType.XTREAM_CODES else ProviderType.STALKER_PORTAL
                probe.id = probe.graph.database().providerDao().insert(
                    com.streamvault.data.local.entity.ProviderEntity(name = "In-flight catalog fixture $run",
                        type = type, isActive = false))
                val origins = app.getSharedPreferences("automatic_sync_work", android.content.Context.MODE_PRIVATE)
                probe.originSnapshot = probe.originKeys.associateWith { origins.all[it] }
                probe.previousOverride = originalConfiguration.providers[probe.id]
                probe.graph.preferences().setAutomaticSyncSettings(probe.id, AutomaticSyncSettings(enabled = false))
                val config: ProviderConfiguration = if (kind == "xtream")
                    XtreamConfig(base, "bench_$run", "fixture", epgSyncMode = ProviderEpgSyncMode.SKIP)
                else StalkerConfig(base, StalkerDeviceIdentity("02:00:00:00:00:77"),
                    transportGrant = StalkerTransportGrant(StalkerTransportMode.USER_ACCEPTED_HTTP,
                        StalkerTransportOrigin("http", "127.0.0.1", Uri.parse(base).port), consentedAt = System.currentTimeMillis()),
                    epgSyncMode = ProviderEpgSyncMode.SKIP)
                probe.graph.database().providerSnapshotDao().commitConfiguration(
                    com.streamvault.data.local.entity.ProviderConfigEntity(providerId = probe.id, type = type,
                        schemaVersion = 1, configurationGeneration = 1, identityKey = "catalog-fixture-$run",
                        encryptedConfigJson = com.google.gson.Gson().toJson(config), updatedAt = System.currentTimeMillis()))
                if (probe.expectYield || probe.prestageTarget > 0) probe.graph.database().channelDao().insert(
                    com.streamvault.data.local.entity.ChannelEntity(name = "Catalog retained fixture seed",
                        providerId = probe.id, streamId = 999_999, streamUrl = "$base/retained.ts"))
                app.filesDir.resolve("catalog-$run-owned.json").writeText(JSONObject().put("providerId", probe.id).toString())
                probe.graph.preferences().setAutomaticSyncSettings(probe.id,
                    AutomaticSyncSettings(movies = false, series = false, epg = false,
                        deferWhileBusy = probe.deferWhileBusy))
                // Use the production unique name/KEEP policy so the automatic observer and
                // this probe converge on one request instead of competing for the lease.
                ProviderSyncWorker.enqueueAutomaticCheck(app, probe.id,
                    AutomaticSyncSettings(movies = false, series = false, epg = false,
                        deferWhileBusy = probe.deferWhileBusy))
                withTimeout(15_000) {
                    while (probe.workId == null) {
                        probe.workId = probe.work.getWorkInfosForUniqueWork("provider-automatic-check-${probe.id}")
                            .get(30, TimeUnit.SECONDS).firstOrNull { !it.state.isFinished }?.id
                        if (probe.workId == null) delay(100)
                    }
                }
                withTimeout(30_000) {
                    while (!JSONObject(probe.control("gate")).getBoolean("held")) {
                        check(!probe.info().state.isFinished) { "Catalog worker finished before reaching gate" }
                        delay(100)
                    }
                }
                probe.ownedRequestId = probe.workId
                if (probe.info().state != WorkInfo.State.RUNNING) {
                    // A concurrent global automatic scan may own the workflow lease. Track
                    // that real running worker, but never cancel a request we did not create.
                    val running = probe.work.getWorkInfosByTag(ProviderSyncWorker::class.java.name)
                        .get(30, TimeUnit.SECONDS).filter { it.state == WorkInfo.State.RUNNING }
                    check(running.size == 1) { "Ambiguous catalog worker ownership: ${running.size}" }
                    probe.workId = running.single().id
                }
                emit(JSONObject().put("event", "catalog-admitted-idle").put("kind", kind)
                    .put("providerId", probe.id).put("workId", probe.workId.toString())
                    .put("trackedGlobalScan", probe.workId != probe.ownedRequestId)
                    .put("workState", "RUNNING").put("playbackOwned", false))
                if (probe.expectYield || probe.prestageTarget > 0) {
                    val target = if (probe.expectYield) 1000 else probe.prestageTarget
                    withTimeout(20_000) { while (probe.stagedRows() < target) delay(100) }
                    emit(JSONObject().put("event", "catalog-partially-staged").put("stagedRows", probe.stagedRows())
                        .put("committedRows", probe.graph.database().channelDao().getByProvider(probe.id).first().size))
                }
                return probe
            } catch (error: Throwable) { probe.close(); throw error }
        }
    }
}
