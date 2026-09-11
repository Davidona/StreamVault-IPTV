package com.streamvault.player

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.PlaybackCompatibilityKey
import com.streamvault.domain.repository.PlaybackCompatibilityRepository
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PlayerEngineFeatureConfigurationTest {

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
    fun `feature configuration APIs update Media3 audio focus and multiview resolution settings`() {
        val featureEngine: PlayerEngine = engine

        featureEngine.setAudioFocusBypassed(true)
        featureEngine.setResolutionConstrainedForMultiView(true)

        assertThat(engine.bypassAudioFocus).isTrue()
        assertThat(engine.constrainResolutionForMultiView).isTrue()
        assertThat(featureEngine.chapters.value).isEmpty()
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
