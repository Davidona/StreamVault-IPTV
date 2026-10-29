package com.streamvault.app.tvinput

import androidx.media3.common.PlaybackException
import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.model.StreamType

/** Restrict surface-loss repair to the directly owned clear live HTTP TS path we measured. */
internal fun canRecoverTvInputSurfaceError(
    info: StreamInfo?,
    type: StreamType,
    firstVideoFrame: Boolean,
    playWhenReady: Boolean,
    retryPending: Boolean,
    surfacePresent: Boolean,
    errorCode: Int
): Boolean = errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED && !surfacePresent &&
    canRecoverTvInputEnd(info, type, firstVideoFrame, playWhenReady, retryPending)
