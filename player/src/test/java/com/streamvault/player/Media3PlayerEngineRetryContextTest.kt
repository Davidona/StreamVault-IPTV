package com.streamvault.player

import android.content.Context
import androidx.media3.common.C
import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.PlaybackCompatibilityKey
import com.streamvault.domain.model.PlaybackContentKind
import com.streamvault.domain.model.PlaybackCompatibilityRecord
import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.model.StreamType
import com.streamvault.domain.repository.PlaybackCompatibilityRepository
import com.streamvault.player.playback.PlaybackPreparationPlan
import kotlinx.coroutines.ExperimentalCoroutinesApi
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class Media3PlayerEngineRetryContextTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val engine = Media3PlayerEngine(
        context = context,
        okHttpClient = OkHttpClient(),
        playbackCompatibilityRepository = NoOpPlaybackCompatibilityRepository,
        audioCompatibilityMemoryStore = AudioCompatibilityMemoryStore(context),
        playbackSupportSnapshotStore = PlaybackSupportSnapshotStore(context)
    )

    @After
    fun tearDown() {
        engine.release()
    }

    @Test
    fun `explicit HLS VOD intent selects the VOD retry budget in the engine`() {
        val streamInfo = StreamInfo(
            url = "https://example.test/movie/master.m3u8",
            streamType = StreamType.HLS,
            playbackContentKind = PlaybackContentKind.ON_DEMAND
        )
        val planBuilder = Media3PlayerEngine::class.java.declaredMethods.single {
            it.name == "buildPlaybackPreparationPlan" && it.parameterCount == 3
        }.apply { isAccessible = true }

        val plan = planBuilder.invoke(engine, streamInfo, false, { false }) as PlaybackPreparationPlan

        assertThat(plan.retryContext.isLive).isFalse()
        assertThat(plan.retryPolicy.getMinimumLoadableRetryCount(C.DATA_TYPE_MANIFEST)).isEqualTo(3)
    }

    private object NoOpPlaybackCompatibilityRepository : PlaybackCompatibilityRepository {
        override suspend fun getKnownBadRecords(
            deviceFingerprint: String,
            streamType: String,
            videoMimeType: String,
            resolutionBucket: String
        ): List<PlaybackCompatibilityRecord> = emptyList()

        override suspend fun recordFailure(key: PlaybackCompatibilityKey, failureType: String, at: Long) = Unit

        override suspend fun recordSuccess(key: PlaybackCompatibilityKey, at: Long) = Unit

        override suspend fun prune(maxRecords: Int, olderThanMs: Long) = Unit
    }
}
