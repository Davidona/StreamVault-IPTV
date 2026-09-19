package com.streamvault.feature.playback.player

import com.streamvault.domain.model.ProviderType
import java.net.URI
import java.util.Locale

internal data class PlaybackProbeFailure(
    val message: String,
    val recoveryType: PlayerRecoveryType
)

internal fun resolvePlaybackProbeFailure(responseCode: Int): PlaybackProbeFailure? = when (responseCode) {
    204 -> PlaybackProbeFailure(
        message = "Portal issued an empty temporary link for this stream (HTTP 204). Retrying the channel may refresh the portal session.",
        recoveryType = PlayerRecoveryType.SOURCE
    )

    401, 403 -> PlaybackProbeFailure(
        message = "This provider stream was rejected ($responseCode Unauthorized/Forbidden).",
        recoveryType = PlayerRecoveryType.SOURCE
    )

    456 -> PlaybackProbeFailure(
        message = "This provider rejected playback for this channel (HTTP 456). The MAC or subscription may not have access to this stream.",
        recoveryType = PlayerRecoveryType.SOURCE
    )

    404 -> PlaybackProbeFailure(
        message = "This provider stream is unavailable right now (404).",
        recoveryType = PlayerRecoveryType.SOURCE
    )

    in 500..599 -> PlaybackProbeFailure(
        message = "The provider returned a server error for this stream ($responseCode).",
        recoveryType = PlayerRecoveryType.NETWORK
    )

    else -> null
}

internal fun shouldSkipPlaybackProbe(
    providerType: ProviderType,
    url: String
): Boolean {
    val normalizedPath = runCatching {
        URI(url).path?.lowercase(Locale.ROOT).orEmpty()
    }.getOrDefault("")
    val normalizedQuery = runCatching {
        URI(url).query?.lowercase(Locale.ROOT).orEmpty()
    }.getOrDefault("")
    return when (providerType) {
        ProviderType.STALKER_PORTAL -> normalizedPath.endsWith("/play/live.php") ||
            normalizedPath.endsWith("/play/movie.php") ||
            "play_token=" in normalizedQuery

        ProviderType.XTREAM_CODES -> normalizedPath.contains("/live/")

        else -> false
    }
}
