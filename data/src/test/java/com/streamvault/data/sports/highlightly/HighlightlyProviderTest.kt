package com.streamvault.data.sports.highlightly

import com.google.common.truth.Truth.assertThat
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

class HighlightlyProviderTest {
    private lateinit var server: MockWebServer
    private lateinit var paths: CopyOnWriteArrayList<String>
    private lateinit var keys: CopyOnWriteArrayList<String?>
    private lateinit var hosts: CopyOnWriteArrayList<String?>
    private lateinit var cache: HighlightlyResponseCache
    private lateinit var quotaTracker: HighlightlyQuotaTracker
    private val now = Instant.parse("2026-10-17T10:00:00Z")
    private val apiKey = "highlightly-test-key"

    @Before
    fun setUp() {
        server = MockWebServer()
        paths = CopyOnWriteArrayList()
        keys = CopyOnWriteArrayList()
        hosts = CopyOnWriteArrayList()
        cache = HighlightlyResponseCache()
        quotaTracker = HighlightlyQuotaTracker()
        server.dispatcher = fixtureDispatcher()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `descriptor exposes reviewed data capabilities without artwork or video`() {
        val factory = factory(config("football:39"))
        val allSportScopes = factory.capabilitiesFor(config("football:39", "nba:NBA"))
        val majorLeagueOnly = factory.capabilitiesFor(config("nba:NBA"))

        assertThat(factory.descriptor.auth).isEqualTo(SportsProviderAuth.ApiKeyHeader("x-rapidapi-key"))
        assertThat(factory.descriptor.defaultBaseUrl).isEqualTo(HighlightlyProviderFactory.BASE_URL)
        assertThat(factory.descriptor.compliance?.releaseGate).isEqualTo(ProviderReleaseGate.APPROVED)
        assertThat(factory.descriptor.capabilities).containsExactly(
            SportsCapability.EVENTS,
            SportsCapability.LIVE_SCORES,
            SportsCapability.GAME_STATUS,
            SportsCapability.STANDINGS,
            SportsCapability.TEAM_METADATA,
            SportsCapability.LEAGUE_METADATA,
        )
        assertThat(allSportScopes).contains(SportsCapability.LEAGUE_METADATA)
        assertThat(majorLeagueOnly).doesNotContain(SportsCapability.LEAGUE_METADATA)
        assertThat(factory.capabilitiesFor(config("unsupported:1"))).isEmpty()
        assertThat(factory.descriptor.capabilities).containsNoneOf(
            SportsCapability.ARTWORK,
            SportsCapability.BROADCASTERS,
            SportsCapability.LINEUPS,
            SportsCapability.STATISTICS,
        )
    }

    @Test
    fun `connection test uses direct All Sports route and header key without RapidAPI host`() = runTest {
        val config = config("football:39")
        val tester = provider(config) as SportsProviderConnectionTester

        assertThat(tester.testConnection(credentials())).isEqualTo(SportsProviderResult.Success(Unit))
        assertThat(paths.single()).contains("/football/matches?")
        assertThat(paths.single()).contains("leagueId=39")
        assertThat(paths.single()).contains("date=2026-10-17")
        assertThat(keys.single()).isEqualTo(apiKey)
        assertThat(hosts.single()).isNull()
    }

    @Test
    fun `date queries page matches across sport prefixes and share account quota`() = runTest {
        val config = config("football:39", "cricket:123")
        val provider = provider(config)
        val events = provider as EventProvider
        val request = ProviderEventRequest(
            from = Instant.parse("2026-10-17T00:00:00Z"),
            until = Instant.parse("2026-10-18T00:00:00Z"),
        )

        val result = events.events(request)
        assertThat(result).isInstanceOf(SportsProviderResult.Success::class.java)
        val rows = (result as SportsProviderResult.Success).value.items
        assertThat(rows.map(ProviderEvent::providerEventId)).containsExactly(
            "football:501", "football:502", "cricket:9001",
        ).inOrder()
        val live = rows.first()
        assertThat(live.sport).isEqualTo("Football")
        assertThat(live.league).isEqualTo(ProviderLeague("Premier League", "football:39", "Football"))
        assertThat(live.competitors.map(ProviderCompetitor::name)).containsExactly("Arsenal", "Chelsea").inOrder()
        assertThat(live.competitors.map(ProviderCompetitor::role)).containsExactly(
            ProviderCompetitorRole.HOME, ProviderCompetitorRole.AWAY,
        ).inOrder()
        assertThat(live.eventStart).isEqualTo(Instant.parse("2026-10-17T16:30:00Z"))
        assertThat(live.status?.state).isEqualTo(ProviderGameState.LIVE)
        val cricket = rows.last()
        assertThat(cricket.sport).isEqualTo("Cricket")
        assertThat(cricket.eventStart).isEqualTo(Instant.parse("2026-10-17T14:30:00Z"))
        assertThat(cricket.status?.state).isEqualTo(ProviderGameState.LIVE)

        val scores = (provider as LiveScoreProvider).liveScores(
            ProviderScoreRequest(setOf("football:501", "cricket:9001")),
        )
        assertThat(scores).isInstanceOf(SportsProviderResult.Success::class.java)
        val values = (scores as SportsProviderResult.Success).value.items
        assertThat(values.first { it.providerEventId == "football:501" }.scores.map { it.value })
            .containsExactly("2", "1").inOrder()
        assertThat(values.first { it.providerEventId == "cricket:9001" }.scores.map { it.value })
            .containsExactly("139/4", "137/8").inOrder()
        val statuses = (provider as GameStatusProvider).gameStatuses(
            ProviderScoreRequest(setOf("football:501", "cricket:9001")),
        )
        assertThat((statuses as SportsProviderResult.Success).value.items).hasSize(2)

        assertThat(paths.count { "/football/matches?" in it }).isEqualTo(2)
        assertThat(paths.count { "/cricket/matches?" in it }).isEqualTo(1)
        assertThat(paths.all { "date=2026-10-17" in it }).isTrue()
        assertThat(keys).containsAtLeast(apiKey, apiKey, apiKey)
        assertThat(hosts).containsExactly(null, null, null)
        val usage = (provider as SportsProviderUsageProvider).usageSnapshot(credentials())
        assertThat(usage?.requestLimit).isEqualTo(100)
        assertThat(usage?.requestsRemaining).isEqualTo(97)
        assertThat(usage?.planTier).isEqualTo("BASIC")
    }

    @Test
    fun `standings map groups and team rows for football and major sport shapes`() = runTest {
        val football = provider(config("football:39")) as StandingsProvider
        val footballResult = football.standings(ProviderStandingsRequest("football:39", "2026"))
        assertThat(footballResult).isInstanceOf(SportsProviderResult.Success::class.java)
        val table = (footballResult as SportsProviderResult.Success).value
        assertThat(table.league).isEqualTo(ProviderLeague("Premier League", "football:39", "Football"))
        assertThat(table.groups.single().name).isEqualTo("Premier League - Regular Season")
        assertThat(table.groups.single().rows.map { it.competitor.name })
            .containsExactly("Arsenal", "Chelsea").inOrder()
        assertThat(table.groups.single().rows.first().rank).isEqualTo(1)
        assertThat(table.groups.single().rows.first().values["total.wins"]).isEqualTo("6")

        val nba = provider(config("nba:NBA")) as StandingsProvider
        val nbaResult = nba.standings(ProviderStandingsRequest("nba:NBA", "2026"))
        assertThat(nbaResult).isInstanceOf(SportsProviderResult.Success::class.java)
        val nbaRows = (nbaResult as SportsProviderResult.Success).value.groups.single().rows
        assertThat(nbaRows.single().competitor.id).isEqualTo("nba:1610612738")
        assertThat(nbaRows.single().values["W"]).isEqualTo("7")
        assertThat(paths.any { "/nba/standings?" in it && "leagueName=NBA" in it && "year=2026" in it })
            .isTrue()
    }

    @Test
    fun `team and league metadata use namespaced IDs and omit visual assets`() = runTest {
        val provider = provider(config("football:39"))
        val team = (provider as TeamMetadataProvider).teamMetadata(ProviderTeamMetadataRequest("football:10"))
        assertThat(team).isInstanceOf(SportsProviderResult.Success::class.java)
        val teamMetadata = (team as SportsProviderResult.Success).value
        assertThat(teamMetadata.team.id).isEqualTo("football:10")
        assertThat(teamMetadata.team.name).isEqualTo("Arsenal")
        assertThat(teamMetadata.shortName).isEqualTo("ARS")

        val league = (provider as LeagueMetadataProvider).leagueMetadata(
            ProviderLeagueMetadataRequest("football:39"),
        )
        assertThat(league).isInstanceOf(SportsProviderResult.Success::class.java)
        val metadata = (league as SportsProviderResult.Success).value
        assertThat(metadata.league).isEqualTo(ProviderLeague("Premier League", "football:39", "Football"))
        assertThat(metadata.country).isEqualTo("England")
        assertThat(metadata.season).isEqualTo("2026")
        assertThat(paths).containsAtLeast("${server.url("/football/teams/10")}", "${server.url("/football/leagues/39")}")
    }

    @Test
    fun `malformed scope and oversized event window fail before making requests`() = runTest {
        val events = provider(config("football:39")) as EventProvider
        val badScope = events.events(
            ProviderEventRequest(now, now.plusSeconds(60), leagueId = "unknown:7"),
        )
        val wideWindow = events.events(
            ProviderEventRequest(now, now.plusSeconds(15L * 24 * 60 * 60)),
        )

        assertThat(badScope).isEqualTo(
            SportsProviderResult.Failure(SportsProviderError.INVALID_CONFIGURATION),
        )
        assertThat(wideWindow).isEqualTo(
            SportsProviderResult.Failure(SportsProviderError.RESPONSE_TOO_LARGE),
        )
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `HTTP failures retain typed rate limit and discard body`() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse()
                .setResponseCode(429)
                .setHeader("x-ratelimit-requests-remaining", "0")
                .setBody(apiKey)
        }
        val tester = provider(config("football:39")) as SportsProviderConnectionTester

        val result = tester.testConnection(credentials())
        assertThat(result).isEqualTo(SportsProviderResult.Failure(SportsProviderError.RATE_LIMITED, 429))
        assertThat(result.toString()).doesNotContain(apiKey)
        assertThat((tester as SportsProviderUsageProvider).usageSnapshot(credentials())?.requestsRemaining).isEqualTo(0)
    }

    private fun fixtureDispatcher() = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.requestUrl?.encodedPath.orEmpty()
            paths += request.requestUrl.toString()
            keys += request.getHeader("x-rapidapi-key")
            hosts += request.getHeader("x-rapidapi-host")
            return when {
                path == "/football/matches" -> {
                    val offset = request.requestUrl?.queryParameter("offset")
                    fixture(if (offset == "1") "highlightly_football_page_2.json" else "highlightly_football_page_1.json")
                        .setHeader("x-ratelimit-requests-limit", "100")
                        .setHeader("x-ratelimit-requests-remaining", if (offset == "1") "98" else "99")
                }
                path == "/cricket/matches" -> fixture("highlightly_cricket_matches.json")
                    .setHeader("x-ratelimit-requests-limit", "100")
                    .setHeader("x-ratelimit-requests-remaining", "97")
                path == "/football/standings" -> fixture("highlightly_football_standings.json")
                path == "/nba/standings" -> fixture("highlightly_nba_standings.json")
                path == "/football/teams/10" -> fixture("highlightly_team.json")
                path == "/football/leagues/39" -> fixture("highlightly_league.json")
                else -> MockResponse().setResponseCode(404)
            }
        }
    }

    private fun fixture(name: String): MockResponse = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(requireNotNull(javaClass.classLoader?.getResource("sports/providers/$name")).readText())

    private fun factory(config: SportsProviderConfig): HighlightlyProviderFactory = HighlightlyProviderFactory(
        httpClient = SportsProviderHttpClient("StreamVault-highlightly-test"),
        store = FakeStore(config, credentials()),
        cache = cache,
        quotaTracker = quotaTracker,
    )

    private fun provider(config: SportsProviderConfig): SportsProvider = HighlightlyProvider(
        config = config,
        descriptor = factory(config).descriptor,
        store = FakeStore(config, credentials()),
        httpClient = SportsProviderHttpClient("StreamVault-highlightly-test"),
        cache = cache,
        quotaTracker = quotaTracker,
        endpointBaseUrl = server.url("/").toString().trimEnd('/'),
        allowInsecureHttp = true,
        clock = Clock.fixed(now, ZoneOffset.UTC),
    )

    private fun config(vararg scopes: String) = SportsProviderConfig(
        instanceId = "highlightly-test-${scopes.joinToString("-").replace(':', '-')}",
        typeId = HighlightlyProviderFactory.TYPE_ID,
        baseUrl = HighlightlyProviderFactory.BASE_URL,
        eventLeagueIds = scopes.toList(),
    )

    private fun credentials() = SportsProviderCredentials(apiKey)

    private inner class FakeStore(
        private val config: SportsProviderConfig,
        private val savedCredentials: SportsProviderCredentials,
    ) : SportsProviderStore {
        override suspend fun configurations(): List<SportsProviderConfig> = listOf(config)
        override suspend fun credentials(instanceId: String): SportsProviderCredentials? =
            savedCredentials.takeIf { instanceId == config.instanceId }
        override suspend fun save(config: SportsProviderConfig, credentials: SportsProviderCredentials?) = Unit
        override suspend fun remove(instanceId: String) = Unit
    }

}
