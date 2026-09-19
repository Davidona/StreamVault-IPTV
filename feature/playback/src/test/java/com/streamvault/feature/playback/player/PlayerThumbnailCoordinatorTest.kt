package com.streamvault.feature.playback.player

import android.graphics.Bitmap
import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.model.StreamType
import com.streamvault.player.playback.FrameThumbnailExtractor
import com.streamvault.player.playback.FrameThumbnailRequest
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlayerThumbnailCoordinatorTest {

    private val media3Extractor: FrameThumbnailExtractor = mock()
    private val legacyProvider: SeekThumbnailProvider = mock()
    private val coordinator = PlayerThumbnailCoordinator(media3Extractor, legacyProvider)
    private val streamInfo = StreamInfo(
        url = "https://example.test/video.mp4",
        streamType = StreamType.PROGRESSIVE
    )

    @Test
    fun `returns Media3 frame without invoking legacy backend`() = runTest {
        val bitmap: Bitmap = mock()
        whenever(media3Extractor.supports(any())).thenReturn(true)
        whenever(media3Extractor.loadFrame(any(), eq(10_000L))).thenReturn(bitmap)

        val result = coordinator.loadFrame(
            streamUrl = streamInfo.url,
            streamInfo = streamInfo,
            isLive = false,
            positionMs = 10_000L
        )

        assertThat(result).isSameInstanceAs(bitmap)
        verify(legacyProvider, never()).loadFrame(any(), any())
    }

    @Test
    fun `falls back to legacy backend when Media3 extraction fails`() = runTest {
        val bitmap: Bitmap = mock()
        whenever(media3Extractor.supports(any())).thenReturn(true)
        whenever(media3Extractor.loadFrame(any(), eq(10_000L))).thenReturn(null)
        whenever(legacyProvider.supportsFrameExtraction(streamInfo.url)).thenReturn(true)
        whenever(legacyProvider.loadFrame(streamInfo.url, 10_000L)).thenReturn(bitmap)

        val result = coordinator.loadFrame(
            streamUrl = streamInfo.url,
            streamInfo = streamInfo,
            isLive = false,
            positionMs = 10_000L
        )

        assertThat(result).isSameInstanceAs(bitmap)
        verify(legacyProvider).loadFrame(streamInfo.url, 10_000L)
    }

    @Test
    fun `falls back to legacy backend when Media3 does not support the request`() = runTest {
        val bitmap: Bitmap = mock()
        whenever(media3Extractor.supports(any())).thenReturn(false)
        whenever(legacyProvider.supportsFrameExtraction(streamInfo.url)).thenReturn(true)
        whenever(legacyProvider.loadFrame(streamInfo.url, 10_000L)).thenReturn(bitmap)

        val result = coordinator.loadFrame(
            streamUrl = streamInfo.url,
            streamInfo = streamInfo,
            isLive = false,
            positionMs = 10_000L
        )

        assertThat(result).isSameInstanceAs(bitmap)
        verify(media3Extractor, never()).loadFrame(any(), any())
        verify(legacyProvider).loadFrame(streamInfo.url, 10_000L)
    }

    @Test
    fun `clears both thumbnail backend caches`() {
        coordinator.clearCache()

        verify(media3Extractor).clearCache()
        verify(legacyProvider).clearCache()
    }
}
