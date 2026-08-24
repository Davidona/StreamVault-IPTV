package com.streamvault.feature.playback.player

import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.DecoderMode
import com.streamvault.domain.model.Result
import com.streamvault.player.PlayerEngine
import com.streamvault.player.PlayerError

/**
 * Bridges recovery execution to the active player runtime without retaining the ViewModel.
 * Every callback is supplied by the composition root and is scoped to the current runtime.
 */
internal class PlayerRecoveryExecutionPortAdapter(
    override val appPackageName: String,
    private val engineCoordinator: PlayerEngineCoordinator,
    override val recoveryState: PlayerRecoveryCoordinator,
    private val isActivePlaybackSession: (Long, String) -> Boolean,
    private val tryAlternateStream: (Channel, Boolean, Boolean) -> Boolean,
    private val tryNextCatchUpVariant: () -> Boolean,
    private val tryFallbackToAvcMovieVariant: suspend (Long, String) -> Boolean,
    private val tryRefreshXtreamPlaybackAfterAuthError: suspend (PlayerError, Long, String) -> Boolean,
    private val recordMovieVariantFailureObservation: (PlayerError) -> Unit,
    private val updateDecoderModes: (DecoderMode, DecoderMode) -> Unit,
    private val setLastFailureReason: (String?) -> Unit,
    private val appendRecoveryAction: (String) -> Unit,
    private val buildRecoveryActions: (PlayerRecoveryType) -> List<PlayerNoticeAction>,
    private val showPlayerNotice: (String, PlayerRecoveryType, List<PlayerNoticeAction>, Boolean) -> Unit,
    private val markStreamFailure: (String) -> Unit,
    private val incrementChannelErrorCount: suspend (Long) -> Result<Unit>,
    private val logRepositoryFailure: (String, Result<Unit>) -> Unit,
    private val fallbackToPreviousChannel: (String) -> Boolean,
    private val hasLastChannel: () -> Boolean,
) : PlayerRecoveryExecutionPort {
    override val playerEngine: PlayerEngine
        get() = engineCoordinator.currentEngine

    override fun isActivePlaybackSession(requestVersion: Long, playbackUrl: String): Boolean =
        isActivePlaybackSession.invoke(requestVersion, playbackUrl)

    override fun tryAlternateStream(
        channel: Channel,
        preferXtreamTsFallback: Boolean,
        allowXtreamTsFallback: Boolean,
    ): Boolean = tryAlternateStream.invoke(channel, preferXtreamTsFallback, allowXtreamTsFallback)

    override fun tryNextCatchUpVariant(): Boolean = tryNextCatchUpVariant.invoke()

    override suspend fun tryFallbackToAvcMovieVariant(requestVersion: Long, playbackUrl: String): Boolean =
        tryFallbackToAvcMovieVariant.invoke(requestVersion, playbackUrl)

    override suspend fun tryRefreshXtreamPlaybackAfterAuthError(
        error: PlayerError,
        requestVersion: Long,
        playbackUrl: String,
    ): Boolean = tryRefreshXtreamPlaybackAfterAuthError.invoke(error, requestVersion, playbackUrl)

    override fun recordMovieVariantFailureObservation(error: PlayerError) {
        recordMovieVariantFailureObservation.invoke(error)
    }

    override fun updateDecoderModes(audioMode: DecoderMode, videoMode: DecoderMode) {
        updateDecoderModes.invoke(audioMode, videoMode)
    }

    override fun setLastFailureReason(message: String?) {
        setLastFailureReason.invoke(message)
    }

    override fun appendRecoveryAction(action: String) {
        appendRecoveryAction.invoke(action)
    }

    override fun buildRecoveryActions(recoveryType: PlayerRecoveryType): List<PlayerNoticeAction> =
        buildRecoveryActions.invoke(recoveryType)

    override fun showPlayerNotice(
        message: String,
        recoveryType: PlayerRecoveryType,
        actions: List<PlayerNoticeAction>,
        isRetryNotice: Boolean,
    ) {
        showPlayerNotice.invoke(message, recoveryType, actions, isRetryNotice)
    }

    override fun markStreamFailure(streamUrl: String) {
        markStreamFailure.invoke(streamUrl)
    }

    override suspend fun incrementChannelErrorCount(channelId: Long): Result<Unit> =
        incrementChannelErrorCount.invoke(channelId)

    override fun logRepositoryFailure(operation: String, result: Result<Unit>) {
        logRepositoryFailure.invoke(operation, result)
    }

    override fun fallbackToPreviousChannel(reason: String): Boolean = fallbackToPreviousChannel.invoke(reason)

    override fun hasLastChannel(): Boolean = hasLastChannel.invoke()
}
