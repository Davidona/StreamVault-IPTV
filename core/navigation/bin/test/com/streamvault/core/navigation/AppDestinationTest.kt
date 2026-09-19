package com.streamvault.core.navigation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AppDestinationTest {
    @Test
    fun detailDestinationKeepsTypedReturnTarget() {
        val destination = AppDestination.MovieDetail(
            movieId = 42L,
            returnDestination = AppDestination.Search("night")
        )

        assertThat(destination.movieId).isEqualTo(42L)
        assertThat(destination.returnDestination)
            .isEqualTo(AppDestination.Search("night"))
    }

    @Test
    fun destinationsRejectInvalidIdentityValues() {
        val invalidMovie = runCatching { AppDestination.MovieDetail(0L) }
        val invalidSeries = runCatching { AppDestination.SeriesDetail(-1L) }
        val invalidParentalGroups = runCatching { AppDestination.ParentalControlGroups(0L) }

        assertThat(invalidMovie.isFailure).isTrue()
        assertThat(invalidSeries.isFailure).isTrue()
        assertThat(invalidParentalGroups.isFailure).isTrue()
    }
}
