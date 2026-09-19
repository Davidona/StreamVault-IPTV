@file:androidx.media3.common.util.UnstableApi

package com.streamvault.player.playback

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.core.graphics.scale
import androidx.media3.common.MediaItem
import androidx.media3.inspector.frame.FrameExtractor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.MediaSource
import com.streamvault.domain.model.StreamInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import com.google.common.util.concurrent.MoreExecutors

internal fun interface FrameThumbnailSessionFactory {
    fun create(request: FrameThumbnailRequest): FrameThumbnailSession
}

internal interface FrameThumbnailSession {
    suspend fun frameAt(positionMs: Long): Bitmap?

    fun close()
}

@Singleton
@UnstableApi
class Media3FrameThumbnailExtractor @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient
) : FrameThumbnailExtractor {
    private companion object {
        private const val TAG = "Media3FrameThumbnail"
        private const val FRAME_BUCKET_MS = 10_000L
        private const val MAX_PREVIEW_WIDTH = 480
        private const val EXTRACTION_TIMEOUT_MS = 8_000L
    }

    private val dataSourceFactoryProvider = PlayerDataSourceFactoryProvider(
        context = context,
        baseClient = okHttpClient
    )
    private val playerMediaSourceFactory = PlayerMediaSourceFactory(dataSourceFactoryProvider)
    private val extractionDispatcher: CoroutineDispatcher =
        Dispatchers.IO.limitedParallelism(1)
    private val bitmapCache = object : android.util.LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024L / 24L).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }
    private var sessionFactory: FrameThumbnailSessionFactory = FrameThumbnailSessionFactory(::createSession)

    internal constructor(
        context: Context,
        okHttpClient: OkHttpClient,
        sessionFactory: FrameThumbnailSessionFactory
    ) : this(context, okHttpClient) {
        this.sessionFactory = sessionFactory
    }

    override fun supports(request: FrameThumbnailRequest): Boolean =
        supportsMedia3FrameThumbnail(request)

    override suspend fun loadFrame(request: FrameThumbnailRequest, positionMs: Long): Bitmap? {
        if (!supports(request)) return null

        val bucketPositionMs = bucketPositionMs(positionMs)
        val cacheKey = cacheKey(request.streamInfo, bucketPositionMs)
        synchronized(bitmapCache) {
            bitmapCache.get(cacheKey)?.let {
                Log.d(
                    TAG,
                    "frame-thumbnail backend=media3 result=cache-hit " +
                        "streamType=${StreamTypeResolver.resolve(request.streamInfo)} positionMs=$bucketPositionMs"
                )
                return it
            }
        }

        return withContext(extractionDispatcher) {
            synchronized(bitmapCache) {
                bitmapCache.get(cacheKey)?.let { return@withContext it }
            }

            val startedAt = System.nanoTime()
            val session = try {
                sessionFactory.create(request)
            } catch (error: Exception) {
                logFailure(request, bucketPositionMs, startedAt, error)
                return@withContext null
            }

            try {
                withTimeoutOrNull(EXTRACTION_TIMEOUT_MS) {
                    val rawBitmap = session.frameAt(bucketPositionMs) ?: return@withTimeoutOrNull null
                    val scaledBitmap = rawBitmap.scaleDown(MAX_PREVIEW_WIDTH)
                    synchronized(bitmapCache) {
                        bitmapCache.put(cacheKey, scaledBitmap)
                    }
                    Log.d(
                        TAG,
                        "frame-thumbnail backend=media3 result=success " +
                            "streamType=${StreamTypeResolver.resolve(request.streamInfo)} " +
                            "positionMs=$bucketPositionMs elapsedMs=${elapsedMs(startedAt)}"
                    )
                    scaledBitmap
                } ?: run {
                    Log.w(
                        TAG,
                        "frame-thumbnail backend=media3 result=timeout " +
                            "streamType=${StreamTypeResolver.resolve(request.streamInfo)} " +
                            "positionMs=$bucketPositionMs elapsedMs=${elapsedMs(startedAt)}"
                    )
                    null
                }
            } catch (error: CancellationException) {
                Log.d(
                    TAG,
                    "frame-thumbnail backend=media3 result=cancelled " +
                        "streamType=${StreamTypeResolver.resolve(request.streamInfo)} positionMs=$bucketPositionMs"
                )
                throw error
            } catch (error: Exception) {
                logFailure(request, bucketPositionMs, startedAt, error)
                null
            } finally {
                runCatching { session.close() }
            }
        }
    }

    override fun clearCache() {
        synchronized(bitmapCache) {
            bitmapCache.evictAll()
        }
    }

    private fun createSession(request: FrameThumbnailRequest): FrameThumbnailSession {
        val resolvedStreamType = StreamTypeResolver.resolve(request.streamInfo)
        val timeoutProfile = PlayerTimeoutProfile.PRELOAD
        val retryPolicy = PlayerRetryPolicy(
            streamContext = PlaybackRetryContext(
                resolvedStreamType = resolvedStreamType,
                timeoutProfile = timeoutProfile
            ),
            playbackStarted = { false }
        )
        val (_, mediaSourceFactory) = playerMediaSourceFactory.createMediaSourceFactory(
            streamInfo = request.streamInfo,
            resolvedStreamType = resolvedStreamType,
            retryPolicy = retryPolicy,
            preload = true
        )
        return Media3FrameThumbnailSession(
            context = context,
            mediaItem = playerMediaSourceFactory.mediaItemFor(request.streamInfo),
            mediaSourceFactory = mediaSourceFactory
        )
    }

    private fun cacheKey(streamInfo: StreamInfo, bucketPositionMs: Long): String =
        "${playerMediaSourceFactory.mediaIdFor(streamInfo)}#$bucketPositionMs"

    private fun bucketPositionMs(positionMs: Long): Long =
        (positionMs.coerceAtLeast(0L) / FRAME_BUCKET_MS) * FRAME_BUCKET_MS

    private fun logFailure(
        request: FrameThumbnailRequest,
        bucketPositionMs: Long,
        startedAt: Long,
        error: Exception
    ) {
        Log.w(
            TAG,
            "frame-thumbnail backend=media3 result=failure " +
                "streamType=${StreamTypeResolver.resolve(request.streamInfo)} " +
                "positionMs=$bucketPositionMs elapsedMs=${elapsedMs(startedAt)} " +
                "error=${error::class.java.simpleName}"
        )
    }

    private fun elapsedMs(startedAt: Long): Long =
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

    private fun Bitmap.scaleDown(maxWidth: Int): Bitmap {
        if (width <= maxWidth || width <= 0 || height <= 0) return this
        val scaledHeight = (height * (maxWidth.toFloat() / width.toFloat()))
            .toInt()
            .coerceAtLeast(1)
        return this.scale(maxWidth, scaledHeight, true)
    }
}

@UnstableApi
private class Media3FrameThumbnailSession(
    context: Context,
    mediaItem: MediaItem,
    mediaSourceFactory: MediaSource.Factory
) : FrameThumbnailSession {
    private val extractor = FrameExtractor.Builder(context, mediaItem)
        .setMediaSourceFactory(mediaSourceFactory)
        .setSeekParameters(SeekParameters.CLOSEST_SYNC)
        .build()

    override suspend fun frameAt(positionMs: Long): Bitmap? =
        suspendCancellableCoroutine { continuation ->
            val future = extractor.getFrame(positionMs)
            future.addListener(
                {
                    if (!continuation.isActive) return@addListener
                    try {
                        continuation.resume(future.get().bitmap)
                    } catch (error: Throwable) {
                        continuation.resumeWithException(error.cause ?: error)
                    }
                },
                MoreExecutors.directExecutor()
            )
            continuation.invokeOnCancellation {
                future.cancel(true)
            }
        }

    override fun close() {
        extractor.close()
    }
}
