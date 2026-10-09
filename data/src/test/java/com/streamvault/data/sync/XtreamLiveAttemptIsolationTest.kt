package com.streamvault.data.sync

import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.RoomDatabaseTransactionRunner
import com.streamvault.data.local.StreamVaultDatabase
import com.streamvault.data.local.entity.ProviderEntity
import com.streamvault.data.local.entity.ChannelEntity
import com.streamvault.data.local.entity.XtreamLiveOnboardingStateEntity
import com.streamvault.data.remote.dto.XtreamCategory
import com.streamvault.data.remote.xtream.OkHttpXtreamApiService
import com.streamvault.data.remote.xtream.XtreamProvider
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.LegacyProvider
import com.streamvault.domain.model.ProviderType
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class XtreamLiveAttemptIsolationTest {
    private lateinit var db: StreamVaultDatabase
    private lateinit var store: SyncCatalogStore
    private val provider = LegacyProvider(id = 1, name = "Synthetic", type = ProviderType.XTREAM_CODES,
        serverUrl = "https://fixture.invalid", username = "synthetic", password = "synthetic")

    @Before
    fun setUp() = runBlocking<Unit> {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), StreamVaultDatabase::class.java)
            .allowMainThreadQueries().build()
        db.providerDao().insert(ProviderEntity(id = provider.id, name = provider.name, type = provider.type))
        store = SyncCatalogStore(db.channelDao(), db.movieDao(), db.seriesDao(), db.categoryDao(),
            db.catalogSyncDao(), db.tmdbIdentityDao(), RoomDatabaseTransactionRunner(db))
    }

    @After
    fun close() {
        db.close()
        RuntimeEnvironment.getApplication().deleteDatabase("xtream-receipt-reopen-test")
    }

    @Test
    fun `full retry replaces failed rows and resets duplicate admission`() = runBlocking<Unit> {
        val requests = AtomicInteger()
        val (strategy, api) = engine { _ ->
            if (requests.incrementAndGet() == 1) array(row(101), row(102)).dropLast(1) + ","
            else array(row(102, "fresh"), row(103))
        }
        val result = strategy.loadXtreamLiveFull(provider, api, profile())
        assertThat(result.catalogResult).isInstanceOf(CatalogStrategyResult.Success::class.java)
        assertThat(requests.get()).isEqualTo(2)
        assertThat(result.stagedAcceptedCount).isEqualTo(2)
        val rows = db.catalogSyncDao().getChannelStages(provider.id, requireNotNull(result.stagedSessionId))
        assertThat(rows.map { it.streamId }).containsExactly(102L, 103L)
        assertThat(rows.single { it.streamId == 102L }.name).startsWith("fresh")
        assertThat(totalStages()).isEqualTo(2)
    }

    @Test
    fun `failed category decoder attempt cannot overwrite successful category or retain partial rows`() = runBlocking<Unit> {
        val attempts = AtomicInteger()
        val (strategy, api) = engine { category ->
            when {
                category == "1" -> array(row(101, "completed", category = 1))
                attempts.incrementAndGet() == 1 -> array(row(101, "failed", 2), row(202, category = 2)).dropLast(1) + ","
                else -> array(row(203, category = 2))
            }
        }
        val result = strategy.loadXtreamLiveByCategory(provider, api, categories(), null, true, profile())
        assertThat(result.catalogResult).isInstanceOf(CatalogStrategyResult.Success::class.java)
        assertThat(result.stagedAcceptedCount).isEqualTo(2)
        val rows = db.catalogSyncDao().getChannelStages(provider.id, requireNotNull(result.stagedSessionId))
        assertThat(rows.map { it.streamId }).containsExactly(101L, 203L)
        assertThat(rows.single { it.streamId == 101L }.name).startsWith("completed")
        assertThat(totalStages()).isEqualTo(2)
    }

    @Test
    fun `permanent category failure publishes only completed scopes as partial`() = runBlocking<Unit> {
        db.channelDao().insertAll(listOf(ChannelEntity(streamId = 900, name = "Existing", streamUrl = "old", providerId = provider.id)))
        val (strategy, api) = engine { category ->
            if (category == "1") array(row(101)) else array(row(202, category = 2)).dropLast(1) + ","
        }
        val result = strategy.loadXtreamLiveByCategory(provider, api, categories(), null, true, profile())
        assertThat(result.catalogResult).isInstanceOf(CatalogStrategyResult.Partial::class.java)
        assertThat(result.stagedAcceptedCount).isEqualTo(1)
        assertThat(db.catalogSyncDao().getChannelStages(provider.id, requireNotNull(result.stagedSessionId)).map { it.streamId })
            .containsExactly(101L)
        assertThat(totalStages()).isEqualTo(1)
        store.applyStagedLiveCatalogUpsertOnly(provider.id, requireNotNull(result.stagedSessionId), result.categories)
        assertThat(db.channelDao().getByProviderSync(provider.id).map { it.streamId }).containsExactly(900L, 101L)
    }

    @Test
    fun `superseded full writer stops without retries or legacy download`() = runBlocking<Unit> {
        val requests = AtomicInteger()
        val (strategy, api) = engine(failWriter = true) { requests.incrementAndGet(); array(row(101), row(102)) }
        var failure: Throwable? = null
        try { strategy.loadXtreamLiveFull(provider, api, profile()) } catch (error: CancellationException) { failure = error }
        assertThat(failure).isInstanceOf(ProviderWorkflowSupersededException::class.java)
        assertThat(requests.get()).isEqualTo(1)
        assertThat(totalStages()).isEqualTo(0)
    }

    @Test
    fun `category supersession discards completed scopes and stops further requests`() = runBlocking<Unit> {
        val requests = AtomicInteger()
        val (strategy, api) = engine(failOnStreamId = 202L) { category ->
            requests.incrementAndGet()
            if (category == "1") array(row(101)) else array(row(202, category = 2))
        }
        val failure = runCatching {
            strategy.loadXtreamLiveByCategory(provider, api, categories(), null, true, profile())
        }.exceptionOrNull()
        assertThat(failure).isInstanceOf(ProviderWorkflowSupersededException::class.java)
        assertThat(requests.get()).isEqualTo(2)
        assertThat(totalStages()).isEqualTo(0)
    }

    @Test
    fun `sealed snapshot and metadata survive closing the database before promotion`() = runBlocking<Unit> {
        val context = RuntimeEnvironment.getApplication()
        val name = "xtream-receipt-reopen-test"
        db.close()
        context.deleteDatabase(name)
        fun open() = Room.databaseBuilder(context, StreamVaultDatabase::class.java, name).allowMainThreadQueries().build()
        fun catalogStore() = SyncCatalogStore(db.channelDao(), db.movieDao(), db.seriesDao(), db.categoryDao(),
            db.catalogSyncDao(), db.tmdbIdentityDao(), RoomDatabaseTransactionRunner(db))
        db = open()
        db.providerDao().insert(ProviderEntity(id = provider.id, name = provider.name, type = provider.type))
        store = catalogStore()
        val (strategy, api) = engine { array(row(101), row(102)) }
        val result = strategy.loadXtreamLiveFull(provider, api, profile())
        val session = requireNotNull(result.stagedSessionId)
        val receipt = XtreamLiveOnboardingStateEntity(providerId = provider.id, phase = "STAGED", stagedSessionId = session,
            importStrategy = "full", acceptedRowCount = 2, sealedSessionId = session, sealedRowCount = 2,
            sealedConfigurationGeneration = 1, sealedMappingProfile = "text=false;base64=false")
        store.sealCompletedLiveImport(provider.id, session, 2, result.categories) { db.xtreamLiveOnboardingDao().upsert(receipt) }
        db.close()
        db = open()
        store = catalogStore()
        assertThat(db.xtreamLiveOnboardingDao().getIncompleteByProvider(provider.id)).isEqualTo(receipt)
        val saved = store.stagedLiveImportState(provider.id, session)
        assertThat(saved.channelCount).isEqualTo(2)
        assertThat(saved.categories).isNotEmpty()
        store.applyStagedLiveCatalog(provider.id, session, saved.categories)
        assertThat(db.channelDao().getByProviderSync(provider.id).map { it.streamId }).containsExactly(101L, 102L)
        assertThat(totalStages()).isEqualTo(0)
    }

    @Test
    fun `snapshot receipt and category metadata are committed together only for matching row count`() = runBlocking<Unit> {
        val (strategy, api) = engine { array(row(101), row(102)) }
        val result = strategy.loadXtreamLiveFull(provider, api, profile())
        val session = requireNotNull(result.stagedSessionId)
        val receipt = XtreamLiveOnboardingStateEntity(providerId = provider.id, phase = "STAGED", stagedSessionId = session,
            importStrategy = "full", acceptedRowCount = 2, sealedSessionId = session, sealedRowCount = 2,
            sealedConfigurationGeneration = 1, sealedMappingProfile = "text=false;base64=false")
        var rejected = false
        try {
            store.sealCompletedLiveImport(provider.id, session, 3, result.categories) { db.xtreamLiveOnboardingDao().upsert(receipt) }
        } catch (_: IllegalStateException) { rejected = true }
        assertThat(rejected).isTrue()
        assertThat(db.xtreamLiveOnboardingDao().getByProvider(provider.id)).isNull()
        store.sealCompletedLiveImport(provider.id, session, 2, result.categories) { db.xtreamLiveOnboardingDao().upsert(receipt) }
        assertThat(db.xtreamLiveOnboardingDao().getByProvider(provider.id)).isEqualTo(receipt)
        assertThat(db.catalogSyncDao().getCategoryStages(provider.id, session, "LIVE")).isNotEmpty()
    }

    private suspend fun totalStages(): Int = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM channel_import_stage").use {
        it.moveToFirst(); it.getInt(0)
    }

    private fun row(id: Long, name: String = "Channel", category: Int = 1): String =
        """{"stream_id":$id,"name":"$name ${"x".repeat(600)}","category_id":"$category","container_extension":"m3u8"}"""

    private fun array(vararg rows: String) = rows.joinToString(",", "[", "]")
    private fun categories() = listOf(XtreamCategory("1", "One"), XtreamCategory("2", "Two"))

    private suspend fun engine(failWriter: Boolean = false, failOnStreamId: Long? = null,
        response: (String?) -> String): Pair<SyncManagerXtreamLiveStrategy, XtreamProvider> {
        val http = OkHttpXtreamApiService(OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(response(chain.request().url.queryParameter("category_id")).toResponseBody("application/json".toMediaType())).build()
        }.build(), Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false; coerceInputValues = true })
        val api = XtreamProvider(provider.id, http, provider.serverUrl, provider.username, provider.password)
        api.mapCategories(ContentType.LIVE, categories())
        val policy = XtreamAdaptiveSyncPolicy()
        val progress: (Long, ((String) -> Unit)?, String) -> Unit = { _, callback, label -> callback?.invoke(label) }
        val sanitize: (Throwable?) -> String = { it?.javaClass?.simpleName.orEmpty() }
        val support = SyncManagerXtreamSupport(policy, { false }, sanitize, progress, 60_000, 60_000, "stopped")
        val stager = SyncManagerCatalogStager(store, 1)
        return SyncManagerXtreamLiveStrategy(http, http, policy, support, SyncManagerXtreamFetcher(http, http, support, sanitize),
            SyncManagerCatalogStrategySupport({ false }, 60_000), store, progress, sanitize,
            { _, _ -> "full failed" }, { _, _, _ -> "category failed" }, "sequential", stageChannelItems = { id, channels, seen, collector, session ->
                if (failWriter || channels.any { it.streamId == failOnStreamId }) throw ProviderWorkflowSupersededException(id, 1)
                stager.stageChannelItems(id, channels, seen, collector, session)
            }, emitProgress = { _, _ -> }) to api
    }

    private fun profile() = CatalogSyncRuntimeProfile(DeviceSyncTier.HIGH, 1, 1, false, false,
        SyncDeviceSnapshot(512, 512, false, false, 1024, 512, true, "fixture", "fixture", 35))
}
