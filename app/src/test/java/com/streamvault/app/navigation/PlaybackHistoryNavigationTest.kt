package com.streamvault.app.navigation

import com.google.common.truth.Truth.assertThat
import com.streamvault.core.navigation.PlayerNavigationRequest
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

    @Test
    fun safePlayerNavigationRequest_allowsInternalStalkerUrls() {
        val request = PlayerNavigationRequest(
            streamUrl = "stalker://7/live/390414?cmd=ffmpeg%20http%3A%2F%2Fportal.example.com%2Fch%2F390414_",
            title = "Channel",
            contentType = ContentType.LIVE.name
        )

        assertThat(safePlayerNavigationRequest(request)).isEqualTo(request)
    }
}
