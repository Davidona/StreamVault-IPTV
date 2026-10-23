package com.streamvault.feature.live.sports

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SportsMetadataRegistryTest {
    @Test
    fun normalizesUnicodeAndPunctuationForSearch() {
        assertThat(SportsTextNormalizer.normalize("Bayern München & Atlético"))
            .isEqualTo("bayern munchen atletico")
        assertThat(SportsTextNormalizer.normalize("F1: Japanese GP — Qualifying"))
            .isEqualTo("f1 japanese gp qualifying")
    }

    @Test
    fun mapsKnownTeamAliasesToCanonicalNames() {
        val aliases = listOf(
            "Manchester United" to listOf("Manchester United", "Man Utd", "MUFC"),
            "Barcelona" to listOf("FC Barcelona", "Barcelona", "Barça"),
            "Bayern Munich" to listOf("Bayern München", "Bayern Munich"),
            "Borussia Dortmund" to listOf("Borussia Dortmund", "BVB"),
        )

        aliases.forEach { (canonical, names) ->
            names.forEach { name ->
                assertThat(SportsAliasRegistry.normalizeTeamName(name))
                    .isEqualTo(canonical.lowercase())
            }
        }
    }

    @Test
    fun keepsDistinctTeamsSeparateWhenNamesAreSimilar() {
        assertThat(SportsAliasRegistry.normalizeTeamName("Inter Miami"))
            .isNotEqualTo(SportsAliasRegistry.normalizeTeamName("Inter Milan"))
        assertThat(SportsAliasRegistry.normalizeTeamName("Manchester United Fans"))
            .isNotEqualTo(SportsAliasRegistry.normalizeTeamName("Manchester United"))
    }

    @Test
    fun recognizesLeagueAliasesWithoutMatchingInsideLargerWords() {
        val leagueAliases = listOf(
            listOf("Premier League", "EPL") to "Premier League",
            listOf(
                "EFL Championship", "English Football League Championship", "Sky Bet Championship",
                "EFL English Football League SkyBet Championship",
            ) to "EFL Championship",
            listOf("Champions League", "UCL") to "UEFA Champions League",
            listOf("Formula 1", "Formula One", "F1") to "Formula 1",
            listOf("LNB Pro A") to "LNB Pro A",
        )

        leagueAliases.forEach { (aliases, displayName) ->
            aliases.forEach { alias ->
                val league = SportsLeagueRegistry.detect(alias)
                assertThat(league?.displayName).isEqualTo(displayName)
                assertThat(league?.id).isEqualTo(displayName.lowercase().replace(' ', '-'))
                assertThat(league?.aliases).contains(SportsTextNormalizer.normalize(alias))
            }
        }

        assertThat(SportsLeagueRegistry.detect("UCLUP"))
            .isNull()
        assertThat(SportsLeagueRegistry.detect("EFL"))
            .isNull()
        assertThat(SportsLeagueRegistry.detect("EFL Cup"))
            .isNull()
        assertThat(SportsLeagueRegistry.detect("EPL")?.id)
            .isEqualTo(SportsLeagueRegistry.detect("Premier League")?.id)
    }

    @Test
    fun detectsBrazilianCompetitionsBeforeShorterEuropeanAliases() {
        assertThat(SportsLeagueRegistry.detect("Brasileirão Série A")?.displayName)
            .isEqualTo("Brasileirão Série A")
        assertThat(SportsLeagueRegistry.detect("a Série A do futebol masculino brasileiro")?.displayName)
            .isEqualTo("Brasileirão Série A")
        assertThat(SportsLeagueRegistry.detect("Campeonato Brasileiro, Série B")?.displayName)
            .isEqualTo("Brasileirão Série B")
        assertThat(SportsLeagueRegistry.detect("Brasileirão Série B")?.displayName)
            .isEqualTo("Brasileirão Série B")
        assertThat(SportsLeagueRegistry.detect("Semifinal da Libertadores")?.displayName)
            .isEqualTo("Copa Libertadores")
        assertThat(SportsLeagueRegistry.detect("Copa Sul-Americana")?.displayName)
            .isEqualTo("Copa Sul-Americana")
        assertThat(SportsLeagueRegistry.detect("Serie A, primeira divisão da Itália")?.displayName)
            .isEqualTo("Serie A")
    }
}
