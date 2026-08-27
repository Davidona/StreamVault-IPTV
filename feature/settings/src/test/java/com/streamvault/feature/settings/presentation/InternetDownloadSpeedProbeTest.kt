package com.streamvault.feature.settings.presentation

import com.google.common.truth.Truth.assertThat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test

class InternetDownloadSpeedProbeTest {
    @Test
    fun `measureMegabitsPerSecond measures bytes read over elapsed time`() {
        var currentTimeNs = 0L
        var requestedUrl: String? = null
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                requestedUrl = chain.request().url.toString()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(ByteArray(1_000_000).toResponseBody("application/octet-stream".toMediaType()))
                    .build()
            }
            .build()
        val probe = InternetDownloadSpeedProbe(
            okHttpClient = client,
            urlFactory = { bytes -> "https://example.test/speed?bytes=$bytes" },
            nanoTime = {
                val value = currentTimeNs
                currentTimeNs += 1_000_000_000L
                value
            }
        )

        val result = probe.measureMegabitsPerSecond(bytesToDownload = 1_000_000L)

        assertThat(result).isWithin(0.001).of(8.0)
        assertThat(requestedUrl).isEqualTo("https://example.test/speed?bytes=1000000")
    }

    @Test
    fun `measureMegabitsPerSecond fails instead of inventing a link speed estimate`() {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(503)
                    .message("Unavailable")
                    .body("".toResponseBody(null))
                    .build()
            }
            .build()
        val probe = InternetDownloadSpeedProbe(
            okHttpClient = client,
            urlFactory = { "https://example.test/speed" },
            nanoTime = { 0L }
        )

        val failure = runCatching { probe.measureMegabitsPerSecond() }.exceptionOrNull()

        assertThat(failure).hasMessageThat().contains("HTTP 503")
    }
}
