package com.streamvault.player.playback

import androidx.media3.common.C

internal fun resolveRetrySeekPositionMs(
    category: PlaybackErrorCategory,
    resolvedStreamType: ResolvedStreamType,
    currentPositionMs: Long?,
    durationMs: Long?,
    isCurrentMediaItemLive: Boolean,
    playbackStarted: Boolean
): Long? {
    val positionMs = currentPositionMs?.takeIf { it > 0L } ?: return null
    if (category == PlaybackErrorCategory.LIVE_WINDOW) return null
    if (isCurrentMediaItemLive) return null

    return when (resolvedStreamType) {
        ResolvedStreamType.PROGRESSIVE -> positionMs.takeIf { playbackStarted }
        ResolvedStreamType.HLS,
        ResolvedStreamType.DASH,
        ResolvedStreamType.SMOOTH_STREAMING,
        ResolvedStreamType.UNKNOWN -> positionMs.takeIf { durationMs.isFiniteMediaDuration() }
        ResolvedStreamType.MPEG_TS_LIVE,
        ResolvedStreamType.RTSP -> null
    }
}

internal fun resolveRetryAttemptAfterReady(
    currentAttempt: Int,
    playbackStarted: Boolean
): Int {
    if (currentAttempt <= 0) return currentAttempt
    if (!playbackStarted) return currentAttempt
    return 0
}

internal fun resolveRetryAttemptAfterPlaybackStarted(currentAttempt: Int): Int {
    if (currentAttempt <= 0) return currentAttempt
    return 0
}

internal fun resolveNextAppSourceRetryAttempt(currentAttempt: Int): Int =
    (currentAttempt + 1).coerceAtLeast(1)

internal fun isScheduledAppSourceRetryCurrent(
    scheduledGeneration: Long,
    currentGeneration: Long,
    scheduledMediaId: String,
    currentMediaId: String?,
    scheduledAttempt: Int,
    currentAttempt: Int
): Boolean = scheduledGeneration == currentGeneration &&
    scheduledMediaId == currentMediaId &&
    scheduledAttempt == currentAttempt

internal fun resolveScheduledRetrySeekPositionMs(
    category: PlaybackErrorCategory,
    plannedPositionMs: Long?,
    userSeekOverrideMs: Long?
): Long? = if (category == PlaybackErrorCategory.LIVE_WINDOW) {
    null
} else {
    userSeekOverrideMs ?: plannedPositionMs
}

private fun Long?.isFiniteMediaDuration(): Boolean {
    return this != null && this != C.TIME_UNSET && this > 0L
}
