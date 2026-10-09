package com.streamvault.player.playback

import com.streamvault.player.PlaybackState

internal fun shouldAllowNeighborPreload(
    previouslyAllowed: Boolean,
    state: PlaybackState,
    isPlaying: Boolean,
    bufferedDurationMs: Long,
    recovering: Boolean,
    providerLimitBlocked: Boolean = false
): Boolean {
    if (state != PlaybackState.READY || !isPlaying || recovering || providerLimitBlocked) return false
    // Keep a reserve for the foreground and use hysteresis to avoid restarting on each sample.
    return if (previouslyAllowed) bufferedDurationMs > 5_000L else bufferedDurationMs >= 10_000L
}
