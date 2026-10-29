@file:androidx.media3.common.util.UnstableApi

package com.streamvault.player.playback

import androidx.media3.exoplayer.hls.HlsMediaSource
import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.PlaybackContentKind
import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.model.StreamType
import java.io.IOException
import java.util.concurrent.TimeoutException
import okhttp3.OkHttpClient
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class HlsTimestampInitializationPolicyTest {
    @Test
    fun `foreground and preload use same live wait and preserve finite defaults`() {
        val provider = PlayerDataSourceFactoryProvider(
            RuntimeEnvironment.getApplication(), OkHttpClient()
        )
        val sourceFactory = PlayerMediaSourceFactory(provider)
        for (kind in PlaybackContentKind.entries) {
            for (preload in listOf(false, true)) {
                val info = StreamInfo(
                    url = "https://example.test/master.m3u8",
                    streamType = StreamType.HLS,
                    playbackContentKind = kind
                )
                val context = PlaybackRetryContext(
                    ResolvedStreamType.HLS,
                    PlayerTimeoutProfile.resolve(info, ResolvedStreamType.HLS, preload),
                    kind
                )
                val factory = sourceFactory.createMediaSourceFactory(
                    info, ResolvedStreamType.HLS, PlayerRetryPolicy(context) { false },
                    preload = preload
                ).second as HlsMediaSource.Factory
                assertThat(waitMs(factory)).isEqualTo(expectedWait(kind))
            }
        }
    }

    @Test
    fun `direct hls factory policy covers tv input and resets finite reuse`() {
        val provider = PlayerDataSourceFactoryProvider(
            RuntimeEnvironment.getApplication(), OkHttpClient()
        )
        val info = StreamInfo("https://example.test/master.m3u8", streamType = StreamType.HLS)
        val dataSourceFactory = provider.createFactory(info, ResolvedStreamType.HLS).second
        val factory = HlsMediaSource.Factory(dataSourceFactory)
        for (kind in listOf(PlaybackContentKind.LIVE, PlaybackContentKind.ON_DEMAND,
            PlaybackContentKind.UNKNOWN, PlaybackContentKind.CATCH_UP)) {
            assertThat(factory.applyPlaybackTimestampInitializationPolicy(kind)).isSameInstanceAs(factory)
            assertThat(waitMs(factory)).isEqualTo(expectedWait(kind))
        }
    }

    @Test
    fun `recognition requires typed dependency failure and its exact library prefix`() {
        assertThat(isHlsTimestampInitializationTimeout(IOException(TimeoutException(
            "TimestampAdjuster failed to initialize in 30000 milliseconds"
        )))).isTrue()
        assertThat(isHlsTimestampInitializationTimeout(IOException(TimeoutException("other wait")))).isFalse()
        assertThat(isHlsTimestampInitializationTimeout(IOException(
            "TimestampAdjuster failed to initialize in 30000 milliseconds"
        ))).isFalse()
        assertThat(isHlsTimestampInitializationTimeout(TimeoutException())).isFalse()
    }

    private fun expectedWait(kind: PlaybackContentKind) = when (kind) {
        PlaybackContentKind.LIVE, PlaybackContentKind.UNKNOWN -> 30_000L
        PlaybackContentKind.ON_DEMAND, PlaybackContentKind.CATCH_UP -> 0L
    }

    private fun waitMs(factory: HlsMediaSource.Factory): Long =
        HlsMediaSource.Factory::class.java.getDeclaredField("timestampAdjusterInitializationTimeoutMs")
            .apply { isAccessible = true }.getLong(factory)
}
