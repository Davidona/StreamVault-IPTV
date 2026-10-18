package com.streamvault.feature.live.sports

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.sports.provider.*
import org.junit.Test

class SportsEventScoreTest {
    private val away = SportsCompetitor("Atlanta Dream")
    private val home = SportsCompetitor("Golden State Valkyries")
    private val event = SportsEvent(
        id = "match", title = "Atlanta at Golden State", league = "WNBA", categoryLabel = null,
        description = "", sport = SportType.BASKETBALL, startTime = 0, endTime = 3_600_000,
        broadcasts = emptyList(), confidence = 1f, competitors = listOf(away, home),
        session = null, eventType = SportsEventType.LIVE_EVENT, rawProgrammeRefs = emptyList(),
        provenance = SportsEventProvenance(score = SourcedValue(
            ProviderScore("match", scores = listOf(
                ProviderCompetitorScore(ProviderCompetitor(home.displayName, role = ProviderCompetitorRole.HOME), "40"),
                ProviderCompetitorScore(ProviderCompetitor(away.displayName, role = ProviderCompetitorRole.AWAY), "37"),
            )),
            SportsSourceRef.Provider("account", "provider", "match"),
        )),
    )

    @Test fun `scores follow team identity when the guide lists the away team first`() {
        assertThat(event.scoreFor(away)).isEqualTo("37")
        assertThat(event.scoreFor(home)).isEqualTo("40")
    }

    @Test fun `unrecognized team never inherits another teams score from row position`() {
        assertThat(event.scoreFor(SportsCompetitor("Unknown team"))).isNull()
    }
}
