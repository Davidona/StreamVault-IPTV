package com.streamvault.app.ui

import android.app.Application
import android.app.ActivityManager
import android.os.Debug
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Player as Media3Player
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.streamvault.app.MainActivity
import com.streamvault.domain.model.PlaybackBufferMode
import com.streamvault.domain.model.PlaybackContentKind
import com.streamvault.domain.model.DecoderMode
import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.model.StreamType
import com.streamvault.player.PlaybackState
import com.streamvault.player.PlayerEngine
import dagger.hilt.internal.GeneratedComponentManager
import javax.inject.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in local fixture probe for a same-device load-control comparison. */
@RunWith(AndroidJUnit4::class)
class PlaybackBufferComparisonRuntimeTest {
    @Test
    fun compareBufferPolicyForFixture() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("bufferComparisonFixture") == "true")
        val fixtureUrl = requireNotNull(arguments.getString("fixtureUrl"))
        val runId = requireNotNull(arguments.getString("runId"))
        require(runId.matches(Regex("[A-Za-z0-9_-]{1,40}")))
        val streamType = arguments.getString("streamType")
            ?.let(StreamType::valueOf)
            ?: StreamType.HLS
        require(streamType in setOf(StreamType.HLS, StreamType.DASH, StreamType.PROGRESSIVE, StreamType.MPEG_TS)) {
            "Unsupported comparison fixture stream type: $streamType"
        }
        val contentKind = arguments.getString("contentKind")
            ?.let(PlaybackContentKind::valueOf)
            ?: PlaybackContentKind.ON_DEMAND
        val bufferMode = arguments.getString("bufferMode")
            ?.let(PlaybackBufferMode::valueOf)
            ?: PlaybackBufferMode.AUTO
        require(bufferMode in setOf(PlaybackBufferMode.AUTO, PlaybackBufferMode.MEDIUM)) {
            "Unsupported comparison buffer mode: $bufferMode"
        }
        val containerExtension = arguments.getString("containerExtension")
            ?.trim()
            ?.removePrefix(".")
            ?.takeIf(String::isNotEmpty)
            ?: when (streamType) {
                StreamType.HLS -> "m3u8"
                StreamType.DASH -> "mpd"
                StreamType.MPEG_TS -> "ts"
                else -> "mp4"
            }
        // The V8 recurrence comparison needs enough time to observe the next gated restore
        // and its post-restoration pressure window in one source session.
        val durationSeconds = arguments.getString("durationSeconds")?.toIntOrNull()
            ?.coerceIn(30, 600) ?: 120
        // Development-only Stage 3 control; never write a saved decoder preference.
        val videoDecoderMode = arguments.getString("videoDecoderMode")?.let(DecoderMode::valueOf)
        val manualVideoHeight = arguments.getString("manualVideoHeight")?.toIntOrNull()

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = ApplicationProvider.getApplicationContext<Application>()
        val lowRamDevice = app.getSystemService(ActivityManager::class.java)?.isLowRamDevice ?: false
        val component = (app as GeneratedComponentManager<*>).generatedComponent()
        val provider = component.javaClass.getDeclaredField("provideMainPlayerEngineProvider").apply {
            isAccessible = true
        }.get(component) as Provider<*>
        val engine = provider.get() as PlayerEngine
        fun requestedMode(field: String) = engine.javaClass.getDeclaredField(field).apply {
            isAccessible = true
        }.get(engine) as DecoderMode
        val originalAudioMode = requestedMode("requestedAudioDecoderMode")
        val originalVideoMode = requestedMode("requestedVideoDecoderMode")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val errors = JSONArray()
        val errorJob = scope.launchCollectErrors(engine, errors)
        fun <T> main(action: () -> T): T {
            val result = java.util.concurrent.atomic.AtomicReference<T>()
            instrumentation.runOnMainSync { result.set(action()) }
            return result.get()
        }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var renderView: android.view.View? = null
        val samples = JSONArray()
        val startedAt = SystemClock.elapsedRealtime()
        try {
            scenario.onActivity { activity ->
                renderView = engine.createRenderView(activity, com.streamvault.player.PlayerSurfaceResizeMode.FIT)
                activity.setContentView(renderView)
                engine.bindRenderView(renderView!!, com.streamvault.player.PlayerSurfaceResizeMode.FIT)
            }
            main {
                engine.setMediaSessionEnabled(true)
                engine.setPlaybackBufferMode(bufferMode)
                videoDecoderMode?.let { engine.setDecoderModes(originalAudioMode, it) }
                engine.prepare(
                    StreamInfo(
                        url = fixtureUrl,
                        title = "Stage 1 ${streamType.name} buffer fixture",
                        streamType = streamType,
                        containerExtension = containerExtension,
                        playbackContentKind = contentKind
                    )
                )
                engine.play()
            }
            withTimeout(45_000) {
                while (!engine.isPlaying.value || engine.playbackState.value != PlaybackState.READY ||
                    engine.playerStats.value.ttffMs <= 0L
                ) delay(100)
            }
            val firstFrameAtMs = SystemClock.elapsedRealtime() - startedAt
            manualVideoHeight?.let { height -> main {
                val track = engine.availableVideoTracks.value.first { it.name.contains("${height}p") }
                engine.selectVideoTrack(track.id)
            } }
            val deadline = SystemClock.elapsedRealtime() + durationSeconds * 1_000L
            while (SystemClock.elapsedRealtime() < deadline) {
                val stats = engine.playerStats.value
                val decoderSample = main {
                    val player = engine.javaClass.getDeclaredField("exoPlayer").apply {
                        isAccessible = true
                    }.get(engine) as androidx.media3.exoplayer.ExoPlayer
                    val counters = player.videoDecoderCounters
                    counters?.ensureUpdated()
                    JSONObject()
                        .put("rendered", counters?.renderedOutputBufferCount)
                        .put("dropped", counters?.droppedBufferCount)
                        .put("skipped", counters?.skippedOutputBufferCount)
                        .put("droppedToKeyframe", counters?.droppedToKeyframeCount)
                        .put("maxConsecutiveDropped", counters?.maxConsecutiveDroppedBufferCount)
                        .put("decoderInitCount", counters?.decoderInitCount)
                        .put("frameRate", player.videoFormat?.frameRate)
                        .put("codecs", player.videoFormat?.codecs)
                        .put("colorTransfer", player.videoFormat?.colorInfo?.colorTransfer)
                        .put("videoOverrideCount", player.trackSelectionParameters.overrides.values.count {
                            it.mediaTrackGroup.type == C.TRACK_TYPE_VIDEO
                        })
                        .put("maxVideoHeight", player.trackSelectionParameters.maxVideoHeight)
                        .put("videoQualityAuto", engine.availableVideoTracks.value.firstOrNull {
                            it.id == com.streamvault.player.PLAYER_TRACK_AUTO_ID
                        }?.isSelected)
                        .put("overrideMaxHeight", player.trackSelectionParameters.overrides.values
                            .filter { it.type == C.TRACK_TYPE_VIDEO }.flatMap { override ->
                                override.trackIndices.map { override.mediaTrackGroup.getFormat(it).height }
                            }.maxOrNull())
                        .put("displayRefreshHz", renderView?.display?.mode?.refreshRate)
                }
                val memory = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
                val javaAllocated = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
                val liveOffsetMs = if (contentKind == PlaybackContentKind.LIVE) main {
                    runCatching {
                        val player = engine.javaClass.getDeclaredField("exoPlayer").apply {
                            isAccessible = true
                        }.get(engine) as? Media3Player
                        if (player?.isCommandAvailable(Media3Player.COMMAND_GET_CURRENT_MEDIA_ITEM) == true) {
                            player.currentLiveOffset
                        } else {
                            C.TIME_UNSET
                        }
                    }.getOrDefault(C.TIME_UNSET)
                } else {
                    C.TIME_UNSET
                }
                samples.put(
                    JSONObject()
                        .put("elapsedMs", SystemClock.elapsedRealtime() - startedAt)
                        .put("state", engine.playbackState.value.name)
                        .put("isPlaying", engine.isPlaying.value)
                        .put("positionMs", engine.currentPosition.value)
                        .put("liveOffsetMs", liveOffsetMs)
                        .put("bufferedMs", stats.bufferedDurationMs)
                        .put("rebufferCount", stats.rebufferCount)
                        .put("droppedFrames", stats.droppedFrames)
                        .put("width", stats.width)
                        .put("height", stats.height)
                        .put("videoBitrate", stats.videoBitrate)
                        .put("bandwidthEstimate", stats.bandwidthEstimate)
                        .put("decoder", stats.videoDecoderName)
                        .put("ttffMs", stats.ttffMs)
                        .put("javaAllocatedBytes", javaAllocated)
                        .put("totalPssKb", memory.totalPss)
                        .put("decoderCounters", decoderSample)
                )
                delay(1_000)
            }
            val last = engine.playerStats.value
            val result = JSONObject()
                .put("runId", runId)
                .put("fixtureUrl", fixtureUrl.replace(Regex("https?://[^/]+"), "<fixture-host>"))
                .put("contentKind", contentKind.name)
                .put("streamType", streamType.name)
                .put("bufferMode", bufferMode.name)
                .put("requestedVideoDecoderMode", videoDecoderMode?.name ?: originalVideoMode.name)
                .put("lowRamDevice", lowRamDevice)
                .put("durationSeconds", durationSeconds)
                .put("firstFrameMs", firstFrameAtMs)
                .put("finalState", engine.playbackState.value.name)
                .put("finalIsPlaying", engine.isPlaying.value)
                .put("finalPositionMs", engine.currentPosition.value)
                .put("finalDurationMs", engine.duration.value)
                .put("finalLiveOffsetMs", if (contentKind == PlaybackContentKind.LIVE) main {
                    runCatching {
                        val player = engine.javaClass.getDeclaredField("exoPlayer").apply {
                            isAccessible = true
                        }.get(engine) as? Media3Player
                        if (player?.isCommandAvailable(Media3Player.COMMAND_GET_CURRENT_MEDIA_ITEM) == true) {
                            player.currentLiveOffset
                        } else {
                            C.TIME_UNSET
                        }
                    }.getOrDefault(C.TIME_UNSET)
                } else C.TIME_UNSET)
                .put("finalBufferMs", last.bufferedDurationMs)
                .put("finalRebufferCount", last.rebufferCount)
                .put("finalDroppedFrames", last.droppedFrames)
                .put("finalWidth", last.width)
                .put("finalHeight", last.height)
                .put("finalVideoBitrate", last.videoBitrate)
                .put("finalBandwidthEstimate", last.bandwidthEstimate)
                .put("finalDecoder", last.videoDecoderName)
                .put("errors", errors)
                .put("samples", samples)
            val resultJson = result.toString(2)
            java.io.File(app.filesDir, "buffer-comparison-$runId.json").writeText(resultJson)
            arguments.getString("resultCallbackUrl")?.trim()?.takeIf(String::isNotEmpty)?.let { callbackUrl ->
                val connection = java.net.URL(callbackUrl).openConnection() as java.net.HttpURLConnection
                try {
                    connection.requestMethod = "POST"
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.outputStream.use { it.write(resultJson.toByteArray(Charsets.UTF_8)) }
                    check(connection.responseCode in 200..299) { "Result callback returned ${connection.responseCode}" }
                } finally {
                    connection.disconnect()
                }
            }
            println("Stage1BufferComparison runId=$runId streamType=$streamType contentKind=$contentKind " +
                "bufferMode=$bufferMode " +
                "lowRamDevice=$lowRamDevice " +
                "firstFrameMs=$firstFrameAtMs samples=${samples.length()} " +
                "finalState=${engine.playbackState.value} bufferMs=${last.bufferedDurationMs} " +
                "rebuffers=${last.rebufferCount} dropped=${last.droppedFrames} " +
                "size=${last.width}x${last.height} errors=${errors.length()}")
        } finally {
            scope.cancel()
            errorJob.cancel()
            main {
                engine.stop()
                engine.clearPreloadWindow()
                renderView?.let(engine::releaseRenderView)
                videoDecoderMode?.let { engine.setDecoderModes(originalAudioMode, originalVideoMode) }
            }
            scenario.close()
        }
    }

    private fun CoroutineScope.launchCollectErrors(engine: PlayerEngine, errors: JSONArray) = launch {
        engine.error.collect { error -> if (error != null) synchronized(errors) { errors.put(error.javaClass.simpleName) } }
    }
}
