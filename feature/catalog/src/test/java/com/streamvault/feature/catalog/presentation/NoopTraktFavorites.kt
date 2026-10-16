package com.streamvault.feature.catalog.presentation

import com.streamvault.domain.trakt.TraktFavoriteActionResult
import com.streamvault.domain.trakt.TraktFavoriteIdentity
import com.streamvault.domain.trakt.TraktFavorites

internal class NoopTraktFavorites : TraktFavorites {
    override suspend fun setFavorited(
        identity: TraktFavoriteIdentity,
        favorited: Boolean,
    ) = TraktFavoriteActionResult.Success
}
