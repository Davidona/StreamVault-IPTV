package com.streamvault.data.sports.balldontlie

import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonParser
import com.streamvault.data.sports.provider.SportsProviderHttpClient
import com.streamvault.domain.sports.provider.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test

class BallDontLieProviderTest {
    private lateinit var server: MockWebServer
    private lateinit var paths: CopyOnWriteArrayList<String>
    private lateinit var authorizationHeaders: CopyOnWriteArrayList<String?>

    @Before
    fun setUp() {
        server = MockWebServer()
        paths = CopyOnWriteArrayList()
        authorizationHeaders = CopyOnWriteArrayList()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl?.encodedPath.orEmpty()
                paths += request.requestUrl.toString()
                authorizationHeaders += request.getHeader("Authorization")
                return when {
                    path == "/account/v1/subscriptions" -> fixture(
                        if (request.getHeader("Authorization") == "Bearer mlb-paid") {
                            "balldontlie_subscriptions_mlb_paid.json"
                        } else "balldontlie_subscriptions.json",
                    )
                    path == "/mlb/v1/games" -> fixture("balldontlie_empty_page.json")
                    path == "/v1/games" -> fixture("balldontlie_nba_games.json")
                    path == "/v1/games/15907925" -> detailFixture()
                    path == "/epl/v2/standings" -> fixture("balldontlie_epl_standings.json")
                    path == "/v1/standings" -> fixture("balldontlie_nba_standings.json")
                    path == "/mlb/v1/standings" -> fixture("balldontlie_mlb_standings.json")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `descriptor and sport scopes resolve only implemented data capabilities`() {
        val store = FakeStore(config("scope"), credentials())
        val factory = factory(store)

        assertThat(factory.descriptor.compliance?.releaseGate).isEqualTo(ProviderReleaseGate.APPROVED)
        assertThat(factory.descriptor.capabilities).containsExactly(
            SportsCapability.EVENTS,
            SportsCapability.LIVE_SCORES,
            SportsCapability.GAME_STATUS,
            SportsCapability.STANDINGS,
        )
        assertThat(factory.capabilitiesFor(config("nba", "epl"))).containsExactly(
            SportsCapability.EVENTS,
            SportsCapability.LIVE_SCORES,
            SportsCapability.GAME_STATUS,
            SportsCapability.STANDINGS,
        )
        assertThat(factory.capabilitiesFor(config("nfl"))).contains(SportsCapability.STANDINGS)
        assertThat(factory.capabilitiesFor(config("unsupported"))).isEmpty()
    }

    @Test
    fun `connection introspects subscriptions and keeps account and sports auth contracts separate`() = runTest {
        val store = FakeStore(config("nba"), credentials())
        val provider = adapter(store)

        assertThat(provider.testConnection(credentials())).isInstanceOf(SportsProviderResult.Success::class.java)
        assertThat(paths).contains("${server.url("/account/v1/subscriptions")}")
        assertThat(paths).contains("${server.url("/v1/games")}?per_page=1")
        assertThat(authorizationHeaders).containsAtLeast("Bearer test-key", "test-key")
    }

    @Test
    fun `empty scope probes a free event endpoint without selecting paid EPL matches`() = runTest {
        val provider = adapter(FakeStore(config(), credentials()))

        val result = provider.testConnection(credentials())

        assertThat(result).isInstanceOf(SportsProviderResult.Success::class.java)
        assertThat(paths).contains("${server.url("/mlb/v1/games")}?per_page=1")
        assertThat(paths.none { "/epl/v2/matches" in it }).isTrue()
    }

    @Test
    fun `event list maps participants status score details and shares bounded detail cache`() = runTest {
        val config = config("nba")
        val store = FakeStore(config, credentials())
        val provider = adapter(store)
        val from = Instant.parse("2026-10-17T00:00:00Z")
        val until = Instant.parse("2026-10-18T00:00:00Z")

        val result = provider.events(ProviderEventRequest(from, until, leagueId = "nba"))
        assertThat(result).isInstanceOf(SportsProviderResult.Success::class.java)
        val event = (result as SportsProviderResult.Success).value.items.single()
        assertThat(event.providerEventId).isEqualTo("nba:15907925")
        assertThat(event.sport).isEqualTo("Basketball")
        assertThat(event.league).isEqualTo(ProviderLeague("NBA", "nba", "Basketball"))
        assertThat(event.competitors.map { it.name }).containsExactly("Cleveland Cavaliers", "Charlotte Hornets")
        assertThat(event.competitors.map { it.role }).containsExactly(
            ProviderCompetitorRole.HOME,
            ProviderCompetitorRole.AWAY,
        )
        assertThat(event.status?.state).isEqualTo(ProviderGameState.LIVE)
        assertThat(paths.single { "/v1/games?" in it }).contains("dates%5B%5D=2026-10-17")

        val scoreResult = provider.liveScores(ProviderScoreRequest(setOf(event.providerEventId)))
        assertThat(scoreResult).isInstanceOf(SportsProviderResult.Success::class.java)
        val score = (scoreResult as SportsProviderResult.Success).value.items.single()
        assertThat(score.scores.map { it.value }).containsExactly("88", "79").inOrder()
        assertThat(score.status?.state).isEqualTo(ProviderGameState.LIVE)

        val requestCount = paths.count { it.endsWith("/v1/games/15907925") }
        val statusResult = provider.gameStatuses(ProviderScoreRequest(setOf(event.providerEventId)))
        assertThat(statusResult).isInstanceOf(SportsProviderResult.Success::class.java)
        assertThat((statusResult as SportsProviderResult.Success).value.items.single().status.state)
            .isEqualTo(ProviderGameState.LIVE)
        assertThat(paths.count { it.endsWith("/v1/games/15907925") }).isEqualTo(requestCount)
    }

    @Test
    fun `standings maps league columns and gates NBA standings by its own subscription tier`() = runTest {
        val epl = adapter(FakeStore(config("epl"), credentials()))
        val result = epl.standings(ProviderStandingsRequest("epl", "2026"))
        assertThat(result).isInstanceOf(SportsProviderResult.Success::class.java)
        val table = (result as SportsProviderResult.Success).value
        assertThat(table.league).isEqualTo(ProviderLeague("Premier League", "epl", "Football"))
        assertThat(table.season).isEqualTo("2026")
        assertThat(table.groups.single().rows.single().competitor.name).isEqualTo("Arsenal")
        assertThat(table.groups.single().rows.single().values["points"]).isEqualTo("19")

        val nba = adapter(FakeStore(config("nba"), credentials()))
        val denied = nba.standings(ProviderStandingsRequest("nba", "2026"))
        assertThat(denied).isInstanceOf(SportsProviderResult.Failure::class.java)
        assertThat((denied as SportsProviderResult.Failure).category)
            .isEqualTo(SportsProviderError.AUTHENTICATION)
        assertThat(paths.none { "/v1/standings?" in it }).isTrue()
    }

    @Test
    fun `free EPL subscription permits standings but blocks matches and score details`() = runTest {
        val provider = adapter(FakeStore(config("epl"), credentials()))

        val connection = provider.testConnection(credentials())
        val events = provider.events(
            ProviderEventRequest(
                Instant.parse("2026-10-17T00:00:00Z"),
                Instant.parse("2026-10-18T00:00:00Z"),
                leagueId = "epl",
            ),
        )
        val scores = provider.liveScores(ProviderScoreRequest(setOf("epl:371")))
        val standings = provider.standings(ProviderStandingsRequest("epl", "2026"))

        assertThat(connection).isInstanceOf(SportsProviderResult.Failure::class.java)
        assertThat((connection as SportsProviderResult.Failure).category)
            .isEqualTo(SportsProviderError.AUTHENTICATION)
        assertThat(events).isInstanceOf(SportsProviderResult.Failure::class.java)
        assertThat((events as SportsProviderResult.Failure).category)
            .isEqualTo(SportsProviderError.AUTHENTICATION)
        assertThat(scores).isInstanceOf(SportsProviderResult.Failure::class.java)
        assertThat((scores as SportsProviderResult.Failure).category)
            .isEqualTo(SportsProviderError.AUTHENTICATION)
        assertThat(standings).isInstanceOf(SportsProviderResult.Success::class.java)
        assertThat(paths.none { "/epl/v2/matches" in it }).isTrue()
        assertThat(paths.any { "/epl/v2/standings?" in it }).isTrue()
    }

    @Test
    fun `MLB standings derive division rank from documented win percentage fields`() = runTest {
        val provider = adapter(FakeStore(config("mlb"), SportsProviderCredentials("mlb-paid")))

        val result = provider.standings(ProviderStandingsRequest("mlb", "2026"))

        assertThat(result).isInstanceOf(SportsProviderResult.Success::class.java)
        val group = (result as SportsProviderResult.Success).value.groups.single()
        assertThat(group.name).isEqualTo("American League East")
        assertThat(group.rows.map { it.competitor.name })
            .containsExactly("New York Yankees", "Baltimore Orioles").inOrder()
        assertThat(group.rows.map(ProviderStandingRow::rank)).containsExactly(1, 2).inOrder()
    }

    @Test
    fun `malformed account and unsupported sport scope fail without sending sport requests`() = runTest {
        val unsupported = config("nhl")
        val provider = adapter(FakeStore(unsupported, credentials()))

        val result = provider.testConnection(credentials())

        assertThat(result).isInstanceOf(SportsProviderResult.Failure::class.java)
        assertThat((result as SportsProviderResult.Failure).category)
            .isEqualTo(SportsProviderError.INVALID_CONFIGURATION)
        assertThat(paths).containsExactly("${server.url("/account/v1/subscriptions")}")
    }

    private fun factory(store: FakeStore) = BallDontLieProviderFactory(
        httpClient = SportsProviderHttpClient("StreamVault-tests"),
        store = store,
        cache = BallDontLieResponseCache(),
    )

    private fun adapter(store: FakeStore) = BallDontLieProvider(
        config = store.config,
        descriptor = factory(store).descriptor,
        store = store,
        httpClient = SportsProviderHttpClient("StreamVault-tests"),
        cache = BallDontLieResponseCache(),
        endpointBaseUrl = server.url("/").toString().trimEnd('/'),
        allowInsecureHttp = true,
        clock = Clock.fixed(Instant.parse("2026-10-17T18:20:00Z"), ZoneOffset.UTC),
    )

    private fun config(vararg scopes: String) = SportsProviderConfig(
        instanceId = "balldontlie-test",
        typeId = BallDontLieProviderFactory.TYPE_ID,
        eventLeagueIds = scopes.toList(),
    )

    private fun credentials() = SportsProviderCredentials("test-key")

    private fun fixture(name: String): MockResponse = MockResponse()
        .setBody(readFixture(name))

    private fun detailFixture(): MockResponse {
        val element = JsonParser.parseString(readFixture("balldontlie_nba_games.json"))
            .asJsonObject.getAsJsonArray("data").single()
        return MockResponse().setBody("""{"data":$element}""")
    }

    private fun readFixture(name: String): String = requireNotNull(
        javaClass.getResourceAsStream("/sports/providers/$name"),
    ).bufferedReader().use { it.readText() }

    private class FakeStore(
        val config: SportsProviderConfig,
        private val savedCredentials: SportsProviderCredentials?,
    ) : SportsProviderStore {
        override suspend fun configurations(): List<SportsProviderConfig> = listOf(config)
        override suspend fun credentials(instanceId: String): SportsProviderCredentials? =
            savedCredentials.takeIf { instanceId == config.instanceId }
        override suspend fun save(config: SportsProviderConfig, credentials: SportsProviderCredentials?) = Unit
        override suspend fun remove(instanceId: String) = Unit
    }
}
