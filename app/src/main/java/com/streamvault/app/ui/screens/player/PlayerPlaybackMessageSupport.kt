package com.streamvault.app.ui.screens.player

import com.streamvault.domain.playback.archivePlaybackCapability
import com.streamvault.domain.model.Channel
import java.util.Locale

internal fun isAuthExpiryPlaybackError(message: String?): Boolean {
    val normalized = message.orEmpty().lowercase(Locale.ROOT)
    return "401" in normalized ||
        "403" in normalized ||
        "unauthorized" in normalized ||
        "forbidden" in normalized ||
        "authentication" in normalized ||
        "token" in normalized ||
        "expired" in normalized
}

internal fun resolveCatchUpFailureMessage(
    channel: Channel?,
    archiveRequested: Boolean,
    programHasArchive: Boolean
): String {
    if (!archiveRequested || channel == null) {
        return "Catch-up playback needs a valid live channel context."
    }
    val archiveCapability = channel.archivePlaybackCapability()
    return when {
        !archiveCapability.advertisedByProvider && !programHasArchive ->
            "This channel does not advertise archive support on the current provider."
        !archiveCapability.canBuildReplayCandidate ->
            "The provider advertises catch-up, but did not expose enough replay metadata for this channel."
        else ->
            "Replay is unavailable for the selected program right now."
    }
}

internal fun resolvePlaybackFormatLabel(
    currentResolvedPlaybackUrl: String,
    currentStreamUrl: String
): String {
    val url = currentResolvedPlaybackUrl.ifBlank { currentStreamUrl }.lowercase(Locale.ROOT)
    return when {
        url.contains("ext=m3u8") || url.endsWith(".m3u8") -> "HLS"
        url.contains("ext=ts") || url.endsWith(".ts") -> "TS"
        else -> "stream"
    }
}
