package com.streamvault.feature.playback.player

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.Episode
import com.streamvault.domain.model.Season
import org.junit.Test

class VodEpisodeRowsTest {
    private fun season(number: Int, vararg ids: Long) = Season(
        seasonNumber = number,
        episodes = ids.mapIndexed { i, id ->
            Episode(id = id, title = "E${i + 1}", episodeNumber = i + 1, seasonNumber = number)
        },
    )

    private val seasons = listOf(season(1, 10, 11, 12), season(2, 20, 21))

    @Test
    fun seasonsWithoutEpisodesAreLeftOut() {
        val result = vodEpisodeSeasons(listOf(season(0), season(1, 10)))

        assertThat(result.map { it.seasonNumber }).containsExactly(1)
    }

    @Test
    fun initialSeasonIsThePlayingOneElseTheFirst() {
        assertThat(vodEpisodeInitialSeason(seasons, 2)).isEqualTo(2)
        assertThat(vodEpisodeInitialSeason(seasons, 9)).isEqualTo(1)
        assertThat(vodEpisodeInitialSeason(seasons, null)).isEqualTo(1)
        assertThat(vodEpisodeInitialSeason(emptyList(), 1)).isNull()
    }

    @Test
    fun targetIsThePlayingEpisode() {
        assertThat(vodEpisodeTargetIndex(seasons[0].episodes, currentEpisodeId = 11)).isEqualTo(1)
    }

    @Test
    fun targetIsTheFirstEpisodeWhenTheSeasonIsNotPlaying() {
        assertThat(vodEpisodeTargetIndex(seasons[1].episodes, currentEpisodeId = 11)).isEqualTo(0)
    }
}
