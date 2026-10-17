package com.streamvault.domain.sports.provider

import com.google.common.truth.Truth.assertThat
import java.net.URI
import java.time.Instant
import org.junit.Assert.assertThrows
import org.junit.Test

class SportsProviderModelsTest {
    private val kickoff = Instant.parse("2026-10-17T18:00:00Z")

    @Test fun `non team sessions and incomplete provider events remain representable`() {
        val race = ProviderEvent("race", sport = "motorsport", title = "Grand Prix", session = "Qualifying", eventStart = kickoff)
        assertThat(race.competitors).isEmpty()
        assertThat(race.session).isEqualTo("Qualifying")
        assertThat(ProviderEvent("unknown-time").eventStart).isNull()
        assertThat(race.broadcasters).isEmpty()
    }

    @Test fun `scores preserve missing values and sport specific text`() {
        for (score in listOf(null, "40", "6-4 3-6 7-5", "213/4", "-8", "Decision")) {
            val result = ProviderScore("event", listOf(ProviderCompetitorScore(ProviderCompetitor("Player"), score)))
            assertThat(result.scores.single().value).isEqualTo(score)
            assertThat(result.status).isNull()
        }
    }

    @Test fun `standings preserve sport specific groups columns ties and missing cells`() {
        val drivers = ProviderStandingGroup("Drivers", listOf(ProviderStandingColumn("points")), listOf(
            ProviderStandingRow(ProviderCompetitor("Driver A"), 1, mapOf("points" to "100")),
            ProviderStandingRow(ProviderCompetitor("Driver B"), 1, mapOf("points" to null)),
        ))
        val constructors = ProviderStandingGroup("Constructors", listOf(ProviderStandingColumn("wins")), emptyList())
        val standings = ProviderStandings(ProviderLeague("Formula 1"), listOf(drivers, constructors))
        assertThat(standings.groups[0].rows.map { it.rank }).containsExactly(1, 1)
        assertThat(standings.groups[1].columns.single().key).isEqualTo("wins")
    }

    @Test fun `invalid identity times ranges and standings schemas fail at the mapping boundary`() {
        val invalid = listOf<() -> Any>(
            { ProviderEvent("") },
            { ProviderScore(" ") },
            { ProviderEvent("a", eventStart = kickoff, eventEnd = kickoff.minusSeconds(1)) },
            { ProviderEventRequest(kickoff, kickoff) },
            { ProviderScoreRequest(emptySet()) },
            { ProviderEventDetailRequest("") },
            { ProviderCompetitor(" ") },
            { ProviderStandingGroup(null, listOf(ProviderStandingColumn("p"), ProviderStandingColumn("p")), emptyList()) },
            { ProviderStandingGroup(null, emptyList(), listOf(ProviderStandingRow(ProviderCompetitor("A"), values = mapOf("p" to "1")))) },
        )
        invalid.forEach { build -> assertThrows(IllegalArgumentException::class.java) { build() } }
    }

    @Test fun `artwork URLs remain typed and redacted and reject embedded credentials`() {
        val ref = ProviderEntityRef(ProviderEntityType.TEAM, "a")
        val artwork = ProviderArtwork(ref, URI("https://images.test/logo.png?token=private-key"), ProviderArtworkKind.LOGO)
        assertThat(artwork.toString()).doesNotContain("private-key")
        assertThrows(IllegalArgumentException::class.java) {
            ProviderArtwork(ref, URI("https://user:private-key@images.test/logo.png"), ProviderArtworkKind.LOGO)
        }
    }
}
