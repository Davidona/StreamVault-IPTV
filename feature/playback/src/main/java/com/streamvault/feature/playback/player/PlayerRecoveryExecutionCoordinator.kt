package com.streamvault.feature.playback.player

import android.util.Log
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.DecoderMode
import com.streamvault.domain.model.PlaybackContentKind
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.StreamInfo
import com.streamvault.player.PlayerEngine
import com.streamvault.player.PlayerError
import com.streamvault.player.playback.ResolvedStreamType
import com.streamvault.player.playback.StreamTypeResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val PROVIDER_AUTH_RETRY_GRACE_MS = 1_200L

internal data class PlayerRecoveryRequest(
    val error: PlayerError,
    val requestVersion: Long,
    val playbackUrl: String,
    val contentType: ContentType,
    val currentStreamUrl: String,
    val resolvedStreamInfo: StreamInfo?,
    val channel: Channel?,
    val isCatchUp: Boolean,
    val currentProgramHasArchive: Boolean,
    val livePlaybackReady: Boolean,
    val requestedAudioDecoderMode: DecoderMode = DecoderMode.AUTO,
    val requestedVideoDecoderMode: DecoderMode = DecoderMode.AUTO,
)

internal interface PlayerRecoveryExecutionPort {
    val appPackageName: String
    val playerEngine: PlayerEngine
    val recoveryState: PlayerRecoveryCoordinator

    fun isActivePlaybackSession(requestVersion: Long, playbackUrl: String): Boolean
    fun tryAlternateStream(
        channel: Channel,
        preferXtreamTsFallback: Boolean = false,
        allowXtreamTsFallback: Boolean = true,
    ): Boolean
    fun tryNextCatchUpVariant(): Boolean
    suspend fun tryFallbackToAvcMovieVariant(requestVersion: Long, playbackUrl: String): Boolean
    suspend fun tryFallbackToAvcEpisodeVariant(requestVersion: Long, playbackUrl: String): EpisodeFallbackResult =
        EpisodeFallbackResult.NotEligible
    fun handleEpisodeReplacementError(error: PlayerError): Boolean = false
    suspend fun tryRefreshXtreamPlaybackAfterAuthError(
        error: PlayerError,
        requestVersion: Long,
        playbackUrl: String,
    ): Boolean
    fun recordMovieVariantFailureObservation(error: PlayerError)
    fun updateDecoderModes(audioMode: DecoderMode, videoMode: DecoderMode)
    fun setLastFailureReason(message: String?)
    fun appendRecoveryAction(action: String)
    fun buildRecoveryActions(recoveryType: PlayerRecoveryType): List<PlayerNoticeAction>
    fun showPlayerNotice(
        message: String,
        recoveryType: PlayerRecoveryType,
        actions: List<PlayerNoticeAction> = emptyList(),
        isRetryNotice: Boolean = false,
    )
    fun markStreamFailure(streamUrl: String)
    suspend fun incrementChannelErrorCount(channelId: Long): Result<Unit>
    fun logRepositoryFailure(operation: String, result: Result<Unit>)
    fun fallbackToPreviousChannel(reason: String): Boolean
    fun hasLastChannel(): Boolean
}

/** Owns the playback-error decision tree and its session-scoped recovery job. */
class PlayerRecoveryExecutionCoordinator @Inject constructor() {
    private var recoveryJob: Job? = null

    internal fun cancel() {
        recoveryJob?.cancel()
        recoveryJob = null
    }

    internal fun handlePlaybackError(
        request: PlayerRecoveryRequest,
        sessionScope: CoroutineScope?,
        port: PlayerRecoveryExecutionPort,
    ): Job? {
        val error = request.error
        logInfo(
            "handle-playback-error type=${error::class.java.simpleName} contentType=${request.contentType} " +
                "hasChannel=${request.channel != null} requestVersion=${request.requestVersion} " +
                "active=${port.isActivePlaybackSession(request.requestVersion, request.playbackUrl)}"
        )
        cancel()
        if (!port.isActivePlaybackSession(request.requestVersion, request.playbackUrl)) return null
        if (request.contentType == ContentType.SERIES_EPISODE && port.handleEpisodeReplacementError(error)) return null

        // The engine deliberately keeps deferred live stalls on the current source.
        // Calling play() here would undo that decision and reopen a provider connection.
        if (error is PlayerError.DecoderError && error.isDeferredLiveStall &&
            request.contentType == ContentType.LIVE && !request.isCatchUp &&
            request.resolvedStreamInfo?.playbackContentKind != PlaybackContentKind.CATCH_UP
        ) return null

        if (error is PlayerError.DecoderError && !port.recoveryState.retriedWithSoftwareDecoder) {
            if (!port.isActivePlaybackSession(request.requestVersion, request.playbackUrl)) return null
            if (request.contentType == ContentType.LIVE) {
                val currentLiveHlsSession = request.isLiveHlsSession()
                if (currentLiveHlsSession) {
                    val channel = request.channel
                    if (channel != null && port.tryAlternateStream(channel, allowXtreamTsFallback = false)) {
                        return null
                    }
                    logWarning(
                        "Decoder error on live HLS. Keeping hardware path to match Sparkle-like playback on ${port.appPackageName}."
                    )
                    if (!error.isDeferredLiveStall) {
                        port.setLastFailureReason(error.message)
                        port.showPlayerNotice(
                            message = resolvePlaybackErrorMessage(error),
                            recoveryType = PlayerRecoveryType.DECODER,
                            actions = port.buildRecoveryActions(PlayerRecoveryType.DECODER)
                        )
                    }
                    return null
                }
            }
            if (request.requestedAudioDecoderMode == DecoderMode.AUTO ||
                request.requestedVideoDecoderMode == DecoderMode.AUTO
            ) {
                val audioMode = request.requestedAudioDecoderMode.softwareRecoveryMode()
                val videoMode = request.requestedVideoDecoderMode.softwareRecoveryMode()
                port.recoveryState.retriedWithSoftwareDecoder = true
                logWarning("Decoder error detected. Retrying with software decoder mode.")
                port.playerEngine.setDecoderModes(
                    audioMode = audioMode,
                    videoMode = videoMode
                )
                port.updateDecoderModes(
                    audioMode = audioMode,
                    videoMode = videoMode
                )
                port.setLastFailureReason(error.message)
                port.appendRecoveryAction("Switched to software decoder")
                port.playerEngine.play()
                port.showPlayerNotice(
                    message = "Retrying with software decoding for this stream.",
                    recoveryType = PlayerRecoveryType.DECODER,
                    actions = port.buildRecoveryActions(PlayerRecoveryType.DECODER)
                )
                return null
            }
        }

        port.recordMovieVariantFailureObservation(error)
        if (
            error is PlayerError.DecoderError &&
            port.recoveryState.retriedWithSoftwareDecoder &&
            request.contentType == ContentType.LIVE
        ) {
            if (!port.isActivePlaybackSession(request.requestVersion, request.playbackUrl)) return null
            val channel = request.channel ?: return null
            if (port.tryAlternateStream(channel)) {
                port.appendRecoveryAction("Trying alternate stream format after decoder error")
                port.showPlayerNotice(
                    message = "Trying alternate stream format for ${channel.name}.",
                    recoveryType = PlayerRecoveryType.DECODER,
                    actions = port.buildRecoveryActions(PlayerRecoveryType.DECODER),
                    isRetryNotice = true
                )
                return null
            }
        }

        recoveryJob = sessionScope?.launch {
            if (!port.isActivePlaybackSession(request.requestVersion, request.playbackUrl)) return@launch
            if (error is PlayerError.DecoderError && request.contentType == ContentType.SERIES_EPISODE) {
                when (port.tryFallbackToAvcEpisodeVariant(request.requestVersion, request.playbackUrl)) {
                    EpisodeFallbackResult.Recovered, EpisodeFallbackResult.HandledFailure -> return@launch
                    EpisodeFallbackResult.NotEligible -> Unit
                }
            }
            if (
                error is PlayerError.DecoderError &&
                request.contentType == ContentType.MOVIE &&
                !port.recoveryState.retriedWithAvcMovieVariant &&
                port.tryFallbackToAvcMovieVariant(request.requestVersion, request.playbackUrl)
            ) {
                return@launch
            }
            if (port.tryRefreshXtreamPlaybackAfterAuthError(error, request.requestVersion, request.playbackUrl)) {
                return@launch
            }

            val recoveryType = classifyPlaybackError(error)
            val channel = request.channel
            logInfo(
                "recovery-dispatch type=$recoveryType live=${request.contentType == ContentType.LIVE} " +
                    "hasChannel=${channel != null}"
            )

            if (recoveryType == PlayerRecoveryType.DRM) {
                if (!port.isActivePlaybackSession(request.requestVersion, request.playbackUrl)) return@launch
                port.showPlayerNotice(
                    message = "This channel requires DRM support that is not available. " +
                        "Your subscription may not include this content.",
                    recoveryType = PlayerRecoveryType.DRM,
                    actions = port.buildRecoveryActions(PlayerRecoveryType.DRM)
                )
                return@launch
            }

            if (request.contentType != ContentType.LIVE || channel == null) {
                if (!port.isActivePlaybackSession(request.requestVersion, request.playbackUrl)) return@launch
                port.showPlayerNotice(
                    message = resolvePlaybackErrorMessage(error),
                    recoveryType = recoveryType,
                    actions = port.buildRecoveryActions(recoveryType)
                )
                return@launch
            }

            if (request.isCatchUp) {
                port.markStreamFailure(request.currentStreamUrl)
                port.setLastFailureReason(error.message)

                val switched = when (recoveryType) {
                    PlayerRecoveryType.NETWORK,
                    PlayerRecoveryType.SOURCE,
                    PlayerRecoveryType.BUFFER_TIMEOUT -> port.tryNextCatchUpVariant()
                    else -> false
                }

                if (!port.isActivePlaybackSession(request.requestVersion, request.playbackUrl)) return@launch
                if (switched) {
                    port.appendRecoveryAction("Trying alternate catch-up URL")
                    port.showPlayerNotice(
                        message = "Trying another replay path for ${channel.name}.",
                        recoveryType = PlayerRecoveryType.CATCH_UP,
                        actions = port.buildRecoveryActions(PlayerRecoveryType.CATCH_UP),
                        isRetryNotice = true
                    )
                    return@launch
                }

                port.showPlayerNotice(
                    message = resolveCatchUpFailureMessage(
                        channel = channel,
                        archiveRequested = true,
                        programHasArchive = request.currentProgramHasArchive
                    ),
                    recoveryType = PlayerRecoveryType.CATCH_UP,
                    actions = port.buildRecoveryActions(PlayerRecoveryType.CATCH_UP)
                )
                return@launch
            }

            port.markStreamFailure(request.currentStreamUrl)
            port.setLastFailureReason(error.message)
            port.logRepositoryFailure(
                operation = "Increment channel error count",
                result = port.incrementChannelErrorCount(channel.id)
            )

            val switched = when (recoveryType) {
                PlayerRecoveryType.NETWORK,
                PlayerRecoveryType.SOURCE,
                PlayerRecoveryType.BUFFER_TIMEOUT -> port.tryAlternateStream(
                    channel = channel,
                    preferXtreamTsFallback = recoveryType == PlayerRecoveryType.SOURCE,
                    allowXtreamTsFallback = !request.livePlaybackReady
                )
                else -> false
            }

            if (!port.isActivePlaybackSession(request.requestVersion, request.playbackUrl)) return@launch
            if (switched) {
                port.appendRecoveryAction("Trying alternate stream")
                port.showPlayerNotice(
                    message = "Trying an alternate stream for ${channel.name}.",
                    recoveryType = recoveryType,
                    actions = port.buildRecoveryActions(recoveryType),
                    isRetryNotice = true
                )
                return@launch
            }
            logWarning(
                "recovery-no-switch type=$recoveryType hasLastChannel=${port.hasLastChannel()}"
            )

            if (port.fallbackToPreviousChannel("Recovery path exhausted for ${recoveryType.name.lowercase()}")) {
                port.appendRecoveryAction("Returned to last channel")
                port.showPlayerNotice(
                    message = "Playback failed on this stream. Returned to the last channel.",
                    recoveryType = recoveryType,
                    actions = port.buildRecoveryActions(recoveryType)
                )
            } else {
                port.showPlayerNotice(
                    message = resolvePlaybackErrorMessage(error),
                    recoveryType = recoveryType,
                    actions = port.buildRecoveryActions(recoveryType)
                )
            }
        }
        return recoveryJob
    }

    private fun logInfo(message: String) {
        runCatching { Log.i("PlayerVM", message) }
    }

    private fun logWarning(message: String) {
        runCatching { Log.w("PlayerVM", message) }
    }
}

private fun PlayerRecoveryRequest.isLiveHlsSession(): Boolean {
    val streamInfo = resolvedStreamInfo
    if (isCatchUp || streamInfo?.playbackContentKind == PlaybackContentKind.CATCH_UP) return false

    if (streamInfo != null) {
        val resolvedType = StreamTypeResolver.resolve(streamInfo)
        if (resolvedType != ResolvedStreamType.UNKNOWN) {
            return resolvedType == ResolvedStreamType.HLS
        }
    }

    return listOfNotNull(streamInfo?.url, currentStreamUrl, playbackUrl)
        .any { url -> StreamTypeResolver.resolve(url = url, isLive = true) == ResolvedStreamType.HLS }
}

private fun DecoderMode.softwareRecoveryMode(): DecoderMode =
    if (this == DecoderMode.AUTO) DecoderMode.SOFTWARE else this
