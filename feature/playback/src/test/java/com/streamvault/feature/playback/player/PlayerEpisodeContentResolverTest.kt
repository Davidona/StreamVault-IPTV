package com.streamvault.feature.playback.player

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.Episode
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.Series
import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.provider.PlayerPlaybackResolver
import com.streamvault.domain.repository.ChannelRepository
import com.streamvault.domain.repository.MovieRepository
import com.streamvault.domain.repository.SeriesRepository
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PlayerEpisodeContentResolverTest {
    private val seriesRepository = mock<SeriesRepository>()
    private val resolver = PlayerContentResolver(mock<ChannelRepository>(), mock<MovieRepository>(),
        seriesRepository, mock<PlayerPlaybackResolver>())

    @Test
    fun rawLookupDoesNotHydrate() = runTest {
        val series = Series(id = 17, name = "Drama", providerId = 7)
        whenever(seriesRepository.getSeriesById(17)).thenReturn(series)
        whenever(seriesRepository.getSeriesVariants(17)).thenReturn(emptyList())
        whenever(seriesRepository.hasExplicitSeriesVariantSelection(17)).thenReturn(false)
        assertThat(resolver.getRawSeries(17)).isEqualTo(series)
        assertThat(resolver.getSeriesVariants(17)).isEmpty()
        assertThat(resolver.hasExplicitSeriesVariantSelection(17)).isFalse()
        verify(seriesRepository, never()).getSeriesDetails(any(), any(), any())
    }

    @Test
    fun episodeStreamResolutionUsesVerifiedEpisode() = runTest {
        val episode = Episode(id = 18, title = "Drama S01E02 720p AVC", seasonNumber = 1,
            episodeNumber = 2, seriesId = 17, providerId = 7)
        val info = StreamInfo(url = "http://fixture.invalid/18.mp4")
        whenever(seriesRepository.getEpisodeStreamInfo(episode)).thenReturn(Result.success(info))
        assertThat(resolver.getEpisodeStreamInfo(episode).getOrNull()).isEqualTo(info)
        verify(seriesRepository).getEpisodeStreamInfo(episode)
    }
}
