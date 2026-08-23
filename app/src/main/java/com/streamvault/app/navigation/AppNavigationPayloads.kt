package com.streamvault.app.navigation

import androidx.navigation.NavBackStackEntry
import com.streamvault.core.navigation.PlayerNavigationRequest
import com.streamvault.domain.model.Movie
import com.streamvault.domain.model.MovieDetailPresentationHint
import com.streamvault.domain.model.Series
import com.streamvault.domain.model.SeriesDetailPresentationHint

internal interface AppNavigationPayloads {
    fun consumePlayerRequest(entry: NavBackStackEntry): PlayerNavigationRequest?
    fun consumeMoviePresentationHint(entry: NavBackStackEntry): MovieDetailPresentationHint?
    fun consumeSeriesPresentationHint(entry: NavBackStackEntry): SeriesDetailPresentationHint?
}

internal fun Movie.toMovieDetailPresentationHint(): MovieDetailPresentationHint? {
    if (variants.isEmpty()) return null
    return MovieDetailPresentationHint(
        providerId = providerId,
        logicalGroupId = logicalGroupId,
        variants = variants,
        duplicateConfidence = duplicateConfidence
    )
}

internal fun Series.toSeriesDetailPresentationHint(): SeriesDetailPresentationHint? {
    if (variants.isEmpty()) return null
    return SeriesDetailPresentationHint(
        providerId = providerId,
        logicalGroupId = logicalGroupId,
        variants = variants,
        duplicateConfidence = duplicateConfidence
    )
}
