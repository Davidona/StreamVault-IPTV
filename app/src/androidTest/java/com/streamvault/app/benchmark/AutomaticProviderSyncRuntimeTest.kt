package com.streamvault.app.benchmark

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.google.common.truth.Truth.assertThat
import com.streamvault.data.sync.ProviderSyncWorker
import com.streamvault.data.sync.SyncRepairSection
import com.streamvault.domain.model.LegacyProvider
import com.streamvault.domain.model.ProviderEpgSyncMode
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.Result
import com.streamvault.domain.sync.AutomaticSyncSettings
import dagger.hilt.android.EntryPointAccessors
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in probe using only a newly created synthetic playlist; existing providers are retained. */
@RunWith(AndroidJUnit4::class)
class AutomaticProviderSyncRuntimeTest {
    @Test
    fun inactivePlaylistReconcilesAndHonorsAutomaticDisable() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("automaticSyncProbe") == "true")
        val app = ApplicationProvider.getApplicationContext<Application>()
        check(app.packageName.endsWith(".debug")) { "Run against the debug application" }
        val graph = EntryPointAccessors.fromApplication(app, XtreamSyncBenchmarkEntryPoint::class.java)
        val original = graph.preferences().automaticSyncConfiguration.first()
        val socket = ServerSocket(0)
        val requests = AtomicInteger()
        val playlist = AtomicReference<String>()
        val base = "http://127.0.0.1:${socket.localPort}"
        fun entry(id: String, name: String) = "#EXTINF:-1 tvg-id=\"$id\" group-title=\"Fixture\",$name\n$base/$id.ts\n"
        playlist.set("#EXTM3U\n" + entry("a", "Original") + entry("b", "Removed"))
        val server = thread(isDaemon = true, name = "automatic-sync-fixture") {
            while (!socket.isClosed) {
                try {
                    socket.accept().use { client ->
                        client.soTimeout = 5_000
                        val reader = client.getInputStream().bufferedReader()
                        reader.readLine()
                        while (!reader.readLine().isNullOrEmpty()) { }
                        requests.incrementAndGet()
                        val body = playlist.get().toByteArray(Charsets.UTF_8)
                        client.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Type: audio/x-mpegurl\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(body); flush()
                        }
                    }
                } catch (error: Exception) { if (!socket.isClosed) throw error }
            }
        }
        var providerId: Long? = null
        try {
            graph.preferences().setAutomaticSyncSettings(null, original.defaults.copy(enabled = true))
            val added = graph.providers().addProvider(LegacyProvider(name = "Automatic sync fixture", type = ProviderType.M3U,
                serverUrl = "$base/playlist.m3u", m3uUrl = "$base/playlist.m3u", isActive = false, epgSyncMode = ProviderEpgSyncMode.SKIP))
            check(added is Result.Success)
            val id = added.data
            providerId = id
            val policy = AutomaticSyncSettings(live = true, movies = false, series = false, epg = false)
            graph.preferences().setAutomaticSyncSettings(id, policy)
            val workManager = WorkManager.getInstance(app)
            suspend fun checkWorker() {
                val request = OneTimeWorkRequestBuilder<ProviderSyncWorker>()
                    .setInputData(workDataOf("automatic_provider_id" to id)).build()
                withContext(Dispatchers.IO) { workManager.enqueue(request).result.get(30, TimeUnit.SECONDS) }
                val state = withTimeout(60_000L) {
                    var finished: WorkInfo? = null
                    while (finished == null) {
                        val info = withContext(Dispatchers.IO) { workManager.getWorkInfoById(request.id).get(30, TimeUnit.SECONDS) }
                        if (info?.state?.isFinished == true) finished = info else delay(200L)
                    }
                    finished.state
                }
                assertThat(state).isEqualTo(WorkInfo.State.SUCCEEDED)
            }
            checkWorker()
            val first = graph.database().channelDao().getByProvider(id).first()
            assertThat(first.map { it.name }).containsExactly("Original", "Removed")
            val originalId = first.single { it.name == "Original" }.id
            assertThat(graph.providers().getProvider(id)?.isActive).isFalse()
            val before = requests.get()
            checkWorker()
            assertThat(requests.get()).isEqualTo(before)
            playlist.set("#EXTM3U\n" + entry("a", "Updated") + entry("c", "Added"))
            val metadata = requireNotNull(graph.syncMetadata().getMetadata(id))
            graph.syncMetadata().updateMetadata(metadata.copy(lastLiveSuccess = 0L))
            checkWorker()
            val refreshed = graph.database().channelDao().getByProvider(id).first()
            assertThat(refreshed.map { it.name }).containsExactly("Updated", "Added")
            assertThat(refreshed.single { it.name == "Updated" }.id).isEqualTo(originalId)
            assertThat(graph.providers().getProvider(id)?.isActive).isFalse()
            graph.preferences().setAutomaticSyncSettings(id, policy.copy(enabled = false))
            graph.syncMetadata().updateMetadata(requireNotNull(graph.syncMetadata().getMetadata(id)).copy(lastLiveSuccess = 0L))
            val disabledRequests = requests.get()
            checkWorker()
            assertThat(requests.get()).isEqualTo(disabledRequests)
            assertThat(graph.sync().syncSection(id, SyncRepairSection.LIVE)).isInstanceOf(Result.Success::class.java)
            assertThat(requests.get()).isGreaterThan(disabledRequests)
        } finally {
            providerId?.let { id -> graph.providers().deleteProvider(id); graph.preferences().setAutomaticSyncSettings(id, null) }
            graph.preferences().setAutomaticSyncSettings(null, original.defaults)
            socket.close()
            withContext(Dispatchers.IO) { server.join(5_000L) }
        }
    }
}
