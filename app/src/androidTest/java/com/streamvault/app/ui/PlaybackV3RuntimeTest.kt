package com.streamvault.app.ui

import android.app.Application
import android.graphics.Bitmap
import android.os.Debug
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.util.Log
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollToIndex
import androidx.lifecycle.ViewModelStore
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.StreamVaultDatabase
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.data.sync.ProviderSyncCommands
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.LegacyProvider
import com.streamvault.domain.model.LiveStreamFormatMode
import com.streamvault.domain.model.PlaybackContentKind
import com.streamvault.domain.model.PlaybackHistory
import com.streamvault.domain.model.Program
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.model.StreamType
import com.streamvault.domain.repository.ChannelRepository
import com.streamvault.domain.repository.ProviderRepository
import com.streamvault.domain.repository.SeriesRepository
import com.streamvault.feature.playback.player.PlayerViewModel
import com.streamvault.feature.playback.player.SeekThumbnailProvider
import com.streamvault.feature.playback.player.updateSeekPreview
import com.streamvault.player.PlaybackState
import com.streamvault.player.PlayerEngine
import com.streamvault.player.playback.ResolvedStreamType
import dagger.hilt.internal.GeneratedComponentManager
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import javax.inject.Provider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in V3 normal-player scrubbing, episode handoff, and cancellation probe. */
@RunWith(AndroidJUnit4::class)
class PlaybackV3RuntimeTest {
    @get:Rule val compose = createAndroidComposeRule<com.streamvault.app.MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val device get() = UiDevice.getInstance(instrumentation)
    private val fixtureName = "Playback resilience V3 20261003"
    private val providerUrl = "http://10.0.2.2:18773"
    private val outputDir get() = File(app.filesDir, "playback-v3-private")
    private var lastArchiveFirstFrameMs = 0L
    private data class TimelineScreenArea(val left: Float, val top: Float, val width: Float, val height: Float) {
        fun point(fraction: Float) = Offset(left + width * fraction.coerceIn(0.02f, 0.98f), top + height / 2f)
    }

    @Test
    fun validateLegacyRemoteReadCancellation() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("playbackV3Fixture") == "true")
        val provider = SeekThumbnailProvider(app, OkHttpClient())
        server("/control?failBeta=false&failEpisodeId=none&cap=5&reset=1")
        try {
            runBlocking {
                repeat(2) { requestNumber ->
                    val url = "$providerUrl/thumbnail/slow.mp4?cancel-check=${SystemClock.elapsedRealtime()}-$requestNumber"
                    val job = launch(Dispatchers.IO) { provider.loadFrame(url, 170_000) }
                    withTimeout(5_000) {
                        while (eventCount(server("/stats"), "request-start", role = "native-thumbnail") < requestNumber + 1) {
                            delay(50)
                        }
                    }

                    val cancelledAt = SystemClock.elapsedRealtime()
                    job.cancel()
                    withTimeout(8_000) {
                        while (true) {
                            val stats = server("/stats")
                            val active = stats.getJSONArray("active").let { rows ->
                                (0 until rows.length()).count { rows.getJSONObject(it).optString("role") == "native-thumbnail" }
                            }
                            if (active == 0) break
                            delay(50)
                        }
                    }
                    job.join()
                    assertThat(SystemClock.elapsedRealtime() - cancelledAt).isLessThan(8_000)
                    assertThat(thumbnailWorkerThreads().length()).isEqualTo(0)
                }
            }
        } finally {
            server("/control?failBeta=false&failEpisodeId=none&cap=5&reset=1")
        }
    }

    private fun enabled() = assumeTrue(InstrumentationRegistry.getArguments().getString("playbackV3Fixture") == "true")

    private fun component(name: String): Any {
        val c = (app as GeneratedComponentManager<*>).generatedComponent()
        return (c.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(c) as Provider<*>).get()!!
    }

    private fun server(path: String): JSONObject {
        val connection = URL("$providerUrl$path").openConnection() as HttpURLConnection
        connection.connectTimeout = 5_000
        connection.readTimeout = 10_000
        return try { JSONObject(connection.inputStream.bufferedReader().use { it.readText() }) }
        finally { connection.disconnect() }
    }

    private fun await(label: String, timeoutMs: Long = 45_000, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < end) {
            compose.waitForIdle()
            if (condition()) return
            SystemClock.sleep(100)
        }
        device.takeScreenshot(File(outputDir, "timeout-$label.png"))
        device.dumpWindowHierarchy(File(outputDir, "timeout-$label.xml"))
        error("Timed out: $label")
    }

    private fun click(text: String) {
        compose.waitForIdle()
        if (text.startsWith("S01E")) {
            compose.onNodeWithText(text, substring = true).performSemanticsAction(SemanticsActions.OnClick) { it() }
        } else {
            val node = device.wait(Until.findObject(By.textContains(text)), 20_000)
                ?: run {
                    outputDir.mkdirs()
                    val safeLabel = text.replace(Regex("[^A-Za-z0-9_-]"), "_")
                    device.takeScreenshot(File(outputDir, "missing-$safeLabel.png"))
                    device.dumpWindowHierarchy(File(outputDir, "missing-$safeLabel.xml"))
                    error("Missing V3 UI text after 20 seconds: $text")
                }
            val b = node.visibleBounds
            device.executeShellCommand("input mouse tap ${b.centerX()} ${b.centerY()}")
        }
        compose.waitForIdle()
    }

    private fun acceptResumePromptIfPresent() {
        compose.waitForIdle()
        if (device.findObject(By.text("Resume Playback")) == null) return
        val resume = device.findObject(By.text("Resume")) ?: error("Resume prompt has no Resume button")
        val bounds = resume.visibleBounds
        device.executeShellCommand("input mouse tap ${bounds.centerX()} ${bounds.centerY()}")
        compose.waitForIdle()
        Log.i("PlaybackV3Fixture", "episode-resume-prompt accepted=true")
    }

    private fun findPlayers(store: ViewModelStore): List<PlayerViewModel> {
        val found = mutableListOf<PlayerViewModel>()
        for (key in store.keys()) {
            val model = store[key] ?: continue
            if (model is PlayerViewModel) found += model
            for (field in model.javaClass.declaredFields.filter { Map::class.java.isAssignableFrom(it.type) }) {
                val values = field.apply { isAccessible = true }.get(model) as? Map<*, *> ?: continue
                values.values.filterIsInstance<ViewModelStore>().forEach { found += findPlayers(it) }
            }
        }
        return found.distinct()
    }

    private fun getter(owner: Any, name: String): Any? = owner.javaClass.methods
        .single { it.name.substringBefore('$') == name && it.parameterCount == 0 }.invoke(owner)

    private fun setter(owner: Any, name: String, value: Any?) {
        owner.javaClass.methods.single {
            it.name.substringBefore('$') == name && it.parameterCount == 1
        }.invoke(owner, value)
    }

    private fun resolvedPlaybackUrl(vm: PlayerViewModel): String =
        getter(vm, "getCurrentResolvedPlaybackUrl") as String

    private fun setResolvedPlaybackUrl(vm: PlayerViewModel, value: String) =
        setter(vm, "setCurrentResolvedPlaybackUrl", value)

    private fun resolvedStreamInfo(vm: PlayerViewModel): StreamInfo? =
        getter(vm, "getCurrentResolvedStreamInfo") as StreamInfo?

    private fun setResolvedStreamInfo(vm: PlayerViewModel, value: StreamInfo?) =
        setter(vm, "setCurrentResolvedStreamInfo", value)

    private fun vmForEpisode(number: Int): PlayerViewModel {
        lateinit var vm: PlayerViewModel
        await("episode $number ViewModel") {
            var found = false
            instrumentation.runOnMainSync {
                findPlayers(compose.activity.viewModelStore).singleOrNull {
                    it.currentEpisode.value?.episodeNumber == number &&
                        it.playerEngine.isPlaying.value && it.playerEngine.playbackState.value == PlaybackState.READY
                }?.let {
                    vm = it
                    found = true
                }
            }
            found
        }
        return vm
    }

    private fun activeSource(engine: PlayerEngine): StreamInfo? = engine.javaClass
        .getDeclaredField("lastStreamInfo").apply { isAccessible = true }.get(engine) as? StreamInfo

    private fun playEpisodeFromDetails(number: Int): PlayerViewModel {
        val label = "S01E%02d".format(number)
        repeat(8) {
            if (compose.onAllNodesWithText(label, substring = true).fetchSemanticsNodes().isNotEmpty()) return@repeat
            val x = (device.displayWidth * 0.78f).toInt()
            val startY = (device.displayHeight * 0.82f).toInt()
            val endY = (device.displayHeight * 0.28f).toInt()
            device.swipe(x, startY, x, endY, 12)
            SystemClock.sleep(300)
            compose.waitForIdle()
        }
        await("episode button $label") {
            compose.onAllNodesWithText(label, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        val started = SystemClock.elapsedRealtime()
        click(label)
        val vm = vmForEpisode(number)
        await("episode $number first frame") {
            val source = activeSource(vm.playerEngine)
            source?.url?.let { URI(it).path?.endsWith("/${73100 + number}.mp4") } == true &&
                vm.playerEngine.playerStats.value.ttffMs > 0
        }
        Log.i("PlaybackV3Fixture", "episode-ready number=$number elapsedMs=${SystemClock.elapsedRealtime() - started} " +
            "ttffMs=${vm.playerEngine.playerStats.value.ttffMs} bufferMs=${vm.playerEngine.playerStats.value.bufferedDurationMs}")
        return vm
    }

    private fun openEpisodePicker(vm: PlayerViewModel) {
        ensureVodControlsVisible(vm)
        val label = app.getString(com.streamvault.feature.playback.R.string.player_episodes)
        await("episode picker control") { compose.onAllNodesWithContentDescription(label).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription(label).performSemanticsAction(SemanticsActions.OnClick) { it() }
    }

    private fun selectAdjacentEpisode(vm: PlayerViewModel, number: Int): Long {
        openEpisodePicker(vm)
        val title = "S01E%02d".format(number)
        await("picker episode $title") { compose.onAllNodesWithText(title, substring = true).fetchSemanticsNodes().isNotEmpty() }
        val started = SystemClock.elapsedRealtime()
        click(title)
        acceptResumePromptIfPresent()
        await("episode $number first frame") {
            vm.currentEpisode.value?.episodeNumber == number && vm.playerEngine.isPlaying.value &&
                vm.playerEngine.playbackState.value == PlaybackState.READY && vm.playerEngine.playerStats.value.ttffMs > 0
        }
        val elapsed = SystemClock.elapsedRealtime() - started
        val source = activeSource(vm.playerEngine) ?: error("No physical episode source")
        assertThat(URI(source.url).path).endsWith("/${73100 + number}.mp4")
        val history = history(vm)
        assertThat(history.contentId).isEqualTo(vm.currentEpisode.value?.id)
        assertThat(history.seriesId).isEqualTo(vm.currentSeries.value?.id)
        Log.i("PlaybackV3Fixture", "episode-handoff number=$number elapsedMs=$elapsed ttffMs=${vm.playerEngine.playerStats.value.ttffMs} " +
            "historyContentId=${history.contentId} historyPositionMs=${history.resumePositionMs}")
        return elapsed
    }

    private fun eventCount(stats: JSONObject, event: String, role: String? = null, program: String? = null): Int {
        val events = stats.getJSONArray("events")
        return (0 until events.length()).count { index ->
            val row = events.getJSONObject(index)
            row.optString("event") == event && (role == null || row.optString("role") == role) &&
                (program == null || row.optString("program") == program || row.optString("episode") == program)
        }
    }

    private fun awaitNativeThumbnailStarts(expected: Int, timeoutMs: Long = 15_000): JSONObject? {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        var stats = server("/stats")
        while (SystemClock.elapsedRealtime() < end) {
            stats = server("/stats")
            if (eventCount(stats, "request-start", role = "native-thumbnail") >= expected) return stats
            SystemClock.sleep(100)
        }
        return null
    }

    private fun timelineScreenArea(timeline: String): TimelineScreenArea {
        val bounds = compose.onNodeWithContentDescription(timeline).fetchSemanticsNode().boundsInRoot
        val location = IntArray(2)
        instrumentation.runOnMainSync {
            compose.activity.window.decorView.findViewById<View>(android.R.id.content).getLocationOnScreen(location)
        }
        return TimelineScreenArea(location[0] + bounds.left, location[1] + bounds.top, bounds.width, bounds.height)
    }

    private fun injectTimelineEvent(action: Int, point: Offset, downTimeMs: Long) {
        val event = MotionEvent.obtain(downTimeMs, SystemClock.uptimeMillis(), action, point.x, point.y, 0).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
        try {
            check(instrumentation.uiAutomation.injectInputEvent(event, true)) { "Could not inject timeline motion event $action" }
        } finally {
            event.recycle()
        }
    }

    private fun beginTimelineGesture(area: TimelineScreenArea, startFraction: Float, targetFraction: Float): Long {
        val downTime = SystemClock.uptimeMillis()
        injectTimelineEvent(MotionEvent.ACTION_DOWN, area.point(startFraction), downTime)
        SystemClock.sleep(40)
        injectTimelineEvent(MotionEvent.ACTION_MOVE, area.point(targetFraction), downTime)
        return downTime
    }

    private fun moveTimelineGesture(area: TimelineScreenArea, targetFraction: Float, downTimeMs: Long) {
        injectTimelineEvent(MotionEvent.ACTION_MOVE, area.point(targetFraction), downTimeMs)
    }

    private fun endTimelineGesture(area: TimelineScreenArea, targetFraction: Float, downTimeMs: Long) {
        injectTimelineEvent(MotionEvent.ACTION_UP, area.point(targetFraction), downTimeMs)
        compose.waitForIdle()
    }

    private fun thumbnailWorkerThreads(): JSONArray {
        val rows = JSONArray()
        Thread.getAllStackTraces().forEach { (thread, stack) ->
            if (stack.any { it.className.contains("SeekThumbnailProvider") || it.className.contains("MediaMetadataRetriever") }) {
                rows.put(JSONObject().put("name", thread.name).put("state", thread.state.name)
                    .put("frames", stack.take(8).joinToString(" | ") { "${it.className}.${it.methodName}" }))
            }
        }
        return rows
    }

    private fun dragLegacyThumbnail(vm: PlayerViewModel): JSONObject {
        ensureVodControlsVisible(vm)
        val timeline = app.getString(com.streamvault.feature.playback.R.string.player_vod_timeline)
        await("R7 VOD timeline") { compose.onAllNodesWithContentDescription(timeline).fetchSemanticsNodes().isNotEmpty() }
        val previousUrl = resolvedPlaybackUrl(vm)
        val previousInfo = resolvedStreamInfo(vm)
        val slowUrl = "$providerUrl/thumbnail/slow.mp4?run=${SystemClock.elapsedRealtime()}"
        val began = SystemClock.elapsedRealtime()
        try {
            setResolvedPlaybackUrl(vm, slowUrl)
            setResolvedStreamInfo(vm, previousInfo?.copy(url = slowUrl, headers = emptyMap(), userAgent = null))
            server("/control?reset=1")
            val area = timelineScreenArea(timeline)
            val duration = vm.playerEngine.duration.value.coerceAtLeast(1L)
            val startFraction = (vm.playerEngine.currentPosition.value.toFloat() / duration).coerceIn(0.02f, 0.98f)
            val firstTarget = (startFraction + 0.08f).coerceAtMost(0.95f)
            val secondTarget = (firstTarget + 0.08f).coerceAtMost(0.98f)
            var downTime: Long? = null
            var first: JSONObject? = null
            var second: JSONObject? = null
            try {
                val gestureDownTime = beginTimelineGesture(area, startFraction, firstTarget)
                downTime = gestureDownTime
                first = awaitNativeThumbnailStarts(1)
                if (first != null) {
                    moveTimelineGesture(area, secondTarget, gestureDownTime)
                    second = awaitNativeThumbnailStarts(2, timeoutMs = 5_000)
                }
            } finally {
                downTime?.let { endTimelineGesture(area, if (second != null) secondTarget else firstTarget, it) }
            }
            await("R7 scrub release clears preview") { !vm.seekPreview.value.visible }
            val releasedAt = SystemClock.elapsedRealtime()
            val samples = JSONArray()
            while (SystemClock.elapsedRealtime() - releasedAt < 8_000) {
                val stats = server("/stats")
                val player = vm.playerEngine.playerStats.value
                samples.put(JSONObject().put("elapsedMs", SystemClock.elapsedRealtime() - releasedAt)
                    .put("nativeActive", stats.getJSONArray("active").let { active -> (0 until active.length()).count { active.getJSONObject(it).optString("role") == "native-thumbnail" } })
                    .put("nativeWorkerThreads", thumbnailWorkerThreads()).put("positionMs", vm.playerEngine.currentPosition.value)
                    .put("state", vm.playerEngine.playbackState.value.name).put("playing", vm.playerEngine.isPlaying.value)
                    .put("bufferMs", player.bufferedDurationMs).put("rebuffers", player.rebufferCount)
                    .put("drops", player.droppedFrames).put("pssKb", Debug.getPss()))
                SystemClock.sleep(250)
            }
            val boundStats = server("/stats")
            val boundActive = boundStats.getJSONArray("active").let { active ->
                (0 until active.length()).count { active.getJSONObject(it).optString("role") == "native-thumbnail" }
            }
            val boundWorkers = thumbnailWorkerThreads()
            val bounded = boundActive == 0 && boundWorkers.length() == 0
            val result = JSONObject().put("declaredCancellationBoundMs", 8_000).put("firstRequestStarted", first != null)
                .put("secondRequestStarted", second != null).put("nativeStarts", eventCount(boundStats, "request-start", "native-thumbnail"))
                .put("nativeEndsAtBound", eventCount(boundStats, "request-end", "native-thumbnail"))
                .put("nativeActiveAtBound", boundActive).put("workersAtBound", boundWorkers).put("withinBound", bounded)
                .put("foregroundSamples", samples).put("previewHiddenAfterRelease", !vm.seekPreview.value.visible)
                .put("elapsedMs", SystemClock.elapsedRealtime() - began)
            File(outputDir, "r7-rapid-scrub.json").writeText(result.toString(2))
            Log.i("PlaybackV3Fixture", "R7 rapidScrub first=${first != null} second=${second != null} withinBound=$bounded")
            return result
        } finally {
            setResolvedPlaybackUrl(vm, previousUrl)
            setResolvedStreamInfo(vm, previousInfo)
        }
    }

    private fun startSlowPreviewAndNavigateAway(vm: PlayerViewModel): JSONObject {
        val previousUrl = resolvedPlaybackUrl(vm)
        val previousInfo = resolvedStreamInfo(vm)
        val slowUrl = "$providerUrl/thumbnail/slow.mp4?navigation=${SystemClock.elapsedRealtime()}"
        try {
            server("/control?reset=1")
            setResolvedPlaybackUrl(vm, slowUrl)
            setResolvedStreamInfo(vm, previousInfo?.copy(url = slowUrl, headers = emptyMap(), userAgent = null))
            vm.updateSeekPreview(170_000)
            val started = awaitNativeThumbnailStarts(1)
            val began = SystemClock.elapsedRealtime()
            device.pressBack()
            await("navigate away from slow preview") {
                compose.onAllNodesWithContentDescription(app.getString(com.streamvault.feature.playback.R.string.player_vod_timeline))
                    .fetchSemanticsNodes().isEmpty()
            }
            val afterDisposeVisible = vm.seekPreview.value.visible
            val samples = JSONArray()
            while (SystemClock.elapsedRealtime() - began < 8_000) {
                val stats = server("/stats")
                samples.put(JSONObject().put("elapsedMs", SystemClock.elapsedRealtime() - began)
                    .put("nativeActive", stats.getJSONArray("active").let { active -> (0 until active.length()).count { active.getJSONObject(it).optString("role") == "native-thumbnail" } })
                    .put("nativeWorkerThreads", thumbnailWorkerThreads()).put("previewVisible", vm.seekPreview.value.visible)
                    .put("previewLoading", vm.seekPreview.value.isLoading))
                SystemClock.sleep(250)
            }
            val finalStats = server("/stats")
            val result = JSONObject().put("nativeStarted", started != null)
                .put("nativeStartCount", started?.let { eventCount(it, "request-start", "native-thumbnail") } ?: 0)
                .put("nativeEndsAtBound", eventCount(finalStats, "request-end", "native-thumbnail"))
                .put("nativeActiveAtBound", finalStats.getJSONArray("active").let { active -> (0 until active.length()).count { active.getJSONObject(it).optString("role") == "native-thumbnail" } })
                .put("previewVisibleAfterDispose", afterDisposeVisible).put("previewVisibleAtBound", vm.seekPreview.value.visible)
                .put("workerThreadsAtBound", thumbnailWorkerThreads()).put("samples", samples)
            result.put("previewClearedAfterDispose", !afterDisposeVisible && !vm.seekPreview.value.visible)
                .put("withinBound", result.getInt("nativeActiveAtBound") == 0 && result.getJSONArray("workerThreadsAtBound").length() == 0)
            File(outputDir, "r7-navigation-away.json").writeText(result.toString(2))
            return result
        } finally {
            setResolvedPlaybackUrl(vm, previousUrl)
            setResolvedStreamInfo(vm, previousInfo)
        }
    }

    private fun history(vm: PlayerViewModel): PlaybackHistory = vm.javaClass.methods
        .single { it.name.substringBefore('$') == "buildPlaybackHistorySnapshot" && it.parameterCount == 2 }
        .invoke(vm, vm.playerEngine.currentPosition.value, vm.playerEngine.duration.value) as PlaybackHistory

    private fun ensureVodControlsVisible(vm: PlayerViewModel) {
        if (!vm.showControls.value) {
            // A centered screen tap reveals VOD controls without activating a focused Play button.
            device.executeShellCommand("input mouse tap ${device.displayWidth / 2} ${device.displayHeight / 2}")
        }
        await("VOD player controls visible") { vm.showControls.value }
        compose.waitForIdle()
    }
    private fun preloadRefresh(vm: PlayerViewModel) {
        vm.javaClass.declaredMethods.single {
            it.name.substringBefore('$') == "clearPreloadWindow" && it.parameterCount == 0
        }.apply { isAccessible = true }.invoke(vm)
        val requestVersion = getter(vm, "getPrepareRequestVersion") as Long
        vm.javaClass.declaredMethods.single {
            it.name.substringBefore('$') == "refreshPreloadWindow" && it.parameterCount == 1
        }.apply { isAccessible = true }.invoke(vm, requestVersion)
    }

    private fun awaitScrubPlayback(vm: PlayerViewModel, fraction: Float, label: String) {
        val engine = vm.playerEngine
        val targetMs = (engine.duration.value * fraction.coerceIn(0f, 1f)).toLong()
        val end = SystemClock.elapsedRealtime() + 45_000
        var lastPosition = engine.currentPosition.value
        var lastState = engine.playbackState.value
        var lastPlaying = engine.isPlaying.value
        while (SystemClock.elapsedRealtime() < end) {
            compose.waitForIdle()
            lastPosition = engine.currentPosition.value
            lastState = engine.playbackState.value
            lastPlaying = engine.isPlaying.value
            if (lastState == PlaybackState.READY && lastPlaying && kotlin.math.abs(lastPosition - targetMs) <= 15_000L) {
                return
            }
            SystemClock.sleep(100)
        }
        device.takeScreenshot(File(outputDir, "timeout-$label.png"))
        device.dumpWindowHierarchy(File(outputDir, "timeout-$label.xml"))
        error("Timed out: $label targetMs=$targetMs positionMs=$lastPosition state=$lastState playing=$lastPlaying")
    }

    private fun dragTimeline(vm: PlayerViewModel, enabled: Boolean, fraction: Float): JSONObject {
        ensureVodControlsVisible(vm)
        val timeline = app.getString(com.streamvault.feature.playback.R.string.player_vod_timeline)
        await("VOD timeline") { compose.onAllNodesWithContentDescription(timeline).fetchSemanticsNodes().isNotEmpty() }
        val previousUrl = resolvedPlaybackUrl(vm)
        val previousInfo = resolvedStreamInfo(vm)
        val engine = vm.playerEngine
        val positionBeforeMs = engine.currentPosition.value
        val durationMs = engine.duration.value
        check(durationMs > 0L) { "VOD timeline has no duration" }
        val targetFraction = fraction.coerceIn(0.02f, 0.98f)
        val targetPositionMs = (durationMs * targetFraction).toLong()
        val startFraction = (positionBeforeMs.toFloat() / durationMs.toFloat()).coerceIn(0.02f, 0.98f)
        if (!enabled) {
            val unsupported = "rtsp://preview-disabled.invalid/live"
            setResolvedPlaybackUrl(vm, unsupported)
            setResolvedStreamInfo(vm, previousInfo?.copy(url = unsupported, streamType = StreamType.RTSP))
        }
        val began = SystemClock.elapsedRealtime()
        var previewMs: Long? = null
        var width = 0
        var height = 0
        var previewVisibleWhileScrubbing = false
        var previewLoadingWhileScrubbing = false
        var previewPositionMs: Long? = null
        val previousBitmap = vm.seekPreview.value.frameBitmap
        val statsBefore = server("/stats")
        val startsBefore = statsBefore.getJSONArray("events").length()
        var area: TimelineScreenArea? = null
        var downTime: Long? = null
        var released = false
        try {
            val gestureArea = timelineScreenArea(timeline)
            area = gestureArea
            val gestureDownTime = beginTimelineGesture(gestureArea, startFraction, targetFraction)
            downTime = gestureDownTime
            val deadline = SystemClock.elapsedRealtime() + 5_000
            while (enabled && SystemClock.elapsedRealtime() < deadline) {
                val preview = vm.seekPreview.value
                if (preview.visible) {
                    previewVisibleWhileScrubbing = true
                    previewPositionMs = preview.positionMs
                    previewLoadingWhileScrubbing = previewLoadingWhileScrubbing || preview.isLoading
                }
                val bitmap = preview.frameBitmap
                if (bitmap != null && bitmap !== previousBitmap) {
                    previewMs = SystemClock.elapsedRealtime() - began
                    width = bitmap.width
                    height = bitmap.height
                    break
                }
                Thread.sleep(25)
            }
            if (!enabled) {
                SystemClock.sleep(250)
                assertThat(vm.seekPreview.value.isLoading).isFalse()
                assertThat(vm.seekPreview.value.frameBitmap).isNull()
            }
            endTimelineGesture(gestureArea, targetFraction, gestureDownTime)
            released = true
        } finally {
            if (!released) {
                val openArea = area
                val openDownTime = downTime
                if (openArea != null && openDownTime != null) {
                    runCatching { endTimelineGesture(openArea, targetFraction, openDownTime) }
                }
            }
            setResolvedPlaybackUrl(vm, previousUrl)
            setResolvedStreamInfo(vm, previousInfo)
        }
        assertThat(vm.seekPreview.value.visible).isFalse()
        val stats = server("/stats")
        val events = stats.getJSONArray("events")
        val thumbnailRequestDelta = JSONArray()
        for (index in startsBefore until events.length()) {
            val event = events.getJSONObject(index)
            if (event.optString("event") == "request-start") {
                thumbnailRequestDelta.put(JSONObject().put("role", event.optString("role"))
                    .put("agent", event.optString("agent")).put("program", event.optString("program")))
            }
        }
        return JSONObject().put("enabled", enabled).put("fraction", targetFraction)
            .put("targetPositionMs", targetPositionMs).put("positionBeforeMs", positionBeforeMs)
            .put("positionAtReleaseMs", engine.currentPosition.value).put("stateAtRelease", engine.playbackState.value.name)
            .put("playingAtRelease", engine.isPlaying.value).put("previewMs", previewMs ?: JSONObject.NULL)
            .put("previewVisibleWhileScrubbing", previewVisibleWhileScrubbing)
            .put("previewLoadingWhileScrubbing", previewLoadingWhileScrubbing).put("previewPositionMs", previewPositionMs ?: JSONObject.NULL)
            .put("previewWidth", width).put("previewHeight", height).put("thumbnailRequestDelta", thumbnailRequestDelta)
            .put("contentType", getter(vm, "getCurrentContentType")?.toString())
            .put("resolvedUrlPath", URI(resolvedPlaybackUrl(vm)).path)
            .put("bufferMs", engine.playerStats.value.bufferedDurationMs)
            .put("rebuffers", engine.playerStats.value.rebufferCount).put("drops", engine.playerStats.value.droppedFrames)
            .put("nativeHeapBytes", Debug.getNativeHeapAllocatedSize())
    }

    private fun capturePlayback(vm: PlayerViewModel, label: String): JSONObject {
        val folder = File(outputDir, label).apply { mkdirs() }
        val samples = JSONArray()
        val hashes = mutableListOf<String>()
        val started = SystemClock.elapsedRealtime()
        val firstPosition = vm.playerEngine.currentPosition.value
        repeat(61) { index ->
            val file = File(folder, "frame-%02d.png".format(index))
            check(device.takeScreenshot(file)) { "Screenshot failed at $index" }
            hashes += sha256(file)
            val stats = vm.playerEngine.playerStats.value
            val sourceEpisode = activeSource(vm.playerEngine)?.url?.let { URI(it).path?.substringAfterLast('/') }
            samples.put(JSONObject().put("elapsedMs", SystemClock.elapsedRealtime() - started)
                .put("positionMs", vm.playerEngine.currentPosition.value).put("state", vm.playerEngine.playbackState.value.name)
                .put("playing", vm.playerEngine.isPlaying.value)
                .put("logicalEpisode", vm.currentEpisode.value?.episodeNumber).put("sourceEpisode", sourceEpisode)
                .put("bufferMs", stats.bufferedDurationMs).put("rebuffers", stats.rebufferCount).put("drops", stats.droppedFrames)
                .put("ttffMs", stats.ttffMs).put("pssKb", Debug.getPss()).put("nativeHeapBytes", Debug.getNativeHeapAllocatedSize()))
            if (index < 60) SystemClock.sleep(2_000)
        }
        val positions = vm.playerEngine.currentPosition.value
        val unique = hashes.toSet().size
        val adjacent = hashes.zipWithNext().count { (a, b) -> a != b }
        val summary = JSONObject().put("label", label).put("frameCount", hashes.size).put("durationMs", SystemClock.elapsedRealtime() - started)
            .put("uniqueHashes", unique).put("adjacentChanges", adjacent).put("positionStartMs", firstPosition)
            .put("positionEndMs", positions).put("positionDeltaMs", positions - firstPosition)
            .put("finalState", vm.playerEngine.playbackState.value.name).put("finalPlaying", vm.playerEngine.isPlaying.value)
            .put("samples", samples)
        File(folder, "summary.json").writeText(summary.toString(2))
        assertThat(positions).isGreaterThan(firstPosition + 90_000)
        assertThat(vm.playerEngine.playbackState.value).isEqualTo(PlaybackState.READY)
        assertThat(vm.playerEngine.isPlaying.value).isTrue()
        Log.i("PlaybackV3Fixture", "capture label=$label frames=${hashes.size} unique=$unique adjacent=$adjacent " +
            "durationMs=${summary.getLong("durationMs")} positionDelta=${positions - firstPosition} " +
            "rebuffers=${vm.playerEngine.playerStats.value.rebufferCount} drops=${vm.playerEngine.playerStats.value.droppedFrames}")
        return summary
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    private suspend fun awaitProviderCap(repository: ProviderRepository, id: Long, expected: Int) {
        withTimeout(30_000) {
            while (repository.getProvider(id)?.maxConnections != expected) delay(100)
        }
    }

    private suspend fun setFixtureCap(repository: ProviderRepository, id: Long, expected: Int) {
        val provider = repository.getProvider(id) ?: error("V3 fixture provider disappeared")
        if (provider.maxConnections != expected) {
            check(repository.updateProvider(provider.copy(maxConnections = expected)).isSuccess) {
                "Could not set V3 fixture connection cap"
            }
        }
        awaitProviderCap(repository, id, expected)
    }

    private suspend fun syncFixture(providerId: Long, providers: ProviderRepository) {
        check(providers.refreshProviderData(providerId, force = true).isSuccess) { "V3 fixture provider refresh failed" }
        val series = component("seriesRepositoryImplProvider") as SeriesRepository
        val categories = series.getCategories(providerId).first()
        check(categories.any { it.name == "V3 Episode Validation" }) { "V3 series category missing" }
        val sync = component("syncManagerProvider") as ProviderSyncCommands
        check(sync.processQueuedXtreamIndexJobs(providerId, section = ContentType.SERIES, force = true).isSuccess)
        val categoryId = categories.single { it.name == "V3 Episode Validation" }.id
        withTimeout(60_000) { series.getSeriesByCategory(providerId, categoryId).first { it.isNotEmpty() } }
        check(sync.processQueuedXtreamIndexJobs(providerId, section = ContentType.LIVE, force = true).isSuccess)
        val channels = component("channelRepositoryImplProvider") as ChannelRepository
        withTimeout(60_000) { channels.getChannels(providerId).first { rows -> rows.any { it.name == "V3 Catch-up Channel" } } }
    }

    private fun openArchive(name: String): PlayerViewModel {
        await("V3 channel row") {
            compose.onAllNodesWithContentDescription("V3 Catch-up Channel", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("V3 Catch-up Channel", substring = true)
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        SystemClock.sleep(800)
        if (compose.onAllNodesWithContentDescription("V3 Catch-up Channel", substring = true).fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithContentDescription("V3 Catch-up Channel", substring = true)
                .performSemanticsAction(SemanticsActions.OnClick) { it() }
        }
        lateinit var vm: PlayerViewModel
        await("V3 live player") {
            instrumentation.runOnMainSync {
                vm = findPlayers(compose.activity.viewModelStore).singleOrNull {
                    getter(it, "getCurrentTitle") == "V3 Catch-up Channel" && it.playerEngine.isPlaying.value
                } ?: return@runOnMainSync
            }
            getter(vm, "getCurrentTitle") == "V3 Catch-up Channel"
        }
        await("V3 programme guide") {
            vm.programHistory.value.count { it.title.startsWith("V3 Program ") } == 3 &&
                vm.currentProgram.value?.title == "V3 Current Program"
        }
        if (!vm.showControls.value) device.pressKeyCode(android.view.KeyEvent.KEYCODE_MENU)
        await("V3 live transport controls") { vm.showControls.value }
        val archiveLabel = app.getString(com.streamvault.feature.playback.R.string.player_archive)
        val archiveAction = androidx.compose.ui.test.hasText(archiveLabel) and hasClickAction()
        if (compose.onAllNodes(archiveAction).fetchSemanticsNodes().isEmpty()) {
            compose.onAllNodes(hasScrollToIndexAction())[0].performScrollToIndex(6)
        }
        await("V3 archive action") { compose.onAllNodes(archiveAction).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(archiveAction).performSemanticsAction(SemanticsActions.OnClick) { it() }
        await("V3 archive item") { compose.onAllNodesWithText("V3 Program $name").fetchSemanticsNodes().isNotEmpty() }
        compose.mainClock.advanceTimeBy(650)
        val selectionStarted = SystemClock.elapsedRealtime()
        compose.onNodeWithText("V3 Program $name").performSemanticsAction(SemanticsActions.OnClick) { it() }
        await("V3 archive first frame") {
            val stream = activeSource(vm.playerEngine)
            stream?.playbackContentKind == PlaybackContentKind.CATCH_UP &&
                getter(vm, "getCurrentTitle") == "V3 Catch-up Channel: V3 Program $name" &&
                vm.playerEngine.playbackState.value == PlaybackState.READY && vm.playerEngine.playerStats.value.ttffMs > 0
        }
        lastArchiveFirstFrameMs = SystemClock.elapsedRealtime() - selectionStarted
        Log.i("PlaybackV3Fixture", "catchup-handoff program=$name firstFrameMs=$lastArchiveFirstFrameMs ttffMs=${vm.playerEngine.playerStats.value.ttffMs}")
        return vm
    }

    private fun backToFixtureChannel() {
        var returned = false
        repeat(5) {
            if (returned) return@repeat
            device.pressBack()
            SystemClock.sleep(400)
            compose.waitForIdle()
            returned = compose.onAllNodesWithContentDescription("V3 Catch-up Channel", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        check(returned) { "Normal Back did not return to the V3 catch-up channel" }
    }

    @Test fun validateNormalUiV3Matrix() = runBlocking {
        enabled()
        check(server("/health").getBoolean("ok")) { "Fresh V3 server is not reachable" }
        outputDir.mkdirs()
        val providers = component("providerRepositoryImplProvider") as ProviderRepository
        val prefs = component("preferencesRepositoryProvider") as PreferencesRepository
        val original = providers.getActiveProvider().first() ?: error("Original provider is missing")
        val originalLast = prefs.lastActiveProviderId.first() ?: original.id
        val fixtureId = providers.addProvider(LegacyProvider(
            name = fixtureName, type = ProviderType.XTREAM_CODES, serverUrl = providerUrl,
            username = "v3-fixture", password = "v3-fixture", maxConnections = 1,
            allowedOutputFormats = listOf("m3u8", "ts"), preferredLiveStreamFormat = LiveStreamFormatMode.HLS,
            isActive = false, xtreamFastSyncEnabled = false
        )).getOrNull() ?: error("V3 fixture provider creation failed")
        val manifest = JSONObject().put("id", fixtureId).put("name", fixtureName)
            .put("originalProviderId", original.id).put("originalLastProviderId", originalLast)
        File(outputDir, "setup-private.json").writeText(manifest.toString(2))
        try {
            server("/control?cap=unknown&reset=1")
            syncFixture(fixtureId, providers)
            assertThat(providers.getProvider(fixtureId)?.maxConnections).isEqualTo(1)
            check(providers.setActiveProvider(fixtureId).isSuccess)
            prefs.setLastActiveProviderId(fixtureId)
            Log.i("PlaybackV3Fixture", "provider-ready id=$fixtureId requestedCap=unknown effectiveCap=1")

            click("Series")
            click("V3 Neighbor Fixture")
            var vm = playEpisodeFromDetails(2)
            val engine = vm.playerEngine
            val unknownStart = server("/stats")
            SystemClock.sleep(12_000)
            val unknownAfter = server("/stats")
            assertThat(eventCount(unknownAfter, "request-start", role = "episode", program = "73101")).isEqualTo(0)
            assertThat(eventCount(unknownAfter, "request-start", role = "episode", program = "73103")).isEqualTo(0)
            assertThat(unknownAfter.getInt("peak")).isAtMost(1)
            Log.i("PlaybackV3Fixture", "episode-preload requestedCap=unknown effectiveCap=1 starts=" +
                unknownStart.getJSONArray("events").length() + " suppressed=true bufferMs=${engine.playerStats.value.bufferedDurationMs}")

            server("/control?cap=1&reset=1")
            check(providers.refreshProviderData(fixtureId, force = true).isSuccess)
            setFixtureCap(providers, fixtureId, 1)
            preloadRefresh(vm)
            SystemClock.sleep(12_000)
            val oneStats = server("/stats")
            assertThat(eventCount(oneStats, "request-start", role = "episode", program = "73101")).isEqualTo(0)
            assertThat(eventCount(oneStats, "request-start", role = "episode", program = "73103")).isEqualTo(0)
            assertThat(oneStats.getInt("peak")).isAtMost(1)
            Log.i("PlaybackV3Fixture", "episode-preload requestedCap=1 effectiveCap=1 suppressed=true bufferMs=${engine.playerStats.value.bufferedDurationMs}")

            server("/control?cap=5&reset=1")
            check(providers.refreshProviderData(fixtureId, force = true).isSuccess)
            setFixtureCap(providers, fixtureId, 5)
            preloadRefresh(vm)
            await("permitted adjacent episode reads") {
                val stats = server("/stats")
                eventCount(stats, "request-start", role = "episode", program = "73101") > 0 &&
                    eventCount(stats, "request-start", role = "episode", program = "73103") > 0
            }
            val multiStats = server("/stats")
            assertThat(multiStats.getInt("peak")).isAtMost(5)
            Log.i("PlaybackV3Fixture", "episode-preload requestedCap=5 effectiveCap=5 previousAndNext=true serverPeak=${multiStats.getInt("peak")}")

            val scrubRows = JSONArray()
            val previewEnabledScrub = dragTimeline(vm, enabled = true, fraction = 0.15f)
            awaitScrubPlayback(vm, 0.15f, "preview-enabled-scrub")
            previewEnabledScrub.put("positionAfterReadyMs", engine.currentPosition.value)
            scrubRows.put(previewEnabledScrub)
            val previewDisabledScrub = dragTimeline(vm, enabled = false, fraction = 0.45f)
            awaitScrubPlayback(vm, 0.45f, "preview-disabled-scrub")
            previewDisabledScrub.put("positionAfterReadyMs", engine.currentPosition.value)
            scrubRows.put(previewDisabledScrub)
            File(outputDir, "scrub-results.json").writeText(scrubRows.toString(2))
            capturePlayback(vm, "preview-enabled")
            // A second no-read scrub mode follows the same slider gesture and engine seek path.
            val beforeDisabledScrubMs = engine.currentPosition.value
            val disabledScrub = dragTimeline(vm, enabled = false, fraction = 0.30f)
            awaitScrubPlayback(vm, 0.30f, "preview-disabled-followup-scrub")
            disabledScrub.put("positionAfterReadyMs", engine.currentPosition.value)
            File(outputDir, "preview-disabled-seek.json").writeText(
                JSONObject().put("positionBeforeMs", beforeDisabledScrubMs).put("gesture", disabledScrub)
                    .put("positionAfterAwaitMs", engine.currentPosition.value).put("durationMs", engine.duration.value)
                    .put("logicalEpisode", vm.currentEpisode.value?.episodeNumber)
                    .put("sourceEpisode", activeSource(engine)?.url?.let { URI(it).path?.substringAfterLast('/') })
                    .toString(2)
            )
            capturePlayback(vm, "preview-disabled")

            // Cancel an active current-episode recovery by navigating in the normal episode picker.
            server("/control?cap=1&reset=1")
            check(providers.refreshProviderData(fixtureId, force = true).isSuccess)
            setFixtureCap(providers, fixtureId, 1)
            preloadRefresh(vm)
            server("/control?failEpisodeId=73102")
            instrumentation.runOnMainSync { engine.seekTo(240_000); engine.play() }
            await("controlled episode recovery request", 30_000) {
                eventCount(server("/stats"), "controlled-503", role = "episode", program = "73102") > 0
            }
            val recoveryStart = server("/stats")
            val failedAttemptsBeforeNavigation = eventCount(recoveryStart, "controlled-503", role = "episode", program = "73102")

            val nextMs = selectAdjacentEpisode(vm, 3)
            SystemClock.sleep(2_000)
            val recoveryAfterNavigation = server("/stats")
            assertThat(eventCount(recoveryAfterNavigation, "controlled-503", role = "episode", program = "73102"))
                .isEqualTo(failedAttemptsBeforeNavigation)
            server("/control?failEpisodeId=none")
            val previousMs = selectAdjacentEpisode(vm, 2)
            val handoff = JSONObject().put("nextFirstFrameMs", nextMs).put("previousFirstFrameMs", previousMs)
                .put("logicalEpisode", vm.currentEpisode.value?.episodeNumber).put("history", JSONObject()
                    .put("contentId", history(vm).contentId).put("positionMs", history(vm).resumePositionMs))
                .put("recoveryFailedAttemptsBeforeNavigation", failedAttemptsBeforeNavigation)
                .put("recoveryFailedAttemptsAfterNavigation", eventCount(recoveryAfterNavigation, "controlled-503", role = "episode", program = "73102"))
            File(outputDir, "episode-handoff.json").writeText(handoff.toString(2))

            val r7RapidScrub = dragLegacyThumbnail(vm)
            val r7Navigation = startSlowPreviewAndNavigateAway(vm)
            // The player Back action lands on series details; one more Back returns to the library.
            device.pressBack()
            await("library after leaving series details") { device.findObject(By.textContains("Live TV")) != null }

            // The channel/program fixture uses fresh UTC guide windows from this server process.
            server("/control?failBeta=false&failEpisodeId=none&cap=5&reset=1")
            check(providers.refreshProviderData(fixtureId, force = true).isSuccess)
            setFixtureCap(providers, fixtureId, 5)
            click("Live TV")
            click("V3 Catch-up Validation")
            vm = openArchive("Beta")
            val catchup = vm.playerEngine
            val selectedProgram = getter(getter(vm, "getPlayerPlaybackContextCoordinator")!!, "getSelectedCatchUpProgram") as Program
            assertThat(selectedProgram.title).isEqualTo("V3 Program Beta")
            assertThat(activeSource(catchup)?.playbackContentKind).isEqualTo(PlaybackContentKind.CATCH_UP)
            await("permitted previous and next catch-up reads", 45_000) {
                val stats = server("/stats")
                stats.toString().contains("\"program\":\"alpha\"") && stats.toString().contains("\"program\":\"gamma\"")
            }
            val archivePreloads = server("/stats")
            assertThat(archivePreloads.getInt("peak")).isAtMost(5)
            assertThat(archivePreloads.toString()).contains("\"program\":\"alpha\"")
            assertThat(archivePreloads.toString()).contains("\"program\":\"gamma\"")
            val catchupHistory = history(vm)
            assertThat(catchupHistory.title).contains("V3 Program Beta")
            Log.i("PlaybackV3Fixture", "catchup-neighbors previousNext=true peak=${archivePreloads.getInt("peak")} " +
                "historyContent=${catchupHistory.contentId} titleCorrect=true")
            server("/control?failBeta=true")
            instrumentation.runOnMainSync { catchup.seekTo(60_000); catchup.play() }
            await("catch-up recovery starts", 20_000) { catchup.playbackState.value == PlaybackState.BUFFERING }
            val archiveRecoveryStart = server("/stats")
            server("/control?failBeta=false")
            await("catch-up recovery completes", 60_000) { catchup.isPlaying.value && catchup.playbackState.value == PlaybackState.READY }
            assertThat(getter(getter(vm, "getPlayerPlaybackContextCoordinator")!!, "getSelectedCatchUpProgram").toString())
                .contains("V3 Program Beta")
            Log.i("PlaybackV3Fixture", "catchup-recovery state=${catchup.playbackState.value} requests=${archiveRecoveryStart.getJSONArray("events").length()}")
            backToFixtureChannel()
            val archiveFirstFrames = JSONObject()
            vm = openArchive("Alpha")
            assertThat(getter(getter(vm, "getPlayerPlaybackContextCoordinator")!!, "getSelectedCatchUpProgram").toString())
                .contains("V3 Program Alpha")
            assertThat(history(vm).title).contains("V3 Program Alpha")
            archiveFirstFrames.put("previousProgramMs", lastArchiveFirstFrameMs)
            backToFixtureChannel()
            vm = openArchive("Gamma")
            assertThat(getter(getter(vm, "getPlayerPlaybackContextCoordinator")!!, "getSelectedCatchUpProgram").toString())
                .contains("V3 Program Gamma")
            assertThat(history(vm).title).contains("V3 Program Gamma")
            archiveFirstFrames.put("nextProgramMs", lastArchiveFirstFrameMs)
            File(outputDir, "catchup-handoff.json").writeText(archiveFirstFrames.toString(2))
            val result = JSONObject().put("fixtureProviderId", fixtureId).put("unknownEffectiveCap", 1).put("oneEffectiveCap", 1)
                .put("multiEffectiveCap", 5).put("scrubs", scrubRows).put("episodeHandoffs", handoff)
                .put("episodeRecoveryCancellation", recoveryAfterNavigation).put("catchupRecovery", archiveRecoveryStart)
                .put("r7RapidScrub", r7RapidScrub).put("r7NavigationAway", r7Navigation)
                .put("catchupHandoffs", archiveFirstFrames)
            val enabledPreviewReady = !scrubRows.getJSONObject(0).isNull("previewMs")
            result.put("enabledPreviewReadyWithin5s", enabledPreviewReady)
            val r7RapidPassed = r7RapidScrub.optBoolean("firstRequestStarted") && r7RapidScrub.optBoolean("secondRequestStarted") && r7RapidScrub.optBoolean("withinBound")
            val r7NavigationPassed = r7Navigation.optBoolean("nativeStarted") && r7Navigation.optBoolean("previewClearedAfterDispose") && r7Navigation.optBoolean("withinBound")
            result.put("r7RapidScrubPassed", r7RapidPassed).put("r7NavigationAwayPassed", r7NavigationPassed)
            File(outputDir, "result.json").writeText(result.toString(2))
            check(enabledPreviewReady && r7RapidPassed && r7NavigationPassed) {
                "V3 thumbnail acceptance failed: enabledPreviewReady=$enabledPreviewReady r7RapidPassed=$r7RapidPassed r7NavigationPassed=$r7NavigationPassed"
            }
        } finally {
            val fixture = providers.getProvider(fixtureId)
            if (fixture != null) {
                check(fixture.name == fixtureName && fixture.serverUrl == providerUrl) { "Refusing to delete a non-fixture provider" }
                if (original.id != fixtureId) check(providers.setActiveProvider(original.id).isSuccess)
                prefs.setLastActiveProviderId(originalLast)
                check(providers.deleteProvider(fixtureId).isSuccess)
            }
            withTimeout(60_000) {
                while (providers.getProvider(fixtureId) != null) delay(100)
            }
            val restoredOriginal = providers.getProvider(original.id)
            check(
                restoredOriginal != null &&
                    restoredOriginal == original.copy(lastSyncedAt = restoredOriginal.lastSyncedAt)
            ) { "Original provider changed during fixture cleanup" }
            assertThat(providers.getActiveProvider().first()?.id).isEqualTo(original.id)
            Log.i("PlaybackV3Fixture", "cleanup fixtureRemoved=true originalProvider=${original.id} activeRestored=true")
        }
    }

    private fun closeToBrowse() {
        repeat(5) {
            device.pressBack()
            SystemClock.sleep(400)
            compose.waitForIdle()
            if (compose.onAllNodesWithText("Series", substring = false).fetchSemanticsNodes().isNotEmpty()) return
        }
    }
}
