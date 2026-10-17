package com.streamvault.domain.sports.provider

import java.time.Instant

/** Identifies a programme or provider event without confusing external IDs with StreamVault IDs. */
sealed interface SportsSourceRef {
    data class Epg(
        val tvProviderId: Long,
        val channelId: String,
        val programmeId: Long,
    ) : SportsSourceRef {
        init { require(channelId.isNotBlank()) }
    }

    data class Provider(
        val instanceId: String,
        val typeId: String,
        val externalEventId: String,
    ) : SportsSourceRef {
        init {
            require(instanceId.isNotBlank() && typeId.isNotBlank() && externalEventId.isNotBlank())
        }
    }
}

data class SourcedValue<T>(
    val value: T,
    val source: SportsSourceRef,
    val updatedAt: Instant? = null,
    /** True when the value was retained from the last successful fetch after a refresh failed. */
    val isStale: Boolean = false,
)

/** Competition times and the local guide's coverage window have separate meanings. */
data class SportsEventTiming(
    val eventStart: Instant? = null,
    val eventEnd: Instant? = null,
    val broadcastStart: Instant? = null,
    val broadcastEnd: Instant? = null,
) {
    init {
        require(eventStart == null || eventEnd == null || !eventEnd.isBefore(eventStart))
        require(broadcastStart == null || broadcastEnd == null || !broadcastEnd.isBefore(broadcastStart))
    }
}

/** Optional fields retain the source used during a deterministic merge. */
data class SportsEventProvenance(
    val sources: Set<SportsSourceRef> = emptySet(),
    val timing: SportsEventTiming = SportsEventTiming(),
    val title: SourcedValue<String>? = null,
    val description: SourcedValue<String>? = null,
    val league: SourcedValue<ProviderLeague>? = null,
    val score: SourcedValue<ProviderScore>? = null,
    val status: SourcedValue<ProviderGameStatus>? = null,
    val teamMetadata: SourcedValue<List<ProviderTeamMetadata>>? = null,
    val leagueMetadata: SourcedValue<ProviderLeagueMetadata>? = null,
    val artwork: SourcedValue<List<ProviderArtwork>>? = null,
    val broadcasters: SourcedValue<List<ProviderBroadcaster>>? = null,
)

/** Selection is per job; configuring an event source does not also select a score source. */
data class SportsEventSourceSelection(
    val eventInstanceId: String? = null,
    val scoreInstanceId: String? = null,
    val metadataInstanceId: String? = null,
)

enum class SportsEventField {
    EVENT_TITLE,
    DESCRIPTION,
    EVENT_START,
    EVENT_END,
    BROADCAST_WINDOW,
    LEAGUE,
    SCORE,
    GAME_STATUS,
    TEAM_METADATA,
    LEAGUE_METADATA,
    ARTWORK,
    BROADCASTERS,
}

/**
 * A field is merged only from its declared source role. EPG remains authoritative for
 * descriptions and broadcast windows; provider kickoff, score and metadata need selection.
 */
object SportsEventMergePolicy {
    const val MIN_EVENT_IDENTITY_CONFIDENCE = 0.8f

    fun <T> select(
        field: SportsEventField,
        current: SourcedValue<T>?,
        candidate: SourcedValue<T>?,
        selection: SportsEventSourceSelection,
        eventMatchConfidence: Float,
    ): SourcedValue<T>? {
        if (candidate == null) return current
        val candidatePriority = priority(field, candidate.source, selection, eventMatchConfidence)
        if (candidatePriority == 0) return current
        if (current == null) return candidate
        val currentPriority = priority(field, current.source, selection, eventMatchConfidence)
        if (candidatePriority != currentPriority) {
            return if (candidatePriority > currentPriority) candidate else current
        }
        if (candidate.source == current.source && candidate.updatedAt == null) return current
        // Within one authoritative role, a timestamped newer value wins.
        val candidateTime = candidate.updatedAt
        val currentTime = current.updatedAt
        return if (candidateTime != null && (currentTime == null || candidateTime.isAfter(currentTime))) candidate else current
    }

    private fun priority(
        field: SportsEventField,
        source: SportsSourceRef,
        selection: SportsEventSourceSelection,
        eventMatchConfidence: Float,
    ): Int {
        val provider = source as? SportsSourceRef.Provider
        val isEpg = source is SportsSourceRef.Epg
        return when (field) {
            SportsEventField.DESCRIPTION, SportsEventField.BROADCAST_WINDOW -> if (isEpg) 2 else 0
            SportsEventField.EVENT_TITLE, SportsEventField.EVENT_START,
            SportsEventField.EVENT_END, SportsEventField.LEAGUE -> when {
                provider?.instanceId == selection.eventInstanceId &&
                    eventMatchConfidence >= MIN_EVENT_IDENTITY_CONFIDENCE -> 2
                isEpg -> 1
                else -> 0
            }
            SportsEventField.SCORE -> if (provider?.instanceId == selection.scoreInstanceId) 2 else 0
            SportsEventField.GAME_STATUS -> when {
                provider?.instanceId == selection.scoreInstanceId -> 3
                selection.scoreInstanceId == null && provider?.instanceId == selection.eventInstanceId -> 2
                else -> 0
            }
            SportsEventField.TEAM_METADATA, SportsEventField.LEAGUE_METADATA,
            SportsEventField.ARTWORK, SportsEventField.BROADCASTERS -> when {
                provider?.instanceId == selection.metadataInstanceId -> 2
                isEpg -> 1
                else -> 0
            }
        }
    }
}

/** Stable association is account-scoped and records the canonical local identity it matched. */
data class ProviderEventLink(
    val providerInstanceId: String,
    val providerTypeId: String,
    val providerEventId: String,
    val localEventId: String,
    val localEventFingerprint: String,
    val confidence: Float,
    val linkedAt: Instant,
    val eventStart: Instant? = null,
    val consecutiveFailures: Int = 0,
) {
    init {
        require(providerInstanceId.isNotBlank() && providerTypeId.isNotBlank())
        require(providerEventId.isNotBlank() && localEventId.isNotBlank() && localEventFingerprint.isNotBlank())
        require(confidence in 0f..1f && consecutiveFailures >= 0)
    }
}

interface SportsProviderEventLinkCache {
    suspend fun get(
        providerInstanceId: String,
        providerEventId: String,
        now: Instant,
        expectedLocalFingerprint: String? = null,
        providerEventStart: Instant? = null,
    ): ProviderEventLink?

    /** Implementations ignore below-threshold links. */
    suspend fun put(link: ProviderEventLink)

    /** Repeated failure eviction prevents a stale provider ID from being trusted indefinitely. */
    suspend fun recordFailure(providerInstanceId: String, providerEventId: String): Boolean

    suspend fun invalidate(providerInstanceId: String, providerEventId: String)
}
