package com.streamvault.feature.catalog.navigation

import com.google.common.truth.Truth.assertThat
import com.streamvault.core.navigation.AppDestination
import org.junit.Test

class CatalogGraphTest {

    @Test
    fun `catalog graph route set preserves existing destinations`() {
        val routes = listOf(
                CatalogRoutePatterns.HOME,
                CatalogRoutePatterns.MOVIES,
                CatalogRoutePatterns.SERIES,
                CatalogRoutePatterns.VOD,
                CatalogRoutePatterns.SEARCH_DESTINATION,
                CatalogRoutePatterns.MOVIE_DETAIL,
                CatalogRoutePatterns.SERIES_DETAIL,
            )
        assertThat(routes).containsExactly(
            "home",
            "movies",
            "series",
            "vod",
            "search?query={query}",
            "movie_detail/{movieId}?returnRoute={returnRoute}",
            "series_detail/{seriesId}?returnRoute={returnRoute}",
        ).inOrder()
        assertThat(routes).doesNotContain("favorites")
    }

    @Test
    fun `catalog detail callbacks retain return destinations`() {
        val returnDestination = AppDestination.Search("news")

        assertThat(AppDestination.MovieDetail(42L, returnDestination).returnDestination)
            .isEqualTo(returnDestination)
        assertThat(AppDestination.SeriesDetail(42L, returnDestination).returnDestination)
            .isEqualTo(returnDestination)
    }
}
