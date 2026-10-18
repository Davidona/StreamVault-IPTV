package com.streamvault.domain.sports.provider

import java.time.Instant

/** Half-open competition-time interval [from, until), not an EPG coverage query. */
data class ProviderEventRequest(
    val from: Instant,
    val until: Instant,
    val sport: String? = null,
    val leagueId: String? = null,
) {
    init { require(from.isBefore(until)) }
}

/** Identifies a provider event's sport/league path when detail APIs require scoped URLs. */
data class ProviderEventScope(val sport: String? = null, val leagueId: String? = null)

/** IDs belong to the receiving provider instance. Adapters own API batch/pagination limits. */
data class ProviderScoreRequest(
    val eventIds: Set<String>,
    val eventScopes: Map<String, ProviderEventScope> = emptyMap(),
) {
    init { require(eventIds.isNotEmpty() && eventIds.none { it.isBlank() }) }
}

data class ProviderEventDetailRequest(
    val eventId: String,
    val sport: String? = null,
    val leagueId: String? = null,
) {
    init { require(eventId.isNotBlank()) }
}

data class ProviderStandingsRequest(
    val leagueId: String,
    val season: String? = null,
    val sport: String? = null,
    val leagueName: String? = null,
) {
    init { require(leagueId.isNotBlank()) }
}

data class ProviderTeamMetadataRequest(
    val teamId: String,
    val sport: String? = null,
    val leagueId: String? = null,
) {
    init { require(teamId.isNotBlank()) }
}

data class ProviderLeagueMetadataRequest(
    val leagueId: String,
    val sport: String? = null,
) {
    init { require(leagueId.isNotBlank()) }
}

/**
 * Adapters return provider-neutral DTOs and typed, secret-safe failures. They own
 * pagination, stable external IDs, UTC conversion, and provider status mapping.
 * Cancellation must propagate. A malformed record must not discard valid peers.
 */
interface EventProvider : SportsProvider {
    suspend fun events(request: ProviderEventRequest): SportsProviderResult<ProviderBatch<ProviderEvent>>
}

interface LiveScoreProvider : SportsProvider {
    suspend fun liveScores(request: ProviderScoreRequest): SportsProviderResult<ProviderBatch<ProviderScore>>
}

/** Allows status-only adapters without pretending they supply scores. */
interface GameStatusProvider : SportsProvider {
    suspend fun gameStatuses(request: ProviderScoreRequest): SportsProviderResult<ProviderBatch<ProviderEventStatus>>
}

interface StandingsProvider : SportsProvider {
    suspend fun standings(request: ProviderStandingsRequest): SportsProviderResult<ProviderStandings>
}

interface BroadcastProvider : SportsProvider {
    suspend fun broadcasters(request: ProviderEventDetailRequest): SportsProviderResult<ProviderBatch<ProviderBroadcaster>>
}

interface TeamMetadataProvider : SportsProvider {
    suspend fun teamMetadata(request: ProviderTeamMetadataRequest): SportsProviderResult<ProviderTeamMetadata>
}

interface LeagueMetadataProvider : SportsProvider {
    suspend fun leagueMetadata(request: ProviderLeagueMetadataRequest): SportsProviderResult<ProviderLeagueMetadata>
}

interface ArtworkProvider : SportsProvider {
    suspend fun artwork(request: ProviderEntityRef): SportsProviderResult<ProviderBatch<ProviderArtwork>>
}

interface LineupProvider : SportsProvider {
    suspend fun lineups(request: ProviderEventDetailRequest): SportsProviderResult<ProviderBatch<ProviderLineup>>
}

interface StatisticsProvider : SportsProvider {
    suspend fun statistics(request: ProviderEventDetailRequest): SportsProviderResult<ProviderEventStatistics>
}

/** Optional, bounded health probe used by provider settings before an account is enabled. */
interface SportsProviderConnectionTester : SportsProvider {
    suspend fun testConnection(credentials: SportsProviderCredentials?): SportsProviderResult<Unit>
}

/** Latest account-level usage metadata observed by an adapter, when its API exposes it. */
data class SportsProviderUsageSnapshot(
    val requestLimit: Int? = null,
    val requestsRemaining: Int? = null,
    val planTier: String? = null,
    val observedAt: Instant? = null,
    val resetAt: Instant? = null,
)

/** Optional, read-only usage reporting; adapters must never expose credentials or response bodies. */
interface SportsProviderUsageProvider : SportsProvider {
    fun usageSnapshot(credentials: SportsProviderCredentials?): SportsProviderUsageSnapshot?
}

/** Declared capabilities may be a subset of implemented ones (for example an account tier). */
fun SportsProvider.validateCapabilities() {
    val implemented = buildSet {
        if (this@validateCapabilities is EventProvider) add(SportsCapability.EVENTS)
        if (this@validateCapabilities is LiveScoreProvider) add(SportsCapability.LIVE_SCORES)
        if (this@validateCapabilities is GameStatusProvider) add(SportsCapability.GAME_STATUS)
        if (this@validateCapabilities is StandingsProvider) add(SportsCapability.STANDINGS)
        if (this@validateCapabilities is BroadcastProvider) add(SportsCapability.BROADCASTERS)
        if (this@validateCapabilities is TeamMetadataProvider) add(SportsCapability.TEAM_METADATA)
        if (this@validateCapabilities is LeagueMetadataProvider) add(SportsCapability.LEAGUE_METADATA)
        if (this@validateCapabilities is ArtworkProvider) add(SportsCapability.ARTWORK)
        if (this@validateCapabilities is LineupProvider) add(SportsCapability.LINEUPS)
        if (this@validateCapabilities is StatisticsProvider) add(SportsCapability.STATISTICS)
    }
    require(implemented.containsAll(descriptor.capabilities))
}
