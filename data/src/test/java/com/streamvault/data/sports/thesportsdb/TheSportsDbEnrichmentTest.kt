package com.streamvault.data.sports.thesportsdb

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.sports.provider.SportsProviderHttpClient
import com.streamvault.domain.sports.provider.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class TheSportsDbEnrichmentTest {
    private val server = MockWebServer()
    private var secret = "private-premium-marker"
    private val config = SportsProviderConfig("premium", "thesportsdb")
    private val store = object : SportsProviderStore {
        override suspend fun configurations() = listOf(config)
        override suspend fun credentials(instanceId: String) = SportsProviderCredentials(secret)
        override suspend fun save(config: SportsProviderConfig, credentials: SportsProviderCredentials?) = Unit
        override suspend fun remove(instanceId: String) = Unit
    }
    private val client = SportsProviderHttpClient("test")
    private val cache = TheSportsDbResponseCache()
    private val factory = TheSportsDbProviderFactory(client, store, cache)
    private fun provider(revision: Long = 0) = TheSportsDbProvider(
        config.copy(cacheRevision = revision), factory.descriptor, store, client, cache,
        endpointBaseUrl = server.url("/v2").toString(), allowInsecureHttp = true,
        clock = Clock.fixed(Instant.parse("2026-10-17T18:00:00Z"), ZoneOffset.UTC),
        standingsBaseUrl = server.url("/v1").toString(),
    )
    @Before fun start() = server.start()
    @After fun stop() = server.shutdown()

    @Test fun `production registration requires working attribution and never enables artwork`() {
        assertThat(SportsProviderRegistry(listOf(factory)).descriptors).isEmpty()
        val registry = SportsProviderRegistry(listOf(factory), SportsProviderReleasePolicy(
            attributionSupportedTypes = setOf("thesportsdb")))
        assertThat(registry.create(config)).isInstanceOf(BroadcastProvider::class.java)
        assertThat(registry.capabilities(config)).containsAtLeast(SportsCapability.STANDINGS,
            SportsCapability.TEAM_METADATA, SportsCapability.LEAGUE_METADATA)
        assertThat(registry.capabilities(config)).doesNotContain(SportsCapability.ARTWORK)
        assertThat(factory.descriptor.compliance?.requiresPaidAccount).isTrue()
    }

    @Test fun `TV mapping deduplicates station country pairs rejects other events and discards image fields`() = runTest {
        enqueueFixture("thesportsdb_tv.json")
        val result = provider().broadcasters(ProviderEventDetailRequest("2070217")) as SportsProviderResult.Success
        assertThat(result.value.items.map { it.name }).containsExactly("Sky Sports Main Event HD",
            "Sky Sports Main Event HD", "Peacock", "Unknown Station")
        assertThat(result.value.items.map { it.country }).containsExactly("United Kingdom", "Ireland", "United States", null)
        assertThat(result.value.rejectedItemCount).isEqualTo(2)
        assertThat(result.value.toString()).doesNotContain("https://")
        val request = server.takeRequest()
        assertThat(request.path).isEqualTo("/v2/lookup/event_tv/2070217")
        assertThat(request.getHeader("X-API-KEY")).isEqualTo(secret)
    }

    @Test fun `TV empty malformed and all invalid envelopes stay distinct`() = runTest {
        for ((body, success) in listOf("""{"lookup":null}""" to true,
            """{"lookup":{}}""" to false, "{}" to false,
            """{"lookup":[{"strChannel":"Wrong event","idEvent":"999"}]}""" to false)) {
            server.enqueue(MockResponse().setBody(body))
            val result = provider(server.requestCount.toLong()).broadcasters(ProviderEventDetailRequest("2070217"))
            assertThat(result is SportsProviderResult.Success).isEqualTo(success)
        }
    }

    @Test fun `TV caches separate edited accounts and changed keys`() = runTest {
        repeat(3) { server.enqueue(MockResponse().setBody("""{"lookup":[]}""")) }
        val adapter = provider()
        adapter.broadcasters(ProviderEventDetailRequest("2070217"))
        adapter.broadcasters(ProviderEventDetailRequest("2070217"))
        provider(1).broadcasters(ProviderEventDetailRequest("2070217"))
        secret = "new-private-marker"
        adapter.broadcasters(ProviderEventDetailRequest("2070217"))
        assertThat(server.requestCount).isEqualTo(3)
    }

    @Test fun `standings use documented V1 only with Premium key and preserve league season and numeric columns`() = runTest {
        enqueuePremiumAccess()
        enqueueFixture("thesportsdb_standings.json")
        val adapter = provider()
        val request = ProviderStandingsRequest("4328", "2026-2027")
        val result = adapter.standings(request) as SportsProviderResult.Success
        assertThat(result.value.league.id).isEqualTo("4328")
        assertThat(result.value.season).isEqualTo("2026-2027")
        assertThat(result.value.groups.single().rows.map { it.rank }).containsExactly(1, 2).inOrder()
        assertThat(result.value.groups.single().rows.first().values["intPoints"]).isEqualTo("24")
        assertThat(result.value.toString()).doesNotContain(secret)
        assertThat(result.value.toString()).doesNotContain("https://")
        assertThat(server.takeRequest().path).isEqualTo("/v2/all/sports")
        val httpRequest = server.takeRequest()
        assertThat(httpRequest.path).isEqualTo("/v1/$secret/lookuptable.php?l=4328&s=2026-2027")
        assertThat(httpRequest.getHeader("X-API-KEY")).isNull()
        assertThat(adapter.standings(request)).isEqualTo(result)
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test fun `standings empty malformed and mismatched league or season responses are handled safely`() = runTest {
        enqueuePremiumAccess()
        server.enqueue(MockResponse().setBody("""{"table":null}"""))
        assertThat((provider().standings(ProviderStandingsRequest("4328")) as SportsProviderResult.Success)
            .value.groups).isEmpty()
        for (body in listOf("{}", """{"table":{}}""",
            """{"table":[{"strTeam":"Team","idLeague":"999","strSeason":"2026-2027"}]}""",
            """{"table":[{"strTeam":"Team","idLeague":"4328","strSeason":"2025-2026"}]}""")) {
            enqueuePremiumAccess()
            server.enqueue(MockResponse().setBody(body))
            val result = provider(server.requestCount.toLong()).standings(ProviderStandingsRequest("4328", "2026-2027"))
            assertThat(result).isEqualTo(SportsProviderResult.Failure(SportsProviderError.INVALID_RESPONSE))
        }
    }

    @Test fun `new endpoints return sanitized auth quota and server failures`() = runTest {
        enqueuePremiumAccess()
        for ((code, category) in listOf(401 to SportsProviderError.AUTHENTICATION,
            403 to SportsProviderError.AUTHENTICATION, 429 to SportsProviderError.RATE_LIMITED,
            503 to SportsProviderError.SERVER)) {
            server.enqueue(MockResponse().setResponseCode(code).setHeader("Retry-After", "60").setBody(secret))
            val result = provider().standings(ProviderStandingsRequest("4328")) as SportsProviderResult.Failure
            assertThat(result.category).isEqualTo(category)
            assertThat(result.toString()).doesNotContain(secret)
            assertThat(result.retryAt).isNotNull()
        }
    }

    @Test fun `a key denied Premium V2 access can never use the V1 standings endpoint`() = runTest {
        secret = "another-free-development-key"
        server.enqueue(MockResponse().setResponseCode(403).setBody(secret))
        val result = provider().standings(ProviderStandingsRequest("4328"))
        assertThat(result).isEqualTo(SportsProviderResult.Failure(SportsProviderError.AUTHENTICATION, 403))
        assertThat(server.requestCount).isEqualTo(1)
        val request = server.takeRequest()
        assertThat(request.path).isEqualTo("/v2/all/sports")
        assertThat(request.path).doesNotContain(secret)
    }

    @Test fun `team and league lookup validate identity and exclude artwork`() = runTest {
        server.enqueue(MockResponse().setBody("""{"lookup":[{"idTeam":"133604","strTeam":"Arsenal","strTeamShort":"ARS","strCountry":"England","strBadge":"https://image.invalid/logo"}]}"""))
        server.enqueue(MockResponse().setBody("""{"lookup":[{"idLeague":"4328","strLeague":"English Premier League","strSport":"Soccer","strCountry":"England","strCurrentSeason":"2026-2027"}]}"""))
        val adapter = provider()
        val team = adapter.teamMetadata(ProviderTeamMetadataRequest("133604")) as SportsProviderResult.Success
        val league = adapter.leagueMetadata(ProviderLeagueMetadataRequest("4328")) as SportsProviderResult.Success
        assertThat(team.value.shortName).isEqualTo("ARS")
        assertThat(team.value.toString()).doesNotContain("https://")
        assertThat(league.value.season).isEqualTo("2026-2027")
        server.enqueue(MockResponse().setBody("""{"lookup":[{"idTeam":"999","strTeam":"Other"}]}"""))
        assertThat(adapter.teamMetadata(ProviderTeamMetadataRequest("133599"))).isEqualTo(
            SportsProviderResult.Failure(SportsProviderError.INVALID_RESPONSE))
        adapter.teamMetadata(ProviderTeamMetadataRequest("133604"))
        assertThat(server.requestCount).isEqualTo(3)
    }

    @Test fun `shared free key and malformed IDs never reach the network`() = runTest {
        secret = "123"
        val adapter = provider()
        assertThat(adapter.testConnection(SportsProviderCredentials(secret))).isEqualTo(
            SportsProviderResult.Failure(SportsProviderError.INVALID_CONFIGURATION))
        assertThat(adapter.standings(ProviderStandingsRequest("4328"))).isInstanceOf(SportsProviderResult.Failure::class.java)
        assertThat(adapter.broadcasters(ProviderEventDetailRequest("../lookup"))).isInstanceOf(SportsProviderResult.Failure::class.java)
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test fun `connection fails when V2 key lacks a required endpoint`() = runTest {
        server.enqueue(MockResponse().setBody("""{"sports":[]}"""))
        server.enqueue(MockResponse().setBody("""{"schedule":[]}"""))
        server.enqueue(MockResponse().setResponseCode(403).setBody(secret))
        val result = provider().testConnection(SportsProviderCredentials(secret))
        assertThat(result).isEqualTo(SportsProviderResult.Failure(SportsProviderError.AUTHENTICATION, 403))
        assertThat(server.requestCount).isEqualTo(3)
    }

    @Test fun `schedule preserves unknown league IDs missing competitors and postponed cancelled final states`() = runTest {
        server.enqueue(MockResponse().setBody("""{"list":[{"strSeason":"2026-2027"}]}"""))
        enqueueFixture("thesportsdb_event_states.json")
        val result = provider().events(ProviderEventRequest(now(), now().plusSeconds(86400), leagueId = "4328"))
            as SportsProviderResult.Success
        assertThat(result.value.items.map { it.status?.state }).containsExactly(ProviderGameState.SCHEDULED,
            ProviderGameState.LIVE, ProviderGameState.FINAL, ProviderGameState.POSTPONED,
            ProviderGameState.CANCELLED).inOrder()
        assertThat(result.value.items.first().league?.id).isEqualTo("4328")
        assertThat(result.value.items.first().league?.name).isNull()
        assertThat(result.value.items.first().competitors).isEmpty()
    }

    private fun now() = Instant.parse("2026-10-17T00:00:00Z")

    private fun enqueuePremiumAccess() {
        server.enqueue(MockResponse().setBody("""{"sports":[]}"""))
    }

    private fun enqueueFixture(name: String) {
        val body = requireNotNull(javaClass.classLoader?.getResource("sports/providers/$name")).readText()
        server.enqueue(MockResponse().setBody(body))
    }
}
