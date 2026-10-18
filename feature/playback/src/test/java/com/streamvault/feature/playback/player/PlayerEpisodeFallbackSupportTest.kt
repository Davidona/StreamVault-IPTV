package com.streamvault.feature.playback.player

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.*
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.*

class PlayerEpisodeFallbackSupportTest {
    private val originalEpisode = Episode(101, "Drama S01E02 1080p HEVC", 2, 1,
        durationSeconds = 240, providerId = 7, seriesId = 10)
    private val original = Series(10, "Drama 1080p", providerId = 7, tmdbId = 5000,
        seasons = listOf(Season(1, episodes = listOf(originalEpisode))))
    private val candidateEpisode = originalEpisode.copy(id = 201, seriesId = 20, title = "Drama S01E02 720p AVC")
    private val candidate = original.copy(id = 20, name = "Drama 720p",
        seasons = listOf(Season(1, episodes = listOf(candidateEpisode))))
    private val info = StreamInfo("https://fixture.invalid/avc.mp4", playbackContentKind = PlaybackContentKind.ON_DEMAND)

    private suspend fun fixture(): Pair<PlayerContentResolver, Port> {
        val resolver = mock<PlayerContentResolver>()
        whenever(resolver.hasExplicitSeriesVariantSelection(10)).thenReturn(false)
        whenever(resolver.getRawSeries(10)).thenReturn(original.copy(seasons = emptyList()))
        whenever(resolver.getRawSeries(20)).thenReturn(candidate.copy(seasons = emptyList()))
        whenever(resolver.getSeriesVariants(10)).thenReturn(listOf(
            variant(original), variant(candidate)))
        whenever(resolver.getSeriesDetails(7, 20)).thenReturn(Result.success(candidate))
        whenever(resolver.getEpisodeStreamInfo(candidateEpisode)).thenReturn(Result.success(info))
        return resolver to Port()
    }

    private fun variant(series: Series) = VodSeriesVariant(series.id, series.name, series.id,
        null, null, series.tmdbId, null, 0f, 0, 0, 0, 0, series.name)

    private inner class Port : EpisodeFallbackPort {
        override val state = PlayerEpisodeSourceState().apply { reset(1) }
        override val source = EpisodeFallbackSource(original, originalEpisode,
            info.copy(url = "https://fixture.invalid/hevc.mp4"), "video/hevc", 1080, 60000, 240000)
        override var manualVideo = false
        val events = mutableListOf<String>()
        var prepareResult = true
        var prepareCount = 0
        var seekPosition = -1L
        var onPrepare: () -> Unit = {}
        override fun isCurrent(token: EpisodeSourceToken) = state.isCurrent(token)
        override fun stopFailedSource() { events += "clear-preload"; events += "stop" }
        override suspend fun prepare(info: StreamInfo, token: EpisodeSourceToken): Boolean {
            prepareCount++; events += "prepare"; onPrepare(); return prepareResult
        }
        override fun seekAndPlay(positionMs: Long) { seekPosition = positionMs; events += "seek-play" }
        override fun failure(message: String) { events += "failure:$message" }
        override fun recovered() { events += "notice" }
    }

    @Test fun stopAndPreloadClearPrecedeCandidateLookup() = runTest {
        val (resolver, port) = fixture()
        whenever(resolver.getSeriesDetails(7, 20)).thenAnswer {
            assertThat(port.events).containsExactly("clear-preload", "stop").inOrder()
            port.events += "lookup"; Result.success(candidate)
        }
        assertThat(runEpisodeFallback(resolver, port)).isEqualTo(EpisodeFallbackResult.Recovered)
        assertThat(port.events).containsExactly("clear-preload", "stop", "lookup", "prepare", "seek-play", "notice").inOrder()
        assertThat(port.seekPosition).isEqualTo(60000L)
        assertThat(port.state.source(port.state.token)?.episode).isEqualTo(candidateEpisode)
    }

    @Test fun oneCandidateDespiteLookupOrPrepareFailure() = runTest {
        for (lookupFails in listOf(true, false)) {
            val (resolver, port) = fixture()
            if (lookupFails) whenever(resolver.getSeriesDetails(7, 20)).thenReturn(Result.error("lookup failed"))
            else port.prepareResult = false
            assertThat(runEpisodeFallback(resolver, port)).isEqualTo(EpisodeFallbackResult.HandledFailure)
            assertThat(runEpisodeFallback(resolver, port)).isEqualTo(EpisodeFallbackResult.NotEligible)
            verify(resolver, times(1)).getSeriesDetails(7, 20)
            assertThat(port.prepareCount).isEqualTo(if (lookupFails) 0 else 1)
            assertThat(port.state.progressSnapshot(port.state.token, 0, 0)).isEqualTo(60000L to 240000L)
        }
    }

    @Test fun manualChoiceBeforeLookupBlocks() = runTest {
        val (resolver, port) = fixture()
        whenever(resolver.hasExplicitSeriesVariantSelection(10)).thenReturn(true)
        assertThat(runEpisodeFallback(resolver, port)).isEqualTo(EpisodeFallbackResult.NotEligible)
        verify(resolver, never()).getSeriesDetails(any(), any())
        assertThat(port.events).isEmpty()
        port.manualVideo = true
        assertThat(runEpisodeFallback(resolver, port)).isEqualTo(EpisodeFallbackResult.NotEligible)
    }

    @Test fun manualTrackChosenDuringLookupBlocksPrepare() = runTest {
        val (resolver, port) = fixture()
        whenever(resolver.getSeriesDetails(7, 20)).thenAnswer { port.manualVideo = true; Result.success(candidate) }
        assertThat(runEpisodeFallback(resolver, port)).isEqualTo(EpisodeFallbackResult.HandledFailure)
        assertThat(port.prepareCount).isEqualTo(0)
    }

    @Test fun newRequestDuringEachAwaitCannotPublish() = runTest {
        for (boundary in listOf("details", "stream", "prepare")) {
            val (resolver, port) = fixture()
            if (boundary == "details") whenever(resolver.getSeriesDetails(7, 20)).thenAnswer { port.state.reset(2); Result.success(candidate) }
            if (boundary == "stream") whenever(resolver.getEpisodeStreamInfo(candidateEpisode)).thenAnswer { port.state.reset(2); Result.success(info) }
            if (boundary == "prepare") {
                port.onPrepare = { port.state.reset(2) }
            }
            assertThat(runEpisodeFallback(resolver, port)).isEqualTo(EpisodeFallbackResult.HandledFailure)
            assertThat(port.seekPosition).isEqualTo(-1L)
            assertThat(port.state.token.requestVersion).isEqualTo(2L)
            assertThat(port.state.source(port.state.token)).isNull()
        }
    }

    @Test fun finalAdaptiveSourceRejectedBeforePrepare() = runTest {
        val (resolver, port) = fixture()
        whenever(resolver.getEpisodeStreamInfo(candidateEpisode)).thenReturn(Result.success(
            info.copy(url = "https://fixture.invalid/avc.m3u8")))
        assertThat(runEpisodeFallback(resolver, port)).isEqualTo(EpisodeFallbackResult.HandledFailure)
        assertThat(port.prepareCount).isEqualTo(0)
    }

    @Test fun terminalCandidateErrorsRemainTerminal() = runTest {
        for (message in listOf("HTTP 401", "subscription expired", "DRM failure", "HTTP 509")) {
            val (resolver, port) = fixture()
            whenever(resolver.getEpisodeStreamInfo(candidateEpisode)).thenReturn(Result.error(message))
            assertThat(runEpisodeFallback(resolver, port)).isEqualTo(EpisodeFallbackResult.HandledFailure)
            assertThat(port.events.last()).isEqualTo("failure:$message")
            assertThat(port.prepareCount).isEqualTo(0)
            assertThat(port.state.attempted).isTrue()
        }
    }

    @Test fun savedChoiceDuringLookupBlocksPrepare() = runTest {
        val (resolver, port) = fixture()
        whenever(resolver.hasExplicitSeriesVariantSelection(10)).thenReturn(false, true)
        assertThat(runEpisodeFallback(resolver, port)).isEqualTo(EpisodeFallbackResult.HandledFailure)
        assertThat(port.prepareCount).isEqualTo(0)
    }

    @Test fun asynchronousCandidateFailureCannotSeekOrPlay() = runTest {
        val (resolver, port) = fixture()
        port.onPrepare = { port.state.markFailed(port.state.token) }
        assertThat(runEpisodeFallback(resolver, port)).isEqualTo(EpisodeFallbackResult.HandledFailure)
        assertThat(port.seekPosition).isEqualTo(-1L)
    }

    @Test fun cancelDoesNotRollbackNewSource() = runTest {
        val (resolver, port) = fixture()
        whenever(resolver.getSeriesDetails(7, 20)).thenAnswer {
            port.state.reset(2)
            throw kotlinx.coroutines.CancellationException("new content")
        }
        try { runEpisodeFallback(resolver, port); org.junit.Assert.fail("Cancellation must propagate") }
        catch (_: kotlinx.coroutines.CancellationException) { }
        assertThat(port.state.token.requestVersion).isEqualTo(2L)
        assertThat(port.events).containsExactly("clear-preload", "stop").inOrder()
        assertThat(port.prepareCount).isEqualTo(0)
    }
}
