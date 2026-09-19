package com.streamvault.app.playback

import com.streamvault.data.remote.stalker.StalkerPlaybackResolutionException
import com.streamvault.data.remote.xtream.ProviderPlaybackResolver
import com.streamvault.data.remote.xtream.ResolvedStreamUrl
import com.streamvault.data.security.CredentialDecryptionException
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.Result
import com.streamvault.domain.provider.PlayerCredentialFailure
import com.streamvault.domain.provider.PlayerPlaybackResolutionFailure
import com.streamvault.domain.provider.PlayerPlaybackResolver
import com.streamvault.domain.provider.ResolvedPlayback
import javax.inject.Inject

/** App-owned adapter between data provider resolution and the player domain contract. */
class AppPlayerPlaybackResolver @Inject constructor(
    private val delegate: ProviderPlaybackResolver
) : PlayerPlaybackResolver {
    override fun isInternalStreamUrl(url: String?): Boolean = delegate.isInternalStreamUrl(url)

    override suspend fun resolveAndCommitMetadata(
        url: String,
        fallbackProviderId: Long?,
        fallbackStreamId: Long?,
        fallbackContentType: ContentType?,
        fallbackContainerExtension: String?,
        preferStableUrl: Boolean
    ): Result<ResolvedPlayback?> = try {
        Result.success(
            delegate.resolveAndCommitMetadata(
                url = url,
                fallbackProviderId = fallbackProviderId,
                fallbackStreamId = fallbackStreamId,
                fallbackContentType = fallbackContentType,
                fallbackContainerExtension = fallbackContainerExtension,
                preferStableUrl = preferStableUrl
            )?.toDomain()
        )
    } catch (error: CredentialDecryptionException) {
        Result.error(
            error.message ?: CredentialDecryptionException.MESSAGE,
            PlayerCredentialFailure(error.message ?: CredentialDecryptionException.MESSAGE, error)
        )
    } catch (error: StalkerPlaybackResolutionException) {
        val message = error.message ?: "We couldn't resolve a playable Stalker stream for this item."
        Result.error(message, PlayerPlaybackResolutionFailure(message, error))
    }
}

private fun ResolvedStreamUrl.toDomain(): ResolvedPlayback = ResolvedPlayback(
    url = url,
    expirationTime = expirationTime,
    containerExtension = containerExtension,
    headers = headers,
    userAgent = userAgent,
    playbackTransportPolicy = playbackTransportPolicy,
    allowInvalidSsl = allowInvalidSsl,
    proxyHost = proxyHost,
    proxyPort = proxyPort,
    observations = observations
)
