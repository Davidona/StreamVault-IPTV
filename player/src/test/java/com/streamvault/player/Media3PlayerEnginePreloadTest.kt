package com.streamvault.player

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.PlaybackCompatibilityKey
import com.streamvault.domain.repository.PlaybackCompatibilityRepository
import com.streamvault.domain.model.StreamInfo
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class Media3PlayerEnginePreloadTest {

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
    fun `preload window submitted before player creation is retained as data only and can be cleared`() {
        val window = PlayerPreloadWindow(
            items = listOf(
                PlayerPreloadItem(
                    key = "current",
                    streamInfo = StreamInfo(url = "https://example.com/current.mp4"),
                    contentType = PlayerPreloadContentType.VOD
                )
            ),
            currentIndex = 0
        )

        engine.preloadWindow(window)

        val pendingField = Media3PlayerEngine::class.java
            .getDeclaredField("pendingPreloadWindow")
            .apply { isAccessible = true }
        assertThat(pendingField.get(engine)).isEqualTo(window)

        engine.clearPreloadWindow()

        assertThat(pendingField.get(engine)).isNull()
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
