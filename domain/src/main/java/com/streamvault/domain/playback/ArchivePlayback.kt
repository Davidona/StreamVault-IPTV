package com.streamvault.domain.playback

import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.Program
import java.util.Locale

private const val MILLIS_PER_DAY = 86_400_000L
private const val XTREAM_INTERNAL_PREFIX = "xtream://"
private const val STALKER_INTERNAL_PREFIX = "stalker://"

enum class ArchiveReplayMechanism {
    XTREAM_STREAM_ID,
    STALKER_ARCHIVE_TOKEN,
    M3U_TEMPLATE,
    ADVERTISED_INCOMPLETE,
    NONE
}

data class ArchivePlaybackCapability(
    val mechanism: ArchiveReplayMechanism,
    val canBuildReplayCandidate: Boolean,
    val advertisedByProvider: Boolean,
    val windowDays: Int?
) {
    val hasKnownWindow: Boolean get() = windowDays != null && windowDays > 0
}

fun Channel.archivePlaybackCapability(): ArchivePlaybackCapability {
    val stream = streamUrl.trim().lowercase(Locale.ROOT)
    val source = catchUpSource?.trim().orEmpty()
    val normalizedSource = source.lowercase(Locale.ROOT)
    val normalizedType = catchUpType?.trim()?.lowercase(Locale.ROOT).orEmpty()
    val hasProviderContext = id > 0L && providerId > 0L
    val hasXtreamReplayContext = stream.startsWith(XTREAM_INTERNAL_PREFIX) && streamId > 0L
    val hasStalkerReplayContext =
        stream.startsWith(STALKER_INTERNAL_PREFIX) || normalizedSource.startsWith(STALKER_INTERNAL_PREFIX)
    val needsUnmappedCatchUpId = Regex("\\$?\\{catchup-id}", RegexOption.IGNORE_CASE).containsMatchIn(source)
    val hasM3uTemplate = source.isNotBlank() && !needsUnmappedCatchUpId &&
        !normalizedSource.startsWith(STALKER_INTERNAL_PREFIX)
    val needsConfiguredFallback = source.isBlank() && normalizedType in setOf("default", "append")
    val isFlussonicMode = normalizedType == "fs" || normalizedType.startsWith("flussonic")
    val hasM3uAutoMode = (
        normalizedType in setOf(
            "default", "append", "shift", "timeshift", "xc", "xc60", "xtream", "xtream-codes"
        ) || isFlussonicMode
    ) && !stream.startsWith(STALKER_INTERNAL_PREFIX) && !needsUnmappedCatchUpId &&
        (!needsConfiguredFallback || catchUpFallbackAvailable)
    val windowDays = catchUpDays.takeIf { it > 0 }

    return when {
        !hasProviderContext -> ArchivePlaybackCapability(
            mechanism = ArchiveReplayMechanism.NONE,
            canBuildReplayCandidate = false,
            advertisedByProvider = catchUpSupported,
            windowDays = windowDays
        )
        hasXtreamReplayContext -> ArchivePlaybackCapability(
            mechanism = ArchiveReplayMechanism.XTREAM_STREAM_ID,
            canBuildReplayCandidate = true,
            advertisedByProvider = catchUpSupported,
            windowDays = windowDays
        )
        hasStalkerReplayContext -> ArchivePlaybackCapability(
            mechanism = ArchiveReplayMechanism.STALKER_ARCHIVE_TOKEN,
            canBuildReplayCandidate = true,
            advertisedByProvider = catchUpSupported,
            windowDays = windowDays
        )
        hasM3uTemplate || hasM3uAutoMode -> ArchivePlaybackCapability(
            mechanism = ArchiveReplayMechanism.M3U_TEMPLATE,
            canBuildReplayCandidate = true,
            advertisedByProvider = catchUpSupported,
            windowDays = windowDays
        )
        catchUpSupported -> ArchivePlaybackCapability(
            mechanism = ArchiveReplayMechanism.ADVERTISED_INCOMPLETE,
            canBuildReplayCandidate = false,
            advertisedByProvider = true,
            windowDays = windowDays
        )
        else -> ArchivePlaybackCapability(
            mechanism = ArchiveReplayMechanism.NONE,
            canBuildReplayCandidate = false,
            advertisedByProvider = false,
            windowDays = windowDays
        )
    }
}

fun Channel.isArchivePlayable(
    program: Program,
    now: Long = System.currentTimeMillis()
): Boolean {
    if (id <= 0L || providerId <= 0L) return false
    if (program.startTime <= 0L || program.endTime <= program.startTime) return false
    val capability = archivePlaybackCapability()
    if (!capability.canBuildReplayCandidate) return false
    if (program.hasArchive) return true
    if (!catchUpSupported || program.endTime > now) return false
    val windowDays = capability.windowDays?.takeIf { it > 0 }
        ?: return capability.mechanism == ArchiveReplayMechanism.XTREAM_STREAM_ID ||
            capability.mechanism == ArchiveReplayMechanism.M3U_TEMPLATE
    val catchUpWindowStart = now - (windowDays.toLong() * MILLIS_PER_DAY)
    return program.startTime >= catchUpWindowStart
}
