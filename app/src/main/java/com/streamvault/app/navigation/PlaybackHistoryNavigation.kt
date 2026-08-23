package com.streamvault.app.navigation

import com.streamvault.core.navigation.AppDestination
import com.streamvault.core.navigation.PlayerNavigationRequest
import com.streamvault.domain.model.PlaybackHistory

internal fun PlaybackHistory.toPlayerNavigationRequest(
    returnDestination: AppDestination? = null
): PlayerNavigationRequest = playerNavigationRequest(
    streamUrl = streamUrl,
    title = title,
    internalId = contentId,
    providerId = providerId,
    contentType = contentType.name,
    artworkUrl = posterUrl,
    returnDestination = returnDestination,
    seriesId = seriesId,
    seasonNumber = seasonNumber,
    episodeNumber = episodeNumber
)
