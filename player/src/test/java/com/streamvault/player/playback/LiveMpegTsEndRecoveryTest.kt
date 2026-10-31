package com.streamvault.player.playback

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.DrmInfo
import com.streamvault.domain.model.DrmScheme
import com.streamvault.domain.model.PlaybackContentKind
import com.streamvault.domain.model.StreamInfo
import org.junit.Test

class LiveMpegTsEndRecoveryTest {
    private val live = StreamInfo("https://example.test/live.ts", playbackContentKind = PlaybackContentKind.LIVE)

    private fun eligible(
        info: StreamInfo? = live,
        type: ResolvedStreamType = ResolvedStreamType.MPEG_TS_LIVE,
        started: Boolean = true,
        requested: Boolean = true,
        owned: Boolean = false,
        retry: Boolean = false
    ) = canRecoverLiveMpegTsEnd(info, type, started, requested, owned, retry)

    @Test fun `only explicit clear HTTP live TS can refresh after an end`() {
        assertThat(eligible()).isTrue()
        assertThat(eligible(live.copy(url = "http://example.test/live.ts"))).isTrue()
        for (kind in listOf(PlaybackContentKind.UNKNOWN, PlaybackContentKind.ON_DEMAND, PlaybackContentKind.CATCH_UP)) {
            assertThat(eligible(live.copy(playbackContentKind = kind))).isFalse()
        }
        for (type in ResolvedStreamType.entries.filter { it != ResolvedStreamType.MPEG_TS_LIVE }) {
            assertThat(eligible(type = type)).isFalse()
        }
        for (url in listOf("file:///recording.ts", "content://recording/1", "rtsp://example.test/live", "malformed URL")) {
            assertThat(eligible(live.copy(url = url))).isFalse()
        }
        assertThat(eligible(null)).isFalse()
        assertThat(eligible(live.copy(drmInfo = DrmInfo(DrmScheme.WIDEVINE, "https://example.test/license")))).isFalse()
    }

    @Test fun `no refresh before playback or against another owner or paused user`() {
        assertThat(eligible(started = false)).isFalse()
        assertThat(eligible(requested = false)).isFalse()
        assertThat(eligible(owned = true)).isFalse()
        assertThat(eligible(retry = true)).isFalse()
    }

    @Test fun `one refresh waits for active sources and quiet interval`() {
        val recovery = LiveMpegTsEndRecovery()
        assertThat(recovery.hasAttempted).isFalse()
        assertThat(recovery.onEnded(0, eligible = true)).isTrue()
        assertThat(recovery.hasAttempted).isTrue()
        assertThat(recovery.evaluate(1_000, true, true)).isEqualTo(StartupRefreshGate.Action.WAIT)
        assertThat(recovery.evaluate(2_000, true, false)).isEqualTo(StartupRefreshGate.Action.WAIT)
        assertThat(recovery.evaluate(2_499, true, false)).isEqualTo(StartupRefreshGate.Action.WAIT)
        assertThat(recovery.evaluate(2_500, true, false)).isEqualTo(StartupRefreshGate.Action.READY)
        assertThat(recovery.isPending).isFalse()
        recovery.reset(preserveAttempt = true)
        assertThat(recovery.hasAttempted).isTrue()
        assertThat(recovery.onEnded(10_000, eligible = true)).isFalse()
        recovery.reset()
        assertThat(recovery.hasAttempted).isFalse()
        assertThat(recovery.onEnded(20_000, eligible = true)).isTrue()
    }

    @Test fun `undrained source ends the attempt without opening another`() {
        val recovery = LiveMpegTsEndRecovery()
        recovery.onEnded(0, eligible = true)
        assertThat(recovery.evaluate(25_000, true, true)).isEqualTo(StartupRefreshGate.Action.GIVE_UP)
        assertThat(recovery.abandoned).isTrue()
        assertThat(recovery.isPending).isFalse()
        assertThat(recovery.onEnded(30_000, eligible = true)).isFalse()
    }

    @Test fun `pause suspends drain clock and does not replenish refresh budget`() {
        val recovery = LiveMpegTsEndRecovery()
        recovery.onEnded(0, eligible = true)
        recovery.pauseClock()
        assertThat(recovery.evaluate(100_000, false, true)).isEqualTo(StartupRefreshGate.Action.WAIT)
        assertThat(recovery.evaluate(200_000, true, true)).isEqualTo(StartupRefreshGate.Action.WAIT)
        assertThat(recovery.evaluate(225_000, true, true)).isEqualTo(StartupRefreshGate.Action.GIVE_UP)
        recovery.reset(preserveAttempt = true)
        assertThat(recovery.abandoned).isFalse()
        assertThat(recovery.onEnded(300_000, eligible = true)).isFalse()
    }

    @Test fun `ineligible end does not consume a future eligible attempt`() {
        val recovery = LiveMpegTsEndRecovery()
        assertThat(recovery.onEnded(0, eligible = false)).isFalse()
        assertThat(recovery.onEnded(100, eligible = true)).isTrue()
    }
}
