package com.streamvault.feature.live.sports

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.sports.provider.*
import java.time.Instant
import org.junit.Test

class SportsDiscoveryPresentationTest {
    @Test fun `guide descriptions show line breaks instead of escaped provider formatting`() {
        assertThat(sportsDescriptionForPresentation("  Match\\r\\nLeague\\nSeason\r\nVenue  "))
            .isEqualTo("Match\nLeague\nSeason\nVenue")
        assertThat(sportsDescriptionForPresentation("")).isEmpty()
        assertThat(sportsDescriptionForPresentation("Regular description")).isEqualTo("Regular description")
    }

    private val now = Instant.parse("2026-10-17T20:00:00Z")
    private val source = SportsSourceRef.Provider("account", "custom-json", "game")
    private val event = SportsEvent(
        id = "game", title = "Atlanta Dream at Golden State Valkyries", league = "WNBA", categoryLabel = null,
        description = "", sport = SportType.BASKETBALL, startTime = now.minusSeconds(1800).toEpochMilli(),
        endTime = now.plusSeconds(3600).toEpochMilli(), broadcasts = emptyList(), confidence = 1f,
        competitors = listOf(SportsCompetitor("Atlanta Dream"), SportsCompetitor("Golden State Valkyries")),
        session = null, eventType = SportsEventType.LIVE_EVENT, rawProgrammeRefs = emptyList(),
    )
    private val scored = event.copy(
        competitors = event.competitors.map { it.copy(logoUrl = "https://images.example/${it.canonicalName.replace(' ', '-')}.png") },
        provenance = event.provenance.copy(score = SourcedValue(
            ProviderScore("game", listOf(ProviderCompetitorScore(ProviderCompetitor("Atlanta Dream"), "39"))),
            source, updatedAt = now.minusSeconds(60),
        )),
    )

    @Test fun `partial refresh retains recent scores and logos without resetting their age`() {
        val refreshed = retainSportsPresentation(event, scored, now)
        assertThat(refreshed.provenance.score?.value).isEqualTo(scored.provenance.score?.value)
        assertThat(refreshed.provenance.score?.updatedAt).isEqualTo(now.minusSeconds(60))
        assertThat(refreshed.provenance.score?.isStale).isTrue()
        assertThat(refreshed.competitors.first().logoUrl).isNotNull()
    }

    @Test fun `retained scores expire instead of being kept alive by repeated refreshes`() {
        val refreshed = retainSportsPresentation(event, scored, now.plusSeconds(601))
        assertThat(refreshed.provenance.score).isNull()
    }

    @Test fun `new scores win and different teams never inherit old enrichment`() {
        val fresh = scored.copy(provenance = scored.provenance.copy(score = scored.provenance.score?.copy(updatedAt = now)))
        assertThat(retainSportsPresentation(fresh, scored, now).provenance.score).isEqualTo(fresh.provenance.score)
        assertThat(retainSportsPresentation(event.copy(competitors = listOf(SportsCompetitor("Other team"))), scored, now).provenance.score).isNull()
    }

    @Test fun `team and matchup search is case insensitive and works across competitor names`() {
        assertThat(event.matchesDiscovery(SportsHubUiState(searchQuery = "ATLANTA"))).isTrue()
        assertThat(event.matchesDiscovery(SportsHubUiState(searchQuery = "Dream Valkyries"))).isTrue()
        assertThat(event.matchesDiscovery(SportsHubUiState(searchQuery = "Lakers"))).isFalse()
    }

    @Test fun `sport and league exclusions apply together with search and quick filters`() {
        assertThat(event.matchesDiscovery(SportsHubUiState(excludedSports = setOf("BASKETBALL")))).isFalse()
        assertThat(event.matchesDiscovery(SportsHubUiState(excludedLeagues = setOf("wnba")))).isFalse()
        assertThat(event.matchesDiscovery(SportsHubUiState(excludedSports = setOf("FOOTBALL"), searchQuery = "Atlanta"))).isTrue()
        assertThat(event.matchesDiscovery(SportsHubUiState(selectedSportFilter = SportsSportFilter.FOOTBALL))).isFalse()
    }
}
