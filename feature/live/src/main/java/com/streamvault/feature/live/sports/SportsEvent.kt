package com.streamvault.feature.live.sports

import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.Program
import com.streamvault.domain.sports.provider.SportsEventProvenance
import com.streamvault.domain.sports.provider.ProviderAttribution
import java.time.Instant

internal enum class SportType {
    FOOTBALL,
    BASKETBALL,
    AMERICAN_FOOTBALL,
    BASEBALL,
    HOCKEY,
    TENNIS,
    MOTORSPORT,
    MMA,
    BOXING,
    CRICKET,
    RUGBY,
    GOLF,
    VOLLEYBALL,
    HANDBALL,
    SNOOKER,
    DARTS,
    CYCLING,
    OTHER,
    UNKNOWN,
}

internal enum class SportsTimeRange {
    ON_NOW,
    TODAY,
    UPCOMING,
}

internal enum class SportsSportFilter {
    ALL,
    FOOTBALL,
    BASKETBALL,
    MOTORSPORT,
    OTHER,
}

internal enum class SportsClassificationReason {
    SPORTS_CATEGORY,
    SPORT_TERM,
    LEAGUE,
    SPORTS_CHANNEL,
    MATCHUP,
    COMPETITION_TERM,
    PROGRAMME_SHOW,
}

internal enum class SportsEventType {
    LIVE_EVENT,
    SCHEDULED_EVENT,
    REPLAY,
    HIGHLIGHTS,
    PREVIEW,
    PRE_GAME,
    POST_GAME,
    SPORTS_NEWS,
    SPORTS_SHOW,
    UNKNOWN,
}

internal data class SportsCompetitor(
    val displayName: String,
    val canonicalName: String = SportsAliasRegistry.normalizeTeamName(displayName),
    val logoUrl: String? = null,
)

internal data class SportsClassification(
    val isSports: Boolean,
    val isCompetitiveEvent: Boolean,
    val sport: SportType,
    val displayTitle: String,
    val competitors: List<String>,
    val league: String?,
    val categoryLabel: String?,
    val confidence: Float,
    val reasons: Set<SportsClassificationReason>,
    val eventType: SportsEventType = SportsEventType.UNKNOWN,
    val session: String? = null,
    val leagueInfo: SportsLeague? = null,
)

internal data class SportsEventCandidate(
    val program: Program,
    val channel: Channel,
    val combinedProfileId: Long?,
    val classification: SportsClassification,
    val eventIdentity: String = SportsProgrammeClassifier.eventIdentity(
        classification = classification,
        sourceKey = channel.providerId.toString() + ":" + channel.id,
    ),
)

internal data class SportsBroadcast(
    val channel: Channel,
    val combinedProfileId: Long?,
    val fallbackMatch: SportsChannelMatch? = null,
)

internal data class SportsEvent(
    val id: String,
    val title: String,
    val league: String?,
    val categoryLabel: String?,
    val description: String,
    val sport: SportType,
    val startTime: Long,
    val endTime: Long,
    val broadcasts: List<SportsBroadcast>,
    val confidence: Float,
    val competitors: List<SportsCompetitor>,
    val session: String?,
    val eventType: SportsEventType,
    val rawProgrammeRefs: List<Program>,
    val rememberedChannelKey: String? = null,
    val rememberedChannelWasAdded: Boolean = false,
    val leagueInfo: SportsLeague? = null,
    val metadataAttributionName: String? = null,
    val providerAttributions: List<ProviderAttribution> = emptyList(),
    val provenance: SportsEventProvenance = SportsEventProvenance(
        timing = com.streamvault.domain.sports.provider.SportsEventTiming(
            broadcastStart = Instant.ofEpochMilli(startTime),
            broadcastEnd = Instant.ofEpochMilli(endTime),
        ),
    ),
)

internal val SportsEvent.leagueFilterKey: String?
    get() = leagueInfo?.id ?: league?.let { SportsTextNormalizer.normalize(it).replace(' ', '-') }

/** EPG programme-window status, not independently verified match status. */
internal enum class SportsBroadcastStatus {
    UPCOMING,
    ON_AIR,
    ENDED,
}

internal fun SportsEvent.broadcastStatusAt(nowMillis: Long): SportsBroadcastStatus = when {
    nowMillis < startTime -> SportsBroadcastStatus.UPCOMING
    nowMillis < endTime -> SportsBroadcastStatus.ON_AIR
    else -> SportsBroadcastStatus.ENDED
}

internal fun SportsEvent.matches(filter: SportsSportFilter): Boolean = when (filter) {
    SportsSportFilter.ALL -> true
    SportsSportFilter.FOOTBALL -> sport == SportType.FOOTBALL
    SportsSportFilter.BASKETBALL -> sport == SportType.BASKETBALL
    SportsSportFilter.MOTORSPORT -> sport == SportType.MOTORSPORT
    SportsSportFilter.OTHER -> sport !in setOf(
        SportType.FOOTBALL,
        SportType.BASKETBALL,
        SportType.MOTORSPORT,
    )
}
