package com.streamvault.data.sports.thesportsdb

import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import com.streamvault.data.sports.provider.SportsProviderHttpClient
import com.streamvault.data.sports.provider.contract.EventProviderContract
import com.streamvault.data.sports.provider.contract.LiveScoreProviderContract
import com.streamvault.domain.sports.provider.*
import java.time.Clock
import java.time.Duration
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

private enum class EventScenario { VALID, MIXED_INVALID, ALL_INVALID, EMPTY, AUTH_FAILURE }
private enum class ScoreScenario { VALID, MISSING_FIELDS, MIXED_INVALID, AUTH_FAILURE, EMPTY }

private class TheSportsDbTestServer : AutoCloseable {
    private val server = MockWebServer()
    private val cache = TheSportsDbResponseCache()
    private val store = TestSportsProviderStore().apply {
        savedCredentials["test-account"] = SportsProviderCredentials(EventProviderContract.SECRET_MARKER)
    }
    private val paths = CopyOnWriteArrayList<String>()
    private val apiKeyHeaders = CopyOnWriteArrayList<String?>()
    @Volatile private var eventScenario = EventScenario.VALID
    @Volatile private var scoreScenario = ScoreScenario.VALID

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                paths += path
                apiKeyHeaders += request.getHeader("X-API-KEY")
                return when {
                    path.endsWith("/all/sports") -> MockResponse().setBody("""{"sports": []}""")
                    path.endsWith("/list/seasons/4328") -> fixture("thesportsdb_seasons.json")
                    path.startsWith("/api/v2/json/schedule/league/4328/") -> eventResponse(path)
                    path.endsWith("/livescore/all") -> scoreResponse()
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    fun provider(scenario: EventScenario): TheSportsDbProvider {
        eventScenario = scenario
        return createProvider()
    }

    fun provider(scenario: ScoreScenario): TheSportsDbProvider {
        scoreScenario = scenario
        return createProvider()
    }

    fun requestedPaths(): List<String> = paths.toList()
    fun apiKeyHeaders(): List<String?> = apiKeyHeaders.toList()
    fun updateCredentials(credentials: SportsProviderCredentials) {
        store.savedCredentials["test-account"] = credentials
    }

    fun providerAt(basePath: String): TheSportsDbProvider = createProvider(
        server.url(basePath).toString().trimEnd('/'),
    )

    private fun createProvider(
        endpointBaseUrl: String = server.url("/api/v2/json").toString().trimEnd('/'),
    ): TheSportsDbProvider {
        val descriptor = SportsProviderDescriptor(
            typeId = TheSportsDbProviderFactory.TYPE_ID,
            capabilities = setOf(SportsCapability.EVENTS, SportsCapability.LIVE_SCORES, SportsCapability.GAME_STATUS),
            auth = SportsProviderAuth.ApiKeyHeader("X-API-KEY"),
            defaultBaseUrl = TheSportsDbProviderFactory.BASE_URL,
        )
        val config = SportsProviderConfig(instanceId = "test-account", typeId = descriptor.typeId)
        return TheSportsDbProvider(
            config = config,
            descriptor = descriptor,
            store = store,
            httpClient = SportsProviderHttpClient("StreamVault-tests"),
            cache = cache,
            endpointBaseUrl = endpointBaseUrl,
            allowInsecureHttp = true,
            clock = Clock.fixed(Instant.parse("2026-10-17T18:20:00Z"), ZoneOffset.UTC),
        )
    }

    private fun eventResponse(path: String): MockResponse = when (eventScenario) {
        EventScenario.VALID -> scheduleForSeason(path)
        EventScenario.MIXED_INVALID -> {
            if (!path.endsWith("/2026-2027")) {
                MockResponse().setBody("""{"schedule": []}""")
            } else {
                val envelope = JsonParser.parseString(readFixture("thesportsdb_events.json")).asJsonObject
                envelope.getAsJsonArray("schedule").add(JsonParser.parseString(
                    """{"idEvent":"","strTimestamp":"${EventProviderContract.SECRET_MARKER}"}""",
                ))
                MockResponse().setBody(envelope.toString())
            }
        }
        EventScenario.ALL_INVALID -> fixture("thesportsdb_events_all_invalid.json")
        EventScenario.EMPTY -> MockResponse().setBody("""{"schedule": null}""")
        EventScenario.AUTH_FAILURE -> authFailure()
    }

    private fun scheduleForSeason(path: String): MockResponse =
        if (path.endsWith("/2026-2027")) fixture("thesportsdb_events.json")
        else MockResponse().setBody("""{"schedule": []}""")

    private fun scoreResponse(): MockResponse = when (scoreScenario) {
        ScoreScenario.VALID -> fixture("thesportsdb_livescores.json")
        ScoreScenario.MISSING_FIELDS -> fixture("thesportsdb_livescores_missing_fields.json")
        ScoreScenario.MIXED_INVALID -> {
            val records = JsonParser.parseString(readFixture("thesportsdb_livescores.json"))
                .asJsonObject.getAsJsonArray("livescore")
            records.add(JsonParser.parseString(
                """{"idEvent":"","updated":"${EventProviderContract.SECRET_MARKER}"}""",
            ))
            MockResponse().setBody("""{"livescore":$records}""")
        }
        ScoreScenario.AUTH_FAILURE -> authFailure()
        ScoreScenario.EMPTY -> MockResponse().setBody("""{"livescore": null}""")
    }

    private fun fixture(name: String): MockResponse = MockResponse().setBody(readFixture(name))

    private fun readFixture(name: String): String = requireNotNull(
        javaClass.getResourceAsStream("/sports/providers/$name"),
    ).bufferedReader().use { it.readText() }

    private fun authFailure() = MockResponse().setResponseCode(401)
        .setBody("server echoed ${EventProviderContract.SECRET_MARKER}")

    override fun close() = server.shutdown()
}

private class TestSportsProviderStore : SportsProviderStore {
    val savedCredentials = mutableMapOf<String, SportsProviderCredentials>()
    private val configs = mutableMapOf<String, SportsProviderConfig>()

    override suspend fun configurations(): List<SportsProviderConfig> = configs.values.toList()
    override suspend fun credentials(instanceId: String): SportsProviderCredentials? = savedCredentials[instanceId]
    override suspend fun save(config: SportsProviderConfig, credentials: SportsProviderCredentials?) {
        configs[config.instanceId] = config
        if (credentials != null) savedCredentials[config.instanceId] = credentials
    }
    override suspend fun remove(instanceId: String) {
        configs.remove(instanceId)
        savedCredentials.remove(instanceId)
    }
}

class TheSportsDbEventProviderContractTest : EventProviderContract() {
    private lateinit var server: TheSportsDbTestServer

    @Before fun start() { server = TheSportsDbTestServer() }
    @After fun stop() { server.close() }

    override val expectedEventId = "2070217"
    override val expectedKickoff: Instant = Instant.parse("2026-10-17T18:00:00Z")
    override fun provider(scenario: Scenario): EventProvider = server.provider(
        when (scenario) {
            Scenario.VALID -> EventScenario.VALID
            Scenario.MIXED_INVALID -> EventScenario.MIXED_INVALID
            Scenario.ALL_INVALID -> EventScenario.ALL_INVALID
            Scenario.EMPTY -> EventScenario.EMPTY
            Scenario.AUTH_FAILURE -> EventScenario.AUTH_FAILURE
        },
    )
}

class TheSportsDbLiveScoreProviderContractTest : LiveScoreProviderContract() {
    private lateinit var server: TheSportsDbTestServer

    @Before fun start() { server = TheSportsDbTestServer() }
    @After fun stop() { server.close() }

    override val expectedEventId = "2070217"
    override val expectedTextScore = "213/4"
    override val expectedUpdatedAt: Instant = Instant.parse("2026-10-17T18:10:00Z")
    override fun provider(scenario: Scenario): LiveScoreProvider = server.provider(
        when (scenario) {
            Scenario.VALID -> ScoreScenario.VALID
            Scenario.MISSING_FIELDS -> ScoreScenario.MISSING_FIELDS
            Scenario.MIXED_INVALID -> ScoreScenario.MIXED_INVALID
            Scenario.AUTH_FAILURE -> ScoreScenario.AUTH_FAILURE
        },
    )
}

class TheSportsDbProviderTest {
    private lateinit var server: TheSportsDbTestServer

    @Before fun start() { server = TheSportsDbTestServer() }
    @After fun stop() { server.close() }

    @Test fun `connection test uses authenticated V2 endpoint`() = runTest {
        val result = server.provider(EventScenario.VALID).testConnection(
            SportsProviderCredentials(EventProviderContract.SECRET_MARKER),
        )

        assertThat(result).isEqualTo(SportsProviderResult.Success(Unit))
        assertThat(server.requestedPaths()).containsExactly("/api/v2/json/all/sports")
        assertThat(server.apiKeyHeaders()).containsExactly(EventProviderContract.SECRET_MARKER)
    }

    @Test fun `event request selects matching season filters range and uses header auth`() = runTest {
        val provider = server.provider(EventScenario.VALID)
        val result = provider.events(ProviderEventRequest(
            from = Instant.parse("2026-10-17T00:00:00Z"),
            until = Instant.parse("2026-10-18T00:00:00Z"),
            sport = "Soccer",
            leagueId = "4328",
        )) as SportsProviderResult.Success
        val seasonRequest = server.requestedPaths().first()

        assertThat(result.value.items.map { it.providerEventId }).containsExactly("2070217")
        assertThat(result.value.items.single().competitors.map { it.role })
            .containsExactly(ProviderCompetitorRole.HOME, ProviderCompetitorRole.AWAY)
        assertThat(server.requestedPaths()).containsExactly(
            "/api/v2/json/list/seasons/4328",
            "/api/v2/json/schedule/league/4328/2025-2026",
            "/api/v2/json/schedule/league/4328/2026-2027",
        ).inOrder()
        assertThat(seasonRequest).doesNotContain(EventProviderContract.SECRET_MARKER)
    }

    @Test fun `event cache avoids repeating schedule and season requests`() = runTest {
        val provider = server.provider(EventScenario.VALID)
        val request = ProviderEventRequest(
            Instant.parse("2026-10-17T00:00:00Z"), Instant.parse("2026-10-18T00:00:00Z"), leagueId = "4328",
        )

        provider.events(request)
        provider.events(request)

        assertThat(server.requestedPaths()).hasSize(3)
    }

    @Test fun `event and season caches are partitioned by current credentials`() = runTest {
        val provider = server.provider(EventScenario.VALID)
        val request = ProviderEventRequest(
            Instant.parse("2026-10-17T00:00:00Z"), Instant.parse("2026-10-18T00:00:00Z"), leagueId = "4328",
        )

        provider.events(request)
        server.updateCredentials(SportsProviderCredentials("replacement-key"))
        provider.events(request)

        assertThat(server.requestedPaths()).hasSize(6)
        assertThat(server.apiKeyHeaders().take(3)).containsExactly(
            EventProviderContract.SECRET_MARKER,
            EventProviderContract.SECRET_MARKER,
            EventProviderContract.SECRET_MARKER,
        ).inOrder()
        assertThat(server.apiKeyHeaders().drop(3)).containsExactly(
            "replacement-key",
            "replacement-key",
            "replacement-key",
        ).inOrder()
    }

    @Test fun `live score cache is partitioned by current credentials`() = runTest {
        val provider = server.provider(ScoreScenario.VALID)
        val request = ProviderScoreRequest(setOf("2070217"))

        provider.liveScores(request)
        server.updateCredentials(SportsProviderCredentials("replacement-key"))
        provider.liveScores(request)

        assertThat(server.requestedPaths()).containsExactly(
            "/api/v2/json/livescore/all",
            "/api/v2/json/livescore/all",
        )
        assertThat(server.apiKeyHeaders()).containsExactly(EventProviderContract.SECRET_MARKER, "replacement-key")
    }

    @Test fun `response cache is partitioned by endpoint base URL`() = runTest {
        val request = ProviderEventRequest(
            Instant.parse("2026-10-17T00:00:00Z"), Instant.parse("2026-10-18T00:00:00Z"), leagueId = "4328",
        )
        server.provider(EventScenario.VALID).events(request)

        val changedEndpoint = server.providerAt("/api/v2/json/alternate")
            .events(request)

        assertThat(changedEndpoint).isInstanceOf(SportsProviderResult.Failure::class.java)
        assertThat(server.requestedPaths()).contains(
            "/api/v2/json/alternate/list/seasons/4328",
        )
    }

    @Test fun `live score cache is shared while each request filters by external event ID`() = runTest {
        val provider = server.provider(ScoreScenario.VALID)
        val live = provider.liveScores(ProviderScoreRequest(setOf("2070217"))) as SportsProviderResult.Success
        val other = provider.liveScores(ProviderScoreRequest(setOf("other-event"))) as SportsProviderResult.Success

        assertThat(live.value.items.single().providerEventId).isEqualTo("2070217")
        assertThat(live.value.items.single().status?.state).isEqualTo(ProviderGameState.LIVE)
        assertThat(other.value.items).isEmpty()
        assertThat(server.requestedPaths()).containsExactly("/api/v2/json/livescore/all")
    }

    @Test fun `null live score array is a valid empty response`() = runTest {
        val result = server.provider(ScoreScenario.EMPTY).liveScores(
            ProviderScoreRequest(setOf("2070217")),
        )

        assertThat(result).isEqualTo(SportsProviderResult.Success(ProviderBatch<ProviderScore>(emptyList())))
    }

    @Test fun `response cache expires entries and stays bounded`() {
        val cache = TheSportsDbResponseCache()
        val now = Instant.parse("2026-10-17T18:00:00Z")
        cache.put("score", "cached", Duration.ofMinutes(2), now)

        assertThat(cache.get<String>("score", now.plusSeconds(119))).isEqualTo("cached")
        assertThat(cache.get<String>("score", now.plusSeconds(120))).isNull()
    }
}
