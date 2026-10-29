package com.streamvault.player.playback

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.source.BehindLiveWindowException
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.streamvault.domain.model.PlaybackContentKind
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap

private const val PLAYBACK_PERF_TAG = "PlaybackPerf"
private const val FAST_TRANSIENT_RETRY_DELAY_MS = 500L
private const val MAX_WHOLE_SOURCE_RETRIES_PER_OUTAGE = 1
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
    private val onLoadRecoveryChanged: () -> Unit = {},
    private val playbackStarted: () -> Boolean
) : LoadErrorHandlingPolicy {
    private val defaultFallbackPolicy = DefaultLoadErrorHandlingPolicy()
    private val pendingLoadRecoveryTaskIds = ConcurrentHashMap.newKeySet<Long>()
    // Published from load callbacks for the application-thread fatal-error handler. Keep
    // exact exception identities: a playlist/auth error with the same status is not media.
    @Volatile private var exhaustedMediaRefusals: List<Throwable> = emptyList()

    /** True while Media3 still owns a retry or fallback for a failed load task. */
    val hasPendingLoadRecovery: Boolean
        get() = pendingLoadRecoveryTaskIds.isNotEmpty()

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

    /**
     * App-level source replacement is a last resort after Media3 has exhausted its
     * per-load retry and fallback handling. Allow one replacement per outage episode.
     */
    fun shouldRetryWholeSource(error: Throwable, playbackStarted: Boolean, attempt: Int): Boolean =
        attempt <= MAX_WHOLE_SOURCE_RETRIES_PER_OUTAGE &&
            (playbackStarted && isLiveMediaRefusal(error) ||
                shouldRetry(error, streamContext, playbackStarted, attempt))

    fun maxWholeSourceRetryAttempts(error: Throwable, playbackStarted: Boolean): Int =
        if (playbackStarted && isLiveMediaRefusal(error) || maxAttempts(error, playbackStarted) > 0)
            MAX_WHOLE_SOURCE_RETRIES_PER_OUTAGE else 0

    internal fun isLiveMediaRefusal(error: Throwable): Boolean {
        val refusals = exhaustedMediaRefusals
        return generateSequence(error) { it.cause }.any { cause -> refusals.any { it === cause } }
    }

    fun clearPendingLoadRecovery() {
        val hadPendingRecovery = pendingLoadRecoveryTaskIds.isNotEmpty()
        pendingLoadRecoveryTaskIds.clear()
        if (hadPendingRecovery) onLoadRecoveryChanged()
    }

    fun retryDelayMs(error: Throwable, attempt: Int): Long {
        if (isLiveMediaRefusal(error)) return FAST_TRANSIENT_RETRY_DELAY_MS
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
            PlaybackErrorCategory.HTTP_NOT_FOUND,
            PlaybackErrorCategory.SSL,
            PlaybackErrorCategory.CLEAR_TEXT_BLOCKED,
            PlaybackErrorCategory.DRM,
            PlaybackErrorCategory.DECODER,
            PlaybackErrorCategory.FORMAT_UNSUPPORTED -> false
        }
    }

    fun retryReason(error: Throwable): String {
        if (isLiveMediaRefusal(error)) return "refresh-live-media-playlist"
        return when (PlayerErrorClassifier.classify(error)) {
            PlaybackErrorCategory.LIVE_WINDOW -> "refresh-live-window"
            PlaybackErrorCategory.NETWORK -> "transient-network"
            PlaybackErrorCategory.HTTP_SERVER -> "server-retryable"
            PlaybackErrorCategory.HTTP_NOT_FOUND -> "source-not-found"
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
    ): LoadErrorHandlingPolicy.FallbackSelection? {
        val adaptiveSource = when (streamContext.resolvedStreamType) {
            ResolvedStreamType.HLS,
            ResolvedStreamType.DASH,
            ResolvedStreamType.SMOOTH_STREAMING -> true
            else -> false
        }
        if (!adaptiveSource) return null

        val category = PlayerErrorClassifier.classify(loadErrorInfo.exception)
        if (category != PlaybackErrorCategory.HTTP_SERVER &&
            category != PlaybackErrorCategory.HTTP_NOT_FOUND &&
            category != PlaybackErrorCategory.UNKNOWN
        ) return null

        val selection = defaultFallbackPolicy.getFallbackSelectionFor(fallbackOptions, loadErrorInfo)
        if (selection != null) {
            markLoadRecoveryPending(loadErrorInfo.loadEventInfo.loadTaskId)
        }
        return selection
    }

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
        val category = PlayerErrorClassifier.classify(error)
        val mediaRefusal = started && streamContext.isLive &&
            streamContext.resolvedStreamType == ResolvedStreamType.HLS &&
            loadErrorInfo.mediaLoadData.dataType == C.DATA_TYPE_MEDIA &&
            generateSequence<Throwable>(error) { it.cause }
                .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
                .any { it.responseCode in setOf(403, 404, 410) }
        val retryDelayMs = if (mediaRefusal) {
            if (attempt == 1) FAST_TRANSIENT_RETRY_DELAY_MS else C.TIME_UNSET
        } else if (shouldRetry(error, streamContext, started, attempt)) {
            retryDelayMs(error, attempt)
        } else {
            C.TIME_UNSET
        }
        if (mediaRefusal && attempt > 1 && retryDelayMs == C.TIME_UNSET &&
            exhaustedMediaRefusals.none { it === error }
        ) {
            // Multiple audio/video loads can fail together. Bound retention even if a
            // track is abandoned before its fatal error reaches the player.
            exhaustedMediaRefusals = (exhaustedMediaRefusals + error).takeLast(8)
        }
        if (Log.isLoggable(PLAYBACK_PERF_TAG, Log.DEBUG)) {
            val causeTypes = generateSequence<Throwable>(error) { it.cause }
                .map { it.javaClass.simpleName }
                .distinct()
                .joinToString(">")
            Log.d(
                PLAYBACK_PERF_TAG,
                "load-retry category=$category attempt=$attempt delayMs=$retryDelayMs " +
                    "dataType=${loadErrorInfo.mediaLoadData.dataType} mediaRefusal=$mediaRefusal " +
                    "started=$started streamType=${streamContext.resolvedStreamType} " +
                    "contentKind=${streamContext.playbackContentKind} causes=$causeTypes"
            )
        }
        if (retryDelayMs != C.TIME_UNSET) {
            markLoadRecoveryPending(loadErrorInfo.loadEventInfo.loadTaskId)
        }
        return retryDelayMs
    }

    override fun onLoadTaskConcluded(loadTaskId: Long) {
        if (pendingLoadRecoveryTaskIds.remove(loadTaskId)) onLoadRecoveryChanged()
    }

    private fun markLoadRecoveryPending(loadTaskId: Long) {
        if (pendingLoadRecoveryTaskIds.add(loadTaskId)) onLoadRecoveryChanged()
    }

    private fun maxAttemptsFor(
        error: Throwable,
        streamContext: PlaybackRetryContext,
        playbackStarted: Boolean
    ): Int {
        val category = PlayerErrorClassifier.classify(error)
        if (streamContext.isLive && streamContext.resolvedStreamType == ResolvedStreamType.HLS &&
            category in setOf(PlaybackErrorCategory.NETWORK, PlaybackErrorCategory.UNKNOWN) &&
            isHlsTimestampInitializationTimeout(error)
        ) {
            // A dependency wait is not a failed socket read. Repeating the normal live
            // request budget would turn the finite wait into minutes before refreshing.
            return 1
        }
        return when (category) {
            PlaybackErrorCategory.DRM,
            PlaybackErrorCategory.DECODER,
            PlaybackErrorCategory.CLEAR_TEXT_BLOCKED,
            PlaybackErrorCategory.SSL,
            PlaybackErrorCategory.HTTP_AUTH,
            PlaybackErrorCategory.HTTP_NOT_FOUND -> 0

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
