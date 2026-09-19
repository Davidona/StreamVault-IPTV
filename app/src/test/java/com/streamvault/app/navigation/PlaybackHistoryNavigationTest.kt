package com.streamvault.app.navigation

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.PlaybackHistory
import org.junit.Test

class PlaybackHistoryNavigationTest {

    @Test
    fun toPlayerNavigationRequest_preservesSeriesEpisodeIdentity() {
        val history = PlaybackHistory(
            contentId = 77L,
            contentType = ContentType.SERIES_EPISODE,
            providerId = 9L,
            title = "Episode",
            posterUrl = "https://example.com/poster.jpg",
            streamUrl = "https://example.com/episode.m3u8",
            seriesId = 12L,
            seasonNumber = 3,
            episodeNumber = 4
        )

        val request = history.toPlayerNavigationRequest()

        assertThat(request.internalId).isEqualTo(77L)
        assertThat(request.providerId).isEqualTo(9L)
        assertThat(request.contentType).isEqualTo(ContentType.SERIES_EPISODE.name)
        assertThat(request.seriesId).isEqualTo(12L)
        assertThat(request.seasonNumber).isEqualTo(3)
        assertThat(request.episodeNumber).isEqualTo(4)
    }

}
