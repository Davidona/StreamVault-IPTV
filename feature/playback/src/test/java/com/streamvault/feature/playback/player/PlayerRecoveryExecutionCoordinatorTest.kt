package com.streamvault.feature.playback.player

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.DecoderMode
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.model.StreamType
import com.streamvault.player.PlayerEngine
import com.streamvault.player.PlayerError
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify

class PlayerRecoveryExecutionCoordinatorTest {

    @Test
    fun `stale playback errors are ignored`() = runTest {
        val port = RecordingRecoveryPort().apply { active = false }
        val coordinator = PlayerRecoveryExecutionCoordinator()

        val job = coordinator.handlePlaybackError(
            request = request(PlayerError.SourceError("source failed")),
            sessionScope = this,
            port = port
        )

        assertThat(job).isNull()
        assertThat(port.notices).isEmpty()
    }

    @Test
    fun `first decoder failure switches to software and publishes retry notice`() = runTest {
        val port = RecordingRecoveryPort()
        val coordinator = PlayerRecoveryExecutionCoordinator()

        coordinator.handlePlaybackError(
            request = request(PlayerError.DecoderError("decoder failed")),
            sessionScope = this,
            port = port
        )
        advanceUntilIdle()

        assertThat(port.recoveryState.retriedWithSoftwareDecoder).isTrue()
        assertThat(port.notices.single().recoveryType).isEqualTo(PlayerRecoveryType.DECODER)
        assertThat(port.notices.single().message).contains("software")
        verify(port.playerEngine).setDecoderModes(DecoderMode.SOFTWARE, DecoderMode.SOFTWARE)
        verify(port.playerEngine).play()
    }

    @Test
    fun `live HLS stall without an alternate keeps the current decoder mode`() = runTest {
        val port = RecordingRecoveryPort()
        val coordinator = PlayerRecoveryExecutionCoordinator()
        val liveHlsError = request(
            PlayerError.DecoderError(
                "Video playback stalled. Automatic recovery was not attempted to avoid opening another provider connection."
            )
        ).copy(
            contentType = ContentType.LIVE,
            currentStreamUrl = "https://example.test/live/channel.m3u8",
            resolvedStreamInfo = StreamInfo(
                url = "https://example.test/live/channel.m3u8",
                streamType = StreamType.HLS
            ),
            channel = Channel(id = 1L, name = "Live channel"),
            livePlaybackReady = true
        )

        coordinator.handlePlaybackError(liveHlsError, this, port)
        advanceUntilIdle()

        assertThat(port.alternateStreamCalls).isEqualTo(1)
        assertThat(port.lastAllowXtreamTsFallback).isFalse()
        assertThat(port.recoveryState.retriedWithSoftwareDecoder).isFalse()
        assertThat(port.notices).isEmpty()
        verify(port.playerEngine, never()).setDecoderModes(DecoderMode.SOFTWARE, DecoderMode.SOFTWARE)
        verify(port.playerEngine, never()).play()
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `live HLS URL with unknown provider stream type keeps the current decoder mode`() = runTest {
        val port = RecordingRecoveryPort()
        val coordinator = PlayerRecoveryExecutionCoordinator()
        val liveHlsError = request(PlayerError.DecoderError("Video playback stalled."))
            .copy(
                contentType = ContentType.LIVE,
                currentStreamUrl = "https://example.test/live/channel.m3u8?token=abc",
                resolvedStreamInfo = StreamInfo(
                    url = "https://example.test/live/channel.m3u8?token=abc",
                    streamType = StreamType.UNKNOWN
                ),
                channel = Channel(id = 1L, name = "Live channel"),
                livePlaybackReady = true
            )

        coordinator.handlePlaybackError(liveHlsError, this, port)
        advanceUntilIdle()

        assertThat(port.recoveryState.retriedWithSoftwareDecoder).isFalse()
        assertThat(port.notices).hasSize(1)
        assertThat(port.notices.single().recoveryType).isEqualTo(PlayerRecoveryType.DECODER)
        verify(port.playerEngine, never()).setDecoderModes(DecoderMode.SOFTWARE, DecoderMode.SOFTWARE)
        verify(port.playerEngine, never()).play()
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `catch-up HLS decoder error uses the non-live decoder fallback`() = runTest {
        val port = RecordingRecoveryPort()
        val coordinator = PlayerRecoveryExecutionCoordinator()
        val catchUpHlsError = request(PlayerError.DecoderError("Video playback stalled."))
            .copy(
                contentType = ContentType.LIVE,
                currentStreamUrl = "https://example.test/archive/program.m3u8",
                resolvedStreamInfo = StreamInfo(
                    url = "https://example.test/archive/program.m3u8",
                    streamType = StreamType.HLS,
                    playbackContentKind = com.streamvault.domain.model.PlaybackContentKind.CATCH_UP
                ),
                channel = Channel(id = 1L, name = "Archive channel"),
                isCatchUp = true
            )

        coordinator.handlePlaybackError(catchUpHlsError, this, port)
        advanceUntilIdle()

        assertThat(port.recoveryState.retriedWithSoftwareDecoder).isTrue()
        verify(port.playerEngine).setDecoderModes(DecoderMode.SOFTWARE, DecoderMode.SOFTWARE)
        verify(port.playerEngine).play()
    }

    @Test
    fun `movie AVC fallback is attempted at most once in a playback session`() = runTest {
        val port = RecordingRecoveryPort().apply { avcFallbackResult = true }
        val coordinator = PlayerRecoveryExecutionCoordinator()
        val decoderFailure = request(PlayerError.DecoderError("decoder failed"))

        coordinator.handlePlaybackError(decoderFailure, this, port)
        advanceUntilIdle()
        coordinator.handlePlaybackError(decoderFailure, this, port)
        advanceUntilIdle()
        coordinator.handlePlaybackError(decoderFailure, this, port)
        advanceUntilIdle()

        assertThat(port.avcFallbackCalls).isEqualTo(1)
        assertThat(port.recoveryState.retriedWithAvcMovieVariant).isTrue()
    }

    @Test
    fun `missing live source tries another stream without enabling TS fallback after ready`() = runTest {
        val port = RecordingRecoveryPort().apply { alternateStreamResult = true }
        val coordinator = PlayerRecoveryExecutionCoordinator()
        val request = request(PlayerError.SourceError("Stream resource not found (HTTP 404)."))
            .copy(
                contentType = ContentType.LIVE,
                channel = Channel(id = 1L, name = "Live channel"),
                livePlaybackReady = true
            )

        coordinator.handlePlaybackError(request, this, port)
        advanceUntilIdle()

        assertThat(port.alternateStreamCalls).isEqualTo(1)
        assertThat(port.lastAllowXtreamTsFallback).isFalse()
        assertThat(port.notices.single().recoveryType).isEqualTo(PlayerRecoveryType.SOURCE)
    }

    @Test
    fun episodeSoftwareRetryPrecedesVariant() = runTest {
        val port = RecordingRecoveryPort().apply { episodeFallbackResult = EpisodeFallbackResult.Recovered }
        val coordinator = PlayerRecoveryExecutionCoordinator()
        val failure = request(PlayerError.DecoderError("decoder failed")).copy(contentType = ContentType.SERIES_EPISODE)
        coordinator.handlePlaybackError(failure, this, port)
        advanceUntilIdle()
        assertThat(port.episodeFallbackCalls).isEqualTo(0)
        assertThat(port.recoveryState.retriedWithSoftwareDecoder).isTrue()
        coordinator.handlePlaybackError(failure, this, port)
        advanceUntilIdle()
        assertThat(port.episodeFallbackCalls).isEqualTo(1)
        assertThat(port.avcFallbackCalls).isEqualTo(0)
    }

    @Test
    fun handledEpisodeFailureDoesNotFallThroughAuthOrNotice() = runTest {
        val port = RecordingRecoveryPort().apply {
            recoveryState.retriedWithSoftwareDecoder = true
            episodeFallbackResult = EpisodeFallbackResult.HandledFailure
        }
        PlayerRecoveryExecutionCoordinator().handlePlaybackError(
            request(PlayerError.DecoderError("decoder failed")).copy(contentType = ContentType.SERIES_EPISODE), this, port)
        advanceUntilIdle()
        assertThat(port.episodeFallbackCalls).isEqualTo(1)
        assertThat(port.authRefreshCalls).isEqualTo(0)
        assertThat(port.notices).isEmpty()
    }

    @Test
    fun nonDecoderErrorNeverSearchesVariants() = runTest {
        val port = RecordingRecoveryPort()
        PlayerRecoveryExecutionCoordinator().handlePlaybackError(
            request(PlayerError.NetworkError("timeout")).copy(contentType = ContentType.SERIES_EPISODE), this, port)
        advanceUntilIdle()
        assertThat(port.episodeFallbackCalls).isEqualTo(0)
    }

    private fun request(error: PlayerError) = PlayerRecoveryRequest(
        error = error,
        requestVersion = 1L,
        playbackUrl = "https://example.test/live.m3u8",
        contentType = ContentType.MOVIE,
        currentStreamUrl = "https://example.test/live.m3u8",
        resolvedStreamInfo = null,
        channel = null,
        isCatchUp = false,
        currentProgramHasArchive = false,
        livePlaybackReady = false
    )

    private class RecordingRecoveryPort : PlayerRecoveryExecutionPort {
        override val appPackageName: String = "com.streamvault.test"
        override val playerEngine: PlayerEngine = mock()
        override val recoveryState = PlayerRecoveryCoordinator()
        var active = true
        var alternateStreamResult = false
        var alternateStreamCalls = 0
        var avcFallbackResult = false
        var avcFallbackCalls = 0
        var episodeFallbackResult = EpisodeFallbackResult.NotEligible
        var episodeFallbackCalls = 0
        var authRefreshCalls = 0
        var lastAllowXtreamTsFallback: Boolean? = null
        val notices = mutableListOf<Notice>()

        override fun isActivePlaybackSession(requestVersion: Long, playbackUrl: String): Boolean = active

        override fun tryAlternateStream(
            channel: com.streamvault.domain.model.Channel,
            preferXtreamTsFallback: Boolean,
            allowXtreamTsFallback: Boolean
        ): Boolean {
            alternateStreamCalls++
            lastAllowXtreamTsFallback = allowXtreamTsFallback
            return alternateStreamResult
        }

        override fun tryNextCatchUpVariant(): Boolean = false

        override suspend fun tryFallbackToAvcMovieVariant(requestVersion: Long, playbackUrl: String): Boolean {
            avcFallbackCalls++
            if (avcFallbackResult) recoveryState.retriedWithAvcMovieVariant = true
            return avcFallbackResult
        }

        override suspend fun tryRefreshXtreamPlaybackAfterAuthError(
            error: PlayerError,
            requestVersion: Long,
            playbackUrl: String
        ): Boolean { authRefreshCalls++; return false }

        override suspend fun tryFallbackToAvcEpisodeVariant(requestVersion: Long, playbackUrl: String): EpisodeFallbackResult {
            episodeFallbackCalls++
            return episodeFallbackResult
        }

        override fun recordMovieVariantFailureObservation(error: PlayerError) = Unit

        override fun updateDecoderModes(audioMode: DecoderMode, videoMode: DecoderMode) = Unit

        override fun setLastFailureReason(message: String?) = Unit

        override fun appendRecoveryAction(action: String) = Unit

        override fun buildRecoveryActions(recoveryType: PlayerRecoveryType): List<PlayerNoticeAction> = emptyList()

        override fun showPlayerNotice(
            message: String,
            recoveryType: PlayerRecoveryType,
            actions: List<PlayerNoticeAction>,
            isRetryNotice: Boolean
        ) {
            notices += Notice(message, recoveryType)
        }

        override fun markStreamFailure(streamUrl: String) = Unit

        override suspend fun incrementChannelErrorCount(channelId: Long): Result<Unit> = Result.Success(Unit)

        override fun logRepositoryFailure(operation: String, result: Result<Unit>) = Unit

        override fun fallbackToPreviousChannel(reason: String): Boolean = false

        override fun hasLastChannel(): Boolean = false
    }

    private data class Notice(
        val message: String,
        val recoveryType: PlayerRecoveryType
    )
}
