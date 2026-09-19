package com.streamvault.feature.playback.player

import android.graphics.Bitmap
import android.util.Log
import com.streamvault.domain.model.StreamInfo
import com.streamvault.player.playback.FrameThumbnailExtractor
import com.streamvault.player.playback.FrameThumbnailRequest
import com.streamvault.player.playback.Media3FrameThumbnailExtractor
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject

/** Feature boundary for seek-frame extraction and its shared cache. */
class PlayerThumbnailCoordinator @Inject constructor(
    private val media3Extractor: FrameThumbnailExtractor,
    private val legacyProvider: SeekThumbnailProvider
) {
    private companion object {
        private const val TAG = "PlayerThumbnail"
    }

    internal fun supportsFrameExtraction(
        streamUrl: String,
        streamInfo: StreamInfo?,
        isLive: Boolean
    ): Boolean {
        val request = buildRequest(streamUrl, streamInfo, isLive)
        return (request != null && media3Extractor.supports(request)) ||
            legacyProvider.supportsFrameExtraction(streamUrl)
    }

    internal suspend fun loadFrame(
        streamUrl: String,
        streamInfo: StreamInfo?,
        isLive: Boolean,
        positionMs: Long
    ): Bitmap? {
        val request = buildRequest(streamUrl, streamInfo, isLive)
        if (request != null && media3Extractor.supports(request)) {
            media3Extractor.loadFrame(request, positionMs)?.let { return it }
            Log.d(TAG, "frame-thumbnail backend=legacy reason=media3-failure")
        } else {
            Log.d(TAG, "frame-thumbnail backend=legacy reason=media3-unsupported")
        }
        if (!legacyProvider.supportsFrameExtraction(streamUrl)) {
            Log.d(TAG, "frame-thumbnail backend=none reason=legacy-unsupported")
            return null
        }
        return legacyProvider.loadFrame(streamUrl, positionMs)
    }

    internal fun clearCache() {
        media3Extractor.clearCache()
        legacyProvider.clearCache()
    }

    private fun buildRequest(
        streamUrl: String,
        streamInfo: StreamInfo?,
        isLive: Boolean
    ): FrameThumbnailRequest? {
        val normalizedUrl = streamUrl.trim()
        val resolvedInfo = streamInfo ?: return null
        if (normalizedUrl.isBlank()) return null
        val requestInfo = if (resolvedInfo.url == normalizedUrl) {
            resolvedInfo
        } else {
            resolvedInfo.copy(url = normalizedUrl)
        }
        return FrameThumbnailRequest(
            streamInfo = requestInfo,
            isLive = isLive
        )
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class PlayerThumbnailModule {
    @Binds
    abstract fun bindFrameThumbnailExtractor(
        extractor: Media3FrameThumbnailExtractor
    ): FrameThumbnailExtractor
}
