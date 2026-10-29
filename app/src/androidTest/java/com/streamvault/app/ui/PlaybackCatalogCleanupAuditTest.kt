package com.streamvault.app.ui

import android.app.Application
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.streamvault.app.benchmark.XtreamSyncBenchmarkEntryPoint
import dagger.hilt.android.EntryPointAccessors
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackCatalogCleanupAuditTest {
    @Test fun removeOwnedCatalogOrigins() = kotlinx.coroutines.runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("catalogOriginCleanup") == "true")
        val ids = requireNotNull(args.getString("originCleanupIds")).split(',').map { it.toLong().also { id -> require(id > 0) } }
        val app = ApplicationProvider.getApplicationContext<Application>()
        val graph = EntryPointAccessors.fromApplication(app, XtreamSyncBenchmarkEntryPoint::class.java)
        val origins = app.getSharedPreferences("automatic_sync_work", android.content.Context.MODE_PRIVATE)
        val policy = com.streamvault.domain.sync.AutomaticSyncSettings(movies = false, series = false, epg = false).toString()
        val keys = ids.flatMap { id ->
            check(graph.database().providerDao().getById(id) == null) { "Provider still exists" }
            check(origins.getBoolean("xtream_$id", false) && origins.getBoolean("xtream_split_queue_$id", false))
            check(origins.getString("xtream_policy_$id", null) == policy) { "Origin policy changed since fixture audit" }
            listOf("xtream_$id", "xtream_split_queue_$id", "xtream_policy_$id")
        }
        check(origins.edit().apply { keys.forEach { remove(it) } }.commit())
        check(keys.none { origins.contains(it) })
        app.filesDir.resolve("catalog-origin-cleanup.json").writeText(JSONObject().put("removedKeys", org.json.JSONArray(keys)).toString(2))
    }
    @Test fun fixtureRowsAreRemoved() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("catalogCleanupAudit") == "true")
        val app = ApplicationProvider.getApplicationContext<Application>()
        val graph = EntryPointAccessors.fromApplication(app, XtreamSyncBenchmarkEntryPoint::class.java)
        val counts = JSONObject()
        for ((name, query) in listOf(
            "providers" to "SELECT COUNT(*) FROM providers WHERE name LIKE 'In-flight catalog fixture %'",
            "configs" to "SELECT COUNT(*) FROM provider_configs WHERE identity_key LIKE 'catalog-fixture-%'",
            "guideProviders" to "SELECT COUNT(*) FROM providers WHERE name LIKE 'In-flight guide fixture %'",
            "guideConfigs" to "SELECT COUNT(*) FROM provider_configs WHERE identity_key LIKE 'epg-fixture-%'"
        )) {
            graph.database().openHelper.readableDatabase.query(query).use { cursor ->
                check(cursor.moveToFirst())
                counts.put(name, cursor.getInt(0))
                check(cursor.getInt(0) == 0) { "Owned fixture rows remain: $name" }
            }
        }
        val guideIds = InstrumentationRegistry.getArguments().getString("guideCleanupIds")
            ?.split(',')?.map { it.toLong().also { id -> require(id > 0) } }.orEmpty()
        if (guideIds.isNotEmpty()) {
            val placeholders = guideIds.joinToString(",") { "?" }
            graph.database().openHelper.readableDatabase.query(
                "SELECT COUNT(*) FROM programs WHERE provider_id IN ($placeholders)",
                guideIds.map { -it as Any }.toTypedArray()).use { cursor ->
                check(cursor.moveToFirst());counts.put("guideStages", cursor.getInt(0));check(cursor.getInt(0) == 0)
            }
        }
        app.filesDir.resolve("catalog-cleanup-audit.json").writeText(counts.toString(2))
    }
    @Test fun restoreOnlyOwnedIndexLearning() = kotlinx.coroutines.runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("indexLearningCleanup") == "true")
        val app = ApplicationProvider.getApplicationContext<Application>()
        val graph = EntryPointAccessors.fromApplication(app, XtreamSyncBenchmarkEntryPoint::class.java)
        val file = app.filesDir.resolve("index-owned-learning-cleanup.json")
        val saved = JSONObject(file.readText())
        @Suppress("UNCHECKED_CAST")
        val store = graph.preferences().javaClass.getDeclaredMethod("getPreferencesDataStore")
            .apply { isAccessible = true }.invoke(graph.preferences()) as androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>
        val key = androidx.datastore.preferences.core.stringPreferencesKey("live_variant_observations")
        store.edit { values ->
            check(values[key] == saved.getString("expected")) { "Learning changed after audit; preserve current data" }
            values[key] = saved.getString("original")
        }
        check(file.delete())
    }
}
