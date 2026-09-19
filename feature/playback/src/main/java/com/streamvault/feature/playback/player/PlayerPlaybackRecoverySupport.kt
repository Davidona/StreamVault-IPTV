package com.streamvault.feature.playback.player

import com.streamvault.player.PlayerError

internal fun classifyPlaybackError(error: PlayerError): PlayerRecoveryType = when (error) {
    is PlayerError.NetworkError -> {
        if (error.message.contains("timeout", ignoreCase = true)) {
            PlayerRecoveryType.BUFFER_TIMEOUT
        } else if (
            error.message.contains("HTTP 456", ignoreCase = true) ||
            error.message.contains("HTTP 509", ignoreCase = true) ||
            error.message.contains("access denied", ignoreCase = true) ||
            error.message.contains("temporary link", ignoreCase = true) ||
            error.message.contains("playback path", ignoreCase = true)
        ) {
            PlayerRecoveryType.SOURCE
        } else {
            PlayerRecoveryType.NETWORK
        }
    }

    is PlayerError.SourceError -> PlayerRecoveryType.SOURCE
    is PlayerError.DecoderError -> PlayerRecoveryType.DECODER
    is PlayerError.DrmError -> PlayerRecoveryType.DRM
    is PlayerError.UnknownError -> {
        if (error.message.contains("timeout", ignoreCase = true)) {
            PlayerRecoveryType.BUFFER_TIMEOUT
        } else {
            PlayerRecoveryType.UNKNOWN
        }
    }
}

internal fun resolvePlaybackErrorMessage(error: PlayerError): String = when (classifyPlaybackError(error)) {
    PlayerRecoveryType.NETWORK -> "This stream is not responding right now. You can retry or try another source."
    PlayerRecoveryType.SOURCE -> when {
        error.message.contains("HTTP 456", ignoreCase = true) ||
            error.message.contains("access denied", ignoreCase = true) ->
            "This provider rejected playback for this channel. The MAC or subscription may not have access to this stream."

        error.message.contains("HTTP 509", ignoreCase = true) ->
            "Provider rejected playback, likely max connections or bandwidth limit."

        error.message.contains("temporary link", ignoreCase = true) ->
            "This portal issued an empty or invalid temporary link for playback."

        error.message.contains("playback path", ignoreCase = true) ->
            "This portal requires a different playback path than the default stream command."

        else -> "We couldn't start this stream on the available paths."
    }

    PlayerRecoveryType.DECODER -> "This stream could not play in the current decoder mode."
    PlayerRecoveryType.DRM -> "Playback requires valid DRM credentials or a supported device security level."
    PlayerRecoveryType.BUFFER_TIMEOUT -> "Playback stayed stuck buffering for too long on this stream."
    PlayerRecoveryType.CATCH_UP -> "Replay is unavailable for the selected program."
    PlayerRecoveryType.UNKNOWN -> error.message.ifBlank { "Playback failed for an unknown reason." }
}
