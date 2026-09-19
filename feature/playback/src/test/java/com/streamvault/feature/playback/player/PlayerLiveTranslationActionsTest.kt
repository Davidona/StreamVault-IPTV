package com.streamvault.feature.playback.player

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.ProviderType
import org.junit.Test

class PlayerLiveTranslationActionsTest {

    @Test
    fun shouldEnableLiveTranslationSession_requiresEnabledSupportedProviderAndLiveContent() {
        assertThat(
            shouldEnableLiveTranslationSession(
                enabledPreference = true,
                contentType = ContentType.LIVE,
                providerType = ProviderType.XTREAM_CODES
            )
        ).isTrue()

        assertThat(
            shouldEnableLiveTranslationSession(
                enabledPreference = false,
                contentType = ContentType.LIVE,
                providerType = ProviderType.XTREAM_CODES
            )
        ).isFalse()

        assertThat(
            shouldEnableLiveTranslationSession(
                enabledPreference = true,
                contentType = ContentType.MOVIE,
                providerType = ProviderType.XTREAM_CODES
            )
        ).isFalse()

        assertThat(
            shouldEnableLiveTranslationSession(
                enabledPreference = true,
                contentType = ContentType.LIVE,
                providerType = ProviderType.STALKER_PORTAL
            )
        ).isFalse()

        assertThat(
            shouldEnableLiveTranslationSession(
                enabledPreference = true,
                contentType = ContentType.LIVE,
                providerType = ProviderType.M3U
            )
        ).isTrue()
    }
}
