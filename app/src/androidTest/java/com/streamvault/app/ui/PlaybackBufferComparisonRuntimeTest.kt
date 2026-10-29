package com.streamvault.app.ui

import android.app.Application
import android.app.ActivityManager
import android.media.MediaCodecList
import android.os.Debug
import android.os.Build
import android.os.SystemClock
import android.view.SurfaceView
import android.view.TextureView
import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.common.Player as Media3Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.streamvault.app.MainActivity
import com.streamvault.domain.model.PlaybackBufferMode
import com.streamvault.domain.model.PlaybackContentKind
import com.streamvault.domain.model.DecoderMode
import com.streamvault.domain.model.PlayerSurfaceMode
import com.streamvault.domain.model.LivePlaybackIdentity
import com.streamvault.domain.model.RecordingSourceType
import com.streamvault.domain.util.LivePlaybackByteTap
import com.streamvault.data.manager.recording.HlsLiveCaptureEngine
import com.streamvault.data.manager.recording.RecordingHttpClientFactory
import com.streamvault.data.manager.recording.RecordingOutputTarget
import com.streamvault.data.manager.recording.ResolvedRecordingSource
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
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
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
        require(bufferMode in setOf(PlaybackBufferMode.AUTO, PlaybackBufferMode.MEDIUM, PlaybackBufferMode.LARGE)) {
            "Unsupported comparison buffer mode: $bufferMode"
        }
        val initialBufferMode = arguments.getString("initialBufferMode")
            ?.let(PlaybackBufferMode::valueOf)
            ?: bufferMode
        require(initialBufferMode in setOf(PlaybackBufferMode.AUTO, PlaybackBufferMode.MEDIUM, PlaybackBufferMode.LARGE)) {
            "Unsupported initial comparison buffer mode: $initialBufferMode"
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
        val startupWaitTimeoutSeconds = arguments.getString("startupWaitTimeoutSeconds")
            ?.toLongOrNull()?.coerceIn(0L, 120L) ?: 45L
        val timestampWaitStackProbe = arguments.getString("timestampWaitStackProbe") == "true"
        val forceProviderSubtitleSelection = arguments.getString("forceProviderSubtitleSelection") == "true"
        // Development-only Stage 3 control; never write a saved decoder preference.
        val videoDecoderMode = arguments.getString("videoDecoderMode")?.let(DecoderMode::valueOf)
        val surfaceMode = arguments.getString("surfaceMode")?.let(PlayerSurfaceMode::valueOf)
        val manualVideoHeight = arguments.getString("manualVideoHeight")?.toIntOrNull()
        val tapMode = arguments.getString("tapMode") ?: "inactive"
        val recordingDelaySeconds = arguments.getString("recordingDelaySeconds")?.toLongOrNull() ?: 0L
        require(recordingDelaySeconds in 0L..90L)
        require(tapMode in setOf("inactive", "idle", "recording"))
        require(tapMode == "inactive" || streamType == StreamType.HLS && contentKind == PlaybackContentKind.LIVE)
        val liveIdentity = if (tapMode == "inactive") null else LivePlaybackIdentity(9_999_999L, 1L)

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = ApplicationProvider.getApplicationContext<Application>()
        val lowRamDevice = app.getSystemService(ActivityManager::class.java)?.isLowRamDevice ?: false
        val component = (app as GeneratedComponentManager<*>).generatedComponent()
        val provider = component.javaClass.getDeclaredField("provideMainPlayerEngineProvider").apply {
            isAccessible = true
        }.get(component) as Provider<*>
        val engine = provider.get() as PlayerEngine
        val tap = engine.javaClass.getDeclaredField("livePlaybackByteTap").apply {
            isAccessible = true
        }.get(engine) as LivePlaybackByteTap
        fun tapSnapshot(): JSONObject {
            val registry = tap.javaClass.getDeclaredField("activeHlsSources").apply {
                isAccessible = true
            }.get(tap) as Map<*, *>
            var payload = 0L
            var playlists = 0L
            var captures = 0
            synchronized(registry) {
                registry.values.filterNotNull().forEach { session -> synchronized(session) {
                    fun field(name: String) = session.javaClass.getDeclaredField(name).apply {
                        isAccessible = true
                    }.get(session)
                    payload += (field("payloadBytes") as Int).toLong()
                    playlists += (field("playlistBytes") as Int).toLong()
                    captures += (field("captures") as Set<*>).size
                } }
            }
            return JSONObject().put("payloadBytes", payload).put("playlistBytes", playlists).put("captures", captures)
        }
        fun requestedMode(field: String) = engine.javaClass.getDeclaredField(field).apply {
            isAccessible = true
        }.get(engine) as DecoderMode
        val originalAudioMode = requestedMode("requestedAudioDecoderMode")
        val originalVideoMode = requestedMode("requestedVideoDecoderMode")
        val originalSurfaceMode = engine.javaClass.getDeclaredField("requestedSurfaceMode").apply {
            isAccessible = true
        }.get(engine) as PlayerSurfaceMode
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val errors = JSONArray()
        val loadDiagnostics = JSONArray()
        var diagnosticPlayer: ExoPlayer? = null
        val loadDiagnosticListener = object : AnalyticsListener {
            override fun onLoadCompleted(
                eventTime: AnalyticsListener.EventTime,
                loadEventInfo: LoadEventInfo,
                mediaLoadData: MediaLoadData
            ) {
                synchronized(loadDiagnostics) {
                    loadDiagnostics.put(JSONObject()
                        .put("event", "completed")
                        .put("elapsedRealtimeMs", SystemClock.elapsedRealtime())
                        .put("loadTaskId", loadEventInfo.loadTaskId)
                        .put("bytesLoaded", loadEventInfo.bytesLoaded)
                        .put("position", loadEventInfo.dataSpec.position))
                }
            }

            override fun onLoadError(
                eventTime: AnalyticsListener.EventTime,
                loadEventInfo: LoadEventInfo,
                mediaLoadData: MediaLoadData,
                error: java.io.IOException,
                wasCanceled: Boolean
            ) {
                val causes = JSONArray()
                generateSequence<Throwable>(error) { it.cause }.take(8).forEach { cause ->
                    val trace = JSONArray()
                    cause.stackTrace.take(12).forEach { trace.put(it.toString()) }
                    val row = JSONObject().put("type", cause.javaClass.name).put("stack", trace)
                    // Only retain protocol parser details, with URLs redacted. General HTTP
                    // exception messages may contain provider paths or account credentials.
                    if (cause is java.net.ProtocolException) {
                        row.put("protocolDetail", cause.message.orEmpty()
                            .replace(Regex("https?://\\S+"), "<url>").take(500))
                    }
                    causes.put(row)
                }
                synchronized(loadDiagnostics) {
                    loadDiagnostics.put(JSONObject()
                        .put("event", "error")
                        .put("elapsedRealtimeMs", SystemClock.elapsedRealtime())
                        .put("loadTaskId", loadEventInfo.loadTaskId)
                        .put("bytesLoaded", loadEventInfo.bytesLoaded)
                        .put("position", loadEventInfo.dataSpec.position)
                        .put("wasCanceled", wasCanceled)
                        .put("causes", causes))
                }
            }
        }
        var forcedSubtitleTrackId: String? = null
        val timestampWaitStacks = JSONArray()
        val timestampWaitStacksLock = Any()
        fun timestampWaitStackSnapshot(): JSONObject {
            val matchingThreads = JSONArray()
            Thread.getAllStackTraces().forEach { (thread, trace) ->
                val matchIndex = trace.indexOfFirst { frame ->
                    frame.className == "androidx.media3.common.util.TimestampAdjuster" &&
                        frame.methodName == "sharedInitializeOrWait"
                }
                if (matchIndex >= 0) {
                    val frames = JSONArray()
                    trace.take(32).forEach { frame ->
                        frames.put(JSONObject()
                            .put("class", frame.className)
                            .put("method", frame.methodName)
                            .put("file", frame.fileName ?: JSONObject.NULL)
                            .put("line", frame.lineNumber))
                    }
                    matchingThreads.put(JSONObject()
                        .put("name", thread.name)
                        .put("state", thread.state.name)
                        .put("timestampAdjusterFrameIndex", matchIndex)
                        .put("stack", frames))
                }
            }
            return JSONObject()
                .put("elapsedRealtimeMs", SystemClock.elapsedRealtime())
                .put("matchingThreadCount", matchingThreads.length())
                .put("threads", matchingThreads)
        }
        var timestampWaitStackJob: kotlinx.coroutines.Job? = null
        val decoderCapabilities = mutableMapOf<String, Triple<Boolean?, Boolean?, Boolean?>>()
        val errorJob = scope.launchCollectErrors(engine, errors)
        fun <T> main(action: () -> T): T {
            val result = java.util.concurrent.atomic.AtomicReference<T>()
            instrumentation.runOnMainSync { result.set(action()) }
            return result.get()
        }
        // Public playbackParameters contains the user's requested speed, not Media3's live
        // adjustment. Read the clock/control on their owning playback thread for this probe.
        suspend fun nativeTimingSample(): JSONObject = withTimeout(2_000L) {
            suspendCancellableCoroutine { continuation ->
                main {
                    val player = engine.javaClass.getDeclaredField("exoPlayer").apply {
                        isAccessible = true
                    }.get(engine) as androidx.media3.exoplayer.ExoPlayer
                    val internalPlayer = player.javaClass.getDeclaredField("internalPlayer").apply {
                        isAccessible = true
                    }.get(player)
                    player.createMessage { _, _ ->
                        try {
                            fun field(name: String) = internalPlayer.javaClass.getDeclaredField(name).apply {
                                isAccessible = true
                            }.get(internalPlayer)
                            val control = field("livePlaybackSpeedControl") as
                                androidx.media3.exoplayer.LivePlaybackSpeedControl
                            val clock = field("mediaClock")
                            val parameters = clock.javaClass.getDeclaredMethod("getPlaybackParameters").apply {
                                isAccessible = true
                            }.invoke(clock) as androidx.media3.common.PlaybackParameters
                            val sample = JSONObject()
                                .put("nativeClockSpeed", parameters.speed)
                                .put("controlTargetLiveOffsetUs", control.targetLiveOffsetUs
                                    .takeUnless { it == C.TIME_UNSET } ?: JSONObject.NULL)
                            if (continuation.isActive) continuation.resume(sample)
                        } catch (error: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(error)
                        }
                    }.send()
                }
            }
        }
        suspend fun rendererQueueSample(): JSONObject = withTimeout(2_000L) {
            suspendCancellableCoroutine { continuation ->
                main {
                    val player = engine.javaClass.getDeclaredField("exoPlayer").apply {
                        isAccessible = true
                    }.get(engine) as ExoPlayer
                    val renderer = findVideoRenderer(player)
                    player.createMessage { _, _ ->
                        val snapshot = videoRendererQueueSnapshot(renderer)
                        if (continuation.isActive) continuation.resume(snapshot)
                    }.send()
                }
            }
        }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var renderView: android.view.View? = null
        val samples = JSONArray()
        val startedAt = SystemClock.elapsedRealtime()
        val gcBaseline = JSONObject(Debug.getRuntimeStats())
        val recordingBytes = java.util.concurrent.atomic.AtomicLong()
        val recordingFailure = java.util.concurrent.atomic.AtomicReference<String?>()
        var recordingJob: kotlinx.coroutines.Job? = null
        val recordingFile = java.io.File(app.filesDir, "tap-profile-$runId.ts")
        try {
            scenario.onActivity { activity ->
                surfaceMode?.let(engine::setSurfaceMode)
                val surfaceType = if (surfaceMode == null) {
                    com.streamvault.player.PlayerRenderSurfaceType.AUTO
                } else {
                    engine.renderSurfaceType.value
                }
                renderView = engine.createRenderView(
                    activity,
                    com.streamvault.player.PlayerSurfaceResizeMode.FIT,
                    surfaceType
                )
                activity.setContentView(renderView)
                engine.bindRenderView(renderView!!, com.streamvault.player.PlayerSurfaceResizeMode.FIT)
            }
            main {
                engine.setMediaSessionEnabled(true)
                engine.setPlaybackBufferMode(initialBufferMode)
                videoDecoderMode?.let { engine.setDecoderModes(originalAudioMode, it) }
                engine.prepare(
                    StreamInfo(
                        url = fixtureUrl,
                        title = "Stage 1 ${streamType.name} buffer fixture",
                        streamType = streamType,
                        containerExtension = containerExtension,
                        playbackContentKind = contentKind,
                        livePlaybackIdentity = liveIdentity
                    )
                )
                diagnosticPlayer = engine.javaClass.getDeclaredField("exoPlayer").apply {
                    isAccessible = true
                }.get(engine) as ExoPlayer
                diagnosticPlayer?.addAnalyticsListener(loadDiagnosticListener)
                engine.play()
            }
            if (forceProviderSubtitleSelection) {
                withTimeout(15_000L) {
                    while (engine.availableSubtitleTracks.value.none {
                            it.subtitleSource == com.streamvault.player.SubtitleTrackSource.PROVIDER
                        }) delay(100)
                }
                val subtitleTrack = engine.availableSubtitleTracks.value.first {
                    it.subtitleSource == com.streamvault.player.SubtitleTrackSource.PROVIDER
                }
                main { engine.selectSubtitleTrack(subtitleTrack.id) }
                forcedSubtitleTrackId = subtitleTrack.id
            }
            if (timestampWaitStackProbe) {
                timestampWaitStackJob = scope.launch(Dispatchers.Default) {
                    while (true) {
                        val snapshot = timestampWaitStackSnapshot()
                        synchronized(timestampWaitStacksLock) {
                            timestampWaitStacks.put(snapshot)
                        }
                        delay(500)
                    }
                }
            }
            val initialFirstFrameObserved = if (startupWaitTimeoutSeconds == 0L) {
                false
            } else {
                withTimeoutOrNull(startupWaitTimeoutSeconds * 1_000L) {
                    while (!engine.isPlaying.value || engine.playbackState.value != PlaybackState.READY ||
                        engine.playerStats.value.ttffMs <= 0L
                    ) delay(100)
                    true
                } ?: false
            }
            val initialFirstFrameAtMs = if (initialFirstFrameObserved) {
                SystemClock.elapsedRealtime() - startedAt
            } else null
            if (initialBufferMode != bufferMode && initialFirstFrameObserved) {
                main { engine.setPlaybackBufferMode(bufferMode) }
                withTimeout(45_000) {
                    while (engine.playbackState.value == PlaybackState.READY &&
                        engine.playerStats.value.ttffMs > 0L
                    ) delay(20)
                    while (!engine.isPlaying.value || engine.playbackState.value != PlaybackState.READY ||
                        engine.playerStats.value.ttffMs <= 0L
                    ) delay(100)
                }
            }
            val firstFrameAtMs = if (engine.playerStats.value.ttffMs > 0L) {
                SystemClock.elapsedRealtime() - startedAt
            } else null
            if (tapMode == "recording") {
                delay(recordingDelaySeconds * 1000L)
                // Exercise the production HLS recorder, requiring reuse of foreground resources.
                val forbiddenUpstream = okhttp3.OkHttpClient.Builder().addInterceptor {
                    throw java.io.IOException("Unexpected duplicate upstream recording request")
                }.build()
                recordingJob = scope.launch {
                    try {
                        HlsLiveCaptureEngine(RecordingHttpClientFactory { forbiddenUpstream }, tap).capture(
                            source = ResolvedRecordingSource(
                                url = fixtureUrl, sourceType = RecordingSourceType.HLS,
                                livePlaybackIdentity = liveIdentity
                            ),
                            outputTarget = RecordingOutputTarget.FileTarget(recordingFile),
                            contentResolver = app.contentResolver,
                            scheduledEndMs = System.currentTimeMillis() + durationSeconds * 1000L + 5_000L,
                            onProgress = { recordingBytes.set(it.bytesWritten) }
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        recordingFailure.set(error.javaClass.simpleName + ": " + error.message)
                    }
                }
            }
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
                    }.get(engine) as ExoPlayer
                    val counters = player.videoDecoderCounters
                    counters?.ensureUpdated()
                    val window = if (player.currentTimeline.isEmpty) null else
                        player.currentTimeline.getWindow(player.currentMediaItemIndex, Timeline.Window())
                    val videoFormat = player.videoFormat
                    val playerView = renderView as? PlayerView
                    val outputView = playerView?.videoSurfaceView
                    val surfaceState = when (outputView) {
                        is SurfaceView -> JSONObject()
                            .put("class", outputView.javaClass.name)
                            .put("attachedToWindow", outputView.isAttachedToWindow)
                            .put("width", outputView.width)
                            .put("height", outputView.height)
                            .put("surfaceValid", runCatching { outputView.holder.surface?.isValid }
                                .getOrNull() ?: JSONObject.NULL)
                        is TextureView -> JSONObject()
                            .put("class", outputView.javaClass.name)
                            .put("attachedToWindow", outputView.isAttachedToWindow)
                            .put("width", outputView.width)
                            .put("height", outputView.height)
                            .put("textureAvailable", outputView.isAvailable)
                        null -> JSONObject().put("class", JSONObject.NULL)
                            .put("attachedToWindow", JSONObject.NULL)
                        else -> JSONObject()
                            .put("class", outputView.javaClass.name)
                            .put("attachedToWindow", outputView.isAttachedToWindow)
                    }
                    val rendererSnapshot = videoRendererSnapshot(player)
                    val decoderName = engine.playerStats.value.videoDecoderName
                    val decoderCapabilitiesForName = decoderName.takeIf { it.isNotBlank() }?.let { name ->
                        decoderCapabilities.getOrPut(name) {
                            runCatching {
                                val codecInfo = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
                                    .firstOrNull { it.name == name }
                                if (codecInfo == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                                    Triple(null, null, null)
                                } else {
                                    Triple(
                                        codecInfo.isHardwareAccelerated,
                                        codecInfo.isSoftwareOnly,
                                        codecInfo.isVendor
                                    )
                                }
                            }.getOrDefault(Triple(null, null, null))
                        }
                    }
                    JSONObject()
                        .put("requestedPlaybackSpeed", player.playbackParameters.speed)
                        .put("isLoading", player.isLoading)
                        .put("liveWindowDynamic", window?.isDynamic)
                        .put("defaultPositionMs", window?.defaultPositionMs)
                        .put("requestedMediaItemTargetOffsetMs", player.currentMediaItem?.liveConfiguration
                            ?.targetOffsetMs?.takeUnless { it == C.TIME_UNSET } ?: JSONObject.NULL)
                        .put("playlistTargetOffsetMs", window?.liveConfiguration?.targetOffsetMs
                            ?.takeUnless { it == C.TIME_UNSET } ?: JSONObject.NULL)
                        .put("playlistMinPlaybackSpeed", window?.liveConfiguration?.minPlaybackSpeed
                            ?.takeUnless { it == C.RATE_UNSET } ?: JSONObject.NULL)
                        .put("playlistMaxPlaybackSpeed", window?.liveConfiguration?.maxPlaybackSpeed
                            ?.takeUnless { it == C.RATE_UNSET } ?: JSONObject.NULL)
                        .put("rendered", counters?.renderedOutputBufferCount)
                        .put("queuedInput", counters?.queuedInputBufferCount)
                        .put("skippedInput", counters?.skippedInputBufferCount)
                        .put("dropped", counters?.droppedBufferCount)
                        .put("skipped", counters?.skippedOutputBufferCount)
                        .put("droppedToKeyframe", counters?.droppedToKeyframeCount)
                        .put("maxConsecutiveDropped", counters?.maxConsecutiveDroppedBufferCount)
                        .put("decoderInitCount", counters?.decoderInitCount)
                        .put("frameRate", videoFormat?.frameRate)
                        .put("maxInputSize", videoFormat?.maxInputSize)
                        .put("codecs", videoFormat?.codecs)
                        .put("colorTransfer", videoFormat?.colorInfo?.colorTransfer)
                        .put("videoRendererClass", rendererSnapshot.first)
                        .put("videoCodecAdapterClass", rendererSnapshot.second)
                        .put("decoderHardwareAccelerated", decoderCapabilitiesForName?.first ?: JSONObject.NULL)
                        .put("decoderSoftwareOnly", decoderCapabilitiesForName?.second ?: JSONObject.NULL)
                        .put("decoderVendor", decoderCapabilitiesForName?.third ?: JSONObject.NULL)
                        .put("renderSurfaceType", engine.renderSurfaceType.value.name)
                        .put("renderSurface", surfaceState)
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
                val nativeTiming = nativeTimingSample()
                val rendererQueue = rendererQueueSample()
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
                        .put("runtimeStats", JSONObject(Debug.getRuntimeStats()))
                        .put("tap", tapSnapshot())
                        .put("recordingBytes", recordingBytes.get())
                        .put("decoderCounters", decoderSample)
                        .put("rendererQueue", rendererQueue)
                        .put("nativeTiming", nativeTiming)
                )
                delay(1_000)
            }
            timestampWaitStackJob?.cancelAndJoin()
            timestampWaitStackJob = null
            val last = engine.playerStats.value
            val result = JSONObject()
                .put("runId", runId)
                .put("loadDiagnostics", synchronized(loadDiagnostics) { JSONArray(loadDiagnostics.toString()) })
                .put("fixtureUrl", fixtureUrl.replace(Regex("https?://[^/]+"), "<fixture-host>"))
                .put("contentKind", contentKind.name)
                .put("streamType", streamType.name)
                .put("bufferMode", bufferMode.name)
                .put("initialBufferMode", initialBufferMode.name)
                .put("bufferModeSwitchedAfterInitialReady", initialBufferMode != bufferMode)
                    .put("tapMode", tapMode)
                    .put("recordingDelaySeconds", recordingDelaySeconds)
                .put("runtimeStatsBaseline", gcBaseline)
                .put("runtimeStatsFinal", JSONObject(Debug.getRuntimeStats()))
                .put("recordingBytes", recordingBytes.get())
                .put("recordingFailure", recordingFailure.get() ?: JSONObject.NULL)
                .put("recordingFile", if (tapMode == "recording") recordingFile.name else JSONObject.NULL)
                .put("requestedVideoDecoderMode", videoDecoderMode?.name ?: originalVideoMode.name)
                .put("requestedSurfaceMode", surfaceMode?.name ?: originalSurfaceMode.name)
                .put("renderSurfaceType", engine.renderSurfaceType.value.name)
                .put("lowRamDevice", lowRamDevice)
                .put("durationSeconds", durationSeconds)
                .put("firstFrameMs", firstFrameAtMs ?: JSONObject.NULL)
                .put("initialFirstFrameMs", initialFirstFrameAtMs ?: JSONObject.NULL)
                .put("initialFirstFrameObserved", initialFirstFrameObserved)
                .put("timestampWaitStackProbeEnabled", timestampWaitStackProbe)
                .put("timestampWaitStackSamples", timestampWaitStacks)
                .put("forceProviderSubtitleSelection", forceProviderSubtitleSelection)
                .put("forcedSubtitleTrackId", forcedSubtitleTrackId ?: JSONObject.NULL)
                .put("availableSubtitleTracks", JSONArray(engine.availableSubtitleTracks.value.map { track ->
                    JSONObject().put("id", track.id).put("name", track.name)
                        .put("selected", track.isSelected).put("source", track.subtitleSource.name)
                }))
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
            check(recordingFailure.get() == null) { "Recording failed: ${recordingFailure.get()}" }
            if (tapMode == "recording") check(recordingBytes.get() > 0) { "Shared recording produced no output" }
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
            timestampWaitStackJob?.cancelAndJoin()
            recordingJob?.cancelAndJoin()
            scope.cancel()
            errorJob.cancel()
            main {
                diagnosticPlayer?.removeAnalyticsListener(loadDiagnosticListener)
                engine.stop()
                engine.clearPreloadWindow()
                renderView?.let(engine::releaseRenderView)
                videoDecoderMode?.let { engine.setDecoderModes(originalAudioMode, originalVideoMode) }
                surfaceMode?.let { engine.setSurfaceMode(originalSurfaceMode) }
            }
            scenario.close()
        }
    }

    private fun CoroutineScope.launchCollectErrors(engine: PlayerEngine, errors: JSONArray) = launch {
        engine.error.collect { error -> if (error != null) synchronized(errors) { errors.put(error.javaClass.simpleName) } }
    }

    /** Best-effort reflection for distinguishing MediaCodec adapter queueing modes in diagnostics. */
    private fun videoRendererSnapshot(player: ExoPlayer): Pair<String?, String?> {
        val renderer = findVideoRenderer(player) ?: return null to null
        var owner: Class<*>? = renderer.javaClass
        var codecField: java.lang.reflect.Field? = null
        while (owner != null && codecField == null) {
            codecField = owner.declaredFields.firstOrNull { it.name == "codec" }
            owner = owner.superclass
        }
        val adapterClass = codecField?.let { field ->
            runCatching {
                field.isAccessible = true
                field.get(renderer)?.javaClass?.name
            }.getOrNull()
        }
        return renderer.javaClass.name to adapterClass
    }

    /** Read codec queue state on the playback thread; unavailable private fields stay null. */
    private fun videoRendererQueueSnapshot(renderer: Any?): JSONObject {
        renderer ?: return JSONObject().put("snapshotAvailable", false).put("failure", "video-renderer-unavailable")
        val failures = JSONArray()
        fun readField(target: Any?, name: String): Any? {
            if (target == null) {
                failures.put("$name:owner-null")
                return null
            }
            var owner: Class<*>? = target.javaClass
            while (owner != null) {
                val field = owner.declaredFields.firstOrNull { it.name == name }
                if (field != null) {
                    return runCatching {
                        field.isAccessible = true
                        field.get(target)
                    }.onFailure { failures.put("$name:${it.javaClass.simpleName}") }.getOrNull()
                }
                owner = owner.superclass
            }
            failures.put("$name:not-found")
            return null
        }
        val outputBufferInfo = readField(renderer, "outputBufferInfo")
        val displaySurface = readField(renderer, "displaySurface")
        val placeholderSurface = readField(renderer, "placeholderSurface")
        val result = JSONObject()
            .put("snapshotAvailable", true)
            .put("rendererClass", renderer.javaClass.name)
            .put("codecOperatingRate", readField(renderer, "codecOperatingRate") ?: JSONObject.NULL)
            .put("inputIndex", readField(renderer, "inputIndex") ?: JSONObject.NULL)
            .put("outputIndex", readField(renderer, "outputIndex") ?: JSONObject.NULL)
            .put("largestQueuedPresentationTimeUs",
                readField(renderer, "largestQueuedPresentationTimeUs") ?: JSONObject.NULL)
            .put("outputBufferInfoClass", outputBufferInfo?.javaClass?.name ?: JSONObject.NULL)
            .put("outputPresentationTimeUs",
                readField(outputBufferInfo, "presentationTimeUs") ?: JSONObject.NULL)
            .put("displaySurfaceClass", displaySurface?.javaClass?.name ?: JSONObject.NULL)
            .put("displaySurfaceValid", (displaySurface as? android.view.Surface)?.isValid ?: JSONObject.NULL)
            .put("placeholderSurfaceClass", placeholderSurface?.javaClass?.name ?: JSONObject.NULL)
            .put("placeholderSurfaceValid",
                (placeholderSurface as? android.view.Surface)?.isValid ?: JSONObject.NULL)
        if (failures.length() > 0) result.put("fieldFailures", failures)
        return result
    }

    private fun findVideoRenderer(player: ExoPlayer): Any? {
        return runCatching {
            val rendererCount = player.javaClass.methods.firstOrNull {
                it.name == "getRendererCount" && it.parameterCount == 0
            }?.invoke(player) as? Int ?: return@runCatching null
            val getRenderer = player.javaClass.methods.firstOrNull {
                it.name == "getRenderer" && it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
            } ?: return@runCatching null
            (0 until rendererCount).asSequence()
                .mapNotNull { index -> runCatching { getRenderer.invoke(player, index) }.getOrNull() }
                .firstOrNull { candidate ->
                    val trackType = runCatching {
                        candidate.javaClass.methods.firstOrNull {
                            it.name == "getTrackType" && it.parameterCount == 0
                        }?.invoke(candidate) as? Int
                    }.getOrNull()
                    trackType == C.TRACK_TYPE_VIDEO || candidate.javaClass.simpleName.contains("VideoRenderer")
                }
        }.getOrNull()
    }
}
