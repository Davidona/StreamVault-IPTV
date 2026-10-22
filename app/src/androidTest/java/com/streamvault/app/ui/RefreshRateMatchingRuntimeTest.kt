package com.streamvault.app.ui

import android.app.Application
import android.os.SystemClock
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.streamvault.app.MainActivity
import com.streamvault.domain.model.PlaybackContentKind
import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.model.StreamType
import com.streamvault.domain.model.PlaybackCompatibilityKey
import com.streamvault.domain.repository.PlaybackCompatibilityRepository
import com.streamvault.player.AudioCompatibilityMemoryStore
import com.streamvault.player.Media3PlayerEngine
import com.streamvault.player.PlaybackSupportSnapshotStore
import com.streamvault.player.PlaybackState
import com.streamvault.player.PlayerEngine
import com.streamvault.player.PlayerSurfaceResizeMode
import okhttp3.OkHttpClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in, local HLS fixture probe. HDMI mode acceptance still needs a physical television. */
@RunWith(AndroidJUnit4::class)
class RefreshRateMatchingRuntimeTest {
    @Test fun livePlaybackWithRefreshRequests() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("refreshMatchingFixture") == "true")
        val fixtureUrl = requireNotNull(arguments.getString("fixtureUrl"))
        val runId = requireNotNull(arguments.getString("runId"))
        require(runId.matches(Regex("[A-Za-z0-9_-]{1,40}")))
        val expectedRate = requireNotNull(arguments.getString("expectedRate")).toFloat()
        val durationMs = (arguments.getString("durationSeconds")?.toLong() ?: 136L) * 1_000L
        val app = ApplicationProvider.getApplicationContext<Application>()
        val engine: PlayerEngine = Media3PlayerEngine(
            context = app,
            okHttpClient = OkHttpClient(),
            playbackCompatibilityRepository = NoOpCompatibilityRepository,
            audioCompatibilityMemoryStore = AudioCompatibilityMemoryStore(app),
            playbackSupportSnapshotStore = PlaybackSupportSnapshotStore(app)
        )
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var renderView: View? = null
        fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
        val errors = mutableListOf<String>()
        val errorJob = launch { engine.error.collect { if (it != null) errors.add(it.javaClass.simpleName) } }
        val samples = JSONArray()
        try {
            scenario.onActivity { activity ->
                renderView = engine.createRenderView(activity, PlayerSurfaceResizeMode.FIT)
                activity.setContentView(renderView)
                engine.bindRenderView(renderView!!, PlayerSurfaceResizeMode.FIT)
            }
            main {
                engine.setMediaSessionEnabled(true)
                engine.prepare(StreamInfo(url = fixtureUrl, title = "AFR fixture $runId", streamType = StreamType.HLS,
                    containerExtension = "m3u8", playbackContentKind = PlaybackContentKind.LIVE))
                engine.play()
            }
            withTimeout(45_000) {
                while (!engine.isPlaying.value || engine.videoFormat.value.frameRate <= 0f) delay(100)
            }
            assertEquals(expectedRate, engine.videoFormat.value.frameRate, 0.1f)
            main { engine.setDisplayFrameRate(engine.videoFormat.value.frameRate) }
            println("AFR_READY runId=$runId fps=${engine.videoFormat.value.frameRate}")
            val started = SystemClock.elapsedRealtime()
            var toggled = false
            while (SystemClock.elapsedRealtime() - started < durationMs) {
                val stats = engine.playerStats.value
                val sample = JSONObject().put("elapsedMs", SystemClock.elapsedRealtime() - started)
                    .put("state", engine.playbackState.value.name).put("playing", engine.isPlaying.value)
                    .put("fps", engine.videoFormat.value.frameRate).put("width", stats.width).put("height", stats.height)
                    .put("dropped", stats.droppedFrames).put("rebuffers", stats.rebufferCount)
                main {
                    sample.put("displayHz", renderView?.display?.mode?.refreshRate)
                    sample.put("outputWidth", renderView?.display?.mode?.physicalWidth)
                    sample.put("outputHeight", renderView?.display?.mode?.physicalHeight)
                }
                samples.put(sample)
                if (!toggled && SystemClock.elapsedRealtime() - started > 50_000) {
                    main { engine.setDisplayFrameRate(null) }
                    delay(1_000)
                    main { engine.setDisplayFrameRate(engine.videoFormat.value.frameRate) }
                    toggled = true
                }
                delay(1_000)
            }
            assertEquals(PlaybackState.READY, engine.playbackState.value)
            assertTrue(engine.isPlaying.value)
            assertTrue("Playback errors: $errors", errors.isEmpty())
            app.filesDir.resolve("refresh-match-$runId.json").writeText(JSONObject().put("runId", runId)
                .put("expectedRate", expectedRate).put("errors", JSONArray(errors)).put("samples", samples).toString(2))
        } finally {
            errorJob.cancel()
            main {
                engine.setDisplayFrameRate(null)
                engine.stop()
                renderView?.let(engine::releaseRenderView)
                engine.release()
            }
            scenario.close()
        }
    }

    private object NoOpCompatibilityRepository : PlaybackCompatibilityRepository {
        override suspend fun getKnownBadRecords(deviceFingerprint: String, streamType: String, videoMimeType: String,
            resolutionBucket: String) = emptyList<com.streamvault.domain.model.PlaybackCompatibilityRecord>()
        override suspend fun recordFailure(key: PlaybackCompatibilityKey, failureType: String, at: Long) = Unit
        override suspend fun recordSuccess(key: PlaybackCompatibilityKey, at: Long) = Unit
        override suspend fun prune(maxRecords: Int, olderThanMs: Long) = Unit
    }
}
