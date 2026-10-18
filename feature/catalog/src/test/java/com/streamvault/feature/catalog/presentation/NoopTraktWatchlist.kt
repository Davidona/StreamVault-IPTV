package com.streamvault.feature.catalog.presentation

import com.streamvault.domain.trakt.TraktWatchlist
import com.streamvault.domain.trakt.TraktWatchlistActionResult
import com.streamvault.domain.trakt.TraktWatchlistFailure
import com.streamvault.domain.trakt.TraktWatchlistIdentity
import com.streamvault.domain.trakt.TraktWatchlistRefreshResult
import com.streamvault.domain.trakt.TraktWatchlistState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class NoopTraktWatchlist : TraktWatchlist {
    private val mutableState = MutableStateFlow(TraktWatchlistState())
    override val state = mutableState.asStateFlow()
    override suspend fun refresh(force: Boolean) =
        TraktWatchlistRefreshResult.Failure(TraktWatchlistFailure.DISCONNECTED)
    override suspend fun setWatchlisted(identity: TraktWatchlistIdentity, watchlisted: Boolean) =
        TraktWatchlistActionResult.Failure(TraktWatchlistFailure.DISCONNECTED)
}
