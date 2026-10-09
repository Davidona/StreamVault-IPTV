package com.streamvault.feature.playback.player

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.ProviderType
import org.junit.Test

class PlayerPlaybackProbeSupportTest {

    @Test
    fun `204 maps to empty temporary link failure`() {
        val failure = resolvePlaybackProbeFailure(204)

        assertThat(failure).isNotNull()
        assertThat(failure?.recoveryType).isEqualTo(PlayerRecoveryType.SOURCE)
        assertThat(failure?.message).contains("empty temporary link")
    }

    @Test
    fun `stalker temp-link playback probe is skipped`() {
        val skipped = shouldSkipPlaybackProbe(
            providerType = ProviderType.STALKER_PORTAL,
            url = "http://fdox.org:8080/play/live.php?mac=00:1A:79:BA:73:FA&stream=228556&extension=ts&play_token=abc123"
        )

        assertThat(skipped).isTrue()
    }

    @Test
    fun `non stalker playback probe is not skipped`() {
        val skipped = shouldSkipPlaybackProbe(
            providerType = ProviderType.XTREAM_CODES,
            url = "http://fdox.org:8080/play/live.php?stream=228556"
        )

        assertThat(skipped).isFalse()
    }

    @Test
    fun `xtream live playback probe is skipped`() {
        val skipped = shouldSkipPlaybackProbe(
            providerType = ProviderType.XTREAM_CODES,
            url = "http://example.com:8080/live/user/pass/2112.m3u8"
        )

        assertThat(skipped).isTrue()
    }

    @Test
    fun `xtream vod playback probe is not skipped`() {
        val skipped = shouldSkipPlaybackProbe(
            providerType = ProviderType.XTREAM_CODES,
            url = "http://example.com:8080/movie/user/pass/2112.mp4"
        )

        assertThat(skipped).isFalse()
    }

    @Test
    fun `provider probe policy skips live urls and keeps eligible internal vod urls`() {
        assertThat(
            shouldProbeProviderStreamUrl(
                providerType = ProviderType.XTREAM_CODES,
                url = "http://example.com/live/user/pass/2112.m3u8",
                hasInternalSource = true,
                probeAlreadyPassed = false
            )
        ).isFalse()
        assertThat(
            shouldProbeProviderStreamUrl(
                providerType = ProviderType.XTREAM_CODES,
                url = "http://example.com/movie/user/pass/2112.mp4",
                hasInternalSource = true,
                probeAlreadyPassed = false
            )
        ).isTrue()
    }

    @Test
    fun `provider probe policy skips stalker temporary links and reuses passed xtream probes`() {
        assertThat(
            shouldProbeProviderStreamUrl(
                providerType = ProviderType.STALKER_PORTAL,
                url = "http://example.com/play/live.php?stream=2112&play_token=abc",
                hasInternalSource = true,
                probeAlreadyPassed = false
            )
        ).isFalse()
        assertThat(
            shouldProbeProviderStreamUrl(
                providerType = ProviderType.XTREAM_CODES,
                url = "http://example.com/movie/user/pass/2112.mp4",
                hasInternalSource = true,
                probeAlreadyPassed = true
            )
        ).isFalse()
    }
}
