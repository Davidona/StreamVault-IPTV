package com.streamvault.data.sports.footballdata

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.sports.provider.SportsProviderHttpClient
import com.streamvault.domain.sports.provider.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test

class FootballDataProviderTest {
    private lateinit var server: MockWebServer
    private lateinit var responses: ConcurrentLinkedQueue<MockResponse>
    private lateinit var requests: CopyOnWriteArrayList<RecordedRequest>
    private lateinit var cache: FootballDataResponseCache
    private lateinit var quota: FootballDataQuotaTracker
    private val now = Instant.parse("2026-10-17T12:00:00Z")
    private val token = "football-data-test-token"

    @Before
    fun setUp() {
        server = MockWebServer()
        responses = ConcurrentLinkedQueue()
        requests = CopyOnWriteArrayList()
        cache = FootballDataResponseCache()
        quota = FootballDataQuotaTracker()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return responses.poll() ?: MockResponse().setResponseCode(500)
            }
        }
        server.start()
    }

    @After
    fun tearDown() { server.shutdown() }

    @Test
    fun `descriptor records required credit mobile single-app use and no artwork`() {
        val factory = factory()
        assertThat(factory.descriptor.auth).isEqualTo(SportsProviderAuth.ApiKeyHeader("X-Auth-Token"))
        assertThat(factory.descriptor.defaultBaseUrl).isEqualTo(FootballDataProviderFactory.BASE_URL)
        assertThat(factory.descriptor.compliance?.releaseGate).isEqualTo(ProviderReleaseGate.REQUIRES_ATTRIBUTION)
        assertThat(factory.descriptor.compliance?.attribution?.format)
            .isEqualTo(ProviderAttributionFormat.FOOTBALL_DATA)
        assertThat(factory.descriptor.capabilities).containsExactly(
            SportsCapability.EVENTS,
            SportsCapability.LIVE_SCORES,
            SportsCapability.GAME_STATUS,
            SportsCapability.STANDINGS,
        )
        assertThat(factory.descriptor.capabilities).containsNoneOf(
            SportsCapability.ARTWORK,
            SportsCapability.BROADCASTERS,
            SportsCapability.STATISTICS,
        )
        assertThat(factory.capabilitiesFor(config("PL", "CL"))).containsExactlyElementsIn(factory.descriptor.capabilities)
        assertThat(factory.capabilitiesFor(config("football:PL"))).isEmpty()
    }

    @Test
    fun `connection probe uses a credential header and accepts empty match lists`() = runTest {
        responses += fixture("football_data_empty_matches.json")
        val tester = provider() as SportsProviderConnectionTester

        assertThat(tester.testConnection(credentials())).isEqualTo(SportsProviderResult.Success(Unit))
        val request = requests.single()
        assertThat(request.getHeader("X-Auth-Token")).isEqualTo(token)
        assertThat(request.requestUrl?.encodedPath).isEqualTo("/v4/matches")
        assertThat(request.requestUrl?.queryParameter("dateFrom")).isEqualTo("2026-10-17")
        assertThat(request.requestUrl?.queryParameter("dateTo")).isEqualTo("2026-10-17")
        assertThat(request.requestUrl?.queryParameterNames).doesNotContain("api_token")
        assertThat(request.requestUrl.toString()).doesNotContain(token)
    }

    @Test
    fun `events map football fixtures filter competition codes and keep user token out of URLs`() = runTest {
        responses += fixture("football_data_matches.json")
        val eventProvider = provider(config("PL")) as EventProvider

        val result = eventProvider.events(ProviderEventRequest(
            from = Instant.parse("2026-10-17T12:00:00Z"),
            until = Instant.parse("2026-10-18T00:00:00Z"),
            sport = "football",
            leagueId = "PL",
        ))

        assertThat(result).isInstanceOf(SportsProviderResult.Success::class.java)
        val events = (result as SportsProviderResult.Success).value.items
        assertThat(events.map(ProviderEvent::providerEventId)).containsExactly("330299", "330300").inOrder()
        val inPlay = events.first()
        assertThat(inPlay.sport).isEqualTo("Football")
        assertThat(inPlay.league).isEqualTo(ProviderLeague("Premier League", "PL", "Football"))
        assertThat(inPlay.title).isNull()
        assertThat(inPlay.competitors.map(ProviderCompetitor::name))
            .containsExactly("Arsenal FC", "Chelsea FC").inOrder()
        assertThat(inPlay.competitors.map(ProviderCompetitor::role)).containsExactly(
            ProviderCompetitorRole.HOME, ProviderCompetitorRole.AWAY,
        ).inOrder()
        assertThat(inPlay.competitors.map(ProviderCompetitor::kind)).containsExactly(
            ProviderCompetitorKind.TEAM, ProviderCompetitorKind.TEAM,
        ).inOrder()
        assertThat(inPlay.eventStart).isEqualTo(Instant.parse("2026-10-17T16:05:00Z"))
        assertThat(inPlay.status?.state).isEqualTo(ProviderGameState.LIVE)
        assertThat(inPlay.status?.clock).isEqualTo("61'")
        assertThat(events.last().status?.state).isEqualTo(ProviderGameState.SCHEDULED)
        val request = requests.single()
        assertThat(request.getHeader("X-Auth-Token")).isEqualTo(token)
        assertThat(request.requestUrl?.queryParameter("competitions")).isEqualTo("PL")
        assertThat(request.requestUrl.toString()).doesNotContain(token)
    }

    @Test
    fun `same-day event windows do not share a locally filtered response`() = runTest {
        responses += fixture("football_data_matches.json")
        responses += fixture("football_data_matches.json")
        val provider = provider() as EventProvider

        val afternoon = provider.events(eventRequest("2026-10-17T12:00:00Z", "2026-10-17T17:00:00Z"))
        val evening = provider.events(eventRequest("2026-10-17T17:00:00Z", "2026-10-17T20:00:00Z"))

        assertThat((afternoon as SportsProviderResult.Success).value.items.map(ProviderEvent::providerEventId))
            .containsExactly("330299")
        assertThat((evening as SportsProviderResult.Success).value.items.map(ProviderEvent::providerEventId))
            .containsExactly("330300")
        assertThat(requests).hasSize(2)
    }

    @Test
    fun `score details are batched cached and expose status without requesting extra fields`() = runTest {
        responses += fixture("football_data_matches.json")
        val provider = provider(config("PL"))
        val ids = ProviderScoreRequest(setOf("330299", "330300"))

        val scores = (provider as LiveScoreProvider).liveScores(ids)
        val mapped = (scores as SportsProviderResult.Success).value.items
        assertThat(mapped.map { it.providerEventId }).containsExactly("330299", "330300").inOrder()
        assertThat(mapped.first().scores.map(ProviderCompetitorScore::value)).containsExactly("2", "1").inOrder()
        assertThat(mapped.last().scores.map(ProviderCompetitorScore::value)).containsExactly(null, null).inOrder()
        assertThat(mapped.first().status?.state).isEqualTo(ProviderGameState.LIVE)

        val statuses = (provider as GameStatusProvider).gameStatuses(ids)
        assertThat((statuses as SportsProviderResult.Success).value.items.map { it.status.state })
            .containsExactly(ProviderGameState.LIVE, ProviderGameState.SCHEDULED).inOrder()
        assertThat(requests).hasSize(1)
        assertThat(requests.single().requestUrl?.queryParameter("ids")).isEqualTo("330299,330300")
    }

    @Test
    fun `standings use only total table and map the provider statistics`() = runTest {
        responses += fixture("football_data_standings.json")
        val result = (provider(config("PL")) as StandingsProvider)
            .standings(ProviderStandingsRequest("PL", "2026"))

        assertThat(result).isInstanceOf(SportsProviderResult.Success::class.java)
        val standings = (result as SportsProviderResult.Success).value
        assertThat(standings.league).isEqualTo(ProviderLeague("Premier League", "PL", "Football"))
        assertThat(standings.season).isEqualTo("2026")
        assertThat(standings.groups).hasSize(1)
        assertThat(standings.groups.single().rows.map { it.competitor.name })
            .containsExactly("Arsenal FC", "Chelsea FC").inOrder()
        assertThat(standings.groups.single().rows.first().values).containsEntry("intPoints", "19")
        assertThat(requests.single().requestUrl?.encodedPath).isEqualTo("/v4/competitions/PL/standings")
        assertThat(requests.single().requestUrl?.queryParameter("season")).isEqualTo("2026")
    }

    @Test
    fun `quota response metadata is surfaced and provider caps calls at published free rate`() = runTest {
        responses += fixture("football_data_empty_matches.json")
            .addHeader("X-RequestsAvailable", "7")
            .addHeader("X-RequestCounter-Reset", "30")
        val provider = provider() as SportsProviderUsageProvider
        assertThat((provider as SportsProviderConnectionTester).testConnection(credentials()))
            .isEqualTo(SportsProviderResult.Success(Unit))
        val usage = provider.usageSnapshot(credentials())
        assertThat(usage?.requestsRemaining).isEqualTo(7)
        assertThat(usage?.resetAt).isEqualTo(now.plusSeconds(30))
        assertThat(usage?.requestLimit).isNull()

        val localLimit = FootballDataQuotaTracker()
        repeat(FootballDataProviderFactory.CONSERVATIVE_CALLS_PER_MINUTE) {
            assertThat(localLimit.reserve("fingerprint", now)).isNull()
        }
        assertThat(localLimit.reserve("fingerprint", now)).isEqualTo(now.plusSeconds(60))
        assertThat(localLimit.reserve("fingerprint", now.plusSeconds(60))).isNull()
    }

    @Test
    fun `403 clears previous response cache for that credential`() = runTest {
        responses += fixture("football_data_matches.json")
        responses += MockResponse().setResponseCode(403).setBody("do not expose this response")
        responses += fixture("football_data_matches.json")
        val provider = provider(config("PL")) as EventProvider
        val first = eventRequest("2026-10-17T00:00:00Z", "2026-10-18T00:00:00Z")
        val different = eventRequest("2026-10-18T00:00:00Z", "2026-10-19T00:00:00Z")

        assertThat(provider.events(first)).isInstanceOf(SportsProviderResult.Success::class.java)
        assertThat(provider.events(different)).isEqualTo(
            SportsProviderResult.Failure(SportsProviderError.AUTHENTICATION, httpStatus = 403),
        )
        assertThat(provider.events(first)).isInstanceOf(SportsProviderResult.Success::class.java)
        assertThat(requests).hasSize(3)
    }

    @Test
    fun `event window and standings scope are bounded and malformed scopes fail before HTTP`() = runTest {
        val provider = provider(config("PL"))
        val tooLong = (provider as EventProvider).events(eventRequest(
            "2026-10-01T00:00:00Z", "2026-10-20T00:00:00Z",
        ))
        val wrongScope = (provider as StandingsProvider).standings(ProviderStandingsRequest("PD"))
        val wrongSport = (provider as EventProvider).events(ProviderEventRequest(
            now, now.plusSeconds(60), sport = "cricket",
        ))

        assertThat(tooLong).isEqualTo(SportsProviderResult.Failure(SportsProviderError.RESPONSE_TOO_LARGE))
        assertThat(wrongScope).isEqualTo(SportsProviderResult.Failure(SportsProviderError.INVALID_CONFIGURATION))
        assertThat(wrongSport).isEqualTo(SportsProviderResult.Failure(SportsProviderError.INVALID_CONFIGURATION))
        assertThat(requests).isEmpty()
    }

    private fun factory() = FootballDataProviderFactory(
        httpClient = httpClient(),
        store = FakeStore(config("PL")),
        cache = cache,
        quota = quota,
    )

    private fun provider(config: SportsProviderConfig = config()) = FootballDataProvider(
        config = config,
        descriptor = factory().descriptor,
        store = FakeStore(config),
        httpClient = httpClient(),
        cache = cache,
        quota = quota,
        endpointBaseUrl = server.url("/v4").toString().trimEnd('/'),
        allowInsecureHttp = true,
        clock = Clock.fixed(now, ZoneOffset.UTC),
    )

    private fun httpClient() = SportsProviderHttpClient(
        userAgent = "StreamVault-test",
        clock = Clock.fixed(now, ZoneOffset.UTC),
    )

    private fun config(vararg leagues: String) = SportsProviderConfig("football-account", "football-data",
        eventLeagueIds = leagues.toList())

    private fun credentials() = SportsProviderCredentials(token)

    private fun eventRequest(from: String, until: String) = ProviderEventRequest(
        from = Instant.parse(from), until = Instant.parse(until),
    )

    private fun fixture(name: String): MockResponse {
        val body = requireNotNull(javaClass.classLoader?.getResourceAsStream("sports/providers/$name"))
            .bufferedReader().use { it.readText() }
        return MockResponse().setBody(body).addHeader("Content-Type", "application/json")
    }

    private class FakeStore(
        private val config: SportsProviderConfig,
        private val credential: SportsProviderCredentials = SportsProviderCredentials("football-data-test-token"),
    ) : SportsProviderStore {
        override suspend fun configurations() = listOf(config)
        override suspend fun credentials(instanceId: String) = credential.takeIf { instanceId == config.instanceId }
        override suspend fun save(config: SportsProviderConfig, credentials: SportsProviderCredentials?) = Unit
        override suspend fun remove(instanceId: String) = Unit
    }
}
