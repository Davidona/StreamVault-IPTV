package com.streamvault.feature.playback.player

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.LiveChannelVariant
import org.junit.Test

class PlayerRecoveryCoordinatorTest {

    @Test
    fun `new playback session resets retry guards but retains provider cooldown`() {
        val coordinator = PlayerRecoveryCoordinator()

        coordinator.beginSession(1L)
        coordinator.retriedWithSoftwareDecoder = true
        coordinator.retriedWithAvcMovieVariant = true
        coordinator.markXtreamAuthRefreshRetried()
        assertThat(coordinator.markLivePreloadCoolingDown(42L)).isTrue()

        coordinator.beginSession(2L)

        assertThat(coordinator.retriedWithSoftwareDecoder).isFalse()
        assertThat(coordinator.retriedWithAvcMovieVariant).isFalse()
        assertThat(coordinator.canRetryXtreamAuthRefresh()).isTrue()
        assertThat(coordinator.isLivePreloadCoolingDown(42L)).isTrue()
    }

    @Test
    fun `new playback session retains stream attempt history`() {
        val coordinator = PlayerRecoveryCoordinator()

        coordinator.beginSession(1L)
        coordinator.markStreamAttempt("https://example.test/primary")
        coordinator.markStreamFailure("https://example.test/primary")

        coordinator.beginSession(2L)

        assertThat(coordinator.streamAttemptSnapshot())
            .containsExactly("https://example.test/primary")
        assertThat(coordinator.failedStreamSnapshot())
            .containsExactly("https://example.test/primary", 1)
    }

    @Test
    fun `multi-step live recovery does not revisit earlier variants`() {
        val primaryUrl = "https://example.test/primary.m3u8"
        val firstAlternateUrl = "https://example.test/alternate-a.m3u8"
        val secondAlternateUrl = "https://example.test/alternate-b.m3u8"
        val channel = Channel(
            id = 1L,
            name = "Live",
            streamUrl = primaryUrl,
            selectedVariantId = 1L,
            variants = listOf(
                LiveChannelVariant(
                    rawChannelId = 2L,
                    logicalGroupId = "live",
                    providerId = 1L,
                    originalName = "Alternate A",
                    canonicalName = "Live",
                    streamUrl = firstAlternateUrl
                ),
                LiveChannelVariant(
                    rawChannelId = 3L,
                    logicalGroupId = "live",
                    providerId = 1L,
                    originalName = "Alternate B",
                    canonicalName = "Live",
                    streamUrl = secondAlternateUrl
                )
            )
        )
        val coordinator = PlayerRecoveryCoordinator()

        coordinator.beginSession(1L)
        coordinator.markStreamAttempt(primaryUrl)
        val firstCandidate = selectNextLiveRecoveryCandidate(
            channel = channel,
            currentVariantId = 1L,
            currentStreamUrl = primaryUrl,
            currentResolvedPlaybackUrl = primaryUrl,
            triedAlternativeStreams = coordinator.streamAttemptSnapshot(),
            failedStreamsThisSession = coordinator.failedStreamSnapshot(),
            preferXtreamTsFallback = false,
            allowXtreamTsFallback = false
        )
        assertThat(firstCandidate?.url).isEqualTo(firstAlternateUrl)

        coordinator.markStreamAttempt(firstAlternateUrl)
        coordinator.beginSession(2L)
        val secondCandidate = selectNextLiveRecoveryCandidate(
            channel = channel,
            currentVariantId = 2L,
            currentStreamUrl = firstAlternateUrl,
            currentResolvedPlaybackUrl = firstAlternateUrl,
            triedAlternativeStreams = coordinator.streamAttemptSnapshot(),
            failedStreamsThisSession = coordinator.failedStreamSnapshot(),
            preferXtreamTsFallback = false,
            allowXtreamTsFallback = false
        )
        assertThat(secondCandidate?.url).isEqualTo(secondAlternateUrl)

        coordinator.markStreamAttempt(secondAlternateUrl)
        coordinator.beginSession(3L)
        val nextCandidate = selectNextLiveRecoveryCandidate(
            channel = channel,
            currentVariantId = 3L,
            currentStreamUrl = secondAlternateUrl,
            currentResolvedPlaybackUrl = secondAlternateUrl,
            triedAlternativeStreams = coordinator.streamAttemptSnapshot(),
            failedStreamsThisSession = coordinator.failedStreamSnapshot(),
            preferXtreamTsFallback = false,
            allowXtreamTsFallback = false
        )

        assertThat(nextCandidate).isNull()
    }

    @Test
    fun `explicit root playback reset clears stream attempt history`() {
        val coordinator = PlayerRecoveryCoordinator()

        coordinator.beginSession(1L)
        coordinator.markStreamAttempt("https://example.test/old.m3u8")
        coordinator.markStreamFailure("https://example.test/old.m3u8")

        coordinator.clearStreamAttempts()

        assertThat(coordinator.streamAttemptSnapshot()).isEmpty()
        assertThat(coordinator.failedStreamSnapshot()).isEmpty()
    }

    @Test
    fun `preload cooldown is idempotent`() {
        val coordinator = PlayerRecoveryCoordinator()

        assertThat(coordinator.markLivePreloadCoolingDown(7L)).isTrue()
        assertThat(coordinator.markLivePreloadCoolingDown(7L)).isFalse()
    }
}
