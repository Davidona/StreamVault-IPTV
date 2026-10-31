package com.streamvault.app.tv

import android.app.Application
import android.content.ContentValues
import android.media.tv.TvContract
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.streamvault.app.benchmark.XtreamSyncBenchmarkEntryPoint
import com.streamvault.app.ui.PlaybackInflightEpgProbe
import com.streamvault.data.local.entity.ChannelEntity
import com.streamvault.data.local.entity.ProviderConfigEntity
import com.streamvault.data.local.entity.ProviderEntity
import com.streamvault.domain.model.M3uConfig
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.sync.AutomaticSyncActivity
import com.streamvault.domain.sync.AutomaticSyncSettings
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.internal.GeneratedComponentManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TvInputProcessDeathFixtureTest {
    @Test fun prepareOrCleanup() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("tvInputDeathFixture") == "true")
        val app = ApplicationProvider.getApplicationContext<Application>()
        require(app.packageName == "com.streamvault.app.debug")
        val run = requireNotNull(args.getString("runId"))
        require(run.matches(Regex("[A-Za-z0-9_-]{1,40}")))
        val file = app.filesDir.resolve("tif-death-$run-owned.json")
        val graph = EntryPointAccessors.fromApplication(app, XtreamSyncBenchmarkEntryPoint::class.java)
        val db = graph.database()
        val component = (app as GeneratedComponentManager<*>).generatedComponent()
        val emit: (JSONObject) -> Unit = { row ->
            app.filesDir.resolve("tif-death-$run-guide.jsonl").appendText(row.toString() + "\n")
        }
        @Suppress("UNCHECKED_CAST")
        val store = graph.preferences().javaClass.getDeclaredMethod("getPreferencesDataStore")
            .apply { isAccessible = true }.invoke(graph.preferences()) as DataStore<Preferences>
        when (args.getString("deathFixturePhase")) {
            "prepare" -> {
                check(!AutomaticSyncActivity.hasPlayback && !file.exists())
                val url = requireNotNull(args.getString("fixtureUrl"))
                require(Uri.parse(url).host in setOf("127.0.0.1", "localhost"))
                val id = db.providerDao().insert(ProviderEntity(name="TIF death fixture $run", type=ProviderType.M3U, isActive=false))
                check(graph.preferences().automaticSyncConfiguration.first().providers[id] == null)
                val keys = setOf("automatic_sync_status_$id", "catalog_sync_upgrade_started_$id", "catalog_sync_engine_$id")
                check(store.data.first().asMap().keys.none { it.name in keys })
                val saved = JSONObject().put("providerId", id).put("pid", android.os.Process.myPid())
                    .put("run", run).put("guide", args.getString("inflightFixtureBase") != null)
                file.writeText(saved.toString(2)) // Recover ownership even if later setup fails.
                graph.preferences().setAutomaticSyncSettings(id, AutomaticSyncSettings(enabled=false, live=false, movies=false, series=false, epg=false))
                db.providerSnapshotDao().commitConfiguration(ProviderConfigEntity(providerId=id, type=ProviderType.M3U,
                    schemaVersion=1, configurationGeneration=1, identityKey="tif-death-$run",
                    encryptedConfigJson=com.google.gson.Gson().toJson(M3uConfig(playlistUrl=url)), updatedAt=System.currentTimeMillis()))
                val channel = db.channelDao().insert(ChannelEntity(name="TIF death fixture $run",providerId=id,streamId=1,streamUrl=url))
                val uri = requireNotNull(app.contentResolver.insert(TvContract.Channels.CONTENT_URI, ContentValues().apply {
                    put(TvContract.Channels.COLUMN_INPUT_ID, "${app.packageName}/com.streamvault.app.tvinput.StreamVaultTvInputService")
                    put(TvContract.Channels.COLUMN_TYPE, TvContract.Channels.TYPE_OTHER)
                    put(TvContract.Channels.COLUMN_DISPLAY_NAME, "TIF death fixture $run")
                    put(TvContract.Channels.COLUMN_DISPLAY_NUMBER, "9980")
                    put(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_DATA, "$id:$channel")
                }))
                saved.put("channelUri", uri.toString()); file.writeText(saved.toString(2))
                val guide = PlaybackInflightEpgProbe.start(app,component,args,run,emit)
                guide?.checkpointForProcessDeath()
                saved.put("ready", true); file.writeText(saved.toString(2))
                delay(180_000L)
                error("Host did not kill the fixture process")
            }
            "cleanup" -> {
                check(!AutomaticSyncActivity.hasPlayback)
                val saved = JSONObject(file.readText()); val id = saved.getLong("providerId")
                check(saved.getString("run") == run && db.providerDao().getById(id)?.name == "TIF death fixture $run")
                if (saved.getBoolean("guide")) {
                    val (probe, guideSaved) = PlaybackInflightEpgProbe.resumeAfterProcessDeath(app,component,run,emit)
                    probe.verifyExternallyRecoveredGuide(guideSaved)
                }
                if (saved.has("channelUri")) app.contentResolver.delete(Uri.parse(saved.getString("channelUri")),null,null)
                graph.providers().deleteProvider(id)
                graph.preferences().setAutomaticSyncSettings(id,null)
                store.edit { values ->
                    values.remove(stringPreferencesKey("automatic_sync_status_$id"))
                    values.remove(longPreferencesKey("catalog_sync_upgrade_started_$id"))
                    values.remove(intPreferencesKey("catalog_sync_engine_$id"))
                }
                check(db.providerDao().getById(id) == null)
                app.filesDir.resolve("tif-death-$run-cleanup.json").writeText(JSONObject().put("removed",true).put("providerId",id).toString())
            }
            else -> error("Explicit phase required")
        }
    }
}
