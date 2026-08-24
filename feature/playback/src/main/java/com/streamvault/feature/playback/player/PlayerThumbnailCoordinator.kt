package com.streamvault.feature.playback.player

import android.graphics.Bitmap
import javax.inject.Inject

/** Feature boundary for seek-frame extraction and its shared cache. */
class PlayerThumbnailCoordinator @Inject constructor(
    private val provider: SeekThumbnailProvider
) {
    internal fun supportsFrameExtraction(streamUrl: String): Boolean =
        provider.supportsFrameExtraction(streamUrl)

    internal suspend fun loadFrame(streamUrl: String, positionMs: Long): Bitmap? =
        provider.loadFrame(streamUrl, positionMs)

    internal fun clearCache() {
        provider.clearCache()
    }
}
