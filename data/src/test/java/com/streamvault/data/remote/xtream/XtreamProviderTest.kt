package com.streamvault.data.remote.xtream

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.streamvault.data.remote.http.HttpRequestProfile
import com.streamvault.data.remote.dto.XtreamAuthResponse
import com.streamvault.data.remote.dto.XtreamCategory
import com.streamvault.data.remote.dto.XtreamEpgListing
import com.streamvault.data.remote.dto.XtreamEpgResponse
import com.streamvault.data.remote.dto.XtreamEpisode
import com.streamvault.data.remote.dto.XtreamEpisodeInfo
import com.streamvault.data.remote.dto.XtreamLiveStreamRow
import com.streamvault.data.remote.dto.XtreamSeason
import com.streamvault.data.remote.dto.XtreamSeriesInfoResponse
import com.streamvault.data.remote.dto.XtreamSeriesItem
import com.streamvault.data.remote.dto.XtreamServerInfo
import com.streamvault.data.remote.dto.XtreamStream
import com.streamvault.data.remote.dto.XtreamUserInfo
import com.streamvault.data.remote.dto.XtreamVodInfo
import com.streamvault.data.remote.dto.XtreamVodInfoResponse
import com.streamvault.data.remote.dto.XtreamVodMovieData
import com.streamvault.domain.model.Result
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class XtreamProviderTest {

    @Test
    fun `series compatibility fallback preserves cancellation and does not issue legacy request`() = runTest {
        val requestedEndpoints = mutableListOf<String>()
        val provider = XtreamProvider(
            providerId = 42,
            api = FakeXtreamApiService(
                seriesInfoLoader = { endpoint ->
                    requestedEndpoints += endpoint.substringAfter("action=get_series_info")
                    throw CancellationException("cancelled")
                }
            ),
            serverUrl = "https://example.com",
            username = "user",
            password = "pass"
        )
        var cancellation: CancellationException? = null

        try {
            provider.getSeriesInfo(91)
        } catch (error: CancellationException) {
            cancellation = error
        }

        assertThat(cancellation).isNotNull()
        assertThat(requestedEndpoints).hasSize(1)
        assertThat(requestedEndpoints.single()).contains("series_id=91")
    }

    @Test
    fun `streaming facade preserves cancellation from its batch consumer`() = runTest {
        val provider = XtreamProvider(
            providerId = 42,
            api = FakeXtreamApiService(
                vodStreams = listOf(vodStream("1"))
            ),
            serverUrl = "https://example.com",
            username = "user",
            password = "pass"
        )
        var cancellation: CancellationException? = null

        try {
            provider.streamVodSummaries(batchSize = 1) {
                throw CancellationException("cancelled while consuming batch")
            }
        } catch (error: CancellationException) {
            cancellation = error
        }

        assertThat(cancellation).isNotNull()
    }

    @Test
    fun `all public facade operations preserve cancellation`() = runTest {
        val operations = listOf(
            "authenticate" to CancellationTarget.AUTHENTICATE,
            "live categories" to CancellationTarget.LIVE_CATEGORIES,
            "live streams" to CancellationTarget.LIVE_STREAMS,
            "VOD categories" to CancellationTarget.VOD_CATEGORIES,
            "VOD streams" to CancellationTarget.VOD_STREAMS,
            "VOD stream summaries" to CancellationTarget.VOD_STREAM_SUMMARIES,
            "VOD details" to CancellationTarget.VOD_INFO,
            "series categories" to CancellationTarget.SERIES_CATEGORIES,
            "series list" to CancellationTarget.SERIES_LIST,
            "series summaries" to CancellationTarget.SERIES_SUMMARIES,
            "series details" to CancellationTarget.SERIES_INFO,
            "short EPG" to CancellationTarget.SHORT_EPG,
            "full EPG" to CancellationTarget.FULL_EPG
        )

        operations.forEach { (label, target) ->
            val provider = XtreamProvider(
                providerId = 42,
                api = CancellationXtreamApiService(target),
                serverUrl = "https://example.com",
                username = "user",
                password = "pass"
            )
            var cancellation: CancellationException? = null

            try {
                when (target) {
                    CancellationTarget.AUTHENTICATE -> provider.authenticate()
                    CancellationTarget.LIVE_CATEGORIES -> provider.getLiveCategories()
                    CancellationTarget.LIVE_STREAMS -> provider.getLiveStreams()
                    CancellationTarget.VOD_CATEGORIES -> provider.getVodCategories()
                    CancellationTarget.VOD_STREAMS -> provider.getVodStreams()
                    CancellationTarget.VOD_STREAM_SUMMARIES -> provider.streamVodSummaries { }
                    CancellationTarget.VOD_INFO -> provider.getVodInfo(91)
                    CancellationTarget.SERIES_CATEGORIES -> provider.getSeriesCategories()
                    CancellationTarget.SERIES_LIST -> provider.getSeriesList()
                    CancellationTarget.SERIES_SUMMARIES -> provider.streamSeriesSummaries { }
                    CancellationTarget.SERIES_INFO -> provider.getSeriesInfo(91)
                    CancellationTarget.SHORT_EPG -> provider.getShortEpg("91", 5)
                    CancellationTarget.FULL_EPG -> provider.getEpg("91")
                }
            } catch (error: CancellationException) {
                cancellation = error
            }

            assertWithMessage("$label must preserve cancellation")
                .that(cancellation)
                .isNotNull()
        }
    }

    @Test
    fun `genuine Xtream network failures remain typed facade errors`() = runTest {
        val provider = XtreamProvider(
            providerId = 42,
            api = FakeXtreamApiService(
                authLoader = { throw IOException("connection reset") }
            ),
            serverUrl = "https://example.com",
            username = "user",
            password = "pass"
        )

        val result = provider.authenticate()

        assertThat(result).isInstanceOf(Result.Error::class.java)
        assertThat((result as Result.Error).exception).isInstanceOf(IOException::class.java)
    }

    @Test
    fun `parseXtreamExpirationDate handles slash separated local date times`() {
        assertThat(parseXtreamExpirationDate("2026/03/20 14:30:00")).isEqualTo(1774017000000L)
    }

    @Test
    fun `parseXtreamExpirationDate handles slash separated dates`() {
        assertThat(parseXtreamExpirationDate("2026/03/20")).isEqualTo(1773964800000L)
    }

    @Test
    fun `parseXtreamExpirationDate handles timestamps and iso instants`() {
        assertThat(parseXtreamExpirationDate("1710801000")).isEqualTo(1710801000000L)
        assertThat(parseXtreamExpirationDate("2026-03-20T14:30:00Z")).isEqualTo(1774017000000L)
        assertThat(parseXtreamExpirationDate("Unlimited")).isEqualTo(Long.MAX_VALUE)
    }

    @Test
    fun `authenticate normalizes allowed output formats`() = runBlocking {
        val provider = XtreamProvider(
            providerId = 42,
            api = FakeXtreamApiService(
                authResponse = XtreamAuthResponse(
                    userInfo = XtreamUserInfo(
                        auth = 1,
                        allowedOutputFormats = listOf("TS", "m3u8", "  ts  ")
                    ),
                    serverInfo = XtreamServerInfo()
                )
            ),
            serverUrl = "https://example.com",
            username = "user",
            password = "pass"
        )

        val authenticated = provider.authenticate().getOrNull()

        assertThat(authenticated?.allowedOutputFormats).containsExactly("ts", "m3u8").inOrder()
    }

    @Test
    fun `authenticate falls back to one connection for zero and placeholder max connections`() = runBlocking {
        listOf("0", "", "NA", "N/A", "  ").forEach { rawMaxConnections ->
            val provider = XtreamProvider(
                providerId = 42,
                api = FakeXtreamApiService(
                    authResponse = XtreamAuthResponse(
                        userInfo = XtreamUserInfo(
                            auth = 1,
                            maxConnections = rawMaxConnections
                        ),
                        serverInfo = XtreamServerInfo()
                    )
                ),
                serverUrl = "https://example.com",
                username = "user",
                password = "pass"
            )

            val authenticated = provider.authenticate().getOrNull()

            assertThat(authenticated?.maxConnections).isEqualTo(1)
        }
    }

    @Test
    fun `getLiveStreams preserves live container extension in internal url`() = runBlocking {
        val provider = XtreamProvider(
            providerId = 42,
            api = FakeXtreamApiService(
                liveStreams = listOf(
                    XtreamStream(
                        name = "Live Channel",
                        streamId = 777,
                        containerExtension = ".M3U8",
                        directSource = "https://cdn.example.com/live/777/master.m3u8?token=abc"
                    )
                )
            ),
            serverUrl = "https://example.com",
            username = "user",
            password = "pass"
        )

        val channels = provider.getLiveStreams().getOrNull().orEmpty()

        assertThat(channels).hasSize(1)
        assertThat(channels.first().streamUrl).isEqualTo(
            "xtream://42/live/777?ext=m3u8&src=https%3A%2F%2Fcdn.example.com%2Flive%2F777%2Fmaster.m3u8%3Ftoken%3Dabc"
        )
    }

    @Test
    fun `getLiveStreams prefers hls and keeps ts fallback when both output formats are allowed`() = runBlocking {
        val provider = XtreamProvider(
            providerId = 42,
            api = FakeXtreamApiService(
                liveStreams = listOf(
                    XtreamStream(
                        name = "Live Channel",
                        streamId = 777
                    )
                )
            ),
            serverUrl = "https://example.com",
            username = "user",
            password = "pass",
            allowedOutputFormats = listOf("ts", "m3u8")
        )

        val channel = provider.getLiveStreams().getOrNull().orEmpty().single()

        assertThat(channel.streamUrl).isEqualTo("xtream://42/live/777?ext=m3u8")
        assertThat(channel.alternativeStreams).containsExactly("xtream://42/live/777?ext=ts")
        assertThat(channel.qualityOptions.map { it.label to it.url }).containsExactly(
            "HLS" to "xtream://42/live/777?ext=m3u8",
            "MPEG-TS" to "xtream://42/live/777?ext=ts"
        ).inOrder()
    }

    @Test
    fun `mapLiveStreamsResponse keeps sync live rows lightweight`() = runBlocking {
        val provider = XtreamProvider(
            providerId = 42,
            api = FakeXtreamApiService(),
            serverUrl = "https://example.com",
            username = "user",
            password = "pass",
            allowedOutputFormats = listOf("ts", "m3u8")
        )

        val channel = provider.mapLiveStreamsResponse(
            listOf(
                XtreamStream(
                    name = "Live Channel",
                    streamId = 777,
                    containerExtension = ".M3U8",
                    directSource = "https://cdn.example.com/live/777/master.m3u8?token=abc"
                )
            )
        ).single()

        assertThat(channel.streamUrl).isEqualTo("xtream://42/live/777?ext=m3u8")
        assertThat(channel.streamUrl).doesNotContain("src=")
        assertThat(channel.qualityOptions).isEmpty()
        assertThat(channel.alternativeStreams).isEmpty()
    }

    @Test
    fun `mapLiveStreamRowsSequence matches legacy sync core fields without playback variants`() = runBlocking {
        val provider = XtreamProvider(
            providerId = 42,
            api = FakeXtreamApiService(),
            serverUrl = "https://example.com",
            username = "user",
            password = "pass",
            allowedOutputFormats = listOf("ts", "m3u8")
        )
        val legacyChannel = provider.mapLiveStreamsSequence(
            sequenceOf(
                XtreamStream(
                    num = 12,
                    name = "Live Channel",
                    streamId = 777,
                    streamIcon = "https://img.example.com/live.png",
                    epgChannelId = "live.us",
                    categoryId = "0",
                    categoryName = "News",
                    categoryIds = listOf("123"),
                    tvArchive = 1,
                    tvArchiveDuration = 3,
                    containerExtension = ".M3U8",
                    directSource = "https://cdn.example.com/live/777/master.m3u8?token=abc",
                    isAdult = false
                )
            )
        ).single()
        val thinChannel = provider.mapLiveStreamRowsSequence(
            sequenceOf(
                XtreamLiveStreamRow(
                    num = 12,
                    name = "Live Channel",
                    streamId = 777,
                    streamIcon = "https://img.example.com/live.png",
                    epgChannelId = "live.us",
                    categoryId = "0",
                    categoryName = "News",
                    categoryIds = listOf("123"),
                    tvArchive = 1,
                    tvArchiveDuration = 3,
                    containerExtension = ".M3U8",
                    isAdult = false
                )
            )
        ).single()

        assertThat(thinChannel.streamId).isEqualTo(legacyChannel.streamId)
        assertThat(thinChannel.name).isEqualTo(legacyChannel.name)
        assertThat(thinChannel.categoryId).isEqualTo(legacyChannel.categoryId)
        assertThat(thinChannel.categoryName).isEqualTo(legacyChannel.categoryName)
        assertThat(thinChannel.logoUrl).isEqualTo(legacyChannel.logoUrl)
        assertThat(thinChannel.epgChannelId).isEqualTo(legacyChannel.epgChannelId)
        assertThat(thinChannel.number).isEqualTo(legacyChannel.number)
        assertThat(thinChannel.catchUpSupported).isEqualTo(legacyChannel.catchUpSupported)
        assertThat(thinChannel.catchUpDays).isEqualTo(legacyChannel.catchUpDays)
        assertThat(thinChannel.isAdult).isEqualTo(legacyChannel.isAdult)
        assertThat(thinChannel.streamUrl).isEqualTo("xtream://42/live/777?ext=m3u8")
        assertThat(thinChannel.streamUrl).doesNotContain("src=")
        assertThat(thinChannel.qualityOptions).isEmpty()
        assertThat(thinChannel.alternativeStreams).isEmpty()
    }

    @Test
    fun `buildCatchUpUrls includes xtream route and php fallbacks for preferred formats`() = runBlocking {
        val provider = XtreamProvider(
            providerId = 42,
            api = FakeXtreamApiService(),
            serverUrl = "https://example.com",
            username = "user",
            password = "pass",
            allowedOutputFormats = listOf("m3u8", "ts")
        )

        val urls = provider.buildCatchUpUrls(
            streamId = 777,
            start = 1_710_000_000L,
            end = 1_710_003_600L
        )

        assertThat(urls).containsAtLeast(
            "https://example.com/timeshift/user/pass/60/2024-03-09%3A16-00/777.m3u8",
            "https://example.com/timeshifts/user/pass/60/777/2024-03-09%3A16-00.m3u8",
            "https://example.com/streaming/timeshift.php?username=user&password=pass&stream=777&start=2024-03-09%3A16-00&duration=60&extension=m3u8",
            "https://example.com/timeshift.php?username=user&password=pass&stream=777&start=2024-03-09%3A16-00&duration=60"
        )
        assertThat(urls.first()).isEqualTo("https://example.com/timeshift/user/pass/60/2024-03-09%3A16-00/777.m3u8")
    }

    @Test
    fun `getVodInfo keeps names raw while decoding common xtream metadata fields`() = runBlocking {
        val provider = XtreamProvider(
            providerId = 42,
            api = FakeXtreamApiService(
                vodInfo = XtreamVodInfoResponse(
                    info = XtreamVodInfo(
                        plot = "U29tZSBQbG90",
                        cast = "Sm9obiBEb2U=",
                        director = "SmFuZSBEb2U=",
                        genre = "QWN0aW9u",
                        durationSecs = 120,
                        rating = "7.5"
                    ),
                    movieData = XtreamVodMovieData(
                        streamId = 99,
                        name = "TW92aWUgTmFtZQ==",
                        containerExtension = "MKV",
                        directSource = "https://cdn.example.com/vod/99/movie.mkv?exp=1774017000"
                    )
                )
            ),
            serverUrl = "https://example.com",
            username = "user",
            password = "pass"
        )

        val movie = provider.getVodInfo(99).getOrNull()

        assertThat(movie).isNotNull()
        assertThat(movie?.name).isEqualTo("TW92aWUgTmFtZQ==")
        assertThat(movie?.plot).isEqualTo("Some Plot")
        assertThat(movie?.cast).isEqualTo("John Doe")
        assertThat(movie?.director).isEqualTo("Jane Doe")
        assertThat(movie?.genre).isEqualTo("Action")
        assertThat(movie?.streamUrl).isEqualTo(
            "xtream://42/movie/99?ext=mkv&src=https%3A%2F%2Fcdn.example.com%2Fvod%2F99%2Fmovie.mkv%3Fexp%3D1774017000"
        )
    }

    @Test
    fun `getSeriesList keeps plain titles that only accidentally look like base64`() = runBlocking {
        val provider = XtreamProvider(
            providerId = 42,
            api = FakeXtreamApiService(
                seriesList = listOf(
                    XtreamSeriesItem(name = "Asaf", seriesId = 1),
                    XtreamSeriesItem(name = "THEM", seriesId = 2),
                    XtreamSeriesItem(name = "Silo", seriesId = 3)
                )
            ),
            serverUrl = "https://example.com",
            username = "user",
            password = "pass"
        )

        val names = provider.getSeriesList().getOrNull().orEmpty().map { it.name }

        assertThat(names).containsExactly("Asaf", "THEM", "Silo").inOrder()
    }

    @Test
    fun `getSeriesList keeps padded base64 looking titles raw`() {
        runBlocking {
            val provider = XtreamProvider(
                providerId = 42,
                api = FakeXtreamApiService(
                    seriesList = listOf(
                        XtreamSeriesItem(name = "TW92aWUgTmFtZQ==", seriesId = 77)
                    )
                ),
                serverUrl = "https://example.com",
                username = "user",
                password = "pass"
            )

            val names = provider.getSeriesList().getOrNull().orEmpty().map { it.name }

            assertThat(names).containsExactly("TW92aWUgTmFtZQ==")
        }
    }

    @Test
    fun `getSeriesList decodes padded base64 looking titles when compatibility mode is enabled`() {
        runBlocking {
            val provider = XtreamProvider(
                providerId = 42,
                api = FakeXtreamApiService(
                    seriesList = listOf(
                        XtreamSeriesItem(name = "TW92aWUgTmFtZQ==", seriesId = 77)
                    )
                ),
                serverUrl = "https://example.com",
                username = "user",
                password = "pass",
                enableBase64TextCompatibility = true
            )

            val names = provider.getSeriesList().getOrNull().orEmpty().map { it.name }

            assertThat(names).containsExactly("Movie Name")
        }
    }

    @Test
    fun `getFullEpg still decodes base64 title and description`() = runBlocking {
        val provider = XtreamProvider(
            providerId = 42,
            api = FakeXtreamApiService(
                fullEpg = XtreamEpgResponse(
                    epgListings = listOf(
                        XtreamEpgListing(
                            id = "15",
                            channelId = "news",
                            title = "TmV3cyBIb3Vy",
                            description = "VG9uaWdodCdzIGhlYWRsaW5lcw==",
                            startTimestamp = 1_710_000_000L,
                            stopTimestamp = 1_710_003_600L
                        )
                    )
                )
            ),
            serverUrl = "https://example.com",
            username = "user",
            password = "pass"
        )

        val programs = provider.getEpg("news").getOrNull().orEmpty()

        assertThat(programs).hasSize(1)
        assertThat(programs.single().title).isEqualTo("News Hour")
        assertThat(programs.single().description).isEqualTo("Tonight's headlines")
    }

    @Test
    fun `getVodStreams still loads when category prefetch fails`() = runBlocking {
        val provider = XtreamProvider(
            providerId = 42,
            api = object : XtreamApiService {
                override suspend fun authenticate(endpoint: String, requestProfile: HttpRequestProfile): XtreamAuthResponse =
                    XtreamAuthResponse(XtreamUserInfo(auth = 1), XtreamServerInfo())

                override suspend fun getLiveCategories(endpoint: String, requestProfile: HttpRequestProfile): List<XtreamCategory> = emptyList()

                override suspend fun getLiveStreams(endpoint: String, requestProfile: HttpRequestProfile): List<XtreamStream> = emptyList()

                override suspend fun getVodCategories(endpoint: String, requestProfile: HttpRequestProfile): List<XtreamCategory> {
                    throw XtreamNetworkException("category prefetch failed")
                }

                override suspend fun getVodStreams(endpoint: String, requestProfile: HttpRequestProfile): List<XtreamStream> = listOf(
                    XtreamStream(
                        name = "Action Movie",
                        streamId = 321,
                        categoryId = "vod-action",
                        categoryName = "Action",
                        containerExtension = "mp4"
                    )
                )

                override suspend fun getVodInfo(endpoint: String, requestProfile: HttpRequestProfile): XtreamVodInfoResponse = XtreamVodInfoResponse()

                override suspend fun getSeriesCategories(endpoint: String, requestProfile: HttpRequestProfile): List<XtreamCategory> = emptyList()

                override suspend fun getSeriesList(endpoint: String, requestProfile: HttpRequestProfile): List<XtreamSeriesItem> = emptyList()

                override suspend fun getSeriesInfo(endpoint: String, requestProfile: HttpRequestProfile): XtreamSeriesInfoResponse = XtreamSeriesInfoResponse()

                override suspend fun getShortEpg(endpoint: String, requestProfile: HttpRequestProfile): XtreamEpgResponse = XtreamEpgResponse()

                override suspend fun getFullEpg(endpoint: String, requestProfile: HttpRequestProfile): XtreamEpgResponse = XtreamEpgResponse()
            },
            serverUrl = "https://example.com",
            username = "user",
            password = "pass"
        )

        val movies = provider.getVodStreams().getOrNull().orEmpty()

        assertThat(movies).hasSize(1)
        assertThat(movies.first().name).isEqualTo("Action Movie")
        assertThat(movies.first().categoryName).isEqualTo("Action")
        assertThat(movies.first().categoryId).isNotNull()
    }

    @Test
    fun `getVodStreams retries adult category prefetch after a transient failure`() = runBlocking {
        var categoryRequests = 0
        val provider = XtreamProvider(
            providerId = 42,
            api = FakeXtreamApiService(
                vodCategoriesLoader = {
                    if (categoryRequests++ == 0) {
                        throw XtreamNetworkException("category prefetch failed")
                    }
                    listOf(XtreamCategory(categoryId = "28", categoryName = "Adults", isAdult = true))
                },
                vodStreams = listOf(
                    XtreamStream(
                        name = "Movie",
                        streamId = 321,
                        categoryId = "28",
                        categoryName = "Movies",
                        containerExtension = "mp4"
                    )
                )
            ),
            serverUrl = "https://example.com",
            username = "user",
            password = "pass"
        )

        assertThat(provider.getVodStreams().getOrNull().orEmpty().single().isAdult).isFalse()
        assertThat(provider.getVodStreams().getOrNull().orEmpty().single().isAdult).isTrue()
        assertThat(categoryRequests).isEqualTo(2)
    }

    @Test
    fun `getVodStreams caches a successful empty adult category response`() = runBlocking {
        var categoryRequests = 0
        val provider = XtreamProvider(
            providerId = 42,
            api = FakeXtreamApiService(
                vodCategoriesLoader = {
                    categoryRequests++
                    emptyList()
                },
                vodStreams = listOf(vodStream(categoryId = "28"))
            ),
            serverUrl = "https://example.com",
            username = "user",
            password = "pass"
        )

        provider.getVodStreams()
        provider.getVodStreams()

        assertThat(categoryRequests).isEqualTo(1)
    }

    @Test
    fun `cancelled adult category prefetch is not cached`() = runTest {
        val firstRequestStarted = CompletableDeferred<Unit>()
        val keepFirstRequestOpen = CompletableDeferred<Unit>()
        var categoryRequests = 0
        val provider = XtreamProvider(
            providerId = 42,
            api = FakeXtreamApiService(
                vodCategoriesLoader = {
                    if (categoryRequests++ == 0) {
                        firstRequestStarted.complete(Unit)
                        keepFirstRequestOpen.await()
                    }
                    listOf(XtreamCategory(categoryId = "28", categoryName = "Adults", isAdult = true))
                },
                vodStreams = listOf(vodStream(categoryId = "28"))
            ),
            serverUrl = "https://example.com",
            username = "user",
            password = "pass"
        )

        val firstLoad = async { provider.getVodStreams() }
        firstRequestStarted.await()
        firstLoad.cancelAndJoin()

        val recoveredMovie = provider.getVodStreams().getOrNull().orEmpty().single()

        assertThat(recoveredMovie.isAdult).isTrue()
        assertThat(categoryRequests).isEqualTo(2)
    }

    @Test
    fun `concurrent adult category prefetches are single flight`() = runTest {
        val firstRequestStarted = CompletableDeferred<Unit>()
        val releaseCategoryResponse = CompletableDeferred<Unit>()
        var categoryRequests = 0
        val provider = XtreamProvider(
            providerId = 42,
            api = FakeXtreamApiService(
                vodCategoriesLoader = {
                    categoryRequests++
                    firstRequestStarted.complete(Unit)
                    releaseCategoryResponse.await()
                    listOf(XtreamCategory(categoryId = "28", categoryName = "Adults", isAdult = true))
                },
                vodStreams = listOf(vodStream(categoryId = "28"))
            ),
            serverUrl = "https://example.com",
            username = "user",
            password = "pass"
        )

        val firstLoad = async { provider.getVodStreams() }
        firstRequestStarted.await()
        val secondLoad = async { provider.getVodStreams() }
        runCurrent()

        assertThat(categoryRequests).isEqualTo(1)

        releaseCategoryResponse.complete(Unit)

        assertThat(firstLoad.await().getOrNull().orEmpty().single().isAdult).isTrue()
        assertThat(secondLoad.await().getOrNull().orEmpty().single().isAdult).isTrue()
        assertThat(categoryRequests).isEqualTo(1)
    }

    @Test
    fun `successful category refresh replaces cached adult category ids`() = runBlocking {
        var categoryRequests = 0
        val provider = XtreamProvider(
            providerId = 42,
            api = FakeXtreamApiService(
                vodCategoriesLoader = {
                    if (categoryRequests++ == 0) {
                        listOf(XtreamCategory(categoryId = "28", categoryName = "Adults", isAdult = true))
                    } else {
                        emptyList()
                    }
                },
                vodStreams = listOf(vodStream(categoryId = "28"))
            ),
            serverUrl = "https://example.com",
            username = "user",
            password = "pass"
        )

        assertThat(provider.getVodStreams().getOrNull().orEmpty().single().isAdult).isTrue()
        provider.getVodCategories()

        assertThat(provider.getVodStreams().getOrNull().orEmpty().single().isAdult).isFalse()
        assertThat(categoryRequests).isEqualTo(2)
    }

    @Test
    fun `getVodStreams honors explicit adult flag from xtream payload`() = runBlocking {
        val provider = XtreamProvider(
            providerId = 42,
            api = FakeXtreamApiService(
                vodStreams = listOf(
                    XtreamStream(
                        name = "Movie",
                        streamId = 55,
                        categoryName = "Cinema",
                        isAdult = true
                    )
                )
            ),
            serverUrl = "https://example.com",
            username = "user",
            password = "pass"
        )

        val movie = provider.getVodStreams().getOrNull().orEmpty().single()

        assertThat(movie.isAdult).isTrue()
    }

    @Test
    fun `getLiveCategories honors explicit adult flag from xtream category payload`() = runBlocking {
        val provider = XtreamProvider(
            providerId = 42,
            api = FakeXtreamApiService(
                liveCategories = listOf(
                    XtreamCategory(
                        categoryId = "28",
                        categoryName = "General",
                        isAdult = true
                    )
                )
            ),
            serverUrl = "https://example.com",
            username = "user",
            password = "pass"
        )

        val category = provider.getLiveCategories().getOrNull().orEmpty().single()

        assertThat(category.id).isEqualTo(28L)
        assertThat(category.isAdult).isTrue()
    }

    @Test
    fun `getLiveStreams inherits adult status from xtream category flag`() = runBlocking {
        val provider = XtreamProvider(
            providerId = 42,
            api = FakeXtreamApiService(
                liveCategories = listOf(
                    XtreamCategory(
                        categoryId = "28",
                        categoryName = "General",
                        isAdult = true
                    )
                ),
                liveStreams = listOf(
                    XtreamStream(
                        name = "Channel",
                        streamId = 77,
                        categoryId = "28",
                        categoryName = "General",
                        isAdult = null
                    )
                )
            ),
            serverUrl = "https://example.com",
            username = "user",
            password = "pass"
        )

        val channel = provider.getLiveStreams().getOrNull().orEmpty().single()

        assertThat(channel.isAdult).isTrue()
    }

    @Test
    fun `getLiveStreams uses category_ids when live category_id is missing or zero`() = runBlocking {
        val provider = XtreamProvider(
            providerId = 42,
            api = FakeXtreamApiService(
                liveStreams = listOf(
                    XtreamStream(
                        name = "Channel",
                        streamId = 77,
                        categoryId = "0",
                        categoryIds = listOf("28")
                    )
                )
            ),
            serverUrl = "https://example.com",
            username = "user",
            password = "pass"
        )

        val channel = provider.getLiveStreams().getOrNull().orEmpty().single()

        assertThat(channel.categoryId).isEqualTo(28L)
        assertThat(channel.categoryName).isEqualTo("Category 28")
    }

    @Test
    fun `getSeriesCategories honors explicit adult flag from xtream category payload`() = runBlocking {
        val provider = XtreamProvider(
            providerId = 42,
            api = FakeXtreamApiService(
                seriesCategories = listOf(
                    XtreamCategory(
                        categoryId = "683",
                        categoryName = "Series",
                        isAdult = true
                    )
                )
            ),
            serverUrl = "https://example.com",
            username = "user",
            password = "pass"
        )

        val category = provider.getSeriesCategories().getOrNull().orEmpty().single()

        assertThat(category.id).isEqualTo(683L)
        assertThat(category.isAdult).isTrue()
    }

    @Test
    fun `vod list and details both normalize ratings to ten point scale`() = runBlocking {
        val provider = XtreamProvider(
            providerId = 42,
            api = FakeXtreamApiService(
                vodStreams = listOf(
                    XtreamStream(
                        name = "Movie",
                        streamId = 55,
                        rating = "10.0",
                        rating5based = "5"
                    )
                ),
                vodInfo = XtreamVodInfoResponse(
                    info = XtreamVodInfo(
                        rating = "10.0",
                        rating5based = "5"
                    ),
                    movieData = XtreamVodMovieData(
                        streamId = 55,
                        name = "Movie"
                    )
                )
            ),
            serverUrl = "https://example.com",
            username = "user",
            password = "pass"
        )

        val gridMovie = provider.getVodStreams().getOrNull().orEmpty().single()
        val detailMovie = provider.getVodInfo(55).getOrNull()

        assertThat(gridMovie.rating).isEqualTo(10f)
        assertThat(detailMovie?.rating).isEqualTo(10f)
    }

    @Test
    fun `getSeriesInfo falls back to legacy series query parameter when primary payload is empty`() = runBlocking {
        val requestedEndpoints = mutableListOf<String>()
        val provider = XtreamProvider(
            providerId = 42,
            api = object : XtreamApiService {
                override suspend fun authenticate(endpoint: String, requestProfile: HttpRequestProfile): XtreamAuthResponse =
                    XtreamAuthResponse(XtreamUserInfo(auth = 1), XtreamServerInfo())

                override suspend fun getLiveCategories(endpoint: String, requestProfile: HttpRequestProfile): List<XtreamCategory> = emptyList()

                override suspend fun getLiveStreams(endpoint: String, requestProfile: HttpRequestProfile): List<XtreamStream> = emptyList()

                override suspend fun getVodCategories(endpoint: String, requestProfile: HttpRequestProfile): List<XtreamCategory> = emptyList()

                override suspend fun getVodStreams(endpoint: String, requestProfile: HttpRequestProfile): List<XtreamStream> = emptyList()

                override suspend fun getVodInfo(endpoint: String, requestProfile: HttpRequestProfile): XtreamVodInfoResponse = XtreamVodInfoResponse()

                override suspend fun getSeriesCategories(endpoint: String, requestProfile: HttpRequestProfile): List<XtreamCategory> = emptyList()

                override suspend fun getSeriesList(endpoint: String, requestProfile: HttpRequestProfile): List<XtreamSeriesItem> = emptyList()

                override suspend fun getSeriesInfo(endpoint: String, requestProfile: HttpRequestProfile): XtreamSeriesInfoResponse {
                    requestedEndpoints += endpoint
                    return if (endpoint.contains("series_id=77")) {
                        XtreamSeriesInfoResponse()
                    } else {
                        XtreamSeriesInfoResponse(
                            info = XtreamSeriesItem(name = "Fallback Series"),
                            episodes = mapOf(
                                "1" to listOf(
                                    XtreamEpisode(
                                        id = "501",
                                        episodeNum = 1,
                                        title = "Episode One",
                                        season = 1,
                                        containerExtension = "mp4"
                                    )
                                )
                            )
                        )
                    }
                }

                override suspend fun getShortEpg(endpoint: String, requestProfile: HttpRequestProfile): XtreamEpgResponse = XtreamEpgResponse()

                override suspend fun getFullEpg(endpoint: String, requestProfile: HttpRequestProfile): XtreamEpgResponse = XtreamEpgResponse()
            },
            serverUrl = "https://example.com",
            username = "user",
            password = "pass"
        )

        val series = provider.getSeriesInfo(77).getOrNull()

        assertThat(series).isNotNull()
        assertThat(series?.name).isEqualTo("Fallback Series")
        assertThat(series?.seasons).hasSize(1)
        assertThat(requestedEndpoints).hasSize(2)
        assertThat(requestedEndpoints.first()).contains("series_id=77")
        assertThat(requestedEndpoints.last()).contains("series=77")
    }

    @Test
    fun `getSeriesInfo builds usable series from episodes when info block is missing`() = runBlocking {
        val provider = XtreamProvider(
            providerId = 42,
            api = FakeXtreamApiService(
                seriesInfo = XtreamSeriesInfoResponse(
                    episodes = mapOf(
                        "1" to listOf(
                            XtreamEpisode(
                                id = "701",
                                episodeNum = 1,
                                title = "Pilot",
                                season = 1,
                                containerExtension = "mp4"
                            )
                        )
                    )
                )
            ),
            serverUrl = "https://example.com",
            username = "user",
            password = "pass"
        )

        val series = provider.getSeriesInfo(88).getOrNull()

        assertThat(series).isNotNull()
        assertThat(series?.seriesId).isEqualTo(88L)
        assertThat(series?.seasons).hasSize(1)
        assertThat(series?.seasons?.first()?.episodes).hasSize(1)
    }

    @Test
    fun `getSeriesInfo maps nested episode arrays into seasons`() {
        runBlocking {
            val json = Json {
                ignoreUnknownKeys = true
                isLenient = true
                explicitNulls = false
                coerceInputValues = true
            }
            val response = json.decodeFromString<XtreamSeriesInfoResponse>(
                """
                {
                  "info": { "series_id": 88, "name": "Specials Series" },
                  "episodes": [
                    [ { "id": "1001", "episode_num": 1, "title": "Special", "container_extension": "mp4" } ],
                    [ { "id": "2001", "episode_num": 1, "title": "Pilot", "container_extension": "mp4" } ]
                  ]
                }
                """.trimIndent()
            )
            val provider = XtreamProvider(
                providerId = 42,
                api = FakeXtreamApiService(seriesInfo = response),
                serverUrl = "https://example.com",
                username = "user",
                password = "pass"
            )

            val series = provider.getSeriesInfo(88).getOrNull()

            assertThat(series?.seasons?.map { it.seasonNumber }).containsExactly(0, 1).inOrder()
            assertThat(series?.seasons?.first { it.seasonNumber == 0 }?.episodes?.map { it.title })
                .containsExactly("Special")
            assertThat(series?.seasons?.first { it.seasonNumber == 1 }?.episodes?.map { it.title })
                .containsExactly("Pilot")
        }
    }

    @Test
    fun `getSeriesInfo preserves season metadata when provider omits episode rows`() = runBlocking {
        val provider = XtreamProvider(
            providerId = 42,
            api = FakeXtreamApiService(
                seriesInfo = XtreamSeriesInfoResponse(
                    info = XtreamSeriesItem(name = "Season Only Series"),
                    seasons = listOf(
                        XtreamSeason(
                            seasonNumber = 1,
                            name = "Season 1",
                            episodeCount = 10
                        )
                    )
                )
            ),
            serverUrl = "https://example.com",
            username = "user",
            password = "pass"
        )

        val series = provider.getSeriesInfo(91).getOrNull()

        assertThat(series).isNotNull()
        assertThat(series?.seasons).hasSize(1)
        assertThat(series?.seasons?.first()?.name).isEqualTo("Season 1")
        assertThat(series?.seasons?.first()?.episodeCount).isEqualTo(10)
        assertThat(series?.seasons?.first()?.episodes).isEmpty()
    }

    private fun vodStream(categoryId: String): XtreamStream = XtreamStream(
        name = "Movie",
        streamId = 321,
        categoryId = categoryId,
        categoryName = "Movies",
        containerExtension = "mp4"
    )

    private enum class CancellationTarget {
        AUTHENTICATE,
        LIVE_CATEGORIES,
        LIVE_STREAMS,
        VOD_CATEGORIES,
        VOD_STREAMS,
        VOD_STREAM_SUMMARIES,
        VOD_INFO,
        SERIES_CATEGORIES,
        SERIES_LIST,
        SERIES_SUMMARIES,
        SERIES_INFO,
        SHORT_EPG,
        FULL_EPG
    }

    private class CancellationXtreamApiService(
        private val target: CancellationTarget
    ) : XtreamApiService {
        private fun cancelIf(expected: CancellationTarget) {
            if (target == expected) throw CancellationException("${expected.name} cancelled")
        }

        override suspend fun authenticate(endpoint: String, requestProfile: HttpRequestProfile): XtreamAuthResponse {
            cancelIf(CancellationTarget.AUTHENTICATE)
            return XtreamAuthResponse(XtreamUserInfo(auth = 1), XtreamServerInfo())
        }

        override suspend fun getLiveCategories(endpoint: String, requestProfile: HttpRequestProfile): List<XtreamCategory> {
            cancelIf(CancellationTarget.LIVE_CATEGORIES)
            return emptyList()
        }

        override suspend fun getLiveStreams(endpoint: String, requestProfile: HttpRequestProfile): List<XtreamStream> {
            cancelIf(CancellationTarget.LIVE_STREAMS)
            return emptyList()
        }

        override suspend fun getVodCategories(endpoint: String, requestProfile: HttpRequestProfile): List<XtreamCategory> {
            cancelIf(CancellationTarget.VOD_CATEGORIES)
            return emptyList()
        }

        override suspend fun getVodStreams(endpoint: String, requestProfile: HttpRequestProfile): List<XtreamStream> {
            cancelIf(CancellationTarget.VOD_STREAMS)
            return emptyList()
        }

        override suspend fun streamVodStreams(
            endpoint: String,
            requestProfile: HttpRequestProfile,
            onItem: suspend (XtreamStream) -> Unit
        ): Int {
            cancelIf(CancellationTarget.VOD_STREAM_SUMMARIES)
            return super.streamVodStreams(endpoint, requestProfile, onItem)
        }

        override suspend fun getVodInfo(endpoint: String, requestProfile: HttpRequestProfile): XtreamVodInfoResponse {
            cancelIf(CancellationTarget.VOD_INFO)
            return XtreamVodInfoResponse()
        }

        override suspend fun getSeriesCategories(endpoint: String, requestProfile: HttpRequestProfile): List<XtreamCategory> {
            cancelIf(CancellationTarget.SERIES_CATEGORIES)
            return emptyList()
        }

        override suspend fun getSeriesList(endpoint: String, requestProfile: HttpRequestProfile): List<XtreamSeriesItem> {
            cancelIf(CancellationTarget.SERIES_LIST)
            return emptyList()
        }

        override suspend fun streamSeriesList(
            endpoint: String,
            requestProfile: HttpRequestProfile,
            onItem: suspend (XtreamSeriesItem) -> Unit
        ): Int {
            cancelIf(CancellationTarget.SERIES_SUMMARIES)
            return super.streamSeriesList(endpoint, requestProfile, onItem)
        }

        override suspend fun getSeriesInfo(endpoint: String, requestProfile: HttpRequestProfile): XtreamSeriesInfoResponse {
            cancelIf(CancellationTarget.SERIES_INFO)
            return XtreamSeriesInfoResponse()
        }

        override suspend fun getShortEpg(endpoint: String, requestProfile: HttpRequestProfile): XtreamEpgResponse {
            cancelIf(CancellationTarget.SHORT_EPG)
            return XtreamEpgResponse()
        }

        override suspend fun getFullEpg(endpoint: String, requestProfile: HttpRequestProfile): XtreamEpgResponse {
            cancelIf(CancellationTarget.FULL_EPG)
            return XtreamEpgResponse()
        }
    }

    private class FakeXtreamApiService(
        private val authResponse: XtreamAuthResponse = XtreamAuthResponse(XtreamUserInfo(auth = 1), XtreamServerInfo()),
        private val authLoader: (suspend () -> XtreamAuthResponse)? = null,
        private val liveCategories: List<XtreamCategory> = emptyList(),
        private val liveCategoriesLoader: (suspend () -> List<XtreamCategory>)? = null,
        private val liveStreams: List<XtreamStream> = emptyList(),
        private val vodCategories: List<XtreamCategory> = emptyList(),
        private val vodCategoriesLoader: (suspend () -> List<XtreamCategory>)? = null,
        private val vodStreams: List<XtreamStream> = emptyList(),
        private val vodInfo: XtreamVodInfoResponse = XtreamVodInfoResponse(),
        private val seriesCategories: List<XtreamCategory> = emptyList(),
        private val seriesList: List<XtreamSeriesItem> = emptyList(),
        private val seriesInfo: XtreamSeriesInfoResponse = XtreamSeriesInfoResponse(),
        private val seriesInfoLoader: (suspend (String) -> XtreamSeriesInfoResponse)? = null,
        private val shortEpg: XtreamEpgResponse = XtreamEpgResponse(),
        private val fullEpg: XtreamEpgResponse = XtreamEpgResponse()
    ) : XtreamApiService {
        override suspend fun authenticate(endpoint: String, requestProfile: HttpRequestProfile): XtreamAuthResponse {
            return authLoader?.invoke() ?: authResponse
        }

        override suspend fun getLiveCategories(endpoint: String, requestProfile: HttpRequestProfile): List<XtreamCategory> =
            liveCategoriesLoader?.invoke() ?: liveCategories

        override suspend fun getLiveStreams(endpoint: String, requestProfile: HttpRequestProfile): List<XtreamStream> = liveStreams

        override suspend fun getVodCategories(endpoint: String, requestProfile: HttpRequestProfile): List<XtreamCategory> =
            vodCategoriesLoader?.invoke() ?: vodCategories

        override suspend fun getVodStreams(endpoint: String, requestProfile: HttpRequestProfile): List<XtreamStream> = vodStreams

        override suspend fun getVodInfo(endpoint: String, requestProfile: HttpRequestProfile): XtreamVodInfoResponse = vodInfo

        override suspend fun getSeriesCategories(endpoint: String, requestProfile: HttpRequestProfile): List<XtreamCategory> = seriesCategories

        override suspend fun getSeriesList(endpoint: String, requestProfile: HttpRequestProfile): List<XtreamSeriesItem> = seriesList

        override suspend fun getSeriesInfo(endpoint: String, requestProfile: HttpRequestProfile): XtreamSeriesInfoResponse =
            seriesInfoLoader?.invoke(endpoint) ?: seriesInfo

        override suspend fun getShortEpg(endpoint: String, requestProfile: HttpRequestProfile): XtreamEpgResponse = shortEpg

        override suspend fun getFullEpg(endpoint: String, requestProfile: HttpRequestProfile): XtreamEpgResponse = fullEpg
    }
}
