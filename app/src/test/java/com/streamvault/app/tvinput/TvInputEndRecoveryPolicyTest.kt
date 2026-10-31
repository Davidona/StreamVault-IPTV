package com.streamvault.app.tvinput

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.PlaybackContentKind
import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.model.StreamType
import com.streamvault.domain.model.DrmInfo
import com.streamvault.domain.model.DrmScheme
import org.junit.Test
import com.streamvault.player.playback.LiveMpegTsEndRecovery
import com.streamvault.player.playback.LiveStartupWatchdog

class TvInputEndRecoveryPolicyTest {
    private val live = StreamInfo("http://fixture.test/raw.ts", playbackContentKind = PlaybackContentKind.LIVE)
    private fun eligible(info: StreamInfo? = live, type: StreamType = StreamType.MPEG_TS,
        frame: Boolean = true, requested: Boolean = true, retry: Boolean = false) =
        canRecoverTvInputEnd(info, type, frame, requested, retry)

    @Test fun `only explicit HTTP live TS with rendered video can recover`() {
        assertThat(eligible()).isTrue()
        assertThat(eligible(live.copy(url = "https://fixture.test/raw.ts"))).isTrue()
        assertThat(eligible(frame = false)).isFalse()
        assertThat(eligible(requested = false)).isFalse()
        assertThat(eligible(retry = true)).isFalse()
        assertThat(eligible(null)).isFalse()
    }

    @Test fun `other source types and finite ownership never reopen on end`() {
        StreamType.entries.filter { it != StreamType.MPEG_TS }.forEach { assertThat(eligible(type = it)).isFalse() }
        listOf(PlaybackContentKind.UNKNOWN, PlaybackContentKind.ON_DEMAND, PlaybackContentKind.CATCH_UP).forEach {
            assertThat(eligible(live.copy(playbackContentKind = it))).isFalse()
        }
    }

    @Test fun `DRM RTSP local and invalid sources remain excluded`() {
        assertThat(eligible(live.copy(drmInfo = DrmInfo(DrmScheme.WIDEVINE, "https://license.test/")))).isFalse()
        listOf("rtsp://fixture.test/live", "file:///recording.ts", "content://recording/1", "invalid URI").forEach {
            assertThat(eligible(live.copy(url = it))).isFalse()
        }
    }

    @Test fun `spent EOF attempt prevents a second startup source refresh`() {
        val end = LiveMpegTsEndRecovery()
        assertThat(end.onEnded(0, eligible())).isTrue()
        end.reset(preserveAttempt = true)
        val startup = LiveStartupWatchdog()
        fun evaluate(time: Long) = startup.evaluate(time, eligible = true,
            firstVideoFrameRendered = false, audioOnlyPlaying = false, bufferedDurationMs = 0,
            sourceRetryAlreadyUsed = end.hasAttempted)
        assertThat(evaluate(0)).isEqualTo(LiveStartupWatchdog.Action.NONE)
        assertThat(evaluate(45_000)).isEqualTo(LiveStartupWatchdog.Action.GIVE_UP)
        end.reset()
        startup.reset()
        assertThat(evaluate(50_000)).isEqualTo(LiveStartupWatchdog.Action.NONE)
        assertThat(evaluate(95_000)).isEqualTo(LiveStartupWatchdog.Action.REFRESH_SOURCE)
    }
}
