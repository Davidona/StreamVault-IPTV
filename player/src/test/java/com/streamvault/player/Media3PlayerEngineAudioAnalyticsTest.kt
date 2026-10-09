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
class Media3PlayerEngineAudioAnalyticsTest {

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
    fun `nonfatal audio analytics errors do not emit playback failures`() = runTest {
        val observedErrors = mutableListOf<PlayerError?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            engine.error.collect(observedErrors::add)
        }
        val listener = analyticsListener()
        val eventTime = AnalyticsListener.EventTime(
            0L,
            Timeline.EMPTY,
            0,
            null,
            0L,
            Timeline.EMPTY,
            0,
            null,
            0L,
            0L
        )

        listener.onAudioSinkError(eventTime, IllegalStateException("timestamp discontinuity"))
        listener.onAudioCodecError(eventTime, IllegalStateException("codec diagnostic"))

        assertThat(observedErrors).isEmpty()
    }

    private fun analyticsListener(): AnalyticsListener {
        val method = Media3PlayerEngine::class.java.getDeclaredMethod("createAnalyticsListener")
        method.isAccessible = true
        return method.invoke(engine) as AnalyticsListener
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
