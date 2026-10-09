package com.streamvault.player.playback

import com.streamvault.domain.model.PlaybackContentKind

internal fun shouldDeferAppStallRecovery(
    activeMediaLoadRetry: Boolean,
    sourceRetryPending: Boolean
): Boolean = activeMediaLoadRetry || sourceRetryPending

internal fun shouldRecoverReadyStalls(resolvedStreamType: ResolvedStreamType): Boolean =
    true

internal fun shouldRecoverPositionAdvancingReadyStalls(
    resolvedStreamType: ResolvedStreamType,
    playbackContentKind: PlaybackContentKind = PlaybackContentKind.UNKNOWN
): Boolean = !isLivePlayback(playbackContentKind, resolvedStreamType)

internal fun shouldRecoverFrameSilentReadyStalls(
    resolvedStreamType: ResolvedStreamType,
    playbackContentKind: PlaybackContentKind = PlaybackContentKind.UNKNOWN
): Boolean = isLivePlayback(playbackContentKind, resolvedStreamType)
