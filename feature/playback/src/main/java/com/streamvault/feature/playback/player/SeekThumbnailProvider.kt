package com.streamvault.feature.playback.player

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

@Singleton
class SeekThumbnailProvider @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    companion object {
        private const val FRAME_BUCKET_MS = 10_000L
        private const val MAX_PREVIEW_WIDTH = 480
        /**
         * Maximum time to wait for [MediaMetadataRetriever] to open a remote URL and extract
         * a frame. Without this, [MediaMetadataRetriever.setDataSource] on a slow or live HTTP
         * stream can block a [kotlinx.coroutines.Dispatchers.IO] thread indefinitely.
         */
        private const val RETRIEVER_TIMEOUT_MS = 8_000L
    }

    private val bitmapCache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 1024L / 24L).toInt()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    fun bucketPositionMs(positionMs: Long): Long =
        (positionMs.coerceAtLeast(0L) / FRAME_BUCKET_MS) * FRAME_BUCKET_MS

    fun supportsFrameExtraction(streamUrl: String): Boolean {
        if (streamUrl.isBlank()) return false
        val normalized = streamUrl.lowercase()
        val scheme = runCatching { Uri.parse(streamUrl).scheme?.lowercase() }.getOrNull()
        if (normalized.contains(".m3u8") || normalized.contains(".mpd")) return false
        return scheme == null || scheme in setOf("http", "https", "file", "content")
    }

    suspend fun loadFrame(streamUrl: String, positionMs: Long): Bitmap? = withContext(Dispatchers.IO) {
        if (!supportsFrameExtraction(streamUrl)) {
            return@withContext null
        }

        val bucketPosition = bucketPositionMs(positionMs)
        val cacheKey = "$streamUrl#$bucketPosition"
        bitmapCache.get(cacheKey)?.let { return@withContext it }

        // withTimeoutOrNull guards against MediaMetadataRetriever.setDataSource() hanging
        // indefinitely on a remote HTTP URL (e.g. a live stream with no file extension).
        withTimeoutOrNull(RETRIEVER_TIMEOUT_MS) {
            val retriever = MediaMetadataRetriever()
            try {
                val uri = Uri.parse(streamUrl)
                when (uri.scheme?.lowercase()) {
                    "content", "file" -> retriever.setDataSource(context, uri)
                    else -> retriever.setDataSource(streamUrl, emptyMap())
                }

                val rawBitmap = retriever.getFrameAtTime(bucketPosition * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: retriever.getFrameAtTime(bucketPosition * 1000L)
                    ?: return@withTimeoutOrNull null

                val scaledBitmap = rawBitmap.scaleDown(MAX_PREVIEW_WIDTH)
                bitmapCache.put(cacheKey, scaledBitmap)
                scaledBitmap
            } catch (_: Exception) {
                null
            } finally {
                runCatching { retriever.release() }
            }
        }
    }

    fun clearCache() {
        bitmapCache.evictAll()
    }

    private fun Bitmap.scaleDown(maxWidth: Int): Bitmap {
        if (width <= maxWidth || width <= 0 || height <= 0) return this
        val scaledHeight = (height * (maxWidth.toFloat() / width.toFloat())).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(this, maxWidth, scaledHeight, true)
    }
}
