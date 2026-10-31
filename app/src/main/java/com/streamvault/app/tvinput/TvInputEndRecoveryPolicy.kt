package com.streamvault.app.tvinput

import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.model.StreamType
import com.streamvault.player.playback.ResolvedStreamType
import com.streamvault.player.playback.canRecoverLiveMpegTsEnd

/** TV input owns its source directly. Keep unknown/progressive/finite sources out of live EOF recovery. */
internal fun canRecoverTvInputEnd(
    info: StreamInfo?,
    type: StreamType,
    firstVideoFrame: Boolean,
    playWhenReady: Boolean,
    retryPending: Boolean
): Boolean = canRecoverLiveMpegTsEnd(
    streamInfo = info,
    streamType = if (type == StreamType.MPEG_TS) ResolvedStreamType.MPEG_TS_LIVE else ResolvedStreamType.UNKNOWN,
    playbackStarted = firstVideoFrame,
    playWhenReady = playWhenReady,
    hasIndependentSourceOwner = false,
    retryPending = retryPending
)
