package com.streamvault.app.navigation

import com.streamvault.core.navigation.AppDestination
import com.streamvault.domain.model.Movie
import com.streamvault.domain.model.Series

internal interface CatalogDetailNavigationActions {
    fun openMovieDetail(movie: Movie, returnDestination: AppDestination? = null)
    fun openSeriesDetail(series: Series, returnDestination: AppDestination? = null)
}
