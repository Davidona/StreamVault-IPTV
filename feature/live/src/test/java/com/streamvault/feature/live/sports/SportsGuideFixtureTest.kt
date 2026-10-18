package com.streamvault.feature.live.sports

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.parser.M3uParser
import com.streamvault.data.parser.XmltvParser
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.Program
import java.io.InputStream
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class SportsGuideFixtureTest {
    @Test
    fun parsesGoodXmltvFixtureAndClassifiesTheMatchedProgramme() {
        val programs = parseXmltv("good_sports_epg.xml")

        assertThat(programs).hasSize(3)
        val match = programs.single { it.title == "Barcelona vs Real Madrid" }
        assertThat(match.channelId).isEqualTo("sports-main")
        assertThat(match.category).isEqualTo("Football")
        assertThat(match.genre).isEqualTo("Football / Sports")

        val classification = SportsProgrammeClassifier.classify(
            match,
            channel(id = 1L, name = "beIN Sports 1", epgChannelId = match.channelId),
        )
        assertThat(classification.isCompetitiveEvent).isTrue()
        assertThat(classification.competitors).containsExactly("Barcelona", "Real Madrid").inOrder()

        val frenchBasketball = programs.single { it.channelId == "basketball-fr" }
        val basketballClassification = SportsProgrammeClassifier.classify(
            frenchBasketball,
            channel(id = 2L, name = "Sports Main", epgChannelId = frenchBasketball.channelId),
        )
        assertThat(basketballClassification.isCompetitiveEvent).isTrue()
        assertThat(basketballClassification.sport).isEqualTo(SportType.BASKETBALL)
        assertThat(basketballClassification.league).isEqualTo("LNB Pro A")
        assertThat(basketballClassification.competitors).containsExactly("Asvel", "Roanne").inOrder()
    }

    @Test
    fun detectsACompetitionInMinimalXmltvWithoutCategoryMetadata() {
        val program = parseXmltv("minimal_sports_epg.xml").single()
        assertThat(program.genre).isNull()
        assertThat(program.category).isNull()

        val classification = SportsProgrammeClassifier.classify(
            program,
            channel(id = 1L, name = "Events 1", epgChannelId = program.channelId),
        )

        assertThat(classification.isSports).isTrue()
        assertThat(classification.isCompetitiveEvent).isTrue()
        assertThat(classification.sport).isEqualTo(SportType.MOTORSPORT)
    }

    @Test
    fun keepsNewsPreviewReplayAndHighlightFixturesOutOfCompetitiveEvents() {
        val programs = parseXmltv("replays_and_highlights.xml")
        assertThat(programs).hasSize(4)

        programs.forEach { program ->
            val classification = SportsProgrammeClassifier.classify(
                program,
                channel(id = 1L, name = "ESPN Sports", epgChannelId = program.channelId),
            )
            assertThat(classification.isSports).isTrue()
            assertThat(classification.isCompetitiveEvent).isFalse()
        }
    }

    @Test
    fun keepsObservedProviderBrandAndLongPromotionOutOfCompetitiveEvents() {
        val programs = parseXmltv("sports_false_positives.xml")
        assertThat(programs).hasSize(9)

        val channelNames = mapOf(
            "tnt-ultimate" to "TNT Sports Ultimate 4K",
            "bein-max" to "beIN SPORTS MAX 1 HD",
            "sky-sport" to "Sky Sports 1 HD",
            "bundesliga-weekly" to "Sky Sports Football",
            "inside-serie-a" to "DAZN Sports",
            "league-label" to "Sports Channel",
            "canal-promo" to "Canal+ Sport",
            "generic-match" to "ESPN Sports",
            "local-news" to "KTVN 2 News Nevada",
        )
        programs.forEach { program ->
            val classification = SportsProgrammeClassifier.classify(
                program,
                channel(
                    id = program.channelId.hashCode().toLong(),
                    name = requireNotNull(channelNames[program.channelId]),
                    epgChannelId = program.channelId,
                ),
            )
            assertThat(classification.isSports).isTrue()
            assertThat(classification.isCompetitiveEvent).isFalse()
        }

        val newsSchedule = programs.single { it.channelId == "local-news" }
        val newsScheduleClassification = SportsProgrammeClassifier.classify(
            newsSchedule,
            channel(
                id = newsSchedule.channelId.hashCode().toLong(),
                name = "KTVN 2 News Nevada",
                epgChannelId = newsSchedule.channelId,
            ),
        )
        assertThat(newsScheduleClassification.competitors).isEmpty()
    }

    @Test
    fun extractsNonTeamSportsFromXmltvWithoutInventingCompetitors() {
        val programs = parseXmltv("non_team_sports.xml")

        assertThat(programs).hasSize(4)
        programs.forEach { program ->
            val classification = SportsProgrammeClassifier.classify(
                program,
                channel(id = 1L, name = "Sports Events", epgChannelId = program.channelId),
            )
            assertThat(classification.isCompetitiveEvent).isTrue()
            assertThat(classification.competitors).isEmpty()
        }

        val surfing = programs.single { it.title.startsWith("World Surf League") }
        val surfingClassification = SportsProgrammeClassifier.classify(
            surfing,
            channel(id = 2L, name = "Sports Events", epgChannelId = surfing.channelId),
        )
        assertThat(surfingClassification.sport).isEqualTo(SportType.OTHER)
        assertThat(surfingClassification.league).isEqualTo("World Surf League")
    }

    @Test
    fun recoversGuideObservedInternationalMatchupsAndCompactTennisSession() {
        val programs = parseXmltv("sports_real_guide_false_negatives.xml")
        val classifications = programs.associate { program ->
            val channelName = when (program.channelId) {
                "tennis-session" -> "Events 4"
                else -> "Sports Main"
            }
            program.channelId to SportsProgrammeClassifier.classify(
                program,
                channel(
                    id = program.channelId.hashCode().toLong(),
                    name = channelName,
                    epgChannelId = program.channelId,
                ),
            )
        }

        assertThat(classifications.values).hasSize(4)
        classifications.forEach { (channelId, classification) ->
            check(classification.isCompetitiveEvent) { "Not classified as a competition: $channelId" }
        }
        assertThat(classifications.getValue("spanish-fixture").competitors)
            .containsExactly("Deportivo", "Levante")
            .inOrder()
        assertThat(classifications.getValue("french-fixture").competitors)
            .containsExactly("Le Mans", "Toulouse")
            .inOrder()
        assertThat(classifications.getValue("bundesliga-fixture").competitors)
            .containsExactly("Eintracht Frankfurt", "Koln")
            .inOrder()
        assertThat(classifications.getValue("bundesliga-fixture").session).isEqualTo("Etapa 6")
        assertThat(classifications.getValue("tennis-session").sport).isEqualTo(SportType.TENNIS)
        assertThat(classifications.getValue("tennis-session").session).isEqualTo("Quarterfinal 4")
        assertThat(classifications.getValue("tennis-session").competitors).isEmpty()
    }

    @Test
    fun joinsM3uTvgIdsToXmltvProgramsAndMergesDuplicateBroadcasts() {
        val channels = M3uParser().parseToChannels(
            fixture("multichannel_sports.m3u"),
            providerId = 12L,
        )
        val programs = parseXmltv("multichannel_same_match.xml")
        val channelsByEpgId = channels.associateBy { it.epgChannelId }
        val candidates = programs.mapNotNull { program ->
            val matchedChannel = channelsByEpgId[program.channelId] ?: return@mapNotNull null
            val classification = SportsProgrammeClassifier.classify(program, matchedChannel)
            if (!classification.isCompetitiveEvent) return@mapNotNull null
            SportsEventCandidate(
                program = program,
                channel = matchedChannel,
                combinedProfileId = null,
                classification = classification,
            )
        }

        assertThat(channels.map { it.epgChannelId })
            .containsExactly("event-main", "event-alt")
        assertThat(candidates).hasSize(2)
        val events = SportsEventAssembler.assemble(candidates)
        assertThat(events).hasSize(1)
        assertThat(events.single().broadcasts.map { it.channel.id }).containsExactly(1L, 2L)
    }

    @Test
    fun doesNotInventResolutionForMissingPlaylistIdsOrUnresolvedProgrammeChannels() {
        val channels = M3uParser().parseToChannels(
            fixture("missing_tvg_id.m3u"),
            providerId = 12L,
        )
        val programs = parseXmltv("unresolved_epg_channel.xml")
        val channelsByEpgId = channels.mapNotNull { channel ->
            channel.epgChannelId?.let { epgId -> epgId to channel }
        }.toMap()
        val resolved = programs.mapNotNull { program ->
            channelsByEpgId[program.channelId]?.let { program to it }
        }

        assertThat(channels).hasSize(1)
        assertThat(channels.single().epgChannelId).isNull()
        assertThat(programs).hasSize(1)
        assertThat(programs.single().channelId).isEqualTo("not-in-playlist")
        assertThat(resolved).isEmpty()
    }

    private fun parseXmltv(name: String): List<Program> =
        XmltvParser().parse(fixture(name), timezoneId = "UTC")

    private fun fixture(name: String): InputStream =
        requireNotNull(javaClass.getResourceAsStream("/sports/$name")) {
            "Missing sports fixture: $name"
        }

    private fun channel(id: Long, name: String, epgChannelId: String): Channel = Channel(
        id = id,
        name = name,
        epgChannelId = epgChannelId,
        streamUrl = "test://sports/$id",
        providerId = 12L,
    )
}
