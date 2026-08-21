package com.streamvault.data.sync

import com.google.common.truth.Truth.assertThat
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import com.streamvault.data.local.DatabaseTransactionRunner
import com.streamvault.data.local.dao.CatalogSyncDao
import com.streamvault.data.local.dao.ChannelStageCategorySummary
import com.streamvault.data.local.dao.CategoryDao
import com.streamvault.data.local.dao.ChannelDao
import com.streamvault.data.local.dao.EpisodeDao
import com.streamvault.data.local.dao.MovieCategoryHydrationDao
import com.streamvault.data.local.dao.MovieDao
import com.streamvault.data.local.dao.ProgramDao
import com.streamvault.data.local.dao.ProviderDao
import com.streamvault.data.local.dao.SeriesCategoryHydrationDao
import com.streamvault.data.local.dao.SeriesDao
import com.streamvault.data.local.dao.StalkerIndexJobDao
import com.streamvault.data.local.dao.StalkerRemoteIdentityDao
import com.streamvault.data.local.dao.TmdbIdentityDao
import com.streamvault.data.local.dao.VodCategoryHydrationDao
import com.streamvault.data.local.dao.VodCatalogEntryDao
import com.streamvault.data.local.dao.XtreamContentIndexDao
import com.streamvault.data.local.dao.XtreamIndexJobDao
import com.streamvault.data.local.dao.XtreamLiveOnboardingDao
import com.streamvault.data.local.entity.CategoryEntity
import com.streamvault.data.local.entity.ChannelEntity
import com.streamvault.data.local.entity.ChannelGuideSyncEntity
import com.streamvault.data.local.entity.MovieCategoryHydrationEntity
import com.streamvault.data.local.entity.MovieEntity
import com.streamvault.data.local.entity.ProviderEntity
import com.streamvault.data.mapper.toEntity
import com.streamvault.data.provider.toProviderSnapshot
import com.streamvault.data.provider.TypedProviderClientFactory
import com.streamvault.data.remote.xtream.OkHttpXtreamApiService
import com.streamvault.data.local.entity.SeriesCategoryHydrationEntity
import com.streamvault.data.local.entity.StalkerIndexJobEntity
import com.streamvault.data.local.entity.StalkerPortalStateEntity
import com.streamvault.data.local.entity.StalkerRemoteIdentityEntity
import com.streamvault.data.local.entity.XtreamIndexJobEntity
import com.streamvault.data.local.entity.XtreamLiveOnboardingStateEntity
import com.streamvault.data.parser.M3uParser
import com.streamvault.data.security.CredentialCrypto
import com.streamvault.domain.model.SyncState
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.ProviderEpgSyncMode
import com.streamvault.domain.model.ProviderXtreamLiveSyncMode
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.LegacyProvider as Provider
import com.streamvault.domain.model.CatalogLayout
import com.streamvault.domain.model.StalkerPortalLearning
import com.streamvault.domain.repository.ProviderSnapshotRepository
import com.streamvault.domain.model.StalkerIndexState
import com.streamvault.domain.model.StalkerTransportMode
import com.streamvault.domain.model.SyncMetadata
import com.streamvault.domain.repository.EpgRepository
import com.streamvault.domain.repository.EpgSourceRepository
import com.streamvault.data.remote.stalker.StalkerCategoryRecord
import com.streamvault.data.remote.stalker.StalkerItemRecord
import com.streamvault.data.remote.stalker.StalkerPagedItems
import com.streamvault.data.remote.stalker.StalkerProgramRecord
import com.streamvault.data.remote.stalker.StalkerProviderProfile
import com.streamvault.data.remote.stalker.StalkerRemoteIdentityResolver
import com.streamvault.data.remote.stalker.stalkerStableHashId
import com.streamvault.data.remote.stalker.StalkerSession
import com.streamvault.data.remote.stalker.StalkerApiService
import com.streamvault.data.remote.stalker.StalkerAdvancedOptions
import com.streamvault.data.remote.stalker.StalkerAdvancedOptionsCodec
import com.streamvault.data.remote.stalker.StalkerDeviceProfile
import com.streamvault.data.remote.jellyfin.JellyfinProvider
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.EpgSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.fail
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.atLeast
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.check
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.reset
import org.mockito.kotlin.verify
import java.io.IOException
import java.util.zip.GZIPOutputStream

/**
 * Unit tests for [SyncManager] state machine transitions.
 *
 * Strategy:
 * - Uses an in-memory [FakeProviderDao] — no Room needed
 * - Real [M3uParser] (pure JVM)
 * - Mockito-kotlin mocks for network/DAO collaborators
 * - All sync calls expected to FAIL (mocks return nulls/errors by default)
 *   because we're testing state transitions, not data correctness
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncManagerTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    // ── In-memory fake ──────────────────────────────────────────────

    private class FakeProviderDao(
        provider: Provider? = sampleProvider()
    ) : ProviderDao() {
        private val provider = provider?.toEntity()
        override suspend fun getById(id: Long): ProviderEntity? = provider
        override fun getByIdSync(id: Long): ProviderEntity? = provider?.takeIf { it.id == id }
        override suspend fun getByIds(ids: List<Long>): List<ProviderEntity> =
            listOfNotNull(provider).filter { it.id in ids }
        override suspend fun updateSyncTime(id: Long, timestamp: Long) = Unit
        override fun getAll() = kotlinx.coroutines.flow.flowOf(listOfNotNull(provider))
        override suspend fun getAllSync(): List<ProviderEntity> = listOfNotNull(provider)
        override fun getActive() = kotlinx.coroutines.flow.flowOf(provider)
        override fun getByTypeSync(type: ProviderType): List<ProviderEntity> =
            listOfNotNull(provider).filter { it.type == type }
        override suspend fun insertDirect(provider: ProviderEntity) = this.provider?.id ?: 0L
        override suspend fun updateDirect(provider: ProviderEntity) = Unit
        override suspend fun insert(provider: ProviderEntity) = this.provider?.id ?: 0L
        override suspend fun update(provider: ProviderEntity) = Unit
        override suspend fun delete(id: Long) = Unit
        override suspend fun deactivateAll() = Unit
        override suspend fun activate(id: Long) = Unit
        override suspend fun setActive(id: Long) = Unit
    }

    private class FakeStalkerRemoteIdentityDao : StalkerRemoteIdentityDao {
        private val rows = mutableListOf<StalkerRemoteIdentityEntity>()

        override suspend fun getByRawId(providerId: Long, contentType: String, rawId: String) =
            rows.firstOrNull { row ->
                row.providerId == providerId && row.contentType.name == contentType && row.rawId == rawId
            }

        override suspend fun getBySurrogateId(providerId: Long, contentType: String, surrogateId: Long) =
            rows.firstOrNull { row ->
                row.providerId == providerId && row.contentType.name == contentType && row.surrogateId == surrogateId
            }

        override suspend fun maxAllocatedSurrogate(providerId: Long, contentType: String, floor: Long): Long? =
            rows.asSequence()
                .filter { row ->
                    row.providerId == providerId && row.contentType.name == contentType && row.surrogateId >= floor
                }
                .maxOfOrNull(StalkerRemoteIdentityEntity::surrogateId)

        override suspend fun insert(entity: StalkerRemoteIdentityEntity) {
            check(rows.none { row ->
                row.providerId == entity.providerId &&
                    row.contentType == entity.contentType &&
                    (row.rawId == entity.rawId || row.surrogateId == entity.surrogateId)
            })
            rows += entity
        }
    }

    companion object {
        fun sampleProvider(type: ProviderType = ProviderType.XTREAM_CODES) = Provider(
            id = 1L, name = "Test", type = type,
            serverUrl = "https://test.example.com:8080",
            username = "demo", password = "demo",
            catalogLayoutDetectionVersion = com.streamvault.data.remote.stalker.StalkerProvider.CATALOG_LAYOUT_DETECTION_VERSION
        )

        fun stalkerSyntheticCategoryId(providerId: Long, type: ContentType, seed: String): Long {
            return stalkerStableHashId(providerId, type, seed)
        }
    }

    private class FakeSyncMetadataRepository : com.streamvault.domain.repository.SyncMetadataRepository {
        private val values = mutableMapOf<Long, SyncMetadata>()

        override fun observeMetadata(providerId: Long): Flow<SyncMetadata?> = flowOf(values[providerId])

        override suspend fun getMetadata(providerId: Long): SyncMetadata? = values[providerId]

        override suspend fun updateMetadata(metadata: SyncMetadata) {
            values[metadata.providerId] = metadata
        }

        override suspend fun clearMetadata(providerId: Long) {
            values.remove(providerId)
        }

        fun reset() {
            values.clear()
        }
    }

    private class FakeXtreamBackend {
        private data class StubbedResponse(
            val code: Int,
            val body: String,
            val contentType: String = "application/json"
        )

        private val stubs = mutableMapOf<String, MutableList<StubbedResponse>>()
        val requestedActions = mutableListOf<String>()

        fun respond(action: String, body: String, code: Int = 200) {
            stubs.getOrPut(action) { mutableListOf() }
                .add(StubbedResponse(code = code, body = body))
        }

        fun requestCount(): Int = requestedActions.size

        fun reset() {
            stubs.clear()
            requestedActions.clear()
        }

        fun okHttpClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    val action = request.url.queryParameter("action").orEmpty()
                    requestedActions += action
                    val queued = stubs[action]
                    val stub = when {
                        queued == null -> StubbedResponse(
                            code = 500,
                            body = """{"error":"missing stub for $action"}"""
                        )
                        queued.size > 1 -> queued.removeAt(0)
                        else -> queued.first()
                    }
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(stub.code)
                        .message(if (stub.code in 200..299) "OK" else "ERROR")
                        .body(stub.body.toResponseBody(stub.contentType.toMediaType()))
                        .build()
                }
                .build()
        }
    }

    // Mockito mocks — all return defaults (null/0/Unit), which will cause
    // the sync pipeline to throw and transition to Error state.
    private val channelDao: ChannelDao = mock()
    private val movieDao: MovieDao = mock()
    private val seriesDao: SeriesDao = mock()
    private val episodeDao: EpisodeDao = mock()
    private val programDao: ProgramDao = mock()
    private val categoryDao: CategoryDao = mock()
    private val catalogSyncDao: CatalogSyncDao = mock()
    private val tmdbIdentityDao: TmdbIdentityDao = mock()
    private val xtreamContentIndexDao: XtreamContentIndexDao = mock()
    private val xtreamIndexJobDao: XtreamIndexJobDao = mock()
    private val xtreamLiveOnboardingDao: XtreamLiveOnboardingDao = mock()
    private val movieCategoryHydrationDao: MovieCategoryHydrationDao = mock()
    private val seriesCategoryHydrationDao: SeriesCategoryHydrationDao = mock()
    private val vodCategoryHydrationDao: VodCategoryHydrationDao = mock()
    private val vodCatalogEntryDao: VodCatalogEntryDao = mock()
    private val epgRepo: EpgRepository = mock()
    private val epgSourceRepo: EpgSourceRepository = mock()
    private val preferencesRepo: PreferencesRepository = mock()
    private val stalkerApiService: StalkerApiService = mock()
    private val jellyfinProvider: JellyfinProvider = mock()
    private val xtreamBackend = FakeXtreamBackend()
    private val xtreamJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    private val applicationContext: Context = mock()
    private val packageManager: PackageManager = mock()
    private val resources: Resources = mock()
    private val transactionRunner = object : DatabaseTransactionRunner {
        override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
    }
    private val credentialCrypto = object : CredentialCrypto {
        override fun encryptIfNeeded(value: String): String = value
        override fun decryptIfNeeded(value: String): String = value
    }
    private val syncMetadataRepo = FakeSyncMetadataRepository()

    @Before
    fun setup() {
        xtreamBackend.reset()
        syncMetadataRepo.reset()
        reset(
            channelDao,
            movieDao,
            seriesDao,
            programDao,
            categoryDao,
            catalogSyncDao,
            tmdbIdentityDao,
            xtreamContentIndexDao,
            xtreamIndexJobDao,
            xtreamLiveOnboardingDao,
            epgRepo,
            epgSourceRepo,
            preferencesRepo,
            stalkerApiService,
            applicationContext,
            packageManager,
            resources
        )
        org.mockito.kotlin.whenever(applicationContext.packageManager).thenReturn(packageManager)
        org.mockito.kotlin.whenever(applicationContext.resources).thenReturn(resources)
        org.mockito.kotlin.whenever(applicationContext.getSystemService(Context.ACTIVITY_SERVICE)).thenReturn(null)
        org.mockito.kotlin.whenever(applicationContext.getSystemService(Context.UI_MODE_SERVICE)).thenReturn(null)
        org.mockito.kotlin.whenever(packageManager.hasSystemFeature(any())).thenAnswer { invocation ->
            invocation.getArgument<String>(0) == PackageManager.FEATURE_TOUCHSCREEN
        }
        org.mockito.kotlin.whenever(resources.configuration).thenReturn(
            Configuration().apply {
                screenWidthDp = 500
            }
        )
        org.mockito.kotlin.whenever(preferencesRepo.useXtreamTextClassification).thenReturn(flowOf(false))
        org.mockito.kotlin.whenever(preferencesRepo.xtreamBase64TextCompatibility).thenReturn(flowOf(false))
        org.mockito.kotlin.whenever(preferencesRepo.getHiddenCategoryIds(any(), any())).thenReturn(flowOf(emptySet()))
        runBlocking {
            org.mockito.kotlin.whenever(categoryDao.getByProviderAndTypeSync(any(), any())).thenReturn(emptyList())
            org.mockito.kotlin.whenever(channelDao.getCount(any())).thenReturn(flowOf(0))
            org.mockito.kotlin.whenever(channelDao.getByProviderSync(any())).thenReturn(emptyList())
            org.mockito.kotlin.whenever(movieDao.getCount(any())).thenReturn(flowOf(0))
            org.mockito.kotlin.whenever(movieDao.getCountByCategory(any(), any())).thenReturn(flowOf(0))
            org.mockito.kotlin.whenever(movieDao.getByProviderSync(any())).thenReturn(emptyList())
            org.mockito.kotlin.whenever(movieDao.getTmdbIdsByProvider(any())).thenReturn(emptyList())
            org.mockito.kotlin.whenever(seriesDao.getCount(any())).thenReturn(flowOf(0))
            org.mockito.kotlin.whenever(seriesDao.getCountByCategory(any(), any())).thenReturn(flowOf(0))
            org.mockito.kotlin.whenever(seriesDao.getByProviderSync(any())).thenReturn(emptyList())
            org.mockito.kotlin.whenever(seriesDao.getTmdbIdsByProvider(any())).thenReturn(emptyList())
            org.mockito.kotlin.whenever(catalogSyncDao.getCategoryStages(any(), any(), any())).thenReturn(emptyList())
            org.mockito.kotlin.whenever(catalogSyncDao.getChannelStages(any(), any())).thenReturn(emptyList())
            org.mockito.kotlin.whenever(catalogSyncDao.getMovieStages(any(), any())).thenReturn(emptyList())
            org.mockito.kotlin.whenever(catalogSyncDao.getSeriesStages(any(), any())).thenReturn(emptyList())
            org.mockito.kotlin.whenever(catalogSyncDao.countChannelStages(any(), any())).thenReturn(0)
            org.mockito.kotlin.whenever(catalogSyncDao.countMovieStages(any(), any())).thenReturn(0)
            org.mockito.kotlin.whenever(catalogSyncDao.countSeriesStages(any(), any())).thenReturn(0)
            org.mockito.kotlin.whenever(catalogSyncDao.countCategoryStages(any(), any(), any())).thenReturn(0)
            org.mockito.kotlin.whenever(catalogSyncDao.getChannelStageCategorySummaries(any(), any())).thenReturn(emptyList())
            org.mockito.kotlin.whenever(xtreamContentIndexDao.pruneStaleLocalContentRows(any(), any())).thenReturn(0)
            // Default stubs for the streamed Stalker API methods. The tests in this file
            // generally don't stress live-stream streaming directly; without these defaults
            // the unstubbed mock returns null which crashes the `when (val streamResult)`
            // exhaustiveness check in SyncManager with NoWhenBranchMatchedException.
            org.mockito.kotlin.whenever(stalkerApiService.streamLiveStreams(any(), any(), any()))
                .thenReturn(Result.success(0))
            org.mockito.kotlin.whenever(stalkerApiService.getVodCategories(any(), any()))
                .thenReturn(Result.success(emptyList()))
            org.mockito.kotlin.whenever(stalkerApiService.getSeriesCategories(any(), any()))
                .thenReturn(Result.success(emptyList()))
            org.mockito.kotlin.whenever(
                stalkerApiService.streamBulkEpg(any(), any(), any(), any())
            ).thenReturn(Result.success(0))
            org.mockito.kotlin.whenever(
                stalkerApiService.streamEpg(any(), any(), any(), any(), any())
            ).thenReturn(Result.success(0))
            org.mockito.kotlin.whenever(epgSourceRepo.refreshAllForProvider(any())).thenReturn(Result.success(Unit))
            org.mockito.kotlin.whenever(epgSourceRepo.resolveForProvider(any(), any()))
                .thenReturn(com.streamvault.domain.model.EpgResolutionSummary())
            org.mockito.kotlin.whenever(epgSourceRepo.getAllSources()).thenReturn(flowOf(emptyList()))
            org.mockito.kotlin.whenever(epgSourceRepo.getAssignmentsForProvider(any())).thenReturn(flowOf(emptyList()))
            org.mockito.kotlin.whenever(epgSourceRepo.addSource(any(), any(), any(), anyOrNull())).thenAnswer { invocation ->
                Result.success(
                    EpgSource(
                        id = 1L,
                        name = invocation.getArgument(0),
                        url = invocation.getArgument(1)
                    )
                )
            }
            org.mockito.kotlin.whenever(epgSourceRepo.assignSourceToProvider(any(), any(), any()))
                .thenReturn(Result.success(Unit))
        }
    }

    private fun buildManager(
        providerType: ProviderType = ProviderType.XTREAM_CODES,
        providerPresent: Boolean = true,
        providerEntity: Provider? = null,
        readinessTracker: StalkerReadinessTracker = StalkerReadinessTracker(),
        portalStateStore: com.streamvault.data.remote.stalker.StalkerPortalStateStore = mock()
    ): SyncManager {
        val configuredProvider = if (providerPresent) providerEntity ?: sampleProvider(providerType) else null
        val stalkerRemoteIdentityResolver = StalkerRemoteIdentityResolver(
            dao = FakeStalkerRemoteIdentityDao(),
            transactionRunner = transactionRunner,
            categoryDao = categoryDao
        )
        val typedProviderClientFactory = TypedProviderClientFactory(
            xtreamApiService = OkHttpXtreamApiService(xtreamBackend.okHttpClient(), xtreamJson),
            stalkerApiService = stalkerApiService,
            jellyfinProvider = jellyfinProvider,
            preferencesRepository = preferencesRepo,
            stalkerRemoteIdentityResolver = stalkerRemoteIdentityResolver,
            stalkerPortalStateStore = portalStateStore
        )
        return SyncManager(
        applicationContext = applicationContext,
        providerDao = FakeProviderDao(
            configuredProvider
        ),
        channelDao = channelDao,
        movieDao = movieDao,
        seriesDao = seriesDao,
        programDao = programDao,
        categoryDao = categoryDao,
        movieCategoryHydrationDao = movieCategoryHydrationDao,
        seriesCategoryHydrationDao = seriesCategoryHydrationDao,
        vodCategoryHydrationDao = vodCategoryHydrationDao,
        vodCatalogEntryDao = vodCatalogEntryDao,
        catalogSyncDao = catalogSyncDao,
        tmdbIdentityDao = tmdbIdentityDao,
        xtreamContentIndexDao = xtreamContentIndexDao,
        xtreamIndexJobDao = xtreamIndexJobDao,
        stalkerIndexJobStore = StalkerIndexJobStore(object : StalkerIndexJobDao {
            override fun observeForProvider(providerId: Long): Flow<List<StalkerIndexJobEntity>> = flowOf(emptyList())

            override suspend fun get(providerId: Long, section: String): StalkerIndexJobEntity? =
                xtreamIndexJobDao.get(providerId, section)?.let { job ->
                    StalkerIndexJobEntity(
                        providerId = job.providerId,
                        section = ContentType.valueOf(job.section),
                        state = when (job.state) {
                            "QUEUED", "STALE" -> StalkerIndexState.QUEUED
                            "RUNNING" -> StalkerIndexState.RUNNING
                            "FAILED_RETRYABLE" -> StalkerIndexState.RETRY_WAIT
                            "PARTIAL" -> StalkerIndexState.PARTIAL
                            "SUCCESS", "COMPLETE" -> StalkerIndexState.COMPLETE
                            "TRUNCATED" -> StalkerIndexState.TRUNCATED
                            "FAILED", "FAILED_PERMANENT" -> StalkerIndexState.FAILED
                            else -> StalkerIndexState.DISABLED
                        },
                        totalCategories = job.totalCategories,
                        completedCategories = job.completedCategories,
                        nextCategoryIndex = job.nextCategoryIndex,
                        failedCategories = job.failedCategories,
                        indexedRows = job.indexedRows,
                        skippedMalformedRows = job.skippedMalformedRows,
                        deletedPrunedRows = job.deletedPrunedRows,
                        lastError = job.lastError,
                        lastAttemptAt = job.lastAttemptAt,
                        lastSuccessAt = job.lastSuccessAt,
                        updatedAt = job.updatedAt
                    )
                }

            override suspend fun getNextPending(providerId: Long): StalkerIndexJobEntity? =
                listOf(ContentType.MOVIE, ContentType.SERIES)
                    .mapNotNull { section -> get(providerId, section.name) }
                    .filter { it.state in setOf(StalkerIndexState.QUEUED, StalkerIndexState.RETRY_WAIT, StalkerIndexState.PARTIAL) }
                    .minByOrNull(StalkerIndexJobEntity::updatedAt)

            override suspend fun upsert(entity: StalkerIndexJobEntity) {
                xtreamIndexJobDao.upsert(
                    XtreamIndexJobEntity(
                        providerId = entity.providerId,
                        section = entity.section.name,
                        state = when (entity.state) {
                            StalkerIndexState.DISABLED -> "DISABLED"
                            StalkerIndexState.QUEUED -> "QUEUED"
                            StalkerIndexState.RUNNING -> "RUNNING"
                            StalkerIndexState.RETRY_WAIT -> "FAILED_RETRYABLE"
                            StalkerIndexState.PARTIAL -> "PARTIAL"
                            StalkerIndexState.COMPLETE -> "SUCCESS"
                            StalkerIndexState.TRUNCATED -> "TRUNCATED"
                            StalkerIndexState.FAILED -> "FAILED_PERMANENT"
                        },
                        totalCategories = entity.totalCategories,
                        completedCategories = entity.completedCategories,
                        nextCategoryIndex = entity.nextCategoryIndex,
                        failedCategories = entity.failedCategories,
                        indexedRows = entity.indexedRows,
                        skippedMalformedRows = entity.skippedMalformedRows,
                        deletedPrunedRows = entity.deletedPrunedRows,
                        lastError = entity.lastError,
                        lastAttemptAt = entity.lastAttemptAt,
                        lastSuccessAt = entity.lastSuccessAt,
                        updatedAt = entity.updatedAt
                    )
                )
            }

            override suspend fun upsertAll(entities: List<StalkerIndexJobEntity>) {
                entities.forEach { upsert(it) }
            }

            override suspend fun disableForProvider(providerId: Long, updatedAt: Long): Int = 0
            override suspend fun deleteByProvider(providerId: Long): Int = 0
        }),
        xtreamLiveOnboardingDao = xtreamLiveOnboardingDao,
        episodeDao = episodeDao,
        jellyfinProvider = jellyfinProvider,
        xtreamJson = xtreamJson,
        m3uParser = M3uParser(),
        epgRepository = epgRepo,
        epgSourceRepository = epgSourceRepo,
        okHttpClient = xtreamBackend.okHttpClient(),
        credentialCrypto = credentialCrypto,
        syncMetadataRepository = syncMetadataRepo,
        transactionRunner = transactionRunner,
        preferencesRepository = preferencesRepo,
        syncProgressBus = SyncProgressBus(),
        stalkerRequestCoordinator = com.streamvault.data.remote.stalker.StalkerRequestCoordinator(),
        stalkerPortalStateStore = portalStateStore,
        stalkerReadinessTracker = readinessTracker,
        providerWorkflowCommitFence = ProviderWorkflowCommitFence(),
        typedProviderClientFactory = typedProviderClientFactory,
        providerSnapshotRepository = object : ProviderSnapshotRepository {
            override suspend fun getSnapshot(providerId: Long) =
                configuredProvider?.takeIf { it.id == providerId }?.toProviderSnapshot()

            override suspend fun compareAndSetStalkerLearning(
                providerId: Long,
                learning: StalkerPortalLearning
            ): Boolean = configuredProvider?.id == providerId

            override suspend fun updateCatalogLayout(providerId: Long, layout: CatalogLayout, detectionVersion: Int) = Unit
        },
        providerWorkLocks = ProviderWorkLockRegistry(),
        providerSyncLocks = ProviderSyncLockRegistry(),
        stalkerPlaybackCapabilityCache = com.streamvault.data.provider.StalkerPlaybackCapabilityCache(typedProviderClientFactory),
        providerSyncWorkScheduler = mock()
    )
    }

    // ── Initial state ───────────────────────────────────────────────

    private fun stubXtreamLiveCatalog() {
        xtreamBackend.respond(action = "get_live_categories", body = """[{"category_id":"1","category_name":"News"}]""")
        xtreamBackend.respond(
            action = "get_live_streams",
            body = """
                [
                  {
                    "name": "Channel One",
                    "stream_id": 1001,
                    "category_id": "1",
                    "stream_icon": "https://example.com/ch1.png",
                    "tv_archive": 0,
                    "num": 1
                  }
                ]
            """.trimIndent()
        )
    }

    private fun stubXtreamEmptyVodAndSeriesCategories() {
        xtreamBackend.respond(action = "get_vod_categories", body = "[]")
        xtreamBackend.respond(action = "get_series_categories", body = "[]")
    }

    @Test
    fun `initialState_isIdle`() = runTest {
        val mgr = buildManager()
        assertThat(mgr.currentSyncState(1L)).isEqualTo(SyncState.Idle)
    }

    // ── Provider not found ──────────────────────────────────────────

    @Test
    fun `sync_providerNotFound_returnsError_stateRemainsIdle`() = runTest {
        val mgr = buildManager(providerPresent = false)

        val result = mgr.sync(99L)

        assertThat(result.isError).isTrue()
        // State must NOT transition away from Idle (no provider = nothing to sync)
        assertThat(mgr.currentSyncState(99L)).isEqualTo(SyncState.Idle)
    }

    @Test
    fun sync_stalker_rebuilt_provider_respects_editable_setup_options() = runTest {
        val advancedOptionsJson = StalkerAdvancedOptionsCodec.encode(
            StalkerAdvancedOptions(
                apiUserAgent = "API Agent/9.0",
                playerUserAgent = "Player Agent/10.0",
                hwVersion = "1.2.3",
                xUserAgentLink = StalkerAdvancedOptions.LINK_WIFI,
                proxyEnabled = true,
                proxyHost = "127.0.0.1",
                proxyPort = 8888
            )
        )
        val providerEntity = sampleProvider(ProviderType.STALKER_PORTAL).copy(
            serverUrl = "http://example.com",
            username = "",
            password = "",
            httpUserAgent = "Legacy Agent/1.0",
            httpHeaders = "X-Test: enabled",
            stalkerMacAddress = "00:11:22:33:44:55",
            stalkerDeviceProfile = "MAG254",
            stalkerDeviceTimezone = "UTC",
            stalkerDeviceLocale = "en",
            stalkerSerialNumber = "SERIAL123",
            stalkerDeviceId = "AABBCC",
            stalkerDeviceId2 = "DDEEFF",
            stalkerAdvancedOptionsJson = advancedOptionsJson,
            epgSyncMode = ProviderEpgSyncMode.SKIP
        )
        val mgr = buildManager(providerType = ProviderType.STALKER_PORTAL, providerEntity = providerEntity)

        mgr.sync(1L, force = true)
        advanceUntilIdle()

        val profileCaptor = argumentCaptor<StalkerDeviceProfile>()
        verify(stalkerApiService, atLeastOnce()).authenticate(profileCaptor.capture())
        val profile = profileCaptor.firstValue
        assertThat(profile.httpUserAgent).isEqualTo("Legacy Agent/1.0")
        assertThat(profile.httpHeaders).isEqualTo("X-Test: enabled")
        assertThat(profile.headerOverrides).containsEntry("X-Test", "enabled")
        assertThat(profile.advancedOptions.apiUserAgent).isEqualTo("API Agent/9.0")
        assertThat(profile.advancedOptions.playerUserAgent).isEqualTo("Player Agent/10.0")
        assertThat(profile.advancedOptions.hwVersion).isEqualTo("1.2.3")
        assertThat(profile.advancedOptions.proxy?.host).isEqualTo("127.0.0.1")
        assertThat(profile.advancedOptions.proxy?.port).isEqualTo(8888)
        assertThat(profile.xUserAgent).isEqualTo("Model: MAG254; Link: WiFi")
        assertThat(profile.serialNumber).isEqualTo("SERIAL123")
        assertThat(profile.deviceId).isEqualTo("AABBCC")
        assertThat(profile.deviceId2).isEqualTo("DDEEFF")
    }

    @Test
    fun sync_stalker_rebuilt_provider_carries_persisted_transport_grant() = runTest {
        val providerEntity = sampleProvider(ProviderType.STALKER_PORTAL).copy(
            serverUrl = "http://example.com",
            stalkerMacAddress = "00:11:22:33:44:55",
            stalkerTransportMode = StalkerTransportMode.USER_ACCEPTED_HTTP,
            stalkerTransportOrigin = "http://example.com:80",
            stalkerTransportConsentAt = 123456789L,
            epgSyncMode = ProviderEpgSyncMode.SKIP
        )
        val mgr = buildManager(providerType = ProviderType.STALKER_PORTAL, providerEntity = providerEntity)

        mgr.sync(1L, force = true)
        advanceUntilIdle()

        val profileCaptor = argumentCaptor<StalkerDeviceProfile>()
        verify(stalkerApiService, atLeastOnce()).authenticate(profileCaptor.capture())
        val grant = profileCaptor.firstValue.transportGrant
        assertThat(grant).isNotNull()
        assertThat(grant!!.mode).isEqualTo(StalkerTransportMode.USER_ACCEPTED_HTTP)
        assertThat(grant.origin.authority).isEqualTo("http://example.com:80")
        assertThat(grant.consentedAt).isEqualTo(123456789L)
    }

    @Test
    fun sync_stalker_rebuilt_provider_omits_transport_grant_for_auto_strict() = runTest {
        val providerEntity = sampleProvider(ProviderType.STALKER_PORTAL).copy(
            serverUrl = "https://example.com",
            stalkerMacAddress = "00:11:22:33:44:55",
            stalkerTransportMode = StalkerTransportMode.AUTO_STRICT,
            stalkerTransportOrigin = "",
            epgSyncMode = ProviderEpgSyncMode.SKIP
        )
        val mgr = buildManager(providerType = ProviderType.STALKER_PORTAL, providerEntity = providerEntity)

        mgr.sync(1L, force = true)
        advanceUntilIdle()

        val profileCaptor = argumentCaptor<StalkerDeviceProfile>()
        verify(stalkerApiService, atLeastOnce()).authenticate(profileCaptor.capture())
        assertThat(profileCaptor.firstValue.transportGrant).isNull()
    }

    // ── Xtream sync failure ─────────────────────────────────────────

    @Test
    fun `sync_xtream_networksError_transitionsToPartial`() = runTest {
        val mgr = buildManager(providerType = ProviderType.XTREAM_CODES)

        // The Xtream path calls XtreamProvider(xtreamApi,...).getLiveCategories()
        // Since xtreamApi is a mock with null returns, the call throws → manager catches → Error
        val result = mgr.sync(1L)
        advanceUntilIdle()

        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat(mgr.currentSyncState(1L)).isInstanceOf(SyncState.Partial::class.java)
    }

    @Test
    fun `sync_xtream_with_empty_live_catalog_still_queues_vod_and_series`() = runTest {
        val mgr = buildManager(providerType = ProviderType.XTREAM_CODES)

        xtreamBackend.respond(action = "get_live_categories", body = "[]")
        xtreamBackend.respond(action = "get_vod_categories", body = """[{"category_id":"42","category_name":"Action"}]""")
        xtreamBackend.respond(action = "get_series_categories", body = """[{"category_id":"77","category_name":"Drama"}]""")

        val result = mgr.sync(1L)

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val queuedJobCaptor = argumentCaptor<XtreamIndexJobEntity>()
        verify(xtreamIndexJobDao, atLeastOnce()).upsert(queuedJobCaptor.capture())
        assertThat(queuedJobCaptor.allValues.any { job ->
            job.section == ContentType.MOVIE.name && job.state == "QUEUED"
        }).isTrue()
        assertThat(queuedJobCaptor.allValues.any { job ->
            job.section == ContentType.SERIES.name && job.state == "QUEUED"
        }).isTrue()
        assertThat(xtreamBackend.requestedActions).contains("get_vod_categories")
        assertThat(xtreamBackend.requestedActions).contains("get_series_categories")
    }

    @Test
    fun `sync_xtream_stateMustPassThroughSyncing_toPartial`() = runTest {
        val mgr = buildManager(providerType = ProviderType.XTREAM_CODES)

        // In TestDispatcher, sync() runs synchronously and state transitions complete
        // before this coroutine resumes. We verify the terminal state is Error —
        // that's only reachable via Syncing, proving the full transition happened.
        mgr.sync(1L)
        advanceUntilIdle()

        val finalState = mgr.currentSyncState(1L)
        assertThat(finalState).isInstanceOf(SyncState.Partial::class.java)
    }

    @Test
    fun `sync_xtream_partialHasNonEmptyMessage`() = runTest {
        val mgr = buildManager(providerType = ProviderType.XTREAM_CODES)
        mgr.sync(1L)
        advanceUntilIdle()

        val state = mgr.currentSyncState(1L) as? SyncState.Partial
        assertThat(state).isNotNull()
        assertThat(state!!.message).isNotEmpty()
    }

    @Test
    fun `sync_xtream_withFreshCache_andForceFalse_runs_index_first_shell`() = runTest {
        val mgr = buildManager(providerType = ProviderType.XTREAM_CODES)
        val now = System.currentTimeMillis()
        stubXtreamLiveCatalog()
        stubXtreamEmptyVodAndSeriesCategories()
        syncMetadataRepo.updateMetadata(
            SyncMetadata(
                providerId = 1L,
                lastLiveSync = now,
                lastLiveSuccess = now,
                lastMovieSync = now,
                lastMovieSuccess = now,
                lastSeriesSync = now,
                lastSeriesSuccess = now,
                lastEpgSync = now,
                lastEpgSuccess = now
            )
        )

        val result = mgr.sync(1L, force = false)
        advanceUntilIdle()

        assertThat(result.isSuccess).isTrue()
        assertThat(xtreamBackend.requestedActions).contains("get_live_categories")
        assertThat(xtreamBackend.requestedActions).contains("get_live_streams")
        assertThat(xtreamBackend.requestedActions).contains("get_vod_categories")
        assertThat(xtreamBackend.requestedActions).contains("get_series_categories")
    }

    @Test
    fun `sync_xtream_tracked_initial_live_onboarding_marks_state_complete`() = runTest {
        val mgr = buildManager(providerType = ProviderType.XTREAM_CODES)
        stubXtreamLiveCatalog()
        stubXtreamEmptyVodAndSeriesCategories()

        val result = mgr.sync(1L, force = false, trackInitialLiveOnboarding = true)
        advanceUntilIdle()

        val states = argumentCaptor<XtreamLiveOnboardingStateEntity>()
        verify(xtreamLiveOnboardingDao, atLeastOnce()).upsert(states.capture())
        val capturedStates = states.allValues
        val completed = capturedStates.last { it.phase == "COMPLETED" }
        assertThat(result.isSuccess).isTrue()
        assertThat(capturedStates.map { it.phase }).containsAtLeast("STARTING", "FETCHING", "COMPLETED")
        assertThat(completed.acceptedRowCount).isEqualTo(1)
        assertThat(completed.completedAt).isGreaterThan(0L)
    }

    @Test
    fun `sync_xtream_tracked_initial_live_onboarding_commits_recovered_staged_session`() = runTest {
        val mgr = buildManager(providerType = ProviderType.XTREAM_CODES)
        stubXtreamEmptyVodAndSeriesCategories()
        org.mockito.kotlin.whenever(xtreamLiveOnboardingDao.getIncompleteByProvider(1L)).thenReturn(
            XtreamLiveOnboardingStateEntity(
                providerId = 1L,
                providerType = ProviderType.XTREAM_CODES.name,
                contentType = ContentType.LIVE.name,
                phase = "STAGED",
                stagedSessionId = 123L,
                importStrategy = "full",
                acceptedRowCount = 1,
                stagedFlushCount = 1
            )
        )
        org.mockito.kotlin.whenever(catalogSyncDao.countChannelStages(1L, 123L)).thenReturn(1)
        org.mockito.kotlin.whenever(catalogSyncDao.getChannelStageCategorySummaries(1L, 123L)).thenReturn(
            listOf(ChannelStageCategorySummary(categoryId = 1L, name = "News", isAdult = false))
        )

        val result = mgr.sync(1L, force = false, trackInitialLiveOnboarding = true)
        advanceUntilIdle()

        val states = argumentCaptor<XtreamLiveOnboardingStateEntity>()
        verify(xtreamLiveOnboardingDao, atLeastOnce()).upsert(states.capture())
        assertThat(result.isSuccess).isTrue()
        assertThat(xtreamBackend.requestedActions).doesNotContain("get_live_categories")
        assertThat(xtreamBackend.requestedActions).doesNotContain("get_live_streams")
        assertThat(states.allValues.map { it.phase }).containsAtLeast("RECOVERING", "COMMITTING", "COMPLETED")
        assertThat(states.allValues.last { it.phase == "COMPLETED" }.stagedSessionId).isNull()
        verify(catalogSyncDao).updateChangedChannelsFromStage(1L, 123L)
        verify(catalogSyncDao).insertMissingChannelsFromStage(1L, 123L)
        verify(catalogSyncDao).deleteStaleChannelsForStage(1L, 123L)
        verify(catalogSyncDao).clearChannelStages(1L, 123L)
    }

    @Test
    fun `sync_xtream_tracked_initial_live_onboarding_discards_missing_staged_session_and_refetches`() = runTest {
        val mgr = buildManager(providerType = ProviderType.XTREAM_CODES)
        stubXtreamLiveCatalog()
        stubXtreamEmptyVodAndSeriesCategories()
        org.mockito.kotlin.whenever(xtreamLiveOnboardingDao.getIncompleteByProvider(1L)).thenReturn(
            XtreamLiveOnboardingStateEntity(
                providerId = 1L,
                providerType = ProviderType.XTREAM_CODES.name,
                contentType = ContentType.LIVE.name,
                phase = "STAGED",
                stagedSessionId = 456L,
                importStrategy = "full",
                acceptedRowCount = 1,
                stagedFlushCount = 1
            )
        )
        org.mockito.kotlin.whenever(catalogSyncDao.countChannelStages(1L, 456L)).thenReturn(0)

        val result = mgr.sync(1L, force = false, trackInitialLiveOnboarding = true)
        advanceUntilIdle()

        assertThat(result.isSuccess).isTrue()
        assertThat(xtreamBackend.requestedActions).contains("get_live_categories")
        assertThat(xtreamBackend.requestedActions).contains("get_live_streams")
        verify(catalogSyncDao).clearChannelStages(1L, 456L)
    }

    @Test
    fun `syncEpg_xtream_success_marks_epg_job_success`() = runTest {
        val mgr = buildManager(providerType = ProviderType.XTREAM_CODES)
        org.mockito.kotlin.whenever(epgRepo.refreshEpg(eq(1L), any())).thenReturn(Result.success(Unit))
        org.mockito.kotlin.whenever(epgSourceRepo.refreshAllForProvider(1L)).thenReturn(Result.success(Unit))
        org.mockito.kotlin.whenever(epgSourceRepo.resolveForProvider(eq(1L), any()))
            .thenReturn(com.streamvault.domain.model.EpgResolutionSummary())
        org.mockito.kotlin.whenever(programDao.countByProvider(1L)).thenReturn(7)

        val result = mgr.syncEpg(1L, force = true)
        advanceUntilIdle()

        val jobs = argumentCaptor<XtreamIndexJobEntity>()
        verify(xtreamIndexJobDao, atLeastOnce()).upsert(jobs.capture())
        assertThat(result.isSuccess).isTrue()
        assertThat(jobs.allValues.any { it.section == "EPG" && it.state == "RUNNING" }).isTrue()
        assertThat(jobs.allValues.last { it.section == "EPG" }.state).isEqualTo("SUCCESS")
        assertThat(jobs.allValues.last { it.section == "EPG" }.indexedRows).isEqualTo(7)
        assertThat(mgr.currentSyncState(1L)).isInstanceOf(SyncState.Success::class.java)
    }

    @Test
    fun `syncEpg_xtream_refresh_error_marks_epg_job_retryable_instead_of_success`() = runTest {
        val mgr = buildManager(providerType = ProviderType.XTREAM_CODES)
        org.mockito.kotlin.whenever(epgRepo.refreshEpg(eq(1L), any()))
            .thenReturn(Result.error("network down", java.io.IOException("network down")))
        org.mockito.kotlin.whenever(epgSourceRepo.refreshAllForProvider(1L)).thenReturn(Result.success(Unit))
        org.mockito.kotlin.whenever(epgSourceRepo.resolveForProvider(eq(1L), any()))
            .thenReturn(com.streamvault.domain.model.EpgResolutionSummary())
        org.mockito.kotlin.whenever(programDao.countByProvider(1L)).thenReturn(0)

        val result = mgr.syncEpg(1L, force = true)
        advanceUntilIdle()

        val jobs = argumentCaptor<XtreamIndexJobEntity>()
        verify(xtreamIndexJobDao, atLeastOnce()).upsert(jobs.capture())
        val finalEpgJob = jobs.allValues.last { it.section == "EPG" }
        val state = mgr.currentSyncState(1L) as SyncState.Partial
        assertThat(result.isSuccess).isTrue()
        assertThat(finalEpgJob.state).isEqualTo("FAILED_RETRYABLE")
        assertThat(finalEpgJob.lastError).contains("EPG XMLTV sync failed")
        assertThat(state.hasRetryableEpgFailure).isTrue()
    }

    @Test
    fun `sync_xtream_partial_live_timestamp_does_not_suppress_retry`() = runTest {
        val mgr = buildManager(providerType = ProviderType.XTREAM_CODES)
        val now = System.currentTimeMillis()
        syncMetadataRepo.updateMetadata(
            SyncMetadata(
                providerId = 1L,
                lastLiveSync = now,
                lastLiveSuccess = 0L,
                lastMovieSync = now,
                lastMovieSuccess = now,
                lastSeriesSync = now,
                lastSeriesSuccess = now,
                lastEpgSync = now,
                lastEpgSuccess = now
            )
        )
        xtreamBackend.respond(action = "get_live_categories", body = """[{"category_id":"1","category_name":"News"}]""")
        xtreamBackend.respond(
            action = "get_live_streams",
            body = """
                [
                  {
                    "name": "Channel One",
                    "stream_id": 1001,
                    "category_id": "1",
                    "stream_icon": "https://example.com/ch1.png",
                    "tv_archive": 0,
                    "num": 1
                  }
                ]
            """.trimIndent()
        )
        stubXtreamEmptyVodAndSeriesCategories()

        val result = mgr.sync(1L, force = false)
        advanceUntilIdle()

        assertThat(result.isSuccess).isTrue()
        assertThat(xtreamBackend.requestedActions).contains("get_live_categories")
        val updated = syncMetadataRepo.getMetadata(1L)
        assertThat(updated?.lastLiveSuccess ?: 0L).isGreaterThan(0L)
    }

    @Test
    fun `retrySection_live_xtream_full_success_keeps_staged_live_count`() = runTest {
        val mgr = buildManager(providerType = ProviderType.XTREAM_CODES)
        stubXtreamLiveCatalog()

        val result = mgr.retrySection(providerId = 1L, section = SyncRepairSection.LIVE)
        advanceUntilIdle()

        assertThat(result.isSuccess).isTrue()
        val updated = syncMetadataRepo.getMetadata(1L)
        assertThat(updated?.liveCount).isEqualTo(1)
        assertThat(mgr.currentSyncState(1L)).isInstanceOf(SyncState.Success::class.java)
    }

    @Test
    fun `unsupported section retry publishes terminal error instead of remaining syncing`() = runTest {
        val mgr = buildManager(providerType = ProviderType.M3U)

        val result = mgr.retrySection(providerId = 1L, section = SyncRepairSection.SERIES)
        advanceUntilIdle()

        assertThat(result.isError).isTrue()
        assertThat(mgr.currentSyncState(1L)).isInstanceOf(SyncState.Error::class.java)
        assertThat(syncMetadataRepo.getMetadata(1L)?.lastSyncStatus).isEqualTo("FAILED_PERMANENT")
    }

    @Test
    fun `retrySection_live_xtream_reports_saving_progress_after_category_download`() = runTest {
        val mgr = buildManager(providerType = ProviderType.XTREAM_CODES)
        val progressMessages = mutableListOf<String>()
        stubXtreamLiveCatalog()

        val result = mgr.retrySection(
            providerId = 1L,
            section = SyncRepairSection.LIVE,
            onProgress = progressMessages::add
        )
        advanceUntilIdle()

        assertThat(result.isSuccess).isTrue()
        assertThat(progressMessages).contains("Saving Live TV channels...")
        assertThat(progressMessages.indexOf("Saving Live TV channels..."))
            .isGreaterThan(progressMessages.indexOf("Downloading Live TV by category 1/1..."))
    }

    @Test
    fun `retrySection_live_xtream_auto_manual_sync_uses_category_mode_on_mid_profile`() = runTest {
        val mgr = buildManager(providerType = ProviderType.XTREAM_CODES)
        xtreamBackend.respond(
            action = "get_live_categories",
            body = """
                [
                  {"category_id":"1","category_name":"News"},
                  {"category_id":"2","category_name":"Sports"}
                ]
            """.trimIndent()
        )
        xtreamBackend.respond(
            action = "get_live_streams",
            body = """
                [
                  {
                    "name": "Channel One",
                    "stream_id": 1001,
                    "category_id": "1",
                    "stream_icon": "https://example.com/ch1.png",
                    "tv_archive": 0,
                    "num": 1
                  }
                ]
            """.trimIndent()
        )

        val result = mgr.retrySection(providerId = 1L, section = SyncRepairSection.LIVE)
        advanceUntilIdle()

        assertThat(result.isSuccess).isTrue()
        assertThat(xtreamBackend.requestedActions.count { it == "get_live_streams" }).isEqualTo(2)
        assertThat(xtreamBackend.requestedActions.first()).isEqualTo("get_live_categories")
    }

    @Test
    fun `sync_xtream_forced_category_mode_skips_full_live_catalog`() = runTest {
        val provider = sampleProvider(ProviderType.XTREAM_CODES).copy(
            xtreamLiveSyncMode = ProviderXtreamLiveSyncMode.CATEGORY_BY_CATEGORY
        )
        val mgr = buildManager(providerType = ProviderType.XTREAM_CODES, providerEntity = provider)
        xtreamBackend.respond(
            action = "get_live_categories",
            body = """
                [
                  {"category_id":"1","category_name":"News"},
                  {"category_id":"2","category_name":"Sports"}
                ]
            """.trimIndent()
        )
        xtreamBackend.respond(
            action = "get_live_streams",
            body = """
                [
                  {
                    "name": "Channel One",
                    "stream_id": 1001,
                    "category_id": "1",
                    "stream_icon": "https://example.com/ch1.png",
                    "tv_archive": 0,
                    "num": 1
                  }
                ]
            """.trimIndent()
        )
        stubXtreamEmptyVodAndSeriesCategories()

        val result = mgr.sync(1L, force = false)
        advanceUntilIdle()

        assertThat(result.isSuccess).isTrue()
        assertThat(xtreamBackend.requestedActions.count { it == "get_live_streams" }).isEqualTo(2)
        assertThat(xtreamBackend.requestedActions).contains("get_vod_categories")
        assertThat(xtreamBackend.requestedActions).contains("get_series_categories")
    }

    @Test
    fun `sync_xtream_forced_stream_all_uses_full_live_catalog_when_safe`() = runTest {
        val provider = sampleProvider(ProviderType.XTREAM_CODES).copy(
            xtreamLiveSyncMode = ProviderXtreamLiveSyncMode.STREAM_ALL
        )
        val mgr = buildManager(providerType = ProviderType.XTREAM_CODES, providerEntity = provider)
        xtreamBackend.respond(
            action = "get_live_categories",
            body = """
                [
                  {"category_id":"1","category_name":"News"},
                  {"category_id":"2","category_name":"Sports"}
                ]
            """.trimIndent()
        )
        xtreamBackend.respond(
            action = "get_live_streams",
            body = """
                [
                  {
                    "name": "Channel One",
                    "stream_id": 1001,
                    "category_id": "1",
                    "stream_icon": "https://example.com/ch1.png",
                    "tv_archive": 0,
                    "num": 1
                  }
                ]
            """.trimIndent()
        )
        stubXtreamEmptyVodAndSeriesCategories()

        val result = mgr.sync(1L, force = false)
        advanceUntilIdle()

        assertThat(result.isSuccess).isTrue()
        assertThat(xtreamBackend.requestedActions.count { it == "get_live_streams" }).isEqualTo(1)
        assertThat(xtreamBackend.requestedActions).contains("get_vod_categories")
        assertThat(xtreamBackend.requestedActions).contains("get_series_categories")
    }

    @Test
    fun `sync_xtream_forced_stream_all_falls_back_and_records_avoid_full_metadata`() = runTest {
        val provider = sampleProvider(ProviderType.XTREAM_CODES).copy(
            xtreamLiveSyncMode = ProviderXtreamLiveSyncMode.STREAM_ALL
        )
        val mgr = buildManager(providerType = ProviderType.XTREAM_CODES, providerEntity = provider)
        xtreamBackend.respond(
            action = "get_live_categories",
            body = """
                [
                  {"category_id":"1","category_name":"News"},
                  {"category_id":"2","category_name":"Sports"}
                ]
            """.trimIndent()
        )
        repeat(4) {
            xtreamBackend.respond(action = "get_live_streams", body = """[{"stream_id":"""")
        }
        xtreamBackend.respond(
            action = "get_live_streams",
            body = """
                [
                  {
                    "name": "Recovered Channel",
                    "stream_id": 2001,
                    "category_id": "1",
                    "stream_icon": "https://example.com/ch1.png",
                    "tv_archive": 0,
                    "num": 1
                  }
                ]
            """.trimIndent()
        )
        stubXtreamEmptyVodAndSeriesCategories()

        val result = mgr.sync(1L, force = false)
        advanceUntilIdle()

        assertThat(result.isSuccess).isTrue()
        assertThat(xtreamBackend.requestedActions.count { it == "get_live_streams" }).isEqualTo(6)
        val metadata = syncMetadataRepo.getMetadata(1L)
        assertThat(metadata?.liveAvoidFullUntil ?: 0L).isGreaterThan(0L)
        assertThat(metadata?.liveCount).isEqualTo(1)
    }

    @Test
    fun `sync_xtream_staged_live_commit_preserves_hidden_categories_and_channels`() = runTest {
        val provider = sampleProvider(ProviderType.XTREAM_CODES).copy(
            xtreamLiveSyncMode = ProviderXtreamLiveSyncMode.CATEGORY_BY_CATEGORY
        )
        val mgr = buildManager(providerType = ProviderType.XTREAM_CODES, providerEntity = provider)
        org.mockito.kotlin.whenever(preferencesRepo.getHiddenCategoryIds(eq(1L), eq(ContentType.LIVE))).thenReturn(flowOf(setOf(2L)))
        runBlocking {
            org.mockito.kotlin.whenever(categoryDao.getByProviderAndTypeSync(1L, ContentType.LIVE.name)).thenReturn(
                listOf(
                    CategoryEntity(
                        categoryId = 2L,
                        name = "Hidden Sports",
                        type = ContentType.LIVE,
                        providerId = 1L
                    )
                )
            )
            org.mockito.kotlin.whenever(channelDao.getByProviderSync(1L)).thenReturn(
                listOf(
                    ChannelEntity(
                        streamId = 2002L,
                        name = "Hidden Sports Channel",
                        streamUrl = "xtream://1/live/2002?ext=ts",
                        categoryId = 2L,
                        categoryName = "Hidden Sports",
                        providerId = 1L,
                        number = 2
                    )
                )
            )
        }
        xtreamBackend.respond(
            action = "get_live_categories",
            body = """
                [
                  {"category_id":"1","category_name":"News"},
                  {"category_id":"2","category_name":"Hidden Sports"}
                ]
            """.trimIndent()
        )
        xtreamBackend.respond(
            action = "get_live_streams",
            body = """
                [
                  {
                    "name": "Visible Channel",
                    "stream_id": 1001,
                    "category_id": "1",
                    "stream_icon": "https://example.com/ch1.png",
                    "tv_archive": 0,
                    "num": 1
                  }
                ]
            """.trimIndent()
        )
        stubXtreamEmptyVodAndSeriesCategories()

        val result = mgr.sync(1L, force = false)
        advanceUntilIdle()

        assertThat(result.isSuccess).isTrue()
        val insertedCategories = argumentCaptor<List<com.streamvault.data.local.entity.CategoryImportStageEntity>>()
        val insertedChannels = argumentCaptor<List<com.streamvault.data.local.entity.ChannelImportStageEntity>>()
        verify(catalogSyncDao, atLeastOnce()).insertCategoryStages(insertedCategories.capture())
        verify(catalogSyncDao, atLeastOnce()).insertChannelStages(insertedChannels.capture())
        val categoryIds = insertedCategories.allValues.flatten()
            .filter { it.type == ContentType.LIVE }
            .map { it.categoryId }
        val streamIds = insertedChannels.allValues.flatten().map { it.streamId }
        assertThat(categoryIds).containsAtLeast(1L, 2L)
        assertThat(streamIds).containsAtLeast(1001L, 2002L)
    }

    @Test
    fun `sync_xtream_recreates hidden category shells when provider catalog rows were deleted`() = runTest {
        val provider = sampleProvider(ProviderType.XTREAM_CODES).copy(
            xtreamLiveSyncMode = ProviderXtreamLiveSyncMode.CATEGORY_BY_CATEGORY
        )
        val mgr = buildManager(providerType = ProviderType.XTREAM_CODES, providerEntity = provider)
        org.mockito.kotlin.whenever(preferencesRepo.getHiddenCategoryIds(eq(1L), eq(ContentType.LIVE)))
            .thenReturn(flowOf(setOf(2L)))
        org.mockito.kotlin.whenever(categoryDao.getByProviderAndTypeSync(1L, ContentType.LIVE.name))
            .thenReturn(emptyList())
        org.mockito.kotlin.whenever(channelDao.getByProviderSync(1L)).thenReturn(emptyList())
        xtreamBackend.respond(
            action = "get_live_categories",
            body = """
                [
                  {"category_id":"1","category_name":"News"},
                  {"category_id":"2","category_name":"Hidden Sports"}
                ]
            """.trimIndent()
        )
        xtreamBackend.respond(
            action = "get_live_streams",
            body = """
                [
                  {
                    "name": "Visible Channel",
                    "stream_id": 1001,
                    "category_id": "1",
                    "stream_icon": "https://example.com/ch1.png",
                    "tv_archive": 0,
                    "num": 1
                  }
                ]
            """.trimIndent()
        )
        stubXtreamEmptyVodAndSeriesCategories()

        val result = mgr.sync(1L, force = false)
        advanceUntilIdle()

        assertThat(result.isSuccess).isTrue()
        val insertedCategories = argumentCaptor<List<com.streamvault.data.local.entity.CategoryImportStageEntity>>()
        verify(catalogSyncDao, atLeastOnce()).insertCategoryStages(insertedCategories.capture())
        assertThat(
            insertedCategories.allValues.flatten()
                .filter { it.type == ContentType.LIVE }
                .map { it.categoryId }
        ).containsAtLeast(1L, 2L)
    }

    @Test
    fun `sync_xtream_empty_valid_live_without_existing_channels_continues_with_vod_and_series`() = runTest {
        val mgr = buildManager(providerType = ProviderType.XTREAM_CODES)
        org.mockito.kotlin.whenever(channelDao.getCount(1L)).thenReturn(flowOf(0))
        xtreamBackend.respond(action = "get_live_categories", body = """[{"category_id":"1","category_name":"News"}]""")
        xtreamBackend.respond(action = "get_live_streams", body = "[]")
        stubXtreamEmptyVodAndSeriesCategories()

        val result = mgr.sync(1L, force = false)
        advanceUntilIdle()

        assertThat(result.isSuccess).isTrue()
        assertThat(mgr.currentSyncState(1L)).isInstanceOf(SyncState.Partial::class.java)
    }

    @Test
    fun `sync_xtream_live_category_bulk_commits_staged_channels`() = runTest {
        val mgr = buildManager(providerType = ProviderType.XTREAM_CODES)
        xtreamBackend.respond(
            action = "get_live_categories",
            body = """
                [
                  {"category_id":"1","category_name":"News"},
                  {"category_id":"2","category_name":"Sports"}
                ]
            """.trimIndent()
        )
        xtreamBackend.respond(
            action = "get_live_streams",
            body = """
                [
                  {
                    "name": "Channel One",
                    "stream_id": 1001,
                    "category_id": "1",
                    "stream_icon": "https://example.com/ch1.png",
                    "tv_archive": 0,
                    "num": 1
                  }
                ]
            """.trimIndent()
        )
        xtreamBackend.respond(
            action = "get_live_streams",
            body = "{" + '"' + "error" + '"' + ":" + '"' + "category unavailable" + '"' + "}",
            code = 500
        )
        stubXtreamEmptyVodAndSeriesCategories()

        val result = mgr.sync(1L, force = false)
        advanceUntilIdle()

        assertThat(result.isSuccess).isTrue()
        assertThat(mgr.currentSyncState(1L)).isInstanceOf(SyncState.Success::class.java)
        assertThat(syncMetadataRepo.getMetadata(1L)?.liveCount).isEqualTo(1)
        val stagedSessionId = argumentCaptor<Long>()
        verify(catalogSyncDao).updateChangedChannelsFromStage(eq(1L), stagedSessionId.capture())
        verify(catalogSyncDao).insertMissingChannelsFromStage(1L, stagedSessionId.firstValue)
        verify(catalogSyncDao).clearChannelStages(1L, stagedSessionId.firstValue)
    }

    @Test
    fun `sync_xtream_vod_category_failure_does_not_fetch_movie_streams`() = runTest {
        val mgr = buildManager(providerType = ProviderType.XTREAM_CODES)
        val now = System.currentTimeMillis()
        stubXtreamLiveCatalog()
        syncMetadataRepo.updateMetadata(
            SyncMetadata(
                providerId = 1L,
                lastLiveSync = now,
                lastLiveSuccess = now,
                lastSeriesSync = now,
                lastSeriesSuccess = now,
                lastEpgSync = now,
                lastEpgSuccess = now
            )
        )
        xtreamBackend.respond(action = "get_vod_categories", body = """{"error":"categories unavailable"}""", code = 500)
        xtreamBackend.respond(action = "get_series_categories", body = "[]")

        val result = mgr.sync(1L, force = false)
        advanceUntilIdle()

        assertThat(result.isSuccess).isTrue()
        assertThat(xtreamBackend.requestedActions).contains("get_vod_categories")
        assertThat(xtreamBackend.requestedActions).doesNotContain("get_vod_streams")
        assertThat(mgr.currentSyncState(1L)).isInstanceOf(SyncState.Partial::class.java)
        val jobs = argumentCaptor<XtreamIndexJobEntity>()
        verify(xtreamIndexJobDao, atLeastOnce()).upsert(jobs.capture())
        assertThat(jobs.allValues.last { it.section == "MOVIE" }.state).isEqualTo("FAILED_RETRYABLE")
    }

    @Test
    fun `sync_xtream_ignores_legacy_fast_sync_and_queues_movie_categories`() = runTest {
        val provider = sampleProvider(ProviderType.XTREAM_CODES).copy(xtreamFastSyncEnabled = true)
        val mgr = buildManager(providerType = ProviderType.XTREAM_CODES, providerEntity = provider)
        val now = System.currentTimeMillis()
        stubXtreamLiveCatalog()
        syncMetadataRepo.updateMetadata(
            SyncMetadata(
                providerId = 1L,
                lastLiveSync = now,
                lastLiveSuccess = now,
                lastSeriesSync = now,
                lastSeriesSuccess = now,
                lastEpgSync = now,
                lastEpgSuccess = now
            )
        )
        xtreamBackend.respond(
            action = "get_vod_categories",
            body = """
                [
                  {"category_id":"42","category_name":"Action"}
                ]
            """.trimIndent()
        )
        xtreamBackend.respond(action = "get_series_categories", body = "[]")
        org.mockito.kotlin.whenever(movieDao.getCount(1L)).thenReturn(flowOf(0))

        val result = mgr.sync(1L, force = false)
        advanceUntilIdle()

        assertThat(result.isSuccess).isTrue()
        assertThat(xtreamBackend.requestedActions).contains("get_vod_categories")
        assertThat(xtreamBackend.requestedActions).doesNotContain("get_vod_streams")
        verify(catalogSyncDao, atLeastOnce()).insertCategoryStages(any())
        val jobs = argumentCaptor<XtreamIndexJobEntity>()
        verify(xtreamIndexJobDao, atLeastOnce()).upsert(jobs.capture())
        assertThat(
            jobs.allValues.any { job ->
                job.section == "MOVIE" &&
                    job.state == "QUEUED" &&
                    job.totalCategories == 1 &&
                    job.indexedRows == 0
            }
        ).isTrue()
    }

    @Test
    fun `retrySection_movies_queues_xtream_index_categories_only`() = runTest {
        val mgr = buildManager(providerType = ProviderType.XTREAM_CODES)
        xtreamBackend.respond(
            action = "get_vod_categories",
            body = """
                [
                  {"category_id":"42","category_name":"Action"}
                ]
            """.trimIndent()
        )
        org.mockito.kotlin.whenever(movieDao.getCount(1L)).thenReturn(flowOf(0))

        val result = mgr.retrySection(
            providerId = 1L,
            section = SyncRepairSection.MOVIES
        )
        advanceUntilIdle()

        assertThat(result.isSuccess).isTrue()
        val metadata = syncMetadataRepo.getMetadata(1L)
        assertThat(metadata?.lastMovieAttempt ?: 0L).isGreaterThan(0L)
        assertThat(metadata?.movieCatalogStale).isTrue()
        assertThat(metadata?.movieSyncMode).isEqualTo(com.streamvault.domain.model.VodSyncMode.UNKNOWN)
        assertThat(xtreamBackend.requestedActions).contains("get_vod_categories")
        assertThat(xtreamBackend.requestedActions).doesNotContain("get_vod_streams")
        val jobs = argumentCaptor<XtreamIndexJobEntity>()
        verify(xtreamIndexJobDao, atLeastOnce()).upsert(jobs.capture())
        assertThat(
            jobs.allValues.any { job ->
                job.section == "MOVIE" &&
                    job.state == "QUEUED" &&
                    job.totalCategories == 1 &&
                    job.indexedRows == 0
            }
        ).isTrue()
    }

    @Test
    fun `retrySection_series_queues_xtream_index_categories_only`() = runTest {
        val mgr = buildManager(providerType = ProviderType.XTREAM_CODES)
        xtreamBackend.respond(
            action = "get_series_categories",
            body = """
                [
                  {"category_id":"77","category_name":"Drama"}
                ]
            """.trimIndent()
        )

        val result = mgr.retrySection(
            providerId = 1L,
            section = SyncRepairSection.SERIES
        )
        advanceUntilIdle()

        assertThat(result.isSuccess).isTrue()
        val metadata = syncMetadataRepo.getMetadata(1L)
        assertThat(metadata?.lastSeriesSync ?: 0L).isGreaterThan(0L)
        assertThat(xtreamBackend.requestedActions).contains("get_series_categories")
        assertThat(xtreamBackend.requestedActions).doesNotContain("get_series")
        val jobs = argumentCaptor<XtreamIndexJobEntity>()
        verify(xtreamIndexJobDao, atLeastOnce()).upsert(jobs.capture())
        assertThat(
            jobs.allValues.any { job ->
                job.section == "SERIES" &&
                    job.state == "QUEUED" &&
                    job.totalCategories == 1 &&
                    job.indexedRows == 0
            }
        ).isTrue()
    }

    @Test
    fun `rebuildXtreamIndex marks_existing_rows_stale_and_queues_movie_and_series_indexes`() = runTest {
        val mgr = buildManager(providerType = ProviderType.XTREAM_CODES)
        org.mockito.kotlin.whenever(xtreamContentIndexDao.markVodAndSeriesRowsStaleForRebuild(1L)).thenReturn(12)
        xtreamBackend.respond(
            action = "get_vod_categories",
            body = """
                [
                  {"category_id":"42","category_name":"Action"}
                ]
            """.trimIndent()
        )
        xtreamBackend.respond(
            action = "get_series_categories",
            body = """
                [
                  {"category_id":"77","category_name":"Drama"}
                ]
            """.trimIndent()
        )

        val result = mgr.rebuildXtreamIndex(1L)
        advanceUntilIdle()

        assertThat(result.isSuccess).isTrue()
        verify(xtreamContentIndexDao).markVodAndSeriesRowsStaleForRebuild(1L)
        assertThat(xtreamBackend.requestedActions).contains("get_vod_categories")
        assertThat(xtreamBackend.requestedActions).contains("get_series_categories")
        assertThat(xtreamBackend.requestedActions).doesNotContain("get_vod_streams")
        assertThat(xtreamBackend.requestedActions).doesNotContain("get_series")
        val jobs = argumentCaptor<XtreamIndexJobEntity>()
        verify(xtreamIndexJobDao, atLeastOnce()).upsert(jobs.capture())
        assertThat(
            jobs.allValues.any { job ->
                job.section == "MOVIE" &&
                    job.state == "QUEUED" &&
                    job.totalCategories == 1
            }
        ).isTrue()
        assertThat(
            jobs.allValues.any { job ->
                job.section == "SERIES" &&
                    job.state == "QUEUED" &&
                    job.totalCategories == 1
            }
        ).isTrue()
    }

    @Test
    fun `processQueuedXtreamIndexJobs streams full movie index before category fallback`() = runTest {
        val mgr = buildManager(providerType = ProviderType.XTREAM_CODES)
        org.mockito.kotlin.whenever(
            categoryDao.getByProviderAndTypeSync(1L, ContentType.MOVIE.name)
        ).thenReturn(
            listOf(
                CategoryEntity(
                    categoryId = 42L,
                    name = "Action",
                    type = ContentType.MOVIE,
                    providerId = 1L
                )
            )
        )
        org.mockito.kotlin.whenever(xtreamIndexJobDao.get(1L, ContentType.MOVIE.name)).thenReturn(
            XtreamIndexJobEntity(
                providerId = 1L,
                section = ContentType.MOVIE.name,
                state = "QUEUED",
                totalCategories = 1
            )
        )
        org.mockito.kotlin.whenever(movieDao.getByStreamIds(eq(1L), any())).thenReturn(emptyList())
        xtreamBackend.respond(
            action = "get_vod_streams",
            body = """
                [
                  {"stream_id":100,"name":"Streamed One","category_id":"42","container_extension":"mp4"},
                  {"stream_id":101,"name":"Streamed Two","category_id":"42","container_extension":"mkv"}
                ]
            """.trimIndent()
        )

        val result = mgr.processQueuedXtreamIndexJobs(
            providerId = 1L,
            section = ContentType.MOVIE,
            maxCategoriesPerSection = 2
        )
        advanceUntilIdle()

        assertThat(result.isSuccess).isTrue()
        assertThat(xtreamBackend.requestedActions).contains("get_vod_streams")
        verify(movieDao, atLeastOnce()).insertAll(any())
        verify(xtreamContentIndexDao, atLeastOnce()).upsertAll(any())
        val jobs = argumentCaptor<XtreamIndexJobEntity>()
        verify(xtreamIndexJobDao, atLeastOnce()).upsert(jobs.capture())
        val finalMovieJob = jobs.allValues.last { it.section == ContentType.MOVIE.name }
        assertThat(finalMovieJob.state).isEqualTo("SUCCESS")
        assertThat(finalMovieJob.indexedRows).isEqualTo(2)
        assertThat(finalMovieJob.nextCategoryIndex).isEqualTo(1)
    }

    @Test
    fun `processQueuedStalkerIndexJobs advances multiple pages for one movie category in one run`() = runTest {
        val providerEntity = sampleProvider(ProviderType.STALKER_PORTAL).copy(
            serverUrl = "http://example.com",
            username = "",
            password = "",
            stalkerMacAddress = "00:11:22:33:44:55",
            epgSyncMode = ProviderEpgSyncMode.SKIP,
            maxConnections = 4
        )
        val categoryId = stalkerSyntheticCategoryId(providerEntity.id, ContentType.MOVIE, "5")
        val manager = buildManager(providerType = ProviderType.STALKER_PORTAL, providerEntity = providerEntity)

        org.mockito.kotlin.whenever(stalkerApiService.authenticate(any())).thenReturn(
            Result.success(
                StalkerSession(
                    loadUrl = "http://example.com/stalker_portal/server/load.php",
                    portalReferer = "http://example.com/stalker_portal/c/",
                    token = "token"
                ) to StalkerProviderProfile(accountName = "Stalker")
            )
        )
        org.mockito.kotlin.whenever(stalkerApiService.getVodCategories(any(), any())).thenReturn(
            Result.success(
                listOf(
                    StalkerCategoryRecord(id = "5", name = "Action")
                )
            )
        )
        org.mockito.kotlin.whenever(categoryDao.getByProviderAndTypeSync(1L, ContentType.MOVIE.name)).thenReturn(
            listOf(
                CategoryEntity(
                    categoryId = categoryId,
                    name = "Action",
                    type = ContentType.MOVIE,
                    providerId = 1L
                )
            )
        )
        org.mockito.kotlin.whenever(xtreamIndexJobDao.get(1L, ContentType.MOVIE.name)).thenReturn(
            XtreamIndexJobEntity(
                providerId = 1L,
                section = ContentType.MOVIE.name,
                state = "QUEUED",
                totalCategories = 1
            )
        )
        val hydrationByCategory = mutableMapOf<Long, MovieCategoryHydrationEntity>()
        org.mockito.kotlin.whenever(movieCategoryHydrationDao.get(eq(1L), any())).thenAnswer { invocation ->
            hydrationByCategory[invocation.getArgument<Long>(1)]
        }
        org.mockito.kotlin.whenever(movieCategoryHydrationDao.upsert(any())).thenAnswer { invocation ->
            val entity = invocation.getArgument<MovieCategoryHydrationEntity>(0)
            hydrationByCategory[entity.categoryId] = entity
            Unit
        }
        org.mockito.kotlin.whenever(movieDao.getByStreamIds(eq(1L), any())).thenReturn(emptyList())
        org.mockito.kotlin.whenever(stalkerApiService.getVodStreamsPage(any(), any(), eq("5"), eq(1))).thenReturn(
            Result.success(
                StalkerPagedItems(
                    items = listOf(
                        StalkerItemRecord(
                            id = "100",
                            name = "Movie 100",
                            categoryId = "5",
                            cmd = "ffmpeg http://example.com/100.ts"
                        )
                    ),
                    page = 1,
                    totalPages = 2,
                    pageSize = 14
                )
            )
        )
        org.mockito.kotlin.whenever(stalkerApiService.getVodStreamsPage(any(), any(), eq("5"), eq(2))).thenReturn(
            Result.success(
                StalkerPagedItems(
                    items = listOf(
                        StalkerItemRecord(
                            id = "101",
                            name = "Movie 101",
                            categoryId = "5",
                            cmd = "ffmpeg http://example.com/101.ts"
                        )
                    ),
                    page = 2,
                    totalPages = 2,
                    pageSize = 14
                )
            )
        )

        val result = manager.processQueuedStalkerIndexJobs(
            providerId = 1L,
            section = ContentType.MOVIE,
            maxCategoriesPerSection = 1
        )

        if (result is Result.Error) fail(result.exception?.stackTraceToString() ?: result.message)
        assertThat(result.isSuccess).isTrue()
        verify(stalkerApiService).getVodStreamsPage(any(), any(), eq("5"), eq(1))
        verify(stalkerApiService).getVodStreamsPage(any(), any(), eq("5"), eq(2))
        verify(movieDao, atLeast(2)).insertAll(any())
        verify(xtreamContentIndexDao, atLeast(2)).upsertAll(any())
        val jobs = argumentCaptor<XtreamIndexJobEntity>()
        verify(xtreamIndexJobDao, atLeastOnce()).upsert(jobs.capture())
        val finalMovieJob = jobs.allValues.last { it.section == ContentType.MOVIE.name }
        assertThat(finalMovieJob.state).isEqualTo("SUCCESS")
    }

    @Test
    fun `processQueuedStalkerIndexJobs can advance beyond legacy six page cap in one run`() = runTest {
        val providerEntity = sampleProvider(ProviderType.STALKER_PORTAL).copy(
            serverUrl = "http://example.com",
            username = "",
            password = "",
            stalkerMacAddress = "00:11:22:33:44:55"
        )
        val categoryId = stalkerSyntheticCategoryId(providerEntity.id, ContentType.MOVIE, "5")
        val manager = buildManager(providerType = ProviderType.STALKER_PORTAL, providerEntity = providerEntity)

        org.mockito.kotlin.whenever(stalkerApiService.authenticate(any())).thenReturn(
            Result.success(
                StalkerSession(
                    loadUrl = "http://example.com/stalker_portal/server/load.php",
                    portalReferer = "http://example.com/stalker_portal/c/",
                    token = "token"
                ) to StalkerProviderProfile(accountName = "Stalker")
            )
        )
        org.mockito.kotlin.whenever(stalkerApiService.getVodCategories(any(), any())).thenReturn(
            Result.success(
                listOf(
                    StalkerCategoryRecord(id = "5", name = "Action")
                )
            )
        )
        org.mockito.kotlin.whenever(categoryDao.getByProviderAndTypeSync(1L, ContentType.MOVIE.name)).thenReturn(
            listOf(
                CategoryEntity(
                    categoryId = categoryId,
                    name = "Action",
                    type = ContentType.MOVIE,
                    providerId = 1L
                )
            )
        )
        org.mockito.kotlin.whenever(xtreamIndexJobDao.get(1L, ContentType.MOVIE.name)).thenReturn(
            XtreamIndexJobEntity(
                providerId = 1L,
                section = ContentType.MOVIE.name,
                state = "QUEUED",
                totalCategories = 1
            )
        )
        val hydrationByCategory = mutableMapOf<Long, MovieCategoryHydrationEntity>()
        org.mockito.kotlin.whenever(movieCategoryHydrationDao.get(eq(1L), any())).thenAnswer { invocation ->
            hydrationByCategory[invocation.getArgument<Long>(1)]
        }
        org.mockito.kotlin.whenever(movieCategoryHydrationDao.upsert(any())).thenAnswer { invocation ->
            val entity = invocation.getArgument<MovieCategoryHydrationEntity>(0)
            hydrationByCategory[entity.categoryId] = entity
            Unit
        }
        org.mockito.kotlin.whenever(movieDao.getByStreamIds(eq(1L), any())).thenReturn(emptyList())
        (1..7).forEach { page ->
            org.mockito.kotlin.whenever(stalkerApiService.getVodStreamsPage(any(), any(), eq("5"), eq(page))).thenReturn(
                Result.success(
                    StalkerPagedItems(
                        items = listOf(
                            StalkerItemRecord(
                                id = "10$page",
                                name = "Movie $page",
                                categoryId = "5",
                                cmd = "ffmpeg http://example.com/$page.ts"
                            )
                        ),
                        page = page,
                        totalPages = 7,
                        pageSize = 14
                    )
                )
            )
        }

        val result = manager.processQueuedStalkerIndexJobs(
            providerId = 1L,
            section = ContentType.MOVIE,
            maxCategoriesPerSection = 1
        )

        if (result is Result.Error) fail(result.exception?.stackTraceToString() ?: result.message)
        assertThat(result.isSuccess).isTrue()
        verify(stalkerApiService).getVodStreamsPage(any(), any(), eq("5"), eq(7))
        val jobs = argumentCaptor<XtreamIndexJobEntity>()
        verify(xtreamIndexJobDao, atLeastOnce()).upsert(jobs.capture())
        val finalMovieJob = jobs.allValues.last { it.section == ContentType.MOVIE.name }
        assertThat(finalMovieJob.state).isEqualTo("SUCCESS")
    }

    @Test
    fun `processQueuedStalkerIndexJobs runs movies before series for same provider`() = runTest {
        val providerEntity = sampleProvider(ProviderType.STALKER_PORTAL).copy(
            serverUrl = "http://example.com",
            username = "",
            password = "",
            stalkerMacAddress = "00:11:22:33:44:55",
            epgSyncMode = ProviderEpgSyncMode.SKIP,
            maxConnections = 2
        )
        val movieCategoryId = stalkerSyntheticCategoryId(providerEntity.id, ContentType.MOVIE, "5")
        val seriesCategoryId = stalkerSyntheticCategoryId(providerEntity.id, ContentType.SERIES, "9")
        val manager = buildManager(providerType = ProviderType.STALKER_PORTAL, providerEntity = providerEntity)

        org.mockito.kotlin.whenever(stalkerApiService.authenticate(any())).thenReturn(
            Result.success(
                StalkerSession(
                    loadUrl = "http://example.com/stalker_portal/server/load.php",
                    portalReferer = "http://example.com/stalker_portal/c/",
                    token = "token"
                ) to StalkerProviderProfile(accountName = "Stalker")
            )
        )
        org.mockito.kotlin.whenever(stalkerApiService.getVodCategories(any(), any())).thenReturn(
            Result.success(listOf(StalkerCategoryRecord(id = "5", name = "Action")))
        )
        org.mockito.kotlin.whenever(stalkerApiService.getSeriesCategories(any(), any())).thenReturn(
            Result.success(listOf(StalkerCategoryRecord(id = "9", name = "Drama")))
        )
        org.mockito.kotlin.whenever(categoryDao.getByProviderAndTypeSync(1L, ContentType.MOVIE.name)).thenReturn(
            listOf(
                CategoryEntity(
                    categoryId = movieCategoryId,
                    name = "Action",
                    type = ContentType.MOVIE,
                    providerId = 1L
                )
            )
        )
        org.mockito.kotlin.whenever(categoryDao.getByProviderAndTypeSync(1L, ContentType.SERIES.name)).thenReturn(
            listOf(
                CategoryEntity(
                    categoryId = seriesCategoryId,
                    name = "Drama",
                    type = ContentType.SERIES,
                    providerId = 1L
                )
            )
        )
        val indexJobs = mutableMapOf(
            ContentType.MOVIE.name to XtreamIndexJobEntity(
                providerId = 1L,
                section = ContentType.MOVIE.name,
                state = "QUEUED",
                totalCategories = 1
            ),
            ContentType.SERIES.name to XtreamIndexJobEntity(
                providerId = 1L,
                section = ContentType.SERIES.name,
                state = "QUEUED",
                totalCategories = 1
            )
        )
        org.mockito.kotlin.whenever(xtreamIndexJobDao.get(eq(1L), any())).thenAnswer { invocation ->
            indexJobs[invocation.getArgument<String>(1)]
        }
        org.mockito.kotlin.whenever(xtreamIndexJobDao.upsert(any())).thenAnswer { invocation ->
            val entity = invocation.getArgument<XtreamIndexJobEntity>(0)
            indexJobs[entity.section] = entity
            Unit
        }
        val movieHydrationByCategory = mutableMapOf<Long, MovieCategoryHydrationEntity>()
        org.mockito.kotlin.whenever(movieCategoryHydrationDao.get(eq(1L), any())).thenAnswer { invocation ->
            movieHydrationByCategory[invocation.getArgument<Long>(1)]
        }
        org.mockito.kotlin.whenever(movieCategoryHydrationDao.upsert(any())).thenAnswer { invocation ->
            val entity = invocation.getArgument<MovieCategoryHydrationEntity>(0)
            movieHydrationByCategory[entity.categoryId] = entity
            Unit
        }
        val seriesHydrationByCategory = mutableMapOf<Long, SeriesCategoryHydrationEntity>()
        org.mockito.kotlin.whenever(seriesCategoryHydrationDao.get(eq(1L), any())).thenAnswer { invocation ->
            seriesHydrationByCategory[invocation.getArgument<Long>(1)]
        }
        org.mockito.kotlin.whenever(seriesCategoryHydrationDao.upsert(any())).thenAnswer { invocation ->
            val entity = invocation.getArgument<SeriesCategoryHydrationEntity>(0)
            seriesHydrationByCategory[entity.categoryId] = entity
            Unit
        }
        org.mockito.kotlin.whenever(movieDao.getByStreamIds(eq(1L), any())).thenReturn(emptyList())
        org.mockito.kotlin.whenever(seriesDao.getBySeriesIds(eq(1L), any())).thenReturn(emptyList())

        val movieFetchStarted = CompletableDeferred<Unit>()
        val releaseMovieFetch = CompletableDeferred<Unit>()
        val seriesFetchStarted = CompletableDeferred<Unit>()
        org.mockito.kotlin.whenever(stalkerApiService.getVodStreamsPage(any(), any(), eq("5"), eq(1))).doSuspendableAnswer {
            movieFetchStarted.complete(Unit)
            releaseMovieFetch.await()
            Result.success(
                StalkerPagedItems(
                    items = listOf(
                        StalkerItemRecord(
                            id = "100",
                            name = "Movie 100",
                            categoryId = "5",
                            cmd = "ffmpeg http://example.com/100.ts"
                        )
                    ),
                    page = 1,
                    totalPages = 1,
                    pageSize = 14
                )
            )
        }
        org.mockito.kotlin.whenever(stalkerApiService.getSeriesPage(any(), any(), eq("9"), eq(1))).thenAnswer {
            seriesFetchStarted.complete(Unit)
            Result.success(
                StalkerPagedItems(
                    items = listOf(
                        StalkerItemRecord(
                            id = "200",
                            name = "Series 200",
                            categoryId = "9",
                            cmd = "ffmpeg http://example.com/200.ts",
                            isSeries = true
                        )
                    ),
                    page = 1,
                    totalPages = 1,
                    pageSize = 14
                )
            )
        }

        val movieJob = async {
            manager.processQueuedStalkerIndexJobs(
                providerId = 1L,
                section = ContentType.MOVIE,
                maxCategoriesPerSection = 1
            )
        }
        movieFetchStarted.await()

        val seriesJob = async {
            manager.processQueuedStalkerIndexJobs(
                providerId = 1L,
                section = ContentType.SERIES,
                maxCategoriesPerSection = 1
            )
        }

        advanceUntilIdle()
        assertThat(seriesFetchStarted.isCompleted).isFalse()
        releaseMovieFetch.complete(Unit)

        val movieResult = movieJob.await()
        withTimeout(1_000) {
            seriesFetchStarted.await()
        }
        val seriesResult = seriesJob.await()

        if (seriesResult is Result.Error) fail(seriesResult.exception?.stackTraceToString() ?: seriesResult.message)
        if (movieResult is Result.Error) fail(movieResult.exception?.stackTraceToString() ?: movieResult.message)
        assertThat(seriesResult.isSuccess).isTrue()
        assertThat(movieResult.isSuccess).isTrue()
        verify(stalkerApiService).getVodStreamsPage(any(), any(), eq("5"), eq(1))
        verify(stalkerApiService).getSeriesPage(any(), any(), eq("9"), eq(1))
    }

    @Test
    fun `processQueuedStalkerIndexJobs retries failed movie category before moving to series`() = runTest {
        val providerEntity = sampleProvider(ProviderType.STALKER_PORTAL).copy(
            serverUrl = "http://example.com",
            username = "",
            password = "",
            stalkerMacAddress = "00:11:22:33:44:55",
            epgSyncMode = ProviderEpgSyncMode.SKIP,
            maxConnections = 1
        )
        val movieCategoryId = stalkerSyntheticCategoryId(providerEntity.id, ContentType.MOVIE, "5")
        val seriesCategoryId = stalkerSyntheticCategoryId(providerEntity.id, ContentType.SERIES, "9")
        val manager = buildManager(providerType = ProviderType.STALKER_PORTAL, providerEntity = providerEntity)

        org.mockito.kotlin.whenever(stalkerApiService.authenticate(any())).thenReturn(
            Result.success(
                StalkerSession(
                    loadUrl = "http://example.com/stalker_portal/server/load.php",
                    portalReferer = "http://example.com/stalker_portal/c/",
                    token = "token"
                ) to StalkerProviderProfile(accountName = "Stalker")
            )
        )
        org.mockito.kotlin.whenever(stalkerApiService.getVodCategories(any(), any())).thenReturn(
            Result.success(listOf(StalkerCategoryRecord(id = "5", name = "Action")))
        )
        org.mockito.kotlin.whenever(stalkerApiService.getSeriesCategories(any(), any())).thenReturn(
            Result.success(listOf(StalkerCategoryRecord(id = "9", name = "Drama")))
        )
        org.mockito.kotlin.whenever(categoryDao.getByProviderAndTypeSync(1L, ContentType.MOVIE.name)).thenReturn(
            listOf(
                CategoryEntity(
                    categoryId = movieCategoryId,
                    name = "Action",
                    type = ContentType.MOVIE,
                    providerId = 1L
                )
            )
        )
        org.mockito.kotlin.whenever(categoryDao.getByProviderAndTypeSync(1L, ContentType.SERIES.name)).thenReturn(
            listOf(
                CategoryEntity(
                    categoryId = seriesCategoryId,
                    name = "Drama",
                    type = ContentType.SERIES,
                    providerId = 1L
                )
            )
        )
        val indexJobs = mutableMapOf(
            ContentType.MOVIE.name to XtreamIndexJobEntity(
                providerId = 1L,
                section = ContentType.MOVIE.name,
                state = "QUEUED",
                totalCategories = 1
            ),
            ContentType.SERIES.name to XtreamIndexJobEntity(
                providerId = 1L,
                section = ContentType.SERIES.name,
                state = "QUEUED",
                totalCategories = 1
            )
        )
        org.mockito.kotlin.whenever(xtreamIndexJobDao.get(eq(1L), any())).thenAnswer { invocation ->
            indexJobs[invocation.getArgument<String>(1)]
        }
        org.mockito.kotlin.whenever(xtreamIndexJobDao.upsert(any())).thenAnswer { invocation ->
            val entity = invocation.getArgument<XtreamIndexJobEntity>(0)
            indexJobs[entity.section] = entity
            Unit
        }
        val movieHydrationByCategory = mutableMapOf<Long, MovieCategoryHydrationEntity>()
        org.mockito.kotlin.whenever(movieCategoryHydrationDao.get(eq(1L), any())).thenAnswer { invocation ->
            movieHydrationByCategory[invocation.getArgument<Long>(1)]
        }
        org.mockito.kotlin.whenever(movieCategoryHydrationDao.upsert(any())).thenAnswer { invocation ->
            val entity = invocation.getArgument<MovieCategoryHydrationEntity>(0)
            movieHydrationByCategory[entity.categoryId] = entity
            Unit
        }
        val seriesHydrationByCategory = mutableMapOf<Long, SeriesCategoryHydrationEntity>()
        org.mockito.kotlin.whenever(seriesCategoryHydrationDao.get(eq(1L), any())).thenAnswer { invocation ->
            seriesHydrationByCategory[invocation.getArgument<Long>(1)]
        }
        org.mockito.kotlin.whenever(seriesCategoryHydrationDao.upsert(any())).thenAnswer { invocation ->
            val entity = invocation.getArgument<SeriesCategoryHydrationEntity>(0)
            seriesHydrationByCategory[entity.categoryId] = entity
            Unit
        }
        org.mockito.kotlin.whenever(movieDao.getByStreamIds(eq(1L), any())).thenReturn(emptyList())
        org.mockito.kotlin.whenever(seriesDao.getBySeriesIds(eq(1L), any())).thenReturn(emptyList())

        var movieAttempts = 0
        org.mockito.kotlin.whenever(stalkerApiService.getVodStreamsPage(any(), any(), eq("5"), eq(1))).thenAnswer {
            movieAttempts += 1
            if (movieAttempts == 1) {
                Result.error("temporary movie failure", java.io.IOException("temporary movie failure"))
            } else {
                Result.success(
                    StalkerPagedItems(
                        items = listOf(
                            StalkerItemRecord(
                                id = "100",
                                name = "Movie 100",
                                categoryId = "5",
                                cmd = "ffmpeg http://example.com/100.ts"
                            )
                        ),
                        page = 1,
                        totalPages = 1,
                        pageSize = 14
                    )
                )
            }
        }
        org.mockito.kotlin.whenever(stalkerApiService.getSeriesPage(any(), any(), eq("9"), eq(1))).thenReturn(
            Result.success(
                StalkerPagedItems(
                    items = listOf(
                        StalkerItemRecord(
                            id = "200",
                            name = "Series 200",
                            categoryId = "9",
                            cmd = "ffmpeg http://example.com/200.ts",
                            isSeries = true
                        )
                    ),
                    page = 1,
                    totalPages = 1,
                    pageSize = 14
                )
            )
        )

        val movieResult = manager.processQueuedStalkerIndexJobs(providerId = 1L, maxCategoriesPerSection = 1)
        val seriesResult = manager.processQueuedStalkerIndexJobs(providerId = 1L, maxCategoriesPerSection = 1)

        if (movieResult is Result.Error) fail(movieResult.exception?.stackTraceToString() ?: movieResult.message)
        if (seriesResult is Result.Error) fail(seriesResult.exception?.stackTraceToString() ?: seriesResult.message)
        assertThat(movieAttempts).isEqualTo(2)
        verify(stalkerApiService).getSeriesPage(any(), any(), eq("9"), eq(1))
        assertThat(indexJobs[ContentType.MOVIE.name]?.state).isEqualTo("SUCCESS")
        assertThat(indexJobs[ContentType.SERIES.name]?.state).isEqualTo("SUCCESS")
    }

    @Test
    fun `processQueuedStalkerIndexJobs metadata concurrency is independent of playback connection entitlement`() = runTest {
        val providerEntity = sampleProvider(ProviderType.STALKER_PORTAL).copy(
            serverUrl = "http://example.com",
            username = "",
            password = "",
            stalkerMacAddress = "00:11:22:33:44:55",
            epgSyncMode = ProviderEpgSyncMode.SKIP,
            maxConnections = 1
        )
        val actionCategoryId = stalkerSyntheticCategoryId(providerEntity.id, ContentType.MOVIE, "5")
        val dramaCategoryId = stalkerSyntheticCategoryId(providerEntity.id, ContentType.MOVIE, "6")
        val manager = buildManager(providerType = ProviderType.STALKER_PORTAL, providerEntity = providerEntity)

        org.mockito.kotlin.whenever(stalkerApiService.authenticate(any())).thenReturn(
            Result.success(
                StalkerSession(
                    loadUrl = "http://example.com/stalker_portal/server/load.php",
                    portalReferer = "http://example.com/stalker_portal/c/",
                    token = "token"
                ) to StalkerProviderProfile(accountName = "Stalker")
            )
        )
        org.mockito.kotlin.whenever(stalkerApiService.getVodCategories(any(), any())).thenReturn(
            Result.success(
                listOf(
                    StalkerCategoryRecord(id = "5", name = "Action"),
                    StalkerCategoryRecord(id = "6", name = "Drama")
                )
            )
        )
        org.mockito.kotlin.whenever(categoryDao.getByProviderAndTypeSync(1L, ContentType.MOVIE.name)).thenReturn(
            listOf(
                CategoryEntity(
                    categoryId = actionCategoryId,
                    name = "Action",
                    type = ContentType.MOVIE,
                    providerId = 1L
                ),
                CategoryEntity(
                    categoryId = dramaCategoryId,
                    name = "Drama",
                    type = ContentType.MOVIE,
                    providerId = 1L
                )
            )
        )
        org.mockito.kotlin.whenever(xtreamIndexJobDao.get(1L, ContentType.MOVIE.name)).thenReturn(
            XtreamIndexJobEntity(
                providerId = 1L,
                section = ContentType.MOVIE.name,
                state = "QUEUED",
                totalCategories = 2
            )
        )
        val hydrationByCategory = mutableMapOf<Long, MovieCategoryHydrationEntity>()
        org.mockito.kotlin.whenever(movieCategoryHydrationDao.get(eq(1L), any())).thenAnswer { invocation ->
            hydrationByCategory[invocation.getArgument<Long>(1)]
        }
        org.mockito.kotlin.whenever(movieCategoryHydrationDao.upsert(any())).thenAnswer { invocation ->
            val entity = invocation.getArgument<MovieCategoryHydrationEntity>(0)
            hydrationByCategory[entity.categoryId] = entity
            Unit
        }
        org.mockito.kotlin.whenever(movieDao.getByStreamIds(eq(1L), any())).thenReturn(emptyList())

        org.mockito.kotlin.whenever(stalkerApiService.getVodStreamsPage(any(), any(), eq("5"), eq(1))).thenReturn(
            Result.success(
                StalkerPagedItems(
                    items = listOf(
                        StalkerItemRecord(
                            id = "100",
                            name = "Movie 100",
                            categoryId = "5",
                            cmd = "ffmpeg http://example.com/100.ts"
                        )
                    ),
                    page = 1,
                    totalPages = 1,
                    pageSize = 14
                )
            )
        )
        org.mockito.kotlin.whenever(stalkerApiService.getVodStreamsPage(any(), any(), eq("6"), eq(1))).thenReturn(
            Result.success(
                StalkerPagedItems(
                    items = listOf(
                        StalkerItemRecord(
                            id = "101",
                            name = "Movie 101",
                            categoryId = "6",
                            cmd = "ffmpeg http://example.com/101.ts"
                        )
                    ),
                    page = 1,
                    totalPages = 1,
                    pageSize = 14
                )
            )
        )

        val result = manager.processQueuedStalkerIndexJobs(
            providerId = 1L,
            section = ContentType.MOVIE,
            maxCategoriesPerSection = 2
        )

        if (result is Result.Error) fail(result.exception?.stackTraceToString() ?: result.message)
        assertThat(result.isSuccess).isTrue()
        verify(stalkerApiService).getVodStreamsPage(any(), any(), eq("5"), eq(1))
        verify(stalkerApiService).getVodStreamsPage(any(), any(), eq("6"), eq(1))
    }

    @Test
    fun `processQueuedStalkerIndexJobs resumes fresh running Stalker movie job`() = runTest {
        val providerEntity = sampleProvider(ProviderType.STALKER_PORTAL).copy(
            serverUrl = "http://example.com",
            username = "",
            password = "",
            stalkerMacAddress = "00:11:22:33:44:55",
            epgSyncMode = ProviderEpgSyncMode.SKIP,
            maxConnections = 1
        )
        val actionCategoryId = stalkerSyntheticCategoryId(providerEntity.id, ContentType.MOVIE, "5")
        val manager = buildManager(providerType = ProviderType.STALKER_PORTAL, providerEntity = providerEntity)

        org.mockito.kotlin.whenever(stalkerApiService.authenticate(any())).thenReturn(
            Result.success(
                StalkerSession(
                    loadUrl = "http://example.com/stalker_portal/server/load.php",
                    portalReferer = "http://example.com/stalker_portal/c/",
                    token = "token"
                ) to StalkerProviderProfile(accountName = "Stalker")
            )
        )
        org.mockito.kotlin.whenever(stalkerApiService.getVodCategories(any(), any())).thenReturn(
            Result.success(listOf(StalkerCategoryRecord(id = "5", name = "Action")))
        )
        org.mockito.kotlin.whenever(categoryDao.getByProviderAndTypeSync(1L, ContentType.MOVIE.name)).thenReturn(
            listOf(
                CategoryEntity(
                    categoryId = actionCategoryId,
                    name = "Action",
                    type = ContentType.MOVIE,
                    providerId = 1L
                )
            )
        )
        org.mockito.kotlin.whenever(xtreamIndexJobDao.get(1L, ContentType.MOVIE.name)).thenReturn(
            XtreamIndexJobEntity(
                providerId = 1L,
                section = ContentType.MOVIE.name,
                state = "RUNNING",
                totalCategories = 1,
                updatedAt = System.currentTimeMillis()
            )
        )
        val hydrationByCategory = mutableMapOf(
            actionCategoryId to MovieCategoryHydrationEntity(
                providerId = 1L,
                categoryId = actionCategoryId,
                lastHydratedAt = System.currentTimeMillis(),
                itemCount = 56,
                lastStatus = "RUNNING",
                lastLoadedPage = 4,
                lastAttemptedPage = 5,
                lastSuccessfulPage = 4,
                totalPages = 13,
                isComplete = false,
                pageSize = 14,
                retryAfterMs = 0L,
                failureCount = 0,
                retryBudgetRemaining = 3
            )
        )
        org.mockito.kotlin.whenever(movieCategoryHydrationDao.get(eq(1L), any())).thenAnswer { invocation ->
            hydrationByCategory[invocation.getArgument<Long>(1)]
        }
        org.mockito.kotlin.whenever(movieCategoryHydrationDao.upsert(any())).thenAnswer { invocation ->
            val entity = invocation.getArgument<MovieCategoryHydrationEntity>(0)
            hydrationByCategory[entity.categoryId] = entity
            Unit
        }
        org.mockito.kotlin.whenever(movieDao.getByStreamIds(eq(1L), any())).thenReturn(emptyList())
        val requestedPages = mutableListOf<Int>()
        org.mockito.kotlin.whenever(stalkerApiService.getVodStreamsPage(any(), any(), anyOrNull(), any())).thenAnswer { invocation ->
            val requestedPage = invocation.getArgument<Int>(3)
            requestedPages += requestedPage
            Result.success(
                StalkerPagedItems(
                    items = listOf(
                        StalkerItemRecord(
                            id = "105",
                            name = "Movie 105",
                            categoryId = "5",
                            cmd = "ffmpeg http://example.com/105.ts"
                        )
                    ),
                    page = requestedPage,
                    totalPages = 13,
                    pageSize = 14
                )
            )
        }

        val result = manager.processQueuedStalkerIndexJobs(
            providerId = 1L,
            section = ContentType.MOVIE,
            maxCategoriesPerSection = 1
        )

        if (result is Result.Error) fail(result.exception?.stackTraceToString() ?: result.message)
        assertThat(result.isSuccess).isTrue()
        assertThat(requestedPages).contains(5)
    }

    @Test
    fun `processQueuedStalkerIndexJobs serializes movie and series sections for one connection provider`() = runTest {
        val providerEntity = sampleProvider(ProviderType.STALKER_PORTAL).copy(
            serverUrl = "http://example.com",
            username = "",
            password = "",
            stalkerMacAddress = "00:11:22:33:44:55",
            epgSyncMode = ProviderEpgSyncMode.SKIP,
            maxConnections = 1
        )
        val movieCategoryId = stalkerSyntheticCategoryId(providerEntity.id, ContentType.MOVIE, "5")
        val seriesCategoryId = stalkerSyntheticCategoryId(providerEntity.id, ContentType.SERIES, "9")
        val manager = buildManager(providerType = ProviderType.STALKER_PORTAL, providerEntity = providerEntity)

        org.mockito.kotlin.whenever(stalkerApiService.authenticate(any())).thenReturn(
            Result.success(
                StalkerSession(
                    loadUrl = "http://example.com/stalker_portal/server/load.php",
                    portalReferer = "http://example.com/stalker_portal/c/",
                    token = "token"
                ) to StalkerProviderProfile(accountName = "Stalker")
            )
        )
        org.mockito.kotlin.whenever(stalkerApiService.getVodCategories(any(), any())).thenReturn(
            Result.success(listOf(StalkerCategoryRecord(id = "5", name = "Action")))
        )
        org.mockito.kotlin.whenever(stalkerApiService.getSeriesCategories(any(), any())).thenReturn(
            Result.success(listOf(StalkerCategoryRecord(id = "9", name = "Drama")))
        )
        org.mockito.kotlin.whenever(categoryDao.getByProviderAndTypeSync(1L, ContentType.MOVIE.name)).thenReturn(
            listOf(
                CategoryEntity(
                    categoryId = movieCategoryId,
                    name = "Action",
                    type = ContentType.MOVIE,
                    providerId = 1L
                )
            )
        )
        org.mockito.kotlin.whenever(categoryDao.getByProviderAndTypeSync(1L, ContentType.SERIES.name)).thenReturn(
            listOf(
                CategoryEntity(
                    categoryId = seriesCategoryId,
                    name = "Drama",
                    type = ContentType.SERIES,
                    providerId = 1L
                )
            )
        )
        val indexJobs = mutableMapOf(
            ContentType.MOVIE.name to XtreamIndexJobEntity(
                providerId = 1L,
                section = ContentType.MOVIE.name,
                state = "QUEUED",
                totalCategories = 1
            ),
            ContentType.SERIES.name to XtreamIndexJobEntity(
                providerId = 1L,
                section = ContentType.SERIES.name,
                state = "QUEUED",
                totalCategories = 1
            )
        )
        org.mockito.kotlin.whenever(xtreamIndexJobDao.get(eq(1L), any())).thenAnswer { invocation ->
            indexJobs[invocation.getArgument<String>(1)]
        }
        org.mockito.kotlin.whenever(xtreamIndexJobDao.upsert(any())).thenAnswer { invocation ->
            val entity = invocation.getArgument<XtreamIndexJobEntity>(0)
            indexJobs[entity.section] = entity
            Unit
        }
        val movieHydrationByCategory = mutableMapOf<Long, MovieCategoryHydrationEntity>()
        org.mockito.kotlin.whenever(movieCategoryHydrationDao.get(eq(1L), any())).thenAnswer { invocation ->
            movieHydrationByCategory[invocation.getArgument<Long>(1)]
        }
        org.mockito.kotlin.whenever(movieCategoryHydrationDao.upsert(any())).thenAnswer { invocation ->
            val entity = invocation.getArgument<MovieCategoryHydrationEntity>(0)
            movieHydrationByCategory[entity.categoryId] = entity
            Unit
        }
        val seriesHydrationByCategory = mutableMapOf<Long, SeriesCategoryHydrationEntity>()
        org.mockito.kotlin.whenever(seriesCategoryHydrationDao.get(eq(1L), any())).thenAnswer { invocation ->
            seriesHydrationByCategory[invocation.getArgument<Long>(1)]
        }
        org.mockito.kotlin.whenever(seriesCategoryHydrationDao.upsert(any())).thenAnswer { invocation ->
            val entity = invocation.getArgument<SeriesCategoryHydrationEntity>(0)
            seriesHydrationByCategory[entity.categoryId] = entity
            Unit
        }
        org.mockito.kotlin.whenever(movieDao.getByStreamIds(eq(1L), any())).thenReturn(emptyList())
        org.mockito.kotlin.whenever(seriesDao.getBySeriesIds(eq(1L), any())).thenReturn(emptyList())

        val movieFetchStarted = CompletableDeferred<Unit>()
        val releaseMovieFetch = CompletableDeferred<Unit>()
        val seriesFetchStarted = CompletableDeferred<Unit>()
        org.mockito.kotlin.whenever(stalkerApiService.getVodStreamsPage(any(), any(), eq("5"), eq(1))).doSuspendableAnswer {
            movieFetchStarted.complete(Unit)
            releaseMovieFetch.await()
            Result.success(
                StalkerPagedItems(
                    items = listOf(
                        StalkerItemRecord(
                            id = "100",
                            name = "Movie 100",
                            categoryId = "5",
                            cmd = "ffmpeg http://example.com/100.ts"
                        )
                    ),
                    page = 1,
                    totalPages = 1,
                    pageSize = 14
                )
            )
        }
        org.mockito.kotlin.whenever(stalkerApiService.getSeriesPage(any(), any(), eq("9"), eq(1))).thenAnswer {
            seriesFetchStarted.complete(Unit)
            Result.success(
                StalkerPagedItems(
                    items = listOf(
                        StalkerItemRecord(
                            id = "200",
                            name = "Series 200",
                            categoryId = "9",
                            cmd = "ffmpeg http://example.com/200.ts",
                            isSeries = true
                        )
                    ),
                    page = 1,
                    totalPages = 1,
                    pageSize = 14
                )
            )
        }

        val movieJob = async {
            manager.processQueuedStalkerIndexJobs(
                providerId = 1L,
                section = ContentType.MOVIE,
                maxCategoriesPerSection = 1
            )
        }
        movieFetchStarted.await()

        val seriesJob = async {
            manager.processQueuedStalkerIndexJobs(
                providerId = 1L,
                section = ContentType.SERIES,
                maxCategoriesPerSection = 1
            )
        }

        advanceUntilIdle()
        assertThat(seriesFetchStarted.isCompleted).isFalse()

        releaseMovieFetch.complete(Unit)
        withTimeout(1_000) {
            seriesFetchStarted.await()
        }

        val movieResult = movieJob.await()
        val seriesResult = seriesJob.await()

        if (movieResult is Result.Error) fail(movieResult.exception?.stackTraceToString() ?: movieResult.message)
        if (seriesResult is Result.Error) fail(seriesResult.exception?.stackTraceToString() ?: seriesResult.message)
        assertThat(movieResult.isSuccess).isTrue()
        assertThat(seriesResult.isSuccess).isTrue()
        verify(stalkerApiService).getVodStreamsPage(any(), any(), eq("5"), eq(1))
        verify(stalkerApiService).getSeriesPage(any(), any(), eq("9"), eq(1))
    }

    @Test
    fun `processQueuedStalkerIndexJobs falls back to non paged movie fetch when first page is empty`() = runTest {
        val providerEntity = sampleProvider(ProviderType.STALKER_PORTAL).copy(
            serverUrl = "http://example.com",
            username = "",
            password = "",
            stalkerMacAddress = "00:11:22:33:44:55",
            epgSyncMode = ProviderEpgSyncMode.SKIP,
            maxConnections = 1
        )
        val movieCategoryId = stalkerSyntheticCategoryId(providerEntity.id, ContentType.MOVIE, "5")
        val manager = buildManager(providerType = ProviderType.STALKER_PORTAL, providerEntity = providerEntity)

        org.mockito.kotlin.whenever(stalkerApiService.authenticate(any())).thenReturn(
            Result.success(
                StalkerSession(
                    loadUrl = "http://example.com/stalker_portal/server/load.php",
                    portalReferer = "http://example.com/stalker_portal/c/",
                    token = "token"
                ) to StalkerProviderProfile(accountName = "Stalker")
            )
        )
        org.mockito.kotlin.whenever(stalkerApiService.getVodCategories(any(), any())).thenReturn(
            Result.success(listOf(StalkerCategoryRecord(id = "5", name = "Action")))
        )
        org.mockito.kotlin.whenever(categoryDao.getByProviderAndTypeSync(1L, ContentType.MOVIE.name)).thenReturn(
            listOf(
                CategoryEntity(
                    categoryId = movieCategoryId,
                    name = "Action",
                    type = ContentType.MOVIE,
                    providerId = 1L
                )
            )
        )
        org.mockito.kotlin.whenever(xtreamIndexJobDao.get(1L, ContentType.MOVIE.name)).thenReturn(
            XtreamIndexJobEntity(
                providerId = 1L,
                section = ContentType.MOVIE.name,
                state = "QUEUED",
                totalCategories = 1
            )
        )
        val movieHydrationByCategory = mutableMapOf<Long, MovieCategoryHydrationEntity>()
        org.mockito.kotlin.whenever(movieCategoryHydrationDao.get(eq(1L), any())).thenAnswer { invocation ->
            movieHydrationByCategory[invocation.getArgument<Long>(1)]
        }
        org.mockito.kotlin.whenever(movieCategoryHydrationDao.upsert(any())).thenAnswer { invocation ->
            val entity = invocation.getArgument<MovieCategoryHydrationEntity>(0)
            movieHydrationByCategory[entity.categoryId] = entity
            Unit
        }
        org.mockito.kotlin.whenever(movieDao.getByStreamIds(eq(1L), any())).thenReturn(emptyList())
        org.mockito.kotlin.whenever(movieDao.getCount(1L)).thenReturn(flowOf(0), flowOf(1), flowOf(1))
        org.mockito.kotlin.whenever(movieDao.getCountByCategory(1L, movieCategoryId)).thenReturn(flowOf(1))
        org.mockito.kotlin.whenever(xtreamContentIndexDao.pruneStaleLocalContentRows(1L, ContentType.MOVIE.name)).thenReturn(0)

        org.mockito.kotlin.whenever(stalkerApiService.getVodStreamsPage(any(), any(), eq("5"), eq(1))).thenReturn(
            Result.success(
                StalkerPagedItems(
                    items = emptyList(),
                    page = 1,
                    totalPages = 1,
                    pageSize = 14
                )
            )
        )
        org.mockito.kotlin.whenever(stalkerApiService.getVodStreams(any(), any(), eq("5"))).thenReturn(
            Result.success(
                listOf(
                    StalkerItemRecord(
                        id = "100",
                        name = "Movie 100",
                        categoryId = "5",
                        cmd = "ffmpeg http://example.com/100.ts"
                    )
                )
            )
        )

        val result = manager.processQueuedStalkerIndexJobs(
            providerId = 1L,
            section = ContentType.MOVIE,
            maxCategoriesPerSection = 1
        )

        if (result is Result.Error) fail(result.exception?.stackTraceToString() ?: result.message)
        assertThat(result.isSuccess).isTrue()
        verify(stalkerApiService).getVodStreamsPage(any(), any(), eq("5"), eq(1))
        verify(stalkerApiService).getVodStreams(any(), any(), eq("5"))
        verify(movieDao).insertAll(check { movies ->
            assertThat(movies).hasSize(1)
            assertThat(movies.first().providerId).isEqualTo(1L)
            assertThat(movies.first().streamId).isEqualTo(100L)
        })
    }

    @Test
    fun `processQueuedStalkerIndexJobs uses wildcard movie indexing when wildcard category is the only visible category`() = runTest {
        val providerEntity = sampleProvider(ProviderType.STALKER_PORTAL).copy(
            serverUrl = "http://example.com",
            username = "",
            password = "",
            stalkerMacAddress = "00:11:22:33:44:55",
            epgSyncMode = ProviderEpgSyncMode.SKIP,
            maxConnections = 1
        )
        val wildcardCategoryId = stalkerSyntheticCategoryId(providerEntity.id, ContentType.MOVIE, "*")
        val manager = buildManager(providerType = ProviderType.STALKER_PORTAL, providerEntity = providerEntity)

        org.mockito.kotlin.whenever(stalkerApiService.authenticate(any())).thenReturn(
            Result.success(
                StalkerSession(
                    loadUrl = "http://example.com/stalker_portal/server/load.php",
                    portalReferer = "http://example.com/stalker_portal/c/",
                    token = "token"
                ) to StalkerProviderProfile(accountName = "Stalker")
            )
        )
        org.mockito.kotlin.whenever(stalkerApiService.getVodCategories(any(), any())).thenReturn(
            Result.success(listOf(StalkerCategoryRecord(id = "*", name = "All Movies")))
        )
        org.mockito.kotlin.whenever(categoryDao.getByProviderAndTypeSync(1L, ContentType.MOVIE.name)).thenReturn(
            listOf(
                CategoryEntity(
                    categoryId = wildcardCategoryId,
                    name = "All Movies",
                    type = ContentType.MOVIE,
                    providerId = 1L
                )
            )
        )
        org.mockito.kotlin.whenever(xtreamIndexJobDao.get(1L, ContentType.MOVIE.name)).thenReturn(
            XtreamIndexJobEntity(
                providerId = 1L,
                section = ContentType.MOVIE.name,
                state = "QUEUED",
                totalCategories = 1
            )
        )
        val movieHydrationByCategory = mutableMapOf<Long, MovieCategoryHydrationEntity>()
        org.mockito.kotlin.whenever(movieCategoryHydrationDao.get(eq(1L), any())).thenAnswer { invocation ->
            movieHydrationByCategory[invocation.getArgument<Long>(1)]
        }
        org.mockito.kotlin.whenever(movieCategoryHydrationDao.upsert(any())).thenAnswer { invocation ->
            val entity = invocation.getArgument<MovieCategoryHydrationEntity>(0)
            movieHydrationByCategory[entity.categoryId] = entity
            Unit
        }
        org.mockito.kotlin.whenever(movieDao.getByStreamIds(eq(1L), any())).thenReturn(emptyList())
        org.mockito.kotlin.whenever(movieDao.getCount(1L)).thenReturn(flowOf(0), flowOf(1), flowOf(1))
        org.mockito.kotlin.whenever(xtreamContentIndexDao.pruneStaleLocalContentRows(1L, ContentType.MOVIE.name)).thenReturn(0)
        org.mockito.kotlin.whenever(stalkerApiService.getVodStreamsPage(any(), any(), eq("*"), eq(1))).thenReturn(
            Result.success(
                StalkerPagedItems(
                    items = listOf(
                        StalkerItemRecord(
                            id = "100",
                            name = "Movie 100",
                            categoryId = "5",
                            cmd = "ffmpeg http://example.com/100.ts"
                        )
                    ),
                    page = 1,
                    totalPages = 1,
                    pageSize = 14
                )
            )
        )

        val result = manager.processQueuedStalkerIndexJobs(
            providerId = 1L,
            section = ContentType.MOVIE,
            maxCategoriesPerSection = 1
        )

        if (result is Result.Error) fail(result.exception?.stackTraceToString() ?: result.message)
        assertThat(result.isSuccess).isTrue()
        verify(stalkerApiService).getVodStreamsPage(any(), any(), eq("*"), eq(1))
        verify(movieDao).insertAll(check { movies ->
            assertThat(movies).hasSize(1)
            assertThat(movies.first().providerId).isEqualTo(1L)
            assertThat(movies.first().streamId).isEqualTo(100L)
        })
    }

    @Test
    fun `chunkedLookupById splits large id lists below sqlite variable limit`() = runTest {
        val requestedChunks = mutableListOf<List<Long>>()

        val result = chunkedLookupById(
            ids = (1L..1_000L).toList(),
            chunkSize = 900,
            fetch = { chunk: List<Long> ->
                requestedChunks += chunk
                chunk.map { id -> MovieEntity(streamId = id, name = "Movie $id", providerId = 1L, streamUrl = "https://example.com/$id") }
            },
            keySelector = { entity: MovieEntity -> entity.streamId }
        )

        assertThat(result).hasSize(1_000)
        assertThat(requestedChunks).hasSize(2)
        assertThat(requestedChunks[0]).hasSize(900)
        assertThat(requestedChunks[1]).hasSize(100)
    }

    @Test
    fun sync_stalker_persists_only_vod_and_series_categories_during_on_demand_initial_sync() = runTest {
        val providerEntity = sampleProvider(ProviderType.STALKER_PORTAL).copy(
            serverUrl = "http://example.com",
            username = "",
            password = "",
            stalkerMacAddress = "00:11:22:33:44:55",
            epgSyncMode = ProviderEpgSyncMode.SKIP
        )
        val readinessTracker = StalkerReadinessTracker()
        val manager = buildManager(
            providerType = ProviderType.STALKER_PORTAL,
            providerEntity = providerEntity,
            readinessTracker = readinessTracker
        )

        org.mockito.kotlin.whenever(stalkerApiService.authenticate(any())).thenReturn(
            Result.success(
                StalkerSession(
                    loadUrl = "http://example.com/stalker_portal/server/load.php",
                    portalReferer = "http://example.com/stalker_portal/c/",
                    token = "token"
                ) to StalkerProviderProfile(accountName = "Stalker")
            )
        )
        org.mockito.kotlin.whenever(stalkerApiService.getLiveCategories(any(), any())).thenReturn(
            Result.success(listOf(StalkerCategoryRecord(id = "10", name = "News")))
        )
        stalkerApiService.stubStreamLiveStreams(
            listOf(
                StalkerItemRecord(
                    id = "100",
                    name = "News",
                    categoryId = "10",
                    cmd = "ffmpeg http://example.com/live.ts"
                )
            )
        )
        org.mockito.kotlin.whenever(stalkerApiService.getVodCategories(any(), any())).thenReturn(
            Result.success(listOf(StalkerCategoryRecord(id = "42", name = "Action")))
        )
        org.mockito.kotlin.whenever(stalkerApiService.getSeriesCategories(any(), any())).thenReturn(
            Result.success(listOf(StalkerCategoryRecord(id = "77", name = "Drama")))
        )

        val result = manager.sync(providerId = 1L, force = false)

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val metadata = syncMetadataRepo.getMetadata(1L)
        assertThat(metadata?.movieSyncMode).isEqualTo(com.streamvault.domain.model.VodSyncMode.PAGED)
        assertThat(metadata?.movieCount).isEqualTo(0)
        assertThat(metadata?.seriesCount).isEqualTo(0)
        assertThat(readinessTracker.current(1L)?.state)
            .isEqualTo(com.streamvault.domain.model.StalkerReadiness.READY)
        assertThat(readinessTracker.current(1L)?.authenticatedAt).isNotNull()
        assertThat(readinessTracker.current(1L)?.liveReadyAt).isNotNull()
        assertThat(readinessTracker.current(1L)?.categoriesReadyAt).isNotNull()
        verify(xtreamIndexJobDao, org.mockito.kotlin.times(0)).upsert(any())
        verify(stalkerApiService).getVodCategories(any(), any())
        verify(stalkerApiService).getSeriesCategories(any(), any())
        verify(stalkerApiService).streamLiveStreams(any(), any(), any())
        verify(stalkerApiService, org.mockito.kotlin.times(0)).getLiveStreams(any(), any(), eq("10"))
        verify(stalkerApiService, org.mockito.kotlin.times(0)).streamEpg(any(), any(), any(), any(), any())
        verify(stalkerApiService, org.mockito.kotlin.times(0)).getVodStreams(any(), any(), anyOrNull())
        verify(stalkerApiService, org.mockito.kotlin.times(0)).getSeries(any(), any(), anyOrNull())
    }

    @Test
    fun sync_stalker_skips_bulk_live_when_validated_capability_is_unsupported() = runTest {
        val providerEntity = sampleProvider(ProviderType.STALKER_PORTAL).copy(
            serverUrl = "http://example.com",
            username = "",
            password = "",
            stalkerMacAddress = "00:11:22:33:44:55",
            epgSyncMode = ProviderEpgSyncMode.SKIP
        )
        val portalStateStore = mock<com.streamvault.data.remote.stalker.StalkerPortalStateStore>()
        org.mockito.kotlin.whenever(portalStateStore.getValidated(eq(1L), any())).thenReturn(
            StalkerPortalStateEntity(
                providerId = 1L,
                bulkLiveSupported = false,
                bulkLiveCategoryFidelity = false,
                validatedAt = System.currentTimeMillis()
            )
        )
        val manager = buildManager(
            providerType = ProviderType.STALKER_PORTAL,
            providerEntity = providerEntity,
            portalStateStore = portalStateStore
        )

        org.mockito.kotlin.whenever(stalkerApiService.authenticate(any())).thenReturn(
            Result.success(
                StalkerSession(
                    loadUrl = "http://example.com/stalker_portal/server/load.php",
                    portalReferer = "http://example.com/stalker_portal/c/",
                    token = "token"
                ) to StalkerProviderProfile(accountName = "Stalker")
            )
        )
        org.mockito.kotlin.whenever(stalkerApiService.getLiveCategories(any(), any())).thenReturn(
            Result.success(listOf(StalkerCategoryRecord(id = "10", name = "News")))
        )
        org.mockito.kotlin.whenever(stalkerApiService.getLiveStreams(any(), any(), eq("10"))).thenReturn(
            Result.success(
                listOf(
                    StalkerItemRecord(
                        id = "100",
                        name = "News",
                        categoryId = "10",
                        cmd = "ffmpeg http://example.com/live.ts"
                    )
                )
            )
        )

        val result = manager.sync(providerId = 1L, force = false)

        assertThat(result).isInstanceOf(Result.Success::class.java)
        verify(stalkerApiService, org.mockito.kotlin.times(0)).streamLiveStreams(any(), any(), any())
        verify(stalkerApiService).getLiveStreams(any(), any(), eq("10"))
    }

    @Test
    fun sync_stalker_does_not_persist_bulk_unsupported_after_transient_bulk_failure() = runTest {
        val providerEntity = sampleProvider(ProviderType.STALKER_PORTAL).copy(
            serverUrl = "http://example.com",
            username = "",
            password = "",
            stalkerMacAddress = "00:11:22:33:44:55",
            epgSyncMode = ProviderEpgSyncMode.SKIP
        )
        val portalStateStore = mock<com.streamvault.data.remote.stalker.StalkerPortalStateStore>()
        org.mockito.kotlin.whenever(portalStateStore.getValidated(eq(1L), any())).thenReturn(null)
        val manager = buildManager(
            providerType = ProviderType.STALKER_PORTAL,
            providerEntity = providerEntity,
            portalStateStore = portalStateStore
        )

        org.mockito.kotlin.whenever(stalkerApiService.authenticate(any())).thenReturn(
            Result.success(
                StalkerSession(
                    loadUrl = "http://example.com/stalker_portal/server/load.php",
                    portalReferer = "http://example.com/stalker_portal/c/",
                    token = "token"
                ) to StalkerProviderProfile(accountName = "Stalker")
            )
        )
        org.mockito.kotlin.whenever(stalkerApiService.getLiveCategories(any(), any())).thenReturn(
            Result.success(listOf(StalkerCategoryRecord(id = "10", name = "News")))
        )
        // A bulk transfer cut short by a slow link surfaces as a generic transport-style
        // failure; it must not downgrade the learned portal capability.
        stalkerApiService.stubStreamLiveStreamsError("Bulk live request timed out")
        org.mockito.kotlin.whenever(stalkerApiService.getLiveStreams(any(), any(), eq("10"))).thenReturn(
            Result.success(
                listOf(
                    StalkerItemRecord(
                        id = "100",
                        name = "News",
                        categoryId = "10",
                        cmd = "ffmpeg http://example.com/live.ts"
                    )
                )
            )
        )
        org.mockito.kotlin.whenever(stalkerApiService.getVodCategories(any(), any())).thenReturn(Result.success(emptyList()))
        org.mockito.kotlin.whenever(stalkerApiService.getSeriesCategories(any(), any())).thenReturn(Result.success(emptyList()))

        val result = manager.sync(providerId = 1L, force = false)

        assertThat(result).isInstanceOf(Result.Success::class.java)
        verify(stalkerApiService).streamLiveStreams(any(), any(), any())
        verify(stalkerApiService).getLiveStreams(any(), any(), eq("10"))
        verify(portalStateStore, org.mockito.kotlin.times(0)).recordBulkLive(eq(1L), eq(false), anyOrNull(), any(), anyOrNull())
    }

    @Test
    fun sync_stalker_persists_bulk_unsupported_after_definitive_bulk_failure() = runTest {
        val providerEntity = sampleProvider(ProviderType.STALKER_PORTAL).copy(
            serverUrl = "http://example.com",
            username = "",
            password = "",
            stalkerMacAddress = "00:11:22:33:44:55",
            epgSyncMode = ProviderEpgSyncMode.SKIP
        )
        val portalStateStore = mock<com.streamvault.data.remote.stalker.StalkerPortalStateStore>()
        org.mockito.kotlin.whenever(portalStateStore.getValidated(eq(1L), any())).thenReturn(null)
        val manager = buildManager(
            providerType = ProviderType.STALKER_PORTAL,
            providerEntity = providerEntity,
            portalStateStore = portalStateStore
        )

        org.mockito.kotlin.whenever(stalkerApiService.authenticate(any())).thenReturn(
            Result.success(
                StalkerSession(
                    loadUrl = "http://example.com/stalker_portal/server/load.php",
                    portalReferer = "http://example.com/stalker_portal/c/",
                    token = "token"
                ) to StalkerProviderProfile(accountName = "Stalker")
            )
        )
        org.mockito.kotlin.whenever(stalkerApiService.getLiveCategories(any(), any())).thenReturn(
            Result.success(listOf(StalkerCategoryRecord(id = "10", name = "News")))
        )
        org.mockito.kotlin.whenever(stalkerApiService.streamLiveStreams(any(), any(), any())).thenReturn(
            Result.error(
                "Portal authorization failed with HTTP 403.",
                com.streamvault.data.remote.stalker.StalkerApiError.Authorization(
                    message = "Portal authorization failed with HTTP 403.",
                    httpStatus = 403
                )
            )
        )
        org.mockito.kotlin.whenever(stalkerApiService.getLiveStreams(any(), any(), eq("10"))).thenReturn(
            Result.success(
                listOf(
                    StalkerItemRecord(
                        id = "100",
                        name = "News",
                        categoryId = "10",
                        cmd = "ffmpeg http://example.com/live.ts"
                    )
                )
            )
        )
        org.mockito.kotlin.whenever(stalkerApiService.getVodCategories(any(), any())).thenReturn(Result.success(emptyList()))
        org.mockito.kotlin.whenever(stalkerApiService.getSeriesCategories(any(), any())).thenReturn(Result.success(emptyList()))

        val result = manager.sync(providerId = 1L, force = false)

        assertThat(result).isInstanceOf(Result.Success::class.java)
        verify(portalStateStore).recordBulkLive(eq(1L), eq(false), anyOrNull(), any(), anyOrNull())
    }

    @Test
    fun sync_stalker_reprobes_bulk_live_when_unsupported_verdict_is_stale() = runTest {
        val providerEntity = sampleProvider(ProviderType.STALKER_PORTAL).copy(
            serverUrl = "http://example.com",
            username = "",
            password = "",
            stalkerMacAddress = "00:11:22:33:44:55",
            epgSyncMode = ProviderEpgSyncMode.SKIP
        )
        val portalStateStore = mock<com.streamvault.data.remote.stalker.StalkerPortalStateStore>()
        // A negative verdict recorded under the old fixed-timeout logic must expire
        // long before the 7-day validated-state TTL, otherwise affected portals keep
        // using the slow per-category path for a week.
        org.mockito.kotlin.whenever(portalStateStore.getValidated(eq(1L), any())).thenReturn(
            StalkerPortalStateEntity(
                providerId = 1L,
                bulkLiveSupported = false,
                validatedAt = System.currentTimeMillis() - (7L * 60L * 60L * 1000L)
            )
        )
        val manager = buildManager(
            providerType = ProviderType.STALKER_PORTAL,
            providerEntity = providerEntity,
            portalStateStore = portalStateStore
        )

        org.mockito.kotlin.whenever(stalkerApiService.authenticate(any())).thenReturn(
            Result.success(
                StalkerSession(
                    loadUrl = "http://example.com/stalker_portal/server/load.php",
                    portalReferer = "http://example.com/stalker_portal/c/",
                    token = "token"
                ) to StalkerProviderProfile(accountName = "Stalker")
            )
        )
        org.mockito.kotlin.whenever(stalkerApiService.getLiveCategories(any(), any())).thenReturn(
            Result.success(listOf(StalkerCategoryRecord(id = "10", name = "News")))
        )
        stalkerApiService.stubStreamLiveStreams(
            listOf(
                StalkerItemRecord(
                    id = "100",
                    name = "News",
                    categoryId = "10",
                    cmd = "ffmpeg http://example.com/live.ts"
                )
            )
        )
        org.mockito.kotlin.whenever(stalkerApiService.getVodCategories(any(), any())).thenReturn(Result.success(emptyList()))
        org.mockito.kotlin.whenever(stalkerApiService.getSeriesCategories(any(), any())).thenReturn(Result.success(emptyList()))

        val result = manager.sync(providerId = 1L, force = false)

        assertThat(result).isInstanceOf(Result.Success::class.java)
        verify(stalkerApiService).streamLiveStreams(any(), any(), any())
        verify(stalkerApiService, org.mockito.kotlin.times(0)).getLiveStreams(any(), any(), eq("10"))
        verify(portalStateStore).recordBulkLive(eq(1L), eq(true), anyOrNull(), any(), anyOrNull())
    }

    @Test
    fun sync_stalker_skips_known_unsupported_epg_and_finishes_ready_with_warning() = runTest {
        val providerEntity = sampleProvider(ProviderType.STALKER_PORTAL).copy(
            serverUrl = "http://example.com",
            username = "",
            password = "",
            stalkerMacAddress = "00:11:22:33:44:55",
            epgSyncMode = ProviderEpgSyncMode.UPFRONT,
            epgUrl = ""
        )
        val portalStateStore = mock<com.streamvault.data.remote.stalker.StalkerPortalStateStore>()
        org.mockito.kotlin.whenever(portalStateStore.getValidated(eq(1L), any())).thenReturn(
            StalkerPortalStateEntity(
                providerId = 1L,
                epgSupported = false,
                validatedAt = System.currentTimeMillis()
            )
        )
        val readinessTracker = StalkerReadinessTracker()
        val manager = buildManager(
            providerType = ProviderType.STALKER_PORTAL,
            providerEntity = providerEntity,
            readinessTracker = readinessTracker,
            portalStateStore = portalStateStore
        )

        org.mockito.kotlin.whenever(stalkerApiService.authenticate(any())).thenReturn(
            Result.success(
                StalkerSession(
                    loadUrl = "http://example.com/stalker_portal/server/load.php",
                    portalReferer = "http://example.com/stalker_portal/c/",
                    token = "token"
                ) to StalkerProviderProfile(accountName = "Stalker")
            )
        )
        org.mockito.kotlin.whenever(stalkerApiService.getLiveCategories(any(), any())).thenReturn(
            Result.success(emptyList())
        )

        val result = manager.sync(providerId = 1L, force = false)

        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat(readinessTracker.current(1L)?.state)
            .isEqualTo(com.streamvault.domain.model.StalkerReadiness.READY_WITH_WARNINGS)
        assertThat(readinessTracker.current(1L)?.warningCount).isGreaterThan(0)
        verify(stalkerApiService, org.mockito.kotlin.times(0)).streamBulkEpg(any(), any(), any(), any())
        verify(stalkerApiService, org.mockito.kotlin.times(0)).streamEpg(any(), any(), any(), any(), any())
    }

    @Test
    fun sync_stalker_with_empty_live_catalog_still_commits_vod_and_series_categories() = runTest {
        val providerEntity = sampleProvider(ProviderType.STALKER_PORTAL).copy(
            serverUrl = "http://example.com",
            username = "",
            password = "",
            stalkerMacAddress = "00:11:22:33:44:55",
            epgSyncMode = ProviderEpgSyncMode.SKIP
        )
        val manager = buildManager(providerType = ProviderType.STALKER_PORTAL, providerEntity = providerEntity)

        org.mockito.kotlin.whenever(stalkerApiService.authenticate(any())).thenReturn(
            Result.success(
                StalkerSession(
                    loadUrl = "http://example.com/stalker_portal/server/load.php",
                    portalReferer = "http://example.com/stalker_portal/c/",
                    token = "token"
                ) to StalkerProviderProfile(accountName = "Stalker")
            )
        )
        org.mockito.kotlin.whenever(stalkerApiService.getLiveCategories(any(), any())).thenReturn(
            Result.success(emptyList())
        )
        stalkerApiService.stubStreamLiveStreams(emptyList())
        org.mockito.kotlin.whenever(stalkerApiService.getVodCategories(any(), any())).thenReturn(
            Result.success(listOf(StalkerCategoryRecord(id = "42", name = "Action")))
        )
        org.mockito.kotlin.whenever(stalkerApiService.getSeriesCategories(any(), any())).thenReturn(
            Result.success(listOf(StalkerCategoryRecord(id = "77", name = "Drama")))
        )

        val result = manager.sync(providerId = 1L, force = false)

        assertThat(result).isInstanceOf(Result.Success::class.java)
        verify(stalkerApiService).getVodCategories(any(), any())
        verify(stalkerApiService).getSeriesCategories(any(), any())
        verify(xtreamIndexJobDao, org.mockito.kotlin.times(0)).upsert(any())
    }

    @Test
    fun sync_stalker_persists_wildcard_vod_and_series_categories_when_portal_category_lists_are_empty() = runTest {
        val providerEntity = sampleProvider(ProviderType.STALKER_PORTAL).copy(
            serverUrl = "http://example.com",
            username = "",
            password = "",
            stalkerMacAddress = "00:11:22:33:44:55",
            epgSyncMode = ProviderEpgSyncMode.SKIP
        )
        val manager = buildManager(providerType = ProviderType.STALKER_PORTAL, providerEntity = providerEntity)

        org.mockito.kotlin.whenever(stalkerApiService.authenticate(any())).thenReturn(
            Result.success(
                StalkerSession(
                    loadUrl = "http://example.com/stalker_portal/server/load.php",
                    portalReferer = "http://example.com/stalker_portal/c/",
                    token = "token"
                ) to StalkerProviderProfile(accountName = "Stalker")
            )
        )
        org.mockito.kotlin.whenever(stalkerApiService.getLiveCategories(any(), any())).thenReturn(
            Result.success(listOf(StalkerCategoryRecord(id = "10", name = "News")))
        )
        stalkerApiService.stubStreamLiveStreams(
            listOf(
                StalkerItemRecord(
                    id = "100",
                    name = "News",
                    categoryId = "10",
                    cmd = "ffmpeg http://example.com/live.ts"
                )
            )
        )
        org.mockito.kotlin.whenever(stalkerApiService.getVodCategories(any(), any())).thenReturn(Result.success(emptyList()))
        org.mockito.kotlin.whenever(stalkerApiService.getSeriesCategories(any(), any())).thenReturn(Result.success(emptyList()))

        val result = manager.sync(providerId = 1L, force = false)

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val categoryStagesCaptor = argumentCaptor<List<com.streamvault.data.local.entity.CategoryImportStageEntity>>()
        verify(catalogSyncDao, atLeastOnce()).insertCategoryStages(categoryStagesCaptor.capture())
        val stagedCategories = categoryStagesCaptor.allValues.flatten()
        assertThat(stagedCategories.any {
            it.type == ContentType.MOVIE && it.name == "All Movies" &&
                it.categoryId == stalkerSyntheticCategoryId(1L, ContentType.MOVIE, "*")
        }).isTrue()
        assertThat(stagedCategories.any {
            it.type == ContentType.SERIES && it.name == "All Series" &&
                it.categoryId == stalkerSyntheticCategoryId(1L, ContentType.SERIES, "*")
        }).isTrue()
        verify(xtreamIndexJobDao, org.mockito.kotlin.times(0)).upsert(any())
        verify(stalkerApiService, org.mockito.kotlin.times(0)).getVodStreamsPage(any(), any(), anyOrNull(), any())
        verify(stalkerApiService, org.mockito.kotlin.times(0)).getSeriesPage(any(), any(), anyOrNull(), any())
    }

    @Test
    fun sync_stalker_runs_upfront_epg_independently_of_catalog_indexing() = runTest {
        val providerEntity = sampleProvider(ProviderType.STALKER_PORTAL).copy(
            serverUrl = "http://example.com",
            username = "",
            password = "",
            stalkerMacAddress = "00:11:22:33:44:55",
            epgSyncMode = ProviderEpgSyncMode.UPFRONT
        )
        val manager = buildManager(providerType = ProviderType.STALKER_PORTAL, providerEntity = providerEntity)

        org.mockito.kotlin.whenever(stalkerApiService.authenticate(any())).thenReturn(
            Result.success(
                StalkerSession(
                    loadUrl = "http://example.com/stalker_portal/server/load.php",
                    portalReferer = "http://example.com/stalker_portal/c/",
                    token = "token"
                ) to StalkerProviderProfile(accountName = "Stalker")
            )
        )
        org.mockito.kotlin.whenever(stalkerApiService.getLiveCategories(any(), any())).thenReturn(
            Result.success(listOf(StalkerCategoryRecord(id = "10", name = "News")))
        )
        stalkerApiService.stubStreamLiveStreams(
            listOf(
                StalkerItemRecord(
                    id = "100",
                    name = "News",
                    categoryId = "10",
                    cmd = "ffmpeg http://example.com/live.ts"
                )
            )
        )
        org.mockito.kotlin.whenever(stalkerApiService.getVodCategories(any(), any())).thenReturn(
            Result.success(listOf(StalkerCategoryRecord(id = "42", name = "Action")))
        )
        org.mockito.kotlin.whenever(stalkerApiService.getSeriesCategories(any(), any())).thenReturn(
            Result.success(listOf(StalkerCategoryRecord(id = "77", name = "Drama")))
        )
        org.mockito.kotlin.whenever(channelDao.getGuideSyncEntriesByProvider(1L)).thenReturn(emptyList())
        org.mockito.kotlin.whenever(programDao.countByProvider(1L)).thenReturn(0)

        val result = manager.sync(providerId = 1L, force = false)

        assertThat(result).isInstanceOf(Result.Success::class.java)
        verify(stalkerApiService, org.mockito.kotlin.times(0)).getVodStreamsPage(any(), any(), anyOrNull(), any())
        verify(stalkerApiService, org.mockito.kotlin.times(0)).getSeriesPage(any(), any(), anyOrNull(), any())
    }

    @Test
    fun syncEpg_stalker_does_not_wait_for_pending_catalog_jobs() = runTest {
        val providerEntity = sampleProvider(ProviderType.STALKER_PORTAL).copy(
            serverUrl = "http://example.com",
            username = "",
            password = "",
            stalkerMacAddress = "00:11:22:33:44:55",
            epgSyncMode = ProviderEpgSyncMode.UPFRONT
        )
        val manager = buildManager(providerType = ProviderType.STALKER_PORTAL, providerEntity = providerEntity)
        val movieCategoryId = stalkerSyntheticCategoryId(providerEntity.id, ContentType.MOVIE, "5")

        org.mockito.kotlin.whenever(xtreamIndexJobDao.get(1L, ContentType.MOVIE.name)).thenReturn(
            XtreamIndexJobEntity(
                providerId = 1L,
                section = ContentType.MOVIE.name,
                state = "RUNNING"
            )
        )
        org.mockito.kotlin.whenever(xtreamIndexJobDao.get(1L, ContentType.SERIES.name)).thenReturn(
            XtreamIndexJobEntity(
                providerId = 1L,
                section = ContentType.SERIES.name,
                state = "QUEUED"
            )
        )
        org.mockito.kotlin.whenever(channelDao.getGuideSyncEntriesByProvider(1L)).thenReturn(emptyList())
        org.mockito.kotlin.whenever(programDao.countByProvider(1L)).thenReturn(0)

        val result = manager.syncEpg(providerId = 1L, force = false)

        assertThat(result).isInstanceOf(Result.Success::class.java)
        verify(stalkerApiService, org.mockito.kotlin.times(0)).getVodStreamsPage(any(), any(), anyOrNull(), any())
        verify(stalkerApiService, org.mockito.kotlin.times(0)).getSeriesPage(any(), any(), anyOrNull(), any())
    }

    @Test
    fun syncEpg_stalker_does_not_wait_for_partial_catalog_job() = runTest {
        val providerEntity = sampleProvider(ProviderType.STALKER_PORTAL).copy(
            serverUrl = "http://example.com",
            username = "",
            password = "",
            stalkerMacAddress = "00:11:22:33:44:55",
            epgSyncMode = ProviderEpgSyncMode.UPFRONT
        )
        val manager = buildManager(providerType = ProviderType.STALKER_PORTAL, providerEntity = providerEntity)
        val movieCategoryId = stalkerSyntheticCategoryId(providerEntity.id, ContentType.MOVIE, "5")

        org.mockito.kotlin.whenever(xtreamIndexJobDao.get(1L, ContentType.MOVIE.name)).thenReturn(
            XtreamIndexJobEntity(
                providerId = 1L,
                section = ContentType.MOVIE.name,
                state = "PARTIAL"
            )
        )
        org.mockito.kotlin.whenever(xtreamIndexJobDao.get(1L, ContentType.SERIES.name)).thenReturn(
            XtreamIndexJobEntity(
                providerId = 1L,
                section = ContentType.SERIES.name,
                state = "SUCCESS"
            )
        )
        org.mockito.kotlin.whenever(categoryDao.getByProviderAndTypeSync(1L, ContentType.MOVIE.name)).thenReturn(
            listOf(
                CategoryEntity(
                    categoryId = movieCategoryId,
                    name = "Action",
                    type = ContentType.MOVIE,
                    providerId = 1L
                )
            )
        )
        org.mockito.kotlin.whenever(movieCategoryHydrationDao.get(1L, movieCategoryId)).thenReturn(
            MovieCategoryHydrationEntity(
                providerId = 1L,
                categoryId = movieCategoryId,
                lastStatus = "FAILED_RETRYABLE",
                lastAttemptedPage = 1,
                retryAfterMs = System.currentTimeMillis() + 60_000L,
                failureCount = 1,
                retryBudgetRemaining = 2
            )
        )
        org.mockito.kotlin.whenever(channelDao.getGuideSyncEntriesByProvider(1L)).thenReturn(emptyList())
        org.mockito.kotlin.whenever(programDao.countByProvider(1L)).thenReturn(0)

        val result = manager.syncEpg(providerId = 1L, force = false)

        assertThat(result).isInstanceOf(Result.Success::class.java)
        verify(stalkerApiService, org.mockito.kotlin.times(0)).getVodStreamsPage(any(), any(), anyOrNull(), any())
        verify(stalkerApiService, org.mockito.kotlin.times(0)).getSeriesPage(any(), any(), anyOrNull(), any())
    }

    @Test
    fun sync_stalker_recovers_when_live_categories_fail_but_bulk_channels_work() = runTest {
        val providerEntity = sampleProvider(ProviderType.STALKER_PORTAL).copy(
            serverUrl = "http://example.com",
            username = "",
            password = "",
            stalkerMacAddress = "00:11:22:33:44:55",
            epgSyncMode = ProviderEpgSyncMode.SKIP
        )
        val manager = buildManager(providerType = ProviderType.STALKER_PORTAL, providerEntity = providerEntity)

        org.mockito.kotlin.whenever(stalkerApiService.authenticate(any())).thenReturn(
            Result.success(
                StalkerSession(
                    loadUrl = "http://example.com/stalker_portal/server/load.php",
                    portalReferer = "http://example.com/stalker_portal/c/",
                    token = "token"
                ) to StalkerProviderProfile(accountName = "Stalker")
            )
        )
        org.mockito.kotlin.whenever(stalkerApiService.getLiveCategories(any(), any())).thenReturn(
            Result.error("Portal returned an empty response for get_genres.")
        )
        stalkerApiService.stubStreamLiveStreams(
            listOf(
                StalkerItemRecord(
                    id = "100",
                    name = "News",
                    categoryId = "10",
                    categoryName = "News",
                    cmd = "ffmpeg http://example.com/live.ts"
                )
            )
        )
        org.mockito.kotlin.whenever(stalkerApiService.getVodCategories(any(), any())).thenReturn(Result.success(emptyList()))
        org.mockito.kotlin.whenever(stalkerApiService.getSeriesCategories(any(), any())).thenReturn(Result.success(emptyList()))

        val result = manager.sync(providerId = 1L, force = false)

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val categoryStagesCaptor = argumentCaptor<List<com.streamvault.data.local.entity.CategoryImportStageEntity>>()
        verify(catalogSyncDao, atLeastOnce()).insertCategoryStages(categoryStagesCaptor.capture())
        assertThat(categoryStagesCaptor.allValues.flatten().any { it.type == com.streamvault.domain.model.ContentType.LIVE && it.name == "News" }).isTrue()
        verify(stalkerApiService).getLiveCategories(any(), any())
        verify(stalkerApiService).streamLiveStreams(any(), any(), any())
    }

    @Test
    fun sync_stalker_recovers_when_bulk_live_streaming_fails_using_category_fetches() = runTest {
        val providerEntity = sampleProvider(ProviderType.STALKER_PORTAL).copy(
            serverUrl = "http://example.com",
            username = "",
            password = "",
            stalkerMacAddress = "00:11:22:33:44:55",
            epgSyncMode = ProviderEpgSyncMode.SKIP
        )
        val manager = buildManager(providerType = ProviderType.STALKER_PORTAL, providerEntity = providerEntity)

        org.mockito.kotlin.whenever(stalkerApiService.authenticate(any())).thenReturn(
            Result.success(
                StalkerSession(
                    loadUrl = "http://example.com/stalker_portal/server/load.php",
                    portalReferer = "http://example.com/stalker_portal/c/",
                    token = "token"
                ) to StalkerProviderProfile(accountName = "Stalker")
            )
        )
        org.mockito.kotlin.whenever(stalkerApiService.getLiveCategories(any(), any())).thenReturn(
            Result.success(
                listOf(
                    StalkerCategoryRecord(id = "10", name = "News"),
                    StalkerCategoryRecord(id = "20", name = "Sports")
                )
            )
        )
        stalkerApiService.stubStreamLiveStreamsError("Portal busy")
        org.mockito.kotlin.whenever(stalkerApiService.getLiveStreams(any(), any(), eq("10"))).thenReturn(
            Result.success(
                listOf(
                    StalkerItemRecord(
                        id = "100",
                        name = "News",
                        categoryId = "10",
                        cmd = "ffmpeg http://example.com/news.ts"
                    )
                )
            )
        )
        org.mockito.kotlin.whenever(stalkerApiService.getLiveStreams(any(), any(), eq("20"))).thenReturn(
            Result.success(
                listOf(
                    StalkerItemRecord(
                        id = "200",
                        name = "Sports",
                        categoryId = "20",
                        cmd = "ffmpeg http://example.com/sports.ts"
                    )
                )
            )
        )
        org.mockito.kotlin.whenever(stalkerApiService.getVodCategories(any(), any())).thenReturn(Result.success(emptyList()))
        org.mockito.kotlin.whenever(stalkerApiService.getSeriesCategories(any(), any())).thenReturn(Result.success(emptyList()))

        val result = manager.sync(providerId = 1L, force = false)

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val categoryStagesCaptor = argumentCaptor<List<com.streamvault.data.local.entity.CategoryImportStageEntity>>()
        verify(catalogSyncDao, atLeastOnce()).insertCategoryStages(categoryStagesCaptor.capture())
        assertThat(categoryStagesCaptor.allValues.flatten().any { it.type == com.streamvault.domain.model.ContentType.LIVE && it.name == "News" }).isTrue()
        assertThat(categoryStagesCaptor.allValues.flatten().any { it.type == com.streamvault.domain.model.ContentType.LIVE && it.name == "Sports" }).isTrue()
        verify(stalkerApiService).streamLiveStreams(any(), any(), any())
        verify(stalkerApiService).getLiveStreams(any(), any(), eq("10"))
        verify(stalkerApiService).getLiveStreams(any(), any(), eq("20"))
    }

    @Test
    fun sync_stalker_surfaces_profile_hint_when_catalog_access_is_missing() = runTest {
        val providerEntity = sampleProvider(ProviderType.STALKER_PORTAL).copy(
            serverUrl = "http://example.com",
            username = "",
            password = "",
            stalkerMacAddress = "00:11:22:33:44:55",
            epgSyncMode = ProviderEpgSyncMode.SKIP
        )
        val manager = buildManager(providerType = ProviderType.STALKER_PORTAL, providerEntity = providerEntity)

        org.mockito.kotlin.whenever(stalkerApiService.authenticate(any())).thenReturn(
            Result.success(
                StalkerSession(
                    loadUrl = "http://example.com/stalker_portal/server/load.php",
                    portalReferer = "http://example.com/stalker_portal/c/",
                    token = "token"
                ) to StalkerProviderProfile(
                    accountId = "0",
                    accountName = "0",
                    authAccess = false
                )
            )
        )
        org.mockito.kotlin.whenever(stalkerApiService.getLiveCategories(any(), any())).thenReturn(
            Result.error("Portal returned an empty response for get_genres.")
        )
        stalkerApiService.stubStreamLiveStreamsError("Portal returned an empty response for get_ordered_list.")
        org.mockito.kotlin.whenever(stalkerApiService.getVodCategories(any(), any())).thenReturn(
            Result.error("Portal returned an empty response for get_categories.")
        )
        org.mockito.kotlin.whenever(stalkerApiService.getSeriesCategories(any(), any())).thenReturn(
            Result.error("Portal returned an empty response for get_categories.")
        )

        val result = manager.sync(providerId = 1L, force = false)

        assertThat(result).isInstanceOf(Result.Error::class.java)
        val error = result as Result.Error
        assertThat(error.message).contains("no accessible catalog data")
        assertThat(error.message).contains("MAC is activated")
    }

    @Test
    fun sync_stalker_upfront_epg_imports_native_portal_guide_before_returning() = runTest {
        val providerEntity = sampleProvider(ProviderType.STALKER_PORTAL).copy(
            serverUrl = "http://example.com",
            username = "",
            password = "",
            stalkerMacAddress = "00:11:22:33:44:55",
            epgSyncMode = ProviderEpgSyncMode.UPFRONT,
            epgUrl = ""
        )
        val manager = buildManager(providerType = ProviderType.STALKER_PORTAL, providerEntity = providerEntity)

        org.mockito.kotlin.whenever(stalkerApiService.authenticate(any())).thenReturn(
            Result.success(
                StalkerSession(
                    loadUrl = "http://example.com/stalker_portal/server/load.php",
                    portalReferer = "http://example.com/stalker_portal/c/",
                    token = "token"
                ) to StalkerProviderProfile(accountName = "Stalker")
            )
        )
        org.mockito.kotlin.whenever(stalkerApiService.getLiveCategories(any(), any())).thenReturn(
            Result.success(listOf(StalkerCategoryRecord(id = "10", name = "News")))
        )
        stalkerApiService.stubStreamLiveStreams(
            listOf(
                StalkerItemRecord(
                    id = "100",
                    name = "News",
                    categoryId = "10",
                    cmd = "ffmpeg http://example.com/live.ts"
                )
            )
        )
        org.mockito.kotlin.whenever(stalkerApiService.getVodCategories(any(), any())).thenReturn(Result.success(emptyList()))
        org.mockito.kotlin.whenever(stalkerApiService.getSeriesCategories(any(), any())).thenReturn(Result.success(emptyList()))
        org.mockito.kotlin.whenever(channelDao.getGuideSyncEntriesByProvider(1L)).thenReturn(
            listOf(
                ChannelGuideSyncEntity(
                    streamId = 100L,
                    name = "News",
                    epgChannelId = "100"
                )
            )
        )
        stalkerApiService.stubStreamEpg(
            channelId = "100",
            programs = listOf(
                StalkerProgramRecord(
                    id = "p1",
                    channelId = "100",
                    title = "Morning News",
                    description = "Top stories",
                    startTimeMillis = 1_700_000_000_000L,
                    endTimeMillis = 1_700_000_360_000L
                )
            )
        )
        org.mockito.kotlin.whenever(programDao.countByProvider(1L)).thenReturn(1)

        val result = manager.sync(providerId = 1L, force = true)

        assertThat(result).isInstanceOf(Result.Success::class.java)
        verify(stalkerApiService).streamLiveStreams(any(), any(), any())
        verify(stalkerApiService, org.mockito.kotlin.times(0)).getLiveStreams(any(), any(), eq("10"))
        verify(stalkerApiService).streamEpg(any(), any(), eq("100"), any(), any())
        verify(programDao, atLeastOnce()).insertAll(any())
    }

    @Test
    fun sync_stalker_upfront_epg_imports_native_portal_guide_in_batches() = runTest {
        val providerEntity = sampleProvider(ProviderType.STALKER_PORTAL).copy(
            serverUrl = "http://example.com",
            username = "",
            password = "",
            stalkerMacAddress = "00:11:22:33:44:55",
            epgSyncMode = ProviderEpgSyncMode.UPFRONT,
            epgUrl = ""
        )
        val manager = buildManager(providerType = ProviderType.STALKER_PORTAL, providerEntity = providerEntity)

        org.mockito.kotlin.whenever(stalkerApiService.authenticate(any())).thenReturn(
            Result.success(
                StalkerSession(
                    loadUrl = "http://example.com/stalker_portal/server/load.php",
                    portalReferer = "http://example.com/stalker_portal/c/",
                    token = "token"
                ) to StalkerProviderProfile(accountName = "Stalker")
            )
        )
        org.mockito.kotlin.whenever(stalkerApiService.getLiveCategories(any(), any())).thenReturn(
            Result.success(listOf(StalkerCategoryRecord(id = "10", name = "News")))
        )
        stalkerApiService.stubStreamLiveStreams(
            listOf(
                StalkerItemRecord(
                    id = "100",
                    name = "News",
                    categoryId = "10",
                    cmd = "ffmpeg http://example.com/live.ts"
                )
            )
        )
        org.mockito.kotlin.whenever(stalkerApiService.getVodCategories(any(), any())).thenReturn(Result.success(emptyList()))
        org.mockito.kotlin.whenever(stalkerApiService.getSeriesCategories(any(), any())).thenReturn(Result.success(emptyList()))
        org.mockito.kotlin.whenever(channelDao.getGuideSyncEntriesByProvider(1L)).thenReturn(
            listOf(
                ChannelGuideSyncEntity(
                    streamId = 100L,
                    name = "News",
                    epgChannelId = "100"
                )
            )
        )
        stalkerApiService.stubStreamEpg(
            channelId = "100",
            programs = (1..505).map { index ->
                StalkerProgramRecord(
                    id = "p$index",
                    channelId = "100",
                    title = "Program $index",
                    description = "Desc $index",
                    startTimeMillis = 1_700_000_000_000L + index * 60_000L,
                    endTimeMillis = 1_700_000_030_000L + index * 60_000L
                )
            }
        )
        org.mockito.kotlin.whenever(programDao.countByProvider(1L)).thenReturn(505)

        val result = manager.sync(providerId = 1L, force = true)

        assertThat(result).isInstanceOf(Result.Success::class.java)
        verify(programDao, atLeastOnce()).insertAll(any())
    }

    @Test
    fun retrySection_epg_stalker_without_xmltv_imports_native_portal_guide() = runTest {
        val providerEntity = sampleProvider(ProviderType.STALKER_PORTAL).copy(
            serverUrl = "http://example.com",
            username = "",
            password = "",
            stalkerMacAddress = "00:11:22:33:44:55",
            epgSyncMode = ProviderEpgSyncMode.BACKGROUND,
            epgUrl = ""
        )
        val manager = buildManager(providerType = ProviderType.STALKER_PORTAL, providerEntity = providerEntity)

        org.mockito.kotlin.whenever(stalkerApiService.authenticate(any())).thenReturn(
            Result.success(
                StalkerSession(
                    loadUrl = "http://example.com/stalker_portal/server/load.php",
                    portalReferer = "http://example.com/stalker_portal/c/",
                    token = "token"
                ) to StalkerProviderProfile(accountName = "Stalker")
            )
        )
        org.mockito.kotlin.whenever(channelDao.getGuideSyncEntriesByProvider(1L)).thenReturn(
            listOf(
                ChannelGuideSyncEntity(
                    streamId = 100L,
                    name = "News",
                    epgChannelId = "100"
                )
            )
        )
        stalkerApiService.stubStreamEpg(
            channelId = "100",
            programs = listOf(
                StalkerProgramRecord(
                    id = "p1",
                    channelId = "100",
                    title = "Morning News",
                    description = "Top stories",
                    startTimeMillis = 1_700_000_000_000L,
                    endTimeMillis = 1_700_000_360_000L
                )
            )
        )
        org.mockito.kotlin.whenever(programDao.countByProvider(1L)).thenReturn(1)

        val result = manager.retrySection(providerId = 1L, section = SyncRepairSection.EPG)

        assertThat(result).isInstanceOf(Result.Success::class.java)
        verify(stalkerApiService).streamEpg(any(), any(), eq("100"), any(), any())
        verify(programDao).insertAll(any())
        val metadata = syncMetadataRepo.getMetadata(1L)
        assertThat(metadata?.epgCount).isEqualTo(1)
    }

    @Test
    fun retrySection_epg_stalker_dedupes_same_guide_key_within_one_run() = runTest {
        val providerEntity = sampleProvider(ProviderType.STALKER_PORTAL).copy(
            serverUrl = "http://example.com",
            username = "",
            password = "",
            stalkerMacAddress = "00:11:22:33:44:55",
            epgSyncMode = ProviderEpgSyncMode.BACKGROUND,
            epgUrl = ""
        )
        val manager = buildManager(providerType = ProviderType.STALKER_PORTAL, providerEntity = providerEntity)

        org.mockito.kotlin.whenever(stalkerApiService.authenticate(any())).thenReturn(
            Result.success(
                StalkerSession(
                    loadUrl = "http://example.com/stalker_portal/server/load.php",
                    portalReferer = "http://example.com/stalker_portal/c/",
                    token = "token"
                ) to StalkerProviderProfile(accountName = "Stalker")
            )
        )
        org.mockito.kotlin.whenever(channelDao.getGuideSyncEntriesByProvider(1L)).thenReturn(
            listOf(
                ChannelGuideSyncEntity(streamId = 100L, name = "News", epgChannelId = "shared-guide-id"),
                ChannelGuideSyncEntity(streamId = 101L, name = "News HD", epgChannelId = "shared-guide-id"),
                ChannelGuideSyncEntity(streamId = 102L, name = "Sports", epgChannelId = "sports-guide-id")
            )
        )
        stalkerApiService.stubStreamEpg(
            channelId = "shared-guide-id",
            programs = listOf(
                StalkerProgramRecord(
                    id = "p1",
                    channelId = "shared-guide-id",
                    title = "Morning News",
                    description = "Top stories",
                    startTimeMillis = 1_700_000_000_000L,
                    endTimeMillis = 1_700_000_360_000L
                )
            )
        )
        stalkerApiService.stubStreamEpg(
            channelId = "sports-guide-id",
            programs = listOf(
                StalkerProgramRecord(
                    id = "p2",
                    channelId = "sports-guide-id",
                    title = "Live Sports",
                    description = "Match coverage",
                    startTimeMillis = 1_700_000_000_000L,
                    endTimeMillis = 1_700_000_360_000L
                )
            )
        )
        org.mockito.kotlin.whenever(programDao.countByProvider(1L)).thenReturn(2)

        val result = manager.retrySection(providerId = 1L, section = SyncRepairSection.EPG)

        assertThat(result).isInstanceOf(Result.Success::class.java)
        verify(stalkerApiService, org.mockito.kotlin.times(1)).streamEpg(any(), any(), eq("shared-guide-id"), any(), any())
        verify(stalkerApiService, org.mockito.kotlin.times(1)).streamEpg(any(), any(), eq("sports-guide-id"), any(), any())
    }

    @Test
    fun retrySection_epg_stalker_uses_bulk_first_and_only_falls_back_for_uncovered_keys() = runTest {
        val providerEntity = sampleProvider(ProviderType.STALKER_PORTAL).copy(
            serverUrl = "http://example.com",
            username = "",
            password = "",
            stalkerMacAddress = "00:11:22:33:44:55",
            epgSyncMode = ProviderEpgSyncMode.BACKGROUND,
            epgUrl = ""
        )
        val manager = buildManager(providerType = ProviderType.STALKER_PORTAL, providerEntity = providerEntity)

        org.mockito.kotlin.whenever(stalkerApiService.authenticate(any())).thenReturn(
            Result.success(
                StalkerSession(
                    loadUrl = "http://example.com/stalker_portal/server/load.php",
                    portalReferer = "http://example.com/stalker_portal/c/",
                    token = "token"
                ) to StalkerProviderProfile(accountName = "Stalker")
            )
        )
        org.mockito.kotlin.whenever(channelDao.getGuideSyncEntriesByProvider(1L)).thenReturn(
            listOf(
                ChannelGuideSyncEntity(streamId = 100L, name = "News", epgChannelId = "shared-guide-id"),
                ChannelGuideSyncEntity(streamId = 102L, name = "Sports", epgChannelId = "sports-guide-id")
            )
        )
        stalkerApiService.stubStreamBulkEpg(
            programs = listOf(
                StalkerProgramRecord(
                    id = "p1",
                    channelId = "shared-guide-id",
                    title = "Morning News",
                    description = "Top stories",
                    startTimeMillis = 1_700_000_000_000L,
                    endTimeMillis = 1_700_000_360_000L
                )
            )
        )
        stalkerApiService.stubStreamEpg(
            channelId = "sports-guide-id",
            programs = listOf(
                StalkerProgramRecord(
                    id = "p2",
                    channelId = "sports-guide-id",
                    title = "Live Sports",
                    description = "Match coverage",
                    startTimeMillis = 1_700_000_000_000L,
                    endTimeMillis = 1_700_000_360_000L
                )
            )
        )
        org.mockito.kotlin.whenever(programDao.countByProvider(1L)).thenReturn(2)

        val result = manager.retrySection(providerId = 1L, section = SyncRepairSection.EPG)

        assertThat(result).isInstanceOf(Result.Success::class.java)
        verify(stalkerApiService, org.mockito.kotlin.times(1)).streamBulkEpg(any(), any(), eq(6), any())
        verify(stalkerApiService, org.mockito.kotlin.times(0)).streamEpg(any(), any(), eq("shared-guide-id"), any(), any())
        verify(stalkerApiService, org.mockito.kotlin.times(1)).streamEpg(any(), any(), eq("sports-guide-id"), any(), any())
    }

    @Test
    fun retrySection_epg_stalker_falls_back_to_stream_id_for_placeholder_epg_key() = runTest {
        val providerEntity = sampleProvider(ProviderType.STALKER_PORTAL).copy(
            serverUrl = "http://example.com",
            username = "",
            password = "",
            stalkerMacAddress = "00:11:22:33:44:55",
            epgSyncMode = ProviderEpgSyncMode.BACKGROUND,
            epgUrl = ""
        )
        val manager = buildManager(providerType = ProviderType.STALKER_PORTAL, providerEntity = providerEntity)

        org.mockito.kotlin.whenever(stalkerApiService.authenticate(any())).thenReturn(
            Result.success(
                StalkerSession(
                    loadUrl = "http://example.com/stalker_portal/server/load.php",
                    portalReferer = "http://example.com/stalker_portal/c/",
                    token = "token"
                ) to StalkerProviderProfile(accountName = "Stalker")
            )
        )
        org.mockito.kotlin.whenever(channelDao.getGuideSyncEntriesByProvider(1L)).thenReturn(
            listOf(
                ChannelGuideSyncEntity(
                    streamId = 100L,
                    name = "News",
                    epgChannelId = "No details available"
                )
            )
        )
        stalkerApiService.stubStreamEpg(
            channelId = "100",
            programs = listOf(
                StalkerProgramRecord(
                    id = "p1",
                    channelId = "100",
                    title = "Morning News",
                    description = "Top stories",
                    startTimeMillis = 1_700_000_000_000L,
                    endTimeMillis = 1_700_000_360_000L
                )
            )
        )
        org.mockito.kotlin.whenever(programDao.countByProvider(1L)).thenReturn(1)

        val result = manager.retrySection(providerId = 1L, section = SyncRepairSection.EPG)

        assertThat(result).isInstanceOf(Result.Success::class.java)
        verify(stalkerApiService, org.mockito.kotlin.times(1)).streamEpg(any(), any(), eq("100"), any(), any())
        verify(stalkerApiService, org.mockito.kotlin.times(0)).streamEpg(any(), any(), eq("No details available"), any(), any())
    }

    @Test
    fun `sync_xtream_movie_failure_does_not_block_series_sync`() = runTest {
        val mgr = buildManager(providerType = ProviderType.XTREAM_CODES)
        val now = System.currentTimeMillis()
        stubXtreamLiveCatalog()
        syncMetadataRepo.updateMetadata(
            SyncMetadata(
                providerId = 1L,
                lastLiveSync = now,
                lastLiveSuccess = now,
                lastEpgSync = now,
                lastEpgSuccess = now
            )
        )
        org.mockito.kotlin.whenever(movieDao.getCount(1L)).thenReturn(flowOf(0))
        xtreamBackend.respond(action = "get_vod_categories", body = """{"error":"categories unavailable"}""", code = 500)
        xtreamBackend.respond(action = "get_series_categories", body = """[{"category_id":"9","category_name":"Drama"}]""")

        val result = mgr.sync(1L, force = false)
        advanceUntilIdle()

        assertThat(result.isSuccess).isTrue()
        assertThat(xtreamBackend.requestedActions).contains("get_series_categories")
        assertThat(xtreamBackend.requestedActions).doesNotContain("get_series")
    }

    @Test
    fun `sync_m3u_fileImport_batchesAndDiscoversEpg`() = runTest {
        val playlist = tempFolder.newFile("playlist.m3u")
        playlist.writeText(buildString {
            append("#EXTM3U x-tvg-url=\"https://epg.example.com/guide.xml\"\n")
            repeat(2501) { index ->
                append("#EXTINF:-1 group-title=\"News\",Channel ${index + 1}\n")
                append("https://live.example.com/ch${index + 1}.ts\n")
            }
            append("#EXTINF:-1 group-title=\"Movies\",Movie One\n")
            append("https://vod.example.com/movie1.mp4\n")
        })
        val url = playlist.toURI().toString()
        val provider = sampleProvider(ProviderType.M3U).copy(
            serverUrl = url,
            m3uUrl = url,
            epgUrl = "",
            m3uVodClassificationEnabled = true
        )
        val mgr = buildManager(providerType = ProviderType.M3U, providerEntity = provider)

        val result = mgr.sync(1L, force = true)
        advanceUntilIdle()

        if (result is Result.Error) {
            error("${result.message}: ${result.exception?.stackTraceToString()}")
        }
        assertThat(result.isSuccess).isTrue()
        verify(catalogSyncDao, atLeast(3)).insertChannelStages(any())
        verify(catalogSyncDao, atLeastOnce()).insertMovieStages(any())
    }

    @Test
    fun `sync_m3u_gzipFileImport_succeeds`() = runTest {
        val gzFile = tempFolder.newFile("playlist.m3u.gz")
        GZIPOutputStream(gzFile.outputStream()).bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write("#EXTM3U\n")
            writer.write("#EXTINF:-1 group-title=\"Live\",Compressed Channel\n")
            writer.write("https://live.example.com/compressed.ts\n")
        }
        val url = gzFile.toURI().toString()
        val provider = sampleProvider(ProviderType.M3U).copy(serverUrl = url, m3uUrl = url)
        val mgr = buildManager(providerType = ProviderType.M3U, providerEntity = provider)

        val result = mgr.sync(1L, force = true)
        advanceUntilIdle()

        if (result is Result.Error) {
            error(result.message)
        }
        assertThat(result.isSuccess).isTrue()
        verify(catalogSyncDao, atLeastOnce()).insertChannelStages(any())
    }

    @Test
    fun `sync_m3u_accepts_http_header_epg_and_ignores_insecure_streams`() = runTest {
        // HTTP EPG URLs in the playlist header are now accepted (same policy as playlist sources).
        // Only stream entry URLs with unsupported schemes (e.g. ftp://) are silently dropped.
        val playlist = tempFolder.newFile("mixed-playlist.m3u")
        playlist.writeText(
            """
            #EXTM3U x-tvg-url="http://epg.example.com/guide.xml"
            #EXTINF:-1 group-title="News",Secure Channel
            https://live.example.com/secure.ts
            #EXTINF:-1 group-title="News",Insecure Channel
            ftp://live.example.com/insecure.ts
            """.trimIndent()
        )

        val provider = sampleProvider(ProviderType.M3U).copy(serverUrl = playlist.toURI().toString(), m3uUrl = playlist.toURI().toString())
        val mgr = buildManager(providerType = ProviderType.M3U, providerEntity = provider)

        val result = mgr.sync(1L, force = true)
        advanceUntilIdle()

        if (result is Result.Error) {
            error("${result.message}: ${result.exception?.stackTraceToString()}")
        }
        assertThat(result.isSuccess).isTrue()
        val state = mgr.currentSyncState(1L)
        assertThat(state).isInstanceOf(SyncState.Success::class.java)
        val insertedChannels = argumentCaptor<List<com.streamvault.data.local.entity.ChannelImportStageEntity>>()
        verify(catalogSyncDao, atLeastOnce()).insertChannelStages(insertedChannels.capture())
        assertThat(insertedChannels.allValues.flatten()).hasSize(1)
    }

    @Test
    fun `sync_m3u_ignores_unsupported_scheme_header_epg`() = runTest {
        val playlist = tempFolder.newFile("ftp-epg-playlist.m3u")
        playlist.writeText(
            """
            #EXTM3U x-tvg-url="ftp://epg.example.com/guide.xml"
            #EXTINF:-1 group-title="News",Channel
            https://live.example.com/channel.ts
            """.trimIndent()
        )

        val provider = sampleProvider(ProviderType.M3U).copy(serverUrl = playlist.toURI().toString(), m3uUrl = playlist.toURI().toString())
        val mgr = buildManager(providerType = ProviderType.M3U, providerEntity = provider)

        val result = mgr.sync(1L, force = true)
        advanceUntilIdle()

        assertThat(result.isSuccess).isTrue()
        assertThat((mgr.currentSyncState(1L) as SyncState.Partial).warnings)
            .contains("Ignored unsupported EPG URL from playlist header.")
    }

    // ── M3U sync failure ────────────────────────────────────────────

    @Test
    fun `sync_m3u_isolates_each_epg_source_failure_and_preserves_source_priority`() = runTest {
        val firstUrl = "https://epg.example.com/first.xml"
        val secondUrl = "https://epg.example.com/second.xml"
        val thirdUrl = "https://epg.example.com/third.xml"
        val playlist = tempFolder.newFile("multi-epg-playlist.m3u")
        playlist.writeText(
            """
                #EXTM3U x-tvg-url="$firstUrl, $secondUrl, $thirdUrl"
                #EXTINF:-1 tvg-id="news",News
                https://live.example.com/news.ts
            """.trimIndent()
        )
        org.mockito.kotlin.whenever(epgSourceRepo.addSource(any(), any(), any(), anyOrNull())).thenAnswer { invocation ->
            when (val url = invocation.getArgument<String>(1)) {
                firstUrl -> throw IOException("first source unavailable")
                secondUrl -> Result.success(EpgSource(id = 2L, name = "Second", url = url))
                else -> Result.success(EpgSource(id = 3L, name = "Third", url = url))
            }
        }
        org.mockito.kotlin.whenever(epgSourceRepo.assignSourceToProvider(any(), any(), any())).thenAnswer { invocation ->
            if (invocation.getArgument<Long>(1) == 2L) throw IOException("second assignment unavailable")
            Result.success(Unit)
        }
        val provider = sampleProvider(ProviderType.M3U).copy(
            serverUrl = playlist.toURI().toString(),
            m3uUrl = playlist.toURI().toString(),
            epgUrl = ""
        )
        val manager = buildManager(providerType = ProviderType.M3U, providerEntity = provider)

        val result = manager.sync(1L, force = true)
        advanceUntilIdle()

        assertThat(result.isSuccess).isTrue()
        assertThat(manager.currentSyncState(1L)).isInstanceOf(SyncState.Partial::class.java)
        verify(epgSourceRepo).addSource(eq("Playlist EPG 1"), eq(firstUrl), any(), anyOrNull())
        verify(epgSourceRepo).addSource(eq("Playlist EPG 2"), eq(secondUrl), any(), anyOrNull())
        verify(epgSourceRepo).addSource(eq("Playlist EPG 3"), eq(thirdUrl), any(), anyOrNull())
        verify(epgSourceRepo).assignSourceToProvider(1L, 2L, 1)
        verify(epgSourceRepo).assignSourceToProvider(1L, 3L, 2)
    }

    @Test
    fun `jellyfin cancellation is propagated instead of reported as partial success`() = runTest {
        org.mockito.kotlin.whenever(jellyfinProvider.fetchMoviesPage(any(), eq(0)))
            .thenThrow(CancellationException("cancelled"))

        val error = runCatching {
            buildManager(providerType = ProviderType.JELLYFIN).sync(providerId = 1L)
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(CancellationException::class.java)
    }

    @Test
    fun `sync_m3u_networkError_transitionsToError`() = runTest {
        val mgr = buildManager(providerType = ProviderType.M3U)

        // OkHttpClient mock: newCall() returns null → NullPointerException → Error
        mgr.sync(1L)
        advanceUntilIdle()

        assertThat(mgr.currentSyncState(1L)).isInstanceOf(SyncState.Error::class.java)
    }

    @Test
    fun `onProviderDeleted resets state and notifies epg repository`() = runTest {
        val mgr = buildManager()
        mgr.sync(1L)
        advanceUntilIdle()

        assertThat(mgr.currentSyncState(1L)).isNotEqualTo(SyncState.Idle)

        mgr.onProviderDeleted(1L)

        assertThat(mgr.currentSyncState(1L)).isEqualTo(SyncState.Idle)
        verify(epgRepo).onProviderDeleted(1L)
    }

    @Test
    fun `runWhenNoSyncActive returns false while a sync is in flight`() = runTest {
        val provider = sampleProvider(ProviderType.M3U).copy(epgUrl = "https://epg.example.com/guide.xml")
        val mgr = buildManager(providerType = ProviderType.M3U, providerEntity = provider)
        val syncEntered = CompletableDeferred<Unit>()
        val releaseSync = CompletableDeferred<Unit>()
        org.mockito.kotlin.whenever(epgRepo.refreshEpg(1L, "https://epg.example.com/guide.xml")).thenAnswer {
            syncEntered.complete(Unit)
            runBlocking { releaseSync.await() }
            Result.success(Unit)
        }

        val syncJob = async {
            mgr.retrySection(providerId = 1L, section = SyncRepairSection.EPG)
        }
        syncEntered.await()

        val vacuumAllowed = mgr.runWhenNoSyncActive { true }
        releaseSync.complete(Unit)

        assertThat(vacuumAllowed).isFalse()
        syncJob.await()
    }

    // ── Reset state ─────────────────────────────────────────────────

    @Test
    fun `resetState_afterPartial_returnsToIdle`() = runTest {
        val mgr = buildManager()
        mgr.sync(1L) // fails → Error
        advanceUntilIdle()

        assertThat(mgr.currentSyncState(1L)).isInstanceOf(SyncState.Partial::class.java)

        mgr.resetState()
        advanceUntilIdle()

        assertThat(mgr.currentSyncState(1L)).isEqualTo(SyncState.Idle)
    }

    @Test
    fun `resetState_whenAlreadyIdle_staysIdle`() = runTest {
        val mgr = buildManager()

        mgr.resetState() // should be a no-op

        assertThat(mgr.currentSyncState(1L)).isEqualTo(SyncState.Idle)
    }

    // ── isVodEntry (SyncManager is the canonical source) ───────────

    @Test
    fun `isVodEntry_mp4Extension_returnsTrue`() {
        assertThat(M3uParser.isVodEntry(entry(url = "http://vod.example.com/film.mp4"))).isTrue()
    }

    @Test
    fun `isVodEntry_mkvExtension_returnsTrue`() {
        assertThat(M3uParser.isVodEntry(entry(url = "http://vod.example.com/show.mkv"))).isTrue()
    }

    @Test
    fun `isVodEntry_fileExtensionWithQueryOrFragment_returnsTrue`() {
        assertThat(M3uParser.isVodEntry(entry(url = "https://vod.example.com/film.MP4?token=abc"))).isTrue()
        assertThat(M3uParser.isVodEntry(entry(url = "https://vod.example.com/show.mkv#preview"))).isTrue()
        assertThat(M3uParser.isVodEntry(entry(url = "https://vod.example.com/archive.avi?token=abc#preview"))).isTrue()
    }

    @Test
    fun `isVodEntry_movieGroupTitle_returnsTrue`() {
        assertThat(M3uParser.isVodEntry(entry(url = "http://vod.example.com/item/1", group = "Movies HD"))).isTrue()
    }

    @Test
    fun `isVodEntry_vodGroupTitle_returnsTrue`() {
        assertThat(M3uParser.isVodEntry(entry(url = "http://vod.example.com/item/1", group = "VOD Library"))).isTrue()
    }

    @Test
    fun `isVodEntry_filmGroupTitle_returnsTrue`() {
        assertThat(M3uParser.isVodEntry(entry(url = "http://vod.example.com/item/1", group = "Film Classics"))).isTrue()
    }

    @Test
    fun `isVodEntry_liveTs_returnsFalse`() {
        assertThat(M3uParser.isVodEntry(entry(url = "http://live.example.com/cnn.ts", group = "News"))).isFalse()
    }

    @Test
    fun `isVodEntry_liveM3u8_returnsFalse`() {
        assertThat(M3uParser.isVodEntry(entry(url = "http://live.example.com/sports.m3u8", group = "Sports"))).isFalse()
    }

    // ── SyncState sealed class properties ───────────────────────────

    @Test
    fun `syncState_properties_idle`() {
        assertThat(SyncState.Idle.isSyncing).isFalse()
        assertThat(SyncState.Idle.isError).isFalse()
        assertThat(SyncState.Idle.isSuccess).isFalse()
    }

    @Test
    fun `syncState_properties_syncing`() {
        val state = SyncState.Syncing("Downloading…")
        assertThat(state.isSyncing).isTrue()
        assertThat(state.isError).isFalse()
        assertThat(state.isSuccess).isFalse()
    }

    @Test
    fun `syncState_properties_success`() {
        val state = SyncState.Success(timestamp = 12345L)
        assertThat(state.isSuccess).isTrue()
        assertThat(state.isSyncing).isFalse()
        assertThat(state.isError).isFalse()
        assertThat(state.timestamp).isEqualTo(12345L)
    }

    @Test
    fun `syncState_properties_error`() {
        val state = SyncState.Error("something went wrong", cause = RuntimeException("root"))
        assertThat(state.isError).isTrue()
        assertThat(state.isSyncing).isFalse()
        assertThat(state.isSuccess).isFalse()
        assertThat(state.message).isEqualTo("something went wrong")
        assertThat(state.cause).isInstanceOf(RuntimeException::class.java)
    }

    // ── Helper ──────────────────────────────────────────────────────

    private fun entry(
        url: String = "http://stream.example.com/ch1.ts",
        group: String = "Live"
    ) = M3uParser.M3uEntry(
        name = "Test", groupTitle = group,
        tvgId = null, tvgName = null, tvgLogo = null, tvgChno = null,
        tvgLanguage = null, tvgCountry = null,
        catchUp = null, catchUpDays = null, catchUpSource = null, timeshift = null, url = url
    )
}


/**
 * Stubs the streaming bulk-EPG path used by [SyncManager.syncStalkerPortalEpg].
 * The test feeds [programs] through the captured callback and reports the count
 * back as the [Result.success] payload, mirroring the production [streamBulkEpg]
 * contract. Use this helper instead of mocking the legacy buffered [getBulkEpg].
 */
private suspend fun com.streamvault.data.remote.stalker.StalkerApiService.stubStreamBulkEpg(
    programs: List<com.streamvault.data.remote.stalker.StalkerProgramRecord>,
    periodHours: Int = 6
) {
    org.mockito.kotlin.whenever(
        streamBulkEpg(
            org.mockito.kotlin.any(),
            org.mockito.kotlin.any(),
            org.mockito.kotlin.eq(periodHours),
            org.mockito.kotlin.any()
        )
    ).doSuspendableAnswer { invocation ->
        @Suppress("UNCHECKED_CAST")
        val cb = invocation.arguments[3] as suspend (com.streamvault.data.remote.stalker.StalkerProgramRecord) -> Unit
        programs.forEach { cb(it) }
        com.streamvault.domain.model.Result.success(programs.size)
    }
}

/** Stubs the streaming live-channel path. Emits [items] through the captured callback. */
private suspend fun com.streamvault.data.remote.stalker.StalkerApiService.stubStreamLiveStreams(
    items: List<com.streamvault.data.remote.stalker.StalkerItemRecord>
) {
    org.mockito.kotlin.whenever(
        streamLiveStreams(
            org.mockito.kotlin.any(),
            org.mockito.kotlin.any(),
            org.mockito.kotlin.any()
        )
    ).doSuspendableAnswer { invocation ->
        @Suppress("UNCHECKED_CAST")
        val cb = invocation.arguments[2] as suspend (com.streamvault.data.remote.stalker.StalkerItemRecord) -> Unit
        items.forEach { cb(it) }
        com.streamvault.domain.model.Result.success(items.size)
    }
}

/** Stubs the streaming live-channel path to return an error result without emitting items. */
private suspend fun com.streamvault.data.remote.stalker.StalkerApiService.stubStreamLiveStreamsError(message: String) {
    org.mockito.kotlin.whenever(
        streamLiveStreams(
            org.mockito.kotlin.any(),
            org.mockito.kotlin.any(),
            org.mockito.kotlin.any()
        )
    ).doSuspendableAnswer { com.streamvault.domain.model.Result.error(message) }
}

/** Counterpart to [stubStreamBulkEpg] for the per-channel streamed EPG path. */
private suspend fun com.streamvault.data.remote.stalker.StalkerApiService.stubStreamEpg(
    channelId: String,
    programs: List<com.streamvault.data.remote.stalker.StalkerProgramRecord>,
    periodHours: Int = 6
) {
    org.mockito.kotlin.whenever(
        streamEpg(
            org.mockito.kotlin.any(),
            org.mockito.kotlin.any(),
            org.mockito.kotlin.eq(channelId),
            org.mockito.kotlin.eq(periodHours),
            org.mockito.kotlin.any()
        )
    ).doSuspendableAnswer { invocation ->
        @Suppress("UNCHECKED_CAST")
        val cb = invocation.arguments[4] as suspend (com.streamvault.data.remote.stalker.StalkerProgramRecord) -> Unit
        programs.forEach { cb(it) }
        com.streamvault.domain.model.Result.success(programs.size)
    }
}
