@file:androidx.media3.common.util.UnstableApi

package com.streamvault.app.ui

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModelStore
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.streamvault.app.MainActivity
import com.streamvault.core.navigation.PlayerNavigationRequest
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.feature.playback.player.PlayerViewModel
import com.streamvault.feature.playback.player.toggleControls
import com.streamvault.player.PlaybackState
import dagger.hilt.internal.GeneratedComponentManager
import javax.inject.Provider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Normal PlayerScreen, local live fixtures, no replacement render view or TIF pipeline. */
@RunWith(AndroidJUnit4::class)
class HiddenControlsPlaybackRuntimeTest {
    @Test fun exerciseDefaultLivePlayer() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("hiddenControlsFixture") == "true")
        val run = requireNotNull(args.getString("runId"))
        require(run.matches(Regex("[A-Za-z0-9_-]{1,40}")))
        val urls = requireNotNull(args.getString("fixtureUrls")).split(',')
        require(urls.all { Uri.parse(it).host in setOf("127.0.0.1", "localhost") })
        val seconds = args.getString("durationSeconds")?.toInt() ?: 130
        require(seconds in 10..160)
        val app = ApplicationProvider.getApplicationContext<Application>()
        val component = (app as GeneratedComponentManager<*>).generatedComponent()
        val prefs = (component.javaClass.getDeclaredField("preferencesRepositoryProvider").apply {
            isAccessible = true
        }.get(component) as Provider<*>).get() as PreferencesRepository
        val originalTimeshift = prefs.playerTimeshiftEnabled.first()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val events = JSONArray()
        fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
        fun players(store: ViewModelStore): List<PlayerViewModel> = buildList {
            for (key in store.keys()) {
                val model = store[key] ?: continue
                if (model is PlayerViewModel) add(model)
                model.javaClass.declaredFields.filter { Map::class.java.isAssignableFrom(it.type) }.forEach { field ->
                    val values = field.apply { isAccessible = true }.get(model) as? Map<*, *> ?: return@forEach
                    values.values.filterIsInstance<ViewModelStore>().forEach { addAll(players(it)) }
                }
            }
        }.distinct()
        fun resolvedUrl(vm: PlayerViewModel): String = vm.javaClass.methods.single {
            it.name.substringBefore('$') == "getCurrentResolvedPlaybackUrl" && it.parameterCount == 0
        }.invoke(vm) as String
        fun emit(row: JSONObject) {
            row.put("elapsedMs", SystemClock.elapsedRealtime())
            synchronized(events) { events.put(row) }
            Log.i("HiddenControlsFixture", row.toString())
        }
        // Durable record survives a host-side harness interruption before finally executes.
        app.filesDir.resolve("hidden-controls-$run-original.json").writeText(
            JSONObject().put("timeshift", originalTimeshift).toString())
        prefs.setPlayerTimeshiftEnabled(false)
        var scenario: ActivityScenario<MainActivity>? = null
        var workerProbe: PlaybackWorkerAdmissionProbe? = null
        var inflightProbe: PlaybackInflightEpgProbe? = null
        var catalogProbe: PlaybackInflightCatalogProbe? = null
        var indexProbe: PlaybackIndexWorkProbe? = null
        var workerEngine: com.streamvault.player.PlayerEngine? = null
        var audioSourceProbe: PlaybackAudioSourceProbe? = null
        var audioSourceEngine: com.streamvault.player.PlayerEngine? = null
        try {
            inflightProbe = PlaybackInflightEpgProbe.start(app, component, args, run, ::emit)
            catalogProbe = PlaybackInflightCatalogProbe.start(app, args, run, ::emit)
            indexProbe = PlaybackIndexWorkProbe.start(app, args, run, ::emit)
            scenario = ActivityScenario.launch(MainActivity::class.java)
            urls.forEachIndexed { index, url ->
                val navigation = indexProbe?.navigation(url) ?: inflightProbe?.navigation(url) ?: PlayerNavigationRequest(
                    streamUrl = url, title = "Local live fixture $index", contentType = "LIVE")
                scenario.onActivity { it.openPlayer(navigation) }
                var vm: PlayerViewModel? = null
                val deadline = SystemClock.elapsedRealtime() + 60_000L
                while (vm == null && SystemClock.elapsedRealtime() < deadline) {
                    scenario.onActivity { activity ->
                        vm = players(activity.viewModelStore).lastOrNull {
                            resolvedUrl(it) == url && it.playerEngine.isPlaying.value &&
                                it.playerEngine.playbackState.value == PlaybackState.READY
                        }
                    }
                    Thread.sleep(100)
                }
                val model = checkNotNull(vm) { "Normal PlayerScreen did not start fixture $index" }
                val engine = model.playerEngine
                if (args.getString("audioSourceProbe") == "true") {
                    check(urls.size == 1) { "Source timing probe requires one fixture per run" }
                    lateinit var native: ExoPlayer
                    main { native = engine.javaClass.getDeclaredField("exoPlayer").apply {
                        isAccessible = true
                    }.get(engine) as ExoPlayer }
                    audioSourceEngine = engine
                    audioSourceProbe = PlaybackAudioSourceProbe(native, ::emit)
                    audioSourceProbe!!.install()
                }
                // Read the effective media clock on its playback thread. Public playbackParameters
                // reports user intent and can omit internal live catch-up adjustments.
                val clockSample = java.util.concurrent.atomic.AtomicReference<JSONObject>()
                fun sampleClock(native: ExoPlayer) {
                    native.createMessage { _, _ ->
                        val internal = native.javaClass.getDeclaredField("internalPlayer").apply {
                            isAccessible = true
                        }.get(native)
                        val clock = internal.javaClass.getDeclaredField("mediaClock").apply {
                            isAccessible = true
                        }.get(internal)
                        val parameters = clock.javaClass.getDeclaredMethod("getPlaybackParameters").apply {
                            isAccessible = true
                        }.invoke(clock) as androidx.media3.common.PlaybackParameters
                        clockSample.set(JSONObject().put("speed", parameters.speed)
                            .put("elapsedMs", SystemClock.elapsedRealtime()))
                    }.send()
                }
                fun visibility(show: Boolean) = main { if (model.showControls.value != show) model.toggleControls() }
                @Suppress("UNCHECKED_CAST")
                val position = engine.javaClass.getDeclaredField("_currentPosition").apply {
                    isAccessible = true
                }.get(engine) as MutableStateFlow<Long>
                fun snapshot(stage: String): JSONObject {
                    lateinit var row: JSONObject
                    main {
                        val native = engine.javaClass.getDeclaredField("exoPlayer").apply {
                            isAccessible = true
                        }.get(engine) as ExoPlayer
                        native.videoDecoderCounters?.ensureUpdated()
                        sampleClock(native)
                        val video = native.videoFormat
                        val displayHz = native.videoFormat?.let { _ ->
                            val binder = engine.javaClass.getDeclaredField("viewBinder").apply {
                                isAccessible = true
                            }.get(engine)
                            val view = binder.javaClass.getDeclaredField("boundPlayerView").apply {
                                isAccessible = true
                            }.get(binder) as? android.view.View
                            view?.display?.mode?.refreshRate
                        }
                        val videoMode = engine.javaClass.getDeclaredField("requestedVideoDecoderMode")
                            .apply { isAccessible = true }.get(engine).toString()
                        val audioMode = engine.javaClass.getDeclaredField("requestedAudioDecoderMode")
                            .apply { isAccessible = true }.get(engine).toString()
                        row = JSONObject().put("event", stage).put("channelIndex", index)
                            .put("state", engine.playbackState.value.name).put("playing", engine.isPlaying.value)
                            .put("error", native.playerError?.errorCode ?: JSONObject.NULL)
                            .put("isLoading", native.isLoading)
                            .put("positionSubscriptions", position.subscriptionCount.value)
                            .put("controlsVisible", model.showControls.value)
                            .put("diagnosticsVisible", model.showDiagnostics.value)
                            .put("timeshift", engine.playerStats.value.isTimeshiftPlayback)
                            .put("bufferedMs", native.totalBufferedDuration)
                            .put("rendered", native.videoDecoderCounters?.renderedOutputBufferCount ?: 0)
                            .put("dropped", native.videoDecoderCounters?.droppedBufferCount ?: 0)
                            .put("skipped", native.videoDecoderCounters?.skippedOutputBufferCount ?: 0)
                            .put("droppedInput", native.videoDecoderCounters?.droppedInputBufferCount ?: 0)
                            .put("maxConsecutiveDrops", native.videoDecoderCounters?.maxConsecutiveDroppedBufferCount ?: 0)
                            .put("droppedToKeyframe", native.videoDecoderCounters?.droppedToKeyframeCount ?: 0)
                            .put("processingOffsetUs", native.videoDecoderCounters?.totalVideoFrameProcessingOffsetUs ?: 0)
                            .put("processingOffsetCount", native.videoDecoderCounters?.videoFrameProcessingOffsetCount ?: 0)
                            .put("effectiveClock", clockSample.get() ?: JSONObject.NULL)
                            .put("requestedSpeed", native.playbackParameters.speed)
                            .put("liveOffsetMs", native.currentLiveOffset)
                            .put("decoder", engine.playerStats.value.videoDecoderName)
                            .put("requestedVideoDecoderMode", videoMode)
                            .put("requestedAudioDecoderMode", audioMode)
                            .put("renderSurfaceType", engine.renderSurfaceType.value.name)
                            .put("formatFps", video?.frameRate ?: JSONObject.NULL)
                            .put("videoWidth", video?.width ?: 0)
                            .put("videoHeight", video?.height ?: 0)
                            .put("audioDecoder", engine.playerStats.value.audioDecoderName)
                            .put("javaHeapBytes", Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())
                            .put("gcCount", android.os.Debug.getRuntimeStat("art.gc.gc-count"))
                            .put("gcTimeMs", android.os.Debug.getRuntimeStat("art.gc.gc-time"))
                            .put("displayHz", displayHz ?: JSONObject.NULL)
                            .put("rebufferCount", engine.playerStats.value.rebufferCount)
                            .put("mainCpuMs", SystemClock.currentThreadTimeMillis())
                            .put("processCpuMs", android.os.Process.getElapsedCpuTime())
                    }
                    return row
                }
                visibility(false)
                Thread.sleep(1_000)
                check(!model.showDiagnostics.value)
                emit(snapshot("capture-begin"))
                check(engine.javaClass.getDeclaredField("requestedVideoDecoderMode").apply {
                    isAccessible = true
                }.get(engine).toString() == "AUTO") { "Expected default AUTO video decoder mode" }
                check(engine.javaClass.getDeclaredField("requestedAudioDecoderMode").apply {
                    isAccessible = true
                }.get(engine).toString() == "AUTO") { "Expected default AUTO audio decoder mode" }
                val workload = PlaybackImportWorkload.start(app, component, args, run, ::emit)
                workerProbe = PlaybackWorkerAdmissionProbe.start(app, args, run, ::emit)
                if (workerProbe != null || inflightProbe != null || catalogProbe != null || indexProbe != null) workerEngine = engine
                inflightProbe?.verifyPlaybackContext(model.javaClass.methods.single {
                    it.name.substringBefore('$') == "getCurrentProviderId" && it.parameterCount == 0
                }.invoke(model) as Long)
                inflightProbe?.playbackReady()
                catalogProbe?.playbackReady()
                indexProbe?.verifyPlaybackContext(model.javaClass.methods.single {
                    it.name.substringBefore('$') == "getCurrentProviderId" && it.parameterCount == 0
                }.invoke(model) as Long)
                indexProbe?.playbackReady()
                try {
                    repeat(seconds) {
                        Thread.sleep(1_000)
                        emit(snapshot("sample"))
                        audioSourceProbe?.drain()
                        check(!engine.playerStats.value.isTimeshiftPlayback)
                    }
                } finally {
                    workload?.close()
                    workerProbe?.finishWhilePlaying()
                    inflightProbe?.finishWhilePlaying()
                    catalogProbe?.finishWhilePlaying()
                    indexProbe?.finishWhilePlaying()
                }
                visibility(true)
                Thread.sleep(700)
                emit(snapshot("controls-open"))
                visibility(false)
                Thread.sleep(700)
                emit(snapshot("controls-closed"))
                val final = snapshot("capture-end")
                emit(final)
                check(engine.playbackState.value == PlaybackState.READY && engine.isPlaying.value) {
                    "Playback unhealthy at end of channel $index: ${engine.playbackState.value}, playing=${engine.isPlaying.value}"
                }
                check(final.isNull("error")) {
                    "Native player error at end of channel $index"
                }
                if (args.getString("indexLifecycleProbe")=="true") indexProbe?.verifyPauseResume(engine)
            }
        } finally {
            try {
                // Closing the Activity can retain service playback. Stop only this probe's
                // engine, including on a failed assertion, before checking idle admission.
                main { workerEngine?.stop() }
                if (audioSourceProbe != null) emit(JSONObject().put("event", "activity-close-begin"))
                scenario?.close()
                if (audioSourceProbe != null) emit(JSONObject().put("event", "activity-close-end"))
                try { audioSourceProbe?.finish() } finally { main { audioSourceEngine?.stop() } }
                workerProbe?.finishAfterPlayback()
                inflightProbe?.finishAfterPlayback()
                catalogProbe?.finishAfterPlayback()
                indexProbe?.finishAfterPlayback()
            } finally {
                try { indexProbe?.close() } finally { try { catalogProbe?.close() } finally {
                    prefs.setPlayerTimeshiftEnabled(originalTimeshift)
                    app.filesDir.resolve("hidden-controls-$run.json").writeText(events.toString(2))
                }
                }
            }
        }
    }
}
