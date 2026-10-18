package com.streamvault.feature.playback.player

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

@Singleton
class SeekThumbnailProvider @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient
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

    suspend fun loadFrame(
        streamUrl: String,
        positionMs: Long,
        requestHeaders: Map<String, String> = emptyMap()
    ): Bitmap? {
        if (!supportsFrameExtraction(streamUrl)) {
            return null
        }

        val bucketPosition = bucketPositionMs(positionMs)
        val cacheKey = "$streamUrl#$bucketPosition"
        bitmapCache.get(cacheKey)?.let { return it }

        val bitmap = withTimeoutOrNull(RETRIEVER_TIMEOUT_MS) {
            extractFrameCancellable(streamUrl, requestHeaders, bucketPosition)
        }
        if (bitmap != null) bitmapCache.put(cacheKey, bitmap)
        return bitmap
    }

    private suspend fun extractFrameCancellable(
        streamUrl: String,
        requestHeaders: Map<String, String>,
        bucketPositionMs: Long
    ): Bitmap? = suspendCancellableCoroutine { continuation ->
        val activeAttempt = AtomicReference<ActiveRetrieverAttempt?>()
        continuation.invokeOnCancellation { activeAttempt.get()?.cancelNetworkReads() }

        Dispatchers.IO.dispatch(continuation.context, Runnable {
            if (!continuation.isActive) return@Runnable

            val uri = Uri.parse(streamUrl)
            val dataSource = when (uri.scheme?.lowercase()) {
                "http", "https" -> CancellableHttpMediaDataSource(
                    url = streamUrl,
                    baseClient = okHttpClient,
                    headers = requestHeaders,
                    callTimeoutMs = RETRIEVER_TIMEOUT_MS
                )
                else -> null
            }
            val attempt = ActiveRetrieverAttempt(MediaMetadataRetriever(), dataSource)
            activeAttempt.set(attempt)
            if (!continuation.isActive) {
                attempt.cancelNetworkReads()
                attempt.release()
                activeAttempt.compareAndSet(attempt, null)
                return@Runnable
            }

            try {
                val retriever = attempt.retriever
                when (uri.scheme?.lowercase()) {
                    "content", "file" -> retriever.setDataSource(context, uri)
                    "http", "https" -> retriever.setDataSource(requireNotNull(dataSource))
                    else -> retriever.setDataSource(streamUrl, requestHeaders)
                }

                val frameTimeUs = bucketPositionMs * 1000L
                val rawBitmap = retriever.getFrameAtTime(frameTimeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: retriever.getFrameAtTime(frameTimeUs)
                val scaledBitmap = rawBitmap?.scaleDown(MAX_PREVIEW_WIDTH)
                if (continuation.isActive) continuation.resume(scaledBitmap)
            } catch (error: CancellationException) {
                if (continuation.isActive) continuation.resumeWithException(error)
            } catch (_: Exception) {
                if (continuation.isActive) continuation.resume(null)
            } finally {
                activeAttempt.compareAndSet(attempt, null)
                attempt.release()
            }
        })
    }

    fun clearCache() {
        bitmapCache.evictAll()
    }

    private fun Bitmap.scaleDown(maxWidth: Int): Bitmap {
        if (width <= maxWidth || width <= 0 || height <= 0) return this
        val scaledHeight = (height * (maxWidth.toFloat() / width.toFloat())).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(this, maxWidth, scaledHeight, true)
    }

    private class ActiveRetrieverAttempt(
        val retriever: MediaMetadataRetriever,
        private val dataSource: CancellableHttpMediaDataSource?
    ) {
        private val released = AtomicBoolean(false)

        fun cancelNetworkReads() {
            dataSource?.close()
        }

        fun release() {
            if (!released.compareAndSet(false, true)) return
            cancelNetworkReads()
            runCatching { retriever.release() }
        }
    }
}
