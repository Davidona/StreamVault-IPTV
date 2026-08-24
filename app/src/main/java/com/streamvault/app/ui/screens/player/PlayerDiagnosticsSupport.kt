package com.streamvault.app.ui.screens.player

import com.streamvault.domain.playback.ArchiveReplayMechanism
import com.streamvault.domain.playback.archivePlaybackCapability
import com.streamvault.domain.model.Channel

internal fun updateChannelDiagnosticsState(
    currentState: PlayerDiagnosticsUiState,
    channel: Channel
): PlayerDiagnosticsUiState {
    val archiveCapability = channel.archivePlaybackCapability()
    val archiveLabel = when {
        archiveCapability.mechanism == ArchiveReplayMechanism.XTREAM_STREAM_ID ->
            "Xtream catch-up supported (${archiveCapability.windowDays?.let { "$it days" } ?: "window unknown"})"
        archiveCapability.mechanism == ArchiveReplayMechanism.STALKER_ARCHIVE_TOKEN ->
            "Portal archive supported (${archiveCapability.windowDays?.let { "$it days" } ?: "window unknown"})"
        archiveCapability.mechanism == ArchiveReplayMechanism.M3U_TEMPLATE ->
            "M3U catch-up template present (best effort)"
        archiveCapability.advertisedByProvider ->
            "Provider advertises catch-up, but replay metadata is incomplete."
        else -> "No archive support advertised"
    }
    val hints = buildList {
        if (channel.errorCount > 0) {
            add("This channel has failed ${channel.errorCount} time(s) recently.")
        }
        if (channel.alternativeStreams.isNotEmpty()) {
            add("${channel.alternativeStreams.size} alternate stream path(s) available.")
        }
        if (archiveCapability.advertisedByProvider && !archiveCapability.canBuildReplayCandidate) {
            add("Replay may fail because this provider did not expose enough archive metadata.")
        }
        if (archiveCapability.canBuildReplayCandidate && !archiveCapability.hasKnownWindow) {
            add("Replay window depth is unknown; provider-marked programs are more reliable than arbitrary history.")
        }
    }
    return currentState.copy(
        alternativeStreamCount = channel.alternativeStreams.size,
        channelErrorCount = channel.errorCount,
        archiveSupportLabel = archiveLabel,
        troubleshootingHints = hints.take(4)
    )
}
