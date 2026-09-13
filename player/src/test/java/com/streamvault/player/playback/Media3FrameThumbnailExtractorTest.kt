package com.streamvault.player.playback

import android.graphics.Bitmap
import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.model.StreamType
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class Media3FrameThumbnailExtractorTest {

    @Test
    fun `loads one bucket once and closes the extraction session`() = runTest {
        val expectedBitmap = Bitmap.createBitmap(800, 400, Bitmap.Config.ARGB_8888)
        val sessionFactory = RecordingFrameThumbnailSessionFactory(expectedBitmap)
        val extractor = Media3FrameThumbnailExtractor(
            context = RuntimeEnvironment.getApplication(),
            okHttpClient = OkHttpClient(),
            sessionFactory = sessionFactory
        )
        val request = FrameThumbnailRequest(
            streamInfo = StreamInfo(
                url = "https://example.test/video.mp4",
                streamType = StreamType.PROGRESSIVE
            ),
            isLive = false
        )

        val first = extractor.loadFrame(request, positionMs = 12_000L)
        val second = extractor.loadFrame(request, positionMs = 12_999L)

        assertThat(first).isNotNull()
        assertThat(second).isSameInstanceAs(first)
        assertThat(first!!.width).isEqualTo(480)
        assertThat(first.height).isEqualTo(240)
        assertThat(sessionFactory.createCount).isEqualTo(1)
        assertThat(sessionFactory.sessions.single().positions).containsExactly(10_000L)
        assertThat(sessionFactory.sessions.single().closeCount).isEqualTo(1)
    }

    @Test
    fun `returns null and closes the session when extraction fails`() = runTest {
        val sessionFactory = RecordingFrameThumbnailSessionFactory(frame = null)
        val extractor = Media3FrameThumbnailExtractor(
            context = RuntimeEnvironment.getApplication(),
            okHttpClient = OkHttpClient(),
            sessionFactory = sessionFactory
        )
        val request = FrameThumbnailRequest(
            streamInfo = StreamInfo(
                url = "https://example.test/video.mp4",
                streamType = StreamType.PROGRESSIVE
            ),
            isLive = false
        )

        assertThat(extractor.loadFrame(request, positionMs = 0L)).isNull()
        assertThat(sessionFactory.sessions.single().closeCount).isEqualTo(1)
    }

    private class RecordingFrameThumbnailSessionFactory(
        private val frame: Bitmap?
    ) : FrameThumbnailSessionFactory {
        val sessions = mutableListOf<RecordingFrameThumbnailSession>()
        var createCount: Int = 0

        override fun create(request: FrameThumbnailRequest): FrameThumbnailSession {
            createCount++
            return RecordingFrameThumbnailSession(frame).also(sessions::add)
        }
    }

    private class RecordingFrameThumbnailSession(
        private val frame: Bitmap?
    ) : FrameThumbnailSession {
        val positions = mutableListOf<Long>()
        var closeCount: Int = 0

        override suspend fun frameAt(positionMs: Long): Bitmap? {
            positions += positionMs
            return frame
        }

        override fun close() {
            closeCount++
        }
    }
}
