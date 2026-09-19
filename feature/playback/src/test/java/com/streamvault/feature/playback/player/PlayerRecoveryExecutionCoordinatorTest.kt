package com.streamvault.feature.playback.player

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.DecoderMode
import com.streamvault.domain.model.Result
import com.streamvault.player.PlayerEngine
import com.streamvault.player.PlayerError
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.mock
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
        val notices = mutableListOf<Notice>()

        override fun isActivePlaybackSession(requestVersion: Long, playbackUrl: String): Boolean = active

        override fun tryAlternateStream(
            channel: com.streamvault.domain.model.Channel,
            preferXtreamTsFallback: Boolean,
            allowXtreamTsFallback: Boolean
        ): Boolean = false

        override fun tryNextCatchUpVariant(): Boolean = false

        override suspend fun tryFallbackToAvcMovieVariant(requestVersion: Long, playbackUrl: String): Boolean = false

        override suspend fun tryRefreshXtreamPlaybackAfterAuthError(
            error: PlayerError,
            requestVersion: Long,
            playbackUrl: String
        ): Boolean = false

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
