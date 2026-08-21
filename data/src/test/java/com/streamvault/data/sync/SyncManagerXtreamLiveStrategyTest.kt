package com.streamvault.data.sync

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.DatabaseTransactionRunner
import com.streamvault.data.local.dao.CatalogSyncDao
import com.streamvault.data.local.dao.CategoryDao
import com.streamvault.data.local.dao.ChannelDao
import com.streamvault.data.local.dao.MovieDao
import com.streamvault.data.local.dao.SeriesDao
import com.streamvault.data.local.dao.TmdbIdentityDao
import com.streamvault.data.remote.dto.XtreamAuthResponse
import com.streamvault.data.remote.dto.XtreamCategory
import com.streamvault.data.remote.dto.XtreamEpgResponse
import com.streamvault.data.remote.dto.XtreamSeriesInfoResponse
import com.streamvault.data.remote.dto.XtreamSeriesItem
import com.streamvault.data.remote.dto.XtreamServerInfo
import com.streamvault.data.remote.dto.XtreamStream
import com.streamvault.data.remote.dto.XtreamUserInfo
import com.streamvault.data.remote.dto.XtreamVodInfoResponse
import com.streamvault.data.remote.http.HttpRequestProfile
import com.streamvault.data.remote.xtream.OkHttpXtreamApiService
import com.streamvault.data.remote.xtream.XtreamApiService
import com.streamvault.data.remote.xtream.XtreamProvider
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.LegacyProvider as Provider
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.SyncMetadata
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import org.mockito.kotlin.mock

class SyncManagerXtreamLiveStrategyTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        coerceInputValues = true
    }

    @Test
    fun `loadXtreamLiveFull retries legacy full decode when thin decode fails`() = runTest {
        val requestCount = AtomicInteger(0)
        val httpService = OkHttpXtreamApiService(
            client = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val attempt = requestCount.incrementAndGet()
                    val body = when (attempt) {
                        1, 2 -> """[{"stream_id":"""
                        else -> """
                            [
                              {
                                "num": "7",
                                "name": "Legacy Live",
                                "stream_id": "777",
                                "stream_icon": "https://img.example.test/live.png",
                                "category_id": "12",
                                "category_name": "News",
                                "container_extension": "m3u8",
                                "direct_source": "https://cdn.example.test/live/777/master.m3u8?token=abc"
                              }
                            ]
                        """.trimIndent()
                    }
                    Response.Builder()
                        .request(Request.Builder().url(chain.request().url).build())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody("application/json".toMediaType()))
                        .build()
                }
                .build(),
            json = json
        )
        val apiService = FakeXtreamApiService()
        val provider = Provider(
            id = 42L,
            name = "Xtream",
            type = ProviderType.XTREAM_CODES,
            serverUrl = "https://example.test",
            username = "user",
            password = "pass",
            allowedOutputFormats = listOf("m3u8", "ts")
        )
        val xtreamProvider = XtreamProvider(
            providerId = provider.id,
            api = apiService,
            serverUrl = provider.serverUrl,
            username = provider.username,
            password = provider.password,
            allowedOutputFormats = provider.allowedOutputFormats
        )
        val stagedChannels = mutableListOf<Channel>()
        val strategy = createStrategy(
            apiService = apiService,
            httpService = httpService,
            stageChannelItems = { _, channels, _, fallbackCollector, _ ->
                stagedChannels += channels
                channels.forEach { channel ->
                    fallbackCollector.record(channel.categoryId, channel.categoryName, channel.isAdult)
                }
                StagedCatalogSnapshot(
                    sessionId = 9001L,
                    acceptedCount = channels.size,
                    fallbackCategories = fallbackCollector.entities()
                )
            }
        )

        val payload = strategy.loadXtreamLiveFull(provider, xtreamProvider, testRuntimeProfile(batchSize = 2))

        assertThat(payload.catalogResult).isInstanceOf(CatalogStrategyResult.Success::class.java)
        assertThat(payload.stagedSessionId).isEqualTo(9001L)
        assertThat(payload.stagedAcceptedCount).isEqualTo(1)
        assertThat(stagedChannels.map { it.streamId }).containsExactly(777L)
        assertThat(stagedChannels.single().streamUrl).isEqualTo("xtream://42/live/777?ext=m3u8")
        assertThat(stagedChannels.single().streamUrl).doesNotContain("src=")
        assertThat(requestCount.get()).isEqualTo(3)
    }

    @Test
    fun `initial low tier live sync attempts streamed full catalog first`() = runTest {
        val requestCount = AtomicInteger(0)
        val httpService = OkHttpXtreamApiService(
            client = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    requestCount.incrementAndGet()
                    val body = """
                        [
                          {
                            "num": "7",
                            "name": "Thin Live",
                            "stream_id": "777",
                            "stream_icon": "https://img.example.test/live.png",
                            "category_id": "12",
                            "category_name": "News",
                            "container_extension": "m3u8"
                          }
                        ]
                    """.trimIndent()
                    Response.Builder()
                        .request(Request.Builder().url(chain.request().url).build())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody("application/json".toMediaType()))
                        .build()
                }
                .build(),
            json = json
        )
        val apiService = FakeXtreamApiService()
        val provider = Provider(
            id = 42L,
            name = "Xtream",
            type = ProviderType.XTREAM_CODES,
            serverUrl = "https://example.test",
            username = "user",
            password = "pass",
            allowedOutputFormats = listOf("m3u8", "ts")
        )
        val xtreamProvider = XtreamProvider(
            providerId = provider.id,
            api = apiService,
            serverUrl = provider.serverUrl,
            username = provider.username,
            password = provider.password,
            allowedOutputFormats = provider.allowedOutputFormats
        )
        val stagedChannels = mutableListOf<Channel>()
        val strategy = createStrategy(
            apiService = apiService,
            httpService = httpService,
            stageChannelItems = { _, channels, _, fallbackCollector, _ ->
                stagedChannels += channels
                channels.forEach { channel ->
                    fallbackCollector.record(channel.categoryId, channel.categoryName, channel.isAdult)
                }
                StagedCatalogSnapshot(
                    sessionId = 1L,
                    acceptedCount = channels.size,
                    fallbackCategories = fallbackCollector.entities()
                )
            }
        )

        val payload = strategy.syncXtreamLiveCatalog(
            provider = provider,
            api = xtreamProvider,
            existingMetadata = SyncMetadata(provider.id),
            hiddenLiveCategoryIds = emptySet(),
            onProgress = null,
            runtimeProfile = testRuntimeProfile(
                tier = DeviceSyncTier.LOW,
                batchSize = 100,
                maxCategoryConcurrency = 1
            ),
            trackInitialLiveOnboarding = true
        )

        assertThat(payload.catalogResult).isInstanceOf(CatalogStrategyResult.Success::class.java)
        assertThat(payload.strategyFeedback.attemptedFullCatalog).isTrue()
        assertThat(payload.strategyFeedback.preferredSegmentedFirst).isFalse()
        assertThat(stagedChannels.map { it.streamId }).containsExactly(777L)
        assertThat(requestCount.get()).isEqualTo(1)
    }

    @Test
    fun `initial live sync skips full catalog and uses categories when device is low on memory`() = runBlocking {
        val requestCount = AtomicInteger(0)
        val requestedCategoryIds = mutableListOf<String?>()
        val httpService = OkHttpXtreamApiService(
            client = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    requestCount.incrementAndGet()
                    requestedCategoryIds += chain.request().url.queryParameter("category_id")
                    val body = """
                        [
                          {
                            "num": "9",
                            "name": "Category Live",
                            "stream_id": "999",
                            "stream_icon": "https://img.example.test/category.png",
                            "category_id": "12",
                            "category_name": "News",
                            "container_extension": "ts"
                          }
                        ]
                    """.trimIndent()
                    Response.Builder()
                        .request(Request.Builder().url(chain.request().url).build())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody("application/json".toMediaType()))
                        .build()
                }
                .build(),
            json = json
        )
        val apiService = FakeXtreamApiService(
            liveCategories = listOf(XtreamCategory(categoryId = "12", categoryName = "News"))
        )
        val provider = Provider(
            id = 42L,
            name = "Xtream",
            type = ProviderType.XTREAM_CODES,
            serverUrl = "https://example.test",
            username = "user",
            password = "pass",
            allowedOutputFormats = listOf("m3u8", "ts")
        )
        val xtreamProvider = XtreamProvider(
            providerId = provider.id,
            api = apiService,
            serverUrl = provider.serverUrl,
            username = provider.username,
            password = provider.password,
            allowedOutputFormats = provider.allowedOutputFormats
        )
        val strategy = createStrategy(
            apiService = apiService,
            httpService = httpService,
            stageChannelItems = { _, channels, _, _, _ ->
                StagedCatalogSnapshot(sessionId = 1L, acceptedCount = channels.size, fallbackCategories = null)
            }
        )

        val payload = strategy.syncXtreamLiveCatalog(
            provider = provider,
            api = xtreamProvider,
            existingMetadata = SyncMetadata(provider.id),
            hiddenLiveCategoryIds = emptySet(),
            onProgress = null,
            runtimeProfile = testRuntimeProfile(
                tier = DeviceSyncTier.LOW,
                batchSize = 100,
                maxCategoryConcurrency = 1,
                isCurrentlyLowOnMemory = true
            ),
            trackInitialLiveOnboarding = true
        )

        val result = payload.catalogResult as CatalogStrategyResult.Success
        assertThat(result.strategyName).isEqualTo("category_bulk")
        assertThat(result.items).isEmpty()
        assertThat(payload.stagedSessionId).isEqualTo(1L)
        assertThat(payload.stagedAcceptedCount).isEqualTo(1)
        assertThat(payload.strategyFeedback.attemptedFullCatalog).isFalse()
        assertThat(payload.strategyFeedback.preferredSegmentedFirst).isTrue()
        assertThat(requestedCategoryIds).containsExactly("12")
        assertThat(requestCount.get()).isEqualTo(1)
    }

    @Test
    fun `initial live sync falls back to categories when streamed full catalog fails`() = runBlocking {
        val requestCount = AtomicInteger(0)
        val requestedCategoryIds = mutableListOf<String?>()
        val httpService = OkHttpXtreamApiService(
            client = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    requestCount.incrementAndGet()
                    val categoryId = chain.request().url.queryParameter("category_id")
                    requestedCategoryIds += categoryId
                    val body = if (categoryId == null) {
                        """[{"stream_id":"""
                    } else {
                        """
                            [
                              {
                                "num": "11",
                                "name": "Recovered Live",
                                "stream_id": "1111",
                                "stream_icon": "https://img.example.test/recovered.png",
                                "category_id": "$categoryId",
                                "category_name": "News",
                                "container_extension": "m3u8"
                              }
                            ]
                        """.trimIndent()
                    }
                    Response.Builder()
                        .request(Request.Builder().url(chain.request().url).build())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody("application/json".toMediaType()))
                        .build()
                }
                .build(),
            json = json
        )
        val apiService = FakeXtreamApiService(
            liveCategories = listOf(XtreamCategory(categoryId = "12", categoryName = "News"))
        )
        val provider = Provider(
            id = 42L,
            name = "Xtream",
            type = ProviderType.XTREAM_CODES,
            serverUrl = "https://example.test",
            username = "user",
            password = "pass",
            allowedOutputFormats = listOf("m3u8", "ts")
        )
        val xtreamProvider = XtreamProvider(
            providerId = provider.id,
            api = apiService,
            serverUrl = provider.serverUrl,
            username = provider.username,
            password = provider.password,
            allowedOutputFormats = provider.allowedOutputFormats
        )
        val strategy = createStrategy(
            apiService = apiService,
            httpService = httpService,
            stageChannelItems = { _, channels, _, _, _ ->
                StagedCatalogSnapshot(sessionId = 1L, acceptedCount = channels.size, fallbackCategories = null)
            }
        )

        val payload = strategy.syncXtreamLiveCatalog(
            provider = provider,
            api = xtreamProvider,
            existingMetadata = SyncMetadata(provider.id),
            hiddenLiveCategoryIds = emptySet(),
            onProgress = null,
            runtimeProfile = testRuntimeProfile(
                tier = DeviceSyncTier.LOW,
                batchSize = 100,
                maxCategoryConcurrency = 1
            ),
            trackInitialLiveOnboarding = true
        )

        val result = payload.catalogResult as CatalogStrategyResult.Success
        assertThat(result.strategyName).isEqualTo("category_bulk")
    assertThat(result.items).isEmpty()
    assertThat(payload.stagedSessionId).isEqualTo(1L)
    assertThat(payload.stagedAcceptedCount).isEqualTo(1)
        assertThat(payload.strategyFeedback.attemptedFullCatalog).isTrue()
        assertThat(payload.strategyFeedback.fullCatalogUnsafe).isTrue()
        assertThat(requestedCategoryIds.filter { it == null }).hasSize(4)
        assertThat(requestedCategoryIds.last()).isEqualTo("12")
        assertThat(requestCount.get()).isEqualTo(5)
    }

    @Test
    fun `initial live sync falls back to categories when memory turns low during full catalog stream`() = runBlocking {
        val requestCount = AtomicInteger(0)
        val requestedCategoryIds = mutableListOf<String?>()
        val memoryLow = java.util.concurrent.atomic.AtomicBoolean(false)
        val httpService = OkHttpXtreamApiService(
            client = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    requestCount.incrementAndGet()
                    val categoryId = chain.request().url.queryParameter("category_id")
                    requestedCategoryIds += categoryId
                    val body = if (categoryId == null) {
                        """
                            [
                              {
                                "num": "1",
                                "name": "Full Live One",
                                "stream_id": "1001",
                                "stream_icon": "https://img.example.test/full-one.png",
                                "category_id": "12",
                                "category_name": "News",
                                "container_extension": "m3u8"
                              },
                              {
                                "num": "2",
                                "name": "Full Live Two",
                                "stream_id": "1002",
                                "stream_icon": "https://img.example.test/full-two.png",
                                "category_id": "12",
                                "category_name": "News",
                                "container_extension": "m3u8"
                              }
                            ]
                        """.trimIndent()
                    } else {
                        """
                            [
                              {
                                "num": "9",
                                "name": "Recovered Category Live",
                                "stream_id": "1999",
                                "stream_icon": "https://img.example.test/recovered.png",
                                "category_id": "$categoryId",
                                "category_name": "News",
                                "container_extension": "ts"
                              }
                            ]
                        """.trimIndent()
                    }
                    Response.Builder()
                        .request(Request.Builder().url(chain.request().url).build())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody("application/json".toMediaType()))
                        .build()
                }
                .build(),
            json = json
        )
        val apiService = FakeXtreamApiService(
            liveCategories = listOf(XtreamCategory(categoryId = "12", categoryName = "News"))
        )
        val provider = Provider(
            id = 42L,
            name = "Xtream",
            type = ProviderType.XTREAM_CODES,
            serverUrl = "https://example.test",
            username = "user",
            password = "pass",
            allowedOutputFormats = listOf("m3u8", "ts")
        )
        val xtreamProvider = XtreamProvider(
            providerId = provider.id,
            api = apiService,
            serverUrl = provider.serverUrl,
            username = provider.username,
            password = provider.password,
            allowedOutputFormats = provider.allowedOutputFormats
        )
        val stagedFullStreams = mutableListOf<Long>()
        val strategy = createStrategy(
            apiService = apiService,
            httpService = httpService,
            isCurrentlyLowOnMemory = { memoryLow.get() },
            stageChannelItems = { _, channels, _, fallbackCollector, _ ->
                stagedFullStreams += channels.map { it.streamId }
                channels.forEach { channel ->
                    fallbackCollector.record(channel.categoryId, channel.categoryName, channel.isAdult)
                }
                if (channels.isNotEmpty()) {
                    memoryLow.set(true)
                }
                StagedCatalogSnapshot(sessionId = 1L, acceptedCount = channels.size, fallbackCategories = fallbackCollector.entities())
            }
        )

        val payload = strategy.syncXtreamLiveCatalog(
            provider = provider,
            api = xtreamProvider,
            existingMetadata = SyncMetadata(provider.id),
            hiddenLiveCategoryIds = emptySet(),
            onProgress = null,
            runtimeProfile = testRuntimeProfile(
                tier = DeviceSyncTier.LOW,
                batchSize = 1,
                maxCategoryConcurrency = 1
            ),
            trackInitialLiveOnboarding = true
        )

        val result = payload.catalogResult as CatalogStrategyResult.Success
        assertThat(result.strategyName).isEqualTo("category_bulk")
        assertThat(result.items).isEmpty()
        assertThat(payload.stagedSessionId).isEqualTo(1L)
        assertThat(payload.stagedAcceptedCount).isEqualTo(1)
        assertThat(payload.strategyFeedback.attemptedFullCatalog).isTrue()
        assertThat(payload.strategyFeedback.fullCatalogUnsafe).isTrue()
        assertThat(stagedFullStreams).containsExactly(1001L, 1999L).inOrder()
        assertThat(requestedCategoryIds).containsExactly(null, "12")
        assertThat(requestCount.get()).isEqualTo(2)

    }

    @Test
    fun `category live sync stages batches while streaming category rows`() = runBlocking {
        val stageCalls = mutableListOf<List<Long>>()
        val httpService = OkHttpXtreamApiService(
            client = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val body = """
                        [
                          {
                            "num": "1",
                            "name": "Live One",
                            "stream_id": "1001",
                            "stream_icon": "https://img.example.test/one.png",
                            "category_id": "12",
                            "category_name": "News",
                            "container_extension": "m3u8"
                          },
                          {
                            "num": "2",
                            "name": "Live Two",
                            "stream_id": "1002",
                            "stream_icon": "https://img.example.test/two.png",
                            "category_id": "12",
                            "category_name": "News",
                            "container_extension": "m3u8"
                          },
                          {
                            "num": "3",
                            "name": "Live Three",
                            "stream_id": "1003",
                            "stream_icon": "https://img.example.test/three.png",
                            "category_id": "12",
                            "category_name": "News",
                            "container_extension": "m3u8"
                          }
                        ]
                    """.trimIndent()
                    Response.Builder()
                        .request(Request.Builder().url(chain.request().url).build())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody("application/json".toMediaType()))
                        .build()
                }
                .build(),
            json = json
        )
        val apiService = FakeXtreamApiService(
            liveCategories = listOf(XtreamCategory(categoryId = "12", categoryName = "News"))
        )
        val provider = Provider(
            id = 42L,
            name = "Xtream",
            type = ProviderType.XTREAM_CODES,
            serverUrl = "https://example.test",
            username = "user",
            password = "pass",
            allowedOutputFormats = listOf("m3u8", "ts")
        )
        val xtreamProvider = XtreamProvider(
            providerId = provider.id,
            api = apiService,
            serverUrl = provider.serverUrl,
            username = provider.username,
            password = provider.password,
            allowedOutputFormats = provider.allowedOutputFormats
        )
        val strategy = createStrategy(
            apiService = apiService,
            httpService = httpService,
            stageChannelItems = { _, channels, _, fallbackCollector, sessionId ->
                stageCalls += channels.map { it.streamId }
                channels.forEach { channel ->
                    fallbackCollector.record(channel.categoryId, channel.categoryName, channel.isAdult)
                }
                StagedCatalogSnapshot(
                    sessionId = sessionId ?: 77L,
                    acceptedCount = channels.size,
                    fallbackCategories = fallbackCollector.entities()
                )
            }
        )

        val payload = strategy.syncXtreamLiveCatalog(
            provider = provider,
            api = xtreamProvider,
            existingMetadata = SyncMetadata(provider.id),
            hiddenLiveCategoryIds = emptySet(),
            onProgress = null,
            runtimeProfile = testRuntimeProfile(
                tier = DeviceSyncTier.LOW,
                batchSize = 2,
                maxCategoryConcurrency = 1
            ),
            trackInitialLiveOnboarding = true,
            effectiveLiveSyncMethod = EffectiveXtreamLiveSyncMethod.CATEGORY_BY_CATEGORY
        )

        val result = payload.catalogResult as CatalogStrategyResult.Success
        assertThat(result.items).isEmpty()
        assertThat(payload.stagedSessionId).isEqualTo(77L)
        assertThat(payload.stagedAcceptedCount).isEqualTo(3)
        assertThat(stageCalls).containsExactly(listOf(1001L, 1002L), listOf(1003L)).inOrder()
    }

    @Test
    fun `forced category mode skips full catalog and requests visible categories only`() = runBlocking {
        val requestCount = AtomicInteger(0)
        val requestedCategoryIds = mutableListOf<String?>()
        val httpService = OkHttpXtreamApiService(
            client = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    requestCount.incrementAndGet()
                    val categoryId = chain.request().url.queryParameter("category_id")
                    requestedCategoryIds += categoryId
                    val body = """
                        [
                          {
                            "num": "9",
                            "name": "Category Live $categoryId",
                            "stream_id": "${if (categoryId == "12") "912" else "913"}",
                            "stream_icon": "https://img.example.test/category-$categoryId.png",
                            "category_id": "$categoryId",
                            "category_name": "${if (categoryId == "12") "News" else "Sports"}",
                            "container_extension": "ts"
                          }
                        ]
                    """.trimIndent()
                    Response.Builder()
                        .request(Request.Builder().url(chain.request().url).build())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody("application/json".toMediaType()))
                        .build()
                }
                .build(),
            json = json
        )
        val apiService = FakeXtreamApiService(
            liveCategories = listOf(
                XtreamCategory(categoryId = "12", categoryName = "News"),
                XtreamCategory(categoryId = "13", categoryName = "Sports")
            )
        )
        val provider = Provider(
            id = 42L,
            name = "Xtream",
            type = ProviderType.XTREAM_CODES,
            serverUrl = "https://example.test",
            username = "user",
            password = "pass",
            allowedOutputFormats = listOf("m3u8", "ts")
        )
        val xtreamProvider = XtreamProvider(
            providerId = provider.id,
            api = apiService,
            serverUrl = provider.serverUrl,
            username = provider.username,
            password = provider.password,
            allowedOutputFormats = provider.allowedOutputFormats
        )
        val strategy = createStrategy(
            apiService = apiService,
            httpService = httpService,
            stageChannelItems = { _, channels, _, _, sessionId ->
                StagedCatalogSnapshot(sessionId = sessionId ?: 44L, acceptedCount = channels.size, fallbackCategories = null)
            }
        )

        val payload = strategy.syncXtreamLiveCatalog(
            provider = provider,
            api = xtreamProvider,
            existingMetadata = SyncMetadata(provider.id),
            hiddenLiveCategoryIds = setOf(13L),
            onProgress = null,
            runtimeProfile = testRuntimeProfile(
                tier = DeviceSyncTier.HIGH,
                batchSize = 100,
                maxCategoryConcurrency = 2
            ),
            trackInitialLiveOnboarding = true,
            effectiveLiveSyncMethod = EffectiveXtreamLiveSyncMethod.CATEGORY_BY_CATEGORY
        )

        val result = payload.catalogResult as CatalogStrategyResult.Success
        assertThat(result.strategyName).isEqualTo("category_bulk")
        assertThat(payload.strategyFeedback.attemptedFullCatalog).isFalse()
        assertThat(payload.strategyFeedback.preferredSegmentedFirst).isTrue()
        assertThat(payload.stagedAcceptedCount).isEqualTo(1)
        assertThat(requestedCategoryIds).containsExactly("12")
        assertThat(requestCount.get()).isEqualTo(1)

        requestedCategoryIds.clear()
        requestCount.set(0)
        val restorePayload = strategy.syncXtreamLiveCatalog(
            provider = provider,
            api = xtreamProvider,
            existingMetadata = SyncMetadata(provider.id),
            hiddenLiveCategoryIds = setOf(13L),
            onProgress = null,
            runtimeProfile = testRuntimeProfile(
                tier = DeviceSyncTier.HIGH,
                batchSize = 100,
                maxCategoryConcurrency = 2
            ),
            trackInitialLiveOnboarding = false,
            effectiveLiveSyncMethod = EffectiveXtreamLiveSyncMethod.STREAM_ALL,
            requiredHiddenLiveCategoryIds = setOf(13L)
        )

        assertThat(restorePayload.stagedAcceptedCount).isEqualTo(2)
        assertThat(requestedCategoryIds).containsExactly("12", "13")
        Unit
    }

    @Test
    fun `category strategy preserves shell category names when live rows omit category_name`() = runBlocking {
        val httpService = OkHttpXtreamApiService(
            client = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val categoryId = chain.request().url.queryParameter("category_id")
                    val body = """
                        [
                          {
                            "num": "9",
                            "name": "Category Live $categoryId",
                            "stream_id": "912",
                            "stream_icon": "https://img.example.test/category-$categoryId.png",
                            "category_id": "$categoryId",
                            "container_extension": "ts"
                          }
                        ]
                    """.trimIndent()
                    Response.Builder()
                        .request(Request.Builder().url(chain.request().url).build())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody("application/json".toMediaType()))
                        .build()
                }
                .build(),
            json = json
        )
        val apiService = FakeXtreamApiService(
            liveCategories = listOf(
                XtreamCategory(categoryId = "12", categoryName = "News")
            )
        )
        val provider = Provider(
            id = 42L,
            name = "Xtream",
            type = ProviderType.XTREAM_CODES,
            serverUrl = "https://example.test",
            username = "user",
            password = "pass",
            allowedOutputFormats = listOf("m3u8", "ts")
        )
        val xtreamProvider = XtreamProvider(
            providerId = provider.id,
            api = apiService,
            serverUrl = provider.serverUrl,
            username = provider.username,
            password = provider.password,
            allowedOutputFormats = provider.allowedOutputFormats
        )
        val strategy = createStrategy(
            apiService = apiService,
            httpService = httpService,
            stageChannelItems = { _, channels, _, fallbackCollector, sessionId ->
                channels.forEach { channel ->
                    fallbackCollector.record(channel.categoryId, channel.categoryName, channel.isAdult)
                }
                StagedCatalogSnapshot(
                    sessionId = sessionId ?: 44L,
                    acceptedCount = channels.size,
                    fallbackCategories = fallbackCollector.entities()
                )
            }
        )

        val payload = strategy.syncXtreamLiveCatalog(
            provider = provider,
            api = xtreamProvider,
            existingMetadata = SyncMetadata(provider.id),
            hiddenLiveCategoryIds = emptySet(),
            onProgress = null,
            runtimeProfile = testRuntimeProfile(
                tier = DeviceSyncTier.HIGH,
                batchSize = 100,
                maxCategoryConcurrency = 1
            ),
            trackInitialLiveOnboarding = true,
            effectiveLiveSyncMethod = EffectiveXtreamLiveSyncMethod.CATEGORY_BY_CATEGORY
        )

        assertThat(payload.catalogResult).isInstanceOf(CatalogStrategyResult.Success::class.java)
        assertThat(payload.categories).isNotNull()
        assertThat(payload.categories!!.single().categoryId).isEqualTo(12L)
        assertThat(payload.categories!!.single().name).isEqualTo("News")
    }

    @Test
    fun `forced stream all attempts full catalog on otherwise segmented device`() = runBlocking {
        val requestCount = AtomicInteger(0)
        val requestedCategoryIds = mutableListOf<String?>()
        val httpService = OkHttpXtreamApiService(
            client = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    requestCount.incrementAndGet()
                    requestedCategoryIds += chain.request().url.queryParameter("category_id")
                    val body = """
                        [
                          {
                            "num": "7",
                            "name": "Forced Full Live",
                            "stream_id": "777",
                            "stream_icon": "https://img.example.test/live.png",
                            "category_id": "12",
                            "category_name": "News",
                            "container_extension": "m3u8"
                          }
                        ]
                    """.trimIndent()
                    Response.Builder()
                        .request(Request.Builder().url(chain.request().url).build())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody("application/json".toMediaType()))
                        .build()
                }
                .build(),
            json = json
        )
        val apiService = FakeXtreamApiService(
            liveCategories = listOf(
                XtreamCategory(categoryId = "12", categoryName = "News"),
                XtreamCategory(categoryId = "13", categoryName = "Sports")
            )
        )
        val provider = Provider(
            id = 42L,
            name = "Xtream",
            type = ProviderType.XTREAM_CODES,
            serverUrl = "https://example.test",
            username = "user",
            password = "pass",
            allowedOutputFormats = listOf("m3u8", "ts")
        )
        val xtreamProvider = XtreamProvider(
            providerId = provider.id,
            api = apiService,
            serverUrl = provider.serverUrl,
            username = provider.username,
            password = provider.password,
            allowedOutputFormats = provider.allowedOutputFormats
        )
        val strategy = createStrategy(
            apiService = apiService,
            httpService = httpService,
            stageChannelItems = { _, channels, _, fallbackCollector, _ ->
                channels.forEach { channel ->
                    fallbackCollector.record(channel.categoryId, channel.categoryName, channel.isAdult)
                }
                StagedCatalogSnapshot(sessionId = 55L, acceptedCount = channels.size, fallbackCategories = fallbackCollector.entities())
            }
        )

        val payload = strategy.syncXtreamLiveCatalog(
            provider = provider,
            api = xtreamProvider,
            existingMetadata = SyncMetadata(provider.id),
            hiddenLiveCategoryIds = emptySet(),
            onProgress = null,
            runtimeProfile = testRuntimeProfile(
                tier = DeviceSyncTier.LOW,
                batchSize = 100,
                maxCategoryConcurrency = 1,
                preferSegmentedLiveOnboarding = true
            ),
            trackInitialLiveOnboarding = true,
            effectiveLiveSyncMethod = EffectiveXtreamLiveSyncMethod.STREAM_ALL
        )

        assertThat(payload.catalogResult).isInstanceOf(CatalogStrategyResult.Success::class.java)
        assertThat(payload.strategyFeedback.attemptedFullCatalog).isTrue()
        assertThat(payload.strategyFeedback.preferredSegmentedFirst).isFalse()
        assertThat(payload.stagedAcceptedCount).isEqualTo(1)
        assertThat(requestedCategoryIds).containsExactly(null)
        assertThat(requestCount.get()).isEqualTo(1)
    }

    @Test
    fun `hidden categories skip full catalog even when effective method is stream all`() = runBlocking {
        val requestCount = AtomicInteger(0)
        val requestedCategoryIds = mutableListOf<String?>()
        val httpService = OkHttpXtreamApiService(
            client = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    requestCount.incrementAndGet()
                    val categoryId = chain.request().url.queryParameter("category_id")
                    requestedCategoryIds += categoryId
                    val body = """
                        [
                          {
                            "num": "8",
                            "name": "Visible Live",
                            "stream_id": "888",
                            "stream_icon": "https://img.example.test/live.png",
                            "category_id": "$categoryId",
                            "category_name": "News",
                            "container_extension": "m3u8"
                          }
                        ]
                    """.trimIndent()
                    Response.Builder()
                        .request(Request.Builder().url(chain.request().url).build())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody("application/json".toMediaType()))
                        .build()
                }
                .build(),
            json = json
        )
        val apiService = FakeXtreamApiService(
            liveCategories = listOf(
                XtreamCategory(categoryId = "12", categoryName = "News"),
                XtreamCategory(categoryId = "13", categoryName = "Sports")
            )
        )
        val provider = Provider(
            id = 42L,
            name = "Xtream",
            type = ProviderType.XTREAM_CODES,
            serverUrl = "https://example.test",
            username = "user",
            password = "pass",
            allowedOutputFormats = listOf("m3u8", "ts")
        )
        val xtreamProvider = XtreamProvider(
            providerId = provider.id,
            api = apiService,
            serverUrl = provider.serverUrl,
            username = provider.username,
            password = provider.password,
            allowedOutputFormats = provider.allowedOutputFormats
        )
        val strategy = createStrategy(
            apiService = apiService,
            httpService = httpService,
            stageChannelItems = { _, channels, _, _, sessionId ->
                StagedCatalogSnapshot(sessionId = sessionId ?: 66L, acceptedCount = channels.size, fallbackCategories = null)
            }
        )

        val payload = strategy.syncXtreamLiveCatalog(
            provider = provider,
            api = xtreamProvider,
            existingMetadata = SyncMetadata(provider.id),
            hiddenLiveCategoryIds = setOf(13L),
            onProgress = null,
            runtimeProfile = testRuntimeProfile(
                tier = DeviceSyncTier.HIGH,
                batchSize = 100,
                maxCategoryConcurrency = 2
            ),
            trackInitialLiveOnboarding = false,
            effectiveLiveSyncMethod = EffectiveXtreamLiveSyncMethod.STREAM_ALL
        )

        assertThat(payload.catalogResult).isInstanceOf(CatalogStrategyResult.Success::class.java)
        assertThat(payload.strategyFeedback.attemptedFullCatalog).isFalse()
        assertThat(payload.strategyFeedback.preferredSegmentedFirst).isTrue()
        assertThat(payload.stagedAcceptedCount).isEqualTo(1)
        assertThat(requestedCategoryIds).containsExactly("12")
        assertThat(requestCount.get()).isEqualTo(1)
    }

    private fun createStrategy(
        apiService: XtreamApiService,
        httpService: OkHttpXtreamApiService,
        isCurrentlyLowOnMemory: () -> Boolean = { false },
        stageChannelItems: suspend (Long, List<Channel>, MutableSet<Long>, FallbackCategoryCollector, Long?) -> StagedCatalogSnapshot
    ): SyncManagerXtreamLiveStrategy {
        val adaptivePolicy = XtreamAdaptiveSyncPolicy()
        val sanitize: (Throwable?) -> String = { throwable -> throwable?.message.orEmpty() }
        val progress: (Long, ((String) -> Unit)?, String) -> Unit = { _, callback, message -> callback?.invoke(message) }
        val support = SyncManagerXtreamSupport(
            adaptiveSyncPolicy = adaptivePolicy,
            shouldRememberSequentialPreference = { false },
            sanitizeThrowableMessage = sanitize,
            progress = progress,
            movieRequestTimeoutMillis = 60_000L,
            seriesRequestTimeoutMillis = 60_000L,
            recoveryAbortWarningSuffix = "stopped early."
        )
        return SyncManagerXtreamLiveStrategy(
            xtreamCatalogApiService = apiService,
            xtreamCatalogHttpService = httpService,
            xtreamAdaptiveSyncPolicy = adaptivePolicy,
            xtreamSupport = support,
            xtreamFetcher = SyncManagerXtreamFetcher(apiService, httpService, support, sanitize),
            catalogStrategySupport = SyncManagerCatalogStrategySupport(
                shouldRememberSequentialPreference = { false },
                avoidFullCatalogCooldownMillis = 60_000L
            ),
            syncCatalogStore = SyncCatalogStore(
                channelDao = mock<ChannelDao>(),
                movieDao = mock<MovieDao>(),
                seriesDao = mock<SeriesDao>(),
                categoryDao = mock<CategoryDao>(),
                catalogSyncDao = mock<CatalogSyncDao>(),
                tmdbIdentityDao = mock<TmdbIdentityDao>(),
                transactionRunner = object : DatabaseTransactionRunner {
                    override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
                }
            ),
            progress = progress,
            sanitizeThrowableMessage = sanitize,
            fullCatalogFallbackWarning = { section, error -> "$section full catalog failed: ${sanitize(error)}" },
            categoryFailureWarning = { section, category, error -> "$section category $category failed: ${sanitize(error)}" },
            liveCategorySequentialModeWarning = "Live category sync used sequential mode.",
            isCurrentlyLowOnMemory = isCurrentlyLowOnMemory,
            stageChannelItems = stageChannelItems,
            emitProgress = { _, _ -> }
        )
    }

    private fun testRuntimeProfile(
        tier: DeviceSyncTier = DeviceSyncTier.HIGH,
        batchSize: Int = 500,
        maxCategoryConcurrency: Int = Int.MAX_VALUE,
        preferSegmentedLiveOnboarding: Boolean = false,
        isCurrentlyLowOnMemory: Boolean = false
    ): CatalogSyncRuntimeProfile = CatalogSyncRuntimeProfile(
        tier = tier,
        stageBatchSize = batchSize,
        maxCategoryConcurrency = maxCategoryConcurrency,
        preferSegmentedLiveOnboarding = preferSegmentedLiveOnboarding,
        deferBackgroundWorkOnLowMemory = false,
        snapshot = SyncDeviceSnapshot(
            memoryClassMb = 512,
            largeMemoryClassMb = 512,
            isLowRamDevice = false,
            isCurrentlyLowOnMemory = isCurrentlyLowOnMemory,
            availableMemMb = 1024L,
            maxHeapMb = 512L,
            isTelevision = true,
            manufacturer = "test",
            model = "test",
            sdkInt = 35
        )
    )

    private class FakeXtreamApiService(
        private val liveCategories: List<XtreamCategory> = emptyList()
    ) : XtreamApiService {
        override suspend fun authenticate(endpoint: String, requestProfile: HttpRequestProfile): XtreamAuthResponse {
            return XtreamAuthResponse(XtreamUserInfo(auth = 1), XtreamServerInfo())
        }

        override suspend fun getLiveCategories(endpoint: String, requestProfile: HttpRequestProfile): List<XtreamCategory> = liveCategories

        override suspend fun getLiveStreams(endpoint: String, requestProfile: HttpRequestProfile): List<XtreamStream> = emptyList()

        override suspend fun getVodCategories(endpoint: String, requestProfile: HttpRequestProfile): List<XtreamCategory> = emptyList()

        override suspend fun getVodStreams(endpoint: String, requestProfile: HttpRequestProfile): List<XtreamStream> = emptyList()

        override suspend fun getVodInfo(endpoint: String, requestProfile: HttpRequestProfile): XtreamVodInfoResponse = XtreamVodInfoResponse()

        override suspend fun getSeriesCategories(endpoint: String, requestProfile: HttpRequestProfile): List<XtreamCategory> = emptyList()

        override suspend fun getSeriesList(endpoint: String, requestProfile: HttpRequestProfile): List<XtreamSeriesItem> = emptyList()

        override suspend fun getSeriesInfo(endpoint: String, requestProfile: HttpRequestProfile): XtreamSeriesInfoResponse = XtreamSeriesInfoResponse()

        override suspend fun getShortEpg(endpoint: String, requestProfile: HttpRequestProfile): XtreamEpgResponse = XtreamEpgResponse()

        override suspend fun getFullEpg(endpoint: String, requestProfile: HttpRequestProfile): XtreamEpgResponse = XtreamEpgResponse()
    }
}
