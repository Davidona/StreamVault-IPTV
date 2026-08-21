package com.streamvault.data.sync

import android.content.Context
import android.util.Log
import com.streamvault.data.local.DatabaseTransactionRunner
import com.streamvault.data.local.dao.CatalogSyncDao
import com.streamvault.data.local.dao.CategoryDao
import com.streamvault.data.local.dao.ChannelDao
import com.streamvault.data.local.dao.EpisodeDao
import com.streamvault.data.local.dao.MovieCategoryHydrationDao
import com.streamvault.data.local.dao.MovieDao
import com.streamvault.data.local.dao.M3uClassificationDao
import com.streamvault.data.local.dao.ProgramDao
import com.streamvault.data.local.dao.ProviderDao
import com.streamvault.data.local.dao.ProviderWorkflowDao
import com.streamvault.data.local.dao.SeriesCategoryHydrationDao
import com.streamvault.data.local.dao.SeriesDao
import com.streamvault.data.local.dao.TmdbIdentityDao
import com.streamvault.data.local.dao.VodCategoryHydrationDao
import com.streamvault.data.local.dao.VodCatalogEntryDao
import com.streamvault.data.local.dao.XtreamContentIndexDao
import com.streamvault.data.local.dao.XtreamIndexJobDao
import com.streamvault.data.local.dao.XtreamLiveOnboardingDao
import com.streamvault.data.local.entity.CategoryEntity
import com.streamvault.data.local.entity.ChannelEntity
import com.streamvault.data.local.entity.MovieEntity
import com.streamvault.data.local.entity.SeriesEntity
import com.streamvault.data.local.entity.VodCatalogEntryEntity
import com.streamvault.data.local.entity.VodCategoryHydrationEntity
import com.streamvault.data.local.entity.XtreamContentIndexEntity
import com.streamvault.data.local.entity.XtreamLiveOnboardingStateEntity
import com.streamvault.data.mapper.toDomain
import com.streamvault.data.manager.PendingBackupRestoreCoordinator
import com.streamvault.data.mapper.toEntity
import com.streamvault.data.parser.M3uParser
import com.streamvault.data.remote.http.buildAppRequestProfile
import com.streamvault.data.remote.http.toGenericRequestProfile
import com.streamvault.data.remote.stalker.StalkerApiService
import com.streamvault.data.remote.stalker.StalkerApiError
import com.streamvault.data.remote.stalker.StalkerPlaybackMode
import com.streamvault.data.remote.stalker.StalkerProvider
import com.streamvault.data.remote.stalker.StalkerVodCatalogItem
import com.streamvault.data.remote.stalker.StalkerRemoteIdentityResolver
import com.streamvault.data.remote.stalker.StalkerRequestCoordinator
import com.streamvault.data.remote.stalker.StalkerRequestDescriptor
import com.streamvault.data.remote.stalker.StalkerResponseMetrics
import com.streamvault.data.remote.stalker.StalkerPortalStateStore
import com.streamvault.data.remote.stalker.StalkerProviderProfile
import com.streamvault.data.remote.jellyfin.JellyfinProvider
import com.streamvault.data.remote.jellyfin.JellyfinCatalogLimitException
import com.streamvault.data.remote.jellyfin.JellyfinItemLimitException
import com.streamvault.data.remote.jellyfin.JellyfinPaginationException
import com.streamvault.data.remote.jellyfin.JellyfinResponseTooLargeException
import com.streamvault.data.remote.stalker.StalkerTrafficCoordinator
import com.streamvault.data.remote.stalker.StalkerTelemetry
import com.streamvault.data.remote.dto.XtreamCategory
import com.streamvault.data.remote.dto.XtreamSeriesItem
import com.streamvault.data.remote.dto.XtreamStream
import com.streamvault.data.remote.xtream.OkHttpXtreamApiService
import com.streamvault.data.remote.xtream.XtreamAuthenticationException
import com.streamvault.data.remote.xtream.XtreamNetworkException
import com.streamvault.data.remote.xtream.XtreamParsingException
import com.streamvault.data.remote.xtream.XtreamRequestException
import com.streamvault.data.remote.xtream.XtreamResponseTooLargeException
import com.streamvault.data.remote.xtream.XtreamApiService
import com.streamvault.data.remote.xtream.XtreamProvider
import com.streamvault.data.remote.xtream.XtreamUrlFactory
import com.streamvault.data.security.CredentialCrypto
import com.streamvault.data.provider.TypedProviderClientFactory
import com.streamvault.data.provider.XtreamClientOptions
import com.streamvault.data.provider.StalkerPlaybackCapabilityCache
import com.streamvault.data.util.AdultContentClassifier
import com.streamvault.data.util.runSuspendCatching
import com.streamvault.data.util.UrlSecurityPolicy
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.CatalogLayout
import com.streamvault.domain.model.GuideSourcePolicy
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.Movie
import com.streamvault.domain.model.ProviderEpgSyncMode
import com.streamvault.domain.model.LegacyProvider as Provider
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.Series
import com.streamvault.domain.model.VodCatalogItem
import com.streamvault.domain.model.StalkerRequestPriority
import com.streamvault.domain.model.StalkerTransportGrant
import com.streamvault.domain.model.StalkerTransportMode
import com.streamvault.domain.model.StalkerTransportOrigin
import com.streamvault.domain.model.SyncMetadata
import com.streamvault.domain.model.SyncState
import com.streamvault.domain.model.VodSyncMode
import com.streamvault.domain.model.XtreamConfig
import com.streamvault.domain.repository.EpgRepository
import com.streamvault.domain.repository.EpgSourceRepository
import com.streamvault.domain.repository.SyncMetadataRepository
import com.streamvault.domain.repository.M3uClassificationRepository
import com.streamvault.domain.repository.ProviderSnapshotRepository
import com.streamvault.domain.sync.Section
import com.streamvault.domain.sync.SyncProgress
import com.streamvault.domain.provider.CapabilityResolution
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SyncManager"
private const val XTREAM_FALLBACK_STAGE_BATCH_SIZE = 500
private const val STALKER_INDEX_CATEGORY_SLICE_SIZE = 32
private const val LIVE_CATEGORY_SEQUENTIAL_MODE_WARNING =
    "Live category sync downgraded to sequential mode after provider stress signals."
private const val XTREAM_RECOVERY_ABORT_WARNING_SUFFIX =
    "recovery stopped early after repeated provider stress signals; keeping recovered results."
private const val XTREAM_AVOID_FULL_CATALOG_COOLDOWN_MILLIS = 6 * 60 * 60 * 1000L
private const val XTREAM_MOVIE_REQUEST_TIMEOUT_MILLIS = 60_000L
private const val XTREAM_SERIES_REQUEST_TIMEOUT_MILLIS = 60_000L
private const val XTREAM_SQLITE_LOOKUP_CHUNK_SIZE = 900

internal suspend fun <K, V> chunkedLookupById(
    ids: List<K>,
    chunkSize: Int,
    fetch: suspend (List<K>) -> List<V>,
    keySelector: (V) -> K
): Map<K, V> {
    if (ids.isEmpty()) return emptyMap()
    return ids
        .distinct()
        .chunked(chunkSize)
        .flatMap { chunk -> fetch(chunk) }
        .associateBy(keySelector)
}

/** Ensures a pending provider edit is promoted by only the first durable catalog commit. */
private class CatalogCommitGate(
    private val afterCatalogApply: suspend () -> Unit
) {
    private var applied = false

    val hasApplied: Boolean
        get() = applied

    suspend fun apply() {
        if (applied) return
        afterCatalogApply()
        applied = true
    }
}

enum class SyncRepairSection {
    LIVE,
    EPG,
    MOVIES,
    SERIES
}

@Singleton
class SyncManager @Inject constructor(
    @param:ApplicationContext private val applicationContext: Context,
    private val providerDao: ProviderDao,
    private val channelDao: ChannelDao,
    private val movieDao: MovieDao,
    private val seriesDao: SeriesDao,
    private val programDao: ProgramDao,
    private val categoryDao: CategoryDao,
    private val movieCategoryHydrationDao: MovieCategoryHydrationDao,
    private val seriesCategoryHydrationDao: SeriesCategoryHydrationDao,
    private val vodCategoryHydrationDao: VodCategoryHydrationDao,
    private val vodCatalogEntryDao: VodCatalogEntryDao,
    private val catalogSyncDao: CatalogSyncDao,
    private val tmdbIdentityDao: TmdbIdentityDao,
    private val xtreamContentIndexDao: XtreamContentIndexDao,
    private val xtreamIndexJobDao: XtreamIndexJobDao,
    private val stalkerIndexJobStore: StalkerIndexJobStore,
    private val xtreamLiveOnboardingDao: XtreamLiveOnboardingDao,
    private val episodeDao: EpisodeDao,
    jellyfinProvider: JellyfinProvider,
    private val xtreamJson: Json,
    private val m3uParser: M3uParser,
    private val epgRepository: EpgRepository,
    private val epgSourceRepository: EpgSourceRepository,
    private val okHttpClient: OkHttpClient,
    credentialCrypto: CredentialCrypto,
    private val syncMetadataRepository: SyncMetadataRepository,
    private val transactionRunner: DatabaseTransactionRunner,
    private val preferencesRepository: com.streamvault.data.preferences.PreferencesRepository,
    private val syncProgressBus: SyncProgressBus,
    private val stalkerRequestCoordinator: StalkerRequestCoordinator,
    private val stalkerPortalStateStore: StalkerPortalStateStore,
    private val stalkerReadinessTracker: StalkerReadinessTracker,
    private val providerWorkflowCommitFence: ProviderWorkflowCommitFence,
    private val typedProviderClientFactory: TypedProviderClientFactory,
    private val providerSnapshotRepository: ProviderSnapshotRepository? = null,
    providerWorkflowDao: ProviderWorkflowDao? = null,
    private val providerWorkLocks: ProviderWorkLockRegistry,
    private val providerSyncLocks: ProviderSyncLockRegistry,
    private val stalkerPlaybackCapabilityCache: StalkerPlaybackCapabilityCache,
    private val providerSyncWorkScheduler: ProviderSyncWorkScheduler,
    private val m3uClassificationDao: M3uClassificationDao? = null,
    private val m3uClassificationRepository: M3uClassificationRepository? = null,
    private val pendingBackupRestoreCoordinator: PendingBackupRestoreCoordinator? = null
) : ProviderSyncCommands, CatalogHydrationCommands, ProviderSyncStateSource, ProviderSyncLifecycle {
    private val syncProviderSnapshotAdapter = SyncProviderSnapshotAdapter(providerSnapshotRepository)
    private val syncStatusPublicationCoordinator = SyncStatusPublicationCoordinator(
        syncMetadataRepository = syncMetadataRepository,
        syncProgressBus = syncProgressBus
    )
    private val syncErrorSanitizer = SyncErrorSanitizer()
    private val xtreamAdaptiveSyncPolicy = XtreamAdaptiveSyncPolicy()
    private val syncCatalogStore = SyncCatalogStore(
        channelDao = channelDao,
        movieDao = movieDao,
        seriesDao = seriesDao,
        categoryDao = categoryDao,
        catalogSyncDao = catalogSyncDao,
        tmdbIdentityDao = tmdbIdentityDao,
        transactionRunner = transactionRunner,
        workflowCommitFence = providerWorkflowCommitFence
    )
    private val catalogIndexJobStore = CatalogIndexJobStore(
        providerDao = providerDao,
        xtreamIndexJobDao = xtreamIndexJobDao,
        stalkerIndexJobStore = stalkerIndexJobStore
    )
    private val stalkerIndexCheckpointStore = StalkerIndexCheckpointStore(
        movieHydrationDao = movieCategoryHydrationDao,
        seriesHydrationDao = seriesCategoryHydrationDao
    )
    private val stalkerIndexContinuationCoordinator by lazy {
        StalkerIndexContinuationCoordinator(
            stalkerIndexJobStore = stalkerIndexJobStore,
            loadCategories = { providerId, contentType ->
                categoryDao.getByProviderAndTypeSync(providerId, contentType.name)
            },
            loadHydration = stalkerIndexCheckpointStore::getHydrationSnapshot,
            visibleCategories = { providerId, contentType, categories, api ->
                visibleStalkerIndexCategories(
                    contentType,
                    allVisibleStalkerIndexCategories(providerId, contentType, categories),
                    api
                )
            },
            loadProvider = ::loadCompatibilityProvider,
            loadStalkerProviders = {
                providerDao.getAllSync()
                    .filter { provider -> provider.type == ProviderType.STALKER_PORTAL }
                    .map { provider -> provider.id }
            },
            deleteLegacyJobs = { providerId -> xtreamIndexJobDao.deleteByProvider(providerId) },
            cancelIndex = ::cancelStalkerIndexSync,
            scheduleIndex = { providerId, initialDelaySeconds, appendSuccessor ->
                providerSyncWorkScheduler.scheduleStalkerIndex(
                    providerId = providerId,
                    force = false,
                    initialDelaySeconds = initialDelaySeconds,
                    appendSuccessor = appendSuccessor
                )
            },
            scheduleBackgroundEpg = providerSyncWorkScheduler::scheduleBackgroundEpg,
            log = { message -> Log.i(TAG, message) }
        )
    }
    private val stalkerIncrementalIndexExecutor by lazy {
        StalkerIncrementalIndexExecutor(
            operations = CallbackStalkerIncrementalIndexOperations(
                StalkerIncrementalIndexCallbacks(
                    runtimeProfile = { CatalogSyncRuntimeProfile.from(applicationContext) },
                    allVisibleCategories = { providerId, contentType ->
                        allVisibleStalkerIndexCategories(
                            providerId = providerId,
                            contentType = contentType,
                            categories = categoryDao.getByProviderAndTypeSync(providerId, contentType.name)
                        )
                    },
                    visibleCategories = { contentType, categories, api ->
                        visibleStalkerIndexCategories(contentType, categories, api)
                    },
                    getJob = { providerId, contentType -> stalkerIndexJobStore.get(providerId, contentType) },
                    getHydration = stalkerIndexCheckpointStore::getHydrationSnapshot,
                    currentIndexedRowCount = ::currentStalkerIndexedRowCount,
                    pruneStaleRows = { providerId, contentType ->
                        xtreamContentIndexDao.pruneStaleLocalContentRows(providerId, contentType.name)
                    },
                    updateSummaryMetadata = ::updateStalkerSummaryMetadata,
                    fetchSummaryPage = { provider, api, contentType, categoryId, page ->
                        withStalkerFetchPermit(provider, contentType, categoryId, page) {
                            fetchStalkerSummaryPageWithRecovery(
                                api = api,
                                contentType = contentType,
                                categoryId = categoryId,
                                page = page,
                                splitVod = provider.catalogLayout == CatalogLayout.SPLIT
                            )
                        }
                    },
                    fetchWildcardPage = { provider, api, contentType, categoryId, page ->
                        withStalkerFetchPermit(provider, contentType, categoryId, page) {
                            fetchStalkerWildcardSummaryPageWithRecovery(api, contentType, categoryId, page)
                        }
                    },
                    markAttemptStarted = stalkerIndexCheckpointStore::markAttemptStarted,
                    markAttemptSucceeded = stalkerIndexCheckpointStore::markAttemptSucceeded,
                    markAttemptFailed = stalkerIndexCheckpointStore::markAttemptFailed,
                    upsertMovieSummaryBatch = { providerId, movies, indexedAt ->
                        upsertXtreamMovieSummaryBatch(providerId, movies, indexedAt, restoreWatchProgress = false)
                    },
                    upsertSeriesSummaryBatch = { providerId, series, indexedAt ->
                        upsertXtreamSeriesSummaryBatch(providerId, series, indexedAt)
                    },
                    upsertVodDerivedSeriesSummaryBatch = { providerId, series, indexedAt ->
                        this@SyncManager.upsertVodDerivedSeriesSummaryBatch(providerId, series, indexedAt)
                    },
                    recordRequestFailure = { providerId, error ->
                        stalkerRequestCoordinator.recordFailure(providerId, error)
                    },
                    failureState = { error ->
                        StalkerIndexRecoveryPolicy.failureState(error, ::sanitizeThrowableMessage)
                    },
                    progress = ::progress,
                    restoreMovieWatchProgress = { providerId -> movieDao.restoreWatchProgress(providerId) },
                    upsertJob = catalogIndexJobStore::upsert,
                    log = { message -> Log.i(TAG, message) }
                )
            )
        )
    }
    private val xtreamIncrementalIndexExecutor by lazy {
        XtreamIncrementalIndexExecutor(
            operations = CallbackXtreamIncrementalIndexOperations(
                XtreamIncrementalIndexCallbacks(
                    getCategories = { providerId, contentType ->
                        categoryDao.getByProviderAndTypeSync(providerId, contentType.name)
                    },
                    ensureCategoryShell = { provider, api, contentType, now, onProgress ->
                        xtreamCatalogSectionExecutor.syncCategoryShell(
                            provider = provider,
                            api = api,
                            contentType = contentType,
                            label = xtreamIndexSectionLabel(contentType),
                            now = now,
                            onProgress = onProgress
                        ).getOrThrow()
                    },
                    getJob = { providerId, contentType ->
                        xtreamIndexJobDao.get(providerId, contentType.name)
                    },
                    shouldRunSummary = catalogIndexJobStore::shouldRunSummary,
                    fetchMovieCategory = { provider, api, category ->
                        fetchMovieCategoryOutcome(provider, api, category.toXtreamCategory())
                    },
                    fetchSeriesCategory = { provider, api, category ->
                        fetchSeriesCategoryOutcome(provider, api, category.toXtreamCategory())
                    },
                    upsertMovieSummaryBatch = { providerId, movies, indexedAt ->
                        upsertXtreamMovieSummaryBatch(
                            providerId = providerId,
                            movies = movies,
                            indexedAt = indexedAt,
                            restoreWatchProgress = false
                        )
                    },
                    upsertSeriesSummaryBatch = ::upsertXtreamSeriesSummaryBatch,
                    streamMovies = { _, api, adultCategoryIds, onBatch ->
                        api.streamVodSummaries(adultCategoryIds = adultCategoryIds, onBatch = onBatch)
                    },
                    streamSeries = { _, api, adultCategoryIds, onBatch ->
                        api.streamSeriesSummaries(adultCategoryIds = adultCategoryIds, onBatch = onBatch)
                    },
                    upsertJob = catalogIndexJobStore::upsert,
                    updateSummaryMetadata = ::updateXtreamSummaryMetadata,
                    scheduleIndex = { providerId, contentType ->
                        scheduleXtreamIndexSync(providerId, contentType, force = false)
                    },
                    progress = ::progress,
                    restoreMovieWatchProgress = { providerId -> movieDao.restoreWatchProgress(providerId) },
                    sanitize = ::sanitizeThrowableMessage,
                    log = { message -> Log.i(TAG, message) }
                )
            )
        )
    }
    private val m3uImporter = SyncManagerM3uImporter(
        m3uParser = m3uParser,
        okHttpClient = okHttpClient,
        syncCatalogStore = syncCatalogStore,
        retryTransient = { block -> retryTransient(block = block) },
        progress = ::progress,
        emitProgress = ::emitProviderProgress,
        classificationDao = m3uClassificationDao,
        classificationRepository = m3uClassificationRepository
    )
    private val m3uCatalogExecutor by lazy {
        M3uCatalogSyncExecutor(
            importer = m3uImporter,
            syncMetadataRepository = syncMetadataRepository,
            epgSourceRepository = epgSourceRepository,
            countPrograms = { providerId -> programDao.countByProvider(providerId) },
            shouldSyncEpgUpfront = ::shouldSyncEpgUpfront,
            updateEpgJobState = ::updateXtreamEpgJobState,
            refreshGuide = providerEpgExecutor::syncM3uProviderEpg,
            markBackgroundEpgQueued = { providerId, now ->
                upsertXtreamIndexJob(
                    providerId = providerId,
                    section = "EPG",
                    state = "QUEUED",
                    now = now,
                    totalCategories = 1,
                    completedCategories = 0,
                    nextCategoryIndex = 0,
                    failedCategories = 0,
                    lastAttemptAt = now,
                    lastError = null
                )
            },
            progress = ::progress,
            sanitizeThrowableMessage = ::sanitizeThrowableMessage
        )
    }
    private val jellyfinCatalogExecutor by lazy {
        JellyfinCatalogSyncExecutor(
            jellyfinProvider = jellyfinProvider,
            credentialCrypto = credentialCrypto,
            syncCatalogStore = syncCatalogStore,
            catalogSyncDao = catalogSyncDao,
            providerWorkflowDao = providerWorkflowDao,
            syncMetadataRepository = syncMetadataRepository,
            movieDao = movieDao,
            seriesDao = seriesDao,
            progress = ::progress
        )
    }
    private val xtreamSupport = SyncManagerXtreamSupport(
        adaptiveSyncPolicy = xtreamAdaptiveSyncPolicy,
        shouldRememberSequentialPreference = ::shouldRememberSequentialPreference,
        sanitizeThrowableMessage = ::sanitizeThrowableMessage,
        progress = ::progress,
        movieRequestTimeoutMillis = XTREAM_MOVIE_REQUEST_TIMEOUT_MILLIS,
        seriesRequestTimeoutMillis = XTREAM_SERIES_REQUEST_TIMEOUT_MILLIS,
        recoveryAbortWarningSuffix = XTREAM_RECOVERY_ABORT_WARNING_SUFFIX
    )
    private val providerEpgExecutor by lazy {
        ProviderEpgSyncExecutor(
            preferencesRepository = preferencesRepository,
            epgRepository = epgRepository,
            epgSourceRepository = epgSourceRepository,
            channelDao = channelDao,
            programDao = programDao,
            transactionRunner = transactionRunner,
            syncMetadataRepository = syncMetadataRepository,
            stalkerPortalStateStore = stalkerPortalStateStore,
            stalkerRequestCoordinator = stalkerRequestCoordinator,
            createStalkerProvider = ::createStalkerSyncProvider,
            xtreamSupport = xtreamSupport,
            progress = ::progress,
            sanitizeThrowableMessage = ::sanitizeThrowableMessage
        )
    }
    private val xtreamCatalogSectionExecutor by lazy {
        XtreamCatalogSectionExecutor(
            preferencesRepository = preferencesRepository,
            syncMetadataRepository = syncMetadataRepository,
            syncCatalogStore = syncCatalogStore,
            createProvider = ::createXtreamSyncProvider,
            markIndexRunning = { providerId, contentType, now ->
                upsertXtreamIndexJob(
                    providerId = providerId,
                    section = contentType.name,
                    state = "RUNNING",
                    now = now,
                    lastAttemptAt = now
                )
            },
            markIndexQueued = { providerId, contentType, now, totalCategories ->
                upsertXtreamIndexJob(
                    providerId = providerId,
                    section = contentType.name,
                    state = "QUEUED",
                    now = now,
                    totalCategories = totalCategories,
                    completedCategories = 0,
                    nextCategoryIndex = 0,
                    failedCategories = 0,
                    indexedRows = 0,
                    skippedMalformedRows = 0,
                    lastAttemptAt = now,
                    lastError = null
                )
            },
            markIndexFailure = { providerId, contentType, now, error ->
                upsertXtreamIndexJob(
                    providerId = providerId,
                    section = contentType.name,
                    state = XtreamIndexRecoveryPolicy.failureState(error),
                    now = now,
                    lastAttemptAt = now,
                    lastError = sanitizeThrowableMessage(error)
                )
            },
            progress = ::progress,
            sanitizeThrowableMessage = ::sanitizeThrowableMessage,
            userMessage = { error, fallback -> syncErrorSanitizer.userMessage(error, fallback) }
        )
    }
    private val stalkerCatalogSectionExecutor by lazy {
        StalkerCatalogSectionExecutor(
            movieCategoryHydrationDao = movieCategoryHydrationDao,
            seriesCategoryHydrationDao = seriesCategoryHydrationDao,
            xtreamContentIndexDao = xtreamContentIndexDao,
            syncCatalogStore = syncCatalogStore,
            movieDao = movieDao,
            seriesDao = seriesDao,
            syncMetadataRepository = syncMetadataRepository,
            createProvider = ::createStalkerSyncProvider,
            updateIndexJob = { providerId, contentType, totalCategories, now ->
                upsertXtreamIndexJob(
                    providerId = providerId,
                    section = contentType.name,
                    state = "QUEUED",
                    now = now,
                    totalCategories = totalCategories,
                    completedCategories = 0,
                    nextCategoryIndex = 0,
                    failedCategories = 0,
                    indexedRows = 0,
                    skippedMalformedRows = 0,
                    deletedPrunedRows = 0,
                    clearPriority = true,
                    lastAttemptAt = now,
                    lastError = null
                )
            },
            emitSectionProgress = { providerId, section, total, itemsIndexed ->
                emitCatalogSyncProgress(
                    providerId,
                    section = section,
                    total = total,
                    itemsIndexed = itemsIndexed
                )
            },
            progress = ::progress
        )
    }
    private val xtreamCatalogExecutor by lazy {
        XtreamCatalogSyncExecutor(
            applicationContext = applicationContext,
            preferencesRepository = preferencesRepository,
            syncMetadataRepository = syncMetadataRepository,
            channelDao = channelDao,
            categoryDao = categoryDao,
            xtreamLiveOnboardingDao = xtreamLiveOnboardingDao,
            syncCatalogStore = syncCatalogStore,
            sectionExecutor = xtreamCatalogSectionExecutor,
            liveStrategy = xtreamLiveStrategy,
            catalogStrategySupport = catalogStrategySupport,
            createProvider = ::createXtreamSyncProvider,
            updateIndexJob = { update ->
                upsertXtreamIndexJob(
                    providerId = update.providerId,
                    section = update.section,
                    state = update.state,
                    now = update.now,
                    totalCategories = update.totalCategories,
                    completedCategories = update.completedCategories,
                    indexedRows = update.indexedRows,
                    lastAttemptAt = update.lastAttemptAt,
                    lastError = update.lastError
                )
            },
                    indexFailureState = XtreamIndexRecoveryPolicy::failureState,
            shouldRememberSequentialPreference = ::shouldRememberSequentialPreference,
            progress = ::progress,
            emitProgress = ::emitProviderProgress,
            sanitizeThrowableMessage = ::sanitizeThrowableMessage,
            userMessage = { error, fallback -> syncErrorSanitizer.userMessage(error, fallback) },
            requiredHiddenCategoryIds = { providerId, type ->
                pendingBackupRestoreCoordinator?.requiredHiddenCategoryIds(providerId, type).orEmpty()
            }
        )
    }
    private val stalkerSyncExecutor by lazy {
        StalkerCatalogSyncExecutor(
            applicationContext = applicationContext,
            preferencesRepository = preferencesRepository,
            syncMetadataRepository = syncMetadataRepository,
            providerSnapshotRepository = providerSnapshotRepository,
            transactionRunner = transactionRunner,
            categoryDao = categoryDao,
            channelDao = channelDao,
            movieCategoryHydrationDao = movieCategoryHydrationDao,
            seriesCategoryHydrationDao = seriesCategoryHydrationDao,
            vodCategoryHydrationDao = vodCategoryHydrationDao,
            vodCatalogEntryDao = vodCatalogEntryDao,
            movieDao = movieDao,
            seriesDao = seriesDao,
            syncCatalogStore = syncCatalogStore,
            catalogStager = catalogStager,
            catalogStrategySupport = catalogStrategySupport,
            categoryRecoverySupport = xtreamSupport,
            sectionExecutor = stalkerCatalogSectionExecutor,
            readinessTracker = stalkerReadinessTracker,
            createProvider = ::createStalkerSyncProvider,
            syncProviderEpg = providerEpgExecutor::syncStalkerProviderEpg,
            progress = ::progress,
            emitCatalogProgress = { providerId, section, total, itemsIndexed ->
                emitCatalogSyncProgress(providerId, section, total ?: 0, itemsIndexed ?: 0)
            },
            categoryFailureWarning = ::categoryFailureWarning,
            sanitizeThrowableMessage = ::sanitizeThrowableMessage,
            requiredHiddenCategoryIds = { providerId, type ->
                pendingBackupRestoreCoordinator?.requiredHiddenCategoryIds(providerId, type).orEmpty()
            }
        )
    }
    private val syncCoordinator: SyncCoordinator by lazy {
        SyncCoordinator(
            CatalogSyncPlanAssembler(
                delegate = SyncManagerPlanDelegate(
                    snapshotAdapter = syncProviderSnapshotAdapter,
                    syncMetadataRepository = syncMetadataRepository,
                    xtreamCatalogExecutor = xtreamCatalogExecutor,
                    xtreamCatalogSectionExecutor = xtreamCatalogSectionExecutor,
                    providerEpgExecutor = providerEpgExecutor,
                    m3uCatalogExecutor = m3uCatalogExecutor,
                    stalkerSyncExecutor = stalkerSyncExecutor,
                    stalkerCatalogSectionExecutor = stalkerCatalogSectionExecutor,
                    jellyfinCatalogExecutor = jellyfinCatalogExecutor
                )
            ).create(),
            continuationScheduler = ProviderContinuationScheduler(providerSyncWorkScheduler)
        )
    }

    private suspend fun loadCompatibilityProvider(providerId: Long): Provider? =
        syncProviderSnapshotAdapter.getLegacyProvider(providerId)
    val syncState: StateFlow<SyncState> = syncStatusPublicationCoordinator.syncState
    val syncStatesByProvider: StateFlow<Map<Long, SyncState>> =
        syncStatusPublicationCoordinator.syncStatesByProvider

    private val vodCategoryHydrationCoordinator by lazy {
        VodCategoryHydrationCoordinator(
            providerSyncLocks = providerSyncLocks,
            categoryDao = categoryDao,
            movieCategoryHydrationDao = movieCategoryHydrationDao,
            seriesCategoryHydrationDao = seriesCategoryHydrationDao,
            vodCategoryHydrationDao = vodCategoryHydrationDao,
            movieDao = movieDao,
            seriesDao = seriesDao,
            vodCatalogEntryDao = vodCatalogEntryDao,
            transactionRunner = transactionRunner,
            loadProvider = ::loadCompatibilityProvider,
            createProvider = ::createStalkerSyncProvider
        )
    }

    override suspend fun hydrateUnifiedVodCategory(
        providerId: Long,
        categoryId: Long,
        request: com.streamvault.domain.model.VodCategoryHydrationRequest
    ): Result<Unit> = vodCategoryHydrationCoordinator
        .hydrateUnifiedVodCategory(providerId, categoryId, request)
        .alsoApplyPendingRestore(providerId)

    override suspend fun hydrateSplitVodCategory(
        providerId: Long,
        movieCategoryId: Long,
        request: com.streamvault.domain.model.VodCategoryHydrationRequest,
        requestedProjection: ContentType
    ): Result<Unit> = vodCategoryHydrationCoordinator
        .hydrateSplitVodCategory(providerId, movieCategoryId, request, requestedProjection)
        .alsoApplyPendingRestore(providerId)

    override suspend fun hydrateSplitVodSeriesCategory(
        providerId: Long,
        seriesCategoryId: Long,
        request: com.streamvault.domain.model.VodCategoryHydrationRequest
    ): Result<Unit> = vodCategoryHydrationCoordinator
        .hydrateSplitVodSeriesCategory(providerId, seriesCategoryId, request)
        .alsoApplyPendingRestore(providerId)

    private suspend fun Result<Unit>.alsoApplyPendingRestore(providerId: Long): Result<Unit> {
        if (this is Result.Success) {
            val startedAt = System.currentTimeMillis()
            pendingBackupRestoreCoordinator?.applyForProvider(providerId)
            Log.i(TAG, "backup restore resolution provider=$providerId took=${System.currentTimeMillis() - startedAt}ms")
        }
        return this
    }

    private val xtreamCatalogHttpService: OkHttpXtreamApiService by lazy {
        OkHttpXtreamApiService(
            client = okHttpClient,
            json = xtreamJson,
            defaultRequestProfile = buildAppRequestProfile(
                versionName = null,
                ownerTag = "sync/xtream"
            )
        )
    }
    private val xtreamCatalogApiService: XtreamApiService by lazy { xtreamCatalogHttpService }
    private val xtreamFetcher: SyncManagerXtreamFetcher by lazy {
        SyncManagerXtreamFetcher(
            xtreamCatalogApiService = xtreamCatalogApiService,
            xtreamCatalogHttpService = xtreamCatalogHttpService,
            xtreamSupport = xtreamSupport,
            sanitizeThrowableMessage = ::sanitizeThrowableMessage
        )
    }
    private val catalogStrategySupport = SyncManagerCatalogStrategySupport(
        shouldRememberSequentialPreference = ::shouldRememberSequentialPreference,
        avoidFullCatalogCooldownMillis = XTREAM_AVOID_FULL_CATALOG_COOLDOWN_MILLIS
    )
    private val catalogStager = SyncManagerCatalogStager(
        syncCatalogStore = syncCatalogStore,
        fallbackStageBatchSize = XTREAM_FALLBACK_STAGE_BATCH_SIZE
    )
    private val xtreamLiveStrategy: SyncManagerXtreamLiveStrategy by lazy {
        SyncManagerXtreamLiveStrategy(
            xtreamCatalogApiService = xtreamCatalogApiService,
            xtreamCatalogHttpService = xtreamCatalogHttpService,
            xtreamAdaptiveSyncPolicy = xtreamAdaptiveSyncPolicy,
            xtreamSupport = xtreamSupport,
            xtreamFetcher = xtreamFetcher,
            catalogStrategySupport = catalogStrategySupport,
            syncCatalogStore = syncCatalogStore,
            progress = ::progress,
            sanitizeThrowableMessage = ::sanitizeThrowableMessage,
            fullCatalogFallbackWarning = ::fullCatalogFallbackWarning,
            categoryFailureWarning = ::categoryFailureWarning,
            liveCategorySequentialModeWarning = LIVE_CATEGORY_SEQUENTIAL_MODE_WARNING,
            isCurrentlyLowOnMemory = applicationContext::isCurrentlyLowOnMemoryForSync,
            stageChannelItems = catalogStager::stageChannelItems,
            emitProgress = ::emitProviderProgress
        )
    }

    override fun syncStateForProvider(providerId: Long): Flow<SyncState> =
        syncStatesByProvider.map { states -> states[providerId] ?: SyncState.Idle }

    override fun currentSyncState(providerId: Long): SyncState =
        syncStatusPublicationCoordinator.currentSyncState(providerId)

    /** Returns true if any provider sync mutex is currently held (used by DatabaseMaintenanceManager). */
    fun isAnySyncActive(): Boolean = providerWorkLocks.isAnyWorkActiveOrWaiting()

    private suspend fun <T> withProviderLock(providerId: Long, block: suspend () -> T): T {
        return providerWorkLocks.withProviderLock(providerId, block)
    }

    private suspend fun <T> withStalkerIndexSectionLock(
        providerId: Long,
        section: ContentType,
        block: suspend () -> T
    ): T {
        return providerSyncLocks.withStalkerIndexSectionLock(providerId, section, block)
    }

    private suspend fun <T> withStalkerSummaryProviderLock(providerId: Long, block: suspend () -> T): T {
        return providerSyncLocks.withStalkerSummaryLock(providerId, block)
    }

    private suspend fun <T> withStalkerSummaryLock(
        providerId: Long,
        section: ContentType?,
        block: suspend () -> T
    ): T = withProviderLock(providerId) {
        when (section) {
            ContentType.MOVIE,
            ContentType.SERIES -> withStalkerSummaryProviderLock(providerId, block)
            else -> block()
        }
    }

    private suspend fun <T> withStalkerFetchPermit(
        provider: Provider,
        contentType: ContentType,
        categoryId: Long,
        page: Int,
        block: suspend () -> T
    ): T = stalkerRequestCoordinator.execute(
        providerId = provider.id,
        priority = StalkerRequestPriority.BACKGROUND_INDEX,
        descriptor = StalkerRequestDescriptor(
            contentType = contentType.name,
            action = "INDEX_PAGE",
            categoryKey = categoryId.toString(),
            page = page
        )
    ) { block() }

    suspend fun runWhenNoSyncActive(block: suspend () -> Boolean): Boolean =
        providerWorkLocks.runWhenNoWorkActive(block)

    override suspend fun onProviderDeleted(providerId: Long) {
        runCatching { providerSyncWorkScheduler.cancelBackgroundEpg(providerId) }
            .onFailure { error -> Log.w(TAG, "Failed to cancel background EPG work: ${sanitizeThrowableMessage(error)}") }
        runCatching { providerSyncWorkScheduler.cancelStalkerIndex(providerId) }
            .onFailure { error -> Log.w(TAG, "Failed to cancel Stalker index work: ${sanitizeThrowableMessage(error)}") }
        withProviderLock(providerId) {
            resetState(providerId)
            stalkerReadinessTracker.clear(providerId)
            xtreamAdaptiveSyncPolicy.forgetProvider(providerId)
            syncCatalogStore.clearProviderStaging(providerId)
            epgRepository.onProviderDeleted(providerId)
        }
        providerSyncLocks.forgetProvider(providerId)
        providerWorkLocks.forgetProvider(providerId)
        stalkerPlaybackCapabilityCache.invalidate(providerId)
        stalkerRequestCoordinator.forgetProvider(providerId)
        StalkerTrafficCoordinator.forgetProvider(providerId)
        StalkerProvider.clearCachesForProvider(providerId)
    }

    override fun scheduleBackgroundEpgSync(providerId: Long) {
        runCatching {
            providerSyncWorkScheduler.scheduleBackgroundEpg(providerId)
        }.onFailure { error ->
            Log.w(TAG, "Failed to schedule background EPG sync for provider $providerId: ${sanitizeThrowableMessage(error)}")
        }
    }

    override fun scheduleProviderSyncResume(
        providerId: Long,
        configurationGeneration: Long?
    ) {
        runCatching {
            providerSyncWorkScheduler.scheduleProviderResume(providerId, configurationGeneration)
        }.onFailure { error ->
            Log.w(TAG, "Failed to schedule provider resume work for provider $providerId: ${sanitizeThrowableMessage(error)}")
        }
    }

    override fun scheduleXtreamIndexSync(providerId: Long, section: ContentType?, force: Boolean) {
        runCatching {
            providerSyncWorkScheduler.scheduleXtreamIndex(providerId, section, force)
        }.onFailure { error ->
            Log.w(TAG, "Failed to schedule Xtream index work for provider $providerId (${section?.name ?: "all"}): ${sanitizeThrowableMessage(error)}")
        }
    }

    override fun scheduleStalkerIndexSync(
        providerId: Long,
        section: ContentType?,
        force: Boolean,
        initialDelaySeconds: Long,
        appendSuccessor: Boolean
    ) {
        runCatching {
            providerSyncWorkScheduler.scheduleStalkerIndex(
                providerId = providerId,
                force = force,
                initialDelaySeconds = initialDelaySeconds,
                appendSuccessor = appendSuccessor
            )
        }.onFailure { error ->
            Log.w(TAG, "Failed to schedule Stalker index work for provider $providerId (${section?.name ?: "all"}): ${sanitizeThrowableMessage(error)}")
        }
    }

    fun noteStalkerPlaybackStarted(providerId: Long) {
        StalkerTrafficCoordinator.notePlaybackStarted(providerId)
    }

    fun noteStalkerPlaybackStopped(providerId: Long) {
        StalkerTrafficCoordinator.notePlaybackStopped(providerId)
        scheduleStalkerIndexSync(providerId = providerId)
    }

    override suspend fun prioritizeXtreamIndexCategory(
        providerId: Long,
        section: ContentType,
        categoryId: Long
    ) {
        if (section != ContentType.MOVIE && section != ContentType.SERIES) return
        val now = System.currentTimeMillis()
        val updated = xtreamIndexJobDao.requestCategoryPriority(
            providerId = providerId,
            section = section.name,
            categoryId = categoryId,
            requestedAt = now
        )
        if (updated == 0) {
            upsertXtreamIndexJob(
                providerId = providerId,
                section = section.name,
                state = "QUEUED",
                now = now,
                priorityCategoryId = categoryId,
                priorityRequestedAt = now
            )
        }
        scheduleXtreamIndexSync(providerId, section, force = false)
    }

    override suspend fun prioritizeStalkerIndexCategory(
        providerId: Long,
        section: ContentType,
        categoryId: Long
    ) {
        // Interactive repositories hydrate the requested category directly. Retained as a
        // source-compatible no-op while callers migrate to the request coordinator.
        if (providerId <= 0L || categoryId == Long.MIN_VALUE) return
        if (section != ContentType.MOVIE && section != ContentType.SERIES) return
    }

    override suspend fun syncEpg(
        providerId: Long,
        force: Boolean,
        onProgress: ((String) -> Unit)?
    ): com.streamvault.domain.model.Result<Unit> {
        val providerEntity = providerDao.getById(providerId)
            ?: return com.streamvault.domain.model.Result.error("Provider $providerId not found")

        return withProviderLock(providerId) {
            val freshProviderEntity = providerDao.getById(providerId)
                ?: return@withProviderLock com.streamvault.domain.model.Result.error("Provider $providerId not found")
            syncEpgLocked(freshProviderEntity, force, onProgress)
        }
    }

    private suspend fun syncEpgLocked(
        providerEntity: com.streamvault.data.local.entity.ProviderEntity,
        force: Boolean,
        onProgress: ((String) -> Unit)?
    ): com.streamvault.domain.model.Result<Unit> {
        val provider = loadCompatibilityProvider(providerEntity.id)
            ?: return Result.error("Provider ${providerEntity.id} has no typed configuration")
        val providerId = provider.id

        val startedAt = System.currentTimeMillis()
        updateXtreamEpgJobState(
            provider = provider,
            state = "RUNNING",
            now = startedAt,
            lastAttemptAt = startedAt,
            lastError = null
        )
        val stateSession = beginSyncStateSession(providerId)
        publishSyncState(providerId, SyncState.Syncing("Downloading EPG..."))

        return try {
            val snapshot = syncProviderSnapshotAdapter.getSnapshot(providerId)
                ?: syncProviderSnapshotAdapter.toSnapshot(provider)
            val epgResult = when (val result = syncCoordinator.syncGuide(
                ProviderGuideSyncRequest(
                    snapshot = snapshot,
                    force = force,
                    now = startedAt,
                    onProgress = onProgress
                )
            )) {
                is CapabilityResolution.Available -> result.capability
                is CapabilityResolution.ConfigurationError -> return failGuideResolution(provider, result.reason)
                is CapabilityResolution.Restricted -> return failGuideResolution(provider, result.reason)
                is CapabilityResolution.Unsupported -> return failGuideResolution(provider, result.reason)
            }
            val finishedAt = System.currentTimeMillis()
            val epgCount = programDao.countByProvider(providerId)
            updateXtreamEpgJobState(
                provider = provider,
                state = when {
                    epgResult.warnings.isEmpty() -> "SUCCESS"
                    epgResult.hasRetryableFailure -> "FAILED_RETRYABLE"
                    else -> "PARTIAL"
                },
                now = finishedAt,
                indexedRows = epgCount,
                lastSuccessAt = finishedAt.takeIf { epgResult.warnings.isEmpty() },
                lastError = epgResult.warnings.takeIf { it.isNotEmpty() }?.joinToString("; ")
            )
            updateSyncStatusMetadata(
                providerId = providerId,
                status = if (epgResult.warnings.isEmpty()) "SUCCESS" else "PARTIAL"
            )
            publishSyncState(
                providerId,
                if (epgResult.warnings.isEmpty()) {
                    SyncState.Success()
                } else {
                    SyncState.Partial(
                        message = "EPG sync completed with warnings",
                        warnings = epgResult.warnings,
                        hasRetryableEpgFailure = epgResult.hasRetryableFailure
                    )
                }
            )
            com.streamvault.domain.model.Result.success(Unit)
        } catch (e: CancellationException) {
            resetState(providerId)
            throw e
        } catch (e: Exception) {
            val safeMessage = syncErrorSanitizer.userMessage(e, "EPG sync failed")
            Log.e(TAG, "EPG sync failed for provider $providerId: ${syncErrorSanitizer.throwableMessage(e)}")
            val failedAt = System.currentTimeMillis()
            updateXtreamEpgJobState(
                provider = provider,
                state = if (isRetryableEpgException(e)) "FAILED_RETRYABLE" else "FAILED_PERMANENT",
                now = failedAt,
                indexedRows = programDao.countByProvider(providerId),
                lastError = safeMessage
            )
            updateSyncStatusMetadata(providerId = providerId, status = syncFailureStatus(provider, e))
            publishSyncState(providerId, SyncState.Error(safeMessage, e))
            com.streamvault.domain.model.Result.error(safeMessage, e)
        } finally {
            finishSyncStateSession(stateSession)
        }
    }

    private suspend fun failGuideResolution(
        provider: Provider,
        reason: String
    ): com.streamvault.domain.model.Result<Unit> {
        val failedAt = System.currentTimeMillis()
        updateXtreamEpgJobState(
            provider = provider,
            state = "FAILED_PERMANENT",
            now = failedAt,
            indexedRows = programDao.countByProvider(provider.id),
            lastError = reason
        )
        updateSyncStatusMetadata(providerId = provider.id, status = "FAILED_PERMANENT")
        publishSyncState(provider.id, SyncState.Error(reason))
        return Result.error(reason)
    }

    private suspend fun failSectionResolution(
        providerId: Long,
        reason: String
    ): com.streamvault.domain.model.Result<Unit> {
        updateSyncStatusMetadata(providerId = providerId, status = "FAILED_PERMANENT")
        publishSyncState(providerId, SyncState.Error(reason))
        return Result.error(reason)
    }

    private fun shouldSyncEpgUpfront(provider: Provider): Boolean =
        provider.epgSyncMode == ProviderEpgSyncMode.UPFRONT

    private suspend fun updateXtreamEpgJobState(
        provider: Provider,
        state: String,
        now: Long,
        indexedRows: Int? = null,
        lastAttemptAt: Long? = null,
        lastSuccessAt: Long? = null,
        lastError: String? = null
    ) {
        if (provider.type != ProviderType.XTREAM_CODES) return
        if (provider.epgSyncMode == ProviderEpgSyncMode.SKIP) return
        upsertXtreamIndexJob(
            providerId = provider.id,
            section = "EPG",
            state = state,
            now = now,
            totalCategories = 1,
            completedCategories = if (state == "SUCCESS") 1 else 0,
            nextCategoryIndex = if (state == "SUCCESS") 1 else 0,
            failedCategories = if (state == "FAILED_RETRYABLE" || state == "FAILED_PERMANENT") 1 else 0,
            indexedRows = indexedRows,
            lastAttemptAt = lastAttemptAt,
            lastSuccessAt = lastSuccessAt,
            lastError = lastError
        )
    }

    override suspend fun sync(
        providerId: Long,
        force: Boolean,
        movieFastSyncOverride: Boolean?,
        epgSyncModeOverride: ProviderEpgSyncMode?,
        onProgress: ((String) -> Unit)?,
        trackInitialLiveOnboarding: Boolean
    ): com.streamvault.domain.model.Result<Unit> = syncWithProviderOverride(
        providerId = providerId,
        force = force,
        movieFastSyncOverride = movieFastSyncOverride,
        epgSyncModeOverride = epgSyncModeOverride,
        onProgress = onProgress,
        trackInitialLiveOnboarding = trackInitialLiveOnboarding
    )

    /**
     * Runs a not-yet-committed configuration. The callback is invoked within the first catalog
     * transaction that publishes data, so callers can atomically promote the configuration.
     */
    override suspend fun syncWithProviderOverride(
        providerId: Long,
        force: Boolean,
        movieFastSyncOverride: Boolean?,
        epgSyncModeOverride: ProviderEpgSyncMode?,
        onProgress: ((String) -> Unit)?,
        trackInitialLiveOnboarding: Boolean,
        providerOverride: Provider?,
        afterCatalogApply: (suspend () -> Unit)?
    ): com.streamvault.domain.model.Result<Unit> = withProviderLock(providerId) lock@{
        var progressSession: SyncProgressSession? = null
        try {
            val providerEntity = providerDao.getById(providerId)
                ?: return@lock com.streamvault.domain.model.Result.error("Provider $providerId not found")

            require(providerOverride == null || providerOverride.id == providerId) {
                "Provider override must match the requested provider ID."
            }
            val provider = (providerOverride ?: loadCompatibilityProvider(providerId)
                ?: return@lock Result.error("Provider $providerId has no typed configuration"))
                .let { resolvedProvider ->
                    resolvedProvider.copy(
                        xtreamFastSyncEnabled = movieFastSyncOverride ?: resolvedProvider.xtreamFastSyncEnabled,
                        epgSyncMode = epgSyncModeOverride ?: resolvedProvider.epgSyncMode
                    )
                }
            val catalogCommitGate = CatalogCommitGate(afterCatalogApply ?: {})
            progressSession = beginProgressSession(providerId)
            publishSyncState(providerId, SyncState.Syncing("Starting..."))

            val snapshot = if (providerOverride != null) {
                syncProviderSnapshotAdapter.toSnapshot(provider)
            } else {
                syncProviderSnapshotAdapter.getSnapshot(providerId)
                    ?: syncProviderSnapshotAdapter.toSnapshot(provider)
            }
            try {
                val outcome = withContext(Dispatchers.IO) {
                    when (val resolution = syncCoordinator.syncFull(
                        FullProviderSyncRequest(
                            snapshot = snapshot,
                            force = force,
                            onProgress = onProgress,
                            trackInitialLiveOnboarding = trackInitialLiveOnboarding,
                            deferProviderStateUntilCatalogCommit = providerOverride != null,
                            afterCatalogApply = catalogCommitGate::apply
                        )
                    )) {
                        is CapabilityResolution.Available -> resolution.capability
                        is CapabilityResolution.ConfigurationError -> throw IllegalStateException(resolution.reason)
                        is CapabilityResolution.Restricted -> throw IllegalStateException(resolution.reason)
                        is CapabilityResolution.Unsupported -> throw IllegalStateException(resolution.reason)
                    }
                }
                transactionRunner.inTransaction {
                    providerWorkflowCommitFence.assertCanCommit(providerId)
                    // A candidate configuration which produced no catalog is not committed. Do
                    // not make the previous provider look freshly synced in that case.
                    if (
                        !outcome.requiresPartialActivation &&
                        (providerOverride == null || catalogCommitGate.hasApplied)
                    ) {
                        providerDao.updateSyncTime(providerId, System.currentTimeMillis())
                    }
                    updateSyncStatusMetadata(
                        providerId = providerId,
                        status = if (outcome.requiresPartialActivation) "PARTIAL" else "SUCCESS"
                    )
                }
                val restoreStartedAt = System.currentTimeMillis()
                progress(providerId, onProgress, "Restoring backup choices...")
                pendingBackupRestoreCoordinator?.applyForProvider(providerId)
                Log.i(TAG, "backup restore resolution provider=$providerId took=${System.currentTimeMillis() - restoreStartedAt}ms")
                publishSyncState(providerId, if (outcome.requiresPartialActivation) {
                    SyncState.Partial("Sync completed with warnings", outcome.warnings)
                } else {
                    SyncState.Success()
                })
                com.streamvault.domain.model.Result.success(Unit)
            } catch (e: CancellationException) {
                resetState(providerId)
                throw e
            } catch (e: Exception) {
                val safeMessage = syncErrorSanitizer.userMessage(e, "Sync failed")
                Log.e(TAG, "Sync failed for provider $providerId: ${syncErrorSanitizer.throwableMessage(e)}")
                if (provider.type == ProviderType.XTREAM_CODES && trackInitialLiveOnboarding) {
                    xtreamCatalogExecutor.markInitialOnboardingFailure(provider, e)
                }
                updateSyncStatusMetadata(providerId = providerId, status = syncFailureStatus(provider, e))
                publishSyncState(providerId, SyncState.Error(safeMessage, e))
                com.streamvault.domain.model.Result.error(safeMessage, e)
            }
        } finally {
            progressSession?.let(::finishProgressSession)
        }
    }

    fun resetState(providerId: Long? = null) {
        syncStatusPublicationCoordinator.reset(providerId)
    }

    override suspend fun rebuildXtreamIndex(
        providerId: Long,
        onProgress: ((String) -> Unit)?
    ): com.streamvault.domain.model.Result<Unit> = withProviderLock(providerId) lock@{
        val providerEntity = providerDao.getById(providerId)
            ?: return@lock com.streamvault.domain.model.Result.error("Provider $providerId not found")
        if (providerEntity.type != ProviderType.XTREAM_CODES) {
            return@lock com.streamvault.domain.model.Result.error("Index rebuild is only available for Xtream providers")
        }

        val provider = loadCompatibilityProvider(providerId)
            ?: return@lock Result.error("Provider $providerId has no typed configuration")

        val progressSession = beginProgressSession(providerId)
        publishSyncState(providerId, SyncState.Syncing("Preparing index rebuild..."))
        val now = System.currentTimeMillis()
        val warnings = mutableListOf<String>()

        try {
            withContext(Dispatchers.IO) {
                progress(providerId, onProgress, "Marking existing index rows stale...")
                val staleRows = xtreamContentIndexDao.markVodAndSeriesRowsStaleForRebuild(providerId)
                Log.i(TAG, "Marked $staleRows Xtream VOD/series index rows STALE_REMOTE for provider $providerId rebuild.")

                val useTextClassification = preferencesRepository.useXtreamTextClassification.first()
                val enableBase64TextCompatibility = preferencesRepository.xtreamBase64TextCompatibility.first()
                val api = createXtreamSyncProvider(provider, useTextClassification, enableBase64TextCompatibility)

                xtreamCatalogSectionExecutor.syncCategoryShell(
                    provider = provider,
                    api = api,
                    contentType = ContentType.MOVIE,
                    label = "Movies",
                    now = now,
                    onProgress = onProgress
                ).getOrElse { error ->
                    warnings += "Movies index rebuild could not be queued."
                    upsertXtreamIndexJob(
                        providerId = providerId,
                        section = ContentType.MOVIE.name,
                        state = XtreamIndexRecoveryPolicy.failureState(error),
                        now = now,
                        lastAttemptAt = now,
                        lastError = sanitizeThrowableMessage(error)
                    )
                    0
                }
                scheduleXtreamIndexSync(providerId, ContentType.MOVIE, force = true)

                xtreamCatalogSectionExecutor.syncCategoryShell(
                    provider = provider,
                    api = api,
                    contentType = ContentType.SERIES,
                    label = "Series",
                    now = now,
                    onProgress = onProgress
                ).getOrElse { error ->
                    warnings += "Series index rebuild could not be queued."
                    upsertXtreamIndexJob(
                        providerId = providerId,
                        section = ContentType.SERIES.name,
                        state = XtreamIndexRecoveryPolicy.failureState(error),
                        now = now,
                        lastAttemptAt = now,
                        lastError = sanitizeThrowableMessage(error)
                    )
                    0
                }
                scheduleXtreamIndexSync(providerId, ContentType.SERIES, force = true)

                syncStatusPublicationCoordinator.markMovieIndexRebuildAttempt(providerId, now)
            }
            updateSyncStatusMetadata(
                providerId = providerId,
                status = if (warnings.isEmpty()) "SUCCESS" else "PARTIAL"
            )
            publishSyncState(
                providerId,
                if (warnings.isEmpty()) {
                    SyncState.Success()
                } else {
                    SyncState.Partial("Index rebuild queued with warnings", warnings)
                }
            )
            com.streamvault.domain.model.Result.success(Unit)
        } catch (e: CancellationException) {
            resetState(providerId)
            throw e
        } catch (e: Exception) {
            val safeMessage = syncErrorSanitizer.userMessage(e, "Index rebuild failed")
            Log.e(TAG, "Xtream index rebuild failed for provider $providerId: ${syncErrorSanitizer.throwableMessage(e)}")
            updateSyncStatusMetadata(providerId = providerId, status = syncFailureStatus(provider, e))
            publishSyncState(providerId, SyncState.Error(safeMessage, e))
            com.streamvault.domain.model.Result.error(safeMessage, e)
        } finally {
            finishProgressSession(progressSession)
        }
    }

    override suspend fun retrySection(
        providerId: Long,
        section: SyncRepairSection,
        movieFastSyncOverride: Boolean?,
        syncReason: XtreamLiveSyncReason,
        onProgress: ((String) -> Unit)?
    ): com.streamvault.domain.model.Result<Unit> = withProviderLock(providerId) lock@{
        val providerEntity = providerDao.getById(providerId)
            ?: return@lock com.streamvault.domain.model.Result.error("Provider $providerId not found")

        val resolvedProvider = loadCompatibilityProvider(providerId)
            ?: return@lock Result.error("Provider $providerId has no typed configuration")
        val provider = movieFastSyncOverride?.let { override ->
            resolvedProvider.copy(xtreamFastSyncEnabled = override)
        } ?: resolvedProvider

        val progressSession = beginProgressSession(providerId)
        publishSyncState(providerId, SyncState.Syncing("Retrying ${section.name}..."))
        try {
            val persistedSnapshot = syncProviderSnapshotAdapter.getSnapshot(providerId)
                ?: syncProviderSnapshotAdapter.toSnapshot(provider)
            val persistedConfiguration = persistedSnapshot.configuration
            val snapshot = if (movieFastSyncOverride != null && persistedConfiguration is XtreamConfig) {
                persistedSnapshot.copy(
                    configuration = persistedConfiguration.copy(
                        fastSyncEnabled = movieFastSyncOverride
                    )
                )
            } else {
                persistedSnapshot
            }
            val sectionResult = withContext(Dispatchers.IO) {
                syncCoordinator.syncSection(
                    SectionProviderSyncRequest(
                        snapshot = snapshot,
                        section = section,
                        syncReason = syncReason,
                        onProgress = onProgress
                    )
                )
            }
            val outcome = when (sectionResult) {
                is CapabilityResolution.Available -> sectionResult.capability
                is CapabilityResolution.Unsupported -> return@lock failSectionResolution(providerId, sectionResult.reason)
                is CapabilityResolution.Restricted -> return@lock failSectionResolution(providerId, sectionResult.reason)
                is CapabilityResolution.ConfigurationError -> return@lock failSectionResolution(providerId, sectionResult.reason)
            }
            updateSyncStatusMetadata(
                providerId = providerId,
                status = if (outcome.requiresPartialActivation) "PARTIAL" else "SUCCESS"
            )
            progress(providerId, onProgress, "Restoring backup choices...")
            pendingBackupRestoreCoordinator?.applyForProvider(providerId)
            publishSyncState(
                providerId,
                if (outcome.requiresPartialActivation) {
                    SyncState.Partial("Section retry completed with warnings", outcome.warnings)
                } else {
                    SyncState.Success()
                }
            )
            com.streamvault.domain.model.Result.success(Unit)
        } catch (e: CancellationException) {
            resetState(providerId)
            throw e
        } catch (e: Exception) {
            val safeMessage = syncErrorSanitizer.userMessage(e, "Retry failed")
            Log.e(TAG, "Section retry failed for provider $providerId [$section]: ${syncErrorSanitizer.throwableMessage(e)}")
            updateSyncStatusMetadata(providerId = providerId, status = syncFailureStatus(provider, e))
            publishSyncState(providerId, SyncState.Error(safeMessage, e))
            com.streamvault.domain.model.Result.error(safeMessage, e)
        } finally {
            finishProgressSession(progressSession)
        }
    }

    override suspend fun processQueuedXtreamIndexJobs(
        providerId: Long,
        section: ContentType?,
        force: Boolean,
        maxCategoriesPerSection: Int?,
        onProgress: ((String) -> Unit)?
    ): com.streamvault.domain.model.Result<Unit> = withProviderLock(providerId) lock@{
        val providerEntity = providerDao.getById(providerId)
            ?: return@lock com.streamvault.domain.model.Result.error("Provider $providerId not found")
        if (providerEntity.type != ProviderType.XTREAM_CODES) {
            return@lock com.streamvault.domain.model.Result.success(Unit)
        }

        val provider = loadCompatibilityProvider(providerId)
            ?: return@lock Result.error("Provider $providerId has no typed configuration")
        val useTextClassification = preferencesRepository.useXtreamTextClassification.first()
        val enableBase64TextCompatibility = preferencesRepository.xtreamBase64TextCompatibility.first()
        val api = createXtreamSyncProvider(provider, useTextClassification, enableBase64TextCompatibility)
        val sections = when (section) {
            ContentType.MOVIE -> listOf(ContentType.MOVIE)
            ContentType.SERIES -> listOf(ContentType.SERIES)
            ContentType.LIVE -> listOf(ContentType.LIVE)
            ContentType.VOD -> emptyList()
            ContentType.SERIES_EPISODE -> emptyList()
            null -> listOf(ContentType.LIVE, ContentType.MOVIE, ContentType.SERIES)
        }

        var sawRetryableFailure = false
        val warnings = mutableListOf<String>()
        val failures = mutableListOf<Throwable>()
        sections.forEach { contentType ->
            val job = xtreamIndexJobDao.get(providerId, contentType.name)
            if (!force && !catalogIndexJobStore.shouldRunSummary(job)) {
                return@forEach
            }
            val failure = runSuspendCatching {
                when (contentType) {
                    ContentType.LIVE -> processXtreamLiveIndexBackfillSection(providerId, onProgress)
                    ContentType.MOVIE,
                    ContentType.SERIES -> xtreamIncrementalIndexExecutor.processSummary(
                        provider,
                        api,
                        contentType,
                        maxCategoriesPerSection,
                        onProgress
                    )
                    ContentType.VOD,
                    ContentType.SERIES_EPISODE -> Unit
                }
            }.exceptionOrNull()
            if (failure != null) {
                val state = XtreamIndexRecoveryPolicy.failureState(failure)
                val currentJob = xtreamIndexJobDao.get(providerId, contentType.name)
                if (currentJob?.state != "PARTIAL") {
                    val failureAt = System.currentTimeMillis()
                    upsertXtreamIndexJob(
                        providerId = providerId,
                        section = contentType.name,
                        state = state,
                        now = failureAt,
                        lastAttemptAt = failureAt,
                        lastError = sanitizeThrowableMessage(failure)
                    )
                }
                failures += failure
                sawRetryableFailure = sawRetryableFailure || state != "FAILED_PERMANENT"
                warnings += "${contentType.name.lowercase().replaceFirstChar { it.titlecase() }} indexing failed: ${sanitizeThrowableMessage(failure)}"
            }
        }

        val result = if (warnings.isNotEmpty()) {
            val message = warnings.joinToString("; ")
            val cause = failures.firstOrNull()
            val exception = if (sawRetryableFailure) IOException(message, cause) else IllegalStateException(message, cause)
            com.streamvault.domain.model.Result.error(warnings.first(), exception)
        } else {
            com.streamvault.domain.model.Result.success(Unit)
        }
        result.alsoApplyPendingRestore(providerId)
    }

    override fun cancelStalkerIndexSync(providerId: Long) {
        providerSyncWorkScheduler.cancelStalkerIndex(providerId)
    }

    override suspend fun reconcileStalkerIndexWorkAtStartup() {
        stalkerIndexContinuationCoordinator.reconcileAtStartup()
    }

    override suspend fun processQueuedStalkerIndexJobs(
        providerId: Long,
        section: ContentType?,
        force: Boolean,
        maxCategoriesPerSection: Int?,
        onProgress: ((String) -> Unit)?
    ): com.streamvault.domain.model.Result<Unit> {
        return withStalkerSummaryLock(providerId, section) lock@{
        val playbackDelayMillis = if (StalkerTrafficCoordinator.isPlaybackActive(providerId)) 2_000L else 0L
        if (playbackDelayMillis > 0L) {
            Log.i(TAG, "Deferring Stalker catalog work for provider $providerId because playback is active.")
            scheduleStalkerIndexSync(
                providerId = providerId,
                section = null,
                force = force,
                initialDelaySeconds = ((playbackDelayMillis + 999L) / 1000L).coerceAtLeast(1L)
            )
            return@lock com.streamvault.domain.model.Result.success(Unit)
        }

        val providerEntity = providerDao.getById(providerId)
            ?: return@lock com.streamvault.domain.model.Result.error("Provider $providerId not found")
        if (providerEntity.type != ProviderType.STALKER_PORTAL) {
            return@lock com.streamvault.domain.model.Result.success(Unit)
        }

        val provider = loadCompatibilityProvider(providerId)
            ?: return@lock Result.error("Provider $providerId has no typed configuration")
        val api = createStalkerSyncProvider(provider)
        val decision = stalkerIndexContinuationCoordinator.chooseNextSection(
            provider = provider,
            api = api,
            requestedSection = section,
            force = force,
            now = System.currentTimeMillis()
        )
        val contentType = decision.contentType
        if (contentType == null) {
            Log.i(TAG, "Stalker catalog worker skipped provider $providerId: ${decision.reason}")
            if (decision.retryDelaySeconds > 0L) {
                scheduleStalkerIndexSync(
                    providerId = providerId,
                    section = null,
                    force = false,
                    initialDelaySeconds = decision.retryDelaySeconds,
                    appendSuccessor = true
                )
            } else {
                stalkerIndexContinuationCoordinator.scheduleEpgIfCatalogIdle(provider)
            }
            return@lock com.streamvault.domain.model.Result.success(Unit)
        }
        Log.i(TAG, "Stalker catalog worker selected ${contentType.name} for provider $providerId: ${decision.reason}")

        val warnings = mutableListOf<String>()
        val failures = mutableListOf<Throwable>()
        var sawRetryableFailure = false
        val categoryLimit = maxCategoriesPerSection?.coerceAtLeast(1) ?: STALKER_INDEX_CATEGORY_SLICE_SIZE
        val failure = runSuspendCatching {
            stalkerIncrementalIndexExecutor.processSummary(
                provider = provider,
                api = api,
                contentType = contentType,
                maxCategories = categoryLimit,
                onProgress = onProgress
            )
        }.exceptionOrNull()
        if (failure != null) {
            val failedAt = System.currentTimeMillis()
            val state = StalkerIndexRecoveryPolicy.failureState(failure, ::sanitizeThrowableMessage)
            upsertXtreamIndexJob(
                providerId = providerId,
                section = contentType.name,
                state = state,
                now = failedAt,
                lastAttemptAt = failedAt,
                lastError = sanitizeThrowableMessage(failure)
            )
            failures += failure
            sawRetryableFailure = sawRetryableFailure || state != "FAILED_PERMANENT"
            warnings += "${contentType.name.lowercase().replaceFirstChar { it.titlecase() }} indexing failed: ${sanitizeThrowableMessage(failure)}"
        }

        val result = if (warnings.isNotEmpty()) {
            val message = warnings.joinToString("; ")
            val cause = failures.firstOrNull()
            val exception = if (sawRetryableFailure) IOException(message, cause) else IllegalStateException(message, cause)
            com.streamvault.domain.model.Result.error(warnings.first(), exception)
        } else {
            stalkerIndexContinuationCoordinator.scheduleNextSection(provider, api)
            stalkerIndexContinuationCoordinator.scheduleEpgIfCatalogIdle(provider)
            com.streamvault.domain.model.Result.success(Unit)
        }
        result.alsoApplyPendingRestore(providerId)
        }
    }

    private suspend fun fetchStalkerSummaryPageWithRecovery(
        api: StalkerProvider,
        contentType: ContentType,
        categoryId: Long,
        page: Int,
        splitVod: Boolean = false
    ): Result<com.streamvault.data.remote.stalker.StalkerPagedResult<out Any>> {
        suspend fun fetch(): Result<com.streamvault.data.remote.stalker.StalkerPagedResult<out Any>> {
            if (splitVod && contentType == ContentType.MOVIE) {
                val seriesCategoryId = api.projectVodCategoryToSeries(categoryId)
                    ?: return Result.error("Unable to resolve VOD-derived Series category")
                return api.getSplitVodPage(categoryId, seriesCategoryId, page)
            }
            return fetchStalkerSummaryPage(api, contentType, categoryId, page)
        }
        val initial = fetch()
        val recovered = if (initial is Result.Error && StalkerIndexRecoveryPolicy.isLikelyAuthFailure(initial.message, initial.exception)) {
            Log.w(
                TAG,
                "Retrying Stalker ${contentType.name} page $page after auth refresh for category $categoryId"
            )
            api.invalidateAuthentication()
            fetch()
        } else {
            initial
        }
        if (page != 1 || recovered !is Result.Success || recovered.data.items.isNotEmpty()) {
            return recovered
        }
        val fallback = fetchStalkerSummaryFirstPageFallback(api, contentType, categoryId)
        return if (fallback is Result.Success && fallback.data.items.isNotEmpty()) {
            Log.i(
                TAG,
                "Recovered empty paged Stalker ${contentType.name} first page with non-paged fallback for category $categoryId"
            )
            fallback
        } else if (fallback is Result.Error && fallback.exception is StalkerApiError.CatalogTruncated) {
            val truncation = fallback.exception as StalkerApiError.CatalogTruncated
            Result.success(
                com.streamvault.data.remote.stalker.StalkerPagedResult(
                    items = emptyList(),
                    page = 1,
                    totalPages = truncation.pageLimit,
                    pageSize = 0,
                    hasAdvertisedTotal = true,
                    advertisedTotalPages = truncation.advertisedTotalPages,
                    isTruncated = true,
                    terminationReason = "page_limit"
                )
            )
        } else {
            recovered
        }
    }

    private suspend fun fetchStalkerSummaryFirstPageFallback(
        api: StalkerProvider,
        contentType: ContentType,
        categoryId: Long
    ): Result<com.streamvault.data.remote.stalker.StalkerPagedResult<out Any>> = when (contentType) {
        ContentType.MOVIE -> when (val result = api.getVodStreams(categoryId)) {
            is Result.Success -> Result.success(
                com.streamvault.data.remote.stalker.StalkerPagedResult(
                    items = result.data,
                    page = 1,
                    totalPages = 1,
                    pageSize = result.data.size
                )
            )
            is Result.Error -> Result.error(result.message, result.exception)
            is Result.Loading -> Result.error("Unexpected loading state from Stalker movie first-page fallback")
        }
        ContentType.SERIES -> when (val result = api.getSeriesList(categoryId)) {
            is Result.Success -> Result.success(
                com.streamvault.data.remote.stalker.StalkerPagedResult(
                    items = result.data,
                    page = 1,
                    totalPages = 1,
                    pageSize = result.data.size
                )
            )
            is Result.Error -> Result.error(result.message, result.exception)
            is Result.Loading -> Result.error("Unexpected loading state from Stalker series first-page fallback")
        }
        else -> Result.error("Unsupported Stalker summary first-page fallback section: $contentType")
    }

    private suspend fun fetchStalkerWildcardSummaryPageWithRecovery(
        api: StalkerProvider,
        contentType: ContentType,
        categoryId: Long,
        page: Int
    ): Result<com.streamvault.data.remote.stalker.StalkerPagedResult<out Any>> {
        val initial = fetchStalkerWildcardSummaryPage(api, contentType, categoryId, page)
        if (initial is Result.Error && StalkerIndexRecoveryPolicy.isLikelyAuthFailure(initial.message, initial.exception)) {
            Log.w(
                TAG,
                "Retrying Stalker wildcard ${contentType.name} page $page after auth refresh for category $categoryId"
            )
            api.invalidateAuthentication()
            return fetchStalkerWildcardSummaryPage(api, contentType, categoryId, page)
        }
        return initial
    }

    private fun detectStalkerPageAnomaly(
        hydration: StalkerHydrationSnapshot?,
        requestedPage: Int,
        pagedResult: com.streamvault.data.remote.stalker.StalkerPagedResult<out Any>,
        pageFingerprint: String?
    ): String? = StalkerIndexPolicy.detectPageAnomaly(
        hydration,
        requestedPage,
        pagedResult,
        pageFingerprint
    )

    private fun stalkerPageFingerprint(
        items: List<out Any>,
        contentType: ContentType
    ): String? = StalkerIndexPolicy.pageFingerprint(items, contentType)

    private fun dedupeStalkerPageItems(
        items: List<out Any>,
        contentType: ContentType
    ): List<out Any> = StalkerIndexPolicy.dedupePageItems(items, contentType)

    private fun filterStalkerItemsToCategories(
        items: List<out Any>,
        contentType: ContentType,
        visibleCategoryIds: Set<Long>?
    ): List<out Any> = StalkerIndexPolicy.filterToVisibleCategories(items, contentType, visibleCategoryIds)

    private suspend fun fetchStalkerSummaryPage(
        api: StalkerProvider,
        contentType: ContentType,
        categoryId: Long,
        page: Int
    ): Result<com.streamvault.data.remote.stalker.StalkerPagedResult<out Any>> = when (contentType) {
        ContentType.MOVIE -> api.getVodStreamsPage(categoryId, page)
        ContentType.SERIES -> api.getSeriesListPage(categoryId, page)
        else -> Result.error("Unsupported Stalker summary page section: $contentType")
    }

    private suspend fun fetchStalkerWildcardSummaryPage(
        api: StalkerProvider,
        contentType: ContentType,
        categoryId: Long,
        page: Int
    ): Result<com.streamvault.data.remote.stalker.StalkerPagedResult<out Any>> = when (contentType) {
        ContentType.MOVIE -> api.getVodStreamsPageUsingItemCategories(categoryId, page)
        ContentType.SERIES -> api.getSeriesListPage(categoryId, page)
        else -> Result.error("Unsupported Stalker wildcard summary page section: $contentType")
    }

    private suspend fun allVisibleStalkerIndexCategories(
        providerId: Long,
        contentType: ContentType,
        categories: List<CategoryEntity>
    ): List<CategoryEntity> {
        val hiddenCategoryIds = preferencesRepository.getHiddenCategoryIds(providerId, contentType).first()
        val requiredHiddenIds = pendingBackupRestoreCoordinator
            ?.requiredHiddenCategoryIds(providerId, contentType)
            .orEmpty()
        return categories.filterNot { category ->
            category.categoryId in hiddenCategoryIds && category.categoryId !in requiredHiddenIds
        }
    }

    private suspend fun visibleStalkerIndexCategories(
        contentType: ContentType,
        categories: List<CategoryEntity>,
        api: StalkerProvider
    ): List<CategoryEntity> {
        val visible = categories
        if (visible.isEmpty()) return emptyList()
        val normalCategories = visible.filterNot { category -> api.isWildcardCategory(contentType, category.categoryId) }
        return if (normalCategories.isNotEmpty()) normalCategories else visible
    }

    private suspend fun currentStalkerIndexedRowCount(providerId: Long, contentType: ContentType): Int =
        when (contentType) {
            ContentType.MOVIE -> movieDao.getCount(providerId).first()
            ContentType.SERIES -> seriesDao.getCount(providerId).first()
            else -> 0
        }

    private suspend fun currentStalkerCategoryCount(providerId: Long, contentType: ContentType, categoryId: Long): Int =
        when (contentType) {
            ContentType.MOVIE -> movieDao.getCountByCategory(providerId, categoryId).first()
            ContentType.SERIES -> seriesDao.getCountByCategory(providerId, categoryId).first()
            else -> 0
        }

    private suspend fun updateStalkerSummaryMetadata(
        providerId: Long,
        contentType: ContentType,
        indexedRows: Int,
        finalState: String,
        now: Long
    ) {
        syncStatusPublicationCoordinator.updateSummaryMetadata(
            providerId = providerId,
            contentType = contentType,
            indexedRows = indexedRows,
            finalState = finalState,
            now = now,
            movieSyncMode = VodSyncMode.PAGED
        )
    }

    private suspend fun upsertXtreamMovieSummaryBatch(
        providerId: Long,
        movies: List<Movie>,
        indexedAt: Long,
        restoreWatchProgress: Boolean = true
    ): Int {
        if (movies.isEmpty()) return 0
        val incoming = movies.map { movie -> movie.toEntity().copy(cacheState = "SUMMARY_ONLY", detailHydratedAt = 0L, remoteStaleAt = 0L) }
        val existingByStreamId = loadMoviesByStreamIds(providerId, incoming.map { it.streamId })
        val merged = incoming.map { summary ->
            val existing = existingByStreamId[summary.streamId]
            mergeMovieSummary(existing, summary)
        }
        transactionRunner.inTransaction {
            movieDao.insertAll(merged)
            val persistedByStreamId = loadMoviesByStreamIds(providerId, merged.map { it.streamId })
            xtreamContentIndexDao.upsertAll(
                merged.map { movie ->
                    val persisted = persistedByStreamId[movie.streamId] ?: movie
                    movie.toXtreamIndexRow(providerId, persisted.id, indexedAt)
                }
            )
        }
        if (restoreWatchProgress) {
            movieDao.restoreWatchProgress(providerId)
        }
        return merged.size
    }

    private suspend fun upsertXtreamSeriesSummaryBatch(
        providerId: Long,
        series: List<Series>,
        indexedAt: Long
    ): Int {
        if (series.isEmpty()) return 0
        val incoming = series.map { item -> item.toEntity().copy(cacheState = "SUMMARY_ONLY", detailHydratedAt = 0L, remoteStaleAt = 0L) }
        val existingBySeriesId = loadSeriesByIds(providerId, incoming.map { it.seriesId })
        val merged = incoming.map { summary ->
            val existing = existingBySeriesId[summary.seriesId]
            mergeSeriesSummary(existing, summary)
        }
        transactionRunner.inTransaction {
            seriesDao.insertAll(merged)
            val persistedBySeriesId = loadSeriesByIds(providerId, merged.map { it.seriesId })
            xtreamContentIndexDao.upsertAll(
                merged.map { item ->
                    val persisted = persistedBySeriesId[item.seriesId] ?: item
                    item.toXtreamIndexRow(providerId, persisted.id, indexedAt)
                }
            )
        }
        return merged.size
    }

    private suspend fun upsertVodDerivedSeriesSummaryBatch(
        providerId: Long,
        series: List<Series>,
        indexedAt: Long
    ): Int {
        if (series.isEmpty()) return 0
        val incoming = series.map { item ->
            item.toEntity().copy(
                catalogOrigin = com.streamvault.domain.model.SeriesCatalogOrigin.VOD_DERIVED,
                cacheState = "SUMMARY_ONLY",
                detailHydratedAt = 0L,
                remoteStaleAt = 0L
            )
        }
        var accepted = emptyList<SeriesEntity>()
        transactionRunner.inTransaction {
            val existing = seriesDao.getBySeriesIds(providerId, incoming.map { it.seriesId })
                .associateBy { it.seriesId }
            accepted = incoming.mapNotNull { summary ->
                val current = existing[summary.seriesId]
                if (current?.catalogOrigin == com.streamvault.domain.model.SeriesCatalogOrigin.NATIVE) {
                    null
                } else {
                    mergeSeriesSummary(current, summary)
                }
            }
            val projectedCategories = accepted.mapNotNull { item ->
                val categoryId = item.categoryId ?: return@mapNotNull null
                CategoryEntity(
                    providerId = providerId,
                    categoryId = categoryId,
                    name = item.categoryName ?: "Category $categoryId",
                    type = ContentType.SERIES,
                    isAdult = item.isAdult,
                    isUserProtected = item.isUserProtected
                )
            }.distinctBy { it.categoryId }
            if (projectedCategories.isNotEmpty()) categoryDao.insertAll(projectedCategories)
            if (accepted.isNotEmpty()) {
                seriesDao.insertAll(accepted)
                val persisted = seriesDao.getBySeriesIds(providerId, accepted.map { it.seriesId })
                    .associateBy { it.seriesId }
                xtreamContentIndexDao.upsertAll(
                    accepted.map { item ->
                        item.toXtreamIndexRow(providerId, persisted[item.seriesId]?.id ?: item.id, indexedAt)
                    }
                )
            }
        }
        return accepted.size
    }

    private suspend fun loadMoviesByStreamIds(
        providerId: Long,
        streamIds: List<Long>
    ): Map<Long, MovieEntity> {
        return chunkedLookupById(
            ids = streamIds,
            chunkSize = XTREAM_SQLITE_LOOKUP_CHUNK_SIZE,
            fetch = { chunk -> movieDao.getByStreamIds(providerId, chunk) },
            keySelector = MovieEntity::streamId
        )
    }

    private suspend fun loadSeriesByIds(
        providerId: Long,
        seriesIds: List<Long>
    ): Map<Long, SeriesEntity> {
        return chunkedLookupById(
            ids = seriesIds,
            chunkSize = XTREAM_SQLITE_LOOKUP_CHUNK_SIZE,
            fetch = { chunk -> seriesDao.getBySeriesIds(providerId, chunk) },
            keySelector = SeriesEntity::seriesId
        )
    }

    private fun mergeMovieSummary(existing: MovieEntity?, summary: MovieEntity): MovieEntity {
        if (existing == null) return summary
        val preserveDetails = existing.cacheState == "DETAIL_HYDRATED" && existing.detailHydratedAt > 0L
        return summary.copy(
            id = existing.id,
            posterUrl = summary.posterUrl ?: existing.posterUrl,
            backdropUrl = if (preserveDetails) existing.backdropUrl else summary.backdropUrl,
            plot = if (preserveDetails) existing.plot else summary.plot,
            cast = if (preserveDetails) existing.cast else summary.cast,
            director = if (preserveDetails) existing.director else summary.director,
            genre = summary.genre ?: existing.genre,
            releaseDate = if (preserveDetails) existing.releaseDate else summary.releaseDate,
            duration = if (preserveDetails) existing.duration else summary.duration,
            durationSeconds = if (preserveDetails) existing.durationSeconds else summary.durationSeconds,
            year = if (preserveDetails) existing.year else summary.year,
            tmdbId = summary.tmdbId ?: existing.tmdbId,
            youtubeTrailer = summary.youtubeTrailer ?: existing.youtubeTrailer,
            watchProgress = existing.watchProgress,
            watchCount = existing.watchCount,
            lastWatchedAt = existing.lastWatchedAt,
            isUserProtected = existing.isUserProtected,
            cacheState = if (preserveDetails) existing.cacheState else "SUMMARY_ONLY",
            detailHydratedAt = if (preserveDetails) existing.detailHydratedAt else 0L,
            remoteStaleAt = 0L
        )
    }

    private fun mergeSeriesSummary(existing: SeriesEntity?, summary: SeriesEntity): SeriesEntity {
        if (existing == null) return summary
        val preserveDetails = existing.cacheState == "DETAIL_HYDRATED" && existing.detailHydratedAt > 0L
        return summary.copy(
            id = existing.id,
            providerSeriesId = summary.providerSeriesId ?: existing.providerSeriesId,
            posterUrl = summary.posterUrl ?: existing.posterUrl,
            backdropUrl = if (preserveDetails) existing.backdropUrl else summary.backdropUrl,
            plot = if (preserveDetails) existing.plot else summary.plot,
            cast = if (preserveDetails) existing.cast else summary.cast,
            director = if (preserveDetails) existing.director else summary.director,
            genre = summary.genre ?: existing.genre,
            releaseDate = if (preserveDetails) existing.releaseDate else summary.releaseDate,
            tmdbId = summary.tmdbId ?: existing.tmdbId,
            youtubeTrailer = summary.youtubeTrailer ?: existing.youtubeTrailer,
            episodeRunTime = if (preserveDetails) existing.episodeRunTime else summary.episodeRunTime,
            isUserProtected = existing.isUserProtected,
            cacheState = if (preserveDetails) existing.cacheState else "SUMMARY_ONLY",
            detailHydratedAt = if (preserveDetails) existing.detailHydratedAt else 0L,
            remoteStaleAt = 0L
        )
    }

    private fun MovieEntity.toXtreamIndexRow(
        providerId: Long,
        localContentId: Long,
        indexedAt: Long
    ): XtreamContentIndexEntity = XtreamContentIndexEntity(
        providerId = providerId,
        contentType = ContentType.MOVIE,
        remoteId = streamId.toString(),
        localContentId = localContentId.takeIf { it > 0L },
        name = name,
        categoryId = categoryId,
        categoryName = categoryName,
        imageUrl = posterUrl,
        containerExtension = containerExtension,
        rating = rating,
        addedAt = addedAt,
        isAdult = isAdult,
        indexedAt = indexedAt,
        detailHydratedAt = detailHydratedAt,
        staleState = "ACTIVE",
        errorState = null,
        syncFingerprint = syncFingerprint
    )

    private fun SeriesEntity.toXtreamIndexRow(
        providerId: Long,
        localContentId: Long,
        indexedAt: Long
    ): XtreamContentIndexEntity = XtreamContentIndexEntity(
        providerId = providerId,
        contentType = ContentType.SERIES,
        remoteId = providerSeriesId?.takeIf { it.isNotBlank() } ?: seriesId.toString(),
        localContentId = localContentId.takeIf { it > 0L },
        name = name,
        categoryId = categoryId,
        categoryName = categoryName,
        imageUrl = posterUrl,
        rating = rating,
        remoteUpdatedAt = lastModified,
        isAdult = isAdult,
        indexedAt = indexedAt,
        detailHydratedAt = detailHydratedAt,
        staleState = "ACTIVE",
        errorState = null,
        syncFingerprint = syncFingerprint
    )

    private suspend fun updateXtreamSummaryMetadata(
        providerId: Long,
        contentType: ContentType,
        indexedRows: Int,
        finalState: String,
        now: Long
    ) {
        syncStatusPublicationCoordinator.updateSummaryMetadata(
            providerId = providerId,
            contentType = contentType,
            indexedRows = indexedRows,
            finalState = finalState,
            now = now,
            movieSyncMode = VodSyncMode.UNKNOWN
        )
    }

    private fun CategoryEntity.toXtreamCategory(): XtreamCategory = XtreamCategory(
        categoryId = categoryId.toString(),
        categoryName = name,
        parentId = parentId?.toInt() ?: 0,
        isAdult = isAdult
    )

    private fun xtreamIndexSectionLabel(contentType: ContentType): String = when (contentType) {
        ContentType.MOVIE -> "Movies"
        ContentType.SERIES -> "Series"
        ContentType.LIVE -> "Live TV"
        ContentType.VOD -> "VOD"
        ContentType.SERIES_EPISODE -> "Episodes"
    }

    private suspend fun processXtreamLiveIndexBackfillSection(
        providerId: Long,
        onProgress: ((String) -> Unit)?
    ) {
        val now = System.currentTimeMillis()
        progress(providerId, onProgress, "Preparing Live TV index...")
        upsertXtreamIndexJob(
            providerId = providerId,
            section = ContentType.LIVE.name,
            state = "RUNNING",
            now = now,
            lastAttemptAt = now,
            lastError = null
        )
        val categoryCount = categoryDao.getByProviderAndTypeSync(providerId, ContentType.LIVE.name).size
        val indexedRows = backfillXtreamLiveIndex(providerId, now)
        upsertXtreamIndexJob(
            providerId = providerId,
            section = ContentType.LIVE.name,
            state = "SUCCESS",
            now = System.currentTimeMillis(),
            totalCategories = categoryCount,
            completedCategories = categoryCount,
            nextCategoryIndex = categoryCount,
            failedCategories = 0,
            indexedRows = indexedRows,
            skippedMalformedRows = 0,
            lastAttemptAt = now,
            lastSuccessAt = System.currentTimeMillis(),
            lastError = null
        )
    }

    private suspend fun backfillXtreamLiveIndex(providerId: Long, indexedAt: Long): Int {
        val channels = channelDao.getByProviderSync(providerId)
        if (channels.isEmpty()) return 0
        val indexRows = channels.map { channel ->
            XtreamContentIndexEntity(
                providerId = providerId,
                contentType = ContentType.LIVE,
                remoteId = channel.streamId.toString(),
                localContentId = channel.id.takeIf { it > 0 },
                name = channel.name,
                categoryId = channel.categoryId,
                categoryName = channel.categoryName ?: channel.groupTitle,
                imageUrl = channel.logoUrl,
                isAdult = channel.isAdult,
                indexedAt = indexedAt,
                detailHydratedAt = indexedAt,
                syncFingerprint = channel.syncFingerprint
            )
        }
        transactionRunner.inTransaction {
            xtreamContentIndexDao.deleteByProviderAndType(providerId, ContentType.LIVE.name)
            xtreamContentIndexDao.upsertAll(indexRows)
        }
        return channels.size
    }

    private suspend fun upsertXtreamIndexJob(
        providerId: Long,
        section: String,
        state: String,
        now: Long,
        totalCategories: Int? = null,
        completedCategories: Int? = null,
        nextCategoryIndex: Int? = null,
        failedCategories: Int? = null,
        indexedRows: Int? = null,
        skippedMalformedRows: Int? = null,
        deletedPrunedRows: Int? = null,
        priorityCategoryId: Long? = null,
        priorityRequestedAt: Long? = null,
        clearPriority: Boolean = false,
        lastAttemptAt: Long? = null,
        lastSuccessAt: Long? = null,
        lastError: String? = null
    ) {
        catalogIndexJobStore.upsert(
            CatalogIndexJobUpdate(
                providerId = providerId,
                section = section,
                state = state,
                now = now,
                totalCategories = totalCategories,
                completedCategories = completedCategories,
                nextCategoryIndex = nextCategoryIndex,
                failedCategories = failedCategories,
                indexedRows = indexedRows,
                skippedMalformedRows = skippedMalformedRows,
                deletedPrunedRows = deletedPrunedRows,
                priorityCategoryId = priorityCategoryId,
                priorityRequestedAt = priorityRequestedAt,
                clearPriority = clearPriority,
                lastAttemptAt = lastAttemptAt,
                lastSuccessAt = lastSuccessAt,
                lastError = lastError
            )
        )
    }


    /**
     * Returns true for transient network/IO exceptions that are worth retrying via
     * WorkManager backoff — as opposed to permanent failures (bad URL, auth, parse error)
     * that will fail identically on every attempt.
     */
    private fun isRetryableEpgException(e: Exception): Boolean =
        e is java.io.IOException ||
            e is java.net.SocketTimeoutException ||
            e is java.net.ConnectException ||
            e is java.net.UnknownHostException ||
            e.cause?.let {
                it is java.io.IOException ||
                    it is java.net.SocketTimeoutException ||
                    it is java.net.ConnectException ||
                    it is java.net.UnknownHostException
            } == true



    private suspend fun updateSyncStatusMetadata(providerId: Long, status: String) {
        syncStatusPublicationCoordinator.updateStatus(providerId, status)
    }

    private fun syncFailureStatus(provider: Provider, error: Throwable): String {
        if (provider.type != ProviderType.STALKER_PORTAL) return "ERROR"
        val causes = generateSequence(error as Throwable?) { it.cause }.toList()
        return when {
            causes.any { it is StalkerApiError.PartialAuthorization } -> "PARTIAL_AUTHORIZATION"
            causes.any { it is StalkerApiError.AccountBlocked } -> "ACCOUNT_BLOCKED"
            causes.any { it is StalkerApiError.RateLimited } -> "RATE_LIMITED"
            causes.any { it is StalkerApiError.TokenRejected } -> "SESSION_REJECTED"
            causes.any { it is StalkerApiError.InvalidMac } -> "INVALID_MAC"
            causes.any { it is StalkerApiError.ModelRejected } -> "MODEL_REJECTED"
            causes.any { it is StalkerApiError.Authorization } -> "AUTHORIZATION_REJECTED"
            else -> "ERROR"
        }
    }

    private fun shouldRememberSequentialPreference(error: Throwable): Boolean {
        return xtreamAdaptiveSyncPolicy.isProviderStress(error) ||
            error is XtreamAuthenticationException ||
            error is XtreamParsingException ||
            (error is XtreamRequestException && error.statusCode in setOf(403, 429)) ||
            (error is XtreamNetworkException && error.message.orEmpty().contains("reset", ignoreCase = true))
    }

    private fun buildFallbackMovieCategories(providerId: Long, movies: List<Movie>): List<CategoryEntity> =
        catalogStrategySupport.buildFallbackMovieCategories(providerId, movies)

    private fun buildFallbackLiveCategories(providerId: Long, channels: List<Channel>): List<CategoryEntity> =
        catalogStrategySupport.buildFallbackLiveCategories(providerId, channels)

    private fun buildFallbackSeriesCategories(providerId: Long, series: List<Series>): List<CategoryEntity> =
        catalogStrategySupport.buildFallbackSeriesCategories(providerId, series)

    private fun mergePreferredAndFallbackCategories(
        preferred: List<CategoryEntity>?,
        fallback: List<CategoryEntity>?
    ): List<CategoryEntity>? = catalogStrategySupport.mergePreferredAndFallbackCategories(preferred, fallback)


    private fun progress(providerId: Long, callback: ((String) -> Unit)?, message: String) {
        syncStatusPublicationCoordinator.progress(providerId, callback, message)
    }

    private fun emitCatalogSyncProgress(
        providerId: Long,
        section: Section,
        current: Int = 0,
        total: Int = 0,
        currentLabel: String = "",
        itemsIndexed: Int = 0
    ) {
        emitProviderProgress(providerId,
            SyncProgress(
                section = section,
                current = current,
                total = total,
                currentLabel = currentLabel,
                itemsIndexed = itemsIndexed
            )
        )
    }

    private fun publishSyncState(providerId: Long, state: SyncState) {
        syncStatusPublicationCoordinator.publish(providerId, state)
    }

    private fun beginSyncStateSession(providerId: Long): SyncStateSession =
        syncStatusPublicationCoordinator.beginStateSession(providerId)

    private fun finishSyncStateSession(session: SyncStateSession) {
        syncStatusPublicationCoordinator.finishStateSession(session)
    }

    private fun beginProgressSession(providerId: Long): SyncProgressSession {
        return syncStatusPublicationCoordinator.beginProgressSession(providerId)
    }

    private fun finishProgressSession(session: SyncProgressSession) {
        syncStatusPublicationCoordinator.finishProgressSession(session)
    }

    private fun emitProviderProgress(providerId: Long, progress: SyncProgress) {
        syncStatusPublicationCoordinator.emitProgress(providerId, progress)
    }

    private fun redactUrlForLogs(url: String?): String {
        if (url.isNullOrBlank()) return "<empty>"
        return runCatching {
            val parsed = URI(url)
            val scheme = parsed.scheme ?: "http"
            val host = parsed.host ?: return@runCatching "<redacted>"
            val path = parsed.path.orEmpty()
            "$scheme://$host$path"
        }.getOrDefault("<redacted>")
    }

    private fun sanitizeThrowableMessage(error: Throwable?): String {
        return syncErrorSanitizer.throwableMessage(error)
    }

    private fun sanitizeLogMessage(message: String?): String {
        return syncErrorSanitizer.sanitize(message)
    }

    private fun fullCatalogFallbackWarning(sectionLabel: String, error: Throwable?): String {
        return when (error) {
            is XtreamResponseTooLargeException ->
                "$sectionLabel full catalog was too large for one request, so sync continued with a safer segmented mode."
            else ->
                "$sectionLabel full catalog request failed, so sync continued with a safer fallback mode."
        }
    }

    private fun categoryFailureWarning(sectionLabel: String, categoryName: String, error: Throwable): String {
        val safeCategoryName = sanitizeLogMessage(categoryName).takeIf { it.isNotBlank() } ?: "Unknown"
        return when (error) {
            is XtreamResponseTooLargeException ->
                "$sectionLabel category '$safeCategoryName' was too large to load safely."
            else ->
                "$sectionLabel category '$safeCategoryName' failed: " +
                    syncErrorSanitizer.userMessage(error, "Provider request failed.")
        }
    }

    private suspend fun <T> executeCategoryRecoveryPlan(
        provider: Provider,
        categories: List<XtreamCategory>,
        initialConcurrency: Int,
        sectionLabel: String,
        sequentialModeWarning: String,
        onProgress: ((String) -> Unit)?,
        fetch: suspend (XtreamCategory) -> TimedCategoryOutcome<T>
    ): CategoryExecutionPlan<T> = xtreamSupport.executeCategoryRecoveryPlan(
        provider = provider,
        categories = categories,
        initialConcurrency = initialConcurrency,
        sectionLabel = sectionLabel,
        sequentialModeWarning = sequentialModeWarning,
        onProgress = onProgress,
        fetch = fetch
    )

    private suspend fun <T> retryTransient(
        maxAttempts: Int = 3,
        initialDelayMs: Long = 700L,
        block: suspend () -> T
    ): T = xtreamSupport.retryTransient(
        maxAttempts = maxAttempts,
        initialDelayMs = initialDelayMs,
        block = block
    )

    private suspend fun <T> attemptNonCancellation(block: suspend () -> T): Attempt<T> =
        xtreamSupport.attemptNonCancellation(block)

    private suspend fun <T> executeXtreamRequest(
        providerId: Long,
        stage: XtreamAdaptiveSyncPolicy.Stage,
        block: suspend () -> T
    ): T = xtreamSupport.executeXtreamRequest(providerId, stage, block)

    private suspend fun <T> withMovieRequestTimeout(
        requestLabel: String,
        block: suspend () -> T
    ): T = xtreamSupport.withMovieRequestTimeout(requestLabel, block)

    private suspend fun <T> withSeriesRequestTimeout(
        requestLabel: String,
        block: suspend () -> T
    ): T = xtreamSupport.withSeriesRequestTimeout(requestLabel, block)

    private suspend fun <T> retryXtreamCatalogTransient(providerId: Long, block: suspend () -> T): T =
        xtreamSupport.retryXtreamCatalogTransient(providerId, block)

    companion object {
        private const val PROGRESS_INTERVAL = 5_000
    }

    private suspend fun createXtreamSyncProvider(
        provider: Provider,
        useTextClassification: Boolean = true,
        enableBase64TextCompatibility: Boolean = false
    ): XtreamProvider {
        return when (val resolution = typedProviderClientFactory.xtream(
            syncProviderSnapshotAdapter.toSnapshot(provider),
            XtreamClientOptions(useTextClassification, enableBase64TextCompatibility)
        )) {
            is CapabilityResolution.Available -> resolution.capability
            is CapabilityResolution.ConfigurationError -> throw IllegalArgumentException(resolution.reason)
            is CapabilityResolution.Restricted -> throw IllegalArgumentException(resolution.reason)
            is CapabilityResolution.Unsupported -> throw IllegalArgumentException(resolution.reason)
        }
    }

    private fun createStalkerSyncProvider(provider: Provider): StalkerProvider {
        return when (val resolution = typedProviderClientFactory.stalker(syncProviderSnapshotAdapter.toSnapshot(provider))) {
            is CapabilityResolution.Available -> resolution.capability
            is CapabilityResolution.ConfigurationError -> throw IllegalArgumentException(resolution.reason)
            is CapabilityResolution.Restricted -> throw IllegalArgumentException(resolution.reason)
            is CapabilityResolution.Unsupported -> throw IllegalArgumentException(resolution.reason)
        }
    }

    /**
     * Rebuilds the persisted transport consent for sync-time re-authentication. Without this,
     * providers on cleartext HTTP (or user-accepted TLS) would fail every background
     * authentication with a consent challenge even though the user already consented when the
     * provider was added.
     */
    private fun Provider.toStalkerTransportGrant(): StalkerTransportGrant? {
        if (stalkerTransportMode != StalkerTransportMode.USER_ACCEPTED_HTTP &&
            stalkerTransportMode != StalkerTransportMode.USER_ACCEPTED_UNVERIFIED_HTTPS &&
            stalkerTransportMode != StalkerTransportMode.VERIFIED_HTTPS
        ) {
            return null
        }
        val origin = stalkerTransportOrigin.toStalkerOrigin()
            ?: serverUrl.toStalkerOrigin()
            ?: return null
        val pin = stalkerTlsSpkiSha256.takeIf(String::isNotBlank)
        if (stalkerTransportMode == StalkerTransportMode.USER_ACCEPTED_UNVERIFIED_HTTPS && pin == null) {
            return null
        }
        return StalkerTransportGrant(
            mode = stalkerTransportMode,
            origin = origin,
            spkiSha256 = pin,
            consentedAt = stalkerTransportConsentAt
        )
    }

    private fun String.toStalkerOrigin(): StalkerTransportOrigin? {
        val uri = runCatching { java.net.URI(trim()) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()?.takeIf { it == "http" || it == "https" } ?: return null
        val host = uri.host?.lowercase()?.takeIf(String::isNotBlank) ?: return null
        val port = when {
            uri.port != -1 -> uri.port
            scheme == "https" -> 443
            else -> 80
        }
        return StalkerTransportOrigin(scheme, host, port)
    }






    private suspend fun loadStalkerMoviesByCategory(
        api: StalkerProvider,
        categories: List<com.streamvault.domain.model.Category>,
        onProgress: ((String) -> Unit)?
    ): List<Movie> {
        if (categories.isEmpty()) {
            return requireResult(api.getVodStreams(null), "Failed to load movies")
        }
        return categories.flatMap { category ->
            progress(api.providerId, onProgress, "Loading ${category.name}...")
            requireResult(api.getVodStreams(category.id), "Failed to load movies for ${category.name}")
        }.distinctBy { it.streamId }
    }

    private suspend fun loadStalkerSeriesByCategory(
        api: StalkerProvider,
        categories: List<com.streamvault.domain.model.Category>,
        onProgress: ((String) -> Unit)?
    ): List<Series> {
        if (categories.isEmpty()) {
            return requireResult(api.getSeriesList(null), "Failed to load series")
        }
        return categories.flatMap { category ->
            progress(api.providerId, onProgress, "Loading ${category.name}...")
            requireResult(api.getSeriesList(category.id), "Failed to load series for ${category.name}")
        }.distinctBy { it.seriesId }
    }

    private fun <T> requireResult(result: com.streamvault.domain.model.Result<T>, fallbackMessage: String): T {
        return when (result) {
            is com.streamvault.domain.model.Result.Success -> result.data
            is com.streamvault.domain.model.Result.Error -> throw IllegalStateException(result.message.ifBlank { fallbackMessage }, result.exception)
            is com.streamvault.domain.model.Result.Loading -> throw IllegalStateException("Unexpected loading state")
        }
    }

    private fun logXtreamCatalogFallback(
        provider: Provider,
        section: String,
        stage: String,
        elapsedMs: Long,
        itemCount: Int?,
        error: Throwable?,
        nextStep: String
    ) = xtreamSupport.logXtreamCatalogFallback(provider, section, stage, elapsedMs, itemCount, error, nextStep)

    private suspend fun fetchLiveCategoryOutcome(
        provider: Provider,
        api: XtreamProvider,
        category: XtreamCategory
    ): TimedCategoryOutcome<Channel> = xtreamFetcher.fetchLiveCategoryOutcome(provider, api, category)


    private suspend fun fetchMovieCategoryOutcome(
        provider: Provider,
        api: XtreamProvider,
        category: XtreamCategory
    ): TimedCategoryOutcome<Movie> = xtreamFetcher.fetchMovieCategoryOutcome(provider, api, category)

    private suspend fun fetchSeriesCategoryOutcome(
        provider: Provider,
        api: XtreamProvider,
        category: XtreamCategory
    ): TimedCategoryOutcome<Series> = xtreamFetcher.fetchSeriesCategoryOutcome(provider, api, category)

    private suspend fun <T> continueFailedCategoryOutcomes(
        provider: Provider,
        timedOutcomes: List<TimedCategoryOutcome<T>>,
        fetchSequentially: suspend (XtreamCategory) -> TimedCategoryOutcome<T>,
        onCategoryRetried: ((completed: Int, total: Int, currentLabel: String) -> Unit)? = null
    ): List<TimedCategoryOutcome<T>> =
        xtreamSupport.continueFailedCategoryOutcomes(provider, timedOutcomes, fetchSequentially, onCategoryRetried)
}
