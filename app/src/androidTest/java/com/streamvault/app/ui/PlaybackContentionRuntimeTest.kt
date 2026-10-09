package com.streamvault.app.ui

import android.app.Application
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.streamvault.app.MainActivity
import com.streamvault.domain.model.PlaybackContentKind
import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.model.StreamType
import com.streamvault.player.*
import com.streamvault.player.playback.FrameThumbnailRequest
import com.streamvault.player.playback.Media3FrameThumbnailExtractor
import dagger.hilt.internal.GeneratedComponentManager
import javax.inject.Provider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList

/** Opt-in measured probe of production playback components, not the episode navigation UI. */
@RunWith(AndroidJUnit4::class)
class PlaybackContentionRuntimeTest {
    @Test fun compareForegroundWithBackgroundReaders() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("contentionFixture") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = ApplicationProvider.getApplicationContext<Application>()
        val component = (app as GeneratedComponentManager<*>).generatedComponent()
        fun provided(name: String) = (component.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(component) as Provider<*>).get()
        val engine = provided("provideMainPlayerEngineProvider") as PlayerEngine
        val client = provided("provideOkHttpClientProvider") as OkHttpClient
        val extractor = Media3FrameThumbnailExtractor(app, client)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val errors = CopyOnWriteArrayList<String>()
        val errorJob = scope.launch { engine.error.collect { if (it != null) errors += it.javaClass.simpleName } }
        fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
        fun server(path: String, body: String? = null): JSONObject {
            val connection = URL("http://10.0.2.2:18768/$path").openConnection() as HttpURLConnection
            connection.connectTimeout = 5_000; connection.readTimeout = 5_000
            if (body != null) { connection.requestMethod = "POST"; connection.doOutput = true; connection.outputStream.use { it.write(body.toByteArray()) } }
            return try { JSONObject(connection.inputStream.bufferedReader().use { it.readText() }) } finally { connection.disconnect() }
        }
        suspend fun waitReady() {
            withTimeout(30_000) { while (!engine.isPlaying.value || engine.playbackState.value != PlaybackState.READY) delay(100) }
        }
        val results = JSONArray()
        val foregroundPriority = InstrumentationRegistry.getArguments().getString("foregroundPriority") == "true"
        val connectionLimitOnly = InstrumentationRegistry.getArguments().getString("connectionLimitOnly") == "true"
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var render: android.view.View? = null
        try {
            scenario.onActivity { activity ->
                render = engine.createRenderView(activity, PlayerSurfaceResizeMode.FIT)
                activity.setContentView(render)
                engine.bindRenderView(render!!, PlayerSurfaceResizeMode.FIT)
            }
            val cases = if (connectionLimitOnly) listOf(
                Case("one_connection_neighbors", false, true, 1)
            ) else if (foregroundPriority) listOf(
                Case("priority_off", false, false, 0, seconds = 90),
                Case("priority_neighbors", false, true, 0, seconds = 90),
                Case("healthy_neighbors", false, true, 0, rateBps = 30_000_000),
                Case("one_connection_neighbors", false, true, 1)
            ) else listOf(
                Case("off", false, false, 0), Case("thumbnails", true, false, 0),
                Case("neighbors", false, true, 0), Case("both", true, true, 0),
                Case("one_connection_off", false, false, 1), Case("one_connection_both", true, true, 1)
            )
            for (case in cases) {
                val (name, thumbnails, neighbors, limit) = case
                main { engine.stop(); engine.clearPreloadWindow() }
                extractor.clearCache(); delay(2_000); errors.clear()
                server("control", "{\"rate_bps\":${case.rateBps},\"limit\":$limit}")
                fun stream(role: String) = StreamInfo(
                    "http://10.0.2.2:18768/$role.mp4?case=$name", title = "Contention $role",
                    headers = mapOf("X-Validation-Role" to role), streamType = StreamType.PROGRESSIVE,
                    containerExtension = "mp4", playbackContentKind = PlaybackContentKind.ON_DEMAND
                )
                val current = stream("foreground"); val next = stream("next")
                val before = SystemClock.elapsedRealtime()
                main { engine.prepare(current); engine.play() }
                waitReady()
                val startup = SystemClock.elapsedRealtime() - before
                if (neighbors) main { engine.preloadWindow(PlayerPreloadWindow(listOf(
                    PlayerPreloadItem("previous", stream("previous"), PlayerPreloadContentType.VOD),
                    PlayerPreloadItem("current", current, PlayerPreloadContentType.VOD),
                    PlayerPreloadItem("next", next, PlayerPreloadContentType.VOD)
                ), 1)) }
                var frames = 0
                val thumbnailsJob = if (thumbnails) scope.launch {
                    for (position in listOf(0L, 10_000L, 20_000L, 30_000L, 40_000L, 50_000L)) {
                        if (extractor.loadFrame(FrameThumbnailRequest(current.copy(headers = mapOf("X-Validation-Role" to "thumbnail")), false), position) != null) frames++
                    }
                } else null
                val samples = JSONArray()
                repeat(case.seconds) { i ->
                    val stats = engine.playerStats.value
                    samples.put(JSONObject().put("second", i).put("bufferMs", stats.bufferedDurationMs)
                        .put("rebuffers", stats.rebufferCount).put("drops", stats.droppedFrames)
                        .put("state", engine.playbackState.value.name).put("position", engine.currentPosition.value))
                    delay(1_000)
                }
                thumbnailsJob?.cancelAndJoin()
                val delivery = server("status")
                val handoffStart = SystemClock.elapsedRealtime()
                main { engine.prepare(next); engine.play() }
                val handoffFailure = runCatching { waitReady() }.exceptionOrNull()?.javaClass?.simpleName
                val handoff = SystemClock.elapsedRealtime() - handoffStart
                val result = JSONObject().put("case", name).put("startupMs", startup).put("nextReadyMs", handoff)
                    .put("handoffFailure", handoffFailure ?: JSONObject.NULL)
                    .put("thumbnailFrames", frames).put("samples", samples).put("errors", JSONArray(errors)).put("server", delivery)
                results.put(result)
                java.io.File(app.filesDir, "contention-results.json").writeText(results.toString(2))
                println("Stage5 completed $name startupMs=$startup nextReadyMs=$handoff thumbnailFrames=$frames errors=${errors.size}")
            }
        } finally {
            scope.cancel(); errorJob.cancel()
            main { engine.stop(); engine.clearPreloadWindow(); render?.let { engine.releaseRenderView(it) } }
            extractor.clearCache(); scenario.close()
        }
    }
    private data class Case(
        val name: String, val thumbnails: Boolean, val neighbors: Boolean, val limit: Int,
        val rateBps: Int = 8_000_000, val seconds: Int = 60
    )
}
