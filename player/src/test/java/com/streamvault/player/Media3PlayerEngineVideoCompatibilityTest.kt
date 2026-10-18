package com.streamvault.player

import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.PlaybackCompatibilityKey
import com.streamvault.domain.repository.PlaybackCompatibilityRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@UnstableApi
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class Media3PlayerEngineVideoCompatibilityTest {

    private val engine = Media3PlayerEngine(
        context = RuntimeEnvironment.getApplication(),
        okHttpClient = OkHttpClient(),
        playbackCompatibilityRepository = NoOpPlaybackCompatibilityRepository,
        audioCompatibilityMemoryStore = AudioCompatibilityMemoryStore(RuntimeEnvironment.getApplication()),
        playbackSupportSnapshotStore = PlaybackSupportSnapshotStore(RuntimeEnvironment.getApplication())
    )

    @After
    fun tearDown() {
        engine.release()
    }

    @Test
    fun `auto video renderer receives compatibility selector with and without av sync`() {
        for (sync in listOf(false, true)) {
            engine.setAudioVideoSyncEnabled(sync)
            val method = Media3PlayerEngine::class.java.getDeclaredMethod("buildRenderersFactory")
            method.isAccessible = true
            val factory = method.invoke(engine) as androidx.media3.exoplayer.DefaultRenderersFactory
            val renderers = factory.createRenderers(
                android.os.Handler(android.os.Looper.getMainLooper()),
                object : androidx.media3.exoplayer.video.VideoRendererEventListener {},
                object : androidx.media3.exoplayer.audio.AudioRendererEventListener {},
                androidx.media3.exoplayer.text.TextOutput {},
                androidx.media3.exoplayer.metadata.MetadataOutput {}
            )
            val video = renderers.filterIsInstance<androidx.media3.exoplayer.video.MediaCodecVideoRenderer>().single()
            val field = androidx.media3.exoplayer.mediacodec.MediaCodecRenderer::class.java
                .getDeclaredField("mediaCodecSelector").apply { isAccessible = true }
            assertThat(field.get(video)).isInstanceOf(
                com.streamvault.player.playback.PlaybackVideoCodecCompatibility::class.java
            )
            renderers.forEach { it.release() }
        }
    }

    private object NoOpPlaybackCompatibilityRepository : PlaybackCompatibilityRepository {
        override suspend fun getKnownBadRecords(
            deviceFingerprint: String,
            streamType: String,
            videoMimeType: String,
            resolutionBucket: String
        ) = emptyList<com.streamvault.domain.model.PlaybackCompatibilityRecord>()

        override suspend fun recordFailure(key: PlaybackCompatibilityKey, failureType: String, at: Long) = Unit

        override suspend fun recordSuccess(key: PlaybackCompatibilityKey, at: Long) = Unit

        override suspend fun prune(maxRecords: Int, olderThanMs: Long) = Unit
    }
}
