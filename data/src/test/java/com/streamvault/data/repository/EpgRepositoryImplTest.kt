package com.streamvault.data.repository

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.DatabaseTransactionRunner
import com.streamvault.data.local.dao.ProgramDao
import com.streamvault.data.local.dao.ProviderDao
import com.streamvault.data.local.entity.ProviderEntity
import com.streamvault.data.local.entity.ProgramBrowseEntity
import com.streamvault.data.local.entity.ProgramEntity
import com.streamvault.data.parser.XmltvParser
import com.streamvault.data.provider.ProviderCapabilityResolver
import com.streamvault.domain.model.Program
import com.streamvault.domain.model.Provider
import com.streamvault.domain.model.ProviderSnapshot
import com.streamvault.domain.model.StalkerConfig
import com.streamvault.domain.model.StalkerDeviceIdentity
import com.streamvault.domain.model.ProviderType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.domain.repository.EpgSourceRepository
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream

@OptIn(ExperimentalCoroutinesApi::class)
class EpgRepositoryImplTest {

    private val programDao: ProgramDao = mock()
    private val providerDao: ProviderDao = mock()
    private val xmltvParser: XmltvParser = mock()
    private val epgSourceRepository: EpgSourceRepository = mock()
    private val providerCapabilityResolver: ProviderCapabilityResolver = mock()
    private val preferencesRepository: PreferencesRepository = mock()
    private val transactionRunner = object : DatabaseTransactionRunner {
        override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
    }

    @Before
    fun setUp() {
        whenever(xmltvParser.maybeDecompressGzip(any(), any())).thenAnswer { invocation ->
            invocation.getArgument(1)
        }
        runBlocking {
            whenever(providerDao.getById(any())).thenReturn(null)
            whenever(preferencesRepository.getEpgTimeShiftMinutes(any())).thenReturn(0)
            whenever(preferencesRepository.epgTimeShiftMinutes(any())).thenReturn(flowOf(0))
        }
    }

    @Test
    fun `searchPrograms ranks exact matches ahead of loose matches`() = runTest {
        whenever(programDao.searchPrograms(any(), any(), any(), any(), anyOrNull(), any())).thenReturn(
            flowOf(
                listOf(
                    ProgramBrowseEntity(
                        id = 1L,
                        providerId = 7L,
                        channelId = "one",
                        title = "Late Sports Replay",
                        startTime = 10L,
                        endTime = 20L
                    ),
                    ProgramBrowseEntity(
                        id = 2L,
                        providerId = 7L,
                        channelId = "two",
                        title = "Sports",
                        startTime = 30L,
                        endTime = 40L
                    )
                )
            )
        )

        val repository = EpgRepositoryImpl(
            programDao = programDao,
            providerDao = providerDao,
            xmltvParser = xmltvParser,
            okHttpClient = okHttpClientReturningXml(),
            transactionRunner = transactionRunner,
            epgSourceRepository = epgSourceRepository,
            preferencesRepository = preferencesRepository
        )

        val result = repository.searchPrograms(7L, "sports", 0L, 100L).first()

        assertThat(result.map { it.title }).containsExactly("Sports", "Late Sports Replay").inOrder()
    }

    @Test
    fun `getResolvedProgramsForPlaybackChannel_prefersResolvedProgramsByStreamId`() = runTest {
        val resolvedPrograms = listOf(
            Program(
                id = 1L,
                providerId = 7L,
                channelId = "42",
                title = "Resolved News",
                startTime = 100L,
                endTime = 200L
            )
        )
        whenever(
            epgSourceRepository.getResolvedProgramsForChannels(
                providerId = 7L,
                channelIds = listOf(101L),
                startTime = 0L,
                endTime = 500L
            )
        ).thenReturn(mapOf("42" to resolvedPrograms))

        val repository = EpgRepositoryImpl(
            programDao = programDao,
            providerDao = providerDao,
            xmltvParser = xmltvParser,
            okHttpClient = okHttpClientReturningXml(),
            transactionRunner = transactionRunner,
            epgSourceRepository = epgSourceRepository,
            preferencesRepository = preferencesRepository
        )

        val result = repository.getResolvedProgramsForPlaybackChannel(
            providerId = 7L,
            internalChannelId = 101L,
            epgChannelId = "bbc1.uk",
            streamId = 42L,
            startTime = 0L,
            endTime = 500L
        )

        assertThat(result.map { it.title }).containsExactly("Resolved News")
    }

    @Test
    fun `getResolvedProgramsForPlaybackChannel_fallsBackToProviderPrograms`() = runTest {
        whenever(
            epgSourceRepository.getResolvedProgramsForChannels(
                providerId = 7L,
                channelIds = listOf(101L),
                startTime = 0L,
                endTime = 500L
            )
        ).thenReturn(emptyMap())
        whenever(programDao.getForChannel(7L, "bbc1.uk", 0L, 500L)).thenReturn(
            flowOf(
                listOf(
                    ProgramBrowseEntity(
                        id = 2L,
                        providerId = 7L,
                        channelId = "bbc1.uk",
                        title = "Provider News",
                        startTime = 150L,
                        endTime = 250L
                    )
                )
            )
        )

        val repository = EpgRepositoryImpl(
            programDao = programDao,
            providerDao = providerDao,
            xmltvParser = xmltvParser,
            okHttpClient = okHttpClientReturningXml(),
            transactionRunner = transactionRunner,
            epgSourceRepository = epgSourceRepository,
            preferencesRepository = preferencesRepository
        )

        val result = repository.getResolvedProgramsForPlaybackChannel(
            providerId = 7L,
            internalChannelId = 101L,
            epgChannelId = "bbc1.uk",
            streamId = 0L,
            startTime = 0L,
            endTime = 500L
        )

        assertThat(result.map { it.title }).containsExactly("Provider News")
    }

    @Test
    fun `refreshEpg serializes concurrent syncs for same provider`() = runTest {
        val firstParserEntered = CompletableDeferred<Unit>()
        val releaseFirstParser = CompletableDeferred<Unit>()
        val secondParserEntered = CompletableDeferred<Unit>()
        val parserCallOrder = AtomicInteger(0)
        val activeParsers = AtomicInteger(0)
        val maxConcurrentParsers = AtomicInteger(0)

        whenever(xmltvParser.parseStreaming(any(), anyOrNull(), any())).thenAnswer { invocation ->
            val activeCount = activeParsers.incrementAndGet()
            maxConcurrentParsers.updateAndGet { current -> maxOf(current, activeCount) }
            val callIndex = parserCallOrder.incrementAndGet()

            try {
                if (callIndex == 1) {
                    firstParserEntered.complete(Unit)
                    runBlocking { releaseFirstParser.await() }
                } else {
                    secondParserEntered.complete(Unit)
                }

                val onProgram = invocation.getArgument<suspend (Program) -> Unit>(2)
                runBlocking {
                    onProgram(
                        Program(
                            providerId = 7L,
                            channelId = "channel-1",
                            title = "News",
                            description = "",
                            startTime = 1_735_722_000_000L,
                            endTime = 1_735_725_600_000L,
                            lang = "en"
                        )
                    )
                }
            } finally {
                activeParsers.decrementAndGet()
            }
        }

        val repository = EpgRepositoryImpl(
            programDao = programDao,
            providerDao = providerDao,
            xmltvParser = xmltvParser,
            okHttpClient = okHttpClientReturningXml(),
            transactionRunner = transactionRunner,
            epgSourceRepository = epgSourceRepository,
            preferencesRepository = preferencesRepository
        )

        val firstRefresh = async { repository.refreshEpg(7L, "https://example.com/epg.xml") }
        firstParserEntered.await()

        val secondRefresh = async { repository.refreshEpg(7L, "https://example.com/epg.xml") }
        delay(100)

        assertThat(secondParserEntered.isCompleted).isFalse()

        releaseFirstParser.complete(Unit)

        assertThat(firstRefresh.await().isSuccess).isTrue()
        assertThat(secondRefresh.await().isSuccess).isTrue()
        assertThat(maxConcurrentParsers.get()).isEqualTo(1)

        val insertedPrograms = argumentCaptor<List<ProgramEntity>>()
        verify(programDao, atLeastOnce()).insertAll(insertedPrograms.capture())
        assertThat(insertedPrograms.allValues.flatten()).isNotEmpty()
        verify(programDao, times(2)).moveToProvider(-7L, 7L)
    }

    @Test
    fun `refreshEpg decompresses gzip xmltv responses`() = runTest {
        val repository = EpgRepositoryImpl(
            programDao = programDao,
            providerDao = providerDao,
            xmltvParser = XmltvParser(),
            okHttpClient = okHttpClientReturningBody(
                gzip(
                    """
                    <tv>
                      <programme channel="bbc1.uk" start="20260101000000 +0000" stop="20260101010000 +0000">
                        <title>Morning News</title>
                      </programme>
                    </tv>
                    """.trimIndent().toByteArray(Charsets.UTF_8)
                ),
                contentType = "application/gzip"
            ),
            transactionRunner = transactionRunner,
            epgSourceRepository = epgSourceRepository,
            preferencesRepository = preferencesRepository
        )

        val result = repository.refreshEpg(7L, "https://example.com/epg.xml.gz")

        assertThat(result.isSuccess).isTrue()
        val insertedPrograms = argumentCaptor<List<ProgramEntity>>()
        verify(programDao, atLeastOnce()).insertAll(insertedPrograms.capture())
        assertThat(insertedPrograms.allValues.flatten().map { it.title }).contains("Morning News")
    }

    @Test
    fun `refreshEpg does not double decompress when content encoding and gz suffix are both present`() = runTest {
        val repository = EpgRepositoryImpl(
            programDao = programDao,
            providerDao = providerDao,
            xmltvParser = XmltvParser(),
            okHttpClient = okHttpClientReturningBody(
                body = gzip(
                    """
                    <tv>
                      <programme channel="bbc1.uk" start="20260101000000 +0000" stop="20260101010000 +0000">
                        <title>CDN Morning News</title>
                      </programme>
                    </tv>
                    """.trimIndent().toByteArray(Charsets.UTF_8)
                ),
                contentType = "application/xml",
                headers = mapOf("Content-Encoding" to "gzip")
            ),
            transactionRunner = transactionRunner,
            epgSourceRepository = epgSourceRepository,
            preferencesRepository = preferencesRepository
        )

        val result = repository.refreshEpg(7L, "https://example.com/epg.xml.gz")

        assertThat(result.isSuccess).isTrue()
        val insertedPrograms = argumentCaptor<List<ProgramEntity>>()
        verify(programDao, atLeastOnce()).insertAll(insertedPrograms.capture())
        assertThat(insertedPrograms.allValues.flatten().map { it.title }).contains("CDN Morning News")
    }

    @Test
    fun `refreshEpg does not force identity encoding`() = runTest {
        val requestRef = java.util.concurrent.atomic.AtomicReference<Request>()
        val repository = EpgRepositoryImpl(
            programDao = programDao,
            providerDao = providerDao,
            xmltvParser = xmltvParser,
            okHttpClient = okHttpClientReturningBody(
                body = "<tv></tv>".toByteArray(Charsets.UTF_8),
                onRequest = { requestRef.set(it) }
            ),
            transactionRunner = transactionRunner,
            epgSourceRepository = epgSourceRepository,
            preferencesRepository = preferencesRepository
        )

        val result = repository.refreshEpg(7L, "https://example.com/epg.xml")

        assertThat(result.isSuccess).isTrue()
        assertThat(requestRef.get()?.header("Accept-Encoding")).isNull()
    }

    @Test
    fun `getNowPlaying refreshes over time for long lived subscriptions`() = runTest {
        whenever(programDao.getNowPlaying(eq(7L), eq("bbc1.uk"), any())).thenReturn(
            flowOf(
                ProgramBrowseEntity(
                    id = 1L,
                    providerId = 7L,
                    channelId = "bbc1.uk",
                    title = "Current Show",
                    startTime = 100L,
                    endTime = 200L
                )
            ),
            flowOf(
                ProgramBrowseEntity(
                    id = 2L,
                    providerId = 7L,
                    channelId = "bbc1.uk",
                    title = "Next Show",
                    startTime = 200L,
                    endTime = 300L
                )
            )
        )

        val repository = EpgRepositoryImpl(
            programDao = programDao,
            providerDao = providerDao,
            xmltvParser = xmltvParser,
            okHttpClient = okHttpClientReturningXml(),
            transactionRunner = transactionRunner,
            epgSourceRepository = epgSourceRepository,
            preferencesRepository = preferencesRepository,
            externalScope = backgroundScope
        )

        val emissions = mutableListOf<Program?>()
        val collection = async {
            repository.getNowPlaying(7L, "bbc1.uk")
                .take(2)
                .toList(emissions)
        }

        advanceTimeBy(60_000L)
        collection.await()

        assertThat(emissions.map { it?.title }).containsExactly("Current Show", "Next Show").inOrder()
        verify(programDao, times(2)).getNowPlaying(eq(7L), eq("bbc1.uk"), any())
    }

    @Test
    fun `getNowPlayingForChannels refreshes over time for long lived subscriptions`() = runTest {
        whenever(programDao.getNowPlayingForChannels(eq(7L), eq(listOf("bbc1.uk", "bbc2.uk")), any())).thenReturn(
            flowOf(
                listOf(
                    ProgramBrowseEntity(
                        id = 1L,
                        providerId = 7L,
                        channelId = "bbc1.uk",
                        title = "Current One",
                        startTime = 100L,
                        endTime = 200L
                    ),
                    ProgramBrowseEntity(
                        id = 2L,
                        providerId = 7L,
                        channelId = "bbc2.uk",
                        title = "Current Two",
                        startTime = 100L,
                        endTime = 200L
                    )
                )
            ),
            flowOf(
                listOf(
                    ProgramBrowseEntity(
                        id = 3L,
                        providerId = 7L,
                        channelId = "bbc1.uk",
                        title = "Next One",
                        startTime = 200L,
                        endTime = 300L
                    ),
                    ProgramBrowseEntity(
                        id = 4L,
                        providerId = 7L,
                        channelId = "bbc2.uk",
                        title = "Next Two",
                        startTime = 200L,
                        endTime = 300L
                    )
                )
            )
        )

        val repository = EpgRepositoryImpl(
            programDao = programDao,
            providerDao = providerDao,
            xmltvParser = xmltvParser,
            okHttpClient = okHttpClientReturningXml(),
            transactionRunner = transactionRunner,
            epgSourceRepository = epgSourceRepository,
            preferencesRepository = preferencesRepository,
            externalScope = backgroundScope
        )

        val emissions = mutableListOf<Map<String, Program?>>()
        val collection = async {
            repository.getNowPlayingForChannels(7L, listOf("bbc1.uk", "bbc2.uk"))
                .take(2)
                .toList(emissions)
        }

        advanceTimeBy(60_000L)
        collection.await()

        assertThat(emissions.map { it.getValue("bbc1.uk")?.title }).containsExactly("Current One", "Next One").inOrder()
        assertThat(emissions.map { it.getValue("bbc2.uk")?.title }).containsExactly("Current Two", "Next Two").inOrder()
        verify(programDao, times(2)).getNowPlayingForChannels(eq(7L), eq(listOf("bbc1.uk", "bbc2.uk")), any())
    }

    @Test
    fun `getProgramsForChannelsSnapshot chunks large requests without reactive combine`() = runTest {
        val firstChunk = (1..500).map { index ->
            ProgramBrowseEntity(
                id = index.toLong(),
                providerId = 7L,
                channelId = "channel-$index",
                title = "Program $index",
                startTime = 100L,
                endTime = 200L
            )
        }
        val secondChunk = listOf(
            ProgramBrowseEntity(
                id = 501L,
                providerId = 7L,
                channelId = "channel-501",
                title = "Program 501",
                startTime = 100L,
                endTime = 200L
            )
        )
        whenever(programDao.getForChannelsSync(eq(7L), any(), eq(0L), eq(1_000L))).thenAnswer { invocation ->
            val requestedIds = invocation.getArgument<List<String>>(1)
            if (requestedIds.size == 500) firstChunk else secondChunk
        }

        val repository = EpgRepositoryImpl(
            programDao = programDao,
            providerDao = providerDao,
            xmltvParser = xmltvParser,
            okHttpClient = okHttpClientReturningXml(),
            transactionRunner = transactionRunner,
            epgSourceRepository = epgSourceRepository,
            preferencesRepository = preferencesRepository
        )

        val result = repository.getProgramsForChannelsSnapshot(
            providerId = 7L,
            channelIds = (1..501).map { "channel-$it" },
            startTime = 0L,
            endTime = 1_000L
        )

        assertThat(result).hasSize(501)
        assertThat(result["channel-1"]?.single()?.title).isEqualTo("Program 1")
        assertThat(result["channel-501"]?.single()?.title).isEqualTo("Program 501")
        verify(programDao, times(2)).getForChannelsSync(eq(7L), any(), eq(0L), eq(1_000L))
        verify(programDao, never()).getForChannels(eq(7L), any(), eq(0L), eq(1_000L))
    }

    @Test
    fun `refreshEpg returns typed error when parser hits oversized chunked response`() = runTest {
        whenever(xmltvParser.maybeDecompressGzip(any(), any())).thenReturn(
            object : java.io.InputStream() {
                override fun read(): Int = throw IOException("EPG response too large (>200 MB)")
            }
        )
        whenever(xmltvParser.parseStreaming(any(), anyOrNull(), any())).thenAnswer { invocation ->
            val input = invocation.getArgument<java.io.InputStream>(0)
            input.read()
            Unit
        }

        val repository = EpgRepositoryImpl(
            programDao = programDao,
            providerDao = providerDao,
            xmltvParser = xmltvParser,
            okHttpClient = okHttpClientReturningXml(),
            transactionRunner = transactionRunner,
            epgSourceRepository = epgSourceRepository,
            preferencesRepository = preferencesRepository
        )

        val result = repository.refreshEpg(7L, "https://example.com/epg.xml")

        assertThat(result.isError).isTrue()
        assertThat(result.errorMessageOrNull()).isEqualTo("EPG response exceeded 200 MB limit")
    }

    @Test
    fun `refreshEpg stages batches in short transactions outside parser transaction`() = runTest {
        val insertTransactionDepths = mutableListOf<Int>()
        val parserCallbackTransactionDepths = mutableListOf<Int>()
        var transactionDepth = 0
        var transactionCount = 0
        val trackingTransactionRunner = object : DatabaseTransactionRunner {
            override suspend fun <T> inTransaction(block: suspend () -> T): T {
                transactionCount++
                transactionDepth++
                try {
                    return block()
                } finally {
                    transactionDepth--
                }
            }
        }
        doAnswer {
            insertTransactionDepths += transactionDepth
            Unit
        }.whenever(programDao).insertAll(any())
        whenever(xmltvParser.parseStreaming(any(), anyOrNull(), any())).thenAnswer { invocation ->
            val onProgram = invocation.getArgument<suspend (Program) -> Unit>(2)
            runBlocking {
                repeat(5_001) { index ->
                    parserCallbackTransactionDepths += transactionDepth
                    onProgram(
                        Program(
                            providerId = 7L,
                            channelId = "channel-$index",
                            title = "Program $index",
                            description = "",
                            startTime = 1_735_722_000_000L + index,
                            endTime = 1_735_725_600_000L + index,
                            lang = "en"
                        )
                    )
                }
            }
        }

        val repository = EpgRepositoryImpl(
            programDao = programDao,
            providerDao = providerDao,
            xmltvParser = xmltvParser,
            okHttpClient = okHttpClientReturningXml(),
            transactionRunner = trackingTransactionRunner,
            epgSourceRepository = epgSourceRepository,
            preferencesRepository = preferencesRepository
        )

        val result = repository.refreshEpg(7L, "https://example.com/epg.xml")

        assertThat(result.isSuccess).isTrue()
        assertThat(transactionCount).isEqualTo(4)
        assertThat(parserCallbackTransactionDepths).hasSize(5_001)
        assertThat(parserCallbackTransactionDepths.all { it == 0 }).isTrue()
        assertThat(insertTransactionDepths).hasSize(2)
        assertThat(insertTransactionDepths.all { it > 0 }).isTrue()
    }

    @Test
    fun `refreshEpg passes provider timezone to parser for no offset timestamps`() = runTest {
        whenever(providerCapabilityResolver.snapshot(7L)).thenReturn(
            ProviderSnapshot(
                provider = Provider(
                    id = 7L,
                    name = "Provider",
                    type = ProviderType.STALKER_PORTAL
                ),
                configuration = StalkerConfig(
                    portalUrl = "https://provider.example.com",
                    device = StalkerDeviceIdentity(
                        macAddress = "00:1A:79:00:00:07",
                        timezone = "America/New_York"
                    )
                ),
                configurationGeneration = 1L
            )
        )
        whenever(xmltvParser.parseStreaming(any(), anyOrNull(), any())).thenAnswer { invocation ->
            val onProgram = invocation.getArgument<suspend (Program) -> Unit>(2)
            runBlocking {
                onProgram(
                    Program(
                        providerId = 7L,
                        channelId = "channel-1",
                        title = "News",
                        description = "",
                        startTime = 1L,
                        endTime = 2L,
                        lang = "en"
                    )
                )
            }
        }

        val repository = EpgRepositoryImpl(
            programDao = programDao,
            providerDao = providerDao,
            xmltvParser = xmltvParser,
            okHttpClient = okHttpClientReturningXml(),
            transactionRunner = transactionRunner,
            epgSourceRepository = epgSourceRepository,
            preferencesRepository = preferencesRepository,
            providerCapabilityResolver = providerCapabilityResolver
        )

        val result = repository.refreshEpg(7L, "https://example.com/epg.xml")

        assertThat(result.isSuccess).isTrue()
        verify(xmltvParser).parseStreaming(any(), eq("America/New_York"), any())
    }

    private fun okHttpClientReturningXml(): OkHttpClient =
        okHttpClientReturningBody("<tv></tv>".toByteArray(Charsets.UTF_8))

    private fun okHttpClientReturningBody(
        body: ByteArray,
        contentType: String = "application/xml",
        headers: Map<String, String> = emptyMap(),
        onRequest: ((Request) -> Unit)? = null
    ): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                onRequest?.invoke(chain.request())
                Response.Builder().apply {
                    headers.forEach { (name, value) -> addHeader(name, value) }
                }
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body.toResponseBody(contentType.toMediaType()))
                    .build()
            }
            .build()

    private fun gzip(bytes: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(bytes) }
        return output.toByteArray()
    }
}
