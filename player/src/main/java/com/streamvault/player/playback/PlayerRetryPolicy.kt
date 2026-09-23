package com.streamvault.player.playback

import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.source.BehindLiveWindowException
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.streamvault.domain.model.PlaybackContentKind
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

private const val FAST_TRANSIENT_RETRY_DELAY_MS = 500L
private const val LIVE_TRANSIENT_RETRY_ATTEMPTS = 10
private const val LIVE_HLS_MALFORMED_RETRY_ATTEMPTS_AFTER_START = 12

data class PlaybackRetryContext(
    val resolvedStreamType: ResolvedStreamType,
    val timeoutProfile: PlayerTimeoutProfile,
    val playbackContentKind: PlaybackContentKind = PlaybackContentKind.UNKNOWN
) {
    val isLive: Boolean
        get() = isLivePlayback(playbackContentKind, resolvedStreamType)
}

@UnstableApi
class PlayerRetryPolicy(
    private val streamContext: PlaybackRetryContext,
    private val fastRetryOnTransientFailures: () -> Boolean = { false },
    private val playbackStarted: () -> Boolean
) : LoadErrorHandlingPolicy {
    fun maxAttempts(error: Throwable, playbackStarted: Boolean): Int {
        return maxAttemptsFor(error, streamContext, playbackStarted)
    }

    fun shouldRetry(
        error: Throwable,
        streamContext: PlaybackRetryContext,
        playbackStarted: Boolean,
        attempt: Int
    ): Boolean {
        val category = PlayerErrorClassifier.classify(error)
        if (playbackStarted && category in setOf(PlaybackErrorCategory.DECODER, PlaybackErrorCategory.DRM)) {
            return false
        }
        val maxAttempts = maxAttemptsFor(error, streamContext, playbackStarted)
        return attempt <= maxAttempts && maxAttempts > 0
    }

    fun retryDelayMs(error: Throwable, attempt: Int): Long {
        val category = PlayerErrorClassifier.classify(error)
        if (fastRetryOnTransientFailures() && isFastRetryEligible(category)) {
            return FAST_TRANSIENT_RETRY_DELAY_MS
        }
        return exponentialRetryDelayMs(attempt)
    }

    private fun exponentialRetryDelayMs(attempt: Int): Long {
        return when (attempt) {
            1 -> 1_000L
            2 -> 2_500L
            else -> 5_000L
        }.coerceAtMost(5_000L)
    }

    private fun isFastRetryEligible(category: PlaybackErrorCategory): Boolean {
        return when (category) {
            PlaybackErrorCategory.LIVE_WINDOW -> true
            PlaybackErrorCategory.NETWORK,
            PlaybackErrorCategory.HTTP_SERVER,
            PlaybackErrorCategory.PROVIDER_LIMIT,
            PlaybackErrorCategory.EMPTY_RESPONSE,
            PlaybackErrorCategory.UNKNOWN -> streamContext.isLive
            PlaybackErrorCategory.SOURCE_MALFORMED ->
                streamContext.isLive && streamContext.resolvedStreamType == ResolvedStreamType.HLS
            PlaybackErrorCategory.HTTP_AUTH,
            PlaybackErrorCategory.SSL,
            PlaybackErrorCategory.CLEAR_TEXT_BLOCKED,
            PlaybackErrorCategory.DRM,
            PlaybackErrorCategory.DECODER,
            PlaybackErrorCategory.FORMAT_UNSUPPORTED -> false
        }
    }

    fun retryReason(error: Throwable): String {
        return when (PlayerErrorClassifier.classify(error)) {
            PlaybackErrorCategory.LIVE_WINDOW -> "refresh-live-window"
            PlaybackErrorCategory.NETWORK -> "transient-network"
            PlaybackErrorCategory.HTTP_SERVER -> "server-retryable"
            PlaybackErrorCategory.PROVIDER_LIMIT -> "terminal-provider-limit"
            PlaybackErrorCategory.EMPTY_RESPONSE -> "terminal-empty-response"
            PlaybackErrorCategory.SOURCE_MALFORMED ->
                if (streamContext.isLive && streamContext.resolvedStreamType == ResolvedStreamType.HLS) {
                    "malformed-live-hls-refresh"
                } else {
                    "malformed-source"
                }
            PlaybackErrorCategory.HTTP_AUTH -> "terminal-auth"
            PlaybackErrorCategory.SSL -> "terminal-tls"
            PlaybackErrorCategory.CLEAR_TEXT_BLOCKED -> "terminal-cleartext"
            PlaybackErrorCategory.DRM -> "terminal-drm"
            PlaybackErrorCategory.DECODER -> "terminal-decoder"
            PlaybackErrorCategory.FORMAT_UNSUPPORTED -> "format-unsupported"
            PlaybackErrorCategory.UNKNOWN ->
                if (streamContext.isLive) {
                    "retryable-live-unknown"
                } else {
                    "retryable-source"
                }
        }
    }

    override fun getFallbackSelectionFor(
        fallbackOptions: LoadErrorHandlingPolicy.FallbackOptions,
        loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo
    ): LoadErrorHandlingPolicy.FallbackSelection? = null

    override fun getMinimumLoadableRetryCount(dataType: Int): Int {
        return when {
            streamContext.isLive && dataType == C.DATA_TYPE_MANIFEST -> LIVE_TRANSIENT_RETRY_ATTEMPTS
            streamContext.isLive && dataType == C.DATA_TYPE_MEDIA -> LIVE_TRANSIENT_RETRY_ATTEMPTS
            dataType == C.DATA_TYPE_MANIFEST -> 3
            dataType == C.DATA_TYPE_MEDIA -> if (streamContext.resolvedStreamType == ResolvedStreamType.PROGRESSIVE) 2 else 2
            else -> 1
        }
    }

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val attempt = loadErrorInfo.errorCount
        val error = loadErrorInfo.exception
        val started = playbackStarted()
        if (!shouldRetry(error, streamContext, started, attempt)) {
            return C.TIME_UNSET
        }
        return retryDelayMs(error, attempt)
    }

    override fun onLoadTaskConcluded(loadTaskId: Long) = Unit

    private fun maxAttemptsFor(
        error: Throwable,
        streamContext: PlaybackRetryContext,
        playbackStarted: Boolean
    ): Int {
        return when (PlayerErrorClassifier.classify(error)) {
            PlaybackErrorCategory.DRM,
            PlaybackErrorCategory.DECODER,
            PlaybackErrorCategory.CLEAR_TEXT_BLOCKED,
            PlaybackErrorCategory.SSL,
            PlaybackErrorCategory.HTTP_AUTH -> 0

            PlaybackErrorCategory.FORMAT_UNSUPPORTED -> if (playbackStarted) 1 else 0

            PlaybackErrorCategory.LIVE_WINDOW -> 1
            PlaybackErrorCategory.HTTP_SERVER -> {
                val isProgressive = streamContext.resolvedStreamType == ResolvedStreamType.PROGRESSIVE
                when {
                    streamContext.isLive -> LIVE_TRANSIENT_RETRY_ATTEMPTS
                    isProgressive -> 3
                    else -> 2
                }
            }
            PlaybackErrorCategory.PROVIDER_LIMIT,
            PlaybackErrorCategory.EMPTY_RESPONSE -> 0
            PlaybackErrorCategory.NETWORK -> when {
                error.hasCause<UnknownHostException>() -> 1
                error.hasCause<SocketTimeoutException>() || error.hasCause<ConnectException>() ->
                    when {
                        streamContext.isLive -> LIVE_TRANSIENT_RETRY_ATTEMPTS
                        streamContext.resolvedStreamType == ResolvedStreamType.PROGRESSIVE && playbackStarted ->
                            LIVE_TRANSIENT_RETRY_ATTEMPTS
                        else -> 2
                    }
                !playbackStarted -> if (streamContext.isLive) LIVE_TRANSIENT_RETRY_ATTEMPTS else 2
                else -> 1
            }

            PlaybackErrorCategory.SOURCE_MALFORMED -> when {
                streamContext.isLive && streamContext.resolvedStreamType == ResolvedStreamType.HLS && playbackStarted ->
                    LIVE_HLS_MALFORMED_RETRY_ATTEMPTS_AFTER_START
                playbackStarted -> 0
                else -> 1
            }
            PlaybackErrorCategory.UNKNOWN -> when {
                streamContext.isLive && playbackStarted -> LIVE_TRANSIENT_RETRY_ATTEMPTS
                playbackStarted -> 0
                else -> 1
            }
        }
    }

    private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean {
        return generateSequence(this) { it.cause }.any { it is T }
    }
}
