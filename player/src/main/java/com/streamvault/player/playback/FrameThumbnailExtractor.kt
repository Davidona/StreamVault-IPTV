package com.streamvault.player.playback

import android.graphics.Bitmap
import com.streamvault.domain.model.StreamInfo
import java.net.URI
import java.util.Locale

/** Stream metadata needed to decide whether a VOD frame can be extracted safely. */
data class FrameThumbnailRequest(
    val streamInfo: StreamInfo,
    val isLive: Boolean
)

/** Player-side boundary for decoded seek-preview frames. */
interface FrameThumbnailExtractor {
    fun supports(request: FrameThumbnailRequest): Boolean

    suspend fun loadFrame(request: FrameThumbnailRequest, positionMs: Long): Bitmap?

    fun clearCache()
}

internal fun supportsMedia3FrameThumbnail(request: FrameThumbnailRequest): Boolean {
    if (request.isLive || request.streamInfo.drmInfo != null) return false

    val resolvedStreamType = StreamTypeResolver.resolve(request.streamInfo)
    if (resolvedStreamType !in SUPPORTED_FRAME_THUMBNAIL_TYPES) return false

    val uri = runCatching { URI(request.streamInfo.url) }.getOrNull() ?: return false
    return uri.scheme?.lowercase(Locale.ROOT) in SUPPORTED_FRAME_THUMBNAIL_SCHEMES
}

private val SUPPORTED_FRAME_THUMBNAIL_TYPES = setOf(
    ResolvedStreamType.PROGRESSIVE,
    ResolvedStreamType.HLS,
    ResolvedStreamType.DASH,
    ResolvedStreamType.SMOOTH_STREAMING
)

private val SUPPORTED_FRAME_THUMBNAIL_SCHEMES = setOf("http", "https", "file", "content")
