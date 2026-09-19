package com.streamvault.player.playback

import android.util.Log
import androidx.annotation.MainThread
import androidx.media3.exoplayer.source.MediaSource
import com.streamvault.domain.model.StreamInfo

/**
 * Main-thread-only cache for a single preloaded media source owned by Media3PlayerEngine.
 */
@MainThread
class PreloadCoordinator(
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {
    var currentPreloadedMediaId: String? = null
        private set
    var currentPreloadedNormalizedUrl: String? = null
        private set
    var preloadedMediaSource: MediaSource? = null
        private set
    var createdAtMs: Long = 0L
        private set

    private var currentResolvedType: ResolvedStreamType? = null
    private var currentDrmKey: String? = null
    private var currentHeadersHash: String? = null

    @MainThread
    fun store(mediaId: String, streamInfo: StreamInfo, resolvedType: ResolvedStreamType, mediaSource: MediaSource) {
        currentPreloadedMediaId = mediaId
        currentPreloadedNormalizedUrl = normalizedUrl(streamInfo.url)
        preloadedMediaSource = mediaSource
        currentResolvedType = resolvedType
        currentDrmKey = drmKey(streamInfo)
        currentHeadersHash = headersHash(streamInfo)
        createdAtMs = nowMs()
        safeLog("preload stored mediaId=$mediaId type=$resolvedType target=${PlaybackLogSanitizer.sanitizeUrl(streamInfo.url)}")
    }

    @MainThread
    fun tryReuse(mediaId: String, streamInfo: StreamInfo, resolvedType: ResolvedStreamType): MediaSource? {
        val result = when {
            preloadedMediaSource == null -> null
            isExpired() -> {
                invalidate("expired")
                null
            }
            currentPreloadedMediaId != mediaId -> {
                invalidate("different-media-id")
                null
            }
            currentPreloadedNormalizedUrl != normalizedUrl(streamInfo.url) -> {
                invalidate("different-url")
                null
            }
            currentResolvedType != resolvedType -> {
                invalidate("different-stream-type")
                null
            }
            currentDrmKey != drmKey(streamInfo) -> {
                invalidate("different-drm")
                null
            }
            currentHeadersHash != headersHash(streamInfo) -> {
                invalidate("different-headers")
                null
            }
            else -> preloadedMediaSource
        }
        if (result != null) {
            safeLog("preload reused mediaId=$mediaId target=${PlaybackLogSanitizer.sanitizeUrl(streamInfo.url)}")
            clear()
        }
        return result
    }

    @MainThread
    fun onPlaybackStarted(mediaId: String) {
        if (currentPreloadedMediaId != null && currentPreloadedMediaId != mediaId) {
            invalidate("playback-started-different-media")
        }
    }

    @MainThread
    fun invalidate(reason: String) {
        if (preloadedMediaSource != null) {
            safeLog("preload invalidated reason=$reason mediaId=$currentPreloadedMediaId")
        }
        clear()
    }

    @MainThread
    fun release() {
        invalidate("release")
    }

    @MainThread
    fun isExpired(): Boolean = preloadedMediaSource != null && nowMs() - createdAtMs > PRELOAD_EXPIRY_MS

    private fun clear() {
        currentPreloadedMediaId = null
        currentPreloadedNormalizedUrl = null
        preloadedMediaSource = null
        currentResolvedType = null
        currentDrmKey = null
        currentHeadersHash = null
        createdAtMs = 0L
    }

    private fun normalizedUrl(url: String): String {
        return url.trim().substringBefore('#')
    }

    private fun drmKey(streamInfo: StreamInfo): String? {
        return streamInfo.drmInfo?.let {
            // Sort header keys for a deterministic fingerprint across JVM restarts.
            val headersPart = it.headers.entries
                .sortedBy { e -> e.key }
                .joinToString("&") { e -> "${e.key}=${e.value}" }
            "${it.scheme}:${it.staticClearKeyLicense?.fingerprint ?: stableHash(it.licenseUrl)}:${stableHash(headersPart)}"
        }
    }

    private fun headersHash(streamInfo: StreamInfo): String {
        // Build a sorted, deterministic string representation of the effective headers so that
        // two identical header maps always produce the same fingerprint after a JVM restart.
        // hashCode() is NOT content-stable across restarts and must not be used here.
        val effectiveHeaders = (streamInfo.headers.toSortedMap() + ("User-Agent" to (streamInfo.userAgent ?: "")))
        val canonical = effectiveHeaders.entries.joinToString("&") { "${it.key}=${it.value}" }
        return stableHash(canonical)
    }



    companion object {
        private const val TAG = "PreloadCoordinator"
        private const val PRELOAD_EXPIRY_MS = 2 * 60 * 1000L
    }

    private fun safeLog(message: String) {
        runCatching { Log.i(TAG, message) }
    }
}
