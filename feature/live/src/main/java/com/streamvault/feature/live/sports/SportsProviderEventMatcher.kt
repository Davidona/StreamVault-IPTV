package com.streamvault.feature.live.sports

import com.streamvault.domain.model.Channel
import com.streamvault.domain.sports.provider.ProviderBroadcaster
import com.streamvault.domain.sports.provider.ProviderCompetitor
import com.streamvault.domain.sports.provider.ProviderEvent
import com.streamvault.domain.sports.provider.ProviderLeague
import com.streamvault.domain.util.ChannelNormalizer
import java.time.Duration
import java.time.Instant

internal data class SportsProviderEventMatch(
    val confidence: Float,
    val signals: Set<Signal>,
) {
    internal enum class Signal {
        SPORT,
        COMPETITORS,
        TITLE,
        LEAGUE,
        TIME,
        SESSION,
        BROADCASTER,
        LOCAL_CONFIDENCE,
    }
}

/** Strict identity matching built on the existing Plan 2 aliases and event normalization. */
internal object SportsProviderEventMatcher {
    internal data class CandidateKey(
        val canonicalTitle: String? = null,
        val canonicalCompetitors: Set<String>? = null,
    )

    private val nonCompetitiveTypes = setOf(
        SportsEventType.REPLAY,
        SportsEventType.HIGHLIGHTS,
        SportsEventType.PREVIEW,
        SportsEventType.PRE_GAME,
        SportsEventType.POST_GAME,
        SportsEventType.SPORTS_NEWS,
        SportsEventType.SPORTS_SHOW,
    )
    private val kickoffGrace = Duration.ofMinutes(30)

    fun candidateKeys(providerEvent: ProviderEvent): Set<CandidateKey> = buildSet {
        val competitors = providerEvent.competitors.map(::canonicalCompetitor)
            .filter(String::isNotBlank)
            .toSet()
        if (competitors.isNotEmpty()) add(CandidateKey(canonicalCompetitors = competitors))
        providerEvent.title?.let(SportsProgrammeClassifier::canonicalEventTitle)
            ?.takeIf(String::isNotBlank)
            ?.let { add(CandidateKey(canonicalTitle = it)) }
    }

    fun candidateKeys(localEvent: SportsEvent): Set<CandidateKey> = buildSet {
        val competitors = localEvent.competitors.map(SportsCompetitor::canonicalName)
            .filter(String::isNotBlank)
            .toSet()
        if (competitors.isNotEmpty()) add(CandidateKey(canonicalCompetitors = competitors))
        SportsProgrammeClassifier.canonicalEventTitle(localEvent.title)
            .takeIf(String::isNotBlank)
            ?.let { add(CandidateKey(canonicalTitle = it)) }
    }

    fun match(providerEvent: ProviderEvent, localEvent: SportsEvent): SportsProviderEventMatch? {
        if (localEvent.eventType in nonCompetitiveTypes || localEvent.confidence < MIN_LOCAL_CONFIDENCE) return null

        val providerSport = SportsProgrammeClassifier.sportForProvider(
            providerEvent.sport ?: providerEvent.league?.sport,
            providerEvent.league?.name,
        )
        if (providerSport != SportType.UNKNOWN && localEvent.sport != SportType.UNKNOWN &&
            providerSport != localEvent.sport
        ) return null

        val providerCompetitors = providerEvent.competitors.map(::canonicalCompetitor).filter(String::isNotBlank).toSet()
        val localCompetitors = localEvent.competitors.map(SportsCompetitor::canonicalName)
            .filter(String::isNotBlank).toSet()
        val competitorsPresentOnBothSides = providerCompetitors.isNotEmpty() && localCompetitors.isNotEmpty()
        if (competitorsPresentOnBothSides && providerCompetitors != localCompetitors) return null

        val providerTitle = providerEvent.title?.let(SportsProgrammeClassifier::canonicalEventTitle).orEmpty()
        val localTitle = SportsProgrammeClassifier.canonicalEventTitle(localEvent.title)
        val exactTitle = providerTitle.isNotBlank() && providerTitle == localTitle &&
            !SportsProgrammeClassifier.isGenericEventTitle(providerTitle) &&
            !SportsProgrammeClassifier.isGenericEventTitle(localTitle)
        val exactCompetitors = competitorsPresentOnBothSides && providerCompetitors == localCompetitors
        if (!exactCompetitors && !exactTitle) return null

        val providerLeagueIdentity = leagueIdentity(providerEvent.league)
        val localLeagueIdentity = localEvent.leagueInfo?.id ?: leagueIdentityFromName(localEvent.league)
        if (providerLeagueIdentity != null && localLeagueIdentity != null &&
            providerLeagueIdentity != localLeagueIdentity
        ) return null

        val providerSession = providerEvent.session?.let(SportsTextNormalizer::normalize)
            ?.takeIf(String::isNotBlank)
            ?: providerEvent.title?.let { SportsEventParser.extractSession(it, providerSport) }
                ?.let(SportsTextNormalizer::normalize)?.takeIf(String::isNotBlank)
        val localSession = localEvent.session?.let(SportsTextNormalizer::normalize)?.takeIf(String::isNotBlank)
        if (providerSession != null && localSession != null && providerSession != localSession) return null

        val timeSignal = providerEvent.eventStart?.let { kickoffAgreement(it, localEvent) }
        if (timeSignal == TimeAgreement.OUTSIDE_WINDOW) return null
        // Team aliases alone are insufficient when a club has more than one fixture in the feed.
        if (exactCompetitors && timeSignal != TimeAgreement.INSIDE_WINDOW) return null

        val broadcasterSignal = providerEvent.broadcasters.any { providerBroadcaster ->
            localEvent.broadcasts.any { localBroadcast -> sameBroadcaster(providerBroadcaster, localBroadcast.channel) }
        }
        val signals = buildSet {
            if (providerSport != SportType.UNKNOWN && providerSport == localEvent.sport) add(SportsProviderEventMatch.Signal.SPORT)
            if (exactCompetitors) add(SportsProviderEventMatch.Signal.COMPETITORS)
            if (exactTitle) add(SportsProviderEventMatch.Signal.TITLE)
            if (providerLeagueIdentity != null && providerLeagueIdentity == localLeagueIdentity) {
                add(SportsProviderEventMatch.Signal.LEAGUE)
            }
            if (timeSignal == TimeAgreement.INSIDE_WINDOW) add(SportsProviderEventMatch.Signal.TIME)
            if (providerSession != null && providerSession == localSession) add(SportsProviderEventMatch.Signal.SESSION)
            if (broadcasterSignal) add(SportsProviderEventMatch.Signal.BROADCASTER)
            if (localEvent.confidence >= HIGH_LOCAL_CONFIDENCE) add(SportsProviderEventMatch.Signal.LOCAL_CONFIDENCE)
        }
        val confidence = signals.sumOf { signal -> signal.weight.toDouble() }.toFloat().coerceAtMost(1f)
        return SportsProviderEventMatch(confidence, signals)
    }

    private fun canonicalCompetitor(competitor: ProviderCompetitor): String =
        SportsAliasRegistry.normalizeTeamName(competitor.name)

    private fun leagueIdentity(league: ProviderLeague?): String? {
        league ?: return null
        val name = league.name?.takeIf(String::isNotBlank)
        val normalizedName = name?.let(SportsTextNormalizer::normalize).orEmpty()
        val registered = SportsLeagueRegistry.detectNormalized(normalizedName)
        return registered?.id ?: normalizedName.takeIf(String::isNotBlank)
    }

    private fun leagueIdentityFromName(name: String?): String? = name
        ?.takeIf(String::isNotBlank)
        ?.let(SportsTextNormalizer::normalize)
        ?.let { normalized -> SportsLeagueRegistry.detectNormalized(normalized)?.id ?: normalized }

    private fun kickoffAgreement(kickoff: Instant, event: SportsEvent): TimeAgreement {
        val timing = event.provenance.timing
        val start = timing.broadcastStart ?: Instant.ofEpochMilli(event.startTime)
        val end = timing.broadcastEnd ?: Instant.ofEpochMilli(event.endTime)
        val expandedStart = start.minus(kickoffGrace)
        val expandedEnd = end.plus(kickoffGrace)
        return if (!kickoff.isBefore(expandedStart) && !kickoff.isAfter(expandedEnd)) {
            TimeAgreement.INSIDE_WINDOW
        } else {
            TimeAgreement.OUTSIDE_WINDOW
        }
    }

    private fun sameBroadcaster(provider: ProviderBroadcaster, local: Channel): Boolean {
        val providerName = ChannelNormalizer.classify(provider.name, 0L).canonicalName
        val localName = ChannelNormalizer.classify(local.name, local.providerId).canonicalName
        return providerName.isNotBlank() && providerName == localName
    }

    private enum class TimeAgreement { INSIDE_WINDOW, OUTSIDE_WINDOW }

    private val SportsProviderEventMatch.Signal.weight: Float
        get() = when (this) {
            SportsProviderEventMatch.Signal.SPORT -> 0.08f
            SportsProviderEventMatch.Signal.COMPETITORS -> 0.54f
            SportsProviderEventMatch.Signal.TITLE -> 0.48f
            SportsProviderEventMatch.Signal.LEAGUE -> 0.16f
            SportsProviderEventMatch.Signal.TIME -> 0.24f
            SportsProviderEventMatch.Signal.SESSION -> 0.08f
            SportsProviderEventMatch.Signal.BROADCASTER -> 0.08f
            SportsProviderEventMatch.Signal.LOCAL_CONFIDENCE -> 0.04f
        }

    private const val MIN_LOCAL_CONFIDENCE = 0.5f
    private const val HIGH_LOCAL_CONFIDENCE = 0.85f
}
