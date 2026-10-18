package com.streamvault.feature.live.sports

import java.time.Duration
import java.time.Instant

internal val SportType.discoveryKey: String
    get() = if (this == SportType.UNKNOWN) SportType.OTHER.name else name

/** Some guides send escaped line breaks as literal text. Keep those out of the UI. */
internal fun sportsDescriptionForPresentation(description: String): String = description
    .replace("\\r\\n", "\n")
    .replace("\\n", "\n")
    .replace("\\r", "\n")
    .replace("\r\n", "\n")
    .trim()

internal fun SportsEvent.matchesDiscovery(state: SportsHubUiState): Boolean {
    if (sport.discoveryKey in state.excludedSports || leagueFilterKey in state.excludedLeagues) return false
    if (!matches(state.selectedSportFilter)) return false
    if (state.selectedLeagueFilter != null && leagueFilterKey != state.selectedLeagueFilter) return false
    val query = SportsTextNormalizer.normalize(state.searchQuery).split(' ').filter(String::isNotBlank)
    if (query.isEmpty()) return true
    val text = SportsTextNormalizer.normalize(buildList {
        add(title)
        league?.let(::add)
        competitors.forEach { add(it.displayName); add(it.canonicalName) }
        provenance.score?.value?.scores?.forEach { add(it.competitor.name) }
        provenance.teamMetadata?.value?.forEach { add(it.team.name) }
    }.joinToString(" "))
    return query.all(text::contains)
}

/** Keep recent enrichment when a refresh has only partial data, without keeping old fixtures. */
internal fun retainSportsPresentation(event: SportsEvent, previous: SportsEvent?, now: Instant): SportsEvent {
    if (previous == null || previous.id != event.id || previous.sport != event.sport) return event
    if (Duration.ofMillis(event.startTime - previous.startTime).abs() > Duration.ofHours(2)) return event
    val identities = event.competitors.map { it.canonicalName }.toSet()
    if (identities.isEmpty() || identities != previous.competitors.map { it.canonicalName }.toSet()) return event
    val oldScore = previous.provenance.score?.takeIf { score ->
        score.updatedAt?.let { !it.isBefore(now.minus(Duration.ofMinutes(10))) } == true
    }
    val score = event.provenance.score ?: oldScore?.copy(isStale = true)
    val status = event.provenance.status ?: previous.provenance.status?.takeIf { oldScore != null }
    return event.copy(
        competitors = event.competitors.map { competitor ->
            competitor.copy(logoUrl = competitor.logoUrl ?: previous.competitors
                .firstOrNull { it.canonicalName == competitor.canonicalName }?.logoUrl)
        },
        provenance = event.provenance.copy(
            sources = event.provenance.sources + listOfNotNull(score?.source, status?.source),
            score = score,
            status = status,
        ),
    )
}
