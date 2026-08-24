package com.streamvault.feature.playback.player

import com.streamvault.data.remote.xtream.ProviderPlaybackResolver
import com.streamvault.data.remote.stalker.StalkerPlaybackResolutionException
import com.streamvault.data.security.CredentialDecryptionException
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.Episode
import com.streamvault.domain.model.Movie
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.Series
import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.model.StreamType
import com.streamvault.domain.model.VodMovieVariant
import com.streamvault.domain.repository.ChannelRepository
import com.streamvault.domain.repository.MovieRepository
import com.streamvault.domain.repository.SeriesRepository
import javax.inject.Inject

/**
 * Feature-owned boundary for resolving player content and provider playback URLs.
 *
 * The ViewModel supplies only the current playback context. Repository lookup, provider URL
 * resolution, and credential/provider-specific failure translation stay behind this boundary.
 */
class PlayerContentResolver @Inject constructor(
    private val channelRepository: ChannelRepository,
    private val movieRepository: MovieRepository,
    private val seriesRepository: SeriesRepository,
    private val providerPlaybackResolver: ProviderPlaybackResolver
) {
    internal suspend fun resolvePlaybackStream(
        logicalUrl: String,
        internalContentId: Long,
        providerId: Long,
        contentType: ContentType,
        currentTitle: String,
        currentSeries: Series?,
        currentEpisode: Episode?
    ): PlayerPlaybackStreamResolution = resolvePlayerPlaybackStreamInfo(
        logicalUrl = logicalUrl,
        internalContentId = internalContentId,
        providerId = providerId,
        contentType = contentType,
        currentTitle = currentTitle,
        currentSeries = currentSeries,
        currentEpisode = currentEpisode,
        channelRepository = channelRepository,
        movieRepository = movieRepository,
        seriesRepository = seriesRepository,
        xtreamStreamUrlResolver = providerPlaybackResolver
    )

    internal fun isInternalStreamUrl(url: String?): Boolean = providerPlaybackResolver.isInternalStreamUrl(url)

    internal suspend fun getMovie(movieId: Long): Movie? = movieRepository.getMovie(movieId)

    internal suspend fun getMovieVariants(movieId: Long): List<VodMovieVariant> =
        movieRepository.getMovieVariants(movieId)

    internal suspend fun getMovieStreamInfo(movie: Movie): Result<StreamInfo> =
        movieRepository.getStreamInfo(movie)

    internal suspend fun getSeriesDetails(providerId: Long, seriesId: Long): Result<Series> =
        seriesRepository.getSeriesDetails(providerId, seriesId)

    internal suspend fun getEpisodeById(episodeId: Long): Episode? =
        seriesRepository.getEpisodeById(episodeId)
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
    xtreamStreamUrlResolver: ProviderPlaybackResolver
): PlayerPlaybackStreamResolution {
    var fallbackStreamId: Long? = null
    var fallbackContainerExtension: String? = null

    if (providerId > 0L && internalContentId > 0L) {
        when (contentType) {
            ContentType.LIVE -> {
                channelRepository.getChannel(internalContentId)?.let { channel ->
                    fallbackStreamId = channel.streamId.takeIf { it > 0L }
                        ?: channel.epgChannelId?.toLongOrNull()
                    if (shouldUseStoredLiveStreamInfo(logicalUrl, channel.streamUrl)) {
                        channelRepository.getStreamInfo(channel).getOrNull()?.let { resolved ->
                            return PlayerPlaybackStreamResolution(
                                streamInfo = resolved.copy(title = resolved.title ?: currentTitle)
                            )
                        }
                    }
                }
            }

            ContentType.MOVIE,
            ContentType.VOD -> {
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
                    currentEpisode?.id == internalContentId ||
                        currentEpisode?.playbackEpisodeIdentity() == internalContentId -> currentEpisode
                    else -> currentSeries
                        ?.seasons
                        .sanitizedForPlayer()
                        .asSequence()
                        .flatMap { it.episodes.asSequence() }
                        .firstOrNull {
                            it.id == internalContentId ||
                                it.playbackEpisodeIdentity() == internalContentId
                        }
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
        xtreamStreamUrlResolver.resolveAndCommitMetadata(
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
                    playbackTransportPolicy = resolved.playbackTransportPolicy,
                    allowInvalidSsl = resolved.allowInvalidSsl,
                    proxyHost = resolved.proxyHost,
                    proxyPort = resolved.proxyPort,
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
            resolutionFailureMessage = e.message
                ?: "We couldn't resolve a playable Stalker stream for this item."
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
