package com.streamvault.feature.live.sports

import com.streamvault.domain.sports.provider.SportsProviderReleasePolicy
import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.sports.provider.ProviderCompetitor
import com.streamvault.domain.sports.provider.ProviderStandingColumn
import com.streamvault.domain.sports.provider.ProviderStandingGroup
import com.streamvault.domain.sports.provider.ProviderStandingRow
import com.streamvault.domain.sports.provider.ProviderLeague
import com.streamvault.domain.sports.provider.ProviderStandings
import com.streamvault.domain.sports.provider.ProviderStandingsRequest
import com.streamvault.domain.sports.provider.SportsCapability
import com.streamvault.domain.sports.provider.SportsProvider
import com.streamvault.domain.sports.provider.SportsProviderAuth
import com.streamvault.domain.sports.provider.SportsProviderConfig
import com.streamvault.domain.sports.provider.SportsProviderDescriptor
import com.streamvault.domain.sports.provider.SportsProviderError
import com.streamvault.domain.sports.provider.SportsProviderFactory
import com.streamvault.domain.sports.provider.SportsProviderRegistry
import com.streamvault.domain.sports.provider.SportsProviderResult
import com.streamvault.domain.sports.provider.SportsSourceRef
import com.streamvault.domain.sports.provider.SportsStandingsSourcePreference
import com.streamvault.domain.sports.provider.StandingsProvider
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SportsProviderStandingsRouterTest {
    @Test
    fun `automatic prefers compatible league identity and explicit source stays isolated`() {
        val primary = TestFactory("primary")
        val sameType = TestFactory("api")
        val registry = SportsProviderRegistry(listOf(primary, sameType), SportsProviderReleasePolicy(development = true))
        val router = SportsProviderStandingsRouter(registry)
        val configs = listOf(
            config("primary-account", "primary", priority = 0),
            config("same-type-account", "api", priority = 5),
        )
        val eventLeagueSource = SportsSourceRef.Provider("events", "api", "event-1")

        val automatic = router.eligibleProviders(
            configs,
            SportsStandingsSourcePreference.AUTOMATIC,
            eventLeagueSource,
        )
        val selected = router.eligibleProviders(
            configs,
            SportsStandingsSourcePreference.provider("primary-account"),
            eventLeagueSource,
        )

        assertThat(automatic.map(SportsProviderConfig::instanceId))
            .containsExactly("same-type-account", "primary-account").inOrder()
        assertThat(selected.map(SportsProviderConfig::instanceId)).containsExactly("primary-account")
        assertThat(router.eligibleProviders(configs, SportsStandingsSourcePreference.OFF, null)).isEmpty()
    }

    @Test
    fun `standings uses scoped league ID preserves dynamic columns and caches then returns stale data`() = runTest {
        val now = Instant.parse("2026-10-17T12:00:00Z")
        var response: SportsProviderResult<ProviderStandings> = SportsProviderResult.Success(
            ProviderStandings(
                league = ProviderLeague("Premier League", "pl-id", "football"),
                season = "2026/27",
                groups = listOf(
                    ProviderStandingGroup(
                        name = "Conference",
                        columns = listOf(ProviderStandingColumn("wins", "W"), ProviderStandingColumn("points", "Pts")),
                        rows = listOf(
                            ProviderStandingRow(
                                competitor = ProviderCompetitor("Club A"),
                                rank = 1,
                                values = mapOf("wins" to "8", "points" to "24"),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val factory = TestFactory("standings", response = { response })
        val router = SportsProviderStandingsRouter(SportsProviderRegistry(listOf(factory), SportsProviderReleasePolicy(development = true)))
        val config = config("standings-account", "standings", leagueIds = listOf("pl-id"))
        val providerSource = SportsSourceRef.Provider("events-account", "another-api", "event-1")
        val providers = router.eligibleProviders(
            listOf(config), SportsStandingsSourcePreference.AUTOMATIC, providerSource,
        )

        val first = router.load(providers, ProviderLeague("Premier League"), providerSource, now)
        val cached = router.load(providers, ProviderLeague("Premier League"), providerSource, now.plusSeconds(60))
        response = SportsProviderResult.Failure(SportsProviderError.RATE_LIMITED)
        val stale = router.load(providers, ProviderLeague("Premier League"), providerSource, now.plusSeconds(1_900))

        assertThat(factory.requestedLeagueIds).containsExactly("pl-id", "pl-id").inOrder()
        assertThat(first?.standings?.groups?.single()?.columns?.map(ProviderStandingColumn::key))
            .containsExactly("wins", "points").inOrder()
        assertThat(cached?.isStale).isFalse()
        assertThat(stale?.isStale).isTrue()
        assertThat(stale?.providerName).isEqualTo("Standings API")
    }

    @Test
    fun `league ID from another provider is never sent without the selected account scope`() = runTest {
        val factory = TestFactory("standings")
        val router = SportsProviderStandingsRouter(SportsProviderRegistry(listOf(factory), SportsProviderReleasePolicy(development = true)))
        val config = config("standings-account", "standings")
        val providers = router.eligibleProviders(
            listOf(config),
            SportsStandingsSourcePreference.AUTOMATIC,
            SportsSourceRef.Provider("event-account", "events", "event-1"),
        )

        val loaded = router.load(
            providers,
            ProviderLeague("Premier League", "private-id"),
            SportsSourceRef.Provider("event-account", "events", "event-1"),
        )

        assertThat(loaded).isNull()
        assertThat(factory.requestedLeagueIds).isEmpty()
    }

    private fun config(
        id: String,
        type: String,
        priority: Int = 0,
        leagueIds: List<String> = emptyList(),
    ) = SportsProviderConfig(
        instanceId = id,
        typeId = type,
        priority = priority,
        eventLeagueIds = leagueIds,
    )

    private class TestFactory(
        private val type: String,
        private val response: suspend (ProviderStandingsRequest) -> SportsProviderResult<ProviderStandings> = {
            SportsProviderResult.Success(ProviderStandings(ProviderLeague("Premier League", it.leagueId), emptyList()))
        },
    ) : SportsProviderFactory {
        override val descriptor = SportsProviderDescriptor(
            typeId = type,
            capabilities = setOf(SportsCapability.STANDINGS),
            auth = SportsProviderAuth.None,
            displayName = "Standings API",
        )
        val requestedLeagueIds = mutableListOf<String>()

        override fun create(config: SportsProviderConfig): SportsProvider = object : StandingsProvider {
            override val descriptor = this@TestFactory.descriptor
            override val instanceId = config.instanceId

            override suspend fun standings(request: ProviderStandingsRequest): SportsProviderResult<ProviderStandings> {
                requestedLeagueIds += request.leagueId
                return response(request)
            }
        }
    }
}
