package com.streamvault.feature.live.sports

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.sports.provider.EventProvider
import com.streamvault.domain.sports.provider.LeagueMetadataProvider
import com.streamvault.domain.sports.provider.ProviderBatch
import com.streamvault.domain.sports.provider.ProviderCompetitor
import com.streamvault.domain.sports.provider.ProviderEvent
import com.streamvault.domain.sports.provider.ProviderEventRequest
import com.streamvault.domain.sports.provider.ProviderLeague
import com.streamvault.domain.sports.provider.ProviderLeagueMetadata
import com.streamvault.domain.sports.provider.ProviderLeagueMetadataRequest
import com.streamvault.domain.sports.provider.ProviderTeamMetadata
import com.streamvault.domain.sports.provider.ProviderTeamMetadataRequest
import com.streamvault.domain.sports.provider.SportsCapability
import com.streamvault.domain.sports.provider.SportsEventProvenance
import com.streamvault.domain.sports.provider.SportsEventTiming
import com.streamvault.domain.sports.provider.SportsMetadataSourcePreference
import com.streamvault.domain.sports.provider.SportsProvider
import com.streamvault.domain.sports.provider.SportsProviderAuth
import com.streamvault.domain.sports.provider.SportsProviderConfig
import com.streamvault.domain.sports.provider.SportsProviderDescriptor
import com.streamvault.domain.sports.provider.SportsProviderError
import com.streamvault.domain.sports.provider.SportsProviderFactory
import com.streamvault.domain.sports.provider.SportsProviderRegistry
import com.streamvault.domain.sports.provider.SportsProviderResult
import com.streamvault.domain.sports.provider.SportsSourceRef
import com.streamvault.domain.sports.provider.TeamMetadataProvider
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SportsProviderMetadataRouterTest {
    @Test
    fun `selected metadata provider enriches a matched provider event with sourced data`() = runTest {
        val factory = TestFactory("generic-api")
        val router = SportsProviderMetadataRouter(SportsProviderRegistry(listOf(factory)))
        val providerEvent = providerEvent()
        val catalog = LoadedSportsProviderEvents("event-account", "generic-api", listOf(providerEvent))
        val config = SportsProviderConfig(instanceId = "metadata-account", typeId = "generic-api")
        val loaded = router.load(
            configs = listOf(config),
            preference = SportsMetadataSourcePreference.provider("metadata-account"),
            eventCatalogs = listOf(catalog),
            eventProviderInstanceId = "event-account",
            now = Instant.parse("2026-10-17T12:00:00Z"),
        )
        val eventSource = SportsSourceRef.Provider("event-account", "generic-api", "match-1")
        val event = SportsEvent(
            id = "local-1",
            title = "Club A vs Club B",
            league = "Premier League",
            categoryLabel = null,
            description = "",
            sport = SportType.FOOTBALL,
            startTime = 1_792_244_400_000,
            endTime = 1_792_248_000_000,
            broadcasts = emptyList(),
            confidence = 0.9f,
            competitors = listOf(SportsCompetitor("Club A"), SportsCompetitor("Club B")),
            session = null,
            eventType = SportsEventType.SCHEDULED_EVENT,
            rawProgrammeRefs = emptyList(),
            provenance = SportsEventProvenance(
                sources = setOf(eventSource),
                timing = SportsEventTiming(eventStart = Instant.ofEpochMilli(1_792_244_400_000)),
                league = com.streamvault.domain.sports.provider.SourcedValue(
                    ProviderLeague("Premier League", "league-1"), eventSource,
                ),
            ),
        )

        val enriched = router.enrichEvents(listOf(event), loaded).single()

        assertThat(factory.teamRequests).containsExactly("team-a", "team-b").inOrder()
        assertThat(factory.leagueRequests).containsExactly("league-1")
        assertThat(enriched.metadataAttributionName).isEqualTo("Generic Sports API")
        assertThat(enriched.provenance.teamMetadata?.value?.map { it.shortName })
            .containsExactly("A", "B").inOrder()
        assertThat(enriched.provenance.leagueMetadata?.value?.country).isEqualTo("England")
        assertThat(enriched.provenance.teamMetadata?.source)
            .isEqualTo(SportsSourceRef.Provider("metadata-account", "generic-api", "match-1"))
    }

    @Test
    fun `metadata requests are cached for a day and stale values survive a provider failure`() = runTest {
        val now = Instant.parse("2026-10-17T12:00:00Z")
        val factory = TestFactory("cached-api")
        val router = SportsProviderMetadataRouter(SportsProviderRegistry(listOf(factory)))
        val config = SportsProviderConfig(instanceId = "account", typeId = "cached-api")
        val catalogs = listOf(LoadedSportsProviderEvents("account", "cached-api", listOf(providerEvent())))
        val preference = SportsMetadataSourcePreference.AUTOMATIC

        val fresh = router.load(listOf(config), preference, catalogs, "account", now)
        val cached = router.load(listOf(config), preference, catalogs, "account", now.plusSeconds(60))
        factory.failRequests = true
        val stale = router.load(listOf(config), preference, catalogs, "account", now.plusSeconds(90_000))

        assertThat(factory.teamRequests).containsExactly("team-a", "team-b", "team-a").inOrder()
        assertThat(fresh.teams.getValue("team-a").isStale).isFalse()
        assertThat(cached.teams.getValue("team-a").isStale).isFalse()
        assertThat(stale.teams.getValue("team-a").isStale).isTrue()
        assertThat(stale.leagues.getValue("league-1").isStale).isTrue()
    }

    @Test
    fun `metadata source is independent and does not cross provider ID namespaces`() = runTest {
        val factory = TestFactory("metadata-api")
        val router = SportsProviderMetadataRouter(SportsProviderRegistry(listOf(factory)))
        val config = SportsProviderConfig(instanceId = "account", typeId = "metadata-api")

        val off = router.load(
            listOf(config), SportsMetadataSourcePreference.OFF,
            listOf(LoadedSportsProviderEvents("events", "events-api", listOf(providerEvent()))), "events",
        )
        val mismatch = router.load(
            listOf(config), SportsMetadataSourcePreference.AUTOMATIC,
            listOf(LoadedSportsProviderEvents("events", "events-api", listOf(providerEvent()))), "events",
        )

        assertThat(off.instanceId).isNull()
        assertThat(mismatch.instanceId).isNull()
        assertThat(factory.teamRequests).isEmpty()
    }

    private fun providerEvent() = ProviderEvent(
        providerEventId = "match-1",
        sport = "football",
        league = ProviderLeague("Premier League", "league-1", "football"),
        competitors = listOf(
            ProviderCompetitor("Club A", "team-a"),
            ProviderCompetitor("Club B", "team-b"),
        ),
    )

    private class TestFactory(private val type: String) : SportsProviderFactory {
        override val descriptor = SportsProviderDescriptor(
            typeId = type,
            capabilities = setOf(
                SportsCapability.EVENTS,
                SportsCapability.TEAM_METADATA,
                SportsCapability.LEAGUE_METADATA,
            ),
            auth = SportsProviderAuth.None,
            displayName = "Generic Sports API",
        )
        val teamRequests = mutableListOf<String>()
        val leagueRequests = mutableListOf<String>()
        var failRequests = false

        override fun create(config: SportsProviderConfig): SportsProvider = object :
            EventProvider, TeamMetadataProvider, LeagueMetadataProvider {
            override val descriptor = this@TestFactory.descriptor
            override val instanceId = config.instanceId

            override suspend fun events(request: ProviderEventRequest): SportsProviderResult<ProviderBatch<ProviderEvent>> =
                SportsProviderResult.Success(ProviderBatch(emptyList()))

            override suspend fun teamMetadata(request: ProviderTeamMetadataRequest): SportsProviderResult<ProviderTeamMetadata> {
                teamRequests += request.teamId
                if (failRequests) return SportsProviderResult.Failure(SportsProviderError.NETWORK)
                val short = if (request.teamId == "team-a") "A" else "B"
                return SportsProviderResult.Success(
                    ProviderTeamMetadata(ProviderCompetitor("Club ${short}", request.teamId), shortName = short),
                )
            }

            override suspend fun leagueMetadata(request: ProviderLeagueMetadataRequest): SportsProviderResult<ProviderLeagueMetadata> {
                leagueRequests += request.leagueId
                if (failRequests) return SportsProviderResult.Failure(SportsProviderError.NETWORK)
                return SportsProviderResult.Success(
                    ProviderLeagueMetadata(ProviderLeague("Premier League", request.leagueId), country = "England"),
                )
            }
        }
    }
}
