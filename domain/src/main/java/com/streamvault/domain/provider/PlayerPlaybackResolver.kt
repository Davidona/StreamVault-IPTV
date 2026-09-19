package com.streamvault.domain.provider

import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.Result

/** Playback resolution surface consumed by the player feature. */
interface PlayerPlaybackResolver {
    fun isInternalStreamUrl(url: String?): Boolean

    suspend fun resolveAndCommitMetadata(
        url: String,
        fallbackProviderId: Long? = null,
        fallbackStreamId: Long? = null,
        fallbackContentType: ContentType? = null,
        fallbackContainerExtension: String? = null,
        preferStableUrl: Boolean = false
    ): Result<ResolvedPlayback?>
}

open class PlayerCredentialFailure(
    message: String,
    cause: Throwable? = null
) : IllegalStateException(message, cause)

class PlayerPlaybackResolutionFailure(
    message: String,
    cause: Throwable? = null
) : IllegalStateException(message, cause)
