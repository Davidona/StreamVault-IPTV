package com.streamvault.data.sports.custom

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.sports.provider.SportsProviderHttpClient
import com.streamvault.domain.sports.provider.*
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Before
import org.junit.Test

class CustomSportsProviderTest {
    private val server = MockWebServer()
    private lateinit var httpClient: SportsProviderHttpClient
    private lateinit var store: TestProviderStore

    @Before
    fun start() {
        val certificate = HeldCertificate.Builder()
            .commonName("localhost")
            .addSubjectAlternativeName("localhost")
            .build()
        val serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(certificate)
            .build()
        server.useHttps(serverCertificates.sslSocketFactory(), false)
        server.start()
        val trustedCertificates = HandshakeCertificates.Builder()
            .addTrustedCertificate(certificate.certificate)
            .build()
        val testClient = OkHttpClient.Builder()
            .sslSocketFactory(trustedCertificates.sslSocketFactory(), trustedCertificates.trustManager)
            .build()
        httpClient = SportsProviderHttpClient("StreamVault-custom-test", clientOverride = testClient)
        store = TestProviderStore()
    }

    @After
    fun stop() {
        server.shutdown()
    }

    @Test
    fun `configured event endpoint maps provider records and keeps credentials out of URL`() = runTest {
        val config = config(eventOnlyDefinition())
        store.credentialsByInstance[config.instanceId] = SportsProviderCredentials("private-token")
        server.enqueue(MockResponse().setBody(eventPayload()))
        val factory = CustomSportsProviderFactory(httpClient, store)
        val provider = SportsProviderRegistry(listOf(factory)).create(config) as EventProvider

        val result = provider.events(
            ProviderEventRequest(
                from = Instant.parse("2026-10-17T00:00:00Z"),
                until = Instant.parse("2026-10-18T00:00:00Z"),
                leagueId = "league & one",
            ),
        ) as SportsProviderResult.Success

        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertThat(request.requestUrl!!.encodedPath).isEqualTo("/events")
        assertThat(request.requestUrl!!.queryParameter("leagueId")).isEqualTo("league & one")
        assertThat(request.getHeader("X-SPORTS-KEY")).isEqualTo("private-token")
        assertThat(request.requestUrl.toString()).doesNotContain("private-token")
        val event = result.value.items.single()
        assertThat(event.providerEventId).isEqualTo("event-1")
        assertThat(event.eventStart).isEqualTo(Instant.parse("2026-10-17T19:30:00Z"))
        assertThat(event.sport).isEqualTo("Football")
        assertThat(event.league).isEqualTo(ProviderLeague("Premier", "league-1"))
        assertThat(event.competitors.map { it.name }).containsExactly("North", "South").inOrder()
        assertThat(event.status?.state).isEqualTo(ProviderGameState.LIVE)
    }

    @Test
    fun `scores endpoint batches IDs filters unrelated rows and maps clock and status`() = runTest {
        val config = config(withScoresDefinition())
        store.credentialsByInstance[config.instanceId] = SportsProviderCredentials("private-token")
        server.enqueue(MockResponse().setBody(
            """{"data":[{"id":"event-1","home":{"name":"North","score":"2"},"away":{"name":"South","score":"1"},"state":"in_progress","clock":"04:20","period":"2","updated":"2026-10-17T20:00:00Z"},{"id":"other","home":{"name":"Other","score":"0"},"away":{"name":"Guest","score":"0"}}]}""",
        ))
        val provider = SportsProviderRegistry(listOf(CustomSportsProviderFactory(httpClient, store)))
            .create(config) as LiveScoreProvider

        val result = provider.liveScores(ProviderScoreRequest(setOf("event-1"))) as SportsProviderResult.Success

        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertThat(request.requestUrl!!.queryParameter("ids")).isEqualTo("event-1")
        assertThat(result.value.items).hasSize(1)
        val score = result.value.items.single()
        assertThat(score.providerEventId).isEqualTo("event-1")
        assertThat(score.scores.map { it.value }).containsExactly("2", "1").inOrder()
        assertThat(score.status?.state).isEqualTo(ProviderGameState.LIVE)
        assertThat(score.status?.clock).isEqualTo("04:20")
        assertThat(score.status?.period).isEqualTo("2")
        assertThat(score.updatedAt).isEqualTo(Instant.parse("2026-10-17T20:00:00Z"))
    }

    @Test
    fun `configured capability set follows available mappings`() {
        val factory = CustomSportsProviderFactory(httpClient, store)
        val registry = SportsProviderRegistry(listOf(factory))
        val eventsOnly = config(eventOnlyDefinition())
        val eventsAndScores = config(withScoresDefinition(), id = "custom-with-scores")

        assertThat(registry.capabilities(eventsOnly)).containsExactly(SportsCapability.EVENTS)
        assertThat(registry.eligible(listOf(eventsOnly), SportsCapability.LIVE_SCORES)).isEmpty()
        assertThat(registry.eligible(listOf(eventsOnly), SportsCapability.EVENTS))
            .containsExactly(eventsOnly)
        assertThat(registry.capabilities(eventsAndScores))
            .containsExactly(SportsCapability.EVENTS, SportsCapability.LIVE_SCORES)
    }

    @Test
    fun `auth modes are declarative and malformed configurations fail closed`() {
        val modes = listOf(
            "\"mode\":\"none\"" to SportsProviderAuth.None,
            "\"mode\":\"api_key_header\",\"parameterName\":\"X-Custom-Key\"" to
                SportsProviderAuth.ApiKeyHeader("X-Custom-Key"),
            "\"mode\":\"api_key_query\",\"parameterName\":\"key\"" to
                SportsProviderAuth.ApiKeyQuery("key"),
            "\"mode\":\"bearer\"" to SportsProviderAuth.Bearer,
            "\"mode\":\"basic\"" to SportsProviderAuth.Basic,
        )
        modes.forEach { (authJson, expected) ->
            assertThat(CustomSportsProviderDefinition.parse(definition(authJson)).auth).isEqualTo(expected)
        }

        val invalidDefinitions = listOf(
            definition("\"mode\":\"unsupported\""),
            eventOnlyDefinition().replace("/events?from={fromDate}", "https://outside.invalid/events"),
            eventOnlyDefinition().replace("\"home.name\"", "\"home..name\""),
            eventOnlyDefinition().replace("\"events\":", "\"script\":\"run()\",\"events\":"),
        )
        invalidDefinitions.forEach { source ->
            assertThat(runCatching { CustomSportsProviderDefinition.parse(source) }.isFailure).isTrue()
        }
    }

    @Test
    fun `endpoint expansion encodes values and remains on the configured origin`() {
        val base = "https://sports.example/root".toHttpUrlOrNull()!!
        val url = buildCustomSportsProviderUrl(
            base,
            "/events?league={leagueId}&from={fromDate}",
            mapOf("leagueId" to "division/A & B", "fromDate" to "2026-10-17T00:00:00Z"),
        )

        assertThat(url?.host).isEqualTo("sports.example")
        assertThat(url?.encodedPath).isEqualTo("/root/events")
        assertThat(url?.queryParameter("league")).isEqualTo("division/A & B")
        assertThat(url?.encodedPath).isEqualTo("/root/events")
        assertThat(buildCustomSportsProviderUrl(base, "//outside.invalid/events", emptyMap())).isNull()
        assertThat(buildCustomSportsProviderUrl(base, "/../outside", emptyMap())).isNull()
        assertThat(buildCustomSportsProviderUrl(base, "/events?token={missing}", emptyMap())).isNull()
    }

    @Test
    fun `event count limit rejects an oversized mapped array`() {
        val definition = CustomSportsProviderDefinition.parse(eventOnlyDefinition())
        val row = Json.parseToJsonElement(
            """{"id":"event","start":"2026-10-17T19:30:00Z","home":{"name":"North"},"away":{"name":"South"}}""",
        )
        val body = buildJsonObject {
            put("data", JsonObject(mapOf("events" to JsonArray(List(2_001) { row }))))
        }

        val result = CustomSportsProviderJsonMapper.events(definition.events, body)
        assertThat(result).isEqualTo(SportsProviderResult.Failure(SportsProviderError.RESPONSE_TOO_LARGE))
    }

    private fun config(
        definition: String,
        id: String = "custom-account",
    ) = SportsProviderConfig(
        instanceId = id,
        typeId = CUSTOM_SPORTS_PROVIDER_TYPE_ID,
        baseUrl = server.url("/").newBuilder().host("localhost").build().toString().trimEnd('/'),
        customDefinitionJson = definition,
    )

    private fun eventOnlyDefinition(auth: String = "\"mode\":\"api_key_header\",\"parameterName\":\"X-SPORTS-KEY\"") =
        """{"name":"My Sports API","auth":{$auth},"events":{"endpoint":"/events?from={fromDate}&to={toDate}&leagueId={leagueId}","items":"data.events","id":"id","sport":"sport","leagueName":"league.name","leagueId":"league.id","title":"title","start":"start","startFormat":"ISO_OFFSET","homeName":"home.name","homeId":"home.id","awayName":"away.name","awayId":"away.id","status":"status","clock":"clock","period":"period"}}"""

    private fun withScoresDefinition() = eventOnlyDefinition().dropLast(1) +
        """, "scores":{"endpoint":"/scores?ids={eventIds}","items":"data","eventId":"id","homeName":"home.name","homeScore":"home.score","awayName":"away.name","awayScore":"away.score","status":"state","clock":"clock","period":"period","updatedAt":"updated","updatedAtFormat":"ISO_OFFSET"}}"""

    private fun definition(authFields: String) = eventOnlyDefinition("$authFields")

    private fun eventPayload() =
        """{"data":{"events":[{"id":"event-1","sport":"Football","league":{"name":"Premier","id":"league-1"},"title":"North v South","start":"2026-10-17T19:30:00Z","home":{"name":"North","id":"north"},"away":{"name":"South","id":"south"},"status":"in_progress","clock":"12:34","period":"1"}]}}"""

    private class TestProviderStore : SportsProviderStore {
        val credentialsByInstance = mutableMapOf<String, SportsProviderCredentials>()
        override suspend fun configurations(): List<SportsProviderConfig> = emptyList()
        override suspend fun credentials(instanceId: String): SportsProviderCredentials? =
            credentialsByInstance[instanceId]
        override suspend fun save(config: SportsProviderConfig, credentials: SportsProviderCredentials?) = Unit
        override suspend fun remove(instanceId: String) = Unit
    }
}
