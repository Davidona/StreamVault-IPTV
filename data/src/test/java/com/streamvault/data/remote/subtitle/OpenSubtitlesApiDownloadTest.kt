package com.streamvault.data.remote.subtitle

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
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
    fun `temporary link token is not written to network logs and file is committed`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("1\n00:00:01,000 --> 00:00:02,000\nHello\n"))
            val logLines = mutableListOf<String>()
            val logger = HttpLoggingInterceptor { line -> logLines += line }.apply {
                level = HttpLoggingInterceptor.Level.BASIC
            }
            val api = OpenSubtitlesApi(OkHttpClient.Builder().addInterceptor(logger).build(), Gson())
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
            val api = OpenSubtitlesApi(OkHttpClient(), Gson())
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
            val api = OpenSubtitlesApi(OkHttpClient(), Gson())
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
