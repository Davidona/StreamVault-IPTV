package com.streamvault.app.benchmark

import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.streamvault.domain.model.CatalogLayout
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.LibraryBrowseQuery
import com.streamvault.domain.model.ProviderEpgSyncMode
import com.streamvault.domain.model.StalkerTransportGrant
import com.streamvault.domain.model.StalkerTransportMode
import com.streamvault.domain.model.StalkerTransportOrigin
import com.streamvault.domain.model.VodCategoryHydrationRequest
import com.streamvault.domain.usecase.StalkerProviderSetupCommand
import com.streamvault.domain.usecase.ValidateAndAddProviderResult
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.internal.GeneratedComponentManager
import javax.inject.Provider
import com.streamvault.domain.repository.MovieRepository
import com.streamvault.domain.repository.SeriesRepository
import com.streamvault.domain.repository.VodRepository
import java.net.URI
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in production-graph probe. Portal credentials arrive through instrumentation arguments. */
@RunWith(AndroidJUnit4::class)
class StalkerCatalogRuntimeTest {
    @Test
    fun setupAndBrowse() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("stalkerCatalogProbe") == "true")
        val portal = requireNotNull(args.getString("portal"))
        val mac = requireNotNull(args.getString("mac"))
        val app = ApplicationProvider.getApplicationContext<Application>()
        check(app.packageName.endsWith(".debug"))
        val graph = EntryPointAccessors.fromApplication(app, XtreamSyncBenchmarkEntryPoint::class.java)
        val component = (app as GeneratedComponentManager<*>).generatedComponent()
        fun binding(name: String): Any = (component.javaClass.getDeclaredField(name).apply { isAccessible = true }
            .get(component) as Provider<*>).get()!!
        val movies = binding("movieRepositoryImplProvider") as MovieRepository
        val series = binding("seriesRepositoryImplProvider") as SeriesRepository
        val vod = binding("vodRepositoryImplProvider") as VodRepository
        val origin = URI(portal)
        args.getString("cleanupProviderId")?.toLongOrNull()?.let { id ->
            androidx.work.WorkManager.getInstance(app).cancelAllWork().result.get()
            graph.sync().cancelStalkerIndexSync(id)
            graph.providers().deleteProvider(id)
        }
        val started = SystemClock.elapsedRealtime()
        val provider = args.getString("browseOnlyProviderId")?.toLongOrNull()?.let {
            requireNotNull(graph.providers().getProvider(it))
        } ?: run {
            val result = withTimeout(240_000L) {
                graph.setup().loginStalker(StalkerProviderSetupCommand(
                    portalUrl = portal, macAddress = mac, name = "Stalker catalog validation",
                    existingProviderId = args.getString("existingProviderId")?.toLongOrNull(),
                    stalkerAdvancedOptionsJson = args.getString("catalogOrder")?.let {
                        JSONObject().put("catalogLoadOrder", it).toString()
                    }.orEmpty(),
                    epgSyncMode = args.getString("epgMode")?.let(ProviderEpgSyncMode::valueOf) ?: ProviderEpgSyncMode.SKIP,
                    transportGrant = StalkerTransportGrant(StalkerTransportMode.USER_ACCEPTED_HTTP,
                        StalkerTransportOrigin(origin.scheme, origin.host, origin.port.takeIf { it > 0 } ?: 80),
                        consentedAt = System.currentTimeMillis())
                ))
            }
            when (result) {
                is ValidateAndAddProviderResult.Success -> result.provider
                is ValidateAndAddProviderResult.SavedWithWarning -> result.provider
                else -> error("Setup failed: ${result.javaClass.simpleName}")
            }
        }
        val report = JSONObject().put("setupMs", SystemClock.elapsedRealtime() - started)
            .put("providerId", provider.id).put("layout", provider.catalogLayout.name)
            .put("savedLoadOrder", runCatching { JSONObject(provider.stalkerAdvancedOptionsJson)
                .optString("catalogLoadOrder", "PARALLEL") }.getOrDefault("PARALLEL"))
        val db = graph.database()
        if (args.getString("backfill") == "true") {
            // Observe actual worker progress before any interactive hydration or process restart.
            fun maxPage(table: String): Int = db.openHelper.readableDatabase.query(
                "SELECT COALESCE(MAX(last_successful_page), 0) FROM $table WHERE provider_id = ?",
                arrayOf<Any>(provider.id)
            ).use { it.moveToFirst(); it.getInt(0) }
            val initialMovies = db.movieDao().getCount(provider.id).first()
            val initialSeries = db.seriesDao().getCount(provider.id).first()
            val requireGuide = args.getString("parallelGuide") == "true"
            val requireBoth = args.getString("parallelCatalog") == "true"
            val initialGuide = db.programDao().countByProvider(provider.id)
            fun guideMetadataCount(): Int = db.openHelper.readableDatabase.query(
                "SELECT epg_count FROM sync_metadata WHERE provider_id = ?", arrayOf<Any>(provider.id)
            ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
            val backfillStarted = SystemClock.elapsedRealtime()
            var finalMovies = initialMovies
            var finalSeries = initialSeries
            withTimeout(180_000L) {
                while (finalMovies <= initialMovies || maxPage("movie_category_hydration") < 2 ||
                    (requireBoth && (finalSeries <= initialSeries || maxPage("series_category_hydration") < 2)) ||
                    (requireGuide && (db.programDao().countByProvider(provider.id) <= initialGuide || guideMetadataCount() == 0))) {
                    kotlinx.coroutines.delay(2_000)
                    finalMovies = db.movieDao().getCount(provider.id).first()
                    finalSeries = db.seriesDao().getCount(provider.id).first()
                }
            }
            report.put("backfillMs", SystemClock.elapsedRealtime() - backfillStarted)
                .put("initialMovies", initialMovies).put("initialSeries", initialSeries)
                .put("backfillMovies", finalMovies).put("backfillSeries", finalSeries)
                .put("movieMaxPage", maxPage("movie_category_hydration"))
                .put("seriesMaxPage", maxPage("series_category_hydration"))
                .put("initialGuide", initialGuide).put("guidePrograms", db.programDao().countByProvider(provider.id))
            if (requireGuide) {
                val catalogWork = androidx.work.WorkManager.getInstance(app)
                    .getWorkInfosForUniqueWork("provider-stalker-index-${provider.id}").get()
                check(catalogWork.any { it.state == androidx.work.WorkInfo.State.RUNNING })
                report.put("catalogRunningWhenGuidePopulated", true)
                    .put("bothCatalogsAdvanced", finalMovies > initialMovies && finalSeries > initialSeries)
                val before = finalMovies
                withTimeout(30_000) {
                    while (db.movieDao().getCount(provider.id).first() <= before) kotlinx.coroutines.delay(1_000)
                }
                report.put("moviesAfterGuide", db.movieDao().getCount(provider.id).first())
                    .put("guideMetadataCount", guideMetadataCount())
                check(guideMetadataCount() > 0)
            }
            app.filesDir.resolve("stalker-backfill-report.json").writeText(report.toString(2))
        }
        val types = if (provider.catalogLayout == CatalogLayout.UNIFIED_VOD) listOf(ContentType.VOD)
            else listOf(ContentType.MOVIE, ContentType.SERIES)
        val samples = JSONArray()
        for (type in types) {
            val categories = db.categoryDao().getByProviderAndTypeSync(provider.id, type.name)
            report.put("${type.name.lowercase()}Categories", categories.size)
            for (category in categories.filter { it.categoryId > 0 && !it.name.startsWith("All", ignoreCase = true) }.take(3)) {
                val openStarted = SystemClock.elapsedRealtime()
                val count = withTimeoutOrNull(15_000L) {
                    when (type) {
                        ContentType.VOD -> vod.requestCategoryHydration(provider.id, category.categoryId, VodCategoryHydrationRequest.OPEN)
                        ContentType.MOVIE -> movies.requestCategoryHydration(provider.id, category.categoryId, VodCategoryHydrationRequest.OPEN)
                        else -> series.requestCategoryHydration(provider.id, category.categoryId, VodCategoryHydrationRequest.OPEN)
                    }
                    when (type) {
                        ContentType.VOD -> vod.getCategoryPreview(provider.id, category.categoryId, 30).first().size
                        ContentType.MOVIE -> movies.browseMovies(LibraryBrowseQuery(provider.id, categoryId = category.categoryId, limit = 30)).first().items.size
                        else -> series.browseSeries(LibraryBrowseQuery(provider.id, categoryId = category.categoryId, limit = 30)).first().items.size
                    }
                }
                samples.put(JSONObject().put("type", type.name).put("categoryId", category.categoryId)
                    .put("category", category.name).put("items", count ?: -1).put("timedOut", count == null)
                    .put("openMs", SystemClock.elapsedRealtime() - openStarted))
            }
        }
        report.put("samples", samples)
            .put("movies", db.movieDao().getCount(provider.id).first())
            .put("series", db.seriesDao().getCount(provider.id).first())
        val firstSeries = series.browseSeries(LibraryBrowseQuery(provider.id, limit = 1)).first().items.firstOrNull()
        if (firstSeries != null) {
            val detailStarted = SystemClock.elapsedRealtime()
            val detail = withTimeoutOrNull(30_000L) { series.getSeriesDetails(provider.id, firstSeries.id) }
            val loaded = (detail as? com.streamvault.domain.model.Result.Success)?.data
            report.put("seriesDetailMs", SystemClock.elapsedRealtime() - detailStarted)
                .put("seasons", loaded?.seasons?.size ?: -1)
                .put("episodes", loaded?.seasons?.sumOf { it.episodes.size } ?: -1)
            loaded?.seasons?.firstNotNullOfOrNull { it.episodes.firstOrNull() }?.let { episode ->
                val linkStarted = SystemClock.elapsedRealtime()
                val link = withTimeoutOrNull(30_000L) { series.getEpisodeStreamInfo(episode) }
                report.put("episodeLinkMs", SystemClock.elapsedRealtime() - linkStarted)
                    .put("episodeLinkResolved", link is com.streamvault.domain.model.Result.Success)
            }
        }
        app.filesDir.resolve("stalker-catalog-report.json").writeText(report.toString(2))
        Log.i("StalkerCatalogProbe", report.toString())
        if (args.getString("cleanup") == "true") {
            graph.sync().cancelStalkerIndexSync(provider.id)
            graph.providers().deleteProvider(provider.id)
        }
        check(samples.length() > 0)
        check((0 until samples.length()).all { samples.getJSONObject(it).getInt("items") > 0 })
        check(report.getInt("movies") + report.getInt("series") > 0)
        if (firstSeries != null) {
            check(report.optInt("episodes", -1) > 0)
            check(report.optBoolean("episodeLinkResolved"))
        }
        if (args.getString("screens") == "true") {
            val device = androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            androidx.test.core.app.ActivityScenario.launch<com.streamvault.app.MainActivity>(
                android.content.Intent(app, com.streamvault.app.MainActivity::class.java)
            ).use {
                for ((label, route) in listOf("Movies" to "movies", "Series" to "series")) {
                    val tab = requireNotNull(device.wait(androidx.test.uiautomator.Until.findObject(
                        androidx.test.uiautomator.By.desc(label)), 15_000))
                    val center = tab.visibleCenter
                    device.executeShellCommand("input mouse tap ${center.x} ${center.y}")
                    check(device.wait(androidx.test.uiautomator.Until.hasObject(
                        androidx.test.uiautomator.By.descStartsWith("streamvault.destination:$route")), 15_000))
                    val library = requireNotNull(device.wait(androidx.test.uiautomator.Until.findObject(
                        androidx.test.uiautomator.By.text("Browse Full ${if (route == "movies") "Movie" else "Series"} Library")), 15_000))
                    val button = library.visibleCenter
                    device.executeShellCommand("input mouse tap ${button.x} ${button.y}")
                    SystemClock.sleep(1_000)
                    check(device.currentPackageName == app.packageName)
                    check(device.takeScreenshot(app.filesDir.resolve("stalker-$route-ui.png")))
                    device.dumpWindowHierarchy(app.filesDir.resolve("stalker-$route-ui.xml"))
                }
            }
        }
    }
}
