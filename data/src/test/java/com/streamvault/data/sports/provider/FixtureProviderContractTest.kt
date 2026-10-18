package com.streamvault.data.sports.provider

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.streamvault.data.sports.provider.contract.EventProviderContract
import com.streamvault.data.sports.provider.contract.LiveScoreProviderContract
import com.streamvault.domain.sports.provider.*
import java.time.Instant
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before

/** Test-only adapter exercises the actual HTTP/mapping boundary without registering a provider. */
private class FixtureProviderServer : AutoCloseable {
    private val server = MockWebServer()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                if (path.endsWith("AUTH_FAILURE")) return MockResponse().setResponseCode(401)
                    .setBody("server echoed ${EventProviderContract.SECRET_MARKER}")
                val fixture = if (path.startsWith("/events/")) "events" else "scores"
                val body = when {
                    path.endsWith("EMPTY") -> "[]"
                    path.endsWith("ALL_INVALID") -> """[{"id":""}]"""
                    path.endsWith("MISSING_FIELDS") -> """[{"id":"event-1"}]"""
                    path.endsWith("MIXED_INVALID") -> JsonParser.parseString(fixture(fixture)).asJsonArray.apply {
                        add(JsonObject().apply {
                            addProperty("id", "")
                            addProperty("start", EventProviderContract.SECRET_MARKER)
                        })
                    }.toString()
                    else -> fixture(fixture)
                }
                return MockResponse().setBody(body)
            }
        }
        server.start()
    }

    fun adapter(scenario: String): FixtureProvider = FixtureProvider(server, scenario)
    override fun close() { server.shutdown() }

    private fun fixture(name: String): String = requireNotNull(
        javaClass.getResourceAsStream("/sports/providers/$name.json")
    ).bufferedReader().use { it.readText() }
}

private class FixtureProvider(
    private val server: MockWebServer,
    private val scenario: String,
) : EventProvider, LiveScoreProvider {
    override val descriptor = SportsProviderDescriptor(
        "contract-fixture", setOf(SportsCapability.EVENTS, SportsCapability.LIVE_SCORES), SportsProviderAuth.Bearer,
    )
    override val instanceId = "fixture-account"
    private val client = SportsProviderHttpClient("StreamVault-contract-test")
    private val credentials = SportsProviderCredentials(EventProviderContract.SECRET_MARKER)

    override suspend fun events(request: ProviderEventRequest): SportsProviderResult<ProviderBatch<ProviderEvent>> =
        requestBatch("events") { obj ->
            ProviderEvent(
                providerEventId = obj.get("id").asString,
                title = obj.get("title")?.asString,
                sport = obj.get("sport")?.asString,
                eventStart = obj.get("start")?.asString?.let(::timestamp),
                session = obj.get("session")?.asString,
                competitors = obj.getAsJsonArray("competitors")?.map { item ->
                    val competitor = item.asJsonObject
                    ProviderCompetitor(
                        name = competitor.get("name").asString,
                        id = competitor.get("id")?.asString,
                        role = competitor.get("role")?.asString?.let(ProviderCompetitorRole::valueOf)
                            ?: ProviderCompetitorRole.PARTICIPANT,
                    )
                }.orEmpty(),
            )
        }

    override suspend fun liveScores(request: ProviderScoreRequest): SportsProviderResult<ProviderBatch<ProviderScore>> =
        requestBatch("scores") { obj ->
            ProviderScore(
                providerEventId = obj.get("id").asString,
                scores = obj.getAsJsonArray("scores")?.map { item ->
                    val score = item.asJsonObject
                    ProviderCompetitorScore(ProviderCompetitor(score.get("name").asString), score.get("value")?.asString)
                }.orEmpty(),
                status = obj.get("status")?.asString?.let {
                    ProviderGameStatus(
                        state = if (it == "in_progress") ProviderGameState.LIVE else ProviderGameState.UNKNOWN,
                        clock = obj.get("clock")?.asString,
                        period = obj.get("period")?.asString,
                    )
                },
                updatedAt = obj.get("updatedAt")?.asString?.let(::timestamp),
            )
        }

    private suspend fun <T : Any> requestBatch(
        kind: String,
        map: (JsonObject) -> T,
    ): SportsProviderResult<ProviderBatch<T>> {
        val response = client.get(server.url("/$kind/$scenario"), descriptor.auth, credentials, true) {
            JsonParser.parseString(it).asJsonArray
        }
        return when (response) {
            is SportsProviderResult.Failure -> response
            is SportsProviderResult.Success<JsonArray> -> mapProviderItems(response.value) { map(it.asJsonObject) }
        }
    }

    private fun timestamp(value: String): Instant = parseProviderTimestamp(value, ProviderTimestampFormat.ISO_OFFSET)
}

class FixtureEventProviderContractTest : EventProviderContract() {
    private lateinit var server: FixtureProviderServer
    @Before fun start() { server = FixtureProviderServer() }
    @After fun stop() { server.close() }
    override val expectedEventId = "event-1"
    override val expectedKickoff: Instant = Instant.parse("2026-10-17T18:00:00Z")
    override fun provider(scenario: Scenario): EventProvider = server.adapter(scenario.name)
}

class FixtureLiveScoreProviderContractTest : LiveScoreProviderContract() {
    private lateinit var server: FixtureProviderServer
    @Before fun start() { server = FixtureProviderServer() }
    @After fun stop() { server.close() }
    override val expectedEventId = "event-1"
    override val expectedTextScore = "213/4"
    override val expectedUpdatedAt: Instant = Instant.parse("2026-10-17T18:10:00Z")
    override fun provider(scenario: Scenario): LiveScoreProvider = server.adapter(scenario.name)
}
