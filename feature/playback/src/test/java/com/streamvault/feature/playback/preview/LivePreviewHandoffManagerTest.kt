package com.streamvault.feature.playback.preview

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.StreamInfo
import com.streamvault.player.PlayerEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain

@OptIn(ExperimentalCoroutinesApi::class)
class LivePreviewHandoffManagerTest {
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `matching pending preview can be consumed by fullscreen`() = runTest(testDispatcher) {
        val manager = LivePreviewHandoffManager()
        val engine = mock<PlayerEngine>()
        val channel = channel(id = 42L, providerId = 7L)
        val streamInfo = StreamInfo(url = "https://example.com/live.m3u8", title = channel.name)

        manager.registerPreviewSession(channel, streamInfo, engine)

        assertThat(manager.beginFullscreenHandoff(channel.id, engine)).isTrue()

        val session = manager.consumeFullscreenHandoff(channel.id, channel.providerId)

        assertThat(session).isNotNull()
        assertThat(session?.engine).isSameInstanceAs(engine)
        assertThat(manager.consumeFullscreenHandoff(channel.id, channel.providerId)).isNull()
        verifyNoInteractions(engine)
    }

    @Test
    fun `pending preview is released when fullscreen never claims it`() = runTest(testDispatcher) {
        val manager = LivePreviewHandoffManager()
        val engine = mock<PlayerEngine>()
        val channel = channel(id = 99L, providerId = 5L)
        val streamInfo = StreamInfo(url = "https://example.com/preview.ts", title = channel.name)

        manager.registerPreviewSession(channel, streamInfo, engine)
        assertThat(manager.beginFullscreenHandoff(channel.id, engine)).isTrue()

        advanceTimeBy(15_001L)
        testDispatcher.scheduler.runCurrent()

        assertThat(manager.consumeFullscreenHandoff(channel.id, channel.providerId)).isNull()
        verify(engine).release()
    }

    @Test
    fun `reverse handoff session is visible on flow after beginReverseHandoff`() = runTest(testDispatcher) {
        val manager = LivePreviewHandoffManager()
        val engine = mock<PlayerEngine>()
        val channel = channel(id = 1L, providerId = 2L)
        val streamInfo = StreamInfo(url = "https://example.com/live.m3u8", title = channel.name)

        manager.beginReverseHandoff(channel, streamInfo, engine, PreviewHandoffSource.GUIDE)

        assertThat(manager.reverseSessionFlow.value).isNotNull()
        assertThat(manager.reverseSessionFlow.value?.engine).isSameInstanceAs(engine)
        assertThat(manager.reverseSessionFlow.value?.source).isEqualTo(PreviewHandoffSource.GUIDE)
    }

    @Test
    fun `consumeReverseHandoff without filter returns any session`() = runTest(testDispatcher) {
        val manager = LivePreviewHandoffManager()
        val engine = mock<PlayerEngine>()
        val channel = channel(id = 1L, providerId = 2L)
        val streamInfo = StreamInfo(url = "https://example.com/live.m3u8", title = channel.name)

        manager.beginReverseHandoff(channel, streamInfo, engine, PreviewHandoffSource.HOME)

        val session = manager.consumeReverseHandoff()
        assertThat(session).isNotNull()
        assertThat(session?.engine).isSameInstanceAs(engine)
        assertThat(manager.reverseSessionFlow.value).isNull()
    }

    @Test
    fun `consumeReverseHandoff for HOME ignores GUIDE session`() = runTest(testDispatcher) {
        val manager = LivePreviewHandoffManager()
        val engine = mock<PlayerEngine>()
        val channel = channel(id = 1L, providerId = 2L)
        val streamInfo = StreamInfo(url = "https://example.com/live.m3u8", title = channel.name)

        manager.beginReverseHandoff(channel, streamInfo, engine, PreviewHandoffSource.GUIDE)

        assertThat(manager.consumeReverseHandoff(PreviewHandoffSource.HOME)).isNull()
        assertThat(manager.reverseSessionFlow.value).isNotNull()
    }

    @Test
    fun `consumeReverseHandoff for GUIDE ignores HOME session`() = runTest(testDispatcher) {
        val manager = LivePreviewHandoffManager()
        val engine = mock<PlayerEngine>()
        val channel = channel(id = 1L, providerId = 2L)
        val streamInfo = StreamInfo(url = "https://example.com/live.m3u8", title = channel.name)

        manager.beginReverseHandoff(channel, streamInfo, engine, PreviewHandoffSource.HOME)

        assertThat(manager.consumeReverseHandoff(PreviewHandoffSource.GUIDE)).isNull()
        assertThat(manager.reverseSessionFlow.value).isNotNull()
    }

    @Test
    fun `consumeReverseHandoff for GUIDE returns GUIDE session`() = runTest(testDispatcher) {
        val manager = LivePreviewHandoffManager()
        val engine = mock<PlayerEngine>()
        val channel = channel(id = 1L, providerId = 2L)
        val streamInfo = StreamInfo(url = "https://example.com/live.m3u8", title = channel.name)

        manager.beginReverseHandoff(channel, streamInfo, engine, PreviewHandoffSource.GUIDE)

        val session = manager.consumeReverseHandoff(PreviewHandoffSource.GUIDE)
        assertThat(session).isNotNull()
        assertThat(session?.engine).isSameInstanceAs(engine)
        assertThat(manager.reverseSessionFlow.value).isNull()
        verifyNoInteractions(engine)
    }

    @Test
    fun `reverse handoff engine is released when never consumed`() = runTest(testDispatcher) {
        val manager = LivePreviewHandoffManager()
        val engine = mock<PlayerEngine>()
        val channel = channel(id = 1L, providerId = 2L)
        val streamInfo = StreamInfo(url = "https://example.com/live.m3u8", title = channel.name)

        manager.beginReverseHandoff(channel, streamInfo, engine, PreviewHandoffSource.HOME)

        advanceTimeBy(15_001L)
        testDispatcher.scheduler.runCurrent()

        assertThat(manager.reverseSessionFlow.value).isNull()
        verify(engine).release()
    }

    @Test
    fun `consuming reverse handoff prevents engine release`() = runTest(testDispatcher) {
        val manager = LivePreviewHandoffManager()
        val engine = mock<PlayerEngine>()
        val channel = channel(id = 1L, providerId = 2L)
        val streamInfo = StreamInfo(url = "https://example.com/live.m3u8", title = channel.name)

        manager.beginReverseHandoff(channel, streamInfo, engine, PreviewHandoffSource.GUIDE)
        manager.consumeReverseHandoff(PreviewHandoffSource.GUIDE)

        advanceTimeBy(15_001L)
        testDispatcher.scheduler.runCurrent()

        verifyNoInteractions(engine)
    }

    private fun channel(id: Long, providerId: Long): Channel = Channel(
        id = id,
        name = "Preview $id",
        streamUrl = "stream://$id",
        categoryId = 1L,
        providerId = providerId
    )
}
