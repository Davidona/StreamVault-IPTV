package com.streamvault.app.ui

import android.app.Application
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import com.streamvault.app.benchmark.XtreamSyncBenchmarkEntryPoint
import com.streamvault.domain.model.LegacyProvider
import com.streamvault.domain.model.ProviderEpgSyncMode
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.Result
import com.streamvault.domain.repository.EpgRepository
import dagger.hilt.android.EntryPointAccessors
import javax.inject.Provider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject

/** Opt-in contention probe: real import code on IO, only newly-created local providers. */
internal class PlaybackImportWorkload private constructor(
    private val app: Application,
    private val component: Any,
    private val base: String,
    private val run: String,
    private val emit: (JSONObject) -> Unit
) {
    private val graph = EntryPointAccessors.fromApplication(app, XtreamSyncBenchmarkEntryPoint::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ids = mutableListOf<Long>()
    private val jobs = mutableListOf<Deferred<Unit>>()

    private suspend fun add(kind: String): Long {
        // Distinct configurations: provider identity is derived from the playlist URL.
        val url = "$base/$kind.m3u"
        val result = graph.providers().addProvider(LegacyProvider(
            name = "Playback import fixture $run $kind", type = ProviderType.M3U,
            serverUrl = url, m3uUrl = url, isActive = false,
            epgSyncMode = ProviderEpgSyncMode.SKIP, m3uVodClassificationEnabled = true))
        check(result is Result.Success) { "Could not add disposable import provider $kind: $result" }
        ids.add(result.data)
        app.filesDir.resolve("playback-import-$run.json").writeText(
            JSONObject().put("providerIds", org.json.JSONArray(ids)).toString())
        return result.data
    }

    private suspend fun runKind(kind: String, id: Long) {
        val epg = if (kind == "epg") (component.javaClass.getDeclaredField("epgRepositoryImplProvider")
            .apply { isAccessible = true }.get(component) as Provider<*>).get() as EpgRepository else null
        // Six scheduled refreshes make overlap measurable; this is a stress workload,
        // not a claim that production normally refreshes every twenty seconds.
        val start = SystemClock.elapsedRealtime() + 10_000L
        repeat(6) { round ->
            delay((start + round * 20_000L - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
            val before = SystemClock.elapsedRealtime()
            emit(JSONObject().put("event", "import-start").put("kind", kind).put("round", round))
            val result = if (kind == "epg") epg!!.refreshEpg(id, "$base/guide.xml?round=$round")
                else graph.sync().refreshPlaylistCatalog(id)
            check(result is Result.Success) { "Local $kind import failed: ${result.javaClass.simpleName}" }
            val count = if (kind == "epg") graph.database().programDao().countByProvider(id)
                else graph.database().channelDao().getByProvider(id).first().size
            check(count > 0) { "Local $kind import produced no rows" }
            emit(JSONObject().put("event", "import-end").put("kind", kind).put("round", round)
                .put("durationMs", SystemClock.elapsedRealtime() - before).put("rows", count))
        }
    }

    suspend fun close() {
        var failure: Throwable? = null
        try {
            withTimeout(180_000L) { jobs.awaitAll() }
        } catch (error: Throwable) { failure = error }
        finally {
            scope.cancel()
            jobs.forEach { it.cancelAndJoin() }
            for (id in ids) {
                graph.database().programDao().deleteByProvider(-id)
                val deleted = graph.providers().deleteProvider(id)
                check(deleted is Result.Success && graph.providers().getProvider(id) == null) {
                    "Disposable provider cleanup failed: $id"
                }
            }
            emit(JSONObject().put("event", "import-cleanup").put("providersRemoved", ids.size))
        }
        failure?.let { throw it }
    }

    companion object {
        suspend fun start(app: Application, component: Any, args: Bundle, run: String,
                          emit: (JSONObject) -> Unit): PlaybackImportWorkload? {
            val base = args.getString("importFixtureBase") ?: return null
            require(app.packageName.endsWith(".debug"))
            val uri = Uri.parse(base)
            require(uri.scheme == "http" && uri.host in setOf("127.0.0.1", "localhost"))
            val kinds = when (args.getString("importKinds") ?: "both") {
                "epg" -> listOf("epg")
                "catalog" -> listOf("catalog")
                "both" -> listOf("catalog", "epg")
                else -> error("importKinds must be epg, catalog, or both")
            }
            val probe = PlaybackImportWorkload(app, component, base.trimEnd('/'), run, emit)
            try {
                val providers = kinds.map { it to probe.add(it) }
                emit(JSONObject().put("event", "import-ready").put("providers", providers.size))
                for ((kind, id) in providers) {
                    probe.jobs.add(probe.scope.async {
                        try { probe.runKind(kind, id) }
                        catch (error: Throwable) {
                            emit(JSONObject().put("event", "import-error").put("kind", kind)
                                .put("exception", error.javaClass.simpleName))
                            throw error
                        }
                    })
                }
            } catch (error: Throwable) {
                probe.close()
                throw error
            }
            return probe
        }
    }
}
