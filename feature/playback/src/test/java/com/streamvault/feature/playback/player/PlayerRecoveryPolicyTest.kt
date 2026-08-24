package com.streamvault.feature.playback.player

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.ProviderType
import org.junit.Test

class PlayerRecoveryPolicyTest {

    @Test
    fun `recovery actions preserve retry alternate previous and guide order`() {
        assertThat(
            PlayerRecoveryPolicy.buildActions(
                hasAlternateStream = true,
                hasLastChannel = true,
                shouldOfferGuide = true
            )
        ).containsExactly(
            PlayerNoticeAction.RETRY,
            PlayerNoticeAction.ALTERNATE_STREAM,
            PlayerNoticeAction.LAST_CHANNEL,
            PlayerNoticeAction.OPEN_GUIDE
        ).inOrder()
    }

    @Test
    fun `provider auth retry only applies to live Xtream and Stalker sessions`() {
        assertThat(
            PlayerRecoveryPolicy.shouldAttemptProviderAuthRetry(
                ProviderType.XTREAM_CODES,
                ContentType.LIVE
            )
        ).isTrue()
        assertThat(
            PlayerRecoveryPolicy.shouldAttemptProviderAuthRetry(
                ProviderType.M3U,
                ContentType.LIVE
            )
        ).isFalse()
        assertThat(
            PlayerRecoveryPolicy.shouldAttemptProviderAuthRetry(
                ProviderType.XTREAM_CODES,
                ContentType.MOVIE
            )
        ).isFalse()
    }

    @Test
    fun `provider limit messages disable preload`() {
        assertThat(PlayerRecoveryPolicy.shouldCooldownLivePreloadAfterError("HTTP 429"))
            .isTrue()
        assertThat(PlayerRecoveryPolicy.shouldCooldownLivePreloadAfterError("decoder failed"))
            .isFalse()
    }
}
