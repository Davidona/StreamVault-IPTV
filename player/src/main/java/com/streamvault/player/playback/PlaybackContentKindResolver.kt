package com.streamvault.player.playback

import com.streamvault.domain.model.PlaybackContentKind

internal fun isLivePlayback(kind: PlaybackContentKind, type: ResolvedStreamType): Boolean =
    when (kind) {
        PlaybackContentKind.LIVE -> true
        PlaybackContentKind.ON_DEMAND, PlaybackContentKind.CATCH_UP -> false
        PlaybackContentKind.UNKNOWN -> when (type) {
            ResolvedStreamType.HLS,
            ResolvedStreamType.SMOOTH_STREAMING,
            ResolvedStreamType.MPEG_TS_LIVE,
            ResolvedStreamType.RTSP -> true
            else -> false
        }
    }
