package com.streamvault.app.ui

import android.app.Application
import android.media.tv.TvContract
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.work.WorkManager
import androidx.work.WorkInfo
import com.streamvault.app.benchmark.XtreamSyncBenchmarkEntryPoint
import com.streamvault.app.tvinput.TvInputCatalogRefreshWorker
import com.streamvault.core.navigation.PlayerNavigationRequest
import com.streamvault.data.local.entity.*
import com.streamvault.domain.model.M3uConfig
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.sync.AutomaticSyncSettings
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject

/** Opt-in real TV-provider publication alongside the normal live PlayerScreen. */
internal class PlaybackPublisherProbe private constructor(
    private val app: Application, private val run: String, private val publish: Boolean,
    private val url: String, private val emit: (JSONObject) -> Unit
) {
    private val graph = EntryPointAccessors.fromApplication(app, XtreamSyncBenchmarkEntryPoint::class.java)
    private val db = graph.database()
    private val prefs = graph.preferences()
    private val work = WorkManager.getInstance(app)
    private val manager = EntryPointAccessors.fromApplication(app, TvInputCatalogRefreshWorker.Entry::class.java)
        .tvInputChannelSyncManager()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var task: Deferred<Unit>? = null
    private var providerId = 0L
    private var channelId = 0L
    private var ownsPreferences = false
    private val saved = JSONObject().put("run", run)
    @Suppress("UNCHECKED_CAST")
    private val store = prefs.javaClass.getDeclaredMethod("getPreferencesDataStore").apply { isAccessible = true }
        .invoke(prefs) as DataStore<Preferences>
    private fun persist() = app.filesDir.resolve("publisher-$run-owned.json").writeText(saved.toString(2))
    private fun count(uri: Uri, selection: String? = null, args: Array<String>? = null): Int =
        app.contentResolver.query(uri, arrayOf("_id"), selection, args, null)!!.use { it.count }
    private fun frameworkPrograms() = count(TvContract.Programs.CONTENT_URI)

    private suspend fun prepare() {
        // Publishing refreshes all managed rows; require a clean isolated TV catalog.
        check(db.providerDao().getAllSync().isEmpty()) { "Publisher probe requires an empty provider database" }
        check(count(TvContract.Channels.CONTENT_URI) == 0 && frameworkPrograms() == 0)
        check(!prefs.playerTimeshiftEnabled.first())
        saved.put("lastActiveProvider", store.data.first()[longPreferencesKey("last_active_provider_id")] ?: JSONObject.NULL)
            .put("variantObservation", store.data.first()[stringPreferencesKey("live_variant_observations")] ?: JSONObject.NULL)
        providerId = db.providerDao().insert(ProviderEntity(name = "Publisher fixture $run", type = ProviderType.M3U, isActive = false))
        saved.put("providerId", providerId); persist()
        check(prefs.automaticSyncConfiguration.first().providers[providerId] == null)
        val ownedKeys = setOf("automatic_sync_status_$providerId", "catalog_sync_upgrade_started_$providerId",
            "catalog_sync_engine_$providerId", "last_live_category_id_$providerId")
        check(store.data.first().asMap().keys.none { it.name in ownedKeys }) { "Fixture preference key already exists" }
        ownsPreferences = true
        prefs.setAutomaticSyncSettings(providerId, AutomaticSyncSettings(enabled = false, live = false, movies = false, series = false, epg = false))
        db.providerSnapshotDao().commitConfiguration(ProviderConfigEntity(providerId = providerId, type = ProviderType.M3U,
            schemaVersion = 1, configurationGeneration = 1, identityKey = "publisher-$run",
            encryptedConfigJson = com.google.gson.Gson().toJson(M3uConfig(playlistUrl = url.substringBefore("/live/") + "/unused.m3u")),
            updatedAt = System.currentTimeMillis()))
        db.channelDao().insertAll((1..2000).map { i -> ChannelEntity(providerId = providerId, streamId = i.toLong(),
            name = "Publisher channel $i", number = i, epgChannelId = "publisher-$i", streamUrl = url) })
        channelId = db.channelDao().getByProviderSync(providerId).single { it.streamId == 1L }.id
        saved.put("channelId", channelId); persist()
        val start = System.currentTimeMillis() / 3_600_000L * 3_600_000L - 3_600_000L
        for (chunk in (1..2000).chunked(100)) {
            db.programDao().insertAll(chunk.flatMap { i -> (0..23).map { j -> ProgramEntity(providerId = providerId,
                channelId = "publisher-$i", title = "Publisher programme $i/$j", description = "Fixture schedule entry $i/$j",
                startTime = start + j * 1_800_000L, endTime = start + (j + 1) * 1_800_000L) } })
        }
        db.providerDao().setActive(providerId)
        manager.refreshTvInputCatalogResult().getOrThrow()
        check(count(TvContract.Channels.CONTENT_URI) == 2000 && frameworkPrograms() == 48000)
        emit(JSONObject().put("event", "publisher-prepared").put("providerId", providerId)
            .put("channels", 2000).put("programs", 48000).put("publishArm", publish))
    }

    fun navigation() = PlayerNavigationRequest(streamUrl = url, title = "Publisher live fixture $run",
        channelId = "publisher-1", internalId = channelId, providerId = providerId, contentType = "LIVE")

    suspend fun awaitBaselineReady() {
        // Serialize behind startup's refresh; complete the same no-op pass in both arms.
        manager.refreshTvInputCatalogResult().getOrThrow()
        delay(2_000)
        emit(JSONObject().put("event", "publisher-baseline-ready").put("frameworkPrograms", frameworkPrograms()))
    }

    fun playbackReady() {
        check(task == null)
        check(com.streamvault.domain.sync.AutomaticSyncActivity.hasPlayback)
        task = scope.async {
            delay(10_000)
            emit(JSONObject().put("event", "publisher-guide-update-begin").put("publishArm", publish))
            db.openHelper.writableDatabase.execSQL("UPDATE programs SET title = title || ' revised' WHERE provider_id = ?", arrayOf(providerId))
            emit(JSONObject().put("event", "publisher-guide-update-end").put("rows", db.programDao().countByProvider(providerId)))
            if (publish) {
                val previous = work.getWorkInfosForUniqueWork("tv-input-catalog-refresh").get()
                check(previous.none { !it.state.isFinished }) { "Unrelated publication already running" }
                TvInputCatalogRefreshWorker.enqueue(app)
                val info = withTimeout(10_000) {
                    var created: WorkInfo? = null
                    while (created == null) {
                        created = work.getWorkInfosForUniqueWork("tv-input-catalog-refresh").get()
                            .singleOrNull { row -> previous.none { it.id == row.id } }
                        if (created == null) delay(50)
                    }
                    created
                }
                val workerId = checkNotNull(info).id
                saved.put("workerId", workerId.toString()); persist()
                emit(JSONObject().put("event", "publisher-work-enqueue").put("workerId", workerId))
                withTimeout(300_000) {
                    while (true) {
                        val info = work.getWorkInfoById(workerId).get()
                        if (info != null && info.state.isFinished) {
                            check(info.state == WorkInfo.State.SUCCEEDED) { "Publisher worker ended ${info.state}" }
                            break
                        }
                        delay(200)
                    }
                }
                emit(JSONObject().put("event", "publisher-work-complete").put("workerId", workerId)
                    .put("frameworkPrograms", frameworkPrograms()))
            } else emit(JSONObject().put("event", "publisher-control-no-publication"))
        }
    }

    suspend fun finishWhilePlaying() {
        task?.await()
        check(frameworkPrograms() == 48000)
        val changed = app.contentResolver.query(TvContract.Programs.CONTENT_URI, arrayOf("title"),
            null, null, null)!!.use { cursor ->
            var revised = 0
            while (cursor.moveToNext()) if (cursor.getString(0).endsWith(" revised")) revised++
            revised
        }
        check(changed == if (publish) 48000 else 0) { "Unexpected framework revision count $changed" }
        emit(JSONObject().put("event", "publisher-verified").put("frameworkRevisedPrograms", changed).put("publishArm", publish))
    }

    suspend fun close() {
        task?.cancelAndJoin(); scope.cancel()
        if (saved.has("workerId")) work.cancelWorkById(java.util.UUID.fromString(saved.getString("workerId"))).result.get()
        if (providerId == 0L) return
        check(db.providerDao().getById(providerId)?.name == "Publisher fixture $run")
        if (!ownsPreferences) {
            // Guard failed before any config/channel/preferences were created. Remove
            // only the newly inserted row; repository runtime cleanup could erase the
            // stale preference key which caused the preflight refusal.
            db.providerDao().delete(providerId)
            return
        }
        val deleted = graph.providers().deleteProvider(providerId)
        check(deleted is com.streamvault.domain.model.Result.Success && db.providerDao().getById(providerId) == null)
        db.providerDao().deactivateAll()
        manager.refreshTvInputCatalogResult().getOrThrow()
        prefs.setAutomaticSyncSettings(providerId, null)
        store.edit { values ->
            values.remove(stringPreferencesKey("automatic_sync_status_$providerId"))
            values.remove(longPreferencesKey("catalog_sync_upgrade_started_$providerId"))
            values.remove(intPreferencesKey("catalog_sync_engine_$providerId"))
            values.remove(longPreferencesKey("last_live_category_id_$providerId"))
            val active = longPreferencesKey("last_active_provider_id")
            if (values[active] == providerId) {
                if (saved.isNull("lastActiveProvider")) values.remove(active) else values[active] = saved.getLong("lastActiveProvider")
            }
            val key = stringPreferencesKey("live_variant_observations")
            val remaining = values[key].orEmpty().lineSequence().filter { it.isNotEmpty() && it.substringBefore('|').toLongOrNull() != channelId }.toList()
            val before = if (saved.isNull("variantObservation")) null else saved.getString("variantObservation")
            if (remaining == before.orEmpty().lineSequence().filter(String::isNotEmpty).toList()) {
                if (before == null) values.remove(key) else values[key] = before
            } else values[key] = remaining.joinToString("\n")
        }
        emit(JSONObject().put("event", "publisher-cleanup").put("providerId", providerId)
            .put("frameworkChannels", count(TvContract.Channels.CONTENT_URI)).put("frameworkPrograms", frameworkPrograms()))
    }

    companion object {
        suspend fun start(app: Application, args: Bundle, run: String, emit: (JSONObject) -> Unit): PlaybackPublisherProbe? {
            if (args.getString("publisherFixture") != "true") return null
            check(app.packageName == "com.streamvault.app.debug")
            val urls = requireNotNull(args.getString("fixtureUrls")).split(','); check(urls.size == 1)
            require(Uri.parse(urls.single()).host in setOf("127.0.0.1", "localhost"))
            val probe = PlaybackPublisherProbe(app, run, args.getString("publisherArm") == "publish", urls.single(), emit)
            try { probe.prepare() } catch (error: Throwable) { probe.close(); throw error }
            return probe
        }
    }
}
