package com.streamvault.feature.catalog.navigation

import com.google.common.truth.Truth.assertThat
import com.streamvault.core.navigation.AppDestination
import com.streamvault.domain.model.Movie
import org.junit.Test

class RelatedMovieNavigationTest {

    @Test
    fun `related movie opens detail and returns to the current movie detail`() {
        val relatedMovie = Movie(id = 202L, name = "Related Movie")
        val currentReturnDestination = AppDestination.Vod
        var openedMovie: Movie? = null
        var openedReturnDestination: AppDestination? = null

        openRelatedMovieDetail(
            relatedMovie = relatedMovie,
            currentMovieId = 101L,
            currentReturnDestination = currentReturnDestination,
            onOpenMovieDetail = { movie, returnDestination ->
                openedMovie = movie
                openedReturnDestination = returnDestination
            }
        )

        assertThat(openedMovie).isEqualTo(relatedMovie)
        assertThat(openedReturnDestination)
            .isEqualTo(AppDestination.MovieDetail(101L, currentReturnDestination))
    }
}
