package com.streamvault.feature.playback.player

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.*
import com.streamvault.domain.repository.*
import com.streamvault.domain.provider.PlayerPlaybackResolver
import org.mockito.kotlin.*
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PlayerEpisodeSourceIntegrationTest {
    @Test fun renewalResolvesPhysicalEpisode() = runTest {
        val repository = mock<SeriesRepository>()
        val episode = Episode(201, "Drama S01E02 720p AVC", 2, 1,
            seriesId = 20, providerId = 7, streamUrl = "xtream://fixture/episode/201")
        val series = Series(20, "Drama 720p", providerId = 7)
        val physical = PhysicalEpisodeSource(EpisodeSourceToken(1, 2), series, episode)
        val info = StreamInfo("https://fixture.invalid/renewed-avc.mp4", title = "Drama S01E02 720p AVC")
        whenever(repository.getEpisodeStreamInfo(episode)).thenReturn(Result.success(info))
        val resolver = PlayerContentResolver(mock(), mock(), repository, mock<PlayerPlaybackResolver>())
        val result = resolver.resolvePhysicalEpisodeStream(physical, "Logical Drama")
        assertThat(result.streamInfo?.url).isEqualTo(info.url)
        assertThat(result.streamInfo?.title).isEqualTo("Logical Drama")
        assertThat(result.streamInfo?.playbackContentKind).isEqualTo(PlaybackContentKind.ON_DEMAND)
        verify(repository).getEpisodeStreamInfo(episode)
        verify(repository, never()).getEpisodeById(any())
    }

    @Test fun logicalHistoryAndNavigationRemainOriginal() {
        val state = PlayerEpisodeSourceState()
        val token = state.reserveFallback(state.reset(1), 60000, 180000)!!
        state.stage(token, Series(20, "Drama 720p"), Episode(201, "S01E02 AVC", 2, 1))
        val logical = Episode(101, "Drama S01E02 HEVC", 2, 1, seriesId = 10, providerId = 7)
        val progress = state.progressSnapshot(token, 0, 0)!!
        val history = buildPlaybackHistorySnapshot(progress.first, progress.second,
            currentContentId = 101, currentProviderId = 7, currentContentType = ContentType.SERIES_EPISODE,
            currentTitle = "Logical Drama", currentArtworkUrl = null, currentStreamUrl = "logical-hevc",
            currentSeriesId = 10, currentEpisode = logical, currentSeasonNumber = 1, currentEpisodeNumber = 2)
        assertThat(history?.contentId).isEqualTo(101L)
        assertThat(history?.seriesId).isEqualTo(10L)
        assertThat(history?.streamUrl).isEqualTo("logical-hevc")
        assertThat(history?.resumePositionMs).isEqualTo(60000L)
        assertThat(buildEpisodePreloadNeighbors(Series(10, "Drama", seasons = listOf(
            Season(1, episodes = listOf(logical, logical.copy(id = 102, episodeNumber = 3))))), logical)
            ?.map { it.id }).containsExactly(101L, 102L).inOrder()
    }

    @Test fun lifecycleFlushDuringRecoveryKeepsSnapshot() {
        val state = PlayerEpisodeSourceState()
        val token = state.reserveFallback(state.reset(1), 61000, 180000)!!
        val capturedAtDisposal = state.progressSnapshot(token, 0, 0)
        state.reset(2)
        assertThat(capturedAtDisposal).isEqualTo(61000L to 180000L)
        assertThat(state.progressSnapshot(token, 0, 0)).isNull()
    }

    @Test fun resolvedUrlReplacementKeepsCapturedLogicalSession() {
        val logical = "xtream://fixture/episode/101"
        val identity = episodeRecoveryIdentity(logical, "https://fixture.invalid/hevc.mp4")
        assertThat(matchesActivePlaybackSession(1, 1, identity,
            "https://fixture.invalid/avc.mp4", logical)).isTrue()
        assertThat(matchesActivePlaybackSession(1, 2, identity,
            "https://fixture.invalid/avc.mp4", logical)).isFalse()
    }
}
