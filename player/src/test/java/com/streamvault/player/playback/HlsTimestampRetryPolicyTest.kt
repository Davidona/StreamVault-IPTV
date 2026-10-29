@file:androidx.media3.common.util.UnstableApi

package com.streamvault.player.playback

import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.PlaybackContentKind
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeoutException
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HlsTimestampRetryPolicyTest {
    @Test
    fun `live dependency wait has one load retry before and after playback starts`() {
        for (started in listOf(false, true)) {
            val policy = policy(PlaybackContentKind.LIVE, started)
            val error = dependencyTimeout()
            val firstFailure = loadError(error, 1)
            assertThat(policy.getRetryDelayMsFor(firstFailure)).isEqualTo(1_000L)
            assertThat(policy.hasPendingLoadRecovery).isTrue()
            val exhausted = LoadErrorHandlingPolicy.LoadErrorInfo(
                firstFailure.loadEventInfo, firstFailure.mediaLoadData, error, 2
            )
            assertThat(policy.getRetryDelayMsFor(exhausted)).isEqualTo(C.TIME_UNSET)
            policy.onLoadTaskConcluded(firstFailure.loadEventInfo.loadTaskId)
            assertThat(policy.hasPendingLoadRecovery).isFalse()
            assertThat(policy.maxAttempts(error, started)).isEqualTo(1)
            val fatal = PlaybackException("dependency wait", error, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
            assertThat(policy.shouldRetryWholeSource(fatal, started, 1)).isTrue()
            assertThat(policy.shouldRetryWholeSource(fatal, started, 2)).isFalse()
        }
    }

    @Test
    fun `ordinary live socket failures keep existing retry budget`() {
        val policy = policy(PlaybackContentKind.LIVE, false)
        assertThat(policy.maxAttempts(SocketTimeoutException("read timeout"), false)).isEqualTo(10)
        assertThat(policy.maxAttempts(IOException(TimeoutException("another dependency")), true)).isEqualTo(10)
    }

    @Test
    fun `finite hls retry behavior is unchanged`() {
        val generic = IOException(TimeoutException("another dependency"))
        for (started in listOf(false, true)) {
            val policy = policy(PlaybackContentKind.ON_DEMAND, started)
            assertThat(policy.maxAttempts(dependencyTimeout(), started))
                .isEqualTo(policy.maxAttempts(generic, started))
        }
    }

    @Test
    fun `dependency cause does not override terminal auth or drm categories`() {
        val policy = policy(PlaybackContentKind.LIVE, true)
        assertThat(policy.maxAttempts(IOException("HTTP 403", dependencyTimeout()), true)).isEqualTo(0)
        assertThat(policy.maxAttempts(PlaybackException(
            "license failure", dependencyTimeout(), PlaybackException.ERROR_CODE_DRM_UNSPECIFIED
        ), true)).isEqualTo(0)
    }

    @Test
    fun `non hls live source does not inherit dependency retry cap`() {
        val policy = PlayerRetryPolicy(PlaybackRetryContext(
            ResolvedStreamType.MPEG_TS_LIVE, PlayerTimeoutProfile.LIVE, PlaybackContentKind.LIVE
        )) { true }
        assertThat(policy.maxAttempts(dependencyTimeout(), true)).isEqualTo(10)
    }

    private fun policy(kind: PlaybackContentKind, started: Boolean) = PlayerRetryPolicy(
        PlaybackRetryContext(ResolvedStreamType.HLS, PlayerTimeoutProfile.LIVE, kind)
    ) { started }

    private fun dependencyTimeout() = IOException(TimeoutException(
        "TimestampAdjuster failed to initialize in 30000 milliseconds"
    ))

    private fun loadError(error: IOException, count: Int): LoadErrorHandlingPolicy.LoadErrorInfo {
        val spec = DataSpec.Builder().setUri("https://example.test/audio.ts").build()
        return LoadErrorHandlingPolicy.LoadErrorInfo(
            LoadEventInfo.Builder(LoadEventInfo.getNewId(), spec, 0L).build(),
            MediaLoadData(C.DATA_TYPE_MEDIA), error, count
        )
    }
}
