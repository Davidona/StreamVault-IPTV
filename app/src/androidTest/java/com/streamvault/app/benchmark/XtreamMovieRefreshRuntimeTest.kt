package com.streamvault.app.benchmark

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.streamvault.data.sync.ProviderSyncWorker
import com.google.common.truth.Truth.assertThat
import com.streamvault.data.sync.SyncRepairSection
import com.streamvault.domain.model.LegacyProvider
import com.streamvault.domain.model.ProviderEpgSyncMode
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.Result
import com.streamvault.domain.sync.AutomaticSyncSettings
import dagger.hilt.android.EntryPointAccessors
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.TimeUnit
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

/** Opt-in local HTTP fixture; no real provider credentials or playback are used. */
@RunWith(AndroidJUnit4::class)
class XtreamMovieRefreshRuntimeTest {
    @Test fun manualAndUpgradeCatchupDiscoverNewMovies() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("automaticSyncProbe") == "true")
        val app = ApplicationProvider.getApplicationContext<Application>()
        check(app.packageName.endsWith(".debug"))
        val graph = EntryPointAccessors.fromApplication(app, XtreamSyncBenchmarkEntryPoint::class.java)
        val original = graph.preferences().automaticSyncConfiguration.first()
        val socket = ServerSocket(0)
        val base = "http://127.0.0.1:${socket.localPort}"
        val movieCount = AtomicInteger(1)
        val seriesCount = AtomicInteger(1)
        val movieRequests = AtomicInteger()
        val server = thread(isDaemon = true, name = "xtream-movie-refresh-fixture") {
            while (!socket.isClosed) {
                try {
                    socket.accept().use { client ->
                        client.soTimeout = 5000
                        val reader = client.getInputStream().bufferedReader()
                        val path = reader.readLine().split(' ').getOrNull(1).orEmpty()
                        while (!reader.readLine().isNullOrEmpty()) { }
                        val action = Uri.parse(base + path).getQueryParameter("action")
                        val body = when (action) {
                            "get_vod_categories" -> """[{"category_id":"42","category_name":"Fixture Movies"}]"""
                            "get_vod_streams" -> {
                                movieRequests.incrementAndGet()
                                (1..movieCount.get()).joinToString(prefix = "[", postfix = "]") { id ->
                                    """{"stream_id":$id,"name":"Fixture Movie $id","category_id":"42","container_extension":"mp4","added":"1700000000"}"""
                                }
                            }
                            "get_series_categories" -> """[{"category_id":"43","category_name":"Fixture Series"}]"""
                            "get_series" -> (1..seriesCount.get()).joinToString(prefix = "[", postfix = "]") { id ->
                                """{"series_id":$id,"name":"Fixture Series $id","category_id":"43","last_modified":"1700000000"}"""
                            }
                            "get_live_categories", "get_live_streams" -> "[]"
                            else -> """{"user_info":{"auth":1,"status":"Active","max_connections":"2"},"server_info":{"url":"127.0.0.1"}}"""
                        }.toByteArray()
                        client.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(body); flush()
                        }
                    }
                } catch (error: Exception) { if (!socket.isClosed) throw error }
            }
        }
        var providerId: Long? = null
        try {
            graph.preferences().setAutomaticSyncSettings(null, original.defaults.copy(enabled = false))
            val added = graph.providers().addProvider(LegacyProvider(name = "Xtream Movie refresh fixture",
                type = ProviderType.XTREAM_CODES, serverUrl = base, username = "fixture", password = "fixture",
                isActive = false, epgSyncMode = ProviderEpgSyncMode.SKIP))
            check(added is Result.Success)
            val id = added.data
            providerId = id
            suspend fun awaitMovies(count: Int) = withTimeout(90_000L) {
                while (true) {
                    val movies = graph.database().movieDao().getByProvider(id).first()
                    val job = graph.database().xtreamIndexJobDao().get(id, "MOVIE")
                    if (movies.size == count && job?.state == "SUCCESS") return@withTimeout movies
                    check(job?.state != "FAILED_PERMANENT") { "Fixture Movie index failed permanently" }
                    delay(200L)
                }
                @Suppress("UNREACHABLE_CODE") error("unreachable")
            }
            assertThat(graph.sync().syncSection(id, SyncRepairSection.MOVIES)).isInstanceOf(Result.Success::class.java)
            val first = awaitMovies(1)
            val firstId = first.single().id
            suspend fun awaitSeries(count: Int) = withTimeout(90_000L) {
                while (true) {
                    val series = graph.database().seriesDao().getByProvider(id).first()
                    val job = graph.database().xtreamIndexJobDao().get(id, "SERIES")
                    if (series.size == count && job?.state == "SUCCESS") return@withTimeout series
                    check(job?.state != "FAILED_PERMANENT") { "Fixture Series index failed permanently" }
                    delay(200L)
                }
                @Suppress("UNREACHABLE_CODE") error("unreachable")
            }
            assertThat(graph.sync().syncSection(id, SyncRepairSection.SERIES)).isInstanceOf(Result.Success::class.java)
            val firstSeriesId = awaitSeries(1).single().id
            // A previous provider recovery can be waiting for its retry delay. A user
            // refreshing Movies must not inherit that unrelated WorkManager prerequisite.
            val manager = WorkManager.getInstance(app)
            val delayedRecovery = OneTimeWorkRequestBuilder<ProviderSyncWorker>()
                .setInputData(workDataOf("provider_id" to id))
                .setInitialDelay(10, TimeUnit.MINUTES)
                .build()
            manager.enqueueUniqueWork("provider-workflow-$id", ExistingWorkPolicy.APPEND_OR_REPLACE,
                delayedRecovery).result.get()
            movieCount.set(2)
            seriesCount.set(2)
            assertThat(graph.sync().syncSection(id, SyncRepairSection.MOVIES)).isInstanceOf(Result.Success::class.java)
            assertThat(graph.sync().syncSection(id, SyncRepairSection.SERIES)).isInstanceOf(Result.Success::class.java)
            delay(5_000L)
            android.util.Log.i("XtreamManualRefreshProbe", "after_manual_refresh job=" +
                graph.database().xtreamIndexJobDao().get(id, "MOVIE")?.let { "${it.state} rows=${it.indexedRows}" } +
                " provider_chain=" + manager.getWorkInfosForUniqueWork("provider-workflow-$id").get()
                    .joinToString { "${it.state}" } + " series_job=" +
                graph.database().xtreamIndexJobDao().get(id, "SERIES")?.let { "${it.state} rows=${it.indexedRows}" })
            val manual = awaitMovies(2)
            assertThat(manual.single { it.name == "Fixture Movie 1" }.id).isEqualTo(firstId)
            assertThat(awaitSeries(2).single { it.name == "Fixture Series 1" }.id).isEqualTo(firstSeriesId)
            assertThat(manager.getWorkInfoById(delayedRecovery.id).get()?.state)
                .isEqualTo(androidx.work.WorkInfo.State.ENQUEUED)

            // The existing Movie success is fresh. A new sync-engine checkpoint must
            // still request catch-up instead of carrying the old freshness across it.
            movieCount.set(3)
            graph.preferences().setAutomaticSyncSettings(id, AutomaticSyncSettings(
                live = false, movies = true, series = false, epg = false, deferWhileBusy = false))
            graph.preferences().setAutomaticSyncSettings(null, original.defaults.copy(enabled = true))
            val automatic = awaitMovies(3)
            assertThat(automatic.map { it.name }).containsExactly("Fixture Movie 1", "Fixture Movie 2", "Fixture Movie 3")
            assertThat(movieRequests.get()).isAtLeast(3)
            assertThat(graph.providers().getProvider(id)?.isActive).isFalse()
        } finally {
            providerId?.let { id ->
                WorkManager.getInstance(app).cancelUniqueWork("provider-workflow-$id").result.get()
                WorkManager.getInstance(app).cancelUniqueWork("provider-xtream-index-$id").result.get()
                graph.providers().deleteProvider(id)
                graph.preferences().setAutomaticSyncSettings(id, null)
            }
            graph.preferences().setAutomaticSyncSettings(null, original.defaults)
            socket.close()
            withContext(Dispatchers.IO) { server.join(5000L) }
        }
    }
}
