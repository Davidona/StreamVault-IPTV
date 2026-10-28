package com.streamvault.player.playback

import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.ParserException
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.source.BehindLiveWindowException
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.PlaybackContentKind
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlayerRetryPolicyTest {

    private val liveContext = PlaybackRetryContext(
        resolvedStreamType = ResolvedStreamType.HLS,
        timeoutProfile = PlayerTimeoutProfile.LIVE
    )
    private val progressiveContext = PlaybackRetryContext(
        resolvedStreamType = ResolvedStreamType.PROGRESSIVE,
        timeoutProfile = PlayerTimeoutProfile.PROGRESSIVE
    )

    private val policy = PlayerRetryPolicy(liveContext) { false }
    private val progressivePolicy = PlayerRetryPolicy(progressiveContext) { true }
    private val fastRetryPolicy = PlayerRetryPolicy(
        streamContext = liveContext,
        playbackStarted = { true },
        fastRetryOnTransientFailures = { true }
    )

    @Test
    fun `retrying Media3 load owns recovery until load task concludes`() {
        val errorInfo = loadError(responseCode = 503)

        assertThat(policy.hasPendingLoadRecovery).isFalse()
        assertThat(policy.getRetryDelayMsFor(errorInfo)).isEqualTo(1_000L)
        assertThat(policy.hasPendingLoadRecovery).isTrue()

        policy.onLoadTaskConcluded(errorInfo.loadEventInfo.loadTaskId)

        assertThat(policy.hasPendingLoadRecovery).isFalse()
    }

    @Test
    fun `load recovery transitions notify foreground priority immediately`() {
        var notifications = 0
        val observed = PlayerRetryPolicy(
            streamContext = liveContext,
            onLoadRecoveryChanged = { notifications++ },
            playbackStarted = { true }
        )
        val errorInfo = loadError(responseCode = 503)

        observed.getRetryDelayMsFor(errorInfo)
        observed.onLoadTaskConcluded(errorInfo.loadEventInfo.loadTaskId)

        assertThat(notifications).isEqualTo(2)
    }

    @Test
    fun `Media3 fallback owns recovery until load task concludes`() {
        val errorInfo = loadError(responseCode = 503)

        assertThat(
            policy.getFallbackSelectionFor(
                fallbackOptions(locations = 2, tracks = 1),
                errorInfo
            )
        ).isNotNull()
        assertThat(policy.hasPendingLoadRecovery).isTrue()

        policy.onLoadTaskConcluded(errorInfo.loadEventInfo.loadTaskId)

        assertThat(policy.hasPendingLoadRecovery).isFalse()
    }

    @Test
    fun `whole source retries are capped after Media3 has exhausted load retry`() {
        val error = SocketTimeoutException("timed out")

        assertThat(policy.shouldRetryWholeSource(error, playbackStarted = true, attempt = 1)).isTrue()
        assertThat(policy.shouldRetryWholeSource(error, playbackStarted = true, attempt = 2)).isFalse()
    }

    @Test
    fun `terminal errors never use whole source retry budget`() {
        assertThat(
            policy.shouldRetryWholeSource(
                IOException("HTTP 403"),
                playbackStarted = true,
                attempt = 1
            )
        ).isFalse()
    }

    @Test
    fun `live server errors retry 10 times with bounded backoff`() {
        val error = IOException("HTTP 500")
        assertThat(policy.shouldRetry(error, liveContext, playbackStarted = false, attempt = 1)).isTrue()
        assertThat(policy.shouldRetry(error, liveContext, playbackStarted = false, attempt = 2)).isTrue()
        assertThat(policy.shouldRetry(error, liveContext, playbackStarted = false, attempt = 3)).isTrue()
        assertThat(policy.shouldRetry(error, liveContext, playbackStarted = false, attempt = 10)).isTrue()
        assertThat(policy.shouldRetry(error, liveContext, playbackStarted = false, attempt = 11)).isFalse()
        assertThat(policy.maxAttempts(error, playbackStarted = false)).isEqualTo(10)
        assertThat(policy.retryDelayMs(error, 1)).isEqualTo(1000L)
        assertThat(policy.retryDelayMs(error, 2)).isEqualTo(2500L)
        assertThat(policy.retryDelayMs(error, 3)).isEqualTo(5000L)
        assertThat(policy.retryDelayMs(error, 10)).isEqualTo(5000L)
    }

    @Test
    fun `403 never retries`() {
        assertThat(policy.shouldRetry(IOException("HTTP 403"), liveContext, playbackStarted = false, attempt = 1))
            .isFalse()
    }

    @Test
    fun `509 never retries`() {
        val error = IOException("HTTP 509")
        assertThat(policy.shouldRetry(error, liveContext, playbackStarted = false, attempt = 1)).isFalse()
        assertThat(policy.shouldRetry(error, liveContext, playbackStarted = true, attempt = 1)).isFalse()
        assertThat(policy.maxAttempts(error, playbackStarted = false)).isEqualTo(0)
        assertThat(policy.maxAttempts(error, playbackStarted = true)).isEqualTo(0)
    }

    @Test
    fun `auth failure after playback start stays terminal`() {
        val error = IOException("HTTP 403")

        assertThat(policy.shouldRetry(error, liveContext, playbackStarted = true, attempt = 1)).isFalse()
        assertThat(policy.maxAttempts(error, playbackStarted = true)).isEqualTo(0)
        assertThat(policy.retryReason(error)).isEqualTo("terminal-auth")
    }

    @Test
    fun `ssl error never retries`() {
        assertThat(policy.shouldRetry(SSLHandshakeException("bad cert"), liveContext, playbackStarted = false, attempt = 1))
            .isFalse()
    }

    @Test
    fun `ssl failure after playback start stays terminal`() {
        val error = SSLHandshakeException("certificate verify failed")

        assertThat(policy.shouldRetry(error, liveContext, playbackStarted = true, attempt = 1)).isFalse()
        assertThat(policy.maxAttempts(error, playbackStarted = true)).isEqualTo(0)
        assertThat(policy.retryReason(error)).isEqualTo("terminal-tls")
    }

    @Test
    fun `cleartext failure after playback start stays terminal`() {
        val error = IOException("cleartext traffic not permitted")

        assertThat(policy.shouldRetry(error, liveContext, playbackStarted = true, attempt = 1)).isFalse()
        assertThat(policy.maxAttempts(error, playbackStarted = true)).isEqualTo(0)
        assertThat(policy.retryReason(error)).isEqualTo("terminal-cleartext")
    }

    @Test
    fun `drm failure after playback start stays terminal`() {
        val error = TestPlaybackException(
            "drm license failed",
            PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED
        )

        assertThat(policy.shouldRetry(error, liveContext, playbackStarted = true, attempt = 1)).isFalse()
        assertThat(policy.maxAttempts(error, playbackStarted = true)).isEqualTo(0)
        assertThat(policy.retryReason(error)).isEqualTo("terminal-drm")
    }

    @Test
    fun `behind live window retries once`() {
        val error = BehindLiveWindowException()
        assertThat(policy.shouldRetry(error, liveContext, playbackStarted = false, attempt = 1)).isTrue()
        assertThat(policy.shouldRetry(error, liveContext, playbackStarted = false, attempt = 2)).isFalse()
        assertThat(policy.retryDelayMs(error, 1)).isEqualTo(1000L)
        assertThat(policy.retryReason(error)).isEqualTo("refresh-live-window")
    }

    @Test
    fun `fast transient retry mode uses 500 ms for live refresh failures`() {
        val error = BehindLiveWindowException()

        assertThat(fastRetryPolicy.retryDelayMs(error, 1)).isEqualTo(500L)
        assertThat(fastRetryPolicy.retryDelayMs(error, 2)).isEqualTo(500L)
    }

    @Test
    fun `decoder init failure does not go through network retry policy`() {
        val error = IllegalStateException("decoder init failed")
        assertThat(policy.shouldRetry(error, liveContext, playbackStarted = false, attempt = 1)).isFalse()
        assertThat(policy.retryReason(error)).isEqualTo("terminal-decoder")
    }

    @Test
    fun `format unsupported after playback start retries once`() {
        val error = IllegalStateException("video format unsupported")
        assertThat(policy.shouldRetry(error, liveContext, playbackStarted = true, attempt = 1)).isTrue()
        assertThat(policy.shouldRetry(error, liveContext, playbackStarted = true, attempt = 2)).isFalse()
        assertThat(policy.maxAttempts(error, playbackStarted = true)).isEqualTo(1)
    }

    @Test
    fun `decoder init failure after playback start still does not retry`() {
        val error = IllegalStateException("decoder init failed")
        assertThat(policy.shouldRetry(error, liveContext, playbackStarted = true, attempt = 1)).isFalse()
    }

    @Test
    fun `progressive movie server errors get tolerant recovery after playback start`() {
        val error = IOException("HTTP 502")
        assertThat(progressivePolicy.shouldRetry(error, progressiveContext, playbackStarted = true, attempt = 1)).isTrue()
        assertThat(progressivePolicy.shouldRetry(error, progressiveContext, playbackStarted = true, attempt = 2)).isTrue()
        assertThat(progressivePolicy.shouldRetry(error, progressiveContext, playbackStarted = true, attempt = 3)).isTrue()
        assertThat(progressivePolicy.shouldRetry(error, progressiveContext, playbackStarted = true, attempt = 4)).isFalse()
        assertThat(progressivePolicy.maxAttempts(error, playbackStarted = true)).isEqualTo(3)
        assertThat(progressivePolicy.retryReason(error)).isEqualTo("server-retryable")
    }

    @Test
    fun `eligible server error falls back to an available location`() {
        val selection = policy.getFallbackSelectionFor(
            fallbackOptions(locations = 2, tracks = 1),
            loadError(responseCode = 503)
        )

        assertThat(selection?.type).isEqualTo(LoadErrorHandlingPolicy.FALLBACK_TYPE_LOCATION)
        assertThat(selection?.exclusionDurationMs)
            .isEqualTo(DefaultLoadErrorHandlingPolicy.DEFAULT_LOCATION_EXCLUSION_MS)
    }

    @Test
    fun `404 skips retrying the missing resource but uses an available adaptive fallback`() {
        val errorInfo = loadError(responseCode = 404)
        val error = errorInfo.exception

        assertThat(PlayerErrorClassifier.classify(error)).isEqualTo(PlaybackErrorCategory.HTTP_NOT_FOUND)
        assertThat(policy.shouldRetry(error, liveContext, playbackStarted = true, attempt = 1)).isFalse()
        assertThat(policy.shouldRetryWholeSource(error, playbackStarted = true, attempt = 1)).isFalse()

        val fallback = policy.getFallbackSelectionFor(
            fallbackOptions(locations = 2, tracks = 1),
            errorInfo
        )
        assertThat(fallback?.type).isEqualTo(LoadErrorHandlingPolicy.FALLBACK_TYPE_LOCATION)
    }

    @Test
    fun `eligible server error falls back to an available track when no alternate location exists`() {
        val selection = policy.getFallbackSelectionFor(
            fallbackOptions(locations = 1, tracks = 2),
            loadError(responseCode = 503)
        )

        assertThat(selection?.type).isEqualTo(LoadErrorHandlingPolicy.FALLBACK_TYPE_TRACK)
        assertThat(selection?.exclusionDurationMs)
            .isEqualTo(DefaultLoadErrorHandlingPolicy.DEFAULT_TRACK_EXCLUSION_MS)
    }

    @Test
    fun `authentication error does not fall back and a single option has no fallback`() {
        val options = fallbackOptions(locations = 2, tracks = 2)

        assertThat(policy.getFallbackSelectionFor(options, loadError(responseCode = 403))).isNull()
        assertThat(
            policy.getFallbackSelectionFor(
                fallbackOptions(locations = 1, tracks = 1),
                loadError(responseCode = 503)
            )
        ).isNull()
    }

    @Test
    fun `progressive source does not use adaptive fallback even when an alternate location exists`() {
        assertThat(
            progressivePolicy.getFallbackSelectionFor(
                fallbackOptions(locations = 2, tracks = 1),
                loadError(responseCode = 503)
            )
        ).isNull()
    }

    @Test
    fun `explicit hls vod uses finite source retry treatment`() {
        val vodHlsContext = PlaybackRetryContext(
            resolvedStreamType = ResolvedStreamType.HLS,
            timeoutProfile = PlayerTimeoutProfile.VOD,
            playbackContentKind = PlaybackContentKind.ON_DEMAND
        )
        val vodHlsPolicy = PlayerRetryPolicy(vodHlsContext) { true }
        val malformed = ParserException.createForMalformedContainer("bad movie segment", null)

        assertThat(vodHlsContext.isLive).isFalse()
        assertThat(vodHlsPolicy.maxAttempts(IOException("HTTP 503"), playbackStarted = false)).isEqualTo(2)
        assertThat(vodHlsPolicy.maxAttempts(malformed, playbackStarted = true)).isEqualTo(0)
        assertThat(vodHlsPolicy.retryReason(malformed)).isEqualTo("malformed-source")
    }

    @Test
    fun `explicit live dash uses live retry treatment`() {
        val liveDashContext = PlaybackRetryContext(
            resolvedStreamType = ResolvedStreamType.DASH,
            timeoutProfile = PlayerTimeoutProfile.LIVE,
            playbackContentKind = PlaybackContentKind.LIVE
        )
        val liveDashPolicy = PlayerRetryPolicy(liveDashContext) { true }

        assertThat(liveDashContext.isLive).isTrue()
        assertThat(liveDashPolicy.maxAttempts(IOException("HTTP 503"), playbackStarted = false)).isEqualTo(10)
    }

    @Test
    fun `network errors keep transient retry reason`() {
        val error = SocketTimeoutException("timed out")

        assertThat(policy.retryReason(error)).isEqualTo("transient-network")
    }

    @Test
    fun `live loadable retry count uses 10 attempt ceiling`() {
        assertThat(policy.getMinimumLoadableRetryCount(C.DATA_TYPE_MANIFEST)).isEqualTo(10)
        assertThat(policy.getMinimumLoadableRetryCount(C.DATA_TYPE_MEDIA)).isEqualTo(10)
    }

    @Test
    fun `live network timeouts retry 10 times`() {
        val error = SocketTimeoutException("timed out")

        assertThat(policy.shouldRetry(error, liveContext, playbackStarted = true, attempt = 10)).isTrue()
        assertThat(policy.shouldRetry(error, liveContext, playbackStarted = true, attempt = 11)).isFalse()
        assertThat(policy.maxAttempts(error, playbackStarted = true)).isEqualTo(10)
    }

    @Test
    fun `progressive connection reset after playback start is retried in place`() {
        val error = SocketException("Connection reset")

        assertThat(progressivePolicy.shouldRetry(error, progressiveContext, playbackStarted = true, attempt = 1))
            .isTrue()
        assertThat(progressivePolicy.shouldRetry(error, progressiveContext, playbackStarted = true, attempt = 10))
            .isTrue()
        assertThat(progressivePolicy.shouldRetry(error, progressiveContext, playbackStarted = true, attempt = 11))
            .isFalse()
    }

    @Test
    fun `progressive body truncated after playback start is retried in place`() {
        // OkHttp's error when an idle connection is closed cleanly before Content-Length, as Media3 wraps it.
        val error = HttpDataSource.HttpDataSourceException(
            java.net.ProtocolException("unexpected end of stream"),
            DataSpec.Builder().setUri("https://example.com/movie.mp4").build(),
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            HttpDataSource.HttpDataSourceException.TYPE_READ
        )

        assertThat(PlayerErrorClassifier.classify(error)).isEqualTo(PlaybackErrorCategory.NETWORK)
        assertThat(progressivePolicy.shouldRetry(error, progressiveContext, playbackStarted = true, attempt = 10))
            .isTrue()
        assertThat(progressivePolicy.shouldRetry(error, progressiveContext, playbackStarted = true, attempt = 11))
            .isFalse()
    }

    @Test
    fun `live connection reset after playback start keeps the live retry budget`() {
        val error = SocketException("Connection reset")

        assertThat(policy.shouldRetry(error, liveContext, playbackStarted = true, attempt = 10)).isTrue()
        assertThat(policy.shouldRetry(error, liveContext, playbackStarted = true, attempt = 11)).isFalse()
    }

    @Test
    fun `progressive network timeouts after playback start retry 10 times`() {
        val error = SocketTimeoutException("timed out")

        assertThat(progressivePolicy.shouldRetry(error, progressiveContext, playbackStarted = true, attempt = 10))
            .isTrue()
        assertThat(progressivePolicy.shouldRetry(error, progressiveContext, playbackStarted = true, attempt = 11))
            .isFalse()
        assertThat(progressivePolicy.maxAttempts(error, playbackStarted = true)).isEqualTo(10)
    }

    @Test
    fun `malformed hls refresh after playback start retries before app recovery`() {
        val error = ParserException.createForMalformedContainer("bad live segment", null)
        assertThat(policy.shouldRetry(error, liveContext, playbackStarted = true, attempt = 1)).isTrue()
        assertThat(policy.shouldRetry(error, liveContext, playbackStarted = true, attempt = 12)).isTrue()
        assertThat(policy.shouldRetry(error, liveContext, playbackStarted = true, attempt = 13)).isFalse()
        assertThat(policy.maxAttempts(error, playbackStarted = true)).isEqualTo(12)
        assertThat(policy.retryReason(error)).isEqualTo("malformed-live-hls-refresh")
        assertThat(policy.retryDelayMs(error, 1)).isEqualTo(1000L)
        assertThat(policy.retryDelayMs(error, 2)).isEqualTo(2500L)
        assertThat(policy.retryDelayMs(error, 3)).isEqualTo(5000L)
    }

    @Test
    fun `fast transient retry mode uses 500 ms for live hls refresh failures`() {
        val error = ParserException.createForMalformedContainer("bad live segment", null)

        assertThat(fastRetryPolicy.retryDelayMs(error, 1)).isEqualTo(500L)
        assertThat(fastRetryPolicy.retryDelayMs(error, 2)).isEqualTo(500L)
        assertThat(fastRetryPolicy.retryDelayMs(error, 3)).isEqualTo(500L)
    }

    @Test
    fun `fast transient retry mode uses 500 ms for live network and server failures`() {
        assertThat(fastRetryPolicy.retryDelayMs(SocketTimeoutException("timed out"), 1)).isEqualTo(500L)
        assertThat(fastRetryPolicy.retryDelayMs(IOException("HTTP 503"), 2)).isEqualTo(500L)
    }

    @Test
    fun `malformed progressive movie after playback start stays fatal`() {
        val error = ParserException.createForMalformedContainer("bad movie segment", null)
        assertThat(progressivePolicy.shouldRetry(error, progressiveContext, playbackStarted = true, attempt = 1)).isFalse()
    }

    @Test
    fun `unknown live runtime error after playback start retries before surfacing`() {
        val error = RuntimeException("Unexpected runtime error")
        assertThat(policy.shouldRetry(error, liveContext, playbackStarted = true, attempt = 1)).isTrue()
        assertThat(policy.shouldRetry(error, liveContext, playbackStarted = true, attempt = 2)).isTrue()
        assertThat(policy.shouldRetry(error, liveContext, playbackStarted = true, attempt = 3)).isTrue()
        assertThat(policy.shouldRetry(error, liveContext, playbackStarted = true, attempt = 10)).isTrue()
        assertThat(policy.shouldRetry(error, liveContext, playbackStarted = true, attempt = 11)).isFalse()
        assertThat(policy.maxAttempts(error, playbackStarted = true)).isEqualTo(10)
    }

    private class TestPlaybackException(
        message: String,
        errorCode: Int
    ) : PlaybackException(message, null, errorCode, Bundle.EMPTY, 0L)

    private fun fallbackOptions(locations: Int, tracks: Int) =
        LoadErrorHandlingPolicy.FallbackOptions(locations, 0, tracks, 0, false)

    private fun loadError(responseCode: Int): LoadErrorHandlingPolicy.LoadErrorInfo {
        val dataSpec = DataSpec.Builder()
            .setUri("https://example.com/manifest.m3u8")
            .build()
        val exception = HttpDataSource.InvalidResponseCodeException(
            responseCode,
            null,
            null,
            emptyMap(),
            dataSpec,
            byteArrayOf()
        )
        val loadEventInfo = LoadEventInfo.Builder(LoadEventInfo.getNewId(), dataSpec, 0L).build()
        return LoadErrorHandlingPolicy.LoadErrorInfo(
            loadEventInfo,
            MediaLoadData(C.DATA_TYPE_MEDIA),
            exception,
            1
        )
    }
}
