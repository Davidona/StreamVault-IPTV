@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.streamvault.player.stats

import androidx.media3.exoplayer.ExoPlayer
import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.VideoFormat
import com.streamvault.player.PlaybackState
import com.streamvault.player.PlayerStats
import java.lang.reflect.Proxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Test

class PlayerStatsCollectorTest {
    @Test fun `refill stats stay live without isPlaying and stop when paused or idle`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var bufferedMs = 200L
            var bufferReads = 0
            var positionReads = 0
            val player = Proxy.newProxyInstance(ExoPlayer::class.java.classLoader, arrayOf(ExoPlayer::class.java)) {
                    _, method, _ ->
                when (method.name) {
                    "getTotalBufferedDuration" -> { bufferReads++; bufferedMs }
                    "getCurrentPosition" -> { positionReads++; 1_000L }
                    "getDuration" -> 60_000L
                    else -> error("Unexpected player access: ${method.name}")
                }
            } as ExoPlayer
            val state = MutableStateFlow(PlaybackState.BUFFERING)
            val stats = MutableStateFlow(PlayerStats())
            val collector = PlayerStatsCollector(
                scopeProvider = { backgroundScope },
                currentPosition = MutableStateFlow(0L), duration = MutableStateFlow(0L),
                videoFormat = MutableStateFlow(VideoFormat(0, 0)), playerStats = stats,
                playbackState = state, clockMs = { testScheduler.currentTime }
            ).also { it.bind { player } }
            collector.updatePlaybackActivity(isPlaying = false, playWhenReady = true)
            runCurrent()
            assertThat(stats.value.bufferedDurationMs).isEqualTo(200)
            bufferedMs = 2_700L
            advanceTimeBy(500)
            runCurrent()
            assertThat(stats.value.bufferedDurationMs).isEqualTo(2_700)
            assertThat(positionReads).isEqualTo(0)
            val beforeDuplicateStart = bufferReads
            collector.updatePlaybackActivity(isPlaying = false, playWhenReady = true)
            runCurrent()
            assertThat(bufferReads).isEqualTo(beforeDuplicateStart)
            collector.updatePlaybackActivity(isPlaying = false, playWhenReady = false)
            advanceTimeBy(2_000)
            runCurrent()
            assertThat(bufferReads).isEqualTo(beforeDuplicateStart)
            state.value = PlaybackState.READY
            collector.updatePlaybackActivity(isPlaying = true, playWhenReady = true)
            runCurrent()
            assertThat(positionReads).isGreaterThan(0)
            state.value = PlaybackState.IDLE
            collector.updatePlaybackActivity(isPlaying = false, playWhenReady = true)
            val stoppedReads = bufferReads
            advanceTimeBy(2_000)
            runCurrent()
            assertThat(bufferReads).isEqualTo(stoppedReads)
        } finally {
            Dispatchers.resetMain()
        }
    }
}
