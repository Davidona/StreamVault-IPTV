package com.streamvault.app.ui

import android.app.Application
import android.os.Debug
import android.os.SystemClock
import androidx.lifecycle.ViewModelStore
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.media3.common.Format
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.streamvault.app.MainActivity
import com.streamvault.core.navigation.PlayerNavigationRequest
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.data.sync.ProviderSyncCommands
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.LegacyProvider
import com.streamvault.domain.model.LiveStreamFormatMode
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.repository.ChannelRepository
import com.streamvault.domain.repository.ProviderRepository
import com.streamvault.feature.playback.player.PlayerViewModel
import com.streamvault.player.PlaybackState
import com.streamvault.player.PlayerEngine
import com.streamvault.player.playback.VideoStallDetector
import com.streamvault.player.stats.FrameRateDetector
import com.streamvault.player.stats.PlayerStatsCollector
import dagger.hilt.internal.GeneratedComponentManager
import java.io.File
import javax.inject.Provider
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in fixture: real fullscreen UI, with missing metadata/rounded estimator input injected. */
@RunWith(AndroidJUnit4::class)
class FrameRateFallbackRuntimeTest {
    private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name).apply {
        isAccessible = true
    }.get(owner)

    private fun getter(owner: Any, name: String): Any? = owner.javaClass.methods.single {
        it.name.substringBefore('$') == name && it.parameterCount == 0
    }.invoke(owner)

    private fun players(store: ViewModelStore): List<PlayerViewModel> = buildList {
        for (key in store.keys()) {
            val model = store[key] ?: continue
            if (model is PlayerViewModel) add(model)
            for (entry in model.javaClass.declaredFields.filter { Map::class.java.isAssignableFrom(it.type) }) {
                val map = entry.apply { isAccessible = true }.get(model) as? Map<*, *> ?: continue
                map.values.filterIsInstance<ViewModelStore>().forEach { addAll(players(it)) }
            }
        }
    }.distinct()

    @Test fun fullscreenRoundedTimestampFallback() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("fpsFixture") == "true")
        val url = requireNotNull(args.getString("fixtureUrl"))
        require(java.net.URI(url).host in setOf("10.0.2.2", "127.0.0.1", "localhost"))
        val runId = requireNotNull(args.getString("runId"))
        require(runId.matches(Regex("[A-Za-z0-9_-]{1,40}")))
        val expected = requireNotNull(args.getString("expectedEstimate")).toFloat()
        val duration = args.getString("durationSeconds")?.toInt() ?: 150
        require(duration in 90..300)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = ApplicationProvider.getApplicationContext<Application>()
        val component = (app as GeneratedComponentManager<*>).generatedComponent()
        val prefs = (field(component, "preferencesRepositoryProvider") as Provider<*>).get() as PreferencesRepository
        val providers = (field(component, "providerRepositoryImplProvider") as Provider<*>).get() as ProviderRepository
        val channels = (field(component, "channelRepositoryImplProvider") as Provider<*>).get() as ChannelRepository
        val savedProvider = providers.getActiveProvider().first()?.id
        val savedLastProvider = prefs.lastActiveProviderId.first()
        val savedMatching = prefs.playerRefreshRateMatchingEnabled.first()
        prefs.setPlayerRefreshRateMatchingEnabled(true)
        fun <T> main(action: () -> T): T {
            val result = java.util.concurrent.atomic.AtomicReference<T>()
            instrumentation.runOnMainSync { result.set(action()) }
            return result.get()
        }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var engine: PlayerEngine? = null
        var player: ExoPlayer? = null
        var detector: FrameRateDetector? = null
        var stall: VideoStallDetector? = null
        var formatObserver: AnalyticsListener? = null
        var fixtureProviderId: Long? = null
        var errorJob: Job? = null
        val errors = JSONArray()
        val startedAt = SystemClock.elapsedRealtime()
        val samples = JSONArray()
        val marker = File(app.filesDir, "fps-ready-$runId.json")
        marker.delete()
        try {
            val providerId = providers.addProvider(LegacyProvider(
                name = "FPS fixture $runId", type = ProviderType.XTREAM_CODES,
                serverUrl = "${java.net.URI(url).scheme}://${java.net.URI(url).rawAuthority}",
                username = "v5-fixture", password = "v5-fixture", maxConnections = 5,
                allowedOutputFormats = listOf("m3u8"), preferredLiveStreamFormat = LiveStreamFormatMode.HLS,
                isActive = false, xtreamFastSyncEnabled = false
            )).getOrNull()?.also { fixtureProviderId = it } ?: error("Fixture provider creation failed")
            check(providers.refreshProviderData(providerId, force = true).isSuccess)
            val sync = (field(component, "syncManagerProvider") as Provider<*>).get() as ProviderSyncCommands
            check(sync.processQueuedXtreamIndexJobs(providerId, section = ContentType.LIVE, force = true).isSuccess)
            val streamId = java.net.URI(url).path.substringAfterLast('/').substringBefore('.').toLong()
            val channel = withTimeout(60_000) {
                channels.getChannels(providerId).first { rows -> rows.any { it.streamId == streamId } }
            }.first { it.streamId == streamId }
            check(providers.setActiveProvider(providerId).isSuccess)
            prefs.setLastActiveProviderId(providerId)
            println("FPS fixture provisioned provider=$providerId channel=${channel.id}")
            scenario.onActivity { activity ->
                activity.openPlayer(PlayerNavigationRequest(
                    streamUrl = channel.streamUrl, channelId = channel.epgChannelId,
                    title = channel.name, internalId = channel.id, providerId = providerId, categoryId = channel.categoryId,
                    contentType = "LIVE"
                ))
            }
            withTimeout(60_000) {
                var lastState = ""
                while (engine == null) {
                    scenario.onActivity { activity ->
                        val found = players(activity.viewModelStore)
                        val state = found.joinToString {
                            "${it.playerEngine.playbackState.value}:${it.playerEngine.isPlaying.value}" +
                                ":session=${getter(it, "getPrepareRequestVersion")}" +
                                ":resolved=${getter(it, "getCurrentResolvedStreamInfo") != null}" +
                                ":resolution=${it.playbackResolutionUiState.value.javaClass.simpleName}"
                        }
                        if (state != lastState) { println("FPS fullscreen players=${found.size} state=$state"); lastState = state }
                        engine = found.map { it.playerEngine }.firstOrNull {
                            it.isPlaying.value && it.playbackState.value == PlaybackState.READY
                        }
                    }
                    delay(100)
                }
            }
            val active = requireNotNull(engine)
            errorJob = launch { active.error.collect { if (it != null) errors.put(it.javaClass.simpleName) } }
            lateinit var collector: PlayerStatsCollector
            val declaredFps = main {
                player = field(active, "exoPlayer") as ExoPlayer
                detector = field(active, "frameRateDetector") as FrameRateDetector
                stall = field(active, "videoStallDetector") as VideoStallDetector
                collector = field(active, "statsCollector") as PlayerStatsCollector
                val exo = requireNotNull(player)
                val original = exo.videoFormat?.frameRate
                // Do not change codec timestamps or frame delivery. Only the estimator sees rounding.
                formatObserver = object : AnalyticsListener {
                    override fun onVideoInputFormatChanged(
                        eventTime: AnalyticsListener.EventTime, format: Format,
                        decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?
                    ) { collector.onVideoFormatChanged(format.buildUpon().setFrameRate(Format.NO_VALUE.toFloat()).build()) }
                }.also(exo::addAnalyticsListener)
                exo.videoFormat?.let {
                    collector.onVideoFormatChanged(it.buildUpon().setFrameRate(Format.NO_VALUE.toFloat()).build())
                }
                requireNotNull(detector).reset()
                exo.setVideoFrameMetadataListener { pts, _, _, _ ->
                    requireNotNull(stall).onVideoFrameRendered((pts / 1_000L).coerceAtLeast(0L))
                    requireNotNull(detector).onFrame((pts / 1000.0).roundToLong() * 1000L)
                }
                original
            }
            val injectedAtMs = SystemClock.elapsedRealtime() - startedAt
            fun snapshot(): JSONObject = main {
                val exo = requireNotNull(player)
                val binder = requireNotNull(field(active, "viewBinder"))
                val controller = field(binder, "refreshRateController")
                val view = field(binder, "boundPlayerView") as? android.view.View
                val counters = exo.videoDecoderCounters.also { it?.ensureUpdated() }
                JSONObject()
                    .put("elapsedMs", SystemClock.elapsedRealtime() - startedAt)
                    .put("estimatedFps", requireNotNull(detector).lockedFrameRate)
                    .put("publishedFps", active.videoFormat.value.frameRate)
                    .put("hostRequestedFps", field(binder, "displayFrameRate"))
                    .put("surfaceVotedFps", controller?.let { field(it, "votedRate") })
                    .put("displayHz", view?.display?.mode?.refreshRate)
                    .put("state", active.playbackState.value.name)
                    .put("isPlaying", active.isPlaying.value)
                    .put("bufferMs", exo.totalBufferedDuration)
                    .put("rebufferCount", active.playerStats.value.rebufferCount)
                    .put("rendered", counters?.renderedOutputBufferCount)
                    .put("dropped", counters?.droppedBufferCount)
                    .put("skipped", counters?.skippedOutputBufferCount)
                    .put("decoder", active.playerStats.value.videoDecoderName)
                    .put("cpuMs", android.os.Process.getElapsedCpuTime())
                    .put("javaBytes", Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())
                    .put("pssKb", Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss)
            }
            withTimeout(35_000) {
                while (true) {
                    val s = snapshot()
                    if (abs(s.optDouble("estimatedFps") - expected) < 0.02 &&
                        abs(s.optDouble("publishedFps") - expected) < 0.02 &&
                        abs(s.optDouble("hostRequestedFps") - expected) < 0.02 &&
                        abs(s.optDouble("surfaceVotedFps") - expected) < 0.02) break
                    delay(100)
                }
            }
            val matchedAtMs = SystemClock.elapsedRealtime() - startedAt
            marker.writeText(snapshot().toString(2))
            val deadline = SystemClock.elapsedRealtime() + duration * 1000L
            while (SystemClock.elapsedRealtime() < deadline) {
                samples.put(snapshot())
                delay(1000)
            }
            val result = JSONObject().put("runId", runId).put("fixtureUrl", url)
                .put("expectedEstimate", expected).put("declaredDecoderFps", declaredFps)
                .put("roundedEstimatorInput", true).put("missingMetadataInjected", true)
                .put("injectedAtMs", injectedAtMs).put("matchedAtMs", matchedAtMs)
                .put("durationSeconds", duration)
                .put("liveIdentityPresent", main { (field(active, "lastStreamInfo") as? StreamInfo)?.livePlaybackIdentity != null })
                .put("detectorImplementation", if (requireNotNull(detector).javaClass.declaredFields.any { it.name == "intervals" }) "windowed" else "minimum")
                .put("errors", errors)
                .put("samples", samples).put("final", snapshot())
            File(app.filesDir, "fps-result-$runId.json").writeText(result.toString(2))
            check(active.isPlaying.value && active.playbackState.value == PlaybackState.READY)
            check(errors.length() == 0)
            check(active.playerStats.value.rebufferCount == 0)
        } finally {
            errorJob?.cancelAndJoin()
            main {
                formatObserver?.let { player?.removeAnalyticsListener(it) }
                player?.setVideoFrameMetadataListener { pts, _, _, _ ->
                    stall?.onVideoFrameRendered((pts / 1000L).coerceAtLeast(0L))
                    detector?.onFrame(pts)
                }
                engine?.stop()
            }
            scenario.close()
            try {
                fixtureProviderId?.let { check(providers.deleteProvider(it).isSuccess) }
            } finally {
                savedProvider?.let { providers.setActiveProvider(it) }
                if (savedLastProvider != null) prefs.setLastActiveProviderId(savedLastProvider)
                else {
                    @Suppress("UNCHECKED_CAST")
                    val store = prefs.javaClass.getDeclaredMethod("getPreferencesDataStore").apply {
                        isAccessible = true
                    }.invoke(prefs) as DataStore<Preferences>
                    store.edit { it.remove(longPreferencesKey("last_active_provider_id")) }
                }
                prefs.setPlayerRefreshRateMatchingEnabled(savedMatching)
            }
        }
    }
}
