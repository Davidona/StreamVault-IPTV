package com.streamvault.app.ui

import android.app.Application
import android.net.Uri
import android.os.Bundle
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.streamvault.app.benchmark.XtreamSyncBenchmarkEntryPoint
import com.streamvault.data.sync.BackgroundEpgSyncWorker
import com.streamvault.domain.model.*
import com.streamvault.data.local.entity.ChannelEntity
import com.streamvault.domain.model.LegacyProvider
import com.streamvault.domain.model.ProviderEpgSyncMode
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.Result
import com.streamvault.domain.repository.EpgRepository
import com.streamvault.domain.sync.AutomaticSyncActivity
import com.streamvault.domain.sync.AutomaticSyncSettings
import dagger.hilt.android.EntryPointAccessors
import java.net.URL
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Provider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

/** Admit a real guide worker while idle, hold its response after one staged batch, then view. */
internal class PlaybackInflightEpgProbe private constructor(
    private val app: Application, private val component: Any, private val base: String,
    private val run: String, private val emit: (JSONObject) -> Unit,
    private val providerKind: String = "m3u"
) {
    private val graph = EntryPointAccessors.fromApplication(app, XtreamSyncBenchmarkEntryPoint::class.java)
    private val work = WorkManager.getInstance(app)
    private val ids = mutableListOf<Long>()
    private var guideId = 0L
    private var manualId = 0L
    private var guideRequestId: UUID? = null
    private var sameProvider = false
    private var interruptGuide = false
    private var networkFault = false
    private var httpError = false
    private var permanentHttpError = false
    private var networkObservation: Deferred<Unit>? = null
    private var networkFirstAttempt = -1
    private var perChannel = false
    private var holdDuringCancel = false
    private val expectedPrograms get() = if (perChannel) 6001 else 100_000
    private val expectedSeedRows get() = if (perChannel) 1 else 0
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var laneProbe: Deferred<Long>? = null
    private var prefixMetadata: SyncMetadata? = null

    suspend fun navigation(url: String): com.streamvault.core.navigation.PlayerNavigationRequest? {
        if (!sameProvider) return null
        val streamId = Uri.parse(url).lastPathSegment!!.substringBefore('.').toLong() - 1000L
        val channel = graph.database().channelDao().getByProviderSync(guideId).single { it.streamId == streamId }
        val logical = com.streamvault.data.remote.stalker.StalkerUrlFactory.buildInternalStreamUrl(
            guideId, com.streamvault.data.remote.stalker.StalkerStreamKind.LIVE, streamId, "ffmpeg $url", "m3u8")
        graph.database().channelDao().updateAll(listOf(channel.copy(streamUrl = logical)))
        return com.streamvault.core.navigation.PlayerNavigationRequest(streamUrl = logical, title = "Local provider fixture",
            channelId = streamId.toString(), internalId = channel.id, providerId = guideId, contentType = "LIVE")
    }

    private fun trafficPlaybackActive(): Boolean {
        val type = Class.forName("com.streamvault.data.remote.stalker.StalkerTrafficCoordinator")
        return type.getDeclaredMethod("isPlaybackActive", java.lang.Long.TYPE)
            .invoke(type.getField("INSTANCE").get(null), guideId) as Boolean
    }

    fun verifyPlaybackContext(providerId: Long) {
        if (!sameProvider) return
        check(providerId == guideId)
        emit(JSONObject().put("event", "stalker-same-provider-context").put("providerId", guideId)
            .put("viewModelProviderId", providerId).put("globalPlaybackOwned", AutomaticSyncActivity.hasPlayback)
            .put("trafficPlaybackActive", trafficPlaybackActive()))
    }

    private suspend fun retainedSeedRows() = graph.database().openHelper.readableDatabase.query(
        "SELECT COUNT(*) FROM programs WHERE provider_id = ? AND title = 'Portal retained seed'", arrayOf<Any>(guideId)
    ).use { it.moveToFirst(); it.getInt(0) }

    private suspend fun verifyInterrupted() {
        val state = withContext(Dispatchers.IO) { work.getWorkInfoById(checkNotNull(guideRequestId)).get(30, TimeUnit.SECONDS)!!.state }
        val metadata = graph.syncMetadata().getMetadata(guideId)
        val rows = graph.database().programDao().countByProvider(guideId)
        val stage = graph.database().programDao().countByProvider(-guideId)
        val seed = retainedSeedRows()
        val unchanged = metadata?.lastEpgSuccess == prefixMetadata?.lastEpgSuccess && metadata?.epgCount == prefixMetadata?.epgCount
        emit(JSONObject().put("event", "stalker-guide-interrupted-verified").put("programs", rows)
            .put("stagedPrograms", stage).put("oldUncoveredSeedRows", seed).put("workState", state.name)
            .put("freshnessUnchanged", unchanged))
        check(state == WorkInfo.State.CANCELLED && rows == 5001 && stage == 0 && seed == 1 && unchanged)
    }
    private var visibleDuringPlayback = -1
    private var phaseDuringPlayback = ""
    private val policy = AutomaticSyncSettings(enabled = false, live = false, movies = false, series = false, epg = true)
    @Suppress("UNCHECKED_CAST")
    private val store = graph.preferences().javaClass.getDeclaredMethod("getPreferencesDataStore")
        .apply { isAccessible = true }.invoke(graph.preferences()) as DataStore<Preferences>
    private lateinit var originalPreferences: Preferences
    private val previousOverrides = mutableMapOf<Long, AutomaticSyncSettings?>()

    private suspend fun add(kind: String): Long {
        if (providerKind in setOf("xtream", "stalker")) {
            val type = if (providerKind == "stalker") ProviderType.STALKER_PORTAL else ProviderType.XTREAM_CODES
            val id = graph.database().providerDao().insert(com.streamvault.data.local.entity.ProviderEntity(
                name = "In-flight guide fixture $run $kind", type = type, isActive = false))
            ids.add(id)
            previousOverrides[id] = graph.preferences().automaticSyncConfiguration.first().providers[id]
            graph.preferences().setAutomaticSyncSettings(id, policy)
            graph.database().providerSnapshotDao().commitConfiguration(com.streamvault.data.local.entity.ProviderConfigEntity(
                providerId = id, type = type, schemaVersion = 1,
                configurationGeneration = 1, identityKey = "epg-fixture-$run-$kind",
                encryptedConfigJson = com.google.gson.Gson().toJson(if (providerKind == "xtream") XtreamConfig(
                    base, "${run}_$kind", "fixture", epgSyncMode = ProviderEpgSyncMode.BACKGROUND) else StalkerConfig(
                    base, StalkerDeviceIdentity("02:00:00:00:00:79"), epgSyncMode = ProviderEpgSyncMode.BACKGROUND,
                    guideSourcePolicy = GuideSourcePolicy.PROVIDER_ONLY,
                    transportGrant = StalkerTransportGrant(StalkerTransportMode.USER_ACCEPTED_HTTP,
                        StalkerTransportOrigin("http", "127.0.0.1", Uri.parse(base).port), consentedAt = System.currentTimeMillis()))),
                updatedAt = System.currentTimeMillis()))
            app.filesDir.resolve("inflight-$run.json").writeText(JSONObject().put("providerIds", JSONArray(ids)).toString())
            return id
        }
        val url = "$base/$kind.m3u?run=$run"
        val result = graph.providers().addProvider(LegacyProvider(
            name = "In-flight guide fixture $run $kind", type = ProviderType.M3U,
            serverUrl = url, m3uUrl = url,
            epgUrl = "$base/${if (kind == "background") "guide" else "small"}.xml?run=$run",
            epgSyncMode = ProviderEpgSyncMode.BACKGROUND, isActive = false))
        check(result is Result.Success) { "In-flight fixture provider creation failed" }
        ids.add(result.data)
        previousOverrides[result.data] = graph.preferences().automaticSyncConfiguration.first().providers[result.data]
        graph.preferences().setAutomaticSyncSettings(result.data, policy)
        app.filesDir.resolve("inflight-$run.json").writeText(JSONObject().put("providerIds", JSONArray(ids)).toString())
        return result.data
    }

    private suspend fun infos(id: Long) = withContext(Dispatchers.IO) {
        work.getWorkInfosForUniqueWork("provider-epg-$id").get(30, TimeUnit.SECONDS)
    }

    private suspend fun enqueue(id: Long, manual: Boolean = false): UUID {
        val before = infos(id).map { it.id }.toSet()
        if (manual) graph.sync().scheduleManualEpgSync(id)
        else BackgroundEpgSyncWorker.enqueue(app, id, force = true, initialDelaySeconds = 0)
        return withTimeout(15_000L) {
            var added: UUID? = null
            while (added == null) {
                added = infos(id).firstOrNull { it.id !in before }?.id
                if (added == null) delay(50L)
            }
            added
        }
    }

    private suspend fun awaitSuccess(id: UUID) = withTimeout(90_000L) {
        while (withContext(Dispatchers.IO) { work.getWorkInfoById(id).get(30, TimeUnit.SECONDS) }?.state != WorkInfo.State.SUCCEEDED) delay(200L)
    }

    private suspend fun phase(): String {
        val dao = graph.database().providerWorkflowDao()
        val workflow = dao.getWorkflow(guideId) ?: return "MISSING"
        return dao.getPhases(guideId, workflow.generation).firstOrNull { it.phase.name == "EPG" }?.state?.name.orEmpty()
    }

    private suspend fun networkSnapshot(label: String): WorkInfo {
        val info = withContext(Dispatchers.IO) {
            work.getWorkInfoById(checkNotNull(guideRequestId)).get(30, TimeUnit.SECONDS)!!
        }
        if (label == "network-first-outcome") networkFirstAttempt = info.runAttemptCount
        val metadata = graph.syncMetadata().getMetadata(guideId)
        emit(JSONObject().put("event", label).put("requestId", info.id.toString())
            .put("workState", info.state.name).put("attempt", info.runAttemptCount)
            .put("playbackOwned", AutomaticSyncActivity.hasPlayback)
            .put("programs", graph.database().programDao().countByProvider(guideId))
            .put("stagedPrograms", graph.database().programDao().countByProvider(-guideId))
            .put("phase", phase()).put("jobState", graph.database().xtreamIndexJobDao().get(guideId, "EPG")?.state)
            .put("oldSeedRows", if (providerKind == "stalker") retainedSeedRows() else -1)
            .put("metadataCount", metadata?.epgCount)
            .put("lastEpgSuccess", metadata?.lastEpgSuccess ?: 0L)
            .put("baselineLastEpgSuccess", prefixMetadata?.lastEpgSuccess ?: 0L)
            .put("freshnessUnchanged", (metadata?.lastEpgSuccess ?: 0L) == (prefixMetadata?.lastEpgSuccess ?: 0L))
            .put("freshnessAdvanced", (metadata?.lastEpgSuccess ?: 0L) > (prefixMetadata?.lastEpgSuccess ?: 0L)))
        return info
    }

    private suspend fun finishNetworkFault() {
        try {
            withTimeout(15_000L) { while (AutomaticSyncActivity.hasPlayback) delay(100L) }
            withContext(Dispatchers.IO) {
                URL("$base/recover?run=$run").openConnection().apply { connectTimeout=5000; readTimeout=5000 }
                    .getInputStream().use { it.readBytes() }
            }
            emit(JSONObject().put("event", "network-source-restored").put("resubmitted", false))
            withTimeout(180_000L) {
                while (!withContext(Dispatchers.IO) {
                    work.getWorkInfoById(checkNotNull(guideRequestId)).get(30, TimeUnit.SECONDS)!!.state.isFinished
                }) delay(250L)
            }
            val info = networkSnapshot("network-idle-result")
            if (permanentHttpError) {
                check(info.state == WorkInfo.State.SUCCEEDED && info.runAttemptCount == networkFirstAttempt &&
                    graph.database().programDao().countByProvider(guideId) == 1 &&
                    graph.database().programDao().countByProvider(-guideId) == 0 &&
                    (graph.syncMetadata().getMetadata(guideId)?.lastEpgSuccess ?: 0L) ==
                        (prefixMetadata?.lastEpgSuccess ?: 0L)) { "Authentication error retried or replaced cached guide" }
                return
            }
            check(info.state == WorkInfo.State.SUCCEEDED && info.runAttemptCount > 0 &&
                graph.database().programDao().countByProvider(guideId) == 100_000 &&
                graph.database().programDao().countByProvider(-guideId) == 0) { "Natural guide retry did not recover" }
            if (providerKind == "stalker") check(retainedSeedRows() == 0 &&
                graph.syncMetadata().getMetadata(guideId)?.epgCount == 100_000 &&
                (graph.syncMetadata().getMetadata(guideId)?.lastEpgSuccess ?: 0L) > (prefixMetadata?.lastEpgSuccess ?: 0L))
        } finally { cleanup() }
    }

    suspend fun playbackReady() = withContext(Dispatchers.IO) {
        check(AutomaticSyncActivity.hasPlayback)
        if (interruptGuide) {
            val cancelledAt = android.os.SystemClock.elapsedRealtime()
            work.cancelWorkById(checkNotNull(guideRequestId)).result.get(30, TimeUnit.SECONDS)
            emit(JSONObject().put("event", "stalker-guide-cancel-requested")
                .put("acknowledgedAfterMs",android.os.SystemClock.elapsedRealtime()-cancelledAt))
            if (holdDuringCancel) {
                laneProbe = scope.async {
                    val manager = graph.sync()
                    val locks = manager.javaClass.getDeclaredField("providerWorkLocks").apply { isAccessible = true }
                        .get(manager) as com.streamvault.data.sync.ProviderWorkLockRegistry
                    withTimeout(90_000L) { locks.withStalkerGuideLock(guideId) { Unit } }
                    val elapsed = android.os.SystemClock.elapsedRealtime() - cancelledAt
                    emit(JSONObject().put("event", "stalker-guide-cancel-lane-free").put("latencyMs", elapsed)
                        .put("responseReleased", false))
                    elapsed
                }
                emit(JSONObject().put("event", "stalker-guide-response-left-held").put("playbackOwned", true))
                return@withContext
            }
        }
        URL("$base/release?run=$run").openConnection().apply { connectTimeout = 5_000; readTimeout = 5_000 }
            .getInputStream().use { it.readBytes() }
        emit(JSONObject().put("event", "inflight-released").put("playbackOwned", true))
        if (networkFault) networkObservation = scope.async {
            withTimeout(60_000L) {
                while (true) {
                    val info = work.getWorkInfoById(checkNotNull(guideRequestId)).get(30, TimeUnit.SECONDS)!!
                    if (info.state.isFinished || (info.state == WorkInfo.State.ENQUEUED && info.runAttemptCount > 0)) break
                    delay(100L)
                }
            }
            networkSnapshot("network-first-outcome")
            Unit
        }
    }

    suspend fun finishWhilePlaying() {
        check(AutomaticSyncActivity.hasPlayback)
        if (interruptGuide) { laneProbe?.await(); verifyInterrupted(); return }
        if (networkFault) {
            try { networkObservation?.await() } catch (e: TimeoutCancellationException) {
                emit(JSONObject().put("event", "network-first-outcome-timeout"))
            }
            networkSnapshot("network-during-playback")
            return
        }
        visibleDuringPlayback = graph.database().programDao().countByProvider(guideId)
        phaseDuringPlayback = phase()
        emit(JSONObject().put("event", "inflight-observed").put("playbackOwned", true)
            .put("visiblePrograms", visibleDuringPlayback).put("stagedPrograms", graph.database().programDao().countByProvider(-guideId))
            .put("phase", phaseDuringPlayback))
        if (providerKind in setOf("xtream", "stalker")) {
            val stage = graph.database().programDao().countByProvider(-guideId)
            val job = graph.database().xtreamIndexJobDao().get(guideId, "EPG")
            emit(JSONObject().put("event", if (providerKind == "stalker") "stalker-guide-observed" else "xtream-guide-observed").put("programs", visibleDuringPlayback)
                .put("stagedPrograms", stage).put("phase", phaseDuringPlayback).put("jobState", job?.state))
            if (providerKind == "stalker") {
                val state = withContext(Dispatchers.IO) {
                    work.getWorkInfoById(checkNotNull(guideRequestId)).get(30, TimeUnit.SECONDS)!!.state
                }
                val metadata = graph.syncMetadata().getMetadata(guideId)
                val seed = graph.database().openHelper.readableDatabase.query(
                    "SELECT COUNT(*) FROM programs WHERE provider_id = ? AND title = 'Portal retained seed'",
                    arrayOf<Any>(guideId)).use { it.moveToFirst(); it.getInt(0) }
                emit(JSONObject().put("event", "stalker-guide-completion-verified").put("programs", visibleDuringPlayback)
                    .put("stagedPrograms", stage).put("workState", state.name).put("oldCoveredSeedRows", seed)
                    .put("metadataCount", metadata?.epgCount).put("freshnessAdvanced", (metadata?.lastEpgSuccess ?: 0L) > 0L)
                    .put("ownership", "UNIQUE_WORKER_GUIDE_MUTEX").put("guideShape", if(perChannel) "PER_CHANNEL" else "BULK").put("expectedSeedRows",expectedSeedRows))
                check(visibleDuringPlayback == expectedPrograms && stage == 0 && seed == expectedSeedRows && state == WorkInfo.State.SUCCEEDED &&
                    metadata?.epgCount == expectedPrograms && (metadata.lastEpgSuccess ?: 0L) > 0L) { "Native guide incomplete" }
                return
            }
            check(visibleDuringPlayback == 100_000 && stage == 0 && phaseDuringPlayback == "SUCCEEDED") {
                "Xtream guide did not finish: visible=$visibleDuringPlayback staged=$stage phase=$phaseDuringPlayback"
            }
            return
        }
        awaitSuccess(enqueue(manualId, manual = true))
        check(graph.database().programDao().countByProvider(manualId) == 1000)
        graph.preferences().setAutomaticSyncSettings(manualId, policy.copy(deferWhileBusy = false))
        awaitSuccess(enqueue(manualId))
        emit(JSONObject().put("event", "inflight-manual-optout-success").put("playbackOwned", true))
    }

    suspend fun finishAfterPlayback() {
        if (networkFault) { finishNetworkFault(); return }
        if (interruptGuide) {
            try {
                withTimeout(15_000L) { while (AutomaticSyncActivity.hasPlayback) delay(100L) }
                guideRequestId = enqueue(guideId)
                awaitSuccess(checkNotNull(guideRequestId))
                val rows = graph.database().programDao().countByProvider(guideId)
                val metadata = graph.syncMetadata().getMetadata(guideId)
                check(rows == expectedPrograms && retainedSeedRows() == expectedSeedRows && metadata?.epgCount == expectedPrograms &&
                    (metadata.lastEpgSuccess ?: 0L) > (prefixMetadata?.lastEpgSuccess ?: 0L))
                emit(JSONObject().put("event", "stalker-guide-idle-repair-verified").put("programs", rows)
                    .put("freshnessAdvanced", true).put("oldCoveredSeedRows", retainedSeedRows()))
            } finally { cleanup() }
            return
        }
        if (providerKind in setOf("xtream", "stalker")) { cleanup(); return }
        try {
            withTimeout(15_000L) { while (AutomaticSyncActivity.hasPlayback) delay(100L) }
            awaitSuccess(enqueue(guideId))
            check(graph.database().programDao().countByProvider(guideId) == 100_000)
            check(graph.database().programDao().countByProvider(-guideId) == 0)
            emit(JSONObject().put("event", "inflight-idle-success").put("phase", phase()).put("programs", 100_000))
        } finally { cleanup() }
        check(visibleDuringPlayback == 1 && phaseDuringPlayback == "PENDING") {
            "In-flight guide continued during playback: visible=$visibleDuringPlayback phase=$phaseDuringPlayback"
        }
    }

    private suspend fun cleanup() {
        if (holdDuringCancel) withContext(Dispatchers.IO) {
            URL("$base/close-held").openConnection().apply { connectTimeout=5000;readTimeout=5000 }
                .getInputStream().use { it.readBytes() }
        }
        scope.cancel()
        for (id in ids) {
            BackgroundEpgSyncWorker.cancel(app, id)
            graph.database().programDao().deleteByProvider(-id)
            graph.providers().deleteProvider(id)
            graph.preferences().setAutomaticSyncSettings(id, previousOverrides[id])
            store.edit { values ->
                fun <T> restore(key: Preferences.Key<T>) {
                    val previous = originalPreferences[key]
                    if (previous == null) values.remove(key) else values[key] = previous
                }
                restore(stringPreferencesKey("automatic_sync_status_$id"))
                restore(longPreferencesKey("catalog_sync_upgrade_started_$id"))
                restore(intPreferencesKey("catalog_sync_engine_$id"))
            }
            check(graph.providers().getProvider(id) == null)
        }
        emit(JSONObject().put("event", "inflight-cleanup").put("providersRemoved", ids.size))
    }

    /** Persist only disposable fixture ownership before the host kills this process. */
    suspend fun checkpointForProcessDeath() {
        val ownedKeys = ids.flatMap { id -> listOf("automatic_sync_status_$id",
            "catalog_sync_upgrade_started_$id", "catalog_sync_engine_$id") }.toSet()
        check(originalPreferences.asMap().keys.none { it.name in ownedKeys })
        check(previousOverrides.values.all { it == null })
        val info = networkSnapshot("process-death-checkpoint")
        check(info.state == WorkInfo.State.RUNNING)
        if (providerKind == "stalker") {
            check(graph.database().programDao().countByProvider(guideId) >= 5001 && retainedSeedRows() == 1)
        } else {
            check(graph.database().programDao().countByProvider(guideId) == 1)
            check(graph.database().programDao().countByProvider(-guideId) >= 5000)
        }
        val saved = JSONObject().put("base", base).put("kind", providerKind).put("run", run)
            .put("providerIds", JSONArray(ids)).put("guideId", guideId)
            .put("requestId", checkNotNull(guideRequestId).toString())
            .put("lastEpgSuccess", prefixMetadata?.lastEpgSuccess ?: 0L)
            .put("workIds", JSONArray(infos(guideId).map { it.id.toString() }))
            .put("pid", android.os.Process.myPid())
        app.filesDir.resolve("process-death-$run-owned.json").writeText(saved.toString(2))
        emit(JSONObject().put("event", "process-death-ready").put("pid", android.os.Process.myPid()))
    }

    suspend fun observeAfterProcessDeath(saved: JSONObject, cleanupOnly: Boolean) {
        try {
            check(saved.getInt("pid") != android.os.Process.myPid()) { "Process did not restart" }
            check(!AutomaticSyncActivity.hasPlayback)
            val initial = networkSnapshot("process-restart-initial")
            check(initial.state == WorkInfo.State.ENQUEUED || initial.state == WorkInfo.State.RUNNING)
            check((graph.syncMetadata().getMetadata(guideId)?.lastEpgSuccess ?: 0L) == saved.getLong("lastEpgSuccess"))
            if (cleanupOnly) return
            URL("$base/release?run=$run").openConnection().apply { connectTimeout=5000; readTimeout=5000 }
                .getInputStream().use { it.readBytes() }
            withTimeout(300_000L) {
                while (true) {
                    val info = networkSnapshot("process-restart-poll")
                    if (info.state.isFinished) {
                        check(info.state == WorkInfo.State.SUCCEEDED)
                        break
                    }
                    delay(2000L)
                }
            }
            val rows = graph.database().programDao().countByProvider(guideId)
            val stage = graph.database().programDao().countByProvider(-guideId)
            val metadata = graph.syncMetadata().getMetadata(guideId)
            check(rows == 100_000 && stage == 0)
            check((metadata?.lastEpgSuccess ?: 0L) > saved.getLong("lastEpgSuccess"))
            if (providerKind == "stalker") check(retainedSeedRows() == 0 && metadata?.epgCount == 100_000)
            val beforeIds = saved.getJSONArray("workIds").let { array ->
                (0 until array.length()).map { array.getString(it) }.toSet()
            }
            check(infos(guideId).map { it.id.toString() }.toSet() == beforeIds)
            emit(JSONObject().put("event", "process-restart-recovered").put("sameRequest", true)
                .put("programs", rows).put("stagedPrograms", stage).put("resubmitted", false))
        } finally { cleanup() }
    }

    /** The independent TV host observed recovery without restarting instrumentation in the player. */
    suspend fun verifyExternallyRecoveredGuide(saved: JSONObject) {
        try {
            check(!AutomaticSyncActivity.hasPlayback)
            val info = networkSnapshot("external-guide-final")
            check(info.state == WorkInfo.State.SUCCEEDED)
            check(graph.database().programDao().countByProvider(guideId) == 100_000)
            check(graph.database().programDao().countByProvider(-guideId) == 0)
            check((graph.syncMetadata().getMetadata(guideId)?.lastEpgSuccess ?: 0L) > saved.getLong("lastEpgSuccess"))
            val beforeIds = saved.getJSONArray("workIds").let { array ->
                (0 until array.length()).map { array.getString(it) }.toSet()
            }
            check(infos(guideId).map { it.id.toString() }.toSet() == beforeIds)
            if (providerKind == "stalker") check(retainedSeedRows() == 0)
        } finally { cleanup() }
    }

    companion object {
        suspend fun resumeAfterProcessDeath(app: Application, component: Any, run: String,
                                           emit: (JSONObject) -> Unit): Pair<PlaybackInflightEpgProbe, JSONObject> {
            val saved = JSONObject(app.filesDir.resolve("process-death-$run-owned.json").readText())
            require(saved.getString("run") == run && app.packageName.endsWith(".debug"))
            require(Uri.parse(saved.getString("base")).host in setOf("127.0.0.1", "localhost"))
            val probe = PlaybackInflightEpgProbe(app, component, saved.getString("base"), run, emit, saved.getString("kind"))
            probe.guideId = saved.getLong("guideId")
            probe.guideRequestId = UUID.fromString(saved.getString("requestId"))
            probe.originalPreferences = emptyPreferences()
            val ids = saved.getJSONArray("providerIds")
            for (index in 0 until ids.length()) {
                val id = ids.getLong(index)
                check(probe.graph.database().providerDao().getById(id)?.name == "In-flight guide fixture $run ${if (id == probe.guideId) "background" else "manual"}")
                probe.ids.add(id)
                probe.previousOverrides[id] = null
            }
            return probe to saved
        }

        suspend fun start(app: Application, component: Any, args: Bundle, run: String,
                          emit: (JSONObject) -> Unit): PlaybackInflightEpgProbe? {
            val base = args.getString("inflightFixtureBase") ?: return null
            require(args.getString("workerFixtureBase") == null && args.getString("importFixtureBase") == null)
            require(Uri.parse(base).host in setOf("127.0.0.1", "localhost") && app.packageName.endsWith(".debug"))
            check(!AutomaticSyncActivity.hasPlayback)
            val kind = args.getString("inflightProviderKind") ?: "m3u"
            require(kind in setOf("m3u", "xtream", "stalker"))
            val probe = PlaybackInflightEpgProbe(app, component, base, run, emit, kind)
            probe.networkFault = args.getString("inflightNetworkFault") == "true"
            probe.httpError = args.getString("inflightHttpError") == "true"
            probe.permanentHttpError = args.getString("inflightPermanentHttpError") == "true"
            require(!probe.httpError || probe.networkFault)
            require(!probe.permanentHttpError || probe.httpError)
            require(!probe.networkFault || kind in setOf("xtream", "stalker"))
            probe.perChannel = args.getString("inflightGuideShape") == "perChannel"
            probe.holdDuringCancel = args.getString("inflightHoldDuringCancel") == "true"
            require(!probe.perChannel || kind == "stalker")
            probe.sameProvider = args.getString("inflightSameProvider") == "true"
            probe.interruptGuide = args.getString("inflightInterrupt") == "true"
            require(!probe.sameProvider || kind == "stalker")
            require(!probe.interruptGuide || probe.sameProvider)
            require(!probe.holdDuringCancel || probe.interruptGuide)
            probe.originalPreferences = probe.store.data.first()
            try {
                probe.guideId = probe.add("background")
                if (kind != "stalker") probe.manualId = probe.add("manual")
                if (kind == "stalker") {
                    probe.graph.database().channelDao().insertAll((1L..(if (probe.perChannel) 4L else 2000L)).map {
                        ChannelEntity(name = "Portal guide fixture $it", streamId = it, epgChannelId = it.toString(),
                            streamUrl = "$base/unplayed/$it.ts", providerId = probe.guideId)
                    })
                }
                val epg = (component.javaClass.getDeclaredField("epgRepositoryImplProvider").apply { isAccessible = true }
                    .get(component) as Provider<*>).get() as EpgRepository
                check(epg.refreshEpg(probe.guideId, "$base/seed.xml?run=$run") is Result.Success)
                check(probe.graph.database().programDao().countByProvider(probe.guideId) == 1)
                probe.prefixMetadata = probe.graph.syncMetadata().getMetadata(probe.guideId)
                probe.guideRequestId = probe.enqueue(probe.guideId)
                withTimeout(if (kind == "stalker") 90_000L else 30_000L) {
                    if (probe.httpError) {
                        withContext(Dispatchers.IO) {
                            while (true) {
                                val state = URL("$base/gate?run=$run").openConnection()
                                    .apply { connectTimeout=5000; readTimeout=5000 }.getInputStream()
                                    .use { JSONObject(it.bufferedReader().readText()) }
                                if (state.optBoolean("requestSeen")) break
                                delay(100L)
                            }
                        }
                    } else while (probe.graph.database().programDao().countByProvider(
                        if (kind == "stalker") probe.guideId else -probe.guideId) < 5000) delay(100L)
                }
                emit(JSONObject().put("event", "inflight-admitted").put("playbackOwned", AutomaticSyncActivity.hasPlayback)
                    .put("providerIds", JSONArray(probe.ids))
                    .put("visiblePrograms", probe.graph.database().programDao().countByProvider(probe.guideId))
                    .put("stagedPrograms", probe.graph.database().programDao().countByProvider(-probe.guideId)))
                if (kind == "stalker") {
                    val seed = probe.graph.database().openHelper.readableDatabase.query(
                        "SELECT COUNT(*) FROM programs WHERE provider_id = ? AND title = 'Portal retained seed'",
                        arrayOf<Any>(probe.guideId)).use { it.moveToFirst(); it.getInt(0) }
                    probe.prefixMetadata = probe.graph.syncMetadata().getMetadata(probe.guideId)
                    check(seed == 1 && probe.infos(probe.guideId).any { it.state == WorkInfo.State.RUNNING })
                    emit(JSONObject().put("event", "stalker-guide-prefix-verified").put("oldUncoveredGuideRetained", true)
                        .put("programs", probe.graph.database().programDao().countByProvider(probe.guideId)))
                }
            } catch (error: Throwable) { probe.cleanup(); throw error }
            return probe
        }
    }
}
