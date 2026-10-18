package com.streamvault.feature.catalog.presentation

import com.streamvault.domain.trakt.TraktRatingActionResult
import com.streamvault.domain.trakt.TraktRatingIdentity
import com.streamvault.domain.trakt.TraktRatingRefreshResult
import com.streamvault.domain.trakt.TraktRatings
import com.streamvault.domain.trakt.TraktRatingsState
import com.streamvault.domain.trakt.TraktRatingType
import com.streamvault.domain.trakt.TraktRatingFailure
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class NoopTraktRatings : TraktRatings {
    private val mutableState = MutableStateFlow(TraktRatingsState())
    override val state = mutableState.asStateFlow()
    override suspend fun refresh(type: TraktRatingType, force: Boolean) =
        TraktRatingRefreshResult.Failure(TraktRatingFailure.DISCONNECTED)
    override suspend fun setRating(identity: TraktRatingIdentity, rating: Int) = TraktRatingActionResult.Success
}
