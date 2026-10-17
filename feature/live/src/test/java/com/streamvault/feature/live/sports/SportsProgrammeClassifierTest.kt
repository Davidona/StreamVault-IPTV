package com.streamvault.feature.live.sports

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.Program
import org.junit.Test

class SportsProgrammeClassifierTest {
    @Test
    fun classifiesTeamAndNonTeamCompetitionsFromTitles() {
        val matchup = classify(
            title = "FC Barcelona - Real Madrid CF",
            genre = "Football",
        )
        assertThat(matchup.isSports).isTrue()
        assertThat(matchup.isCompetitiveEvent).isTrue()
        assertThat(matchup.sport).isEqualTo(SportType.FOOTBALL)
        assertThat(matchup.competitors).containsExactly("FC Barcelona", "Real Madrid CF").inOrder()

        val qualifying = classify("Formula 1 Japanese GP Qualifying")
        assertThat(qualifying.isSports).isTrue()
        assertThat(qualifying.isCompetitiveEvent).isTrue()
        assertThat(qualifying.sport).isEqualTo(SportType.MOTORSPORT)
        assertThat(qualifying.league).isEqualTo("Formula 1")
        assertThat(qualifying.leagueInfo?.id).isEqualTo("formula-1")
        assertThat(qualifying.competitors).isEmpty()

        val race = classify("Japanese Grand Prix - Race")
        assertThat(race.isCompetitiveEvent).isTrue()
        assertThat(race.sport).isEqualTo(SportType.MOTORSPORT)
        assertThat(race.competitors).isEmpty()

        val fight = classify("UFC 330 Main Card")
        assertThat(fight.isCompetitiveEvent).isTrue()
        assertThat(fight.sport).isEqualTo(SportType.MMA)
        assertThat(fight.league).isEqualTo("UFC")

        val tennis = classify("ATP Madrid: Alcaraz vs Sinner")
        assertThat(tennis.isCompetitiveEvent).isTrue()
        assertThat(tennis.sport).isEqualTo(SportType.TENNIS)
        assertThat(tennis.competitors).containsExactly("Alcaraz", "Sinner").inOrder()

        assertThat(classify("ATP Tour Madrid Finals").league).isEqualTo("ATP Tour")
    }

    @Test
    fun prefersTheLeagueNamedInTheProgrammeTitleOverAReferencedLeagueInDescription() {
        val classification = classify(
            title = "Burnley vs Wolverhampton Wanderers - EFL - English Football League SkyBet - Championship 2026/2027",
            description = "The English Football League Championship is the second-highest division after the Premier League.",
        )

        assertThat(classification.league).isEqualTo("EFL Championship")
        assertThat(classification.leagueInfo?.id).isEqualTo("efl-championship")
        assertThat(classification.leagueInfo?.sport).isEqualTo(SportType.FOOTBALL)
        assertThat(classification.competitors)
            .containsExactly("Burnley", "Wolverhampton Wanderers")
            .inOrder()
    }

    @Test
    fun classifiesEventTypesAndExtractsSportSpecificSessions() {
        val liveRace = classify("Live: Formula 1 Japanese Grand Prix Sprint Qualifying")
        val golfRound = classify("Golf: Buick LPGA Shanghai: Day 2")
        val tennisRound = classify("ATP Madrid: Quarterfinals")
        val fightCard = classify("UFC 330: Main Card")

        assertThat(liveRace.eventType).isEqualTo(SportsEventType.LIVE_EVENT)
        assertThat(liveRace.session).isEqualTo("Sprint Qualifying")
        assertThat(golfRound.sport).isEqualTo(SportType.GOLF)
        assertThat(golfRound.session).isEqualTo("Day 2")
        assertThat(golfRound.competitors).isEmpty()
        assertThat(tennisRound.eventType).isEqualTo(SportsEventType.SCHEDULED_EVENT)
        assertThat(tennisRound.session).isEqualTo("Quarterfinals")
        assertThat(tennisRound.competitors).isEmpty()
        assertThat(fightCard.sport).isEqualTo(SportType.MMA)
        assertThat(fightCard.session).isEqualTo("Main Card")
        assertThat(fightCard.competitors).isEmpty()
    }

    @Test
    fun distinguishesReplayHighlightAndStudioContentFromCompetition() {
        val replay = classify("Barcelona vs Real Madrid Replay", genre = "Football")
        val highlights = classify("Football Highlights", genre = "Sports")
        val preview = classify("Premier League Preview", genre = "Sports")
        val preGame = classify("Pregame Analysis", genre = "Football")
        val postGame = classify("Post Match Show", genre = "Football")
        val sportsNews = classify("Sports News Tonight", genre = "Sports")
        val catchUpAvailable = classify("Barcelona vs Real Madrid", genre = "Football", hasArchive = true)

        assertThat(replay.eventType).isEqualTo(SportsEventType.REPLAY)
        assertThat(replay.isCompetitiveEvent).isFalse()
        assertThat(highlights.eventType).isEqualTo(SportsEventType.HIGHLIGHTS)
        assertThat(preview.eventType).isEqualTo(SportsEventType.PREVIEW)
        assertThat(preGame.eventType).isEqualTo(SportsEventType.PRE_GAME)
        assertThat(postGame.eventType).isEqualTo(SportsEventType.POST_GAME)
        assertThat(sportsNews.eventType).isEqualTo(SportsEventType.SPORTS_NEWS)
        assertThat(catchUpAvailable.eventType).isEqualTo(SportsEventType.SCHEDULED_EVENT)
        assertThat(catchUpAvailable.isCompetitiveEvent).isTrue()
    }

    @Test
    fun detectsSportsFromDescriptionWithoutCategory() {
        val classification = classify(
            title = "Japanese Grand Prix Qualifying",
            description = "Formula One cars compete for pole position.",
            genre = null,
            category = null,
            channel = channel("International 1"),
        )

        assertThat(classification.isSports).isTrue()
        assertThat(classification.isCompetitiveEvent).isTrue()
        assertThat(classification.sport).isEqualTo(SportType.MOTORSPORT)
    }

    @Test
    fun rejectsSportsChannelBrandingAndPromotionalScheduleTitlesAsEvents() {
        val channelBrand = classify(
            title = "beIN SPORTS MAX",
            channel = channel("beIN SPORTS MAX 1 HD"),
        )
        val longPromotion = classify(
            title = "This is TNT Sports Ultimate",
            description = "Premier League, Champions League and Europa League coverage all season.",
            channel = channel("TNT Sports Ultimate 4K"),
        )
        val tourBranding = classify(
            title = "HotelPlanner Tour Series",
            channel = channel("Sky Sports 1 HD"),
        )
        val weeklyStudioShow = classify(
            title = "Bundesliga Weekly",
            channel = channel("Sky Sports Football"),
        )
        val leagueLabel = classify(
            title = "Copa Libertadores",
            channel = channel("beIN Sports 1"),
        )

        assertThat(channelBrand.isSports).isTrue()
        assertThat(channelBrand.isCompetitiveEvent).isFalse()
        assertThat(longPromotion.isSports).isTrue()
        assertThat(longPromotion.league).isEqualTo("Premier League")
        assertThat(longPromotion.isCompetitiveEvent).isFalse()
        assertThat(tourBranding.isSports).isTrue()
        assertThat(tourBranding.isCompetitiveEvent).isFalse()
        assertThat(weeklyStudioShow.isSports).isTrue()
        assertThat(weeklyStudioShow.isCompetitiveEvent).isFalse()
        assertThat(leagueLabel.isSports).isTrue()
        assertThat(leagueLabel.isCompetitiveEvent).isFalse()
    }

    @Test
    fun extractsSpacedXMatchupsAndRemovesTheRecognizedCompetitionHeader() {
        val classification = classify(
            title = "UEFA Europa League 2026-27 - SL Benfica x Celtic",
            channel = channel("SPORT TV 1 4K"),
        )

        assertThat(classification.isCompetitiveEvent).isTrue()
        assertThat(classification.sport).isEqualTo(SportType.FOOTBALL)
        assertThat(classification.league).isEqualTo("UEFA Europa League")
        assertThat(classification.competitors).containsExactly("SL Benfica", "Celtic").inOrder()
        assertThat(classification.displayTitle).isEqualTo("SL Benfica vs Celtic")
    }

    @Test
    fun preservesObservedNonTeamEventsWithoutInventingCompetitors() {
        val surfing = classify("World Surf League 2026 - Pro Portugal (Direto)")
        val fights = classify("One Friday Fights - 175")
        val padel = classify("Premier Padel - Milan P1 2026 - M3")
        val tennis = classify("Ténis - ATP Challenger Tour 2026 - ATP Ch 100 Maia Open (Direto)")

        listOf(surfing, fights, padel, tennis).forEach { classification ->
            assertThat(classification.isCompetitiveEvent).isTrue()
            assertThat(classification.competitors).isEmpty()
        }
        assertThat(surfing.sport).isEqualTo(SportType.OTHER)
        assertThat(surfing.league).isEqualTo("World Surf League")
        assertThat(fights.sport).isEqualTo(SportType.MMA)
        assertThat(tennis.sport).isEqualTo(SportType.TENNIS)
    }

    @Test
    fun recognizesInternationalMatchupSeparatorsAndCompactTennisSessions() {
        val spanishLeague = classify("Fudbal - Španska liga: Deportivo - Levante")
        val frenchLeague = classify("Fudbal - Francuska liga: Le Mans - Toulouse")
        val bundesligaRound = classify("Live Bundesliga: Eintracht Frankfurt-Koln Etapa 6")
        val tennisQuarterfinal = classify("ATP1000 '26: Shanghai Masters QF4", channel = channel("Events 4"))

        listOf(spanishLeague, frenchLeague, bundesligaRound, tennisQuarterfinal).forEach { classification ->
            check(classification.isCompetitiveEvent) {
                "Not classified as a competition: ${classification.displayTitle} (${classification.reasons})"
            }
        }
        assertThat(spanishLeague.sport).isEqualTo(SportType.FOOTBALL)
        assertThat(spanishLeague.competitors).containsExactly("Deportivo", "Levante").inOrder()
        assertThat(frenchLeague.competitors).containsExactly("Le Mans", "Toulouse").inOrder()
        assertThat(bundesligaRound.league).isEqualTo("Bundesliga")
        assertThat(bundesligaRound.competitors)
            .containsExactly("Eintracht Frankfurt", "Koln")
            .inOrder()
        assertThat(bundesligaRound.session).isEqualTo("Etapa 6")
        assertThat(tennisQuarterfinal.sport).isEqualTo(SportType.TENNIS)
        assertThat(tennisQuarterfinal.session).isEqualTo("Quarterfinal 4")
        assertThat(tennisQuarterfinal.competitors).isEmpty()
    }

    @Test
    fun excludesSportsNewsPreviewsReplaysAndStudioShows() {
        val titles = listOf(
            "SportsCenter",
            "Match of the Day",
            "Football Highlights",
            "Premier League Preview",
            "Postgame Analysis",
            "Sports Documentary",
            "Sports News Tonight",
        )

        titles.forEach { title ->
            val classification = classify(
                title = title,
                genre = "Sports",
                channel = channel("ESPN Sports"),
            )

            assertThat(classification.isSports).isTrue()
            assertThat(classification.isCompetitiveEvent).isFalse()
        }
    }

    @Test
    fun doesNotTreatNewsScheduleTimesAsMatchupCompetitors() {
        val classification = classify(
            title = "2 News Nevada vs 5:00p",
            description = "Local news, weather, traffic, and sports updates for Nevada.",
            category = "News",
            channel = channel("KTVN 2 News Nevada"),
        )

        assertThat(classification.isSports).isTrue()
        assertThat(classification.isCompetitiveEvent).isFalse()
        assertThat(classification.competitors).isEmpty()
    }

    @Test
    fun createsStableTeamEventIdentityAcrossAliasesAndOrdering() {
        val barcelonaFirst = classify("FC Barcelona vs Real Madrid CF", genre = "Football")
        val madridFirst = classify("Real Madrid vs Barça", genre = "Football")

        assertThat(
            SportsProgrammeClassifier.eventIdentity(barcelonaFirst, sourceKey = "provider:1"),
        ).isEqualTo(
            SportsProgrammeClassifier.eventIdentity(madridFirst, sourceKey = "provider:2"),
        )
    }

    private fun classify(
        title: String,
        description: String = "",
        genre: String? = null,
        category: String? = null,
        channel: Channel = channel("Sports Channel"),
        hasArchive: Boolean = false,
    ): SportsClassification = SportsProgrammeClassifier.classify(
        program = Program(
            channelId = "epg-channel",
            title = title,
            description = description,
            genre = genre,
            category = category,
            hasArchive = hasArchive,
        ),
        channel = channel,
    )

    private fun channel(name: String): Channel = Channel(
        id = name.hashCode().toLong(),
        name = name,
        streamUrl = "test://sports/$name",
        providerId = 1L,
    )
}
