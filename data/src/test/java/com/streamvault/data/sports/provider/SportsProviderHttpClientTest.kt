package com.streamvault.data.sports.provider

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.sports.provider.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import okhttp3.Credentials
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import okio.GzipSink
import okio.buffer
import org.junit.After
import org.junit.Before
import org.junit.Test

class SportsProviderHttpClientTest {
    private val server = MockWebServer()
    private val now = Instant.parse("2026-10-17T00:00:00Z")
    private val client = SportsProviderHttpClient("StreamVault-test", Clock.fixed(now, ZoneOffset.UTC), 128)
    private val credentials = SportsProviderCredentials("private-key", "alice")

    @Before fun start() { server.start() }
    @After fun stop() { server.shutdown() }

    @Test fun `auth modes inject credentials only where declared`() = runTest {
        val modes = listOf(
            SportsProviderAuth.None,
            SportsProviderAuth.ApiKeyHeader("X-Api-Key"),
            SportsProviderAuth.ApiKeyQuery("key"),
            SportsProviderAuth.Bearer,
            SportsProviderAuth.Basic,
            SportsProviderAuth.OAuth2("reviewed-adapter"),
        )
        for (mode in modes) {
            server.enqueue(MockResponse().setBody("{}"))
            val result = get(mode)
            assertThat(result).isEqualTo(SportsProviderResult.Success("{}"))
            val request = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertThat(request.getHeader("User-Agent")).isEqualTo("StreamVault-test")
            assertThat(request.getHeader("Accept")).isEqualTo("application/json")
            assertThat(request.getHeader("X-Api-Key"))
                .isEqualTo(if (mode is SportsProviderAuth.ApiKeyHeader) "private-key" else null)
            assertThat(request.requestUrl?.queryParameter("key"))
                .isEqualTo(if (mode is SportsProviderAuth.ApiKeyQuery) "private-key" else null)
            val expected = when (mode) {
                SportsProviderAuth.Bearer, is SportsProviderAuth.OAuth2 -> "Bearer private-key"
                SportsProviderAuth.Basic -> Credentials.basic("alice", "private-key")
                else -> null
            }
            assertThat(request.getHeader("Authorization")).isEqualTo(expected)
        }
    }

    @Test fun `HTTP failures discard sensitive body and classify without retrying`() = runTest {
        val statuses = mapOf(
            401 to SportsProviderError.AUTHENTICATION,
            403 to SportsProviderError.AUTHENTICATION,
            429 to SportsProviderError.RATE_LIMITED,
            503 to SportsProviderError.SERVER,
            404 to SportsProviderError.HTTP,
        )
        statuses.forEach { (code, category) ->
            server.enqueue(MockResponse().setResponseCode(code).setBody("private-key"))
            assertThat(get()).isEqualTo(SportsProviderResult.Failure(category, code))
        }
        assertThat(server.requestCount).isEqualTo(statuses.size)
    }

    @Test fun `Retry-After supports seconds and HTTP date and ignores invalid values`() = runTest {
        listOf(
            "90" to now.plusSeconds(90),
            "Sat, 17 Oct 2026 00:02:00 GMT" to now.plusSeconds(120),
            "-1" to null,
            "private-key" to null,
            "9223372036854775807" to null,
        ).forEach { (header, expected) ->
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", header))
            assertThat(get()).isEqualTo(SportsProviderResult.Failure(SportsProviderError.RATE_LIMITED, 429, expected))
        }
    }

    @Test fun `allow-listed request quota headers are observed on success and failure`() = runTest {
        val observed = mutableListOf<SportsProviderResponseMetadata>()
        server.enqueue(MockResponse().setBody("{}").setHeader("x-ratelimit-requests-limit", "100")
            .setHeader("x-ratelimit-requests-remaining", "97"))
        val success = client.get(
            server.url("/"), SportsProviderAuth.ApiKeyHeader("x-rapidapi-key"), credentials,
            allowInsecureHttp = true,
            onResponseMetadata = observed::add,
        ) { it }
        assertThat(success).isEqualTo(SportsProviderResult.Success("{}"))

        server.enqueue(MockResponse().setResponseCode(429).setHeader("x-ratelimit-requests-remaining", "0")
            .setBody("private-key"))
        val limited = client.get(
            server.url("/"), SportsProviderAuth.ApiKeyHeader("x-rapidapi-key"), credentials,
            allowInsecureHttp = true,
            onResponseMetadata = observed::add,
        ) { it }
        assertThat(limited).isEqualTo(SportsProviderResult.Failure(SportsProviderError.RATE_LIMITED, 429))
        assertThat(observed).containsExactly(
            SportsProviderResponseMetadata(requestLimit = 100, requestsRemaining = 97),
            SportsProviderResponseMetadata(requestsRemaining = 0),
        ).inOrder()
        assertThat(observed.toString()).doesNotContain("private-key")
    }

    @Test fun `football data quota headers are normalized and reset seconds provide retry time`() = runTest {
        val observed = mutableListOf<SportsProviderResponseMetadata>()
        server.enqueue(MockResponse().setResponseCode(429)
            .setHeader("X-RequestsAvailable", "0")
            .setHeader("X-RequestCounter-Reset", "45")
            .setBody("private-key"))

        val result = client.get(
            server.url("/"), SportsProviderAuth.ApiKeyHeader("X-Auth-Token"), credentials,
            allowInsecureHttp = true,
            onResponseMetadata = observed::add,
        ) { it }

        assertThat(result).isEqualTo(SportsProviderResult.Failure(
            SportsProviderError.RATE_LIMITED, 429, now.plusSeconds(45),
        ))
        assertThat(observed).containsExactly(SportsProviderResponseMetadata(
            requestsRemaining = 0, resetAfterSeconds = 45,
        ))
    }

    @Test fun `redirect cannot forward auth to another endpoint`() = runTest {
        val destination = MockWebServer()
        destination.start()
        try {
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", destination.url("/steal")))
            assertThat(get(SportsProviderAuth.ApiKeyQuery("key")))
                .isEqualTo(SportsProviderResult.Failure(SportsProviderError.HTTP, 302))
            assertThat(destination.requestCount).isEqualTo(0)
        } finally { destination.shutdown() }
    }

    @Test fun `known length chunked and gzip response limits apply before parsing`() = runTest {
        val oversized = "x".repeat(129)
        val compressed = Buffer()
        GzipSink(compressed).buffer().use { it.writeUtf8(oversized) }
        listOf(
            MockResponse().setBody(oversized),
            MockResponse().setChunkedBody(oversized, 10),
            MockResponse().setBody(compressed).setHeader("Content-Encoding", "gzip"),
        ).forEach { response ->
            server.enqueue(response)
            var decoded = false
            val result = client.get(server.url("/"), SportsProviderAuth.None, null, true) {
                decoded = true
                it
            }
            assertThat(result).isEqualTo(SportsProviderResult.Failure(SportsProviderError.RESPONSE_TOO_LARGE))
            assertThat(decoded).isFalse()
        }
    }

    @Test fun `exact response limit is accepted`() = runTest {
        server.enqueue(MockResponse().setBody("x".repeat(128)))
        assertThat(get()).isEqualTo(SportsProviderResult.Success("x".repeat(128)))
    }

    @Test fun `parser failure never exposes source text or cause`() = runTest {
        server.enqueue(MockResponse().setBody("private-key"))
        val result = client.get(server.url("/"), SportsProviderAuth.Bearer, credentials, true) {
            error("URL contains private-key and $it")
        }
        assertThat(result).isEqualTo(SportsProviderResult.Failure(SportsProviderError.INVALID_RESPONSE))
        assertThat(result.toString()).doesNotContain("private-key")
    }

    @Test fun `parser cancellation is propagated`() = runTest {
        server.enqueue(MockResponse().setBody("{}"))
        val failure = runCatching {
            client.get(server.url("/"), SportsProviderAuth.None, null, true) {
                throw CancellationException("cancelled")
            }
        }.exceptionOrNull()
        assertThat(failure).isInstanceOf(CancellationException::class.java)
    }

    @Test fun `missing credentials and cleartext default are rejected without network calls`() = runTest {
        val missing = client.get(server.url("/"), SportsProviderAuth.Bearer, null, true) { it }
        val cleartext = client.get(server.url("/"), SportsProviderAuth.None, null) { it }
        assertThat(missing).isEqualTo(SportsProviderResult.Failure(SportsProviderError.INVALID_CONFIGURATION))
        assertThat(cleartext).isEqualTo(missing)
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test fun `cancelled request closes pending call`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val request = async { get() }
        // runTest uses a single test thread; start the request before waiting on the real server.
        testScheduler.runCurrent()
        assertThat(server.takeRequest(5, TimeUnit.SECONDS)).isNotNull()
        request.cancelAndJoin()
        assertThat(request.isCancelled).isTrue()
    }

    @Test fun `connection failure is sanitized`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        assertThat(get()).isEqualTo(SportsProviderResult.Failure(SportsProviderError.NETWORK))
    }

    private suspend fun get(auth: SportsProviderAuth = SportsProviderAuth.None) =
        client.get(server.url("/events"), auth, credentials, allowInsecureHttp = true) { it }
}
