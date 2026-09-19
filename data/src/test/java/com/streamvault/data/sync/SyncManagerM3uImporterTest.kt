package com.streamvault.data.sync

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.parser.M3uParser
import com.streamvault.data.parser.M3uPlaybackMetadataCodec
import com.streamvault.domain.model.LegacyProvider as Provider
import com.streamvault.domain.model.ProviderType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPOutputStream

class SyncManagerM3uImporterTest {

    @Test
    fun `unknown length input accepts the exact decompressed byte limit`() = runTest {
        val playlist = playlistEntry("News", "https://stream.example.com/news.ts").toByteArray()
        val fixture = fixture(
            body = playlist,
            limits = CatalogSizeLimits(
                maxM3uDecompressedBytes = playlist.size.toLong(),
                maxM3uLineBytes = 1_024
            )
        )

        val stats = fixture.importer.importPlaylist(provider(), onProgress = null)

        assertThat(stats.liveCount).isEqualTo(1)
        assertThat(fixture.finalized).isTrue()
        verify(fixture.store).discardStagedImport(PROVIDER_ID, SESSION_ID)
    }

    @Test
    fun `gzip expansion beyond the decompressed limit aborts before catalog apply`() = runTest {
        val playlist = playlistEntry("News", "https://stream.example.com/news.ts").toByteArray()
        val fixture = fixture(
            body = gzip(playlist),
            contentEncoding = "gzip",
            limits = CatalogSizeLimits(
                maxM3uDecompressedBytes = playlist.size.toLong() - 1,
                maxM3uLineBytes = 1_024
            )
        )

        val failure = runCatching {
            fixture.importer.importPlaylist(provider(), onProgress = null)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(CatalogAdmissionExceeded::class.java)
        assertThat(failure).hasMessageThat().contains("decompressed byte limit")
        assertThat(fixture.finalized).isFalse()
        verify(fixture.store).discardStagedImport(PROVIDER_ID, SESSION_ID)
    }

    @Test
    fun `declared response length beyond the limit is rejected before parsing`() = runTest {
        val fixture = fixture(
            body = playlistEntry("News", "https://stream.example.com/news.ts").toByteArray(),
            reportedLength = 8_192,
            limits = CatalogSizeLimits(maxM3uDecompressedBytes = 4_096, maxM3uLineBytes = 1_024)
        )

        val failure = runCatching {
            fixture.importer.importPlaylist(provider(), onProgress = null)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(CatalogAdmissionExceeded::class.java)
        assertThat(failure).hasMessageThat().contains("response length limit")
        assertThat(fixture.finalized).isFalse()
    }

    @Test
    fun `overlong raw line is rejected before parser allocation grows further`() = runTest {
        val playlist = playlistEntry("N".repeat(65), "https://stream.example.com/news.ts").toByteArray()
        val fixture = fixture(
            body = playlist,
            limits = CatalogSizeLimits(maxM3uDecompressedBytes = 4_096, maxM3uLineBytes = 32)
        )

        val failure = runCatching {
            fixture.importer.importPlaylist(provider(), onProgress = null)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(CatalogAdmissionExceeded::class.java)
        assertThat(failure).hasMessageThat().contains("line length limit")
        assertThat(fixture.finalized).isFalse()
    }

    @Test
    fun `persisted metadata fields are subject to the field limit`() = runTest {
        val overlongTvgId = "t".repeat(65)
        val playlist = """
            #EXTM3U
            #EXTINF:-1 tvg-id="$overlongTvgId" group-title="News",News
            https://stream.example.com/news.ts
        """.trimIndent().toByteArray()
        val fixture = fixture(
            body = playlist,
            limits = CatalogSizeLimits(
                maxM3uDecompressedBytes = 4_096,
                maxM3uLineBytes = 1_024,
                maxM3uFieldLength = 64
            )
        )

        val failure = runCatching {
            fixture.importer.importPlaylist(provider(), onProgress = null)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(CatalogAdmissionExceeded::class.java)
        assertThat(failure).hasMessageThat().contains("field length limit")
        assertThat(fixture.finalized).isFalse()
    }

    @Test
    fun `adaptive metadata is included in staged channel entity`() = runTest {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 group-title="Protected",Protected channel
            #KODIPROP:inputstream.adaptive.manifest_type=mpd
            #KODIPROP:inputstream.adaptive.license_type=clearkey
            #KODIPROP:inputstream.adaptive.license_key=00112233445566778899aabbccddeeff:ffeeddccbbaa99887766554433221100
            #EXTVLCOPT:http-user-agent=Mozilla/5.0
            https://stream.example.com/protected.mpd
        """.trimIndent().toByteArray()
        val fixture = fixture(
            body = playlist,
            limits = CatalogSizeLimits(maxM3uDecompressedBytes = 4_096, maxM3uLineBytes = 1_024)
        )
        var stagedChannels = emptyList<com.streamvault.data.local.entity.ChannelEntity>()
        doAnswer { invocation ->
            @Suppress("UNCHECKED_CAST")
            stagedChannels = (invocation.arguments[2] as List<com.streamvault.data.local.entity.ChannelEntity>).toList()
            Unit
        }.whenever(fixture.store).stageChannelBatch(any(), any(), any())

        fixture.importer.importPlaylist(provider(), onProgress = null)

        val metadata = M3uPlaybackMetadataCodec.decode(stagedChannels.single().playbackMetadataJson)
        assertThat(metadata?.manifestType).isEqualTo(com.streamvault.domain.model.StreamType.DASH)
        assertThat(metadata?.staticClearKeyLicense?.keys).hasSize(1)
        assertThat(metadata?.userAgent).isEqualTo("Mozilla/5.0")
    }

    @Test
    fun `http content type charset is honored by the streaming importer`() = runTest {
        val iso88591 = Charsets.ISO_8859_1
        val playlist = "#EXTM3U\n#EXTINF:-1 tvg-id=\"café-tv\" group-title=\"Télévision\",Café\nhttps://stream.example.com/cafe.ts\n"
        val fixture = fixture(
            body = playlist.toByteArray(iso88591),
            contentType = "application/x-mpegURL; charset=ISO-8859-1".toMediaType(),
            limits = CatalogSizeLimits(maxM3uDecompressedBytes = 4_096, maxM3uLineBytes = 1_024)
        )
        var stagedChannels = emptyList<com.streamvault.data.local.entity.ChannelEntity>()
        doAnswer { invocation ->
            @Suppress("UNCHECKED_CAST")
            stagedChannels = (invocation.arguments[2] as List<com.streamvault.data.local.entity.ChannelEntity>).toList()
            Unit
        }.whenever(fixture.store).stageChannelBatch(any(), any(), any())

        fixture.importer.importPlaylist(provider(), onProgress = null)

        val channel = stagedChannels.single()
        assertThat(channel.name).isEqualTo("Café")
        assertThat(channel.groupTitle).isEqualTo("Télévision")
        assertThat(channel.epgChannelId).isEqualTo("café-tv")
    }

    @Test
    fun `provider override disabled keeps vod-looking entries in live tv`() = runTest {
        val fixture = fixture(
            body = playlistEntry("Feature", "https://cdn.example.com/feature.mp4").toByteArray(),
            limits = CatalogSizeLimits(maxM3uDecompressedBytes = 4_096, maxM3uLineBytes = 1_024)
        )
        var stagedChannels = emptyList<com.streamvault.data.local.entity.ChannelEntity>()
        doAnswer { invocation ->
            @Suppress("UNCHECKED_CAST")
            stagedChannels = (invocation.arguments[2] as List<com.streamvault.data.local.entity.ChannelEntity>).toList()
            Unit
        }.whenever(fixture.store).stageChannelBatch(any(), any(), any())

        val stats = fixture.importer.importPlaylist(
            provider(vodClassificationEnabled = false),
            onProgress = null
        )

        assertThat(stats.liveCount).isEqualTo(1)
        assertThat(stats.movieCount).isEqualTo(0)
        assertThat(stagedChannels.single().name).isEqualTo("Feature")
        verify(fixture.store).finalizeStagedImport(
            any(), any(), anyOrNull(), anyOrNull(), eq(true), eq(true), any()
        )
    }

    @Test
    fun `full refresh classifies vod and prunes the now-empty live section`() = runTest {
        val fixture = fixture(
            body = playlistEntry("Feature", "https://cdn.example.com/feature.mp4?token=abc").toByteArray(),
            limits = CatalogSizeLimits(maxM3uDecompressedBytes = 4_096, maxM3uLineBytes = 1_024)
        )
        var stagedMovies = emptyList<com.streamvault.data.local.entity.MovieEntity>()
        doAnswer { invocation ->
            @Suppress("UNCHECKED_CAST")
            stagedMovies = (invocation.arguments[2] as List<com.streamvault.data.local.entity.MovieEntity>).toList()
            Unit
        }.whenever(fixture.store).stageMovieBatch(any(), any(), any())

        val stats = fixture.importer.importPlaylist(
            provider(vodClassificationEnabled = true),
            onProgress = null
        )

        assertThat(stats.liveCount).isEqualTo(0)
        assertThat(stats.movieCount).isEqualTo(1)
        assertThat(stagedMovies.single().name).isEqualTo("Feature")
        verify(fixture.store).finalizeStagedImport(
            any(), any(), anyOrNull(), anyOrNull(), eq(true), eq(true), any()
        )
    }

    @Test
    fun `invalid ratio is enforced when valid entries cross the sample threshold`() = runTest {
        val playlist = buildString {
            appendLine("#EXTM3U")
            repeat(51) { index ->
                appendLine("#EXTINF:-1,Invalid $index")
                appendLine("not-a-stream-url")
            }
            repeat(49) { index ->
                appendLine("#EXTINF:-1,Valid $index")
                appendLine("https://stream.example.com/$index.ts")
            }
        }.toByteArray()
        val fixture = fixture(
            body = playlist,
            limits = CatalogSizeLimits(
                maxM3uDecompressedBytes = 64_000,
                maxM3uLineBytes = 1_024,
                maxM3uInvalidEntryRatioPercent = 50
            )
        )

        val failure = runCatching {
            fixture.importer.importPlaylist(provider(), onProgress = null)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(CatalogAdmissionExceeded::class.java)
        assertThat(failure).hasMessageThat().contains("invalid-entry ratio limit")
        assertThat(fixture.finalized).isFalse()
        verify(fixture.store).discardStagedImport(PROVIDER_ID, SESSION_ID)
    }

    @Test
    fun `entry live movie and category maxima reject the first item beyond the boundary`() = runTest {
        val liveEntries = buildString {
            appendLine("#EXTM3U")
            append(playlistRows("First", "News", "https://stream.example.com/1.ts"))
            append(playlistRows("Second", "Sports", "https://stream.example.com/2.ts"))
        }
        val movieEntries = buildString {
            appendLine("#EXTM3U")
            append(playlistRows("First", "Movies", "https://stream.example.com/1.mp4"))
            append(playlistRows("Second", "Movies", "https://stream.example.com/2.mp4"))
        }
        val cases = listOf(
            Triple(
                liveEntries,
                CatalogSizeLimits(maxM3uEntries = 1, maxM3uDecompressedBytes = 8_192, maxM3uLineBytes = 1_024),
                "entry limit"
            ),
            Triple(
                liveEntries,
                CatalogSizeLimits(maxChannelsPerProvider = 1, maxM3uDecompressedBytes = 8_192, maxM3uLineBytes = 1_024),
                "live-channel limit"
            ),
            Triple(
                movieEntries,
                CatalogSizeLimits(maxMoviesPerProvider = 1, maxM3uDecompressedBytes = 8_192, maxM3uLineBytes = 1_024),
                "movie limit"
            ),
            Triple(
                liveEntries,
                CatalogSizeLimits(maxM3uCategoriesPerType = 1, maxM3uDecompressedBytes = 8_192, maxM3uLineBytes = 1_024),
                "category limit"
            )
        )

        cases.forEach { (playlist, limits, expectedMessage) ->
            val fixture = fixture(body = playlist.toByteArray(), limits = limits)
            val failure = runCatching {
                fixture.importer.importPlaylist(
                    provider = provider(vodClassificationEnabled = expectedMessage == "movie limit"),
                    onProgress = null
                )
            }.exceptionOrNull()

            assertThat(failure).isInstanceOf(CatalogAdmissionExceeded::class.java)
            assertThat(failure).hasMessageThat().contains(expectedMessage)
            assertThat(fixture.finalized).isFalse()
        }
    }

    @Test
    fun `duplicate heavy input remains governed by the total entry limit`() = runTest {
        val playlist = buildString {
            appendLine("#EXTM3U")
            repeat(3) { index ->
                append(playlistRows("Duplicate $index", "News", "https://stream.example.com/same.ts"))
            }
        }
        val fixture = fixture(
            body = playlist.toByteArray(),
            limits = CatalogSizeLimits(
                maxM3uEntries = 2,
                maxM3uDecompressedBytes = 8_192,
                maxM3uLineBytes = 1_024
            )
        )

        val failure = runCatching {
            fixture.importer.importPlaylist(provider(), onProgress = null)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(CatalogAdmissionExceeded::class.java)
        assertThat(failure).hasMessageThat().contains("entry limit")
        assertThat(fixture.finalized).isFalse()
    }

    @Test
    fun `staging failure discards the session without applying the catalog`() = runTest {
        val fixture = fixture(
            body = playlistEntry("News", "https://stream.example.com/news.ts").toByteArray(),
            limits = CatalogSizeLimits(maxM3uDecompressedBytes = 4_096, maxM3uLineBytes = 1_024)
        )
        doAnswer { throw IOException("database full") }
            .whenever(fixture.store)
            .stageChannelBatch(any(), any(), any())

        val failure = runCatching {
            fixture.importer.importPlaylist(provider(), onProgress = null, batchSize = 1)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IOException::class.java)
        assertThat(fixture.finalized).isFalse()
        verify(fixture.store).discardStagedImport(PROVIDER_ID, SESSION_ID)
    }

    @Test
    fun `cancellation propagates after staging cleanup`() = runTest {
        val fixture = fixture(
            body = playlistEntry("News", "https://stream.example.com/news.ts").toByteArray(),
            limits = CatalogSizeLimits(maxM3uDecompressedBytes = 4_096, maxM3uLineBytes = 1_024)
        )
        doAnswer { throw CancellationException("cancelled") }
            .whenever(fixture.store)
            .stageChannelBatch(any(), any(), any())

        val failure = runCatching {
            fixture.importer.importPlaylist(provider(), onProgress = null, batchSize = 1)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(CancellationException::class.java)
        assertThat(fixture.finalized).isFalse()
        verify(fixture.store).discardStagedImport(PROVIDER_ID, SESSION_ID)
    }

    private suspend fun fixture(
        body: ByteArray,
        limits: CatalogSizeLimits,
        contentEncoding: String? = null,
        reportedLength: Long = -1L,
        contentType: MediaType? = null
    ): Fixture {
        val store = mock<SyncCatalogStore>()
        whenever(store.newSessionId()).thenReturn(SESSION_ID)
        val fixture = Fixture(store)
        doAnswer {
            fixture.finalized = true
            Unit
        }.whenever(store).finalizeStagedImport(
            any(),
            any(),
            anyOrNull(),
            anyOrNull(),
            any(),
            any(),
            any()
        )
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .apply {
                        if (contentEncoding != null) header("Content-Encoding", contentEncoding)
                    }
                    .body(TestResponseBody(body, reportedLength, contentType))
                    .build()
            }
            .build()
        fixture.importer = SyncManagerM3uImporter(
            m3uParser = M3uParser(),
            okHttpClient = client,
            syncCatalogStore = store,
            retryTransient = { operation -> operation() },
            progress = { _, _, _ -> },
            emitProgress = { _, _ -> },
            sizeLimits = limits
        )
        return fixture
    }

    private fun provider(vodClassificationEnabled: Boolean = false) = Provider(
        id = PROVIDER_ID,
        name = "Test playlist",
        type = ProviderType.M3U,
        serverUrl = PLAYLIST_URL,
        m3uUrl = PLAYLIST_URL,
        m3uVodClassificationEnabled = vodClassificationEnabled
    )

    private fun playlistEntry(name: String, url: String): String = """
        #EXTM3U
        #EXTINF:-1 group-title="News",$name
        $url
    """.trimIndent()

    private fun playlistRows(name: String, group: String, url: String): String =
        "#EXTINF:-1 group-title=\"$group\",$name\n$url\n"

    private fun gzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().use { output ->
        GZIPOutputStream(output).use { it.write(bytes) }
        output.toByteArray()
    }

    private class Fixture(val store: SyncCatalogStore) {
        lateinit var importer: SyncManagerM3uImporter
        var finalized: Boolean = false
    }

    private class TestResponseBody(
        bytes: ByteArray,
        private val reportedLength: Long,
        private val mediaType: MediaType?
    ) : ResponseBody() {
        private val source = Buffer().write(bytes)

        override fun contentType(): MediaType? = mediaType
        override fun contentLength(): Long = reportedLength
        override fun source(): BufferedSource = source
    }

    private companion object {
        const val PROVIDER_ID = 41L
        const val SESSION_ID = 73L
        const val PLAYLIST_URL = "https://playlist.example.com/list.m3u"
    }
}
