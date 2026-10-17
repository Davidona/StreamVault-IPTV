package com.streamvault.domain.sports.provider

import java.net.URI
import java.time.Instant

/** IDs in these DTOs are external IDs, scoped to SportsProvider.instanceId, never local IDs. */
data class ProviderLeague(val name: String?, val id: String? = null, val sport: String? = null)

enum class ProviderCompetitorKind { TEAM, PERSON, CONSTRUCTOR, OTHER, UNKNOWN }
enum class ProviderCompetitorRole { HOME, AWAY, PARTICIPANT }

/** Raw names are retained here; shared Plan 2 normalization belongs in the resolver. */
data class ProviderCompetitor(
    val name: String,
    val id: String? = null,
    val kind: ProviderCompetitorKind = ProviderCompetitorKind.UNKNOWN,
    val role: ProviderCompetitorRole = ProviderCompetitorRole.PARTICIPANT,
) {
    init { require(name.isNotBlank()) }
}

enum class ProviderGameState { SCHEDULED, LIVE, SUSPENDED, DELAYED, POSTPONED, CANCELLED, FINAL, UNKNOWN }

/** Unknown status is distinct from a provider that returned no status at all (null). */
data class ProviderGameStatus(
    val state: ProviderGameState,
    val clock: String? = null,
    val period: String? = null,
    val detail: String? = null,
    val updatedAt: Instant? = null,
)

enum class ProviderBroadcastType { LINEAR_TV, STREAMING_SERVICE, RADIO, UNKNOWN }

/** A matching signal only. Deliberately has no URL or local Channel reference. */
data class ProviderBroadcaster(
    val name: String,
    val country: String? = null,
    val language: String? = null,
    val type: ProviderBroadcastType = ProviderBroadcastType.UNKNOWN,
) {
    init { require(name.isNotBlank()) }
}

/** Event times are actual competition times, never EPG broadcast-window times. */
data class ProviderEvent(
    val providerEventId: String,
    val sport: String? = null,
    val league: ProviderLeague? = null,
    val title: String? = null,
    val competitors: List<ProviderCompetitor> = emptyList(),
    val eventStart: Instant? = null,
    val eventEnd: Instant? = null,
    val status: ProviderGameStatus? = null,
    val session: String? = null,
    val broadcasters: List<ProviderBroadcaster> = emptyList(),
) {
    init {
        require(providerEventId.isNotBlank())
        require(eventStart == null || eventEnd == null || !eventEnd.isBefore(eventStart))
    }
}

/** Strings accommodate tennis sets, cricket innings, golf, and other non-numeric results. */
data class ProviderCompetitorScore(val competitor: ProviderCompetitor, val value: String?)

data class ProviderScore(
    val providerEventId: String,
    val scores: List<ProviderCompetitorScore> = emptyList(),
    val status: ProviderGameStatus? = null,
    val updatedAt: Instant? = null,
) {
    init { require(providerEventId.isNotBlank()) }
}

data class ProviderEventStatus(val providerEventId: String, val status: ProviderGameStatus) {
    init { require(providerEventId.isNotBlank()) }
}

/** A column's provider key is stable; labels are provider content, not app UI labels. */
data class ProviderStandingColumn(val key: String, val label: String? = null) {
    init { require(key.isNotBlank()) }
}

data class ProviderStandingRow(
    val competitor: ProviderCompetitor,
    val rank: Int? = null,
    val values: Map<String, String?> = emptyMap(),
) {
    init { require(rank == null || rank > 0) }
}

/** Each group can have its own sport-specific columns (conference, drivers, constructors...). */
data class ProviderStandingGroup(
    val name: String?,
    val columns: List<ProviderStandingColumn>,
    val rows: List<ProviderStandingRow>,
) {
    init {
        val keys = columns.map { it.key }.toSet()
        require(keys.size == columns.size)
        require(rows.all { keys.containsAll(it.values.keys) })
    }
}

data class ProviderStandings(
    val league: ProviderLeague,
    val groups: List<ProviderStandingGroup>,
    val season: String? = null,
    val updatedAt: Instant? = null,
)

data class ProviderTeamMetadata(
    val team: ProviderCompetitor,
    val shortName: String? = null,
    val country: String? = null,
    val updatedAt: Instant? = null,
)

data class ProviderLeagueMetadata(
    val league: ProviderLeague,
    val country: String? = null,
    val season: String? = null,
    val updatedAt: Instant? = null,
)

enum class ProviderEntityType { EVENT, TEAM, LEAGUE, COMPETITOR }

data class ProviderEntityRef(val type: ProviderEntityType, val id: String) {
    init { require(id.isNotBlank()) }
}

enum class ProviderArtworkKind { LOGO, BADGE, POSTER, BACKGROUND, OTHER }

/** Describes artwork only. Loading/caching requires adapter rights/attribution review. */
data class ProviderArtwork(
    val entity: ProviderEntityRef,
    val uri: URI,
    val kind: ProviderArtworkKind,
) {
    init {
        require(uri.scheme == "https" && !uri.host.isNullOrBlank())
        require(uri.rawUserInfo == null && uri.rawFragment == null)
    }

    // Signed artwork URLs may contain tokens. Do not expose them in diagnostics.
    override fun toString(): String = "ProviderArtwork(entity=$entity, kind=$kind)"
}

data class ProviderLineupMember(
    val competitor: ProviderCompetitor,
    val position: String? = null,
    val shirtNumber: String? = null,
    val starter: Boolean? = null,
)

data class ProviderLineup(
    val providerEventId: String,
    val team: ProviderCompetitor?,
    val members: List<ProviderLineupMember>,
    val updatedAt: Instant? = null,
) {
    init { require(providerEventId.isNotBlank()) }
}

data class ProviderStatistic(
    val key: String,
    val value: String?,
    val competitor: ProviderCompetitor? = null,
    val label: String? = null,
    val period: String? = null,
) {
    init { require(key.isNotBlank()) }
}

data class ProviderEventStatistics(
    val providerEventId: String,
    val statistics: List<ProviderStatistic>,
    val updatedAt: Instant? = null,
) {
    init { require(providerEventId.isNotBlank()) }
}

/** A successful, possibly partial response; counts are safe to include in diagnostics. */
data class ProviderBatch<out T>(val items: List<T>, val rejectedItemCount: Int = 0) {
    init { require(rejectedItemCount >= 0) }
}
