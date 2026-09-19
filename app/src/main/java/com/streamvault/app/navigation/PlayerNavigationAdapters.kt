package com.streamvault.app.navigation

import com.streamvault.core.navigation.AppDestination
import com.streamvault.core.navigation.PlayerNavigationRequest
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.Episode
import com.streamvault.domain.model.Movie
import com.streamvault.domain.repository.ChannelRepository

internal fun playerNavigationRequest(
    streamUrl: String,
    title: String,
    channelId: String? = null,
    internalId: Long = -1L,
    categoryId: Long? = null,
    providerId: Long? = null,
    isVirtual: Boolean = false,
    combinedProfileId: Long? = null,
    combinedSourceFilterProviderId: Long? = null,
    contentType: String = "LIVE",
    artworkUrl: String? = null,
    archiveStartMs: Long? = null,
    archiveEndMs: Long? = null,
    archiveTitle: String? = null,
    returnDestination: AppDestination? = null,
    seriesId: Long? = null,
    seasonNumber: Int? = null,
    episodeNumber: Int? = null,
    episodeId: Long? = null
): PlayerNavigationRequest = PlayerNavigationRequest(
    streamUrl = streamUrl,
    title = title,
    channelId = channelId,
    internalId = internalId,
    categoryId = categoryId,
    providerId = providerId,
    isVirtual = isVirtual,
    combinedProfileId = combinedProfileId,
    combinedSourceFilterProviderId = combinedSourceFilterProviderId,
    contentType = contentType,
    artworkUrl = artworkUrl,
    archiveStartMs = archiveStartMs,
    archiveEndMs = archiveEndMs,
    archiveTitle = archiveTitle,
    returnDestination = returnDestination,
    seriesId = seriesId,
    seasonNumber = seasonNumber,
    episodeNumber = episodeNumber,
    episodeId = episodeId
)

internal fun Channel.toLivePlayerRequest(
    categoryId: Long? = this.categoryId,
    providerId: Long? = this.providerId,
    isVirtual: Boolean = false,
    combinedProfileId: Long? = null,
    combinedSourceFilterProviderId: Long? = null,
    returnDestination: AppDestination? = null
): PlayerNavigationRequest = playerNavigationRequest(
    streamUrl = streamUrl,
    title = name,
    channelId = epgChannelId,
    internalId = id,
    categoryId = categoryId ?: ChannelRepository.ALL_CHANNELS_ID,
    providerId = providerId,
    isVirtual = isVirtual,
    combinedProfileId = combinedProfileId,
    combinedSourceFilterProviderId = combinedSourceFilterProviderId,
    contentType = "LIVE",
    returnDestination = returnDestination
)

internal fun Movie.toPlayerNavigationRequest(
    returnDestination: AppDestination? = null
): PlayerNavigationRequest = playerNavigationRequest(
    streamUrl = streamUrl,
    title = name,
    internalId = id,
    categoryId = categoryId,
    providerId = providerId,
    contentType = "MOVIE",
    artworkUrl = posterUrl ?: backdropUrl,
    returnDestination = returnDestination
)

internal fun Episode.toPlayerNavigationRequest(
    returnDestination: AppDestination? = null
): PlayerNavigationRequest = playerNavigationRequest(
    streamUrl = streamUrl,
    title = "$title - S${seasonNumber}E${episodeNumber}",
    internalId = id,
    providerId = providerId,
    contentType = "SERIES_EPISODE",
    artworkUrl = coverUrl,
    returnDestination = returnDestination,
    seriesId = seriesId.takeIf { it > 0L },
    seasonNumber = seasonNumber,
    episodeNumber = episodeNumber,
    episodeId = episodeId.takeIf { it > 0L }
)
