package com.streamvault.data.sports.oddspapi

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

class OddsPapiProviderTest {
    private lateinit var server: MockWebServer
    private lateinit var responses: ConcurrentLinkedQueue<MockResponse>
    private lateinit var requests: CopyOnWriteArrayList<RecordedRequest>
    private lateinit var cache: OddsPapiResponseCache
    private lateinit var cooldown: OddsPapiEndpointCooldown
    private val now = Instant.parse("2026-10-17T12:00:00Z")
    private val token = "oddspapi-test-token"

    @Before
    fun setUp() {
        server = MockWebServer()
        responses = ConcurrentLinkedQueue()
        requests = CopyOnWriteArrayList()
        cache = OddsPapiResponseCache()
        cooldown = OddsPapiEndpointCooldown()
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
    fun `descriptor exposes only fixtures scores and state behind production vendor gate`() {
        val factory = factory()
        assertThat(factory.descriptor.auth).isEqualTo(SportsProviderAuth.ApiKeyQuery("apiKey"))
        assertThat(factory.descriptor.defaultBaseUrl).isEqualTo(OddsPapiProviderFactory.BASE_URL)
        assertThat(factory.descriptor.compliance?.releaseGate)
            .isEqualTo(ProviderReleaseGate.REQUIRES_VENDOR_CONFIRMATION)
        assertThat(factory.descriptor.compliance?.commercialUseStatus).isEqualTo(CommercialUseStatus.CONDITIONAL)
        assertThat(factory.descriptor.capabilities).containsExactly(
            SportsCapability.EVENTS, SportsCapability.LIVE_SCORES, SportsCapability.GAME_STATUS,
        )
        assertThat(factory.descriptor.capabilities).containsNoneOf(
            SportsCapability.STANDINGS, SportsCapability.ARTWORK, SportsCapability.BROADCASTERS,
        )
        assertThat(SportsProviderRegistry(listOf(factory)).descriptors).isEmpty()
        assertThat(SportsProviderRegistry(
            listOf(factory), SportsProviderReleasePolicy(development = true),
        ).descriptors).containsExactly(factory.descriptor)
    }

    @Test
    fun `connection test uses the documented query key and sanitizes authorization failures`() = runTest {
        responses += fixture("oddspapi_account.json")
        val tester = provider() as SportsProviderConnectionTester
        assertThat(tester.testConnection(credentials())).isEqualTo(SportsProviderResult.Success(Unit))
        val request = requests.single()
        assertThat(request.requestUrl?.encodedPath).isEqualTo("/v4/account")
        assertThat(request.requestUrl?.queryParameter("apiKey")).isEqualTo(token)
        assertThat(request.getHeader("Authorization")).isNull()

        responses += MockResponse().setResponseCode(401).setBody("private response text")
        val denied = tester.testConnection(SportsProviderCredentials("denied-token"))
        assertThat(denied).isEqualTo(SportsProviderResult.Failure(SportsProviderError.AUTHENTICATION, httpStatus = 401))
        assertThat(denied.toString()).doesNotContain(token)
        assertThat(denied.toString()).doesNotContain("private response text")
    }

    @Test
    fun `fixtures map participants states and exact time windows without requesting odds`() = runTest {
        responses += fixture("oddspapi_sports.json")
        responses += fixture("oddspapi_fixtures.json")
        val eventProvider = provider() as EventProvider

        val result = eventProvider.events(ProviderEventRequest(
            from = Instant.parse("2026-10-17T12:00:00Z"),
            until = Instant.parse("2026-10-17T17:00:00Z"),
            sport = "football",
            leagueId = "17",
        ))

        assertThat(result).isInstanceOf(SportsProviderResult.Success::class.java)
        val event = (result as SportsProviderResult.Success).value.items.single()
        assertThat(event.providerEventId).isEqualTo("id1000001761301153")
        assertThat(event.title).isNull()
        assertThat(event.sport).isEqualTo("Soccer")
        assertThat(event.league).isEqualTo(ProviderLeague("Premier League", "17", "Soccer"))
        assertThat(event.competitors.map(ProviderCompetitor::name))
            .containsExactly("Liverpool FC", "Manchester United").inOrder()
        assertThat(event.competitors.map(ProviderCompetitor::role))
            .containsExactly(ProviderCompetitorRole.HOME, ProviderCompetitorRole.AWAY).inOrder()
        assertThat(event.status?.state).isEqualTo(ProviderGameState.LIVE)
        assertThat(event.eventStart).isEqualTo(Instant.parse("2026-10-17T16:00:00Z"))
        assertThat(requests.map { it.requestUrl?.encodedPath })
            .containsExactly("/v4/sports", "/v4/fixtures").inOrder()
        val fixtureRequest = requests.last()
        assertThat(fixtureRequest.requestUrl?.queryParameter("sportId")).isEqualTo("10")
        assertThat(fixtureRequest.requestUrl?.queryParameter("tournamentId")).isEqualTo("17")
        assertThat(fixtureRequest.requestUrl?.queryParameterNames).doesNotContain("bookmakers")
    }

    @Test
    fun `score periods are summed and event status is served from the fixture cache`() = runTest {
        responses += fixture("oddspapi_fixtures.json")
        responses += fixture("oddspapi_scores.json")
        val provider = provider()
        val eventRequest = ProviderEventRequest(
            Instant.parse("2026-10-17T12:00:00Z"),
            Instant.parse("2026-10-17T20:00:00Z"),
            leagueId = "17",
        )
        assertThat((provider as EventProvider).events(eventRequest)).isInstanceOf(SportsProviderResult.Success::class.java)

        val scoreRequest = ProviderScoreRequest(setOf("id1000001761301153"))
        val scores = (provider as LiveScoreProvider).liveScores(scoreRequest)
        val score = (scores as SportsProviderResult.Success).value.items.single()
        assertThat(score.scores.map(ProviderCompetitorScore::value)).containsExactly("3", "1").inOrder()
        assertThat(score.updatedAt).isEqualTo(Instant.parse("2026-10-17T16:10:00Z"))

        val statuses = (provider as GameStatusProvider).gameStatuses(scoreRequest)
        assertThat((statuses as SportsProviderResult.Success).value.items.single().status.state)
            .isEqualTo(ProviderGameState.LIVE)
        assertThat(requests.map { it.requestUrl?.encodedPath })
            .containsExactly("/v4/fixtures", "/v4/scores").inOrder()
        assertThat(requests.mapNotNull { it.requestUrl?.queryParameter("apiKey") })
            .containsExactly(token, token).inOrder()
    }

    @Test
    fun `unscoped fixture windows obey documented under forty eight hour bound`() = runTest {
        val provider = provider() as EventProvider
        val result = provider.events(ProviderEventRequest(
            Instant.parse("2026-10-17T00:00:00Z"),
            Instant.parse("2026-10-19T00:00:00Z"),
        ))

        assertThat(result).isEqualTo(SportsProviderResult.Failure(SportsProviderError.RESPONSE_TOO_LARGE))
        assertThat(requests).isEmpty()
    }

    private fun factory() = OddsPapiProviderFactory(httpClient(), FakeStore(config()), cache, cooldown)

    private fun provider(config: SportsProviderConfig = config()) = OddsPapiProvider(
        config = config,
        descriptor = factory().descriptor,
        store = FakeStore(config),
        httpClient = httpClient(),
        cache = cache,
        cooldown = cooldown,
        endpointBaseUrl = server.url("/v4").toString().trimEnd('/'),
        allowInsecureHttp = true,
        clock = Clock.fixed(now, ZoneOffset.UTC),
    )

    private fun httpClient() = SportsProviderHttpClient(
        userAgent = "StreamVault-test",
        clock = Clock.fixed(now, ZoneOffset.UTC),
    )

    private fun config(vararg tournamentIds: String) = SportsProviderConfig(
        instanceId = "oddspapi-account",
        typeId = OddsPapiProviderFactory.TYPE_ID,
        eventLeagueIds = tournamentIds.toList(),
    )

    private fun credentials() = SportsProviderCredentials(token)

    private fun fixture(name: String): MockResponse {
        val body = requireNotNull(javaClass.classLoader?.getResourceAsStream("sports/providers/$name"))
            .bufferedReader().use { it.readText() }
        return MockResponse().setBody(body).addHeader("Content-Type", "application/json")
    }

    private class FakeStore(
        private val config: SportsProviderConfig,
        private val credential: SportsProviderCredentials = SportsProviderCredentials("oddspapi-test-token"),
    ) : SportsProviderStore {
        override suspend fun configurations() = listOf(config)
        override suspend fun credentials(instanceId: String) = credential.takeIf { instanceId == config.instanceId }
        override suspend fun save(config: SportsProviderConfig, credentials: SportsProviderCredentials?) = Unit
        override suspend fun remove(instanceId: String) = Unit
    }
}
