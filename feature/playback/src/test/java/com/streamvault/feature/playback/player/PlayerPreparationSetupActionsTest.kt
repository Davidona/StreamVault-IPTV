package com.streamvault.feature.playback.player

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.Episode
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PlayerPreparationSetupActionsTest {

    @Test
    fun `resolveSeriesEpisodeIdentity uses explicit series id when present`() = runTest {
        val identity = resolveSeriesEpisodeIdentity(
            providerId = 5L,
            internalChannelId = 11L,
            seriesId = 22L,
            seasonNumber = 3,
            episodeNumber = 4,
            lookupEpisode = { error("should not lookup persisted episode") }
        )

        assertThat(identity).isEqualTo(
            ResolvedSeriesEpisodeIdentity(seriesId = 22L, seasonNumber = 3, episodeNumber = 4)
        )
    }

    @Test
    fun `resolveSeriesEpisodeIdentity falls back to persisted episode metadata`() = runTest {
        val identity = resolveSeriesEpisodeIdentity(
            providerId = 5L,
            internalChannelId = 11L,
            seriesId = null,
            seasonNumber = null,
            episodeNumber = null,
            lookupEpisode = {
                Episode(
                    id = 11L,
                    title = "Episode",
                    episodeNumber = 7,
                    seasonNumber = 2,
                    streamUrl = "https://example.com/episode.m3u8",
                    seriesId = 22L,
                    providerId = 5L
                )
            }
        )

        assertThat(identity).isEqualTo(
            ResolvedSeriesEpisodeIdentity(seriesId = 22L, seasonNumber = 2, episodeNumber = 7)
        )
    }
}