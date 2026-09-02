package com.streamvault.feature.catalog.navigation

object CatalogRoutePatterns {
    const val HOME = "home"
    const val MOVIES = "movies"
    const val SERIES = "series"
    const val VOD = "vod"
    const val SEARCH = "search"
    const val SEARCH_DESTINATION = "search?query={query}"
    const val MOVIE_DETAIL = "movie_detail/{movieId}?returnRoute={returnRoute}"
    const val SERIES_DETAIL = "series_detail/{seriesId}?returnRoute={returnRoute}"
    const val MOVIE_DETAIL_PRESENTATION_HINT_KEY = "movie_detail_presentation_hint"
    const val SERIES_DETAIL_PRESENTATION_HINT_KEY = "series_detail_presentation_hint"
}
