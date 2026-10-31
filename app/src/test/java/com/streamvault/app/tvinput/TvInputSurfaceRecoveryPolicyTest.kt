package com.streamvault.app.tvinput

import androidx.media3.common.PlaybackException
import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.DrmInfo
import com.streamvault.domain.model.DrmScheme
import com.streamvault.domain.model.PlaybackContentKind
import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.model.StreamType
import com.streamvault.player.playback.LiveMpegTsEndRecovery
import com.streamvault.player.playback.StartupRefreshGate
import org.junit.Test

class TvInputSurfaceRecoveryPolicyTest {
    private val liveTs = StreamInfo(
        url = "http://fixture.test/live.ts",
        playbackContentKind = PlaybackContentKind.LIVE
    )

    private fun eligible(
        info: StreamInfo? = liveTs,
        type: StreamType = StreamType.MPEG_TS,
        firstFrame: Boolean = true,
        playWhenReady: Boolean = true,
        retryPending: Boolean = false,
        surfacePresent: Boolean = false,
        errorCode: Int = PlaybackException.ERROR_CODE_DECODING_FAILED
    ) = canRecoverTvInputSurfaceError(
        info = info,
        type = type,
        firstVideoFrame = firstFrame,
        playWhenReady = playWhenReady,
        retryPending = retryPending,
        surfacePresent = surfacePresent,
        errorCode = errorCode
    )

    @Test
    fun `only a decoded live HTTP TS surface-loss error is eligible`() {
        assertThat(eligible()).isTrue()
        assertThat(eligible(liveTs.copy(url = "https://fixture.test/live.ts"))).isTrue()
        assertThat(eligible(surfacePresent = true)).isFalse()
        assertThat(eligible(errorCode = PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)).isFalse()
        assertThat(eligible(info = null)).isFalse()
        assertThat(eligible(type = StreamType.HLS)).isFalse()
        assertThat(eligible(type = StreamType.RTSP)).isFalse()
        assertThat(eligible(type = StreamType.PROGRESSIVE)).isFalse()
    }

    @Test
    fun `startup pause retry DRM and non-live content remain ineligible`() {
        assertThat(eligible(firstFrame = false)).isFalse()
        assertThat(eligible(playWhenReady = false)).isFalse()
        assertThat(eligible(retryPending = true)).isFalse()
        assertThat(eligible(liveTs.copy(playbackContentKind = PlaybackContentKind.UNKNOWN))).isFalse()
        assertThat(eligible(liveTs.copy(playbackContentKind = PlaybackContentKind.ON_DEMAND))).isFalse()
        assertThat(eligible(liveTs.copy(playbackContentKind = PlaybackContentKind.CATCH_UP))).isFalse()
        assertThat(eligible(liveTs.copy(drmInfo = DrmInfo(DrmScheme.WIDEVINE, "https://license.test/")))).isFalse()
        assertThat(eligible(liveTs.copy(url = "rtsp://fixture.test/live.ts"))).isFalse()
        assertThat(eligible(liveTs.copy(url = "file:///recording.ts"))).isFalse()
    }

    @Test
    fun `eligible surface repair reuses one-shot drain and quiet gate`() {
        val recovery = LiveMpegTsEndRecovery()

        assertThat(recovery.onEnded(0L, eligible = eligible())).isTrue()
        assertThat(recovery.isPending).isTrue()
        assertThat(recovery.evaluate(1_000L, canResume = true, hasActiveTransfers = true))
            .isEqualTo(StartupRefreshGate.Action.WAIT)
        assertThat(recovery.evaluate(2_000L, canResume = true, hasActiveTransfers = false))
            .isEqualTo(StartupRefreshGate.Action.WAIT)
        assertThat(recovery.evaluate(2_499L, canResume = true, hasActiveTransfers = false))
            .isEqualTo(StartupRefreshGate.Action.WAIT)
        assertThat(recovery.evaluate(2_500L, canResume = true, hasActiveTransfers = false))
            .isEqualTo(StartupRefreshGate.Action.READY)
        assertThat(recovery.isPending).isFalse()

        recovery.reset(preserveAttempt = true)
        assertThat(recovery.onEnded(3_000L, eligible = eligible())).isFalse()
        recovery.reset()
        assertThat(recovery.onEnded(4_000L, eligible = eligible())).isTrue()
    }
}
