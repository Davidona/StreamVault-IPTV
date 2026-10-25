package com.streamvault.data.manager

import com.google.common.truth.Truth.assertThat
import java.net.HttpURLConnection
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class DownloadMockWebServerBoundaryTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `server replay covers fresh unknown length transfer`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(HttpURLConnection.HTTP_OK)
                .setBody("raw bytes")
                .removeHeader("Content-Length")
        )

        val response = OkHttpClient().newCall(Request.Builder().url(server.url("/movie")).build()).execute()
        response.use {
            val body = it.body!!
            assertThat(resolveResumeResponse(0L, it.code, body.contentLength().takeIf { length -> length >= 0 }, null, null))
                .isEqualTo(DownloadResumeResponse.TRANSFER(false, null, false))
        }
    }

    @Test
    fun `server replay covers aligned range and exact end range`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(HttpURLConnection.HTTP_PARTIAL)
                .setHeader("Content-Range", "bytes 512-999/1000")
                .setBody("r".repeat(488))
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(416)
                .setHeader("Content-Range", "bytes */1000")
        )

        val client = OkHttpClient()
        val partial = client.newCall(
            buildDownloadHttpRequest(server.url("/movie").toString(), emptyMap(), null, 512L)
        ).execute()
        partial.use {
            val body = it.body!!
            assertThat(resolveResumeResponse(512L, it.code, body.contentLength(), it.header("Accept-Ranges"), it.header("Content-Range")))
                .isEqualTo(DownloadResumeResponse.TRANSFER(true, 1_000L, true))
        }

        val complete = client.newCall(
            buildDownloadHttpRequest(server.url("/movie").toString(), emptyMap(), null, 1_000L)
        ).execute()
        complete.use {
            assertThat(resolveResumeResponse(1_000L, it.code, it.body?.contentLength(), null, it.header("Content-Range")))
                .isEqualTo(DownloadResumeResponse.COMPLETE)
        }
    }

    @Test
    fun `server replay rejects misaligned partial response`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(HttpURLConnection.HTTP_PARTIAL)
                .setHeader("Content-Range", "bytes 400-999/1000")
                .setBody("wrong range")
        )

        val response = OkHttpClient().newCall(
            buildDownloadHttpRequest(server.url("/movie").toString(), emptyMap(), null, 512L)
        ).execute()
        response.use {
            assertThat(resolveResumeResponse(512L, it.code, it.body?.contentLength(), null, it.header("Content-Range")))
                .isEqualTo(DownloadResumeResponse.RESTART)
        }
    }
}
