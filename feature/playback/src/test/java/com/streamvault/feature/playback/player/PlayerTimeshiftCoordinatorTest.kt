package com.streamvault.feature.playback.player

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.StreamInfo
import com.streamvault.player.PlayerEngine
import com.streamvault.player.timeshift.TimeshiftConfig
import org.junit.Test
import org.mockito.kotlin.never
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify

class PlayerTimeshiftCoordinatorTest {

    @Test
    fun `live stream starts rewind with resolved channel key`() {
        val engine = mock<PlayerEngine>()
        val coordinator = PlayerTimeshiftCoordinator(PlayerEngineCoordinator(engine))
        val streamInfo = StreamInfo(url = "https://provider.example/live.m3u8")
        var preparing = false

        coordinator.startOrStop(
            request = PlayerTimeshiftCoordinator.Request(
                contentType = ContentType.LIVE,
                enabled = true,
                streamClassLabel = "Live",
                config = TimeshiftConfig(enabled = true),
                streamInfoOverride = streamInfo,
                currentResolvedStreamInfo = null,
                currentResolvedPlaybackUrl = "",
                currentStreamUrl = null,
                playbackTitle = "Channel",
                currentTitle = "Channel",
                channelKey = "channel-42"
            ),
            onUnavailable = {},
            onPreparing = { preparing = true }
        )

        assertThat(preparing).isTrue()
        verify(engine).startLiveTimeshift(
            streamInfo.copy(title = "Channel"),
            "channel-42",
            TimeshiftConfig(enabled = true)
        )
        verify(engine, never()).stopLiveTimeshift()
    }

    @Test
    fun `catch up stream is detached without starting rewind`() {
        val engine = mock<PlayerEngine>()
        val coordinator = PlayerTimeshiftCoordinator(PlayerEngineCoordinator(engine))

        coordinator.startOrStop(
            request = PlayerTimeshiftCoordinator.Request(
                contentType = ContentType.LIVE,
                enabled = true,
                streamClassLabel = "Catch-up",
                config = TimeshiftConfig(enabled = true),
                streamInfoOverride = StreamInfo(url = "https://provider.example/live.m3u8"),
                currentResolvedStreamInfo = null,
                currentResolvedPlaybackUrl = "",
                currentStreamUrl = null,
                playbackTitle = "Channel",
                currentTitle = "Channel",
                channelKey = "channel-42"
            ),
            onUnavailable = {},
            onPreparing = {}
        )

        verify(engine).stopLiveTimeshift()
        verify(engine, never()).startLiveTimeshift(
            org.mockito.kotlin.any(),
            org.mockito.kotlin.any(),
            org.mockito.kotlin.any()
        )
    }

    @Test
    fun `live stream starts rewind on the engine adopted after coordinator creation`() {
        val mainEngine = mock<PlayerEngine>()
        val adoptedEngine = mock<PlayerEngine>()
        val engineCoordinator = PlayerEngineCoordinator(mainEngine)
        val coordinator = PlayerTimeshiftCoordinator(engineCoordinator)
        val streamInfo = StreamInfo(url = "https://provider.example/live.m3u8")

        engineCoordinator.switchTo(adoptedEngine)

        coordinator.startOrStop(
            request = PlayerTimeshiftCoordinator.Request(
                contentType = ContentType.LIVE,
                enabled = true,
                streamClassLabel = "Live",
                config = TimeshiftConfig(enabled = true),
                streamInfoOverride = streamInfo,
                currentResolvedStreamInfo = null,
                currentResolvedPlaybackUrl = "",
                currentStreamUrl = null,
                playbackTitle = "Channel",
                currentTitle = "Channel",
                channelKey = "channel-42"
            ),
            onUnavailable = {},
            onPreparing = {}
        )

        verify(adoptedEngine).startLiveTimeshift(
            streamInfo.copy(title = "Channel"),
            "channel-42",
            TimeshiftConfig(enabled = true)
        )
        verify(mainEngine, never()).startLiveTimeshift(
            org.mockito.kotlin.any(),
            org.mockito.kotlin.any(),
            org.mockito.kotlin.any()
        )
    }
}
