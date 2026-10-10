package com.streamvault.data.remote.xtream

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.remote.NetworkTimeoutConfig
import com.streamvault.data.remote.http.HttpRequestProfile
import com.streamvault.data.remote.dto.XtreamSeriesInfoResponse
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.runBlocking
import com.streamvault.data.sync.runXtreamLivePipeline
import com.streamvault.data.sync.ProviderWorkflowSupersededException
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.ResponseBody
import okio.buffer
import okio.source
import org.junit.Test

class OkHttpXtreamApiServiceTest {

    @Test
    fun `bulk and metadata responses retain throttling status and retry after`() = runTest {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(429).message("Too Many Requests").header("Retry-After", "10")
                .body("".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val service = OkHttpXtreamApiService(client, json)
        val metadata = runCatching { service.getLiveCategories("https://example.test/player_api.php?action=get_live_categories") }.exceptionOrNull()
        val bulk = runCatching { service.streamLiveStreamRows("https://example.test/player_api.php?action=get_live_streams") { } }.exceptionOrNull()
        for (failure in listOf(metadata, bulk)) {
            assertThat(failure).isInstanceOf(XtreamNetworkException::class.java)
            val network = failure as XtreamNetworkException
            assertThat(network.statusCode).isEqualTo(429)
            assertThat(network.retryAfterMs).isEqualTo(10_000L)
        }
    }

    @Test
    fun `retry after accepts seconds and HTTP dates without accepting malformed values`() {
        assertThat(parseXtreamRetryAfter("3", 0)).isEqualTo(3000L)
        assertThat(parseXtreamRetryAfter("Thu, 01 Jan 1970 00:00:10 GMT", 1000)).isEqualTo(9000L)
        assertThat(parseXtreamRetryAfter("Thu, 01 Jan 1970 00:00:00 GMT", 1000)).isEqualTo(0L)
        assertThat(parseXtreamRetryAfter("-1", 0)).isNull()
        assertThat(parseXtreamRetryAfter("garbage", 0)).isNull()
    }

    @Test
    fun `pipeline writer supersession cancels a body blocked after its first row`() = runBlocking {
        withTimeout(5_000) {
            val bodyBlocked = CountDownLatch(1)
            val cancellationObserved = CountDownLatch(1)
            val prefix = ("""[{"stream_id":1,"name":"One"},""" + " ".repeat(16_384)).toByteArray()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val source = object : InputStream() {
                    var offset = 0
                    override fun read(): Int {
                        if (offset < prefix.size) return prefix[offset++].toInt()
                        bodyBlocked.countDown()
                        while (!chain.call().isCanceled()) Thread.sleep(10)
                        cancellationObserved.countDown()
                        throw IOException("Canceled body read")
                    }
                }.source().buffer()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(object : ResponseBody() {
                        override fun contentType() = "application/json".toMediaType()
                        override fun contentLength() = -1L
                        override fun source() = source
                    }).build()
            }.build()
            val service = OkHttpXtreamApiService(client, json)
            val failure = runCatching {
                runXtreamLivePipeline(1, 4096, 1, { _: com.streamvault.data.remote.dto.XtreamLiveStreamRow -> 256L },
                    read = { emit -> service.streamLiveStreamRows("https://example.test/player_api.php?action=get_live_streams", onItem = emit) },
                    consume = {
                        check(bodyBlocked.await(4, TimeUnit.SECONDS))
                        throw ProviderWorkflowSupersededException(1, 1)
                    })
            }.exceptionOrNull()
            assertThat(failure).isInstanceOf(ProviderWorkflowSupersededException::class.java)
            assertThat(cancellationObserved.count).isEqualTo(0)
        }
    }

    @Test
    fun `streamed live rows reject huge tokens before full string allocation`() = runTest {
        val prefix = """[{"stream_id":1,"name":"""".toByteArray()
        val returned = AtomicInteger()
        val source = object : InputStream() {
            var offset = 0
            override fun read(): Int {
                returned.incrementAndGet()
                return if (offset < prefix.size) prefix[offset++].toInt() else 'x'.code
            }
        }.source().buffer()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(object : ResponseBody() {
                    override fun contentType() = "application/json".toMediaType()
                    override fun contentLength() = -1L
                    override fun source() = source
                }).build()
        }.build()
        val failure = runCatching {
            OkHttpXtreamApiService(client, json).streamLiveStreamRows(
                "https://example.test/player_api.php?action=get_live_streams") { error("oversized row emitted") }
        }.exceptionOrNull()
        assertThat(failure).isInstanceOf(XtreamLiveRowTooLargeException::class.java)
        // Includes Okio/InputStreamReader/JsonReader read-ahead, not a 96 MB response.
        assertThat(returned.get()).isAtMost(XtreamLiveRowBudgetReader.MAX_ROW_CHARS + 32 * 1024)
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        coerceInputValues = true
    }

    @Test
    fun `get classifies 401 as authentication error`() = runTest {
        val service = OkHttpXtreamApiService(
            client = clientReturning(statusCode = 401, body = "{}"),
            json = json
        )

        val failure = runCatching {
            service.getLiveCategories("https://example.test/player_api.php")
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(XtreamAuthenticationException::class.java)
        assertThat(failure).hasMessageThat().contains("HTTP 401")
    }

    @Test
    fun `get classifies malformed JSON as parsing error`() = runTest {
        val service = OkHttpXtreamApiService(
            client = clientReturning(statusCode = 200, body = "{not-json}"),
            json = json
        )

        val failure = runCatching {
            service.getLiveCategories("https://example.test/player_api.php")
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(XtreamParsingException::class.java)
        assertThat(failure).hasMessageThat().contains("Malformed JSON")
    }

    @Test
    fun `get classifies transport failures as network errors`() = runTest {
        val service = OkHttpXtreamApiService(
            client = OkHttpClient.Builder()
                .addInterceptor { throw SocketTimeoutException("timed out") }
                .build(),
            json = json
        )

        val failure = runCatching {
            service.getLiveCategories("https://example.test/player_api.php")
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(XtreamNetworkException::class.java)
        assertThat(failure).hasMessageThat().contains("timed out")
    }

    @Test
    fun `streamVodStreams decodes array incrementally`() = runTest {
        val service = OkHttpXtreamApiService(
            client = clientReturning(
                statusCode = 200,
                body = """
                    [
                      {"stream_id": "101", "name": "Movie One", "container_extension": "mp4"},
                      {"stream_id": "102", "name": "Movie Two", "container_extension": "mkv"}
                    ]
                """.trimIndent()
            ),
            json = json
        )
        val seenIds = mutableListOf<Long>()

        val count = service.streamVodStreams("https://example.test/player_api.php?action=get_vod_streams") { item ->
            seenIds += item.streamId
        }

        assertThat(count).isEqualTo(2)
        assertThat(seenIds).containsExactly(101L, 102L).inOrder()
    }

    @Test
    fun `streamLiveStreamRows decodes thin live rows incrementally`() = runTest {
        val service = OkHttpXtreamApiService(
            client = clientReturning(
                statusCode = 200,
                body = """
                    [
                      {
                        "stream_id": "101",
                        "name": "Live One",
                        "category_ids": ["10"],
                        "direct_source": "https://cdn.example.test/live/101/master.m3u8?token=abc",
                        "rating": "9.1"
                      },
                      {
                        "stream_id": "102",
                        "name": "Live Two",
                        "container_extension": "ts",
                        "cover_big": "https://img.example.test/cover.jpg"
                      }
                    ]
                """.trimIndent()
            ),
            json = json
        )
        val seen = mutableListOf<Pair<Long, String>>()

        val count = service.streamLiveStreamRows("https://example.test/player_api.php?action=get_live_streams") { row ->
            seen += row.streamId to row.name
        }

        assertThat(count).isEqualTo(2)
        assertThat(seen).containsExactly(101L to "Live One", 102L to "Live Two").inOrder()
    }

    @Test
    fun `streamLiveStreamRows cancels the underlying call when coroutine times out`() = runTest {
        val requestStarted = CountDownLatch(1)
        val cancellationObserved = CountDownLatch(1)
        val service = OkHttpXtreamApiService(
            client = OkHttpClient.Builder()
                .addInterceptor(Interceptor { chain ->
                    requestStarted.countDown()
                    while (!chain.call().isCanceled()) {
                        Thread.sleep(10)
                    }
                    cancellationObserved.countDown()
                    throw IOException("Canceled")
                })
                .build(),
            json = json
        )

        val requestScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val requestJob = requestScope.launch {
            service.streamLiveStreamRows(
                "https://example.test/player_api.php?action=get_live_streams&category_id=7"
            ) { }
        }

        assertThat(requestStarted.await(5, TimeUnit.SECONDS)).isTrue()
        requestJob.cancelAndJoin()
        requestScope.cancel()

        assertThat(requestJob.isCancelled).isTrue()
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            assertThat(cancellationObserved.await(5, TimeUnit.SECONDS)).isTrue()
        }
    }

    @Test
    fun `stream rejects trailing documents after emitting valid rows`() = runTest {
        val service = OkHttpXtreamApiService(clientReturning(200,
            """[{"stream_id":101,"name":"One"}] [{"stream_id":102,"name":"Two"}]"""), json)
        val seen = mutableListOf<Long>()
        val failure = runCatching {
            service.streamLiveStreamRows("https://example.test/player_api.php?action=get_live_streams") { seen += it.streamId }
        }.exceptionOrNull()
        assertThat(failure).isInstanceOf(XtreamParsingException::class.java)
        assertThat(seen).containsExactly(101L)
    }

    @Test
    fun `cancellation interrupts body reads after response headers have arrived`() = runTest {
        val bodyStarted = CountDownLatch(1)
        val cancellationObserved = CountDownLatch(1)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val source = object : InputStream() {
                override fun read(): Int {
                    bodyStarted.countDown()
                    while (!chain.call().isCanceled()) Thread.sleep(10)
                    cancellationObserved.countDown()
                    throw IOException("Canceled body read")
                }
            }.source().buffer()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(object : ResponseBody() {
                    override fun contentType() = "application/json".toMediaType()
                    override fun contentLength() = -1L
                    override fun source() = source
                }).build()
        }.build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val job = scope.launch {
                OkHttpXtreamApiService(client, json).streamLiveStreamRows(
                    "https://example.test/player_api.php?action=get_live_streams") { }
            }
            assertThat(bodyStarted.await(5, TimeUnit.SECONDS)).isTrue()
            withContext(Dispatchers.Default) { withTimeout(5_000) { job.cancelAndJoin() } }
            assertThat(job.isCancelled).isTrue()
            assertThat(cancellationObserved.await(5, TimeUnit.SECONDS)).isTrue()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `segmented live requests use segmented timeout profile`() = runTest {
        val seenReadTimeoutMs = AtomicInteger(-1)
        val service = OkHttpXtreamApiService(
            client = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    seenReadTimeoutMs.set(chain.readTimeoutMillis())
                    Response.Builder()
                        .request(Request.Builder().url(chain.request().url).build())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("test")
                        .body("[]".toResponseBody("application/json".toMediaType()))
                        .build()
                }
                .build(),
            json = json
        )

        service.streamLiveStreamRows(
            "https://example.test/player_api.php?action=get_live_streams&category_id=7"
        ) { }

        assertThat(seenReadTimeoutMs.get())
            .isEqualTo((NetworkTimeoutConfig.XTREAM_SEGMENTED_READ_TIMEOUT_SECONDS * 1_000L).toInt())
    }

    @Test
    fun `get applies request profile user agent`() = runTest {
        var seenUserAgent: String? = null
        val service = OkHttpXtreamApiService(
            client = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    seenUserAgent = chain.request().header("User-Agent")
                    Response.Builder()
                        .request(Request.Builder().url(chain.request().url).build())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("test")
                        .body("[]".toResponseBody("application/json".toMediaType()))
                        .build()
                }
                .build(),
            json = json
        )

        service.getLiveCategories(
            endpoint = "https://example.test/player_api.php",
            requestProfile = HttpRequestProfile(userAgent = "StreamVaultTest/1.0", ownerTag = "provider:7/xtream")
        )

        assertThat(seenUserAgent).isEqualTo("StreamVaultTest/1.0")
    }

    @Test
    fun `series info parser tolerates flattened info and nested episode objects`() {
        val payload = """
            {
              "name": "Example Series",
              "cover_big": "https://img.example.test/cover.jpg",
              "releasedate": "2024-04-01",
              "episodes": {
                "1": {
                  "101": {
                    "id": "101",
                    "episode_num": "1",
                    "title": "Pilot",
                    "season": "1",
                    "container_extension": "mp4",
                    "info": {
                      "plot": "First episode"
                    }
                  }
                }
              }
            }
        """.trimIndent()

        val response = json.decodeFromString<XtreamSeriesInfoResponse>(payload)

        assertThat(response.info).isNotNull()
        assertThat(response.info?.name).isEqualTo("Example Series")
        assertThat(response.info?.releaseDateAlt).isEqualTo("2024-04-01")
        assertThat(response.episodes.keys).containsExactly("1")
        assertThat(response.episodes["1"]).hasSize(1)
        assertThat(response.episodes["1"]?.first()?.title).isEqualTo("Pilot")
    }

    private fun clientReturning(statusCode: Int, body: String): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(Request.Builder().url(chain.request().url).build())
                    .protocol(Protocol.HTTP_1_1)
                    .code(statusCode)
                    .message("test")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
    }
}
