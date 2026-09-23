package com.streamvault.player.playback

import com.streamvault.domain.model.PlaybackContentKind
import com.streamvault.player.PlaybackState

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

internal fun shouldReconnectLiveStall(
    playbackState: PlaybackState,
    resolvedStreamType: ResolvedStreamType,
    recoveryAttempt: Int,
    playbackContentKind: PlaybackContentKind = PlaybackContentKind.UNKNOWN
): Boolean =
    recoveryAttempt == 1 &&
        isLivePlayback(playbackContentKind, resolvedStreamType) &&
        (playbackState == PlaybackState.BUFFERING || playbackState == PlaybackState.READY)
