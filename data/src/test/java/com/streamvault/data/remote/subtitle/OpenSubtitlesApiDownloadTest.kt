package com.streamvault.data.remote.subtitle

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.streamvault.domain.model.Result
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OpenSubtitlesApiDownloadTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `login sends the configured application key`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"token":"test-token","user":{"allowed_downloads":20}}"""))
            val api = OpenSubtitlesApi(OkHttpClient(), Gson(), "test-opensubtitles-key")

            val result = api.login(
                "test-user",
                "test-password",
                server.url("/api/v1").toString().trimEnd('/'),
            )

            assertThat(result.getOrNull()?.token).isEqualTo("test-token")
            assertThat(server.takeRequest().getHeader("Api-Key")).isEqualTo("test-opensubtitles-key")
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `login requests are spaced to respect the API rate limit`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            repeat(2) {
                server.enqueue(MockResponse().setBody("""{"token":"test-token"}"""))
            }
            val api = OpenSubtitlesApi(OkHttpClient(), Gson(), "test-api-key")
            val baseUrl = server.url("/api/v1").toString().trimEnd('/')

            val firstStartedAt = System.nanoTime()
            assertThat(api.login("test-user", "test-password", baseUrl).isSuccess).isTrue()
            assertThat(api.login("test-user", "test-password", baseUrl).isSuccess).isTrue()

            val elapsedMillis = (System.nanoTime() - firstStartedAt) / 1_000_000L
            assertThat(elapsedMillis).isAtLeast(950L)
            assertThat(server.requestCount).isEqualTo(2)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `missing application key returns setup guidance without a request`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val api = OpenSubtitlesApi(OkHttpClient(), Gson(), " ")

            val result = api.login("test-user", "test-password")

            assertThat(result).isInstanceOf(Result.Error::class.java)
            assertThat((result as Result.Error).message).contains("opensubtitles.api.key")
            assertThat(server.requestCount).isEqualTo(0)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `search sends the app key and only sends bearer when an account token exists`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"data":[]}"""))
            server.enqueue(MockResponse().setBody("""{"data":[]}"""))
            val api = OpenSubtitlesApi(OkHttpClient(), Gson(), "test-api-key")
            val baseUrl = server.url("/").toString().trimEnd('/')

            assertThat(api.search(OpenSubtitlesApi.SearchQuery(query = "Alien"), baseUrlHost = baseUrl).isSuccess)
                .isTrue()
            assertThat(api.search(
                OpenSubtitlesApi.SearchQuery(query = "Alien"),
                token = "user-token",
                baseUrlHost = baseUrl,
            ).isSuccess).isTrue()

            val anonymous = server.takeRequest()
            assertThat(anonymous.getHeader("Api-Key")).isEqualTo("test-api-key")
            assertThat(anonymous.getHeader("Authorization")).isNull()
            assertThat(anonymous.getHeader("User-Agent")).isEqualTo("StreamVault vdev")
            val authenticated = server.takeRequest()
            assertThat(authenticated.getHeader("Api-Key")).isEqualTo("test-api-key")
            assertThat(authenticated.getHeader("Authorization")).isEqualTo("Bearer user-token")
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `search sorts query parameters and encodes spaces with plus`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"data":[]}"""))
            val api = OpenSubtitlesApi(OkHttpClient(), Gson(), "test-api-key")

            val result = api.search(
                OpenSubtitlesApi.SearchQuery(query = "Star Trek", tmdbId = 42L, languages = "en"),
                baseUrlHost = server.url("/").toString().trimEnd('/'),
            )

            assertThat(result.isSuccess).isTrue()
            assertThat(server.takeRequest().path)
                .isEqualTo("/api/v1/subtitles?languages=en&query=Star+Trek&tmdb_id=42")
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `anonymous download request includes the app key without a bearer token`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody(
                """{"link":"https://example.com/file.srt","file_name":"file.srt","remaining":4}"""
            ))
            val api = OpenSubtitlesApi(OkHttpClient(), Gson(), "test-api-key")

            val result = api.downloadLink(
                fileId = 42L,
                baseUrlHost = server.url("/").toString().trimEnd('/'),
            )

            assertThat(result.isSuccess).isTrue()
            val request = server.takeRequest()
            assertThat(request.getHeader("Api-Key")).isEqualTo("test-api-key")
            assertThat(request.getHeader("Authorization")).isNull()
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `temporary link token is not written to network logs and file is committed`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("1\n00:00:01,000 --> 00:00:02,000\nHello\n"))
            val logLines = mutableListOf<String>()
            val logger = HttpLoggingInterceptor { line -> logLines += line }.apply {
                level = HttpLoggingInterceptor.Level.BASIC
            }
            val api = OpenSubtitlesApi(OkHttpClient.Builder().addInterceptor(logger).build(), Gson(), "test-api-key")
            val destination = File(temporaryFolder.newFolder(), "123.srt")
            val token = "private-download-token"
            val link = OpenSubtitlesApi.DownloadLink(
                url = server.url("/download/$token/subfile/movie.srt").toString(),
                fileName = "movie.srt",
                remaining = 19,
                resetTimeUtc = "2030-01-01T00:00:00Z",
            )

            val result = api.downloadSubtitleFile(link, destination)

            assertThat(result.getOrNull()).isEqualTo(destination)
            assertThat(destination.readText()).contains("Hello")
            assertThat(File(destination.parentFile, "${destination.name}.part").exists()).isFalse()
            assertThat(logLines.joinToString("\n")).doesNotContain(token)
            assertThat(server.takeRequest().path).contains(token)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `failed response leaves no final or partial subtitle file`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(503).setBody("unavailable"))
            val api = OpenSubtitlesApi(OkHttpClient(), Gson(), "test-api-key")
            val destination = File(temporaryFolder.newFolder(), "456.srt")

            val result = api.downloadSubtitleFile(
                OpenSubtitlesApi.DownloadLink(
                    url = server.url("/private-link").toString(),
                    fileName = "captions.srt",
                    remaining = null,
                    resetTimeUtc = null,
                ),
                destination,
            )

            assertThat(result.isError).isTrue()
            assertThat(destination.exists()).isFalse()
            assertThat(File(destination.parentFile, "${destination.name}.part").exists()).isFalse()
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `cancelling a response body read removes the temporary file`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setBody("x".repeat(100_000))
                    .throttleBody(512, 1, java.util.concurrent.TimeUnit.SECONDS)
            )
            val api = OpenSubtitlesApi(OkHttpClient(), Gson(), "test-api-key")
            val destination = File(temporaryFolder.newFolder(), "789.srt")
            val download = async {
                api.downloadSubtitleFile(
                    OpenSubtitlesApi.DownloadLink(
                        url = server.url("/slow-link").toString(),
                        fileName = "slow.srt",
                        remaining = null,
                        resetTimeUtc = null,
                    ),
                    destination,
                )
            }
            delay(200)
            download.cancelAndJoin()

            assertThat(destination.exists()).isFalse()
            assertThat(File(destination.parentFile, "${destination.name}.part").exists()).isFalse()
        } finally {
            server.shutdown()
        }
    }
}
