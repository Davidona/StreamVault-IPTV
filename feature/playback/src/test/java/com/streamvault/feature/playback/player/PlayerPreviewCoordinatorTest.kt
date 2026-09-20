package com.streamvault.feature.playback.player

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.StreamInfo
import com.streamvault.feature.playback.preview.LivePreviewHandoffManager
import com.streamvault.feature.playback.preview.PreviewHandoffSource
import com.streamvault.player.PlayerEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerPreviewCoordinatorTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var handoffManager: LivePreviewHandoffManager
    private lateinit var engineCoordinator: PlayerEngineCoordinator
    private lateinit var preparationCoordinator: PlayerPreparationCoordinator
    private lateinit var preferencesCoordinator: PlayerPreferencesCoordinator

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        handoffManager = LivePreviewHandoffManager()
        engineCoordinator = PlayerEngineCoordinator(mock())
        preparationCoordinator = mock()
        preferencesCoordinator = mock()
        whenever(preferencesCoordinator.playerMediaSessionEnabled).thenReturn(flowOf(true))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun coordinator(): PlayerPreviewCoordinator = PlayerPreviewCoordinator(
        handoffManager = handoffManager,
        engineCoordinator = engineCoordinator,
        preparationCoordinator = preparationCoordinator,
        preferencesCoordinator = preferencesCoordinator
    )

    private suspend fun adopt(
        coordinator: PlayerPreviewCoordinator,
        channel: Channel,
        source: PreviewHandoffSource
    ): Boolean {
        val previewEngine = mock<PlayerEngine>()
        val streamInfo = StreamInfo(url = "https://example.com/live.m3u8", title = channel.name)
        handoffManager.registerPreviewSession(
            channel = channel,
            streamInfo = streamInfo,
            engine = previewEngine,
            source = source
        )
        handoffManager.beginFullscreenHandoff(channel.id, previewEngine)
        return coordinator.tryAdoptFullscreenHandoff(
            channelId = channel.id,
            providerId = channel.providerId,
            contentType = ContentType.LIVE,
            audioVideoOffsetMs = 0,
            isCurrent = { true },
            onAdopted = {},
            onStarted = {}
        )
    }

    @Test
    fun `adopting a guide preview records guide as the reverse handoff source`() = runTest(testDispatcher) {
        val coordinator = coordinator()
        val channel = channel(id = 10L, providerId = 3L)

        assertThat(adopt(coordinator, channel, PreviewHandoffSource.GUIDE)).isTrue()
        assertThat(coordinator.consumeAdoptedHandoffSource()).isEqualTo(PreviewHandoffSource.GUIDE)
        assertThat(coordinator.consumeAdoptedHandoffSource()).isNull()
    }

    @Test
    fun `adopting a home preview records home as the reverse handoff source`() = runTest(testDispatcher) {
        val coordinator = coordinator()
        val channel = channel(id = 11L, providerId = 3L)

        assertThat(adopt(coordinator, channel, PreviewHandoffSource.HOME)).isTrue()
        assertThat(coordinator.consumeAdoptedHandoffSource()).isEqualTo(PreviewHandoffSource.HOME)
    }

    @Test
    fun `preparing without a preview handoff records no reverse handoff source`() = runTest(testDispatcher) {
        val coordinator = coordinator()

        val adopted = coordinator.tryAdoptFullscreenHandoff(
            channelId = 12L,
            providerId = 3L,
            contentType = ContentType.LIVE,
            audioVideoOffsetMs = 0,
            isCurrent = { true },
            onAdopted = {},
            onStarted = {}
        )

        assertThat(adopted).isFalse()
        assertThat(coordinator.consumeAdoptedHandoffSource()).isNull()
    }

    private fun channel(id: Long, providerId: Long): Channel = Channel(
        id = id,
        name = "Preview $id",
        streamUrl = "stream://$id",
        categoryId = 1L,
        providerId = providerId
    )
}
