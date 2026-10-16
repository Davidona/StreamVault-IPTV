package com.streamvault.app.ui

import android.app.Activity
import android.app.Application
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.streamvault.app.MainActivity
import com.streamvault.domain.model.PlaybackContentKind
import com.streamvault.domain.repository.MovieRepository
import com.streamvault.domain.repository.ProviderRepository
import com.streamvault.player.PlaybackState
import com.streamvault.player.PlayerEngine
import dagger.hilt.internal.GeneratedComponentManager
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Provider
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Short, read-only playback probe for candidate locations on the active provider. */
@RunWith(AndroidJUnit4::class)
class V6aProviderPlaybackProbeRuntimeTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    private fun component(name: String): Any {
        val generated = (app as GeneratedComponentManager<*>).generatedComponent()
        return (generated.javaClass.getDeclaredField(name).apply { isAccessible = true }
            .get(generated) as Provider<*>).get()!!
    }

    @Test
    fun probeCandidateLocations() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("v6aProviderProbe") == "true")
        val ids = requireNotNull(InstrumentationRegistry.getArguments().getString("v6aMovieIds"))
            .split(',').map(String::trim).filter(String::isNotEmpty).map(String::toLong)
        require(ids.size in 2..4)
        val holdSeconds = InstrumentationRegistry.getArguments().getString("v6aHoldSeconds")
            ?.toLongOrNull() ?: 0L
        require(holdSeconds in 0..300)
        val providers = component("providerRepositoryImplProvider") as ProviderRepository
        val movies = component("movieRepositoryImplProvider") as MovieRepository
        val providerId = providers.getActiveProvider().first()?.id ?: error("No active provider")
        val engine = component("provideMainPlayerEngineProvider") as PlayerEngine
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var render: android.view.View? = null
        scenario.onActivity { activity: Activity ->
            render = engine.createRenderView(activity, com.streamvault.player.PlayerSurfaceResizeMode.FIT)
            activity.setContentView(render)
            engine.bindRenderView(render!!, com.streamvault.player.PlayerSurfaceResizeMode.FIT)
        }
        try {
            for ((index, id) in ids.withIndex()) {
                val movie = movies.getMovie(id) ?: error("Missing movie $id")
                val detail = movies.getMovieDetails(providerId, id).getOrNull() ?: movie
                val source = movies.getStreamInfo(detail).getOrNull() ?: error("Unresolved movie $id")
                val started = SystemClock.elapsedRealtime()
                var firstFrameReady = false
                val main = { action: () -> Unit -> instrumentation.runOnMainSync(action) }
                main {
                    engine.prepare(source.copy(
                        title = detail.name,
                        playbackContentKind = PlaybackContentKind.ON_DEMAND
                    ))
                    engine.play()
                }
                val result = runCatching {
                    withTimeout(45_000) {
                        while (engine.playerStats.value.ttffMs <= 0L ||
                            engine.playbackState.value != PlaybackState.READY || !engine.isPlaying.value
                        ) delay(100)
                    }
                    firstFrameReady = true
                    val stats = engine.playerStats.value
                    "id=$id,stream=${movie.streamId},name=${detail.name},ext=${detail.containerExtension},duration=${detail.durationSeconds}," +
                        "codec=${stats.videoCodec},audio=${stats.audioCodec},size=${stats.width}x${stats.height},bitrate=${stats.videoBitrate}," +
                        "ttff=${stats.ttffMs},elapsed=${SystemClock.elapsedRealtime() - started},state=${engine.playbackState.value}"
                }.getOrElse { error ->
                    "id=$id,stream=${movie.streamId},name=${detail.name},ext=${detail.containerExtension},error=${error.javaClass.simpleName}:${error.message}"
                }
                android.util.Log.i("V6aProviderPlayback", result)
                if (index == 0 && firstFrameReady && holdSeconds > 0L) {
                    val holdStarted = SystemClock.elapsedRealtime()
                    var previousPosition = engine.currentPosition.value
                    while (SystemClock.elapsedRealtime() - holdStarted < holdSeconds * 1_000L) {
                        delay(2_000)
                        val position = engine.currentPosition.value
                        check(engine.playbackState.value == PlaybackState.READY && engine.isPlaying.value) {
                            "Playback became unhealthy during hold: state=${engine.playbackState.value}, playing=${engine.isPlaying.value}"
                        }
                        check(position > previousPosition) {
                            "Playback position stopped advancing during hold: previous=$previousPosition, current=$position"
                        }
                        previousPosition = position
                        android.util.Log.i(
                            "V6aProviderPlayback",
                            "hold id=$id elapsedMs=${SystemClock.elapsedRealtime() - holdStarted} positionMs=$position state=${engine.playbackState.value}"
                        )
                    }
                }
                main { engine.stop() }
                SystemClock.sleep(1_000)
            }
        } finally {
            instrumentation.runOnMainSync {
                engine.release()
                render?.let { engine.releaseRenderView(it) }
            }
            scenario.close()
        }
    }
}
