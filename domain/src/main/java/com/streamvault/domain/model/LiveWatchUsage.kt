package com.streamvault.domain.model

/** Physical stream receiving watch time, within a logical channel's viewing session. */
data class LiveWatchTarget(
    val providerId: Long,
    val contentId: Long,
    val streamId: Long,
    val logicalKey: String
) {
    init { require(providerId > 0 && contentId > 0 && streamId != 0L && logicalKey.isNotBlank()) }
}

/** Idempotent actual-playing-time increment; media duration and watchCount are never inputs. */
data class LiveWatchDelta(
    val target: LiveWatchTarget,
    val sessionId: String,
    val sequence: Long,
    val watchedMs: Long,
    val lastWatchedAtMs: Long,
    /** UTC epoch day containing the actual playback increment. */
    val watchedDayUtc: Long = Math.floorDiv(lastWatchedAtMs, 86_400_000L)
) {
    init { require(sessionId.isNotBlank() && sequence > 0 && watchedMs > 0) }
}

data class LiveWatchUsage(val lastWatchedAtMs: Long = 0, val watchedMs: Long = 0)

data class LiveWatchSample(
    val owner: String,
    val target: LiveWatchTarget?,
    val isPlaying: Boolean,
    val elapsedMs: Long,
    val wallTimeMs: Long
) {
    init { require(owner.isNotBlank() && (!isPlaying || target != null)) }
}

fun Channel.liveWatchTargetOrNull(): LiveWatchTarget? {
    val variant = currentVariant
    val source = variant?.providerId ?: providerId
    val rawId = variant?.rawChannelId ?: id
    val remoteId = variant?.streamId ?: streamId
    if (source <= 0 || rawId <= 0 || remoteId == 0L) return null
    val logical = (variant?.logicalGroupId ?: logicalGroupId).ifBlank { "stream:$remoteId" }
    return LiveWatchTarget(source, rawId, remoteId, logical)
}
