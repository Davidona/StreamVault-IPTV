package com.streamvault.app.ui.screens.player

import com.streamvault.data.remote.xtream.XtreamStreamUrlResolver
import com.streamvault.data.remote.stalker.StalkerPlaybackResolutionException
import com.streamvault.data.security.CredentialDecryptionException
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.Episode
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.Series
import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.model.StreamType
import com.streamvault.domain.repository.ChannelRepository
import com.streamvault.domain.repository.MovieRepository
import com.streamvault.domain.repository.SeriesRepository

internal data class SeriesEpisodeResolution(
    val resolvedEpisode: Episode?,
    val nextEpisode: Episode?,
    val resolvedArtworkUrl: String?,
    val resolvedTitle: String?,
    val resolvedSeasonNumber: Int?,
    val resolvedEpisodeNumber: Int?
)

internal data class PlayerPlaybackStreamResolution(
    val streamInfo: StreamInfo?,
    val credentialFailureMessage: String? = null,
    val resolutionFailureMessage: String? = null
)

internal fun buildSeriesEpisodeResolution(
    series: Series,
    episodeId: Long,
    seasonNumber: Int?,
    episodeNumber: Int?,
    currentContentType: ContentType,
    currentArtworkUrl: String?
): SeriesEpisodeResolution {
    val resolvedEpisode = resolveEpisode(series, episodeId, seasonNumber, episodeNumber)
    return SeriesEpisodeResolution(
        resolvedEpisode = resolvedEpisode,
        nextEpisode = resolvedEpisode?.let { findNextEpisode(series, it) },
        resolvedArtworkUrl = if (resolvedEpisode != null && currentContentType == ContentType.SERIES_EPISODE) {
            resolvedEpisode.coverUrl ?: currentArtworkUrl ?: series.posterUrl ?: series.backdropUrl
        } else {
            currentArtworkUrl
        },
        resolvedTitle = if (resolvedEpisode != null && currentContentType == ContentType.SERIES_EPISODE) {
            buildEpisodePlaybackTitle(resolvedEpisode)
        } else {
            null
        },
        resolvedSeasonNumber = resolvedEpisode?.seasonNumber ?: seasonNumber,
        resolvedEpisodeNumber = resolvedEpisode?.episodeNumber ?: episodeNumber
    )
}

internal suspend fun resolvePlayerPlaybackStreamInfo(
    logicalUrl: String,
    internalContentId: Long,
    providerId: Long,
    contentType: ContentType,
    currentTitle: String,
    currentSeries: Series?,
    currentEpisode: Episode?,
    channelRepository: ChannelRepository,
    movieRepository: MovieRepository,
    seriesRepository: SeriesRepository,
    xtreamStreamUrlResolver: XtreamStreamUrlResolver
): PlayerPlaybackStreamResolution {
    var fallbackStreamId: Long? = null
    var fallbackContainerExtension: String? = null

    if (providerId > 0L && internalContentId > 0L) {
        when (contentType) {
            ContentType.LIVE -> {
                channelRepository.getChannel(internalContentId)?.let { channel ->
                    fallbackStreamId = channel.streamId.takeIf { it > 0L }
                        ?: channel.epgChannelId?.toLongOrNull()
                    val streamInfoResult = channelRepository.getStreamInfo(channel)
                    if (streamInfoResult.isSuccess) {
                        streamInfoResult.getOrNull()?.let { resolved ->
                            return PlayerPlaybackStreamResolution(
                                streamInfo = resolved.copy(title = resolved.title ?: currentTitle)
                            )
                        }
                    } else {
                        // Propagate the underlying error so the caller can show it to the user
                        (streamInfoResult as? Result.Error)?.message?.let { errorMsg ->
                            return PlayerPlaybackStreamResolution(
                                streamInfo = null,
                                resolutionFailureMessage = errorMsg
                            )
                        }
                    }
                }
            }

            ContentType.MOVIE -> {
                movieRepository.getMovie(internalContentId)?.let { movie ->
                    fallbackStreamId = movie.streamId.takeIf { it > 0L }
                    fallbackContainerExtension = movie.containerExtension
                    val streamInfoResult = movieRepository.getStreamInfo(movie)
                    if (streamInfoResult.isSuccess) {
                        streamInfoResult.getOrNull()?.let { resolved ->
                            return PlayerPlaybackStreamResolution(
                                streamInfo = resolved.copy(title = resolved.title ?: currentTitle)
                            )
                        }
                    } else {
                        (streamInfoResult as? Result.Error)?.message?.let { errorMsg ->
                            return PlayerPlaybackStreamResolution(
                                streamInfo = null,
                                resolutionFailureMessage = errorMsg
                            )
                        }
                    }
                }
            }

            ContentType.SERIES,
            ContentType.SERIES_EPISODE -> {
                val episode = when {
                    currentEpisode?.id == internalContentId -> currentEpisode
                    else -> currentSeries
                        ?.seasons
                        .sanitizedForPlayer()
                        .asSequence()
                        .flatMap { it.episodes.asSequence() }
                        .firstOrNull { it.id == internalContentId }
                }
                episode?.let {
                    fallbackStreamId = it.episodeId.takeIf { episodeId -> episodeId > 0L } ?: it.id
                    fallbackContainerExtension = it.containerExtension
                    val streamInfoResult = seriesRepository.getEpisodeStreamInfo(it)
                    if (streamInfoResult.isSuccess) {
                        streamInfoResult.getOrNull()?.let { resolved ->
                            return PlayerPlaybackStreamResolution(
                                streamInfo = resolved.copy(title = resolved.title ?: currentTitle)
                            )
                        }
                    } else {
                        (streamInfoResult as? Result.Error)?.message?.let { errorMsg ->
                            return PlayerPlaybackStreamResolution(
                                streamInfo = null,
                                resolutionFailureMessage = errorMsg
                            )
                        }
                    }
                }
            }
        }
    }

    try {
        xtreamStreamUrlResolver.resolveWithMetadata(
            url = logicalUrl,
            fallbackProviderId = providerId.takeIf { it > 0 },
            fallbackStreamId = fallbackStreamId,
            fallbackContentType = contentType,
            fallbackContainerExtension = fallbackContainerExtension
        )?.let { resolved ->
            val ext = resolved.containerExtension ?: fallbackContainerExtension
            return PlayerPlaybackStreamResolution(
                streamInfo = StreamInfo(
                    url = resolved.url,
                    title = currentTitle,
                    headers = resolved.headers,
                    userAgent = resolved.userAgent,
                    streamType = StreamType.fromContainerExtension(ext),
                    containerExtension = ext,
                    expirationTime = resolved.expirationTime
                )
            )
        }
    } catch (e: CredentialDecryptionException) {
        return PlayerPlaybackStreamResolution(
            streamInfo = null,
            credentialFailureMessage = e.message ?: CredentialDecryptionException.MESSAGE
        )
    } catch (e: StalkerPlaybackResolutionException) {
        return PlayerPlaybackStreamResolution(
            streamInfo = null,
            resolutionFailureMessage = e.message ?: "We couldn't resolve a playable Stalker stream for this item."
        )
    }

    val isLogicalInternalUrl = xtreamStreamUrlResolver.isInternalStreamUrl(logicalUrl)
    if (isLogicalInternalUrl) {
        return PlayerPlaybackStreamResolution(
            streamInfo = null,
            resolutionFailureMessage = "This portal requires a different playback path than the default command."
        )
    }

    return PlayerPlaybackStreamResolution(
        streamInfo = logicalUrl.takeIf { it.isNotBlank() }?.let {
            StreamInfo(
                url = it,
                title = currentTitle,
                streamType = StreamType.fromContainerExtension(fallbackContainerExtension),
                containerExtension = fallbackContainerExtension
            )
        }
    )
}
