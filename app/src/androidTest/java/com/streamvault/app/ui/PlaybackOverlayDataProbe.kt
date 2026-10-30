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
import com.streamvault.domain.model.PlayerSurfaceMode
import com.streamvault.domain.sync.AutomaticSyncSettings
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject

/** One real local channel/guide, identical in every opt-in overlay comparison. */
internal class PlaybackOverlayDataProbe private constructor(
    private val app: Application, private val run: String,
    private val url: String, private val emit: (JSONObject) -> Unit,
    private val surfaceMode: PlayerSurfaceMode?
) {
    private val graph = EntryPointAccessors.fromApplication(app, XtreamSyncBenchmarkEntryPoint::class.java)
    private val db = graph.database()
    private val prefs = graph.preferences()
    private val manager = EntryPointAccessors.fromApplication(app, TvInputCatalogRefreshWorker.Entry::class.java)
        .tvInputChannelSyncManager()
    private var providerId = 0L
    private var channelId = 0L
    private var ownsPreferences = false
    private val saved = JSONObject().put("run", run)
    @Suppress("UNCHECKED_CAST")
    private val store = prefs.javaClass.getDeclaredMethod("getPreferencesDataStore").apply { isAccessible = true }
        .invoke(prefs) as DataStore<Preferences>
    private fun persist() = app.filesDir.resolve("overlay-$run-owned.json").writeText(saved.toString(2))
    private fun count(uri: Uri, selection: String? = null, args: Array<String>? = null): Int =
        app.contentResolver.query(uri, arrayOf("_id"), selection, args, null)!!.use { it.count }
    private fun frameworkPrograms() = count(TvContract.Programs.CONTENT_URI)

    private suspend fun prepare() {
        // Publishing refreshes all managed rows; require a clean isolated TV catalog.
        check(db.providerDao().getAllSync().isEmpty()) { "Overlay probe requires an empty provider database" }
        check(count(TvContract.Channels.CONTENT_URI) == 0 && frameworkPrograms() == 0)
        check(!prefs.playerTimeshiftEnabled.first())
        saved.put("lastActiveProvider", store.data.first()[longPreferencesKey("last_active_provider_id")] ?: JSONObject.NULL)
            .put("variantObservation", store.data.first()[stringPreferencesKey("live_variant_observations")] ?: JSONObject.NULL)
            .put("originalSurfacePreference", store.data.first()[stringPreferencesKey("player_surface_mode")] ?: JSONObject.NULL)
            .put("fixtureSurfaceMode", surfaceMode?.name ?: JSONObject.NULL)
        providerId = db.providerDao().insert(ProviderEntity(name = "Overlay fixture $run", type = ProviderType.M3U, isActive = false))
        saved.put("providerId", providerId); persist()
        check(prefs.automaticSyncConfiguration.first().providers[providerId] == null)
        val ownedKeys = setOf("automatic_sync_status_$providerId", "catalog_sync_upgrade_started_$providerId",
            "catalog_sync_engine_$providerId", "last_live_category_id_$providerId")
        check(store.data.first().asMap().keys.none { it.name in ownedKeys }) { "Fixture preference key already exists" }
        ownsPreferences = true
        surfaceMode?.let { prefs.setPlayerSurfaceMode(it) }
        prefs.setAutomaticSyncSettings(providerId, AutomaticSyncSettings(enabled = false, live = false, movies = false, series = false, epg = false))
        db.providerSnapshotDao().commitConfiguration(ProviderConfigEntity(providerId = providerId, type = ProviderType.M3U,
            schemaVersion = 1, configurationGeneration = 1, identityKey = "overlay-$run",
            encryptedConfigJson = com.google.gson.Gson().toJson(M3uConfig(playlistUrl = url.substringBefore("/live/") + "/unused.m3u")),
            updatedAt = System.currentTimeMillis()))
        db.channelDao().insertAll((1..1).map { i -> ChannelEntity(providerId = providerId, streamId = i.toLong(),
            name = "Overlay live fixture", number = i, epgChannelId = "overlay-$i", streamUrl = url) })
        channelId = db.channelDao().getByProviderSync(providerId).single { it.streamId == 1L }.id
        saved.put("channelId", channelId); persist()
        val start = System.currentTimeMillis() / 3_600_000L * 3_600_000L - 3_600_000L
        for (chunk in (1..1).chunked(100)) {
            db.programDao().insertAll(chunk.flatMap { i -> (0..47).map { j -> ProgramEntity(providerId = providerId,
                channelId = "overlay-$i", title = "Overlay programme $i/$j", description = "Fixture schedule entry $i/$j",
                startTime = start + j * 900_000L, endTime = start + (j + 1) * 900_000L) } })
        }
        db.providerDao().setActive(providerId)
        manager.refreshTvInputCatalogResult().getOrThrow()
        check(count(TvContract.Channels.CONTENT_URI) == 1 && frameworkPrograms() == 24)
        emit(JSONObject().put("event", "overlay-prepared").put("providerId", providerId)
            .put("channels", 1).put("programs", 48))
    }

    fun navigation() = PlayerNavigationRequest(streamUrl = url, title = "Overlay live fixture",
        channelId = "overlay-1", internalId = channelId, providerId = providerId, contentType = "LIVE")

    suspend fun awaitBaselineReady() {
        // Serialize behind startup's refresh; complete the same no-op pass in both arms.
        manager.refreshTvInputCatalogResult().getOrThrow()
        delay(2_000)
        emit(JSONObject().put("event", "overlay-baseline-ready").put("frameworkPrograms", frameworkPrograms()))
    }

    suspend fun close() {
        if (providerId == 0L) return
        check(db.providerDao().getById(providerId)?.name == "Overlay fixture $run")
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
            if (surfaceMode != null) {
                val surfaceKey = stringPreferencesKey("player_surface_mode")
                check(values[surfaceKey] == surfaceMode.name) { "Surface preference changed outside fixture" }
                if (saved.isNull("originalSurfacePreference")) values.remove(surfaceKey)
                else values[surfaceKey] = saved.getString("originalSurfacePreference")
            }
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
        emit(JSONObject().put("event", "overlay-cleanup").put("providerId", providerId)
            .put("frameworkChannels", count(TvContract.Channels.CONTENT_URI)).put("frameworkPrograms", frameworkPrograms()))
    }

    companion object {
        suspend fun start(app: Application, args: Bundle, run: String, emit: (JSONObject) -> Unit): PlaybackOverlayDataProbe? {
            if (args.getString("overlayMode") == null) return null
            check(app.packageName == "com.streamvault.app.debug")
            val urls = requireNotNull(args.getString("fixtureUrls")).split(','); check(urls.size == 1)
            require(Uri.parse(urls.single()).host in setOf("127.0.0.1", "localhost"))
            val surfaceMode = args.getString("fixtureSurfaceMode")?.let(PlayerSurfaceMode::valueOf)
            require(surfaceMode == null || surfaceMode in setOf(PlayerSurfaceMode.AUTO, PlayerSurfaceMode.TEXTURE_VIEW))
            val probe = PlaybackOverlayDataProbe(app, run, urls.single(), emit, surfaceMode)
            try { probe.prepare() } catch (error: Throwable) { probe.close(); throw error }
            return probe
        }
    }
}
