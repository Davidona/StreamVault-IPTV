package com.streamvault.feature.playback.player

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.remote.xtream.XtreamStreamKind
import com.streamvault.data.remote.xtream.XtreamUrlFactory
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.LiveChannelVariant
import org.junit.Test

class PlayerAlternateStreamSupportTest {
    @Test
    fun buildXtreamLiveTsFallbackUrl_convertsLiveHlsInternalUrlToTs() {
        val channel = Channel(id = 10, name = "Live", providerId = 7, streamId = 61351)
        val currentUrl = XtreamUrlFactory.buildInternalStreamUrl(
            providerId = 7,
            kind = XtreamStreamKind.LIVE,
            streamId = 61351,
            containerExtension = "m3u8"
        )

        val fallbackUrl = buildXtreamLiveTsFallbackUrl(
            channel = channel,
            currentStreamUrl = currentUrl,
            currentResolvedPlaybackUrl = "http://example.test/live/user/pass/61351.m3u8"
        )

        assertThat(fallbackUrl).isEqualTo(
            XtreamUrlFactory.buildInternalStreamUrl(
                providerId = 7,
                kind = XtreamStreamKind.LIVE,
                streamId = 61351,
                containerExtension = "ts"
            )
        )
    }

    @Test
    fun buildXtreamLiveTsFallbackUrl_usesChannelIdentifiersForResolvedHlsUrl() {
        val channel = Channel(id = 10, name = "Live", providerId = 7, streamId = 61351)

        val fallbackUrl = buildXtreamLiveTsFallbackUrl(
            channel = channel,
            currentStreamUrl = "http://example.test/live/user/pass/61351.m3u8",
            currentResolvedPlaybackUrl = "http://example.test/live/user/pass/61351.m3u8?token=abc"
        )

        assertThat(fallbackUrl).isEqualTo(
            XtreamUrlFactory.buildInternalStreamUrl(
                providerId = 7,
                kind = XtreamStreamKind.LIVE,
                streamId = 61351,
                containerExtension = "ts"
            )
        )
    }

    @Test
    fun buildXtreamLiveTsFallbackUrl_extractsStreamIdFromResolvedLivePath() {
        val channel = Channel(id = 10, name = "Live", providerId = 7, streamId = 0)

        val fallbackUrl = buildXtreamLiveTsFallbackUrl(
            channel = channel,
            currentStreamUrl = "https://example.test/live/user/pass/61351.m3u8",
            currentResolvedPlaybackUrl = "https://example.test/proxy/live/user/pass/61351.m3u8?token=abc"
        )

        assertThat(fallbackUrl).isEqualTo(
            XtreamUrlFactory.buildInternalStreamUrl(
                providerId = 7,
                kind = XtreamStreamKind.LIVE,
                streamId = 61351,
                containerExtension = "ts"
            )
        )
    }

    @Test
    fun buildXtreamLiveTsFallbackUrl_ignoresNonLiveInternalUrl() {
        val channel = Channel(id = 10, name = "Movie", providerId = 7, streamId = 61351)
        val currentUrl = XtreamUrlFactory.buildInternalStreamUrl(
            providerId = 7,
            kind = XtreamStreamKind.MOVIE,
            streamId = 61351,
            containerExtension = "m3u8"
        )

        assertThat(
            buildXtreamLiveTsFallbackUrl(
                channel = channel,
                currentStreamUrl = currentUrl,
                currentResolvedPlaybackUrl = "http://example.test/movie/user/pass/61351.m3u8"
            )
        ).isNull()
    }

    @Test
    fun buildXtreamLiveTsFallbackUrl_ignoresAlreadyTsLiveUrl() {
        val channel = Channel(id = 10, name = "Live", providerId = 7, streamId = 61351)
        val currentUrl = XtreamUrlFactory.buildInternalStreamUrl(
            providerId = 7,
            kind = XtreamStreamKind.LIVE,
            streamId = 61351,
            containerExtension = "ts"
        )

        assertThat(
            buildXtreamLiveTsFallbackUrl(
                channel = channel,
                currentStreamUrl = currentUrl,
                currentResolvedPlaybackUrl = "http://example.test/live/user/pass/61351.ts"
            )
        ).isNull()
    }

    @Test
    fun selectXtreamLiveTsFallbackUrl_skipsTriedFallback() {
        val channel = Channel(id = 10, name = "Live", providerId = 7, streamId = 61351)
        val fallbackUrl = XtreamUrlFactory.buildInternalStreamUrl(
            providerId = 7,
            kind = XtreamStreamKind.LIVE,
            streamId = 61351,
            containerExtension = "ts"
        )

        assertThat(
            selectXtreamLiveTsFallbackUrl(
                channel = channel,
                currentStreamUrl = "xtream://7/live/61351?ext=m3u8",
                currentResolvedPlaybackUrl = "http://example.test/live/user/pass/61351.m3u8",
                triedAlternativeStreams = setOf(fallbackUrl),
                failedStreamsThisSession = emptyMap()
            )
        ).isNull()
    }

    @Test
    fun selectXtreamLiveTsFallbackUrl_skipsFailedFallback() {
        val channel = Channel(id = 10, name = "Live", providerId = 7, streamId = 61351)
        val fallbackUrl = XtreamUrlFactory.buildInternalStreamUrl(
            providerId = 7,
            kind = XtreamStreamKind.LIVE,
            streamId = 61351,
            containerExtension = "ts"
        )

        assertThat(
            selectXtreamLiveTsFallbackUrl(
                channel = channel,
                currentStreamUrl = "xtream://7/live/61351?ext=m3u8",
                currentResolvedPlaybackUrl = "http://example.test/live/user/pass/61351.m3u8",
                triedAlternativeStreams = emptySet(),
                failedStreamsThisSession = mapOf(fallbackUrl to 1)
            )
        ).isNull()
    }

    @Test
    fun selectNextLiveRecoveryCandidate_prefersTsFallbackBeforeVariantsWhenRequested() {
        val currentUrl = XtreamUrlFactory.buildInternalStreamUrl(
            providerId = 7,
            kind = XtreamStreamKind.LIVE,
            streamId = 61351,
            containerExtension = "m3u8"
        )
        val variantUrl = XtreamUrlFactory.buildInternalStreamUrl(
            providerId = 7,
            kind = XtreamStreamKind.LIVE,
            streamId = 61352,
            containerExtension = "m3u8"
        )
        val channel = Channel(
            id = 10,
            name = "Live",
            providerId = 7,
            streamId = 61351,
            streamUrl = currentUrl,
            selectedVariantId = 10,
            variants = listOf(
                LiveChannelVariant(
                    rawChannelId = 11,
                    logicalGroupId = "live",
                    providerId = 7,
                    originalName = "Live HD",
                    canonicalName = "Live",
                    streamUrl = variantUrl,
                    streamId = 61352
                )
            )
        )

        val candidate = selectNextLiveRecoveryCandidate(
            channel = channel,
            currentVariantId = 10,
            currentStreamUrl = currentUrl,
            currentResolvedPlaybackUrl = "http://example.test/live/user/pass/61351.m3u8",
            triedAlternativeStreams = emptySet(),
            failedStreamsThisSession = emptyMap(),
            preferXtreamTsFallback = true
        )

        assertThat(candidate?.kind).isEqualTo(LiveRecoveryCandidateKind.XTREAM_TS_FALLBACK)
        assertThat(candidate?.url).isEqualTo(
            XtreamUrlFactory.buildInternalStreamUrl(
                providerId = 7,
                kind = XtreamStreamKind.LIVE,
                streamId = 61351,
                containerExtension = "ts"
            )
        )
    }

    @Test
    fun selectNextLiveRecoveryCandidate_skipsTsFallbackWhenDisallowed() {
        val currentUrl = XtreamUrlFactory.buildInternalStreamUrl(
            providerId = 7,
            kind = XtreamStreamKind.LIVE,
            streamId = 61351,
            containerExtension = "m3u8"
        )
        val channel = Channel(
            id = 10,
            name = "Live",
            providerId = 7,
            streamId = 61351,
            streamUrl = currentUrl,
            selectedVariantId = 10
        )

        val candidate = selectNextLiveRecoveryCandidate(
            channel = channel,
            currentVariantId = 10,
            currentStreamUrl = currentUrl,
            currentResolvedPlaybackUrl = "http://example.test/live/user/pass/61351.m3u8",
            triedAlternativeStreams = emptySet(),
            failedStreamsThisSession = emptyMap(),
            preferXtreamTsFallback = true,
            allowXtreamTsFallback = false
        )

        assertThat(candidate).isNull()
    }

    @Test
    fun selectNextLiveRecoveryCandidate_keepsVariantPreferenceByDefault() {
        val currentUrl = XtreamUrlFactory.buildInternalStreamUrl(
            providerId = 7,
            kind = XtreamStreamKind.LIVE,
            streamId = 61351,
            containerExtension = "m3u8"
        )
        val variantUrl = XtreamUrlFactory.buildInternalStreamUrl(
            providerId = 7,
            kind = XtreamStreamKind.LIVE,
            streamId = 61352,
            containerExtension = "m3u8"
        )
        val channel = Channel(
            id = 10,
            name = "Live",
            providerId = 7,
            streamId = 61351,
            streamUrl = currentUrl,
            selectedVariantId = 10,
            variants = listOf(
                LiveChannelVariant(
                    rawChannelId = 11,
                    logicalGroupId = "live",
                    providerId = 7,
                    originalName = "Live HD",
                    canonicalName = "Live",
                    streamUrl = variantUrl,
                    streamId = 61352
                )
            )
        )

        val candidate = selectNextLiveRecoveryCandidate(
            channel = channel,
            currentVariantId = 10,
            currentStreamUrl = currentUrl,
            currentResolvedPlaybackUrl = "http://example.test/live/user/pass/61351.m3u8",
            triedAlternativeStreams = emptySet(),
            failedStreamsThisSession = emptyMap(),
            preferXtreamTsFallback = false
        )

        assertThat(candidate?.kind).isEqualTo(LiveRecoveryCandidateKind.VARIANT)
        assertThat(candidate?.url).isEqualTo(variantUrl)
    }
}
