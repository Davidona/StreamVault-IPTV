package com.streamvault.app.benchmark

import android.app.ActivityManager
import android.app.Application
import android.os.Build
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.streamvault.data.sync.SyncRepairSection
import com.streamvault.domain.model.ProviderEpgSyncMode
import com.streamvault.domain.model.ProviderXtreamLiveSyncMode
import com.streamvault.domain.model.Result
import com.streamvault.domain.usecase.ValidateAndAddProviderResult
import com.streamvault.domain.usecase.XtreamProviderSetupCommand
import com.streamvault.domain.util.ProviderInputSanitizer
import dagger.hilt.android.EntryPointAccessors
import java.net.URI
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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
        val modes = (args.getString("syncModes") ?: "AUTO,STREAM_ALL,CATEGORY_BY_CATEGORY")
            .split(',').map { ProviderXtreamLiveSyncMode.valueOf(it) }
        val iterations = (args.getString("iterations") ?: "5").toInt().also { require(it in 1..20) }
        val warmups = (args.getString("warmups") ?: "1").toInt().also { require(it in 0..3) }
        val measureRefresh = args.getString("measureRefresh") == "true"
        val graph = EntryPointAccessors.fromApplication(app, XtreamSyncBenchmarkEntryPoint::class.java)
        args.getString("cleanupRun")?.let { run ->
            require(run.matches(Regex("bench_[a-zA-Z0-9_]{1,100}")))
            val interrupted = graph.providers().getProviders().first().single { it.username == run }
            check(interrupted.serverUrl.trimEnd('/') == endpoint.trimEnd('/') &&
                interrupted.name == benchmarkName(run))
            check(graph.providers().deleteProvider(interrupted.id) is Result.Success)
            check(graph.providers().getProvider(interrupted.id) == null)
        }
        check(graph.database().providerDao().getAllSync().isEmpty()) {
            "Benchmark storage must contain no providers; resolve an interrupted benchmark first"
        }
        val manager = app.getSystemService(ActivityManager::class.java)
        Log.i(TAG, JSONObject()
            .put("event", "environment").put("package", app.packageName)
            .put("sdk", Build.VERSION.SDK_INT).put("model", Build.MODEL)
            .put("memory_class_mb", manager.memoryClass).put("low_ram", manager.isLowRamDevice)
            .put("max_heap_bytes", Runtime.getRuntime().maxMemory())
            .put("payload_sha256", fixture.getString("payload_sha256"))
            .put("expected_channels", expected).toString())
        for (mode in modes) {
            repeat(iterations + warmups) { iteration ->
                val run = "bench_" + mode.name + "_" + UUID.randomUUID().toString().replace("-", "")
                val name = benchmarkName(run)
                val progress = mutableListOf<JSONObject>()
                val start = SystemClock.elapsedRealtimeNanos()
                var providerId: Long? = null
                try {
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
                                        "Downloading Live TV..." -> "live-start"
                                        "Saving Live TV channels..." -> "commit-start"
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
                    val state = graph.sync().currentSyncState(provider.id).javaClass.simpleName
                    Log.i(TAG, JSONObject().put("event", "sample").put("run", run)
                        .put("provider_id", provider.id).put("mode", mode.name).put("phase", "initial")
                        .put("warmup", iteration < warmups).put("elapsed_ms", elapsed)
                        .put("channels", count).put("sync_state", state)
                        .put("peak_java_heap_bytes", sample.peakHeapBytes)
                        .put("gc_count_delta", sample.gcCountDelta ?: JSONObject.NULL)
                        .put("milestones", org.json.JSONArray(progress)).toString())
                    if (measureRefresh) {
                        val refreshStart = SystemClock.elapsedRealtimeNanos()
                        val refresh = sampleHeap {
                            withTimeout(600_000L) {
                                graph.sync().retrySection(provider.id, SyncRepairSection.LIVE)
                            }
                        }
                        check(refresh.value is Result.Success)
                        val refreshed = graph.database().channelDao().getCount(provider.id).first()
                        check(refreshed == expected)
                        Log.i(TAG, JSONObject().put("event", "sample").put("run", run)
                            .put("provider_id", provider.id).put("mode", mode.name).put("phase", "refresh")
                            .put("warmup", iteration < warmups)
                            .put("elapsed_ms", (SystemClock.elapsedRealtimeNanos() - refreshStart) / 1_000_000.0)
                            .put("channels", refreshed).put("peak_java_heap_bytes", refresh.peakHeapBytes)
                            .put("gc_count_delta", refresh.gcCountDelta ?: JSONObject.NULL).toString())
                    }
                } finally {
                    withContext(NonCancellable) {
                        // Setup can save a provider before returning an error. Identify only
                        // this exact synthetic run; never clean up unrelated provider rows.
                        val saved = providerId?.let { graph.providers().getProvider(it) }
                            ?: graph.providers().getProviders().first().singleOrNull { it.name == name }
                        if (saved != null) {
                            check(saved.name == name)
                            check(graph.providers().deleteProvider(saved.id) is Result.Success)
                            check(graph.providers().getProvider(saved.id) == null)
                        }
                    }
                }
            }
        }
        Log.i(TAG, JSONObject().put("event", "complete").toString())
    }

    private data class HeapSample<T>(val value: T, val peakHeapBytes: Long, val gcCountDelta: Long?)

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
    }
}
