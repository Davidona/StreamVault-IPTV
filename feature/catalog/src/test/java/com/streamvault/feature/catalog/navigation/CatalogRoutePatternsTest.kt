package com.streamvault.feature.catalog.navigation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CatalogRoutePatternsTest {
    @Test
    fun `catalog route values preserve app compatibility`() {
        assertThat(CatalogRoutePatterns.HOME).isEqualTo("home")
        assertThat(CatalogRoutePatterns.MOVIES).isEqualTo("movies")
        assertThat(CatalogRoutePatterns.SERIES).isEqualTo("series")
        assertThat(CatalogRoutePatterns.VOD).isEqualTo("vod")
        assertThat(CatalogRoutePatterns.SEARCH_DESTINATION).isEqualTo("search?query={query}")
        assertThat(CatalogRoutePatterns.MOVIE_DETAIL)
            .isEqualTo("movie_detail/{movieId}?returnRoute={returnRoute}")
        assertThat(CatalogRoutePatterns.SERIES_DETAIL)
            .isEqualTo("series_detail/{seriesId}?returnRoute={returnRoute}")
    }

    @Test
    fun `presentation hint keys remain stable`() {
        assertThat(CatalogRoutePatterns.MOVIE_DETAIL_PRESENTATION_HINT_KEY)
            .isEqualTo("movie_detail_presentation_hint")
        assertThat(CatalogRoutePatterns.SERIES_DETAIL_PRESENTATION_HINT_KEY)
            .isEqualTo("series_detail_presentation_hint")
    }
}
