package com.streamvault.data.sync

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.entity.MovieEntity
import com.streamvault.data.local.entity.SeriesEntity
import org.junit.Test

class XtreamSummaryFingerprintTest {
    @Test
    fun `movie fingerprint tracks summary while ignoring local watch and hydration state`() {
        val summary = MovieEntity(
            streamId = 42L,
            providerId = 7L,
            name = "Example",
            posterUrl = "poster.jpg",
            categoryId = 3L,
            categoryName = "Movies"
        )

        assertThat(XtreamSummaryFingerprint.movie(summary.copy(
            watchProgress = 90_000L,
            watchCount = 3,
            lastWatchedAt = 123L,
            isUserProtected = true,
            cacheState = "DETAIL_HYDRATED",
            detailHydratedAt = 456L,
            remoteStaleAt = 789L,
            playbackMetadataJson = "{\"userAgent\":\"custom\"}"
        ))).isEqualTo(XtreamSummaryFingerprint.movie(summary))
        assertThat(XtreamSummaryFingerprint.movie(summary.copy(name = "Renamed")))
            .isNotEqualTo(XtreamSummaryFingerprint.movie(summary))
    }

    @Test
    fun `series fingerprint ignores local protection and hydration timestamps`() {
        val summary = SeriesEntity(
            seriesId = 84L,
            providerId = 7L,
            providerSeriesId = "84",
            name = "Example Series",
            categoryId = 5L
        )

        assertThat(XtreamSummaryFingerprint.series(summary.copy(
            isUserProtected = true,
            cacheState = "DETAIL_HYDRATED",
            detailHydratedAt = 456L,
            remoteStaleAt = 789L
        ))).isEqualTo(XtreamSummaryFingerprint.series(summary))
        assertThat(XtreamSummaryFingerprint.series(summary.copy(name = "Renamed Series")))
            .isNotEqualTo(XtreamSummaryFingerprint.series(summary))
    }
}
