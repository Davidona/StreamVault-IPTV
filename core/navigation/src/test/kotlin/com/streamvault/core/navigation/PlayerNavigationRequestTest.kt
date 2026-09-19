package com.streamvault.core.navigation

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import org.junit.Test

class PlayerNavigationRequestTest {
    @Test
    fun serializableRoundTripPreservesEpisodeAndReturnIdentity() {
        val request = PlayerNavigationRequest(
            streamUrl = "https://example.com/e.m3u8",
            title = "Episode",
            providerId = 9L,
            contentType = "SERIES_EPISODE",
            returnDestination = AppDestination.SeriesDetail(12L),
            seriesId = 12L,
            seasonNumber = 3,
            episodeNumber = 4,
            episodeId = 77L
        )

        val bytes = ByteArrayOutputStream()
        ObjectOutputStream(bytes).use { it.writeObject(request) }
        val restored = ObjectInputStream(ByteArrayInputStream(bytes.toByteArray()))
            .use { it.readObject() as PlayerNavigationRequest }

        assertThat(restored).isEqualTo(request)
    }

    @Test
    fun requestPreservesArchiveAndCombinedSourceFields() {
        val request = PlayerNavigationRequest(
            streamUrl = "https://example.com/archive.m3u8",
            title = "Morning News",
            channelId = "news.hd",
            internalId = 42L,
            categoryId = -4L,
            providerId = 7L,
            isVirtual = true,
            combinedProfileId = 11L,
            combinedSourceFilterProviderId = 8L,
            contentType = "LIVE",
            artworkUrl = "https://example.com/news.png",
            archiveStartMs = 1_700_000_000_000L,
            archiveEndMs = 1_700_000_360_000L,
            archiveTitle = "Morning News: 08:00",
            returnDestination = AppDestination.LiveTv(-4L)
        )

        assertThat(request.archiveStartMs).isEqualTo(1_700_000_000_000L)
        assertThat(request.archiveEndMs).isEqualTo(1_700_000_360_000L)
        assertThat(request.archiveTitle).isEqualTo("Morning News: 08:00")
        assertThat(request.combinedProfileId).isEqualTo(11L)
        assertThat(request.combinedSourceFilterProviderId).isEqualTo(8L)
        assertThat(request.returnDestination).isEqualTo(AppDestination.LiveTv(-4L))
    }
}
