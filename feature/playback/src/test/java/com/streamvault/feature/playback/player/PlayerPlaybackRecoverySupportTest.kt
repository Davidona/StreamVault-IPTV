package com.streamvault.feature.playback.player

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.ProviderType
import com.streamvault.player.PlayerError
import org.junit.Test

class PlayerPlaybackRecoverySupportTest {

    @Test
    fun `509 source error shows provider limit message`() {
        val error = PlayerError.SourceError(
            "Provider rejected playback, likely max connections or bandwidth limit (HTTP 509)."
        )

        assertThat(classifyPlaybackError(error)).isEqualTo(PlayerRecoveryType.SOURCE)
        assertThat(resolvePlaybackErrorMessage(error))
            .isEqualTo("Provider rejected playback, likely max connections or bandwidth limit.")
    }

    @Test
    fun `live provider auth retry is limited to managed IPTV providers`() {
        assertThat(shouldAttemptProviderAuthRetry(ProviderType.XTREAM_CODES, ContentType.LIVE)).isTrue()
        assertThat(shouldAttemptProviderAuthRetry(ProviderType.STALKER_PORTAL, ContentType.LIVE)).isTrue()
        assertThat(shouldAttemptProviderAuthRetry(ProviderType.M3U, ContentType.LIVE)).isFalse()
    }

    @Test
    fun `provider auth retry does not run for non-live playback`() {
        assertThat(shouldAttemptProviderAuthRetry(ProviderType.XTREAM_CODES, ContentType.MOVIE)).isFalse()
        assertThat(shouldAttemptProviderAuthRetry(ProviderType.STALKER_PORTAL, ContentType.SERIES_EPISODE)).isFalse()
    }

    @Test
    fun `live preload cools down after provider connection errors`() {
        assertThat(shouldCooldownLivePreloadAfterError("HTTP 403 Forbidden")).isTrue()
        assertThat(shouldCooldownLivePreloadAfterError("Provider rejected playback, likely max connections or bandwidth limit (HTTP 509).")).isTrue()
        assertThat(shouldCooldownLivePreloadAfterError("Too many connections")).isTrue()
    }

    @Test
    fun `live preload does not cool down after generic decoder error`() {
        assertThat(shouldCooldownLivePreloadAfterError("Decoder initialization failed")).isFalse()
    }
}
