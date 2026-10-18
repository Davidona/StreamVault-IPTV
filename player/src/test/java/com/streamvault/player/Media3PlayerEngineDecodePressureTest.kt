package com.streamvault.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DecoderCounters
import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.PlaybackCompatibilityKey
import com.streamvault.domain.repository.PlaybackCompatibilityRepository
import com.streamvault.player.playback.ResolvedStreamType
import com.streamvault.player.tracks.PlayerTrackController
import java.lang.reflect.Proxy
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Media3PlayerEngineDecodePressureTest {
    private val context = RuntimeEnvironment.getApplication()
    private val engine = Media3PlayerEngine(context, OkHttpClient(), NoOpRepository,
        AudioCompatibilityMemoryStore(context), PlaybackSupportSnapshotStore(context))
    private val formats = listOf(format("high", 2160, 41_000_000), format("mid", 1080, 9_000_000), format("low", 720, 3_000_000))
    private val group = TrackGroup("ladder", *formats.toTypedArray())
    private val tracks = Tracks(listOf(Tracks.Group(group, true, IntArray(3) { C.FORMAT_HANDLED }, booleanArrayOf(true, false, false))))
    private val counters = DecoderCounters()
    private var params = TrackSelectionParameters.Builder(context).setViewportSize(3840, 2160, true).build()
    private val player = Proxy.newProxyInstance(ExoPlayer::class.java.classLoader, arrayOf(ExoPlayer::class.java)) { _, method, args ->
        when (method.name) {
            "getCurrentTracks" -> tracks
            "getVideoFormat" -> formats[0]
            "getTrackSelectionParameters" -> params
            "setTrackSelectionParameters" -> { params = args!![0] as TrackSelectionParameters; null }
            "getVideoDecoderCounters" -> counters
            "isPlaying", "getPlayWhenReady" -> true
            "getPlaybackState" -> Player.STATE_READY
            "getTotalBufferedDuration" -> 5_000L
            "getApplicationLooper" -> android.os.Looper.getMainLooper()
            else -> when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                java.lang.Float.TYPE -> 0f
                else -> null
            }
        }
    } as ExoPlayer
    private fun format(id: String, height: Int, bitrate: Int) = Format.Builder().setId(id).setSampleMimeType("video/avc")
        .setHeight(height).setWidth(height * 16 / 9).setAverageBitrate(bitrate).build()
    private fun field(name: String) = Media3PlayerEngine::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun install(type: ResolvedStreamType = ResolvedStreamType.HLS) {
        field("exoPlayer").set(engine, player)
        field("currentResolvedStreamType").set(engine, type)
        (field("trackController").get(engine) as PlayerTrackController).onTracksChanged(tracks)
    }
    private fun tick(t: Long) {
        counters.renderedOutputBufferCount = (t * 10).toInt()
        counters.droppedBufferCount = (t * 50).toInt()
        Media3PlayerEngine::class.java.getDeclaredMethod("updateAdaptiveDecodePressure", Long::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(engine, t * 1_000)
    }
    @After fun close() { engine.release() }

    @Test fun `pressure changes selection on the existing player for adaptive formats`() {
        for (type in listOf(ResolvedStreamType.HLS, ResolvedStreamType.DASH, ResolvedStreamType.SMOOTH_STREAMING)) {
            install(type)
            (0L..21).forEach(::tick)
            assertThat(params.overrides[group]?.trackIndices.orEmpty()).containsExactly(1, 2)
            assertThat(field("exoPlayer").get(engine)).isSameInstanceAs(player)
            engine.stop()
            assertThat(params.overrides).isEmpty()
        }
    }

    @Test fun `progressive raw TS RTSP and unresolved sources do not get quality constraints`() {
        for (type in listOf(ResolvedStreamType.PROGRESSIVE, ResolvedStreamType.MPEG_TS_LIVE, ResolvedStreamType.RTSP, ResolvedStreamType.UNKNOWN)) {
            install(type)
            (0L..35).forEach(::tick)
            assertThat(params.overrides).isEmpty()
            engine.stop()
        }
    }

    @Test fun `manual choice removes policy subset and survives further pressure`() {
        install()
        (0L..21).forEach(::tick)
        engine.selectVideoTrack("high")
        assertThat(params.overrides[group]?.trackIndices).containsExactly(0)
        (22L..55).forEach(::tick)
        assertThat(params.overrides[group]?.trackIndices).containsExactly(0)
    }

    @Test fun `seek discards incomplete observation windows`() {
        install()
        (0L..15).forEach(::tick)
        engine.seekTo(20_000)
        (16L..24).forEach(::tick)
        assertThat(params.overrides).isEmpty()
    }

    private object NoOpRepository : PlaybackCompatibilityRepository {
        override suspend fun getKnownBadRecords(deviceFingerprint: String, streamType: String, videoMimeType: String, resolutionBucket: String) = emptyList<com.streamvault.domain.model.PlaybackCompatibilityRecord>()
        override suspend fun recordFailure(key: PlaybackCompatibilityKey, failureType: String, at: Long) = Unit
        override suspend fun recordSuccess(key: PlaybackCompatibilityKey, at: Long) = Unit
        override suspend fun prune(maxRecords: Int, olderThanMs: Long) = Unit
    }
}
