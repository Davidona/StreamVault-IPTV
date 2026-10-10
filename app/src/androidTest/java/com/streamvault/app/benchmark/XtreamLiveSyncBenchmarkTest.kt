package com.streamvault.app.benchmark

import android.app.ActivityManager
import android.app.Application
import android.os.Build
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.streamvault.app.MainActivity
import com.streamvault.data.local.dao.CategoryDao
import com.streamvault.data.local.dao.ChannelDao
import com.streamvault.data.remote.xtream.XtreamStreamKind
import com.streamvault.data.remote.xtream.XtreamUrlFactory
import com.streamvault.data.sync.ProviderSyncWorker
import com.streamvault.data.sync.SyncRepairSection
import com.streamvault.data.local.entity.PlaybackHistoryEntity
import com.streamvault.domain.model.AppLandingDestination
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.ProviderEpgSyncMode
import com.streamvault.domain.model.ProviderXtreamLiveSyncMode
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.SyncMetadata
import com.streamvault.domain.usecase.ValidateAndAddProviderResult
import com.streamvault.domain.usecase.XtreamProviderSetupCommand
import com.streamvault.domain.util.ChannelNormalizer
import com.streamvault.domain.util.ProviderInputSanitizer
import dagger.hilt.android.EntryPointAccessors
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the real setup and Settings-live engines against a synthetic local fixture.
 * Explicitly opt-in; never uses/deletes an existing provider or clears app data.
 * Backend timings do not claim first-screen readiness or playback validation.
 */
@RunWith(AndroidJUnit4::class)
class XtreamLiveSyncBenchmarkTest {
    @Test
    fun measureInitialAndRefresh() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("xtreamSyncBenchmark") == "true")
        val app = ApplicationProvider.getApplicationContext<Application>()
        check(app.packageName == "com.streamvault.app.syncbenchmark") {
            "Use the isolated APK built with -PxtreamSyncBenchmark=true"
        }
        val endpoint = requireNotNull(args.getString("fixtureUrl"))
        val uri = URI(endpoint)
        require(uri.scheme == "http" && uri.host in setOf("127.0.0.1", "localhost", "10.0.2.2") &&
            uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            uri.path in setOf("", "/")) { "Only the local synthetic fixture is supported" }
        val fixture = withContext(Dispatchers.IO) {
            OkHttpClient().newCall(Request.Builder().url(endpoint.trimEnd('/') + "/fixture").build())
                .execute().use { response ->
                    check(response.isSuccessful)
                    JSONObject(requireNotNull(response.body).string())
                }
        }
        check(fixture.getString("fixture") == "streamvault-xtream-sync-performance-v1")
        val expected = fixture.getInt("expected_channels")
        val fixtureScenario = fixture.getJSONObject("scenario")
        val fixtureSourceChannelCount = fixtureScenario.getInt("channels")
        val fixtureCategoryCount = fixtureScenario.getInt("categories")
        val fixtureSeed = fixtureScenario.getInt("seed")
        val fixtureMalformedEvery = fixtureScenario.optInt("malformed_every", 0)
        val expectedMovies = fixture.optInt("expected_movies", 0)
        val expectedSeries = fixture.optInt("expected_series", 0)
        val modes = (args.getString("syncModes") ?: "AUTO,STREAM_ALL,CATEGORY_BY_CATEGORY")
            .split(',').map { ProviderXtreamLiveSyncMode.valueOf(it) }
        val iterations = (args.getString("iterations") ?: "5").toInt().also { require(it in 1..20) }
        val warmups = (args.getString("warmups") ?: "1").toInt().also { require(it in 0..3) }
        val measureRefresh = args.getString("measureRefresh") == "true"
        val measureSummaryRefresh = args.getString("measureSummaryRefresh") == "true"
        val measureFirstBrowsableScreen = args.getString("measureFirstBrowsableScreen") == "true"
        val measureBackgroundWorker = args.getString("measureBackgroundWorker") == "true"
        val verifyChannelFields = args.getString("verifyChannelFields") == "true"
        val settleSeconds = (args.getString("settleSeconds") ?: "0").toInt().also { require(it in 0..120) }
        val runtimeTier = args.getString("runtimeTier") ?: "DEVICE"
        val stageBatchSize = args.getString("stageBatchSize")?.toIntOrNull()
        val experimentalAutoBulk = args.getString("experimentalAutoBulk") == "true"
        val hiddenCategoryPercent = (args.getString("hiddenCategoryPercent") ?: "0").toInt()
        val rememberSequentialStress = args.getString("rememberSequentialStress") == "true"
        val avoidFullCooldownSeconds = (args.getString("avoidFullCooldownSeconds") ?: "0").toInt()
        require(runtimeTier in setOf("DEVICE", "LOW", "MID", "HIGH"))
        require(stageBatchSize == null || stageBatchSize in setOf(100, 128, 256, 300, 500, 512, 1024))
        require(hiddenCategoryPercent in 0..100 && avoidFullCooldownSeconds in 0..86_400)
        val profileOverrides = app.getSharedPreferences("xtream_sync_benchmark_profile", 0)
        profileOverrides.edit().clear().commit()
        val graph = EntryPointAccessors.fromApplication(app, XtreamSyncBenchmarkEntryPoint::class.java)
        args.getString("cleanupRun")?.let { run ->
            require(run.matches(Regex("bench_[a-zA-Z0-9_]{1,100}")))
            val interrupted = graph.providers().getProviders().first().single { it.username == run }
            check(interrupted.serverUrl.trimEnd('/') == endpoint.trimEnd('/') &&
                interrupted.name == benchmarkName(run))
            graph.preferences().setHiddenCategoryIds(interrupted.id, ContentType.LIVE, emptySet())
            check(graph.providers().deleteProvider(interrupted.id) is Result.Success)
            check(graph.providers().getProvider(interrupted.id) == null)
        }
        check(graph.database().providerDao().getAllSync().isEmpty()) {
            "Benchmark storage must contain no providers; resolve an interrupted benchmark first"
        }
        val previousLandingDestination = if (measureFirstBrowsableScreen) {
            graph.preferences().appLandingDestination.first().also {
                graph.preferences().setAppLandingDestination(AppLandingDestination.LIVE_TV)
            }
        } else null
        val previousShowFavoritesCategory = if (measureFirstBrowsableScreen) {
            graph.preferences().showFavoritesCategory.first().also {
                graph.preferences().setShowFavoritesCategory(false)
            }
        } else null
        val previousShowRecentChannelsCategory = if (measureFirstBrowsableScreen) {
            graph.preferences().showRecentChannelsCategory.first().also {
                graph.preferences().setShowRecentChannelsCategory(false)
            }
        } else null
        val manager = app.getSystemService(ActivityManager::class.java)
        Log.i(TAG, JSONObject()
            .put("event", "environment").put("package", app.packageName)
            .put("sdk", Build.VERSION.SDK_INT).put("model", Build.MODEL)
            .put("memory_class_mb", manager.memoryClass).put("low_ram", manager.isLowRamDevice)
            .put("max_heap_bytes", Runtime.getRuntime().maxMemory())
            .put("payload_sha256", fixture.getString("payload_sha256"))
            .put("expected_channels", expected)
            .put("measure_first_browsable_screen", measureFirstBrowsableScreen)
            .put("measure_background_worker", measureBackgroundWorker)
            .put("verify_channel_fields", verifyChannelFields)
            .put("measure_summary_refresh", measureSummaryRefresh)
            .put("hide_empty_virtual_categories_for_browse", measureFirstBrowsableScreen)
            .put("runtime_tier_override", runtimeTier)
            .put("stage_batch_size_override", stageBatchSize ?: JSONObject.NULL)
            .put("experimental_auto_bulk", experimentalAutoBulk)
            .put("hidden_category_percent", hiddenCategoryPercent)
            .put("remember_sequential_stress", rememberSequentialStress)
            .put("avoid_full_cooldown_seconds", avoidFullCooldownSeconds).toString())
        try {
            for (mode in modes) {
                repeat(iterations + warmups) { iteration ->
                profileOverrides.edit().clear().also { editor ->
                    if (runtimeTier != "DEVICE") editor.putString("tier", runtimeTier)
                    if (stageBatchSize != null) editor.putInt("batch_size", stageBatchSize)
                    editor.putBoolean("experimental_auto_bulk", experimentalAutoBulk)
                }.commit()
                val run = "bench_" + mode.name + "_" + UUID.randomUUID().toString().replace("-", "")
                val name = benchmarkName(run)
                val progress = mutableListOf<JSONObject>()
                var start = 0L
                var firstBrowsableScreenElapsedMs: Double? = null
                var firstBrowsableScreenWaiter: kotlinx.coroutines.Deferred<Long>? = null
                var activityScenario: ActivityScenario<MainActivity>? = null
                var providerId: Long? = null
                try {
                    if (measureFirstBrowsableScreen) {
                        activityScenario = ActivityScenario.launch(MainActivity::class.java)
                        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
                        val welcomeReady = device.wait(
                            Until.hasObject(By.text(app.getString(com.streamvault.feature.system.R.string.welcome_setup_provider))),
                            120_000L
                        )
                        check(welcomeReady) { "Synthetic first-screen run did not reach the empty-provider Welcome screen" }
                    }
                    start = SystemClock.elapsedRealtimeNanos()
                    if (measureFirstBrowsableScreen) {
                        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
                        firstBrowsableScreenWaiter = async(Dispatchers.IO) {
                            val selector = By.descContains(FIRST_LIVE_CHANNEL_NAME)
                            val deadline = SystemClock.elapsedRealtime() + 600_000L
                            while (SystemClock.elapsedRealtime() < deadline) {
                                currentCoroutineContext().ensureActive()
                                if (device.wait(Until.hasObject(selector), 100L)) break
                                delay(25L)
                            }
                            check(device.hasObject(selector)) {
                                "First synthetic live channel row did not become accessible"
                            }
                            SystemClock.elapsedRealtimeNanos()
                        }
                    }
                    val sample = sampleHeap {
                        withTimeout(600_000L) {
                            graph.setup().loginXtream(
                                XtreamProviderSetupCommand(
                                    serverUrl = endpoint, username = run, password = "synthetic",
                                    name = name, epgSyncMode = ProviderEpgSyncMode.SKIP,
                                    xtreamLiveSyncMode = mode
                                ),
                                onProgress = { label ->
                                    // Retain milestones, not every category label.
                                    val phase = when (label) {
                                        "Loading Live TV channels..." -> "live-start"
                                        "Saving Live TV channels..." -> "commit-start"
                                        "Live TV ready." -> "live-committed"
                                        "Loading Movies categories..." -> "live-committed"
                                        else -> null
                                    }
                                    if (phase != null && progress.none { it.optString("phase") == phase }) {
                                        progress += JSONObject().put("phase", phase)
                                            .put("elapsed_ms", (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0)
                                    }
                                }
                            )
                        }
                    }
                    val elapsed = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
                    val result = sample.value
                    val provider = when (result) {
                        is ValidateAndAddProviderResult.Success -> result.provider
                        is ValidateAndAddProviderResult.SavedWithWarning -> result.provider
                        else -> error("Setup failed: " + result.javaClass.simpleName)
                    }
                    providerId = provider.id
                    val count = graph.database().channelDao().getCount(provider.id).first()
                    check(count == expected) { "Incomplete catalog: " + count + "/" + expected }
                    var fixtureRowsChecked: Int? = null
                    var fixtureCategoriesChecked: Int? = null
                    var fixtureRowFieldVerificationMs: Double? = null
                    if (verifyChannelFields) {
                        val verificationStarted = SystemClock.elapsedRealtimeNanos()
                        val verification = verifyFixtureChannelRows(
                            channelDao = graph.database().channelDao(),
                            categoryDao = graph.database().categoryDao(),
                            providerId = provider.id,
                            sourceChannelCount = fixtureSourceChannelCount,
                            categoryCount = fixtureCategoryCount,
                            seed = fixtureSeed,
                            malformedEvery = fixtureMalformedEvery,
                            expectedRows = expected
                        )
                        fixtureRowsChecked = verification.channelRows
                        fixtureCategoriesChecked = verification.categories
                        fixtureRowFieldVerificationMs =
                            (SystemClock.elapsedRealtimeNanos() - verificationStarted) / 1_000_000.0
                    }
                    firstBrowsableScreenWaiter?.await()?.let { visibleAtNanos ->
                        firstBrowsableScreenElapsedMs = (visibleAtNanos - start) / 1_000_000.0
                    }
                    var movieCount = 0
                    var seriesCount = 0
                    var movieIndexElapsedMs: Double? = null
                    var movieAfterLiveElapsedMs: Double? = null
                    var seriesIndexElapsedMs: Double? = null
                    var seriesAfterLiveElapsedMs: Double? = null
                    var movieIndexState = "NOT_REQUESTED"
                    var seriesIndexState = "NOT_REQUESTED"
                    var summaryPeakHeapBytes: Long? = null
                    var summaryGcCountDelta: Long? = null
                    var unchangedMovieRefreshElapsedMs: Double? = null
                    var unchangedSeriesRefreshElapsedMs: Double? = null
                    var unchangedVodRefreshElapsedMs: Double? = null
                    var unchangedSummaryPeakHeapBytes: Long? = null
                    var unchangedSummaryGcCountDelta: Long? = null
                    val expectedIndexCounts = listOf(
                        ContentType.MOVIE to expectedMovies,
                        ContentType.SERIES to expectedSeries
                    ).filter { (_, expectedCount) -> expectedCount > 0 }
                    if (expectedIndexCounts.isNotEmpty()) {
                        val indexSample = sampleHeap {
                            withTimeout(600_000L) {
                                graph.database().xtreamIndexJobDao().observeForProvider(provider.id).first { jobs ->
                                    expectedIndexCounts.all { (section, _) ->
                                        jobs.any { job ->
                                            job.section == section.name &&
                                                (job.state == "SUCCESS" || job.state.startsWith("FAILED"))
                                        }
                                    }
                                }
                            }
                        }
                        val terminalJobs = indexSample.value
                        summaryPeakHeapBytes = indexSample.peakHeapBytes
                        summaryGcCountDelta = indexSample.gcCountDelta
                        val liveCommittedMs = progress.firstOrNull {
                            it.optString("phase") == "live-committed"
                        }?.optDouble("elapsed_ms", 0.0) ?: 0.0
                        val movieJob = terminalJobs.firstOrNull { it.section == ContentType.MOVIE.name }
                        if (expectedMovies > 0) {
                            movieIndexState = movieJob?.state ?: "MISSING"
                            check(movieIndexState == "SUCCESS") { "Movie summary index ended in $movieIndexState" }
                            movieCount = graph.database().movieDao().countByProvider(provider.id)
                            check(movieCount == expectedMovies) { "Incomplete movie catalog: $movieCount/$expectedMovies" }
                            val movieIndexRows = graph.database().xtreamContentIndexDao()
                                .countActiveRows(provider.id, ContentType.MOVIE.name)
                            check(movieIndexRows == expectedMovies) { "Incomplete movie index: $movieIndexRows/$expectedMovies" }
                            movieIndexElapsedMs = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
                            movieAfterLiveElapsedMs = (movieIndexElapsedMs - liveCommittedMs).coerceAtLeast(0.0)
                        }
                        val seriesJob = terminalJobs.firstOrNull { it.section == ContentType.SERIES.name }
                        if (expectedSeries > 0) {
                            seriesIndexState = seriesJob?.state ?: "MISSING"
                            check(seriesIndexState == "SUCCESS") { "Series summary index ended in $seriesIndexState" }
                            seriesCount = graph.database().seriesDao().countByProvider(provider.id)
                            check(seriesCount == expectedSeries) { "Incomplete series catalog: $seriesCount/$expectedSeries" }
                            val seriesIndexRows = graph.database().xtreamContentIndexDao()
                                .countActiveRows(provider.id, ContentType.SERIES.name)
                            check(seriesIndexRows == expectedSeries) { "Incomplete series index: $seriesIndexRows/$expectedSeries" }
                            seriesIndexElapsedMs = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
                            seriesAfterLiveElapsedMs = (seriesIndexElapsedMs - liveCommittedMs).coerceAtLeast(0.0)
                        }
                    }
                    if (measureSummaryRefresh) {
                        check(expectedMovies > 0 && expectedSeries > 0) {
                            "Repeated VOD refresh requires non-empty movie and series fixtures"
                        }
                        val refreshSample = sampleHeap {
                            withTimeout(600_000L) {
                                val movieStart = SystemClock.elapsedRealtimeNanos()
                                check(graph.sync().processQueuedXtreamIndexJobs(
                                    providerId = provider.id,
                                    section = ContentType.MOVIE,
                                    force = true
                                ) is Result.Success) { "Unchanged movie summary refresh failed" }
                                val movieElapsed = (SystemClock.elapsedRealtimeNanos() - movieStart) / 1_000_000.0

                                val seriesStart = SystemClock.elapsedRealtimeNanos()
                                check(graph.sync().processQueuedXtreamIndexJobs(
                                    providerId = provider.id,
                                    section = ContentType.SERIES,
                                    force = true
                                ) is Result.Success) { "Unchanged series summary refresh failed" }
                                val seriesElapsed = (SystemClock.elapsedRealtimeNanos() - seriesStart) / 1_000_000.0
                                movieElapsed to seriesElapsed
                            }
                        }
                        unchangedMovieRefreshElapsedMs = refreshSample.value.first
                        unchangedSeriesRefreshElapsedMs = refreshSample.value.second
                        unchangedVodRefreshElapsedMs = refreshSample.value.first + refreshSample.value.second
                        unchangedSummaryPeakHeapBytes = refreshSample.peakHeapBytes
                        unchangedSummaryGcCountDelta = refreshSample.gcCountDelta
                        check(graph.database().movieDao().countByProvider(provider.id) == expectedMovies)
                        check(graph.database().seriesDao().countByProvider(provider.id) == expectedSeries)
                        check(graph.database().xtreamContentIndexDao()
                            .countActiveRows(provider.id, ContentType.MOVIE.name) == expectedMovies)
                        check(graph.database().xtreamContentIndexDao()
                            .countActiveRows(provider.id, ContentType.SERIES.name) == expectedSeries)
                    }
                    val roomPreservationVerified = iteration < warmups && expectedMovies > 0 && expectedSeries > 0
                    if (roomPreservationVerified) {
                        verifySummaryRoomPreservation(graph, provider.id)
                    }
                    val state = graph.sync().currentSyncState(provider.id).javaClass.simpleName
                    if (settleSeconds > 0) {
                        check(state in setOf("Success", "Partial")) { "Nonterminal setup state: $state" }
                        repeat(settleSeconds) {
                            delay(1_000L)
                            check(graph.sync().currentSyncState(provider.id).javaClass.simpleName == state) {
                                "Setup state changed after completion"
                            }
                            check(graph.database().channelDao().getCount(provider.id).first() == expected) {
                                "Committed catalog changed after completion"
                            }
                        }
                    }
                    val backgroundWorkerSample = if (measureBackgroundWorker) {
                        measureAutomaticProviderSyncWorker(app, graph, provider.id, expected)
                    } else null
                    val sampleRecord = JSONObject().put("event", "sample").put("run", run)
                        .put("provider_id", provider.id).put("mode", mode.name).put("phase", "initial")
                        .put("warmup", iteration < warmups).put("elapsed_ms", elapsed)
                        .put("channels", count).put("sync_state", state)
                        .put("settle_seconds", settleSeconds)
                        .put("movies", movieCount).put("series", seriesCount)
                        .put("movie_index_state", movieIndexState).put("series_index_state", seriesIndexState)
                        .put("peak_java_heap_bytes", sample.peakHeapBytes)
                        .put("gc_count_delta", sample.gcCountDelta ?: JSONObject.NULL)
                        .put("runtime_tier_override", runtimeTier)
                        .put("stage_batch_size_override", stageBatchSize ?: JSONObject.NULL)
                        .put("experimental_auto_bulk", experimentalAutoBulk)
                        .put("measure_summary_refresh", measureSummaryRefresh)
                        .put("room_preservation_verified", roomPreservationVerified)
                        .put("fixture_row_fields_verified", verifyChannelFields)
                        .put("milestones", org.json.JSONArray(progress))
                    fixtureRowsChecked?.let { sampleRecord.put("fixture_rows_checked", it) }
                    fixtureCategoriesChecked?.let { sampleRecord.put("fixture_categories_checked", it) }
                    fixtureRowFieldVerificationMs?.let {
                        sampleRecord.put("fixture_row_field_verification_ms", it)
                    }
                    backgroundWorkerSample?.let {
                        sampleRecord.put("background_worker_state", it.state)
                            .put("background_worker_elapsed_ms", it.elapsedMs)
                            .put("background_worker_channels", it.channels)
                            .put("background_worker_last_live_success", it.lastLiveSuccess)
                    }
                    firstBrowsableScreenElapsedMs?.let {
                        sampleRecord.put("first_browsable_screen_elapsed_ms", it)
                    }
                    summaryPeakHeapBytes?.let { sampleRecord.put("summary_peak_java_heap_bytes", it) }
                    summaryGcCountDelta?.let { sampleRecord.put("summary_gc_count_delta", it) }
                    unchangedMovieRefreshElapsedMs?.let {
                        sampleRecord.put("unchanged_movie_refresh_elapsed_ms", it)
                    }
                    unchangedSeriesRefreshElapsedMs?.let {
                        sampleRecord.put("unchanged_series_refresh_elapsed_ms", it)
                    }
                    unchangedVodRefreshElapsedMs?.let {
                        sampleRecord.put("unchanged_vod_refresh_elapsed_ms", it)
                    }
                    unchangedSummaryPeakHeapBytes?.let {
                        sampleRecord.put("unchanged_summary_peak_java_heap_bytes", it)
                    }
                    unchangedSummaryGcCountDelta?.let {
                        sampleRecord.put("unchanged_summary_gc_count_delta", it)
                    }
                    movieIndexElapsedMs?.let { sampleRecord.put("movie_index_elapsed_ms", it) }
                    movieAfterLiveElapsedMs?.let { sampleRecord.put("movie_after_live_elapsed_ms", it) }
                    seriesIndexElapsedMs?.let { sampleRecord.put("series_index_elapsed_ms", it) }
                    seriesAfterLiveElapsedMs?.let { sampleRecord.put("series_after_live_elapsed_ms", it) }
                    Log.i(TAG, sampleRecord.toString())
                    if (measureRefresh) {
                        if (hiddenCategoryPercent > 0) {
                            val categories = graph.database().categoryDao()
                                .getByProviderAndTypeSync(provider.id, ContentType.LIVE.name)
                                .sortedWith(compareBy({ it.providerOrder }, { it.categoryId }))
                            check(categories.isNotEmpty()) { "Fixture returned no live categories" }
                            val hiddenCount = ((categories.size * hiddenCategoryPercent) + 99) / 100
                            val hiddenIds = categories.take(hiddenCount).map { it.categoryId }.toSet()
                            graph.preferences().setHiddenCategoryIds(provider.id, ContentType.LIVE, hiddenIds)
                            check(hiddenIds.size == hiddenCount)
                        }
                        if (rememberSequentialStress || avoidFullCooldownSeconds > 0) {
                            val current = graph.syncMetadata().getMetadata(provider.id) ?: SyncMetadata(provider.id)
                            graph.syncMetadata().updateMetadata(
                                current.copy(
                                    liveSequentialFailuresRemembered = current.liveSequentialFailuresRemembered || rememberSequentialStress,
                                    liveHealthySyncStreak = if (rememberSequentialStress) 0 else current.liveHealthySyncStreak,
                                    liveAvoidFullUntil = if (avoidFullCooldownSeconds > 0) {
                                        System.currentTimeMillis() + avoidFullCooldownSeconds * 1_000L
                                    } else current.liveAvoidFullUntil
                                )
                            )
                        }
                        val refreshStart = SystemClock.elapsedRealtimeNanos()
                        val refresh = sampleHeap {
                            withTimeout(600_000L) {
                                graph.sync().retrySection(provider.id, SyncRepairSection.LIVE)
                            }
                        }
                        check(refresh.value is Result.Success)
                        val refreshed = graph.database().channelDao().getCount(provider.id).first()
                        check(refreshed == expected)
                        val refreshElapsedMs =
                            (SystemClock.elapsedRealtimeNanos() - refreshStart) / 1_000_000.0
                        var refreshFixtureRowsChecked: Int? = null
                        var refreshFixtureCategoriesChecked: Int? = null
                        var refreshFixtureFieldVerificationMs: Double? = null
                        if (verifyChannelFields) {
                            val verificationStarted = SystemClock.elapsedRealtimeNanos()
                            val verification = verifyFixtureChannelRows(
                                channelDao = graph.database().channelDao(),
                                categoryDao = graph.database().categoryDao(),
                                providerId = provider.id,
                                sourceChannelCount = fixtureSourceChannelCount,
                                categoryCount = fixtureCategoryCount,
                                seed = fixtureSeed,
                                malformedEvery = fixtureMalformedEvery,
                                expectedRows = expected
                            )
                            refreshFixtureRowsChecked = verification.channelRows
                            refreshFixtureCategoriesChecked = verification.categories
                            refreshFixtureFieldVerificationMs =
                                (SystemClock.elapsedRealtimeNanos() - verificationStarted) / 1_000_000.0
                        }
                        val refreshSampleRecord = JSONObject().put("event", "sample").put("run", run)
                            .put("provider_id", provider.id).put("mode", mode.name).put("phase", "refresh")
                            .put("warmup", iteration < warmups)
                            .put("elapsed_ms", refreshElapsedMs)
                            .put("channels", refreshed).put("peak_java_heap_bytes", refresh.peakHeapBytes)
                            .put("gc_count_delta", refresh.gcCountDelta ?: JSONObject.NULL)
                            .put("hidden_category_percent", hiddenCategoryPercent)
                            .put("remember_sequential_stress", rememberSequentialStress)
                            .put("avoid_full_cooldown_seconds", avoidFullCooldownSeconds)
                            .put("fixture_row_fields_verified", verifyChannelFields)
                        refreshFixtureRowsChecked?.let {
                            refreshSampleRecord.put("fixture_rows_checked", it)
                        }
                        refreshFixtureCategoriesChecked?.let {
                            refreshSampleRecord.put("fixture_categories_checked", it)
                        }
                        refreshFixtureFieldVerificationMs?.let {
                            refreshSampleRecord.put("fixture_row_field_verification_ms", it)
                        }
                        Log.i(TAG, refreshSampleRecord.toString())
                    }
                } finally {
                    withContext(NonCancellable) {
                        firstBrowsableScreenWaiter?.cancelAndJoin()
                        activityScenario?.close()
                        profileOverrides.edit().clear().commit()
                        // Setup can save a provider before returning an error. Identify only
                        // this exact synthetic run; never clean up unrelated provider rows.
                        val saved = providerId?.let { graph.providers().getProvider(it) }
                            ?: graph.providers().getProviders().first().singleOrNull { it.name == name }
                        if (saved != null) {
                            check(saved.name == name)
                            graph.preferences().setHiddenCategoryIds(saved.id, ContentType.LIVE, emptySet())
                            check(graph.providers().deleteProvider(saved.id) is Result.Success)
                            check(graph.providers().getProvider(saved.id) == null)
                        }
                    }
                }
                }
            }
        } finally {
            profileOverrides.edit().clear().commit()
            previousLandingDestination?.let { graph.preferences().setAppLandingDestination(it) }
            previousShowFavoritesCategory?.let { graph.preferences().setShowFavoritesCategory(it) }
            previousShowRecentChannelsCategory?.let { graph.preferences().setShowRecentChannelsCategory(it) }
        }
        Log.i(TAG, JSONObject().put("event", "complete").toString())
    }

    private data class HeapSample<T>(val value: T, val peakHeapBytes: Long, val gcCountDelta: Long?)
    private data class BackgroundWorkerSample(
        val state: String,
        val elapsedMs: Double,
        val channels: Int,
        val lastLiveSuccess: Long
    )

    private suspend fun verifyFixtureChannelRows(
        channelDao: ChannelDao,
        categoryDao: CategoryDao,
        providerId: Long,
        sourceChannelCount: Int,
        categoryCount: Int,
        seed: Int,
        malformedEvery: Int,
        expectedRows: Int
    ): FixtureFieldVerification {
        require(sourceChannelCount > 0 && categoryCount > 0)
        require(malformedEvery >= 0)
        val pageSize = 1_024
        var sourceNumber = 1
        var offset = 0
        var checkedRows = 0

        fun nextValidSourceNumber(): Int? {
            while (sourceNumber <= sourceChannelCount && malformedEvery > 0 &&
                sourceNumber % malformedEvery == 0
            ) {
                sourceNumber++
            }
            return sourceNumber.takeIf { it <= sourceChannelCount }?.also { sourceNumber++ }
        }

        while (true) {
            val page = channelDao.getByProviderPage(providerId, pageSize, offset).first()
            if (page.isEmpty()) break
            for (channel in page) {
                val number = nextValidSourceNumber()
                    ?: error("Unexpected extra fixture channel at persisted row $checkedRows")
                val expectedName = "Benchmark Channel ${number.toString().padStart(6, '0')} HD"
                val expectedStreamId = 100_000L + number
                val expectedCategoryId = (number - 1) % categoryCount + 1
                // The live row omits category_name, so the row mapper stores its documented fallback.
                val expectedRowCategoryName = "Category $expectedCategoryId"
                val expectedEpgId = "benchmark-$seed-$number"
                val expectedCatchUp = number % 7 == 0

                assertFixtureField(number, "number", number, channel.number)
                assertFixtureField(number, "stream_id", expectedStreamId, channel.streamId)
                assertFixtureField(number, "name", expectedName, channel.name)
                assertFixtureField(number, "logo_url", null, channel.logoUrl)
                assertFixtureField(number, "group_title", null, channel.groupTitle)
                assertFixtureField(number, "category_id", expectedCategoryId.toLong(), channel.categoryId)
                assertFixtureField(number, "category_name", expectedRowCategoryName, channel.categoryName)
                assertFixtureField(number, "epg_channel_id", expectedEpgId, channel.epgChannelId)
                assertFixtureField(number, "catch_up_supported", expectedCatchUp, channel.catchUpSupported)
                assertFixtureField(number, "catch_up_days", if (expectedCatchUp) 3 else 0, channel.catchUpDays)
                assertFixtureField(number, "catch_up_source", null, channel.catchUpSource)
                assertFixtureField(number, "provider_id", providerId, channel.providerId)
                assertFixtureField(number, "is_adult", false, channel.isAdult)
                assertFixtureField(number, "is_user_protected", false, channel.isUserProtected)
                assertFixtureField(number, "logical_group_id",
                    ChannelNormalizer.getLogicalGroupId(expectedName, providerId), channel.logicalGroupId)
                assertFixtureField(number, "error_count", 0, channel.errorCount)
                assertFixtureField(number, "quality_options_json", null, channel.qualityOptionsJson)
                assertFixtureField(number, "sync_fingerprint_present", true, channel.syncFingerprint.isNotBlank())
                assertFixtureField(number, "local_id_present", true, channel.id > 0)

                val streamToken = XtreamUrlFactory.parseInternalStreamUrl(channel.streamUrl)
                check(streamToken != null) { "Fixture mismatch at source row $number for stream_url" }
                assertFixtureField(number, "stream_url_provider_id", providerId, streamToken.providerId)
                assertFixtureField(number, "stream_url_kind", XtreamStreamKind.LIVE, streamToken.kind)
                assertFixtureField(number, "stream_url_stream_id", expectedStreamId, streamToken.streamId)
                assertFixtureField(number, "stream_url_extension", "m3u8", streamToken.containerExtension)
                assertFixtureField(number, "stream_url_direct_source", null, streamToken.directSource)
                checkedRows++
            }
            offset += page.size
        }

        val firstMissingSourceNumber = nextValidSourceNumber()
        check(firstMissingSourceNumber == null) {
            "Missing fixture channel source row $firstMissingSourceNumber after checking $checkedRows rows"
        }
        check(checkedRows == expectedRows) {
            "Fixture row-field verification covered $checkedRows/$expectedRows rows"
        }

        val categories = categoryDao.getByProviderAndTypeSync(providerId, ContentType.LIVE.name)
        check(categories.size == categoryCount) {
            "Fixture category verification found ${categories.size}/$categoryCount categories"
        }
        val categoriesById = categories.associateBy { it.categoryId }
        for (categoryId in 1..categoryCount) {
            val category = categoriesById[categoryId.toLong()]
                ?: error("Missing fixture category $categoryId")
            assertFixtureField(categoryId, "category_provider_id", providerId, category.providerId)
            assertFixtureField(categoryId, "category_name",
                "Benchmark Group ${categoryId.toString().padStart(4, '0')}", category.name)
            assertFixtureField(categoryId, "category_is_adult", false, category.isAdult)
        }
        return FixtureFieldVerification(checkedRows, categories.size)
    }

    private fun assertFixtureField(
        sourceNumber: Int,
        field: String,
        expected: Any?,
        actual: Any?
    ) {
        check(expected == actual) {
            "Fixture mismatch at source row $sourceNumber for $field: expected=$expected actual=$actual"
        }
    }

    private data class FixtureFieldVerification(
        val channelRows: Int,
        val categories: Int
    )

    private suspend fun measureAutomaticProviderSyncWorker(
        app: Application,
        graph: XtreamSyncBenchmarkEntryPoint,
        providerId: Long,
        expectedChannels: Int
    ): BackgroundWorkerSample {
        check(graph.providers().getActiveProvider().first()?.id == providerId) {
            "Automatic worker probe requires the synthetic provider to be active"
        }
        val staleTimestamp = 1L
        val previousMetadata = graph.syncMetadata().getMetadata(providerId) ?: SyncMetadata(providerId)
        graph.syncMetadata().updateMetadata(previousMetadata.copy(lastLiveSuccess = staleTimestamp))

        val workManager = WorkManager.getInstance(app)
        val request = OneTimeWorkRequestBuilder<ProviderSyncWorker>().build()
        val start = SystemClock.elapsedRealtimeNanos()
        withContext(Dispatchers.IO) {
            workManager.enqueue(request).result.get(30, TimeUnit.SECONDS)
        }
        val terminalInfo = withTimeout(600_000L) {
            var terminal: WorkInfo? = null
            while (terminal == null) {
                val info = withContext(Dispatchers.IO) {
                    workManager.getWorkInfoById(request.id).get(30, TimeUnit.SECONDS)
                } ?: error("Automatic provider worker disappeared from WorkManager")
                if (info.state in setOf(WorkInfo.State.SUCCEEDED, WorkInfo.State.FAILED, WorkInfo.State.CANCELLED)) {
                    terminal = info
                } else {
                    delay(250L)
                }
            }
            requireNotNull(terminal)
        }
        check(terminalInfo.state == WorkInfo.State.SUCCEEDED) {
            "Automatic provider worker ended in ${terminalInfo.state}"
        }
        val channels = graph.database().channelDao().getCount(providerId).first()
        check(channels == expectedChannels) { "Background worker changed channel coverage: $channels/$expectedChannels" }
        val lastLiveSuccess = graph.syncMetadata().getMetadata(providerId)?.lastLiveSuccess ?: 0L
        check(lastLiveSuccess > staleTimestamp) { "Background worker did not advance live success metadata" }
        return BackgroundWorkerSample(
            state = terminalInfo.state.name,
            elapsedMs = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0,
            channels = channels,
            lastLiveSuccess = lastLiveSuccess
        )
    }

    private suspend fun verifySummaryRoomPreservation(
        graph: XtreamSyncBenchmarkEntryPoint,
        providerId: Long
    ) {
        val database = graph.database()
        val movie = requireNotNull(database.movieDao().getByStreamId(providerId, FIRST_MOVIE_STREAM_ID))
        val series = requireNotNull(database.seriesDao().getBySeriesId(providerId, FIRST_SERIES_ID))
        val movieHydratedAt = 1_700_100_001L
        val seriesHydratedAt = 1_700_100_002L
        val movieProgress = 123_456L
        val movieLastWatchedAt = 1_700_100_003L

        database.playbackHistoryDao().insertOrUpdate(
            PlaybackHistoryEntity(
                contentId = movie.id,
                contentType = ContentType.MOVIE,
                providerId = providerId,
                resumePositionMs = movieProgress,
                lastWatchedAt = movieLastWatchedAt,
                watchCount = 4
            )
        )
        database.movieDao().update(
            movie.copy(
                name = "Stale benchmark movie title",
                backdropUrl = "https://local.invalid/movie-backdrop",
                plot = "Locally hydrated movie plot",
                cast = "Local movie cast",
                director = "Local movie director",
                releaseDate = "1999-01-02",
                duration = "2h 10m",
                durationSeconds = 7_800,
                year = "1999",
                watchProgress = movieProgress,
                watchCount = 4,
                lastWatchedAt = movieLastWatchedAt,
                isUserProtected = true,
                cacheState = "DETAIL_HYDRATED",
                detailHydratedAt = movieHydratedAt,
                playbackMetadataJson = "{\"source\":\"local\"}"
            )
        )
        database.seriesDao().update(
            series.copy(
                name = "Stale benchmark series title",
                backdropUrl = "https://local.invalid/series-backdrop",
                plot = "Locally hydrated series plot",
                cast = "Local series cast",
                director = "Local series director",
                releaseDate = "1998-03-04",
                episodeRunTime = "52",
                isUserProtected = true,
                cacheState = "DETAIL_HYDRATED",
                detailHydratedAt = seriesHydratedAt
            )
        )

        check(graph.sync().processQueuedXtreamIndexJobs(
            providerId = providerId,
            section = ContentType.MOVIE,
            force = true
        ) is Result.Success) { "Movie summary preservation refresh failed" }
        check(graph.sync().processQueuedXtreamIndexJobs(
            providerId = providerId,
            section = ContentType.SERIES,
            force = true
        ) is Result.Success) { "Series summary preservation refresh failed" }

        val refreshedMovie = requireNotNull(database.movieDao().getByStreamId(providerId, FIRST_MOVIE_STREAM_ID))
        check(refreshedMovie.id == movie.id)
        check(refreshedMovie.name == FIRST_MOVIE_NAME)
        check(refreshedMovie.backdropUrl == "https://local.invalid/movie-backdrop")
        check(refreshedMovie.plot == "Locally hydrated movie plot")
        check(refreshedMovie.cast == "Local movie cast")
        check(refreshedMovie.director == "Local movie director")
        check(refreshedMovie.releaseDate == "1999-01-02")
        check(refreshedMovie.durationSeconds == 7_800)
        check(refreshedMovie.year == "1999")
        check(refreshedMovie.watchProgress == movieProgress)
        check(refreshedMovie.watchCount == 4)
        check(refreshedMovie.lastWatchedAt == movieLastWatchedAt)
        check(refreshedMovie.isUserProtected)
        check(refreshedMovie.cacheState == "DETAIL_HYDRATED")
        check(refreshedMovie.detailHydratedAt == movieHydratedAt)
        check(refreshedMovie.playbackMetadataJson == "{\"source\":\"local\"}")
        check(database.xtreamContentIndexDao()
            .getByRemoteId(providerId, ContentType.MOVIE.name, FIRST_MOVIE_STREAM_ID.toString())
            ?.detailHydratedAt == movieHydratedAt)

        val refreshedSeries = requireNotNull(database.seriesDao().getBySeriesId(providerId, FIRST_SERIES_ID))
        check(refreshedSeries.id == series.id)
        check(refreshedSeries.name == FIRST_SERIES_NAME)
        check(refreshedSeries.backdropUrl == "https://local.invalid/series-backdrop")
        check(refreshedSeries.plot == "Locally hydrated series plot")
        check(refreshedSeries.cast == "Local series cast")
        check(refreshedSeries.director == "Local series director")
        check(refreshedSeries.releaseDate == "1998-03-04")
        check(refreshedSeries.episodeRunTime == "52")
        check(refreshedSeries.isUserProtected)
        check(refreshedSeries.cacheState == "DETAIL_HYDRATED")
        check(refreshedSeries.detailHydratedAt == seriesHydratedAt)
        check(database.xtreamContentIndexDao()
            .getByRemoteId(providerId, ContentType.SERIES.name, FIRST_SERIES_ID.toString())
            ?.detailHydratedAt == seriesHydratedAt)
    }

    private fun benchmarkName(run: String) =
        ProviderInputSanitizer.normalizeProviderName("Xtream sync benchmark " + run)

    private suspend fun <T> sampleHeap(block: suspend () -> T): HeapSample<T> = coroutineScope {
        fun usedHeap() = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
        fun gcCount() = Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull()
        val beforeGc = gcCount()
        val peak = AtomicLong(usedHeap())
        val sampler = async(Dispatchers.Default) {
            while (true) {
                val used = usedHeap()
                peak.updateAndGet { maxOf(it, used) }
                delay(50)
            }
        }
        try {
            val result = block()
            val used = usedHeap()
            peak.updateAndGet { maxOf(it, used) }
            val afterGc = gcCount()
            HeapSample(result, peak.get(), if (beforeGc != null && afterGc != null) afterGc - beforeGc else null)
        } finally {
            sampler.cancel()
            withContext(NonCancellable) { sampler.join() }
        }
    }

    companion object {
        private const val TAG = "XtreamSyncBenchmark"
        private const val FIRST_MOVIE_STREAM_ID = 200_001L
        private const val FIRST_SERIES_ID = 300_001L
        private const val FIRST_MOVIE_NAME = "Benchmark Movie 000001"
        private const val FIRST_SERIES_NAME = "Benchmark Series 000001"
        private const val FIRST_LIVE_CHANNEL_NAME = "Benchmark Channel 000001 HD"
    }
}
