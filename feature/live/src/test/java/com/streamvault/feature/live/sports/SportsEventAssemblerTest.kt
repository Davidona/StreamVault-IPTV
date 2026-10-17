package com.streamvault.feature.live.sports

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.Program
import org.junit.runner.RunWith
import org.junit.Test
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class SportsEventAssemblerTest {
    @Test
    fun mergesEquivalentBroadcastsAndRetainsEachPlayableChannel() {
        val start = 1_800_000_000_000L
        val candidates = listOf(
            candidate("Barcelona vs Real Madrid", channelId = 1L, startTime = start),
            candidate("FC Barcelona - Real Madrid CF", channelId = 2L, startTime = start + 5 * MINUTE),
            candidate("LaLiga: Barcelona v Real Madrid", channelId = 3L, startTime = start + 10 * MINUTE),
            candidate("Barcelona vs Real Madrid", channelId = 1L, startTime = start + MINUTE),
        )

        val events = SportsEventAssembler.assemble(candidates)

        assertThat(events).hasSize(1)
        assertThat(events.single().broadcasts.map { it.channel.id })
            .containsExactly(1L, 2L, 3L)
        assertThat(events.single().startTime).isEqualTo(start)
        assertThat(events.single().endTime).isEqualTo(start + 100 * MINUTE)
        assertThat(events.single().competitors.map(SportsCompetitor::canonicalName))
            .containsExactly("barcelona", "real madrid")
        assertThat(events.single().rawProgrammeRefs).hasSize(4)
    }

    @Test
    fun keepsDifferentMatchupsAndEventsOutsideTheTimeToleranceSeparate() {
        val start = 1_800_000_000_000L
        val candidates = listOf(
            candidate("Barcelona vs Real Madrid", channelId = 1L, startTime = start),
            candidate("Arsenal vs Liverpool", channelId = 2L, startTime = start),
            candidate(
                "Barcelona vs Real Madrid",
                channelId = 3L,
                startTime = start + SportsEventAssembler.DEFAULT_START_TOLERANCE_MS + 1,
            ),
        )

        val events = SportsEventAssembler.assemble(candidates)

        assertThat(events).hasSize(3)
        assertThat(events.map(SportsEvent::startTime)).isInOrder()
    }

    @Test
    fun mergesMultilingualTeamAliasesButKeepsDifferentLeaguesSeparate() {
        val start = 1_800_000_000_000L
        val aliases = SportsEventAssembler.assemble(
            listOf(
                candidate("Bayern München vs Paris Saint-Germain", 1L, start),
                candidate("PSG @ Bayern Munich", 2L, start + 5 * MINUTE),
            ),
        )
        val differentLeagues = SportsEventAssembler.assemble(
            listOf(
                candidate("Premier League: Arsenal vs Liverpool", 3L, start),
                candidate("UEFA Champions League: Arsenal vs Liverpool", 4L, start + MINUTE),
            ),
        )

        assertThat(aliases).hasSize(1)
        assertThat(aliases.single().broadcasts).hasSize(2)
        assertThat(aliases.single().competitors.map(SportsCompetitor::canonicalName))
            .containsExactly("bayern munich", "paris saint germain")
        assertThat(differentLeagues).hasSize(2)
        assertThat(differentLeagues.map(SportsEvent::id).distinct()).hasSize(2)
    }

    @Test
    fun mergesLeagueAliasesByCanonicalLeagueIdentityAndKeepsTheRegistryMetadata() {
        val start = 1_800_000_000_000L
        val events = SportsEventAssembler.assemble(listOf(
            candidate("English Premier League: Arsenal vs Liverpool", 1L, start),
            candidate("EPL: Arsenal vs Liverpool", 2L, start + MINUTE),
        ))

        assertThat(events).hasSize(1)
        assertThat(events.single().leagueInfo?.id).isEqualTo("premier-league")
        assertThat(events.single().leagueInfo?.sport).isEqualTo(SportType.FOOTBALL)
        assertThat(events.single().leagueInfo?.aliases)
            .containsAtLeast("english premier league", "premier league", "epl")
        assertThat(events.single().broadcasts).hasSize(2)
    }

    @Test
    fun keepsNonTeamSessionsAndContentTypesAsSeparateEvents() {
        val start = 1_800_000_000_000L
        val qualifying = candidate(
            "Formula 1 Japanese Grand Prix Qualifying",
            channelId = 1L,
            startTime = start,
            genre = null,
        )
        val race = candidate(
            "Formula 1 Japanese Grand Prix Race",
            channelId = 2L,
            startTime = start + MINUTE,
            genre = null,
        )
        val replayClassification = qualifying.classification.copy(eventType = SportsEventType.REPLAY)
        val replay = qualifying.copy(
            channel = channel(3L),
            classification = replayClassification,
            eventIdentity = SportsProgrammeClassifier.eventIdentity(replayClassification, "replay"),
        )
        val events = SportsEventAssembler.assemble(listOf(qualifying, race, replay))

        assertThat(events).hasSize(3)
        assertThat(events.map(SportsEvent::session)).containsExactly("Qualifying", "Qualifying", "Race")
        assertThat(events.map(SportsEvent::eventType))
            .containsExactly(
                SportsEventType.SCHEDULED_EVENT,
                SportsEventType.REPLAY,
                SportsEventType.SCHEDULED_EVENT,
            )
    }

    @Test
    fun appliesConfidenceAndNonTeamTimeToleranceBeforeMerging() {
        val start = 1_800_000_000_000L
        val confident = candidate("Golf: Buick LPGA Shanghai: Day 2", 1L, start, genre = null)
        val twentyMinutesLater = candidate(
            "Golf: Buick LPGA Shanghai: Day 2",
            2L,
            start + 20 * MINUTE,
            genre = null,
        )
        val fortyMinutesLater = candidate(
            "Golf: Buick LPGA Shanghai: Day 2",
            3L,
            start + 40 * MINUTE,
            genre = null,
        )
        val lowConfidence = candidate("Golf: Buick LPGA Shanghai: Day 2", 4L, start + 2 * MINUTE, genre = null)
            .let { it.copy(classification = it.classification.copy(confidence = 0.4f)) }

        val events = SportsEventAssembler.assemble(
            listOf(confident, twentyMinutesLater, fortyMinutesLater, lowConfidence),
        )

        assertThat(events).hasSize(3)
        assertThat(events.map { it.broadcasts.size }).containsExactly(2, 1, 1)
        assertThat(events.first().session).isEqualTo("Day 2")
    }

    @Test
    fun keepsDifferentGolfTournamentsWithTheSameRoundSeparate() {
        val start = 1_800_000_000_000L
        val shanghai = candidate("Golf: Buick LPGA Shanghai: Day 2", 1L, start, genre = null)
        val evian = candidate("Golf: Evian Championship: Day 2", 2L, start + MINUTE, genre = null)

        val events = SportsEventAssembler.assemble(listOf(shanghai, evian))

        assertThat(events).hasSize(2)
        assertThat(events.map(SportsEvent::session)).containsExactly("Day 2", "Day 2")
        assertThat(events.map(SportsEvent::id).distinct()).hasSize(2)
    }

    @Test
    fun keepsSameTeamsPlayingLaterInTheDayAsSeparateEvents() {
        val start = 1_800_000_000_000L
        val events = SportsEventAssembler.assemble(
            listOf(
                candidate("Barcelona vs Real Madrid", 1L, start),
                candidate("Real Madrid vs Barcelona", 2L, start + 3 * 60 * MINUTE),
            ),
        )

        assertThat(events).hasSize(2)
        assertThat(events.map(SportsEvent::startTime)).containsExactly(start, start + 3 * 60 * MINUTE).inOrder()
    }

    private fun candidate(
        title: String,
        channelId: Long,
        startTime: Long,
        genre: String? = "Football",
    ): SportsEventCandidate {
        val program = Program(
            id = channelId * 100 + startTime / MINUTE,
            channelId = "epg-$channelId",
            title = title,
            description = "Description for $title",
            startTime = startTime,
            endTime = startTime + 90 * MINUTE,
            genre = genre,
        )
        val channel = Channel(
            id = channelId,
            name = "Sports $channelId",
            streamUrl = "test://sports/$channelId",
            providerId = 1L,
        )
        val classification = SportsProgrammeClassifier.classify(program, channel)
        return SportsEventCandidate(
            program = program,
            channel = channel,
            combinedProfileId = null,
            classification = classification,
        )
    }

    private fun channel(channelId: Long): Channel = Channel(
        id = channelId,
        name = "Sports $channelId",
        streamUrl = "test://sports/$channelId",
        providerId = 1L,
    )

    private companion object {
        const val MINUTE = 60_000L
    }
}
