package com.streamvault.app.live

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.StreamInfo
import com.streamvault.feature.live.api.LiveMultiViewStatus
import com.streamvault.feature.live.api.LivePreviewOrigin
import com.streamvault.feature.playback.preview.PreviewHandoffSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppLiveAdaptersTest {
    @Test
    fun previewStreamPreparer_returnsDelegateResultUnchanged() = runTest {
        val input = StreamInfo(url = "https://example.test/input.m3u8")
        val expected = Result.Success(StreamInfo(url = "https://example.test/prepared.m3u8"))
        var received: StreamInfo? = null
        val adapter = AppLivePreviewStreamPreparer { streamInfo ->
            received = streamInfo
            expected
        }

        val actual = adapter.prepare(input)

        assertThat(received).isSameInstanceAs(input)
        assertThat(actual).isSameInstanceAs(expected)
    }

    @Test
    fun surfaceRefresh_delegatesExactlyOnce() = runTest {
        var calls = 0
        val adapter = AppLiveSurfaceRefreshAdapter { calls++ }

        adapter.refreshTvInputCatalog()

        assertThat(calls).isEqualTo(1)
    }

    @Test
    fun multiViewStatus_mapsOccupiedSlotsAndCompactCapacity() = runTest {
        val slots = MutableStateFlow<List<Channel?>>(List(4) { null })
        val centeredCompactLayout = MutableStateFlow(false)
        val adapter = AppLiveMultiViewStatusAdapter(slots, centeredCompactLayout)
        val statuses = mutableListOf<LiveMultiViewStatus>()
        val collection = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            adapter.status.take(3).toList(statuses)
        }

        slots.value = listOf(Channel(id = 7L, name = "News", providerId = 3L), null, null, null)
        centeredCompactLayout.value = true
        collection.join()

        assertThat(statuses).containsExactly(
            LiveMultiViewStatus(channelCount = 0, slotCapacity = 4),
            LiveMultiViewStatus(channelCount = 1, slotCapacity = 4),
            LiveMultiViewStatus(channelCount = 1, slotCapacity = 2),
        ).inOrder()
    }

    @Test
    fun previewOriginMapping_preservesBothHandoffDirections() {
        assertThat(LivePreviewOrigin.HOME.toPreviewHandoffSource())
            .isEqualTo(PreviewHandoffSource.HOME)
        assertThat(LivePreviewOrigin.GUIDE.toPreviewHandoffSource())
            .isEqualTo(PreviewHandoffSource.GUIDE)
        assertThat(PreviewHandoffSource.HOME.toLivePreviewOrigin())
            .isEqualTo(LivePreviewOrigin.HOME)
        assertThat(PreviewHandoffSource.GUIDE.toLivePreviewOrigin())
            .isEqualTo(LivePreviewOrigin.GUIDE)
    }
}
