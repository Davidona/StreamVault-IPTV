package com.streamvault.data.sports.openligadb

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.sports.provider.SportsProviderHttpClient
import com.streamvault.domain.sports.provider.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.Duration
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

class OpenLigaDbProviderTest {
    private lateinit var server: MockWebServer
    private lateinit var responses: ConcurrentLinkedQueue<MockResponse>
    private lateinit var requests: CopyOnWriteArrayList<RecordedRequest>
    private lateinit var cache: OpenLigaDbRawResponseCache
    private lateinit var limiter: OpenLigaDbRequestLimiter
    private val now = Instant.parse("2026-10-17T12:00:00Z")

    @Before
    fun setUp() {
        server = MockWebServer()
        responses = ConcurrentLinkedQueue()
        requests = CopyOnWriteArrayList()
        cache = OpenLigaDbRawResponseCache()
        limiter = OpenLigaDbRequestLimiter()
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
    fun `descriptor exposes football data and production waits for linked ODbL attribution`() {
        val factory = factory()
        assertThat(factory.descriptor.auth).isEqualTo(SportsProviderAuth.None)
        assertThat(factory.descriptor.defaultBaseUrl).isEqualTo(OpenLigaDbProviderFactory.BASE_URL)
        assertThat(factory.descriptor.capabilities).containsExactly(
            SportsCapability.EVENTS,
            SportsCapability.LIVE_SCORES,
            SportsCapability.GAME_STATUS,
            SportsCapability.STANDINGS,
        )
        assertThat(factory.descriptor.compliance?.attribution?.format)
            .isEqualTo(ProviderAttributionFormat.ODBL)
        assertThat(factory.descriptor.compliance?.attribution?.licenseUrl)
            .isEqualTo(OpenLigaDbProviderFactory.ODBL_URL)
        assertThat(factory.descriptor.compliance?.releaseGate).isEqualTo(ProviderReleaseGate.REQUIRES_ATTRIBUTION)
        assertThat(factory.descriptor.capabilities).doesNotContain(SportsCapability.ARTWORK)
        assertThat(SportsProviderRegistry(listOf(factory)).descriptors).isEmpty()
        assertThat(SportsProviderRegistry(
            listOf(factory), SportsProviderReleasePolicy(attributionSupportedTypes = setOf("openligadb")),
        ).descriptors).containsExactly(factory.descriptor)
    }

    @Test
    fun `connection probe validates a configured league against available groups`() = runTest {
        val configured = config("bl1:2026")
        assertThat(configured.eventLeagueIds).containsExactly("bl1:2026")
        assertThat(factory().capabilitiesFor(configured)).isNotEmpty()
        responses += fixture("openligadb_groups.json")
        val tester = provider() as SportsProviderConnectionTester

        assertThat(tester.testConnection(null)).isEqualTo(SportsProviderResult.Success(Unit))
        val request = requests.single()
        assertThat(request.requestUrl?.encodedPath).isEqualTo("/getavailablegroups/bl1/2026")
        assertThat(request.getHeader("Authorization")).isNull()
        assertThat(request.requestUrl.toString()).doesNotContain("?")
    }

    @Test
    fun `season fixtures are scoped by date and scores and status reuse the short lived raw cache`() = runTest {
        responses += fixture("openligadb_matches.json")
        val provider = provider()
        val request = ProviderEventRequest(
            from = Instant.parse("2026-10-17T10:00:00Z"),
            until = Instant.parse("2026-10-17T15:00:00Z"),
            sport = "football",
            leagueId = "bl1:2026",
        )

        val result = (provider as EventProvider).events(request)
        assertThat(result).isInstanceOf(SportsProviderResult.Success::class.java)
        val batch = (result as SportsProviderResult.Success).value
        assertThat(batch.items.map(ProviderEvent::providerEventId)).containsExactly("507806", "507807").inOrder()
        assertThat(batch.rejectedItemCount).isEqualTo(0)
        val final = batch.items.first()
        assertThat(final.title).isNull()
        assertThat(final.competitors.map(ProviderCompetitor::name))
            .containsExactly("Bayern München", "Borussia Dortmund").inOrder()
        assertThat(final.sport).isEqualTo("Football")
        assertThat(final.league).isEqualTo(ProviderLeague("1. Bundesliga", "bl1", "Football"))
        assertThat(final.eventStart).isEqualTo(Instant.parse("2026-10-17T10:30:00Z"))
        assertThat(final.status?.state).isEqualTo(ProviderGameState.FINAL)
        assertThat(final.competitors.map(ProviderCompetitor::role))
            .containsExactly(ProviderCompetitorRole.HOME, ProviderCompetitorRole.AWAY).inOrder()

        val ids = ProviderScoreRequest(setOf("507806", "507807"))
        val scoreBatch = ((provider as LiveScoreProvider).liveScores(ids) as SportsProviderResult.Success).value
        assertThat(scoreBatch.items.single().scores.map(ProviderCompetitorScore::value))
            .containsExactly("5", "4").inOrder()
        assertThat(scoreBatch.rejectedItemCount).isEqualTo(1)
        val statuses = ((provider as GameStatusProvider).gameStatuses(ids) as SportsProviderResult.Success).value
        assertThat(statuses.items.map { it.status.state })
            .containsExactly(ProviderGameState.FINAL, ProviderGameState.SCHEDULED).inOrder()
        assertThat(requests).hasSize(1)
        assertThat(requests.single().requestUrl?.encodedPath).isEqualTo("/getmatchdata/bl1/2026")
    }

    @Test
    fun `standings map only localized football columns and ignore provider artwork URLs`() = runTest {
        responses += fixture("openligadb_table.json")
        val result = (provider() as StandingsProvider)
            .standings(ProviderStandingsRequest("bl1", "2026"))

        assertThat(result).isInstanceOf(SportsProviderResult.Success::class.java)
        val table = (result as SportsProviderResult.Success).value
        assertThat(table.league).isEqualTo(ProviderLeague("bl1", "bl1", "Football"))
        assertThat(table.season).isEqualTo("2026")
        assertThat(table.groups.single().columns.map(ProviderStandingColumn::key)).containsExactly(
            "played", "won", "drawn", "lost", "goals_for", "goals_against", "goal_difference", "points",
        ).inOrder()
        assertThat(table.groups.single().rows.map { it.competitor.name })
            .containsExactly("Bayern München", "Borussia Dortmund").inOrder()
        assertThat(table.groups.single().rows.first().values).containsEntry("goal_difference", "14")
        assertThat(table.groups.single().rows.first().values).doesNotContainKey("teamIconUrl")
        assertThat(requests.single().requestUrl?.encodedPath).isEqualTo("/getbltable/bl1/2026")
    }

    @Test
    fun `invalid scopes and request limits fail before HTTP`() = runTest {
        val factory = factory()
        assertThat(factory.capabilitiesFor(config("bl1:2026", "bl1:2027")))
            .containsExactly(SportsCapability.EVENTS, SportsCapability.LIVE_SCORES,
                SportsCapability.GAME_STATUS, SportsCapability.STANDINGS)
        assertThat(factory.capabilitiesFor(config("bl1:2101"))).isEmpty()
        assertThat(factory.capabilitiesFor(config("bad_scope"))).isEmpty()
        assertThat(factory.capabilitiesFor(config(
            "bl1:2026", "bl2:2026", "bl3:2026", "dfb:2026", "ucl:2026", "uel:2026",
        ))).isEmpty()

        val unconfigured = (provider(config("bl1:2026")) as StandingsProvider)
            .standings(ProviderStandingsRequest("bl2", "2026"))
        val tooWide = (provider(config("bl1:2026")) as EventProvider).events(ProviderEventRequest(
            now, now.plus(Duration.ofDays(32)), leagueId = "bl1:2026",
        ))
        assertThat(unconfigured).isEqualTo(
            SportsProviderResult.Failure(SportsProviderError.INVALID_CONFIGURATION),
        )
        assertThat(tooWide).isEqualTo(SportsProviderResult.Failure(SportsProviderError.RESPONSE_TOO_LARGE))
        assertThat(requests).isEmpty()
    }

    @Test
    fun `raw cache bounds size expires entries and keeps account revision in its key`() {
        val rawCache = OpenLigaDbRawResponseCache()
        val expires = now.plusSeconds(60)
        rawCache.put("account|1|matches", "{\"raw\":true}", Duration.ofSeconds(60), now)
        assertThat(rawCache.get("account|1|matches", now)).isEqualTo("{\"raw\":true}")
        assertThat(rawCache.get("account|2|matches", now)).isNull()
        assertThat(rawCache.get("account|1|matches", expires)).isNull()
        rawCache.put("huge", "x".repeat(4 * 1024 * 1024 + 1), Duration.ofMinutes(1), now)
        assertThat(rawCache.get("huge", now)).isNull()
    }

    private fun factory() = OpenLigaDbProviderFactory(httpClient(), cache, limiter)

    private fun provider(config: SportsProviderConfig = config("bl1:2026")) = OpenLigaDbProvider(
        config = config,
        descriptor = factory().descriptor,
        httpClient = httpClient(),
        cache = cache,
        limiter = limiter,
        endpointBaseUrl = server.url("/").toString().trimEnd('/'),
        clock = Clock.fixed(now, ZoneOffset.UTC),
        minimumRequestIntervalMillis = 0,
        allowInsecureHttpForTests = true,
    )

    private fun httpClient() = SportsProviderHttpClient(
        userAgent = "StreamVault-test",
        clock = Clock.fixed(now, ZoneOffset.UTC),
    )

    private fun config(vararg scopes: String) = SportsProviderConfig(
        "openligadb-account", OpenLigaDbProviderFactory.TYPE_ID, eventLeagueIds = scopes.toList(),
    )

    private fun fixture(name: String): MockResponse {
        val body = requireNotNull(javaClass.classLoader?.getResourceAsStream("sports/providers/$name"))
            .bufferedReader().use { it.readText() }
        return MockResponse().setBody(body).addHeader("Content-Type", "application/json")
    }
}
