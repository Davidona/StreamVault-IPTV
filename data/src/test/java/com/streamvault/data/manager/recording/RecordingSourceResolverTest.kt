package com.streamvault.data.manager.recording

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.dao.ChannelDao
import com.streamvault.data.local.dao.ProviderDao
import com.streamvault.data.remote.xtream.ResolvedStreamUrl
import com.streamvault.data.remote.xtream.XtreamStreamUrlResolver
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.RecordingSourceType
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class RecordingSourceResolverTest {
    @Test fun fragmentedHlsSignatureOverridesStaleTsMetadata() {
        assertThat(resolve(
            MockResponse().setBody("#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\nsegment.ts\n")
                .throttleBody(3, 10, TimeUnit.MILLISECONDS),
            containerExtension = "ts"
        )).isEqualTo(RecordingSourceType.HLS)
    }

    @Test fun fragmentedDashSignatureIsDetected() {
        assertThat(resolve(
            MockResponse().setBody("<?xml version=\"1.0\"?>\n<MPD type=\"dynamic\"></MPD>")
                .throttleBody(2, 10, TimeUnit.MILLISECONDS)
        )).isEqualTo(RecordingSourceType.DASH)
    }

    @Test fun shortTsResponseDoesNotUseStaleHlsMetadata() {
        assertThat(resolve(
            MockResponse().setBody(Buffer().write(ByteArray(188) { 0x47.toByte() })),
            containerExtension = "m3u8"
        )).isEqualTo(RecordingSourceType.TS)
    }

    @Test fun emptyProbeRetainsMetadataFallback() {
        assertThat(resolve(MockResponse(), containerExtension = "m3u8"))
            .isEqualTo(RecordingSourceType.HLS)
    }

    @Test fun liveProbeStopsAfterBoundedPrefixWithoutWaitingForEof() = runBlocking {
        var producedBytes = 0
        val liveSource = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                check(producedBytes < 1026) { "Probe read beyond its bounded prefix" }
                val size = minOf(3L, byteCount).toInt()
                sink.write(ByteArray(size) { 0x47.toByte() })
                producedBytes += size
                return size.toLong()
            }
            override fun timeout() = Timeout.NONE
            override fun close() = Unit
        }.buffer()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val body = if (chain.request().method == "HEAD") {
                ByteArray(0).toResponseBody()
            } else {
                object : ResponseBody() {
                    override fun contentLength() = -1L
                    override fun contentType() = null
                    override fun source() = liveSource
                }
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body).build()
        }.build()
        try {
            val resolver = createResolver(client, "https://provider.example/live/123", "m3u8")
            assertThat(resolver.resolveLiveSource(1L, 123L, "https://provider.example/live/123").sourceType)
                .isEqualTo(RecordingSourceType.TS)
            assertThat(producedBytes).isAtMost(1026)
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    private fun resolve(response: MockResponse, containerExtension: String? = null): RecordingSourceType = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setHeader("Content-Type", "application/octet-stream"))
            server.enqueue(response.setHeader("Content-Type", "application/octet-stream"))
            val url = server.url("/live/123").toString()
            val client = OkHttpClient()
            try {
                createResolver(client, url, containerExtension).resolveLiveSource(1L, 123L, url).sourceType
            } finally {
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdown()
            }
        }
    }

    private suspend fun createResolver(client: OkHttpClient, url: String, containerExtension: String?): RecordingSourceResolver {
        val playbackResolver: XtreamStreamUrlResolver = mock()
        whenever(playbackResolver.resolveAndCommitMetadata(
            url = url,
            fallbackProviderId = 1L,
            fallbackStreamId = 123L,
            fallbackContentType = ContentType.LIVE,
            preferStableUrl = false
        )).thenReturn(ResolvedStreamUrl(url, containerExtension = containerExtension))
        val providerDao: ProviderDao = mock()
        val channelDao: ChannelDao = mock()
        return RecordingSourceResolver(RecordingHttpClientFactory { client }, providerDao, playbackResolver, channelDao)
    }
}
