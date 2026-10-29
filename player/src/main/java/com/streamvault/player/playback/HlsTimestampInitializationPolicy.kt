@file:androidx.media3.common.util.UnstableApi

package com.streamvault.player.playback

import androidx.media3.exoplayer.hls.HlsMediaSource
import com.streamvault.domain.model.PlaybackContentKind
import java.util.concurrent.TimeoutException

internal const val LIVE_HLS_TIMESTAMP_INITIALIZATION_TIMEOUT_MS = 30_000L

/**
 * Bounds the secondary rendition's dependency wait, not the HTTP request or total startup.
 * Keep one policy for foreground, preload and Android TV-input playback. Finite media retains
 * Media3's default; the live budget permits slow primary initialization before retrying.
 */
fun HlsMediaSource.Factory.applyPlaybackTimestampInitializationPolicy(
    contentKind: PlaybackContentKind
): HlsMediaSource.Factory = setTimestampAdjusterInitializationTimeoutMs(
    if (isLivePlayback(contentKind, ResolvedStreamType.HLS)) {
        LIVE_HLS_TIMESTAMP_INITIALIZATION_TIMEOUT_MS
    } else {
        0L
    }
)

/** Media3 1.11 wraps this particular shared-adjuster failure in IOException. */
internal fun isHlsTimestampInitializationTimeout(error: Throwable): Boolean =
    generateSequence(error) { it.cause }.any {
        it is TimeoutException &&
            it.message?.startsWith("TimestampAdjuster failed to initialize in ") == true
    }
