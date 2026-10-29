package com.streamvault.app.ui

import android.app.Application
import android.net.Uri
import android.os.Bundle
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.streamvault.app.benchmark.XtreamSyncBenchmarkEntryPoint
import com.streamvault.data.sync.BackgroundEpgSyncWorker
import com.streamvault.data.sync.ProviderSyncWorker
import com.streamvault.domain.model.LegacyProvider
import com.streamvault.domain.model.ProviderEpgSyncMode
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.Result
import com.streamvault.domain.sync.AutomaticSyncActivity
import com.streamvault.domain.sync.AutomaticSyncSettings
import dagger.hilt.android.EntryPointAccessors
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

/** Actual WorkManager admission, with small local fixtures and only disposable provider IDs. */
internal class PlaybackWorkerAdmissionProbe private constructor(
    private val app: Application, private val base: String, private val run: String,
    private val emit: (JSONObject) -> Unit,
    private val observeUnownedTvInput: Boolean = false
) {
    private val graph = EntryPointAccessors.fromApplication(app, XtreamSyncBenchmarkEntryPoint::class.java)
    private val work = WorkManager.getInstance(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ids = mutableListOf<Long>()
    private val catalogWorkIds = mutableListOf<UUID>()
    private lateinit var observation: Deferred<Unit>
    private var epgId = 0L
    private var catalogId = 0L
    private var normalRows = -1
    private var normalAttempts = -1
    private var repairRows = -1
    private var repairAttempts = -1
    private var automaticRows = -1
    private var automaticOutcome = ""
    private var epgWorkId: UUID? = null
    private val epgPolicy = AutomaticSyncSettings(live = false, movies = false, series = false, epg = true)
    @Suppress("UNCHECKED_CAST")
    private val store = graph.preferences().javaClass.getDeclaredMethod("getPreferencesDataStore")
        .apply { isAccessible = true }.invoke(graph.preferences()) as DataStore<Preferences>
    private lateinit var originalPreferences: Preferences

    private fun verifyOwnership() {
        check(AutomaticSyncActivity.hasPlayback == !observeUnownedTvInput)
    }

    private suspend fun add(kind: String): Long {
        val url = "$base/$kind.m3u?run=$run"
        val result = graph.providers().addProvider(LegacyProvider(
            name = "Worker admission fixture $run $kind", type = ProviderType.M3U,
            serverUrl = url, m3uUrl = url, epgUrl = "$base/guide.xml?run=$run",
            isActive = false, epgSyncMode = ProviderEpgSyncMode.BACKGROUND))
        check(result is Result.Success) { "Disposable worker provider setup failed: $result" }
        ids.add(result.data)
        graph.preferences().setAutomaticSyncSettings(result.data, if (kind == "epg") epgPolicy
            else AutomaticSyncSettings(movies = false, series = false, epg = false))
        app.filesDir.resolve("worker-probe-$run.json").writeText(
            JSONObject().put("providerIds", JSONArray(ids)).toString())
        return result.data
    }

    private suspend fun enqueueCatalog() {
        val request = OneTimeWorkRequestBuilder<ProviderSyncWorker>()
            .setInputData(workDataOf("automatic_provider_id" to catalogId)).build()
        catalogWorkIds.add(request.id)
        withContext(Dispatchers.IO) { work.enqueue(request).result.get(30, TimeUnit.SECONDS) }
    }

    private suspend fun epgIds(): Set<UUID> = withContext(Dispatchers.IO) {
        work.getWorkInfosForUniqueWork("provider-epg-$epgId").get(30, TimeUnit.SECONDS).map { it.id }.toSet()
    }

    private suspend fun awaitNewEpg(previous: Set<UUID>) = withTimeout(15_000L) {
        while (true) {
            val added = epgIds() - previous
            if (added.isNotEmpty()) { epgWorkId = added.single(); break }
            delay(50L)
        }
    }

    private suspend fun epgInfo(): WorkInfo = withContext(Dispatchers.IO) {
        requireNotNull(work.getWorkInfoById(requireNotNull(epgWorkId)).get(30, TimeUnit.SECONDS))
    }

    private suspend fun enqueueRepair() {
        val previous = epgIds()
        enqueueBackground(force = true, delaySeconds = 0)
        awaitNewEpg(previous)
    }

    private fun enqueueBackground(force: Boolean, delaySeconds: Long) {
        // One test APK can probe both the released signature and the added intent flag.
        val companion = BackgroundEpgSyncWorker.Companion
        val method = companion.javaClass.methods.single { it.name == "enqueue" }
        val parameters = if (method.parameterCount == 5) arrayOf(app, epgId, force, delaySeconds, false)
            else arrayOf(app, epgId, force, delaySeconds)
        method.invoke(companion, *parameters)
    }

    private suspend fun checkpoint(phase: String): Pair<Int, Int> {
        val info = epgInfo()
        val rows = graph.database().programDao().countByProvider(epgId)
        val outcome = graph.preferences().automaticSyncStatuses.first()[catalogId]?.outcome?.name.orEmpty()
        val channels = graph.database().channelDao().getByProvider(catalogId).first().size
        emit(JSONObject().put("event", "worker-checkpoint").put("phase", phase)
            .put("playbackOwned", AutomaticSyncActivity.hasPlayback).put("epgRows", rows)
            .put("epgWorkState", info.state.name).put("epgAttempts", info.runAttemptCount)
            .put("automaticCatalogRows", channels).put("automaticOutcome", outcome))
        if (phase == "repair-during-playback") { automaticRows = channels; automaticOutcome = outcome }
        return rows to info.runAttemptCount
    }

    private suspend fun awaitEpgSuccess() = withTimeout(60_000L) {
        while (epgInfo().state != WorkInfo.State.SUCCEEDED) delay(200L)
        check(graph.database().programDao().countByProvider(epgId) == 1000)
    }

    suspend fun finishWhilePlaying() {
        observation.await()
        verifyOwnership()
        if (observeUnownedTvInput) return
        // Exercise the real settings action, not a direct refresh/import API.
        val previous = epgIds()
        graph.sync().scheduleManualEpgSync(epgId)
        awaitNewEpg(previous)
        awaitEpgSuccess()
        emit(JSONObject().put("event", "worker-manual-success").put("playbackOwned", AutomaticSyncActivity.hasPlayback))
        // A user who explicitly permits overlap retains that choice.
        graph.preferences().setAutomaticSyncSettings(epgId, epgPolicy.copy(deferWhileBusy = false))
        enqueueRepair()
        awaitEpgSuccess()
        emit(JSONObject().put("event", "worker-optout-success").put("playbackOwned", AutomaticSyncActivity.hasPlayback))
        graph.preferences().setAutomaticSyncSettings(epgId, epgPolicy)
    }

    suspend fun finishAfterPlayback() {
        try {
            withTimeout(15_000L) { while (AutomaticSyncActivity.hasPlayback) delay(100L) }
            // Resubmit only this probe's jobs to test eligibility without waiting out backoff.
            catalogWorkIds.forEach { work.cancelWorkById(it).result.get(30, TimeUnit.SECONDS) }
            enqueueCatalog()
            withTimeout(60_000L) {
                while (graph.database().channelDao().getByProvider(catalogId).first().isEmpty()) delay(200L)
            }
            enqueueRepair()
            awaitEpgSuccess()
            emit(JSONObject().put("event", "worker-idle-success").put("playbackOwned", AutomaticSyncActivity.hasPlayback))
        } finally {
            scope.cancel()
            if (::observation.isInitialized) observation.cancelAndJoin()
            catalogWorkIds.forEach { work.cancelWorkById(it).result.get(30, TimeUnit.SECONDS) }
            for (id in ids) {
                BackgroundEpgSyncWorker.cancel(app, id)
                graph.database().programDao().deleteByProvider(-id)
                check(graph.providers().deleteProvider(id) is Result.Success)
                graph.preferences().setAutomaticSyncSettings(id, null)
                check(graph.providers().getProvider(id) == null)
            }
            store.edit { values ->
                val owned = ids.flatMap { id -> listOf("automatic_sync_status_$id",
                    "catalog_sync_upgrade_started_$id", "catalog_sync_engine_$id") }.toSet()
                for (key in values.asMap().keys.filter { it.name in owned }) {
                    @Suppress("UNCHECKED_CAST")
                    val typed = key as Preferences.Key<Any>
                    val old = originalPreferences[typed]
                    if (old == null) values.remove(typed) else values[typed] = old
                }
            }
            emit(JSONObject().put("event", "worker-cleanup").put("providersRemoved", ids.size))
        }
        if (observeUnownedTvInput) {
            check(normalRows == 1000 && repairRows == 1000 && automaticRows == 20) {
                "Unowned TV-input observation mismatch: normal=$normalRows repair=$repairRows catalog=$automaticRows"
            }
            return
        }
        check(normalRows == 0 && repairRows == 0 && normalAttempts > 0 && repairAttempts > 0) {
            "Background EPG overlapped playback: normalRows=$normalRows repairRows=$repairRows"
        }
        check(automaticRows == 0 && automaticOutcome == "WAITING_PLAYBACK") {
            "Automatic catalog did not defer: rows=$automaticRows outcome=$automaticOutcome"
        }
    }

    companion object {
        suspend fun start(app: Application, args: Bundle, run: String,
                          emit: (JSONObject) -> Unit): PlaybackWorkerAdmissionProbe? {
            val base = args.getString("workerFixtureBase") ?: return null
            val uri = Uri.parse(base)
            require(app.packageName.endsWith(".debug") && uri.scheme == "http" &&
                uri.host in setOf("127.0.0.1", "localhost"))
            require(args.getString("fixtureUrls")!!.split(',').size == 1)
            val probe = PlaybackWorkerAdmissionProbe(app, base.trimEnd('/'), run, emit,
                args.getString("workerObserveUnownedTvInput") == "true")
            probe.originalPreferences = probe.store.data.first()
            probe.epgId = probe.add("epg")
            probe.catalogId = probe.add("catalog")
            probe.observation = probe.scope.async {
                delay(10_000L)
                probe.verifyOwnership()
                val previous = probe.epgIds()
                probe.enqueueBackground(force = false, delaySeconds = 30) // Normal scheduling delay.
                probe.awaitNewEpg(previous)
                probe.enqueueCatalog()
                emit(JSONObject().put("event", "worker-enqueued").put("playbackOwned", AutomaticSyncActivity.hasPlayback))
                delay(55_000L)
                val normal = probe.checkpoint("normal-during-playback")
                probe.normalRows = normal.first; probe.normalAttempts = normal.second
                probe.enqueueRepair()
                delay(55_000L)
                val repair = probe.checkpoint("repair-during-playback")
                probe.repairRows = repair.first; probe.repairAttempts = repair.second
            }
            return probe
        }
    }
}
