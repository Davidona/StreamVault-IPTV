package com.streamvault.player.playback

import com.streamvault.domain.model.PlaybackContentKind
import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.model.StreamType

enum class PlayerTimeoutProfile(
    val connectTimeoutMs: Long,
    val readTimeoutMs: Long,
    val writeTimeoutMs: Long
) {
    LIVE(
        connectTimeoutMs = 12_000L,
        readTimeoutMs = 20_000L,
        writeTimeoutMs = 20_000L
    ),
    VOD(
        connectTimeoutMs = 15_000L,
        readTimeoutMs = 45_000L,
        writeTimeoutMs = 30_000L
    ),
    PROGRESSIVE(
        connectTimeoutMs = 5_000L,
        readTimeoutMs = 10_000L,
        writeTimeoutMs = 30_000L
    ),
    PRELOAD(
        connectTimeoutMs = 10_000L,
        readTimeoutMs = 15_000L,
        writeTimeoutMs = 15_000L
    );

    companion object {
        fun resolve(
            streamInfo: StreamInfo,
            resolvedStreamType: ResolvedStreamType,
            preload: Boolean
        ): PlayerTimeoutProfile {
            if (preload) return PRELOAD
            val isLive = isLivePlayback(streamInfo.playbackContentKind, resolvedStreamType)
            if (
                isLive ||
                (streamInfo.playbackContentKind == PlaybackContentKind.UNKNOWN &&
                    streamInfo.streamType == StreamType.RTSP)
            ) return LIVE
            return when {
                resolvedStreamType == ResolvedStreamType.PROGRESSIVE -> PROGRESSIVE
                streamInfo.streamType == StreamType.PROGRESSIVE -> PROGRESSIVE
                else -> VOD
            }
        }
    }
}
