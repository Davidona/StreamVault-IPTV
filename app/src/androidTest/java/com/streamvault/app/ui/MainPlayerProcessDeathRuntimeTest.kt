package com.streamvault.app.ui

import android.app.Application
import android.media.tv.TvContract
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.streamvault.app.benchmark.XtreamSyncBenchmarkEntryPoint
import com.streamvault.data.local.entity.ChannelEntity
import com.streamvault.data.local.entity.ProviderConfigEntity
import com.streamvault.data.local.entity.ProviderEntity
import com.streamvault.domain.model.M3uConfig
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.sync.AutomaticSyncSettings
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Data setup only: the actual PlayerScreen/kill/launcher return run without instrumentation. */
@RunWith(AndroidJUnit4::class)
class MainPlayerProcessDeathRuntimeTest {
    @Test fun removeAuditedFixtureNavigationPreferences() = runBlocking {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("mainPreferenceCleanup")=="true")
        require(args.getString("originalLastActiveAbsent")=="true")
        val runs=requireNotNull(args.getString("mainPreferenceRuns")).split(',')
        require(runs.isNotEmpty() && runs.all { it.matches(Regex("[A-Za-z0-9_-]{1,40}")) })
        val app=ApplicationProvider.getApplicationContext<Application>()
        require(app.packageName=="com.streamvault.app.debug")
        val graph=EntryPointAccessors.fromApplication(app,XtreamSyncBenchmarkEntryPoint::class.java)
        val ids=runs.map { run ->
            val saved=JSONObject(app.filesDir.resolve("main-death-$run-owned.json").readText())
            check(saved.getString("run")==run)
            // Older measured fixtures rely on the explicit host-audited absence flag.
            if(saved.has("lastActiveProvider")) check(saved.isNull("lastActiveProvider"))
            saved.getLong("providerId").also { check(graph.database().providerDao().getById(it)==null) }
        }
        @Suppress("UNCHECKED_CAST")
        val store=graph.preferences().javaClass.getDeclaredMethod("getPreferencesDataStore").apply { isAccessible=true }
            .invoke(graph.preferences()) as DataStore<Preferences>
        store.edit { values ->
            ids.forEach { id ->
                val key=longPreferencesKey("last_live_category_id_$id")
                check(values[key]==-1_000_000L) { "Fixture navigation changed since cleanup audit" }
                values.remove(key)
            }
            val lastActive=longPreferencesKey("last_active_provider_id")
            check(values[lastActive]==ids.last()) { "Active provider preference changed since cleanup audit" }
            values.remove(lastActive)
        }
        app.filesDir.resolve("main-navigation-cleanup.json").writeText(JSONObject().put("ownedProviderIds",JSONArray(ids)).put("removedAuditedKeys",ids.size+1).toString())
    }

    @Test fun prepareOrCleanup() = runBlocking {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("mainProcessDeathFixture")=="true")
        val run=requireNotNull(args.getString("runId"))
        require(run.matches(Regex("[A-Za-z0-9_-]{1,40}")))
        val app=ApplicationProvider.getApplicationContext<Application>()
        require(app.packageName=="com.streamvault.app.debug")
        val graph=EntryPointAccessors.fromApplication(app,XtreamSyncBenchmarkEntryPoint::class.java)
        val db=graph.database()
        val prefs=graph.preferences()
        @Suppress("UNCHECKED_CAST")
        val store=prefs.javaClass.getDeclaredMethod("getPreferencesDataStore").apply { isAccessible=true }.invoke(prefs) as DataStore<Preferences>
        val observationKey=stringPreferencesKey("live_variant_observations")
        val savedFile=app.filesDir.resolve("main-death-$run-owned.json")
        when(args.getString("mainDeathPhase")) {
            "prepare" -> {
                val url=requireNotNull(args.getString("fixtureUrl"))
                require(Uri.parse(url).host in setOf("127.0.0.1","localhost"))
                check(!savedFile.exists())
                check(!prefs.playerTimeshiftEnabled.first()) { "This fixture requires the existing timeshift-off setting" }
                val activeIds=db.providerDao().getAllSync().filter { it.isActive }.map { it.id }
                val id=db.providerDao().insert(ProviderEntity(name="Main death fixture $run",type=ProviderType.M3U,isActive=false))
                check(prefs.automaticSyncConfiguration.first().providers[id]==null)
                val ownedKeys=setOf("automatic_sync_status_$id","catalog_sync_upgrade_started_$id","catalog_sync_engine_$id","last_live_category_id_$id")
                check(store.data.first().asMap().keys.none { it.name in ownedKeys })
                val saved=JSONObject().put("run",run).put("providerId",id).put("url",url)
                    .put("title","Main live fixture $run").put("activeIds",JSONArray(activeIds))
                    .put("variantObservation",store.data.first()[observationKey] ?: JSONObject.NULL)
                    .put("lastActiveProvider",store.data.first()[longPreferencesKey("last_active_provider_id")] ?: JSONObject.NULL)
                savedFile.writeText(saved.toString(2))
                prefs.setAutomaticSyncSettings(id,AutomaticSyncSettings(enabled=false,live=false,movies=false,series=false,epg=false))
                db.providerSnapshotDao().commitConfiguration(ProviderConfigEntity(providerId=id,type=ProviderType.M3U,
                    schemaVersion=1,configurationGeneration=1,identityKey="main-death-$run",
                    encryptedConfigJson=com.google.gson.Gson().toJson(M3uConfig(playlistUrl=url.substringBefore("/live/")+"/unused.m3u")),updatedAt=System.currentTimeMillis()))
                val channel=db.channelDao().insert(ChannelEntity(name=saved.getString("title"),providerId=id,
                    streamId=if(url.contains("1002")) 1002 else 1001,streamUrl=url,number=1))
                saved.put("channelId",channel);savedFile.writeText(saved.toString(2))
                db.providerDao().setActive(id)
                saved.put("ready",true);savedFile.writeText(saved.toString(2))
            }
            "cleanup" -> {
                val saved=JSONObject(savedFile.readText());val id=saved.getLong("providerId")
                check(saved.getString("run")==run && db.providerDao().getById(id)?.name=="Main death fixture $run")
                val rows=mutableListOf<Uri>()
                app.contentResolver.query(TvContract.buildChannelsUriForInput("${app.packageName}/com.streamvault.app.tvinput.StreamVaultTvInputService"),
                    arrayOf("_id","internal_provider_data"),null,null,null)?.use { cursor ->
                    while(cursor.moveToNext()) if(cursor.getString(1)?.startsWith("$id:")==true)
                        rows+=TvContract.buildChannelUri(cursor.getLong(0))
                }
                rows.forEach { app.contentResolver.delete(it,null,null) }
                val activeId=db.providerDao().getActive().first()?.id
                graph.providers().deleteProvider(id)
                if(activeId==id) {
                    db.providerDao().deactivateAll()
                    val previous=saved.getJSONArray("activeIds")
                    for(index in 0 until previous.length()) {
                        val previousId=previous.getLong(index)
                        check(db.providerDao().getById(previousId)!=null)
                        db.providerDao().activate(previousId)
                    }
                }
                prefs.setAutomaticSyncSettings(id,null)
                store.edit { values ->
                    values.remove(stringPreferencesKey("automatic_sync_status_$id"))
                    values.remove(longPreferencesKey("catalog_sync_upgrade_started_$id"))
                    values.remove(intPreferencesKey("catalog_sync_engine_$id"))
                    values.remove(longPreferencesKey("last_live_category_id_$id"))
                    val lastActive=longPreferencesKey("last_active_provider_id")
                    if(values[lastActive]==id && saved.has("lastActiveProvider")) {
                        if(saved.isNull("lastActiveProvider")) values.remove(lastActive)
                        else values[lastActive]=saved.getLong("lastActiveProvider")
                    }
                    val current=values[observationKey]
                    val previous=if(saved.isNull("variantObservation")) null else saved.getString("variantObservation")
                    if(current!=previous) {
                        val channelId=saved.getLong("channelId")
                        val remaining=current.orEmpty().lineSequence().filter {
                            it.substringBefore('|').toLongOrNull()!=channelId
                        }.filter { it.isNotEmpty() }.toList()
                        val original=previous.orEmpty().lineSequence().filter { it.isNotEmpty() }.toList()
                        if(remaining==original) {
                            if(previous==null) values.remove(observationKey) else values[observationKey]=previous
                        } else values[observationKey]=remaining.joinToString("\n")
                    }
                }
                check(db.providerDao().getById(id)==null)
                app.filesDir.resolve("main-death-$run-cleanup.json").writeText(JSONObject().put("providerRemoved",true).put("frameworkRowsRemoved",rows.size).toString())
            }
            else -> error("Explicit phase required")
        }
    }
}
