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
import com.streamvault.data.local.dao.ProgramDao
import com.streamvault.data.local.dao.ProviderDao
import com.streamvault.data.local.dao.SeriesCategoryHydrationDao
import com.streamvault.data.local.dao.SeriesDao
import com.streamvault.data.local.dao.TmdbIdentityDao
import com.streamvault.data.local.dao.XtreamContentIndexDao
import com.streamvault.data.local.dao.XtreamIndexJobDao
import com.streamvault.data.local.dao.XtreamLiveOnboardingDao
import com.streamvault.data.local.entity.CategoryEntity
import com.streamvault.data.local.entity.ChannelEntity
import com.streamvault.data.local.entity.ChannelGuideSyncEntity
import com.streamvault.data.local.entity.MovieEntity
import com.streamvault.data.local.entity.SeriesEntity
import com.streamvault.data.local.entity.XtreamContentIndexEntity
import com.streamvault.data.local.entity.XtreamIndexJobEntity
import com.streamvault.data.local.entity.XtreamLiveOnboardingStateEntity
import com.streamvault.data.mapper.toDomain
import com.streamvault.data.mapper.toEntity
import com.streamvault.data.parser.M3uParser
import com.streamvault.data.remote.http.buildAppRequestProfile
import com.streamvault.data.remote.http.toGenericRequestProfile
import com.streamvault.data.remote.stalker.StalkerApiService
import com.streamvault.data.remote.stalker.StalkerPlaybackMode
import com.streamvault.data.remote.stalker.StalkerProvider
import com.streamvault.data.remote.stalker.StalkerProviderProfile
import com.streamvault.data.remote.jellyfin.JellyfinProvider
import com.streamvault.data.remote.stalker.StalkerTrafficCoordinator
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
import com.streamvault.data.util.AdultContentClassifier
import com.streamvault.data.util.UrlSecurityPolicy
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.GuideSourcePolicy
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.Movie
import com.streamvault.domain.model.ProviderEpgSyncMode
import com.streamvault.domain.model.Provider
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.Series
import com.streamvault.domain.model.SyncMetadata
import com.streamvault.domain.model.SyncState
import com.streamvault.domain.model.VodSyncMode
import com.streamvault.domain.repository.EpgRepository
import com.streamvault.domain.repository.EpgSourceRepository
import com.streamvault.domain.repository.SyncMetadataRepository
import com.streamvault.domain.sync.Section
import com.streamvault.domain.sync.SyncProgress
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SyncManager"
private const val XTREAM_FALLBACK_STAGE_BATCH_SIZE = 500
private const val STALKER_INDEX_CATEGORY_SLICE_SIZE = 32
private const val STALKER_WILDCARD_PAGE_SLICE_SIZE = 192
private const val STALKER_MAX_PARALLEL_CATEGORY_FETCHES = 6
private const val STALKER_MAX_SECTION_RUN_MILLIS = 240_000L
private const val STALKER_CATEGORY_RETRY_BUDGET = 3
private const val STALKER_CATEGORY_RETRY_COOLDOWN_MILLIS = 5 * 60 * 1000L
private const val STALKER_RUNNING_JOB_STALE_MILLIS = 15 * 60 * 1000L
private const val STALKER_MIN_HEALTHY_EPG_PROGRAMS = 3
private const val LIVE_CATEGORY_SEQUENTIAL_MODE_WARNING =
    "Live category sync downgraded to sequential mode after provider stress signals."
private const val XTREAM_RECOVERY_ABORT_WARNING_SUFFIX =
    "recovery stopped early after repeated provider stress signals; keeping recovered results."
private const val XTREAM_AVOID_FULL_CATALOG_COOLDOWN_MILLIS = 6 * 60 * 60 * 1000L
private const val XTREAM_MOVIE_REQUEST_TIMEOUT_MILLIS = 60_000L
private const val XTREAM_SERIES_REQUEST_TIMEOUT_MILLIS = 60_000L
private const val XTREAM_SQLITE_LOOKUP_CHUNK_SIZE = 900
private const val STALKER_GUIDE_PROGRAM_BATCH_SIZE = 5000
private const val XTREAM_ONBOARDING_PHASE_STARTING = "STARTING"
private const val XTREAM_ONBOARDING_PHASE_FETCHING = "FETCHING"
private const val XTREAM_ONBOARDING_PHASE_RECOVERING = "RECOVERING"
private const val XTREAM_ONBOARDING_PHASE_STAGED = "STAGED"
private const val XTREAM_ONBOARDING_PHASE_COMMITTING = "COMMITTING"
private const val XTREAM_ONBOARDING_PHASE_COMPLETED = "COMPLETED"
private const val XTREAM_ONBOARDING_PHASE_FAILED = "FAILED"
/**
 * Maximum number of programs we will accept from a single per-channel `get_epg_info`
 * call before treating the response as a portal that ignores `ch_id` and returns the
 * full bulk EPG. Probed real-world portals return at most ~150 programs/channel for a
 * 6h period; 5 000 leaves ample headroom while still catching multi-megabyte payloads.
 */
private const val STALKER_PER_CHANNEL_RECORD_SANITY_CAP = 5_000

/**
 * Sentinel exception raised inside the streamed per-channel EPG callback when we detect
 * that the portal is returning the bulk payload regardless of the requested `ch_id`.
 * Caught and converted into a warning by [SyncManager.syncStalkerPortalEpg].
 */
private class StalkerBrokenPerChannelEpgException(channelName: String) :
    RuntimeException("Stalker portal returned bulk-shaped EPG for per-channel request ($channelName)")

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

private data class XtreamLiveCommitResult(
    val acceptedCount: Int,
    val warnings: List<String>
)

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
    private val catalogSyncDao: CatalogSyncDao,
    private val tmdbIdentityDao: TmdbIdentityDao,
    private val xtreamContentIndexDao: XtreamContentIndexDao,
    private val xtreamIndexJobDao: XtreamIndexJobDao,
    private val xtreamLiveOnboardingDao: XtreamLiveOnboardingDao,
    private val stalkerApiService: StalkerApiService,
    private val episodeDao: EpisodeDao,
    private val jellyfinProvider: JellyfinProvider,
    private val xtreamJson: Json,
    private val m3uParser: M3uParser,
    private val epgRepository: EpgRepository,
    private val epgSourceRepository: EpgSourceRepository,
    private val okHttpClient: OkHttpClient,
    private val credentialCrypto: CredentialCrypto,
    private val syncMetadataRepository: SyncMetadataRepository,
    private val transactionRunner: DatabaseTransactionRunner,
    private val preferencesRepository: com.streamvault.data.preferences.PreferencesRepository,
    private val syncProgressBus: SyncProgressBus
) {
    private val syncStateTracker = SyncStateTracker()
    private val syncErrorSanitizer = SyncErrorSanitizer()
    private val xtreamAdaptiveSyncPolicy = XtreamAdaptiveSyncPolicy()
    private val syncCatalogStore = SyncCatalogStore(
        channelDao = channelDao,
        movieDao = movieDao,
        seriesDao = seriesDao,
        categoryDao = categoryDao,
        catalogSyncDao = catalogSyncDao,
        tmdbIdentityDao = tmdbIdentityDao,
        transactionRunner = transactionRunner
    )
    private val m3uImporter = SyncManagerM3uImporter(
        m3uParser = m3uParser,
        okHttpClient = okHttpClient,
        syncCatalogStore = syncCatalogStore,
        retryTransient = { block -> retryTransient(block = block) },
        progress = ::progress,
        syncProgressBus = syncProgressBus
    )
    private val xtreamSupport = SyncManagerXtreamSupport(
        adaptiveSyncPolicy = xtreamAdaptiveSyncPolicy,
        shouldRememberSequentialPreference = ::shouldRememberSequentialPreference,
        sanitizeThrowableMessage = ::sanitizeThrowableMessage,
        progress = ::progress,
        movieRequestTimeoutMillis = XTREAM_MOVIE_REQUEST_TIMEOUT_MILLIS,
        seriesRequestTimeoutMillis = XTREAM_SERIES_REQUEST_TIMEOUT_MILLIS,
        recoveryAbortWarningSuffix = XTREAM_RECOVERY_ABORT_WARNING_SUFFIX
    )
    val syncState: StateFlow<SyncState> = syncStateTracker.aggregateState
    val syncStatesByProvider: StateFlow<Map<Long, SyncState>> = syncStateTracker.statesByProvider
    private val providerSyncMutexes = ConcurrentHashMap<Long, Mutex>()
    private val providerStalkerSummaryMutexes = ConcurrentHashMap<Long, Mutex>()
    private val providerStalkerIndexSectionMutexes = ConcurrentHashMap<String, Mutex>()
    private val providerStalkerFetchSemaphores = ConcurrentHashMap<Long, Semaphore>()
    private val providerEpgMutexes = ConcurrentHashMap<Long, Mutex>()
    private val syncAdmissionMutex = Mutex()
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
            syncProgressBus = syncProgressBus
        )
    }

    fun syncStateForProvider(providerId: Long): Flow<SyncState> =
        syncStatesByProvider.map { states -> states[providerId] ?: SyncState.Idle }

    fun currentSyncState(providerId: Long): SyncState =
        syncStateTracker.current(providerId)

    /** Returns true if any provider sync mutex is currently held (used by DatabaseMaintenanceManager). */
    fun isAnySyncActive(): Boolean = isAnySyncMutexLocked()

    private fun isAnySyncMutexLocked(): Boolean =
        providerSyncMutexes.values.any { it.isLocked } ||
            providerEpgMutexes.values.any { it.isLocked }

    private suspend fun <T> withProviderLock(providerId: Long, block: suspend () -> T): T {
        val mutex = syncAdmissionMutex.withLock {
            providerSyncMutexes.computeIfAbsent(providerId) { Mutex() }.also { providerMutex ->
                providerMutex.lock()
            }
        }
        try {
            return block()
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun <T> withProviderEpgLock(providerId: Long, block: suspend () -> T): T {
        val mutex = syncAdmissionMutex.withLock {
            providerEpgMutexes.computeIfAbsent(providerId) { Mutex() }.also { providerMutex ->
                providerMutex.lock()
            }
        }
        try {
            return block()
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun <T> withStalkerIndexSectionLock(
        providerId: Long,
        section: ContentType,
        block: suspend () -> T
    ): T {
        val lockKey = "$providerId:${section.name}"
        val mutex = syncAdmissionMutex.withLock {
            providerStalkerIndexSectionMutexes.computeIfAbsent(lockKey) { Mutex() }.also { sectionMutex ->
                sectionMutex.lock()
            }
        }
        try {
            return block()
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun <T> withStalkerSummaryProviderLock(providerId: Long, block: suspend () -> T): T {
        val mutex = syncAdmissionMutex.withLock {
            providerStalkerSummaryMutexes.computeIfAbsent(providerId) { Mutex() }.also { providerMutex ->
                providerMutex.lock()
            }
        }
        try {
            return block()
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun <T> withStalkerSummaryLock(
        providerId: Long,
        section: ContentType?,
        providerMaxConnections: Int,
        block: suspend () -> T
    ): T = when (section) {
        ContentType.MOVIE,
        ContentType.SERIES -> withStalkerSummaryProviderLock(providerId, block)
        else -> withProviderLock(providerId, block)
    }

    private suspend fun <T> withStalkerFetchPermit(provider: Provider, block: suspend () -> T): T {
        val permitCount = minOf(
            provider.maxConnections.coerceAtLeast(1),
            STALKER_MAX_PARALLEL_CATEGORY_FETCHES
        ).coerceAtLeast(1)
        val semaphore = syncAdmissionMutex.withLock {
            providerStalkerFetchSemaphores.computeIfAbsent(provider.id) { Semaphore(permitCount) }
        }
        return semaphore.withPermit { block() }
    }

    suspend fun runWhenNoSyncActive(block: suspend () -> Boolean): Boolean =
        syncAdmissionMutex.withLock {
            if (isAnySyncMutexLocked()) {
                false
            } else {
                block()
            }
        }

    suspend fun onProviderDeleted(providerId: Long) {
        BackgroundEpgSyncWorker.cancel(applicationContext, providerId)
        withProviderLock(providerId) {
            withProviderEpgLock(providerId) {
                syncStateTracker.reset(providerId)
                xtreamAdaptiveSyncPolicy.forgetProvider(providerId)
                syncCatalogStore.clearProviderStaging(providerId)
                epgRepository.onProviderDeleted(providerId)
                providerSyncMutexes.remove(providerId)
                providerStalkerSummaryMutexes.remove(providerId)
                providerEpgMutexes.remove(providerId)
            }
        }
    }

    fun scheduleBackgroundEpgSync(providerId: Long) {
        runCatching {
            BackgroundEpgSyncWorker.enqueue(applicationContext, providerId)
        }.onFailure { error ->
            Log.w(TAG, "Failed to schedule background EPG sync for provider $providerId: ${sanitizeThrowableMessage(error)}")
        }
    }

    fun scheduleProviderSyncResume(providerId: Long) {
        runCatching {
            ProviderSyncWorker.enqueueProvider(applicationContext, providerId)
        }.onFailure { error ->
            Log.w(TAG, "Failed to schedule provider resume work for provider $providerId: ${sanitizeThrowableMessage(error)}")
        }
    }

    fun scheduleXtreamIndexSync(providerId: Long, section: ContentType? = null, force: Boolean = false) {
        runCatching {
            XtreamIndexWorker.enqueue(
                context = applicationContext,
                providerId = providerId,
                section = section?.name,
                force = force
            )
        }.onFailure { error ->
            Log.w(TAG, "Failed to schedule Xtream index work for provider $providerId (${section?.name ?: "all"}): ${sanitizeThrowableMessage(error)}")
        }
    }

    fun scheduleStalkerIndexSync(
        providerId: Long,
        section: ContentType? = null,
        force: Boolean = false,
        initialDelaySeconds: Long = 0L
    ) {
        val effectiveSection: ContentType? = null
        runCatching {
            StalkerIndexWorker.enqueue(
                context = applicationContext,
                providerId = providerId,
                section = effectiveSection?.name,
                force = force,
                initialDelaySeconds = initialDelaySeconds
            )
        }.onFailure { error ->
            Log.w(TAG, "Failed to schedule Stalker index work for provider $providerId (${effectiveSection?.name ?: "all"}): ${sanitizeThrowableMessage(error)}")
        }
    }

    fun noteStalkerPlaybackStarted(providerId: Long) {
        StalkerTrafficCoordinator.notePlaybackStarted(providerId)
    }

    fun noteStalkerPlaybackStopped(providerId: Long) {
        StalkerTrafficCoordinator.notePlaybackStopped(providerId)
        scheduleStalkerIndexSync(providerId = providerId)
    }

    suspend fun prioritizeXtreamIndexCategory(
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

    suspend fun prioritizeStalkerIndexCategory(
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
        val provider = providerDao.getById(providerId)?.toDomain()
        if (provider != null) {
            scheduleStalkerIndexContinuation(provider, section, force = false)
        } else {
            scheduleStalkerIndexSync(providerId, section, force = false)
        }
    }

    suspend fun syncEpg(
        providerId: Long,
        force: Boolean = true,
        onProgress: ((String) -> Unit)? = null
    ): com.streamvault.domain.model.Result<Unit> {
        val providerEntity = providerDao.getById(providerId)
            ?: return com.streamvault.domain.model.Result.error("Provider $providerId not found")

        return if (providerEntity.type == ProviderType.STALKER_PORTAL) {
            withProviderEpgLock(providerId) {
                syncEpgLocked(providerEntity, force, onProgress)
            }
        } else {
            withProviderLock(providerId) {
                val freshProviderEntity = providerDao.getById(providerId)
                    ?: return@withProviderLock com.streamvault.domain.model.Result.error("Provider $providerId not found")
                syncEpgLocked(freshProviderEntity, force, onProgress)
            }
        }
    }

    private suspend fun syncEpgLocked(
        providerEntity: com.streamvault.data.local.entity.ProviderEntity,
        force: Boolean,
        onProgress: ((String) -> Unit)?
    ): com.streamvault.domain.model.Result<Unit> {
        val provider = providerEntity
            .copy(password = credentialCrypto.decryptIfNeeded(providerEntity.password))
            .toDomain()
        val providerId = provider.id

        if (!force && provider.type == ProviderType.STALKER_PORTAL && hasPendingStalkerCatalogIndex(provider.id)) {
            updateXtreamEpgJobState(
                provider = provider,
                state = "QUEUED",
                now = System.currentTimeMillis(),
                lastError = null
            )
            return com.streamvault.domain.model.Result.success(Unit)
        }

        val startedAt = System.currentTimeMillis()
        updateXtreamEpgJobState(
            provider = provider,
            state = "RUNNING",
            now = startedAt,
            lastAttemptAt = startedAt,
            lastError = null
        )
        publishSyncState(providerId, SyncState.Syncing("Downloading EPG..."))

        return try {
            val epgResult = syncProviderEpg(
                provider = provider,
                metadata = syncMetadataRepository.getMetadata(provider.id) ?: SyncMetadata(provider.id),
                now = startedAt,
                force = force,
                onProgress = onProgress
            )
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
            updateSyncStatusMetadata(providerId = providerId, status = "ERROR")
            publishSyncState(providerId, SyncState.Error(safeMessage, e))
            com.streamvault.domain.model.Result.error(safeMessage, e)
        }
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
        if (
            provider.type != ProviderType.XTREAM_CODES &&
            provider.type != ProviderType.STALKER_PORTAL
        ) {
            return
        }
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

    suspend fun sync(
        providerId: Long,
        force: Boolean = false,
        movieFastSyncOverride: Boolean? = null,
        epgSyncModeOverride: ProviderEpgSyncMode? = null,
        onProgress: ((String) -> Unit)? = null,
        trackInitialLiveOnboarding: Boolean = false
    ): com.streamvault.domain.model.Result<Unit> = withProviderLock(providerId) lock@{
        try {
            val providerEntity = providerDao.getById(providerId)
                ?: return@lock com.streamvault.domain.model.Result.error("Provider $providerId not found")

            val provider = providerEntity
                .copy(password = credentialCrypto.decryptIfNeeded(providerEntity.password))
                .toDomain()
                .let { resolvedProvider ->
                    resolvedProvider.copy(
                        xtreamFastSyncEnabled = movieFastSyncOverride ?: resolvedProvider.xtreamFastSyncEnabled,
                        epgSyncMode = epgSyncModeOverride ?: resolvedProvider.epgSyncMode
                    )
                }
            publishSyncState(providerId, SyncState.Syncing("Starting..."))

            try {
                val outcome = withContext(Dispatchers.IO) {
                    when (provider.type) {
                        ProviderType.XTREAM_CODES -> syncXtreamIndexFirst(
                            provider = provider,
                            force = force,
                            onProgress = onProgress,
                            trackInitialLiveOnboarding = trackInitialLiveOnboarding,
                            syncReason = if (trackInitialLiveOnboarding) {
                                XtreamLiveSyncReason.INITIAL_ONBOARDING
                            } else {
                                XtreamLiveSyncReason.FOREGROUND
                            }
                        )
                        ProviderType.M3U -> syncM3u(provider, force, onProgress)
                        ProviderType.STALKER_PORTAL -> syncStalker(provider, force, onProgress)
                        ProviderType.JELLYFIN -> syncJellyfin(provider, force, onProgress)
                    }
                }
                providerDao.updateSyncTime(providerId, System.currentTimeMillis())
                updateSyncStatusMetadata(
                    providerId = providerId,
                    status = if (outcome.partial) "PARTIAL" else "SUCCESS"
                )
                publishSyncState(providerId, if (outcome.partial) {
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
                    recordXtreamLiveOnboardingState(
                        provider = provider,
                        phase = XTREAM_ONBOARDING_PHASE_FAILED,
                        lastError = sanitizeThrowableMessage(e)
                    )
                }
                updateSyncStatusMetadata(providerId = providerId, status = "ERROR")
                publishSyncState(providerId, SyncState.Error(safeMessage, e))
                com.streamvault.domain.model.Result.error(safeMessage, e)
            }
        } finally {
            // D7 — reset systematique du bus a la fin du cycle (succes, exception, abort low-memory)
            // pour eviter qu'un ecran ulterieur n'herite d'un etat de progression obsolete.
            syncProgressBus.reset()
        }
    }

    fun resetState(providerId: Long? = null) {
        syncStateTracker.reset(providerId)
    }

    suspend fun rebuildXtreamIndex(
        providerId: Long,
        onProgress: ((String) -> Unit)? = null
    ): com.streamvault.domain.model.Result<Unit> = withProviderLock(providerId) lock@{
        val providerEntity = providerDao.getById(providerId)
            ?: return@lock com.streamvault.domain.model.Result.error("Provider $providerId not found")
        if (providerEntity.type != ProviderType.XTREAM_CODES) {
            return@lock com.streamvault.domain.model.Result.error("Index rebuild is only available for Xtream providers")
        }

        val provider = providerEntity
            .copy(password = credentialCrypto.decryptIfNeeded(providerEntity.password))
            .toDomain()

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

                syncXtreamCategoryShell(
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
                        state = xtreamIndexFailureState(error),
                        now = now,
                        lastAttemptAt = now,
                        lastError = sanitizeThrowableMessage(error)
                    )
                    0
                }
                scheduleXtreamIndexSync(providerId, ContentType.MOVIE, force = true)

                syncXtreamCategoryShell(
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
                        state = xtreamIndexFailureState(error),
                        now = now,
                        lastAttemptAt = now,
                        lastError = sanitizeThrowableMessage(error)
                    )
                    0
                }
                scheduleXtreamIndexSync(providerId, ContentType.SERIES, force = true)

                val metadata = syncMetadataRepository.getMetadata(providerId) ?: SyncMetadata(providerId)
                syncMetadataRepository.updateMetadata(
                    metadata.copy(
                        lastMovieAttempt = now,
                        movieCatalogStale = true,
                        movieSyncMode = VodSyncMode.UNKNOWN
                    )
                )
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
            updateSyncStatusMetadata(providerId = providerId, status = "ERROR")
            publishSyncState(providerId, SyncState.Error(safeMessage, e))
            com.streamvault.domain.model.Result.error(safeMessage, e)
        }
    }

    suspend fun retrySection(
        providerId: Long,
        section: SyncRepairSection,
        movieFastSyncOverride: Boolean? = null,
        syncReason: XtreamLiveSyncReason = XtreamLiveSyncReason.MANUAL_SETTINGS,
        onProgress: ((String) -> Unit)? = null
    ): com.streamvault.domain.model.Result<Unit> = withProviderLock(providerId) lock@{
        val providerEntity = providerDao.getById(providerId)
            ?: return@lock com.streamvault.domain.model.Result.error("Provider $providerId not found")

        val provider = providerEntity
            .copy(password = credentialCrypto.decryptIfNeeded(providerEntity.password))
            .toDomain()
            .let { resolvedProvider ->
                movieFastSyncOverride?.let { override ->
                    resolvedProvider.copy(xtreamFastSyncEnabled = override)
                } ?: resolvedProvider
            }

        try {
            val outcome = withContext(Dispatchers.IO) {
                when (section) {
                    SyncRepairSection.LIVE -> syncLiveOnly(provider, syncReason, onProgress)
                    SyncRepairSection.EPG -> {
                        syncEpgOnly(provider, onProgress)
                        SyncOutcome()
                    }
                    SyncRepairSection.MOVIES -> syncMoviesOnly(provider, onProgress)
                    SyncRepairSection.SERIES -> syncSeriesOnly(provider, onProgress)
                }
            }
            updateSyncStatusMetadata(
                providerId = providerId,
                status = if (outcome.partial) "PARTIAL" else "SUCCESS"
            )
            publishSyncState(
                providerId,
                if (outcome.partial) {
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
            updateSyncStatusMetadata(providerId = providerId, status = "ERROR")
            publishSyncState(providerId, SyncState.Error(safeMessage, e))
            com.streamvault.domain.model.Result.error(safeMessage, e)
        } finally {
            syncProgressBus.reset()
        }
    }

    private suspend fun syncXtreamIndexFirst(
        provider: Provider,
        force: Boolean,
        onProgress: ((String) -> Unit)?,
        trackInitialLiveOnboarding: Boolean = false,
        syncReason: XtreamLiveSyncReason = XtreamLiveSyncReason.FOREGROUND
    ): SyncOutcome {
        val warnings = mutableListOf<String>()
        UrlSecurityPolicy.validateXtreamServerUrl(provider.serverUrl)?.let { message ->
            throw IllegalStateException(message)
        }

        progress(provider.id, onProgress, "Connecting to server...")
        val useTextClassification = preferencesRepository.useXtreamTextClassification.first()
        val enableBase64TextCompatibility = preferencesRepository.xtreamBase64TextCompatibility.first()
        val hiddenLiveCategoryIds = preferencesRepository.getHiddenCategoryIds(provider.id, ContentType.LIVE).first()
        val api = createXtreamSyncProvider(provider, useTextClassification, enableBase64TextCompatibility)
        val runtimeProfile = CatalogSyncRuntimeProfile.from(applicationContext)
        val now = System.currentTimeMillis()
        var metadata = syncMetadataRepository.getMetadata(provider.id) ?: SyncMetadata(provider.id)

        if (trackInitialLiveOnboarding) {
            recordXtreamLiveOnboardingState(
                provider = provider,
                phase = XTREAM_ONBOARDING_PHASE_STARTING,
                now = now,
                clearError = true,
                runtimeProfile = runtimeProfile
            )
        }

        upsertXtreamIndexJob(
            providerId = provider.id,
            section = ContentType.LIVE.name,
            state = "RUNNING",
            now = now,
            lastAttemptAt = now
        )
        val liveOutcome = runCatching {
            val recoveredLiveCommit = if (trackInitialLiveOnboarding) {
                recoverXtreamLiveOnboardingSession(
                    provider = provider,
                    hiddenLiveCategoryIds = hiddenLiveCategoryIds
                )
            } else {
                null
            }
            if (recoveredLiveCommit != null) {
                warnings += recoveredLiveCommit.warnings
                val acceptedCount = recoveredLiveCommit.acceptedCount
                val completedAt = System.currentTimeMillis()
                recordXtreamLiveOnboardingState(
                    provider = provider,
                    phase = XTREAM_ONBOARDING_PHASE_COMPLETED,
                    now = completedAt,
                    acceptedRowCount = acceptedCount,
                    stagedFlushCount = stagedFlushCountFor(acceptedCount),
                    clearError = true,
                    completedAt = completedAt,
                    clearStagedSession = true,
                    runtimeProfile = runtimeProfile
                )
                metadata = metadata.copy(
                    lastLiveSync = now,
                    lastLiveSuccess = now,
                    liveCount = acceptedCount
                )
                syncMetadataRepository.updateMetadata(metadata)
                return@runCatching acceptedCount
            }

            if (trackInitialLiveOnboarding) {
                recordXtreamLiveOnboardingState(
                    provider = provider,
                    phase = XTREAM_ONBOARDING_PHASE_FETCHING,
                    now = System.currentTimeMillis(),
                    clearError = true,
                    runtimeProfile = runtimeProfile
                )
            }
            progress(provider.id, onProgress, "Downloading Live TV...")
            val liveSyncResult = syncXtreamLiveCatalog(
                provider = provider,
                api = api,
                existingMetadata = metadata,
                hiddenLiveCategoryIds = hiddenLiveCategoryIds,
                onProgress = onProgress,
                runtimeProfile = runtimeProfile,
                trackInitialLiveOnboarding = trackInitialLiveOnboarding,
                syncReason = syncReason
            )
            if (trackInitialLiveOnboarding) {
                val stagedAcceptedCount = liveSyncResult.stagedAcceptedCount
                recordXtreamLiveOnboardingState(
                    provider = provider,
                    phase = if (liveSyncResult.stagedSessionId != null) {
                        XTREAM_ONBOARDING_PHASE_STAGED
                    } else {
                        XTREAM_ONBOARDING_PHASE_COMMITTING
                    },
                    now = System.currentTimeMillis(),
                    stagedSessionId = liveSyncResult.stagedSessionId,
                    importStrategy = liveSyncResult.catalogResult.strategyNameOrNull(),
                    acceptedRowCount = stagedAcceptedCount,
                    stagedFlushCount = stagedFlushCountFor(stagedAcceptedCount),
                    clearError = true,
                    runtimeProfile = runtimeProfile,
                    syncProfileStrategy = liveSyncResult.profileStrategyName(runtimeProfile, trackInitialLiveOnboarding)
                )
            }
            val liveSequentialStress = liveSyncResult.strategyFeedback.segmentedStressDetected
            val liveProviderAdaptation = updateSequentialProviderAdaptation(
                previousRemembered = metadata.liveSequentialFailuresRemembered,
                previousHealthyStreak = metadata.liveHealthySyncStreak,
                sawSequentialStress = liveSequentialStress
            )
            val liveAvoidFullUntil = updateAvoidFullUntil(
                previousAvoidFullUntil = metadata.liveAvoidFullUntil,
                now = now,
                feedback = liveSyncResult.strategyFeedback
            )

            val acceptedCount = when (val liveResult = liveSyncResult.catalogResult) {
                is CatalogStrategyResult.Success -> {
                    if (trackInitialLiveOnboarding) {
                        recordXtreamLiveOnboardingState(
                            provider = provider,
                            phase = XTREAM_ONBOARDING_PHASE_COMMITTING,
                            now = System.currentTimeMillis(),
                            stagedSessionId = liveSyncResult.stagedSessionId,
                            importStrategy = liveResult.strategyName,
                            acceptedRowCount = liveSyncResult.stagedAcceptedCount,
                            stagedFlushCount = stagedFlushCountFor(liveSyncResult.stagedAcceptedCount),
                            clearError = true,
                            runtimeProfile = runtimeProfile,
                            syncProfileStrategy = liveSyncResult.profileStrategyName(runtimeProfile, trackInitialLiveOnboarding)
                        )
                    }
                    finalizeXtreamLiveCatalog(
                        providerId = provider.id,
                        liveSyncResult = liveSyncResult,
                        hiddenLiveCategoryIds = hiddenLiveCategoryIds,
                        onProgress = onProgress
                    ).also { commitResult ->
                        warnings += commitResult.warnings
                    }.acceptedCount
                }
                is CatalogStrategyResult.Partial -> {
                    if (trackInitialLiveOnboarding) {
                        recordXtreamLiveOnboardingState(
                            provider = provider,
                            phase = XTREAM_ONBOARDING_PHASE_COMMITTING,
                            now = System.currentTimeMillis(),
                            stagedSessionId = liveSyncResult.stagedSessionId,
                            importStrategy = liveResult.strategyName,
                            acceptedRowCount = liveSyncResult.stagedAcceptedCount,
                            stagedFlushCount = stagedFlushCountFor(liveSyncResult.stagedAcceptedCount),
                            clearError = true,
                            runtimeProfile = runtimeProfile,
                            syncProfileStrategy = liveSyncResult.profileStrategyName(runtimeProfile, trackInitialLiveOnboarding)
                        )
                    }
                    finalizeXtreamLiveCatalog(
                        providerId = provider.id,
                        liveSyncResult = liveSyncResult,
                        hiddenLiveCategoryIds = hiddenLiveCategoryIds,
                        onProgress = onProgress,
                        partialCompletionWarning = "Live TV sync completed partially."
                    ).also { commitResult ->
                        warnings += commitResult.warnings
                    }.acceptedCount
                }
                is CatalogStrategyResult.EmptyValid -> {
                    val existingChannelCount = channelDao.getCount(provider.id).first()
                    if (existingChannelCount == 0) {
                        warnings += liveSyncResult.warnings + liveResult.warnings +
                            "Live TV provider exposed no live channels; continuing with VOD and series only."
                        0
                    } else {
                        warnings += liveSyncResult.warnings + liveResult.warnings +
                            "Live TV refresh returned an empty valid catalog; keeping previous channel library."
                        existingChannelCount
                    }
                }
                is CatalogStrategyResult.Failure -> {
                    val existingChannelCount = channelDao.getCount(provider.id).first()
                    if (existingChannelCount == 0) {
                        warnings += liveSyncResult.warnings + liveResult.warnings +
                            "Live TV could not be fetched; continuing with VOD and series only."
                        0
                    } else {
                        warnings += liveSyncResult.warnings + liveResult.warnings +
                            "Live TV sync degraded; keeping previous channel library."
                        existingChannelCount
                    }
                }
            }

            metadata = metadata.copy(
                lastLiveSync = now,
                lastLiveSuccess = now,
                liveCount = acceptedCount,
                liveAvoidFullUntil = liveAvoidFullUntil,
                liveSequentialFailuresRemembered = liveProviderAdaptation.rememberSequential,
                liveHealthySyncStreak = liveProviderAdaptation.healthyStreak
            )
            syncMetadataRepository.updateMetadata(metadata)
            acceptedCount
        }
        val liveCount = liveOutcome.getOrElse { error ->
            if (trackInitialLiveOnboarding) {
                recordXtreamLiveOnboardingState(
                    provider = provider,
                    phase = XTREAM_ONBOARDING_PHASE_FAILED,
                    lastError = sanitizeThrowableMessage(error),
                    runtimeProfile = runtimeProfile
                )
            }
            upsertXtreamIndexJob(
                providerId = provider.id,
                section = ContentType.LIVE.name,
                state = xtreamIndexFailureState(error),
                now = now,
                lastAttemptAt = now,
                lastError = sanitizeThrowableMessage(error)
            )
            throw error
        }
        upsertXtreamIndexJob(
            providerId = provider.id,
            section = ContentType.LIVE.name,
            state = "QUEUED",
            now = now,
            totalCategories = 1,
            completedCategories = 0,
            indexedRows = liveCount,
            lastAttemptAt = now,
            lastError = null
        )
        scheduleXtreamIndexSync(provider.id, ContentType.LIVE)

        // Transition VOD : signale a l'UI qu'on passe a la section Movies. Le total
        // reel des categories VOD n'est connu qu'a l'interieur de `syncXtreamCategoryShell`,
        // donc on emet en indetermine (total = 0). `itemsIndexed` cumule le LIVE deja importe.
        syncProgressBus.emit(
            com.streamvault.domain.sync.SyncProgress(
                section = com.streamvault.domain.sync.Section.VOD,
                current = 0,
                total = 0,
                currentLabel = "",
                itemsIndexed = liveCount
            )
        )
        val movieCategoryCount = syncXtreamCategoryShell(
            provider = provider,
            api = api,
            contentType = ContentType.MOVIE,
            label = "Movies",
            now = now,
            onProgress = onProgress
        ).getOrElse { error ->
            warnings += "Movies categories could not be loaded; movie indexing will retry later."
            upsertXtreamIndexJob(
                providerId = provider.id,
                section = ContentType.MOVIE.name,
                state = xtreamIndexFailureState(error),
                now = now,
                lastAttemptAt = now,
                lastError = sanitizeThrowableMessage(error)
            )
            0
        }
        if (movieCategoryCount > 0) {
            scheduleXtreamIndexSync(provider.id, ContentType.MOVIE)
        }
        // Transition SERIES : meme principe que VOD ci-dessus. `itemsIndexed` reste a
        // `liveCount` car VOD ne stage pas d'items dans la base au moment du shell
        // (le contenu detaille est rempli ulterieurement par XtreamIndexWorker).
        syncProgressBus.emit(
            com.streamvault.domain.sync.SyncProgress(
                section = com.streamvault.domain.sync.Section.SERIES,
                current = 0,
                total = 0,
                currentLabel = "",
                itemsIndexed = liveCount
            )
        )
        val seriesCategoryCount = syncXtreamCategoryShell(
            provider = provider,
            api = api,
            contentType = ContentType.SERIES,
            label = "Series",
            now = now,
            onProgress = onProgress
        ).getOrElse { error ->
            warnings += "Series categories could not be loaded; series indexing will retry later."
            upsertXtreamIndexJob(
                providerId = provider.id,
                section = ContentType.SERIES.name,
                state = xtreamIndexFailureState(error),
                now = now,
                lastAttemptAt = now,
                lastError = sanitizeThrowableMessage(error)
            )
            0
        }
        if (seriesCategoryCount > 0) {
            scheduleXtreamIndexSync(provider.id, ContentType.SERIES)
        }

        if (trackInitialLiveOnboarding) {
            if (liveCount > 0 || movieCategoryCount > 0 || seriesCategoryCount > 0) {
                val completedAt = System.currentTimeMillis()
                recordXtreamLiveOnboardingState(
                    provider = provider,
                    phase = XTREAM_ONBOARDING_PHASE_COMPLETED,
                    now = completedAt,
                    acceptedRowCount = liveCount,
                    stagedFlushCount = stagedFlushCountFor(liveCount),
                    clearError = true,
                    completedAt = completedAt,
                    clearStagedSession = true,
                    runtimeProfile = runtimeProfile
                )
            } else {
                recordXtreamLiveOnboardingState(
                    provider = provider,
                    phase = XTREAM_ONBOARDING_PHASE_FAILED,
                    acceptedRowCount = liveCount,
                    lastError = "Live TV did not finish with any committed channels.",
                    clearStagedSession = true,
                    runtimeProfile = runtimeProfile
                )
            }
        }

        metadata = metadata.copy(
            lastMovieAttempt = if (movieCategoryCount > 0) now else metadata.lastMovieAttempt,
            movieCatalogStale = true,
            movieSyncMode = VodSyncMode.UNKNOWN
        )
        syncMetadataRepository.updateMetadata(metadata)

        val epgState = if (provider.epgSyncMode == ProviderEpgSyncMode.SKIP) "IDLE" else "QUEUED"
        upsertXtreamIndexJob(
            providerId = provider.id,
            section = "EPG",
            state = epgState,
            now = now,
            lastAttemptAt = if (epgState == "QUEUED") now else 0L
        )
        if (provider.epgSyncMode != ProviderEpgSyncMode.SKIP) {
            runCatching { scheduleBackgroundEpgSync(provider.id) }
                .onFailure { error ->
                    Log.w(TAG, "Failed to schedule Xtream background EPG sync for provider ${provider.id}: ${sanitizeThrowableMessage(error)}")
                    warnings += "EPG background sync could not be scheduled; it will retry on the next launch or manual sync."
                }
        }

        if (force) {
            Log.i(TAG, "Xtream index-first sync completed for provider ${provider.id}; VOD and series index jobs are queued.")
        }
        return if (warnings.isEmpty()) SyncOutcome() else SyncOutcome(partial = true, warnings = warnings.distinct())
    }

    private suspend fun recordXtreamLiveOnboardingState(
        provider: Provider,
        phase: String,
        now: Long = System.currentTimeMillis(),
        stagedSessionId: Long? = null,
        importStrategy: String? = null,
        nextCategoryIndex: Int? = null,
        acceptedRowCount: Int? = null,
        stagedFlushCount: Int? = null,
        lastError: String? = null,
        clearError: Boolean = false,
        completedAt: Long? = null,
        clearStagedSession: Boolean = false,
        runtimeProfile: CatalogSyncRuntimeProfile? = null,
        syncProfileStrategy: String? = null
    ) {
        val existing = xtreamLiveOnboardingDao.getByProvider(provider.id)
        xtreamLiveOnboardingDao.upsert(
            XtreamLiveOnboardingStateEntity(
                providerId = provider.id,
                providerType = provider.type.name,
                contentType = ContentType.LIVE.name,
                phase = phase,
                stagedSessionId = if (clearStagedSession) null else stagedSessionId ?: existing?.stagedSessionId,
                importStrategy = importStrategy ?: existing?.importStrategy,
                nextCategoryIndex = nextCategoryIndex ?: existing?.nextCategoryIndex ?: 0,
                acceptedRowCount = acceptedRowCount ?: existing?.acceptedRowCount ?: 0,
                stagedFlushCount = stagedFlushCount ?: existing?.stagedFlushCount ?: 0,
                syncProfileTier = runtimeProfile?.tier?.name ?: existing?.syncProfileTier,
                syncProfileBatchSize = runtimeProfile?.stageBatchSize ?: existing?.syncProfileBatchSize ?: 0,
                syncProfileStrategy = syncProfileStrategy ?: existing?.syncProfileStrategy,
                syncProfileLowMemory = runtimeProfile?.snapshot?.isCurrentlyLowOnMemory ?: existing?.syncProfileLowMemory ?: false,
                syncProfileMemoryClassMb = runtimeProfile?.snapshot?.memoryClassMb ?: existing?.syncProfileMemoryClassMb ?: 0,
                syncProfileAvailableMemMb = runtimeProfile?.snapshot?.availableMemMb ?: existing?.syncProfileAvailableMemMb ?: 0L,
                lastError = when {
                    clearError -> null
                    lastError != null -> lastError
                    else -> existing?.lastError
                },
                createdAt = existing?.createdAt?.takeIf { it > 0L } ?: now,
                updatedAt = now,
                completedAt = completedAt
            )
        )
    }

    private fun CatalogStrategyResult<*>.strategyNameOrNull(): String? = when (this) {
        is CatalogStrategyResult.Success -> strategyName
        is CatalogStrategyResult.Partial -> strategyName
        is CatalogStrategyResult.EmptyValid -> strategyName
        is CatalogStrategyResult.Failure -> strategyName
    }

    private fun stagedFlushCountFor(acceptedCount: Int): Int =
        if (acceptedCount <= 0) 0 else (acceptedCount + XTREAM_FALLBACK_STAGE_BATCH_SIZE - 1) / XTREAM_FALLBACK_STAGE_BATCH_SIZE

    private fun CatalogSyncPayload<Channel>.profileStrategyName(
        runtimeProfile: CatalogSyncRuntimeProfile,
        trackInitialLiveOnboarding: Boolean
    ): String {
        val baseStrategy = catalogResult.strategyNameOrNull() ?: "unknown"
        val fullPolicy = if (strategyFeedback.preferredSegmentedFirst) {
            "segmented_first"
        } else if (runtimeProfile.shouldAttemptFullLiveCatalog(trackInitialLiveOnboarding)) {
            "full_allowed"
        } else {
            "full_blocked"
        }
        return "$baseStrategy;$fullPolicy"
    }

    private suspend fun recoverXtreamLiveOnboardingSession(
        provider: Provider,
        hiddenLiveCategoryIds: Set<Long>
    ): XtreamLiveCommitResult? {
        val state = xtreamLiveOnboardingDao.getIncompleteByProvider(provider.id) ?: return null
        val sessionId = state.stagedSessionId ?: return null
        if (state.providerType != ProviderType.XTREAM_CODES.name || state.contentType != ContentType.LIVE.name) {
            discardXtreamLiveOnboardingSession(provider, sessionId, "Saved Live TV import did not match this provider.")
            return null
        }
        if (state.importStrategy != null && state.importStrategy != "full") {
            discardXtreamLiveOnboardingSession(provider, sessionId, "Saved Live TV import strategy could not be resumed.")
            return null
        }

        val stagedState = syncCatalogStore.stagedLiveImportState(provider.id, sessionId)
        val discardReason = when {
            stagedState.channelCount <= 0 -> "Saved Live TV import was missing staged channels."
            stagedState.movieCount > 0 || stagedState.seriesCount > 0 -> "Saved Live TV import contained rows for another catalog type."
            else -> null
        }
        if (discardReason != null) {
            discardXtreamLiveOnboardingSession(provider, sessionId, discardReason)
            return null
        }

        recordXtreamLiveOnboardingState(
            provider = provider,
            phase = XTREAM_ONBOARDING_PHASE_RECOVERING,
            stagedSessionId = sessionId,
            importStrategy = state.importStrategy ?: "full",
            acceptedRowCount = stagedState.channelCount,
            stagedFlushCount = stagedFlushCountFor(stagedState.channelCount),
            clearError = true
        )
        if (hiddenLiveCategoryIds.isNotEmpty()) {
            mergeHiddenChannelsIntoStaging(provider.id, sessionId, hiddenLiveCategoryIds)
        }
        val commitState = if (hiddenLiveCategoryIds.isNotEmpty()) {
            syncCatalogStore.stagedLiveImportState(provider.id, sessionId)
        } else {
            stagedState
        }
        recordXtreamLiveOnboardingState(
            provider = provider,
            phase = XTREAM_ONBOARDING_PHASE_COMMITTING,
            stagedSessionId = sessionId,
            importStrategy = state.importStrategy ?: "full",
            acceptedRowCount = stagedState.channelCount,
            stagedFlushCount = stagedFlushCountFor(stagedState.channelCount),
            clearError = true
        )
        syncCatalogStore.applyStagedLiveCatalog(
            providerId = provider.id,
            sessionId = sessionId,
            categories = commitState.categories.takeIf { it.isNotEmpty() }
        )
        return XtreamLiveCommitResult(
            acceptedCount = stagedState.channelCount,
            warnings = listOf("Live TV import resumed from saved staged session.")
        )
    }

    private suspend fun discardXtreamLiveOnboardingSession(
        provider: Provider,
        sessionId: Long,
        reason: String
    ) {
        Log.w(TAG, "Discarding saved Xtream Live onboarding session for provider ${provider.id}: $reason")
        syncCatalogStore.discardStagedImport(provider.id, sessionId)
        recordXtreamLiveOnboardingState(
            provider = provider,
            phase = XTREAM_ONBOARDING_PHASE_STARTING,
            lastError = reason,
            clearStagedSession = true
        )
    }

    private suspend fun syncXtreamCategoryShell(
        provider: Provider,
        api: XtreamProvider,
        contentType: ContentType,
        label: String,
        now: Long,
        onProgress: ((String) -> Unit)?
    ): kotlin.Result<Int> = runCatching {
        progress(provider.id, onProgress, "Loading $label categories...")
        upsertXtreamIndexJob(
            providerId = provider.id,
            section = contentType.name,
            state = "RUNNING",
            now = now,
            lastAttemptAt = now
        )
        val categories = when (contentType) {
            ContentType.MOVIE -> requireResult(api.getVodCategories(), "Failed to load VOD categories")
            ContentType.SERIES -> requireResult(api.getSeriesCategories(), "Failed to load series categories")
            else -> throw IllegalArgumentException("Unsupported Xtream category shell: $contentType")
        }
        syncCatalogStore.upsertCategories(
            providerId = provider.id,
            type = contentType.name,
            categories = categories.map { category -> category.toEntity(provider.id) }
        )
        upsertXtreamIndexJob(
            providerId = provider.id,
            section = contentType.name,
            state = "QUEUED",
            now = now,
            totalCategories = categories.size,
            completedCategories = 0,
            nextCategoryIndex = 0,
            failedCategories = 0,
            indexedRows = 0,
            skippedMalformedRows = 0,
            lastAttemptAt = now,
            lastError = null
        )
        categories.size
    }

    suspend fun processQueuedXtreamIndexJobs(
        providerId: Long,
        section: ContentType? = null,
        force: Boolean = false,
        maxCategoriesPerSection: Int? = null,
        onProgress: ((String) -> Unit)? = null
    ): com.streamvault.domain.model.Result<Unit> = withProviderLock(providerId) lock@{
        val providerEntity = providerDao.getById(providerId)
            ?: return@lock com.streamvault.domain.model.Result.error("Provider $providerId not found")
        if (providerEntity.type != ProviderType.XTREAM_CODES) {
            return@lock com.streamvault.domain.model.Result.success(Unit)
        }

        val provider = providerEntity
            .copy(password = credentialCrypto.decryptIfNeeded(providerEntity.password))
            .toDomain()
        val useTextClassification = preferencesRepository.useXtreamTextClassification.first()
        val enableBase64TextCompatibility = preferencesRepository.xtreamBase64TextCompatibility.first()
        val api = createXtreamSyncProvider(provider, useTextClassification, enableBase64TextCompatibility)
        val sections = when (section) {
            ContentType.MOVIE -> listOf(ContentType.MOVIE)
            ContentType.SERIES -> listOf(ContentType.SERIES)
            ContentType.LIVE -> listOf(ContentType.LIVE)
            ContentType.SERIES_EPISODE -> emptyList()
            null -> listOf(ContentType.LIVE, ContentType.MOVIE, ContentType.SERIES)
        }

        var sawRetryableFailure = false
        val warnings = mutableListOf<String>()
        val failures = mutableListOf<Throwable>()
        sections.forEach { contentType ->
            val job = xtreamIndexJobDao.get(providerId, contentType.name)
            if (!force && !shouldRunXtreamSummaryIndex(job)) {
                return@forEach
            }
            val failure = runCatching {
                when (contentType) {
                    ContentType.LIVE -> processXtreamLiveIndexBackfillSection(providerId, onProgress)
                    ContentType.MOVIE,
                    ContentType.SERIES -> processXtreamSummaryIndexSection(provider, api, contentType, maxCategoriesPerSection, onProgress)
                    ContentType.SERIES_EPISODE -> Unit
                }
            }.exceptionOrNull()
            if (failure != null) {
                val state = xtreamIndexFailureState(failure)
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

        if (warnings.isNotEmpty()) {
            val message = warnings.joinToString("; ")
            val cause = failures.firstOrNull()
            val exception = if (sawRetryableFailure) IOException(message, cause) else IllegalStateException(message, cause)
            com.streamvault.domain.model.Result.error(warnings.first(), exception)
        } else {
            com.streamvault.domain.model.Result.success(Unit)
        }
    }

    private fun shouldRunXtreamSummaryIndex(job: XtreamIndexJobEntity?): Boolean {
        if (job == null) return true
        if (job.state in setOf("QUEUED", "RUNNING", "PARTIAL", "STALE", "FAILED_RETRYABLE")) return true
        return ContentCachePolicy.shouldRefresh(job.lastSuccessAt, ContentCachePolicy.CATALOG_TTL_MILLIS)
    }

    suspend fun processQueuedStalkerIndexJobs(
        providerId: Long,
        section: ContentType? = null,
        force: Boolean = false,
        maxCategoriesPerSection: Int? = null,
        onProgress: ((String) -> Unit)? = null
    ): com.streamvault.domain.model.Result<Unit> {
        val providerMaxConnections = providerDao.getById(providerId)?.maxConnections ?: Int.MAX_VALUE
        return withStalkerSummaryLock(providerId, section, providerMaxConnections) lock@{
        val playbackDelayMillis = StalkerTrafficCoordinator.deferCatalogFetchMillis(providerId)
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

        val provider = providerEntity
            .copy(password = credentialCrypto.decryptIfNeeded(providerEntity.password))
            .toDomain()
        val api = createStalkerSyncProvider(provider)
        val decision = chooseNextStalkerCatalogSection(
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
                    initialDelaySeconds = decision.retryDelaySeconds
                )
            } else {
                scheduleStalkerEpgIfCatalogIdle(provider)
            }
            return@lock com.streamvault.domain.model.Result.success(Unit)
        }
        Log.i(TAG, "Stalker catalog worker selected ${contentType.name} for provider $providerId: ${decision.reason}")

        val warnings = mutableListOf<String>()
        val failures = mutableListOf<Throwable>()
        var sawRetryableFailure = false
        val categoryLimit = maxCategoriesPerSection?.coerceAtLeast(1) ?: STALKER_INDEX_CATEGORY_SLICE_SIZE
        val failure = runCatching {
            processStalkerSummaryIndexSection(
                provider = provider,
                api = api,
                contentType = contentType,
                maxCategories = categoryLimit,
                onProgress = onProgress
            )
        }.exceptionOrNull()
        if (failure != null) {
            val failedAt = System.currentTimeMillis()
            val state = stalkerIndexFailureState(failure)
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

        if (warnings.isNotEmpty()) {
            val message = warnings.joinToString("; ")
            val cause = failures.firstOrNull()
            val exception = if (sawRetryableFailure) IOException(message, cause) else IllegalStateException(message, cause)
            com.streamvault.domain.model.Result.error(warnings.first(), exception)
        } else {
            scheduleNextStalkerCatalogStep(provider, api)
            scheduleStalkerEpgIfCatalogIdle(provider)
            com.streamvault.domain.model.Result.success(Unit)
        }
        }
    }

    private data class StalkerCatalogDecision(
        val contentType: ContentType?,
        val retryDelaySeconds: Long = 0L,
        val reason: String
    )

    private data class StalkerCatalogSectionState(
        val contentType: ContentType,
        val runnable: Boolean,
        val retryDelaySeconds: Long,
        val pending: Boolean,
        val jobState: String?
    )

    private suspend fun chooseNextStalkerCatalogSection(
        provider: Provider,
        api: StalkerProvider,
        requestedSection: ContentType?,
        force: Boolean,
        now: Long
    ): StalkerCatalogDecision {
        val movie = stalkerCatalogSectionState(provider, api, ContentType.MOVIE, force, now)
        if (movie.runnable) {
            return StalkerCatalogDecision(ContentType.MOVIE, reason = "movies are runnable (${movie.jobState ?: "no job"})")
        }

        val series = stalkerCatalogSectionState(provider, api, ContentType.SERIES, force, now)
        if (series.runnable) {
            val reason = if (movie.pending && movie.retryDelaySeconds > 0L) {
                "movies are waiting ${movie.retryDelaySeconds}s for retry; series is runnable"
            } else {
                "movies are complete or terminal; series is runnable (${series.jobState ?: "no job"})"
            }
            return StalkerCatalogDecision(ContentType.SERIES, reason = reason)
        }

        val retryDelaySeconds = listOf(movie.retryDelaySeconds, series.retryDelaySeconds)
            .filter { it > 0L }
            .minOrNull()
            ?: 0L
        return StalkerCatalogDecision(
            contentType = null,
            retryDelaySeconds = retryDelaySeconds,
            reason = when {
                retryDelaySeconds > 0L -> "catalog is waiting ${retryDelaySeconds}s for retry"
                movie.pending || series.pending -> "catalog has no attemptable work"
                requestedSection == ContentType.MOVIE -> "requested movie section is not pending"
                requestedSection == ContentType.SERIES -> "requested series section is not pending"
                else -> "catalog is idle"
            }
        )
    }

    private suspend fun stalkerCatalogSectionState(
        provider: Provider,
        api: StalkerProvider,
        contentType: ContentType,
        force: Boolean,
        now: Long
    ): StalkerCatalogSectionState {
        val job = xtreamIndexJobDao.get(provider.id, contentType.name)
        if (!force && !shouldRunXtreamSummaryIndex(job)) {
            return StalkerCatalogSectionState(
                contentType = contentType,
                runnable = false,
                retryDelaySeconds = 0L,
                pending = false,
                jobState = job?.state
            )
        }

        val categories = categoryDao.getByProviderAndTypeSync(provider.id, contentType.name)
        val visibleCategories = visibleStalkerIndexCategories(
            contentType,
            allVisibleStalkerIndexCategories(provider.id, contentType, categories),
            api
        )
        if (visibleCategories.isEmpty()) {
            return StalkerCatalogSectionState(
                contentType = contentType,
                runnable = true,
                retryDelaySeconds = 0L,
                pending = true,
                jobState = job?.state
            )
        }

        val hydrations = visibleCategories.map { category ->
            getStalkerHydrationSnapshot(provider.id, contentType, category.categoryId)
        }
        val hasAttemptableWork = hydrations.any { hydration -> canAttemptStalkerCategory(hydration, now) }
        val retryDelaySeconds = nextRetryDelaySeconds(hydrations, now)
        val hasUnresolvedWork = hydrations.any { hydration ->
            hydration == null ||
                (!hydration.isComplete && !hydration.isTerminalFailure && hydration.retryBudgetRemaining > 0)
        }
        val needsStateReconciliation = job?.state in setOf("QUEUED", "RUNNING", "STALE", "FAILED_RETRYABLE") &&
            !hasUnresolvedWork
        return StalkerCatalogSectionState(
            contentType = contentType,
            runnable = hasAttemptableWork || needsStateReconciliation,
            retryDelaySeconds = retryDelaySeconds,
            pending = hasUnresolvedWork || needsStateReconciliation,
            jobState = job?.state
        )
    }

    private suspend fun scheduleNextStalkerCatalogStep(provider: Provider, api: StalkerProvider) {
        val decision = chooseNextStalkerCatalogSection(
            provider = provider,
            api = api,
            requestedSection = null,
            force = false,
            now = System.currentTimeMillis()
        )
        when {
            decision.contentType != null -> {
                Log.i(TAG, "Scheduling next Stalker catalog step for provider ${provider.id}: ${decision.contentType.name}")
                scheduleStalkerIndexSync(provider.id, section = null, force = false)
            }
            decision.retryDelaySeconds > 0L -> {
                Log.i(TAG, "Scheduling Stalker catalog retry for provider ${provider.id} in ${decision.retryDelaySeconds}s")
                scheduleStalkerIndexSync(
                    providerId = provider.id,
                    section = null,
                    force = false,
                    initialDelaySeconds = decision.retryDelaySeconds
                )
            }
        }
    }

    private suspend fun scheduleStalkerEpgIfCatalogIdle(provider: Provider) {
        if (provider.type != ProviderType.STALKER_PORTAL || provider.epgSyncMode == ProviderEpgSyncMode.SKIP) return
        if (!hasPendingStalkerCatalogIndex(provider.id)) {
            Log.i(TAG, "Scheduling Stalker EPG for provider ${provider.id}: catalog is idle.")
            scheduleBackgroundEpgSync(provider.id)
        } else {
            Log.i(TAG, "Deferring Stalker EPG for provider ${provider.id}: catalog indexing is still pending.")
        }
    }

    private suspend fun hasPendingStalkerCatalogIndex(providerId: Long): Boolean {
        val now = System.currentTimeMillis()
        return listOf(ContentType.MOVIE, ContentType.SERIES).any { contentType ->
            when (xtreamIndexJobDao.get(providerId, contentType.name)?.state) {
                "QUEUED",
                "RUNNING",
                "STALE",
                "FAILED_RETRYABLE" -> true
                "PARTIAL" -> hasRetryableStalkerCategoryWork(providerId, contentType, now)
                else -> false
            }
        }
    }

    private suspend fun hasRetryableStalkerCategoryWork(
        providerId: Long,
        contentType: ContentType,
        now: Long
    ): Boolean =
        categoryDao.getByProviderAndTypeSync(providerId, contentType.name).any { category ->
            val hydration = getStalkerHydrationSnapshot(providerId, contentType, category.categoryId)
            hydration == null ||
                (!hydration.isComplete &&
                    !hydration.isTerminalFailure &&
                    hydration.retryBudgetRemaining > 0)
        }

    private fun scheduleStalkerIndexContinuation(
        provider: Provider,
        section: ContentType,
        force: Boolean = false,
        initialDelaySeconds: Long = 0L
    ) {
        scheduleStalkerIndexSync(
            providerId = provider.id,
            section = null,
            force = force,
            initialDelaySeconds = initialDelaySeconds
        )
    }

    private fun nextRetryDelaySeconds(
        hydrations: Iterable<StalkerHydrationSnapshot?>,
        now: Long
    ): Long {
        val retryAt = hydrations
            .filterNotNull()
            .filter { hydration ->
                !hydration.isComplete &&
                    !hydration.isTerminalFailure &&
                    hydration.retryBudgetRemaining > 0 &&
                    hydration.retryAfterMs > now
            }
            .minOfOrNull { it.retryAfterMs }
            ?: return 0L
        return ((retryAt - now + 999L) / 1000L).coerceAtLeast(1L)
    }

    private suspend fun processStalkerSummaryIndexSection(
        provider: Provider,
        api: StalkerProvider,
        contentType: ContentType,
        maxCategories: Int,
        onProgress: ((String) -> Unit)?
    ) {
        require(contentType == ContentType.MOVIE || contentType == ContentType.SERIES) {
            "Unsupported Stalker summary index section: $contentType"
        }
        val now = System.currentTimeMillis()
        val categories = categoryDao.getByProviderAndTypeSync(provider.id, contentType.name)
        val allVisibleCategories = allVisibleStalkerIndexCategories(provider.id, contentType, categories)
        val visibleCategories = visibleStalkerIndexCategories(contentType, allVisibleCategories, api)
        val wildcardCategory = allVisibleCategories.firstOrNull { category ->
            api.isWildcardCategory(contentType, category.categoryId)
        }
        if (visibleCategories.isEmpty()) {
            val deletedRows = xtreamContentIndexDao.pruneStaleLocalContentRows(provider.id, contentType.name)
            val indexedRows = currentStalkerIndexedRowCount(provider.id, contentType)
            upsertXtreamIndexJob(
                providerId = provider.id,
                section = contentType.name,
                state = "SUCCESS",
                now = now,
                totalCategories = 0,
                completedCategories = 0,
                nextCategoryIndex = 0,
                failedCategories = 0,
                indexedRows = indexedRows,
                deletedPrunedRows = deletedRows,
                clearPriority = true,
                lastAttemptAt = now,
                lastSuccessAt = now,
                lastError = null
            )
            updateStalkerSummaryMetadata(provider.id, contentType, indexedRows, "SUCCESS", now)
            return
        }

        val initialJob = xtreamIndexJobDao.get(provider.id, contentType.name)

        if (wildcardCategory != null && visibleCategories.size == 1 && visibleCategories.first().categoryId == wildcardCategory.categoryId) {
            if (processStalkerWildcardIndexSection(
                    provider = provider,
                    api = api,
                    contentType = contentType,
                    wildcardCategory = wildcardCategory,
                    visibleCategories = allVisibleCategories,
                    maxPages = STALKER_WILDCARD_PAGE_SLICE_SIZE,
                    initialJob = initialJob,
                    onProgress = onProgress
                )) {
                return
            }
        }

        val priorityCategoryId = initialJob?.priorityCategoryId
        val hydrationByCategory = visibleCategories.associate { category ->
            category.categoryId to getStalkerHydrationSnapshot(provider.id, contentType, category.categoryId)
        }
        val completedBefore = hydrationByCategory.values.count { it?.isComplete == true }
        val failedBefore = hydrationByCategory.values.count { it?.isTerminalFailure == true }
        val indexedBefore = currentStalkerIndexedRowCount(provider.id, contentType)
        upsertXtreamIndexJob(
            providerId = provider.id,
            section = contentType.name,
            state = "RUNNING",
            now = now,
            totalCategories = visibleCategories.size,
            completedCategories = completedBefore,
            failedCategories = failedBefore,
            indexedRows = indexedBefore,
            lastAttemptAt = now,
            lastError = null
        )

        var restoreMovieWatchProgressPending = false

        try {
        val pending = buildList {
            priorityCategoryId
                ?.takeIf { requestedId -> visibleCategories.any { it.categoryId == requestedId } }
                ?.let { requestedId ->
                    val hydration = hydrationByCategory[requestedId]
                    if (canAttemptStalkerCategory(hydration, now)) {
                        visibleCategories.firstOrNull { it.categoryId == requestedId }?.let(::add)
                    }
                }
            visibleCategories.forEach { category ->
                if (size >= maxCategories) return@forEach
                if (any { it.categoryId == category.categoryId }) return@forEach
                val hydration = hydrationByCategory[category.categoryId]
                if (!canAttemptStalkerCategory(hydration, now)) return@forEach
                add(category)
            }
        }

        data class StalkerCategoryFetchAttempt(
            val category: CategoryEntity,
            val hydration: StalkerHydrationSnapshot?,
            val nextPage: Int
        )

        data class StalkerCategoryFetchResult(
            val attempt: StalkerCategoryFetchAttempt,
            val result: Result<com.streamvault.data.remote.stalker.StalkerPagedResult<out Any>>
        )

        data class StalkerSuccessfulPage(
            val category: CategoryEntity,
            val hydration: StalkerHydrationSnapshot?,
            val requestedPage: Int,
            val items: List<Any>,
            val totalPages: Int,
            val pageSize: Int,
            val isComplete: Boolean,
            val pageFingerprint: String?
        )

        var clearPriority = false
        var skippedMalformedRows = initialJob?.skippedMalformedRows ?: 0
        var pagesCommitted = 0
        var rowsCommitted = 0
        val sectionStartedAt = System.currentTimeMillis()
        val runtimeProfile = CatalogSyncRuntimeProfile.from(applicationContext)
        val parallelFetchLimit = stalkerCategoryFetchConcurrencyLimit(
            provider = provider,
            runtimeProfile = runtimeProfile,
            maxCategories = maxCategories
        )
        val runDeadlineAt = System.currentTimeMillis() + STALKER_MAX_SECTION_RUN_MILLIS
        val pendingQueue = ArrayDeque(pending)
        var forceSequential = parallelFetchLimit <= 1
        val retriedImmediately = mutableSetOf<Long>()
        while (pendingQueue.isNotEmpty() && System.currentTimeMillis() < runDeadlineAt) {
            if (StalkerTrafficCoordinator.shouldDeferCatalogFetch(provider.id)) {
                break
            }
            val windowSize = minOf(
                if (forceSequential) 1 else parallelFetchLimit,
                pendingQueue.size
            )
            val window = buildList {
                repeat(windowSize) {
                    add(pendingQueue.removeFirst())
                }
            }
            val attempts = window.mapNotNull { category ->
                val hydration = getStalkerHydrationSnapshot(provider.id, contentType, category.categoryId)
                if (!canAttemptStalkerCategory(hydration, System.currentTimeMillis())) {
                    return@mapNotNull null
                }
                val nextPage = nextStalkerAttemptPage(hydration)
                progress(
                    provider.id,
                    onProgress,
                    "Indexing ${xtreamIndexSectionLabel(contentType)}: ${category.name} page $nextPage"
                )
                markStalkerAttemptStarted(
                    providerId = provider.id,
                    contentType = contentType,
                    categoryId = category.categoryId,
                    hydration = hydration,
                    attemptedPage = nextPage,
                    now = System.currentTimeMillis()
                )
                StalkerCategoryFetchAttempt(
                    category = category,
                    hydration = hydration,
                    nextPage = nextPage
                )
            }
            if (attempts.isEmpty()) {
                continue
            }
            val fetchedResults = coroutineScope {
                attempts.map { attempt ->
                    async {
                        StalkerCategoryFetchResult(
                            attempt = attempt,
                            result = withStalkerFetchPermit(provider) {
                                fetchStalkerSummaryPageWithRecovery(
                                    api = api,
                                    contentType = contentType,
                                    categoryId = attempt.category.categoryId,
                                    page = attempt.nextPage
                                )
                            }
                        )
                    }
                }.awaitAll()
            }

            var windowRetryableFailures = 0
            val successfulPages = mutableListOf<StalkerSuccessfulPage>()
            for (fetched in fetchedResults) {
                val category = fetched.attempt.category
                val hydration = fetched.attempt.hydration
                val nextPage = fetched.attempt.nextPage
                when (val result = fetched.result) {
                is Result.Success -> {
                    val pageFingerprint = stalkerPageFingerprint(result.data.items, contentType)
                    val anomaly = detectStalkerPageAnomaly(
                        hydration = hydration,
                        requestedPage = nextPage,
                        pagedResult = result.data,
                        pageFingerprint = pageFingerprint
                    )
                    if (anomaly != null) {
                        val indexedAt = System.currentTimeMillis()
                        skippedMalformedRows += result.data.items.size
                        val shouldRetryImmediately = category.categoryId !in retriedImmediately &&
                            ((hydration?.failureCount ?: 0) == 0)
                        markStalkerAttemptFailed(
                            providerId = provider.id,
                            contentType = contentType,
                            categoryId = category.categoryId,
                            hydration = hydration,
                            attemptedPage = nextPage,
                            now = indexedAt,
                            message = anomaly,
                            retryable = true,
                            pageFingerprint = pageFingerprint
                        )
                        if (shouldRetryImmediately) {
                            retriedImmediately += category.categoryId
                            pendingQueue.addLast(category)
                        }
                        upsertXtreamIndexJob(
                            providerId = provider.id,
                            section = contentType.name,
                            state = "RUNNING",
                            now = indexedAt,
                            skippedMalformedRows = skippedMalformedRows,
                            lastAttemptAt = indexedAt,
                            lastError = anomaly
                        )
                        continue
                    }
                    val dedupedItems = dedupeStalkerPageItems(result.data.items, contentType)
                    skippedMalformedRows += (result.data.items.size - dedupedItems.size).coerceAtLeast(0)
                    successfulPages += StalkerSuccessfulPage(
                        category = category,
                        hydration = hydration,
                        requestedPage = nextPage,
                        items = dedupedItems,
                        totalPages = result.data.totalPages,
                        pageSize = result.data.pageSize,
                        isComplete = result.data.isComplete,
                        pageFingerprint = pageFingerprint
                    )
                }
                is Result.Error -> {
                    val failedAt = System.currentTimeMillis()
                    val retryable = stalkerIndexFailureState(result.exception ?: IllegalStateException(result.message)) != "FAILED_PERMANENT"
                    if (retryable) {
                        windowRetryableFailures += 1
                    }
                    val shouldRetryImmediately = retryable &&
                        category.categoryId !in retriedImmediately &&
                        ((hydration?.failureCount ?: 0) == 0)
                    markStalkerAttemptFailed(
                        providerId = provider.id,
                        contentType = contentType,
                        categoryId = category.categoryId,
                        hydration = hydration,
                        attemptedPage = nextPage,
                        now = failedAt,
                        message = result.message,
                        retryable = retryable,
                        pageFingerprint = hydration?.lastPageFingerprint
                    )
                    if (shouldRetryImmediately) {
                        retriedImmediately += category.categoryId
                        pendingQueue.addLast(category)
                    }
                    if (!retryable) {
                        throw IllegalStateException(result.message, result.exception)
                    }
                    upsertXtreamIndexJob(
                        providerId = provider.id,
                        section = contentType.name,
                        state = "RUNNING",
                        now = failedAt,
                        skippedMalformedRows = skippedMalformedRows,
                        lastAttemptAt = now,
                        lastError = result.message
                    )
                }
                is Result.Loading -> Unit
                }
                if (priorityCategoryId == category.categoryId) {
                    clearPriority = true
                }
            }
            if (successfulPages.isNotEmpty()) {
                val indexedAt = System.currentTimeMillis()
                when (contentType) {
                    ContentType.MOVIE -> upsertXtreamMovieSummaryBatch(
                        provider.id,
                        successfulPages.flatMap { page -> page.items.filterIsInstance<Movie>() },
                        indexedAt,
                        restoreWatchProgress = false
                    )
                    ContentType.SERIES -> upsertXtreamSeriesSummaryBatch(
                        provider.id,
                        successfulPages.flatMap { page -> page.items.filterIsInstance<Series>() },
                        indexedAt
                    )
                    else -> Unit
                }
                if (contentType == ContentType.MOVIE) {
                    restoreMovieWatchProgressPending = true
                }
                for (page in successfulPages) {
                    pagesCommitted += 1
                    rowsCommitted += page.items.size
                    val pageComplete = page.isComplete ||
                        (page.items.isEmpty() && page.totalPages in 1..page.requestedPage)
                    val categoryCount = (page.hydration?.itemCount ?: 0) + page.items.size
                    markStalkerAttemptSucceeded(
                        providerId = provider.id,
                        contentType = contentType,
                        categoryId = page.category.categoryId,
                        hydration = page.hydration,
                        attemptedPage = page.requestedPage,
                        now = indexedAt,
                        itemCount = categoryCount,
                        totalPages = page.totalPages,
                        pageSize = page.pageSize,
                        pageComplete = pageComplete,
                        pageFingerprint = page.pageFingerprint
                    )
                    if (!pageComplete && System.currentTimeMillis() < runDeadlineAt) {
                        pendingQueue.addLast(page.category)
                    }
                }
            }
            if (!forceSequential && windowRetryableFailures >= minOf(2, attempts.size)) {
                forceSequential = true
                Log.w(
                    TAG,
                    "Stalker ${contentType.name} category fetch downgraded to sequential mode for provider ${provider.id} after retryable parallel failures."
                )
            }
        }

        val refreshedHydration = visibleCategories.associate { category ->
            category.categoryId to getStalkerHydrationSnapshot(provider.id, contentType, category.categoryId)
        }
        val finishedAt = System.currentTimeMillis()
        val completedCategories = refreshedHydration.values.count { it?.isComplete == true }
        val failedCategories = refreshedHydration.values.count { it?.isTerminalFailure == true }
        val hasMoreCategories = refreshedHydration.values.any { hydration ->
            canAttemptStalkerCategory(hydration, finishedAt)
        }
        val retryDelaySeconds = nextRetryDelaySeconds(refreshedHydration.values, finishedAt)
        val indexedRows = currentStalkerIndexedRowCount(provider.id, contentType)
        val pruneSuppressed = refreshedHydration.values.any { hydration ->
            hydration?.hasPruneSuppressionRisk == true
        }
        val deletedRows = if (!hasMoreCategories && failedCategories == 0 && !pruneSuppressed) {
            xtreamContentIndexDao.pruneStaleLocalContentRows(provider.id, contentType.name)
        } else {
            0
        }
        val finalState = when {
            hasMoreCategories -> "QUEUED"
            failedCategories > 0 -> "PARTIAL"
            pruneSuppressed -> "PARTIAL"
            else -> "SUCCESS"
        }
        upsertXtreamIndexJob(
            providerId = provider.id,
            section = contentType.name,
            state = finalState,
            now = finishedAt,
            totalCategories = visibleCategories.size,
            completedCategories = completedCategories,
            nextCategoryIndex = completedCategories,
            failedCategories = failedCategories,
            indexedRows = indexedRows,
            skippedMalformedRows = skippedMalformedRows,
            deletedPrunedRows = deletedRows,
            clearPriority = clearPriority || finalState == "SUCCESS",
            lastAttemptAt = now,
            lastSuccessAt = finishedAt.takeIf { finalState == "SUCCESS" },
            lastError = refreshedHydration.values.firstOrNull { it?.lastStatus == "ERROR" }?.lastError
        )
        updateStalkerSummaryMetadata(provider.id, contentType, indexedRows, finalState, finishedAt)
        Log.i(
            TAG,
            "Stalker ${contentType.name} indexing finished for provider ${provider.id}: state=$finalState completed=$completedCategories failed=$failedCategories rows=$indexedRows committedPages=$pagesCommitted committedRows=$rowsCommitted throughput=${stalkerThroughputSummary(sectionStartedAt, pagesCommitted, rowsCommitted)} retryDelay=${retryDelaySeconds}s"
        )
        when {
            hasMoreCategories -> scheduleStalkerIndexContinuation(provider, contentType, force = false)
            finalState == "PARTIAL" -> {
                if (retryDelaySeconds > 0L) {
                    scheduleStalkerIndexContinuation(
                        provider = provider,
                        section = contentType,
                        force = false,
                        initialDelaySeconds = retryDelaySeconds
                    )
                }
            }
        }
        } finally {
            if (contentType == ContentType.MOVIE && restoreMovieWatchProgressPending) {
                movieDao.restoreWatchProgress(provider.id)
            }
        }
    }

    private fun stalkerCategoryFetchConcurrencyLimit(
        provider: Provider,
        runtimeProfile: CatalogSyncRuntimeProfile,
        maxCategories: Int
    ): Int {
        val runtimeLimit = runtimeProfile.maxCategoryConcurrency.coerceAtLeast(1)
        val providerLimit = provider.maxConnections.coerceAtLeast(1)
        val effectiveLimit = minOf(runtimeLimit, providerLimit)
        return minOf(
            maxCategories.coerceAtLeast(1),
            effectiveLimit,
            STALKER_MAX_PARALLEL_CATEGORY_FETCHES
        ).coerceAtLeast(1)
    }

    private fun stalkerThroughputSummary(
        startedAt: Long,
        pages: Int,
        rows: Int
    ): String {
        val elapsedSeconds = ((System.currentTimeMillis() - startedAt).coerceAtLeast(1L)) / 1000.0
        val pagesPerSecond = pages / elapsedSeconds
        val rowsPerSecond = rows / elapsedSeconds
        return "%.2f pages/s, %.2f rows/s".format(java.util.Locale.US, pagesPerSecond, rowsPerSecond)
    }

    private suspend fun processStalkerWildcardIndexSection(
        provider: Provider,
        api: StalkerProvider,
        contentType: ContentType,
        wildcardCategory: CategoryEntity,
        visibleCategories: List<CategoryEntity>,
        maxPages: Int,
        initialJob: XtreamIndexJobEntity?,
        onProgress: ((String) -> Unit)?
    ): Boolean {
        val now = System.currentTimeMillis()
        val normalVisibleCategoryIds = mutableSetOf<Long>()
        visibleCategories.forEach { category ->
            if (!api.isWildcardCategory(contentType, category.categoryId)) {
                normalVisibleCategoryIds += category.categoryId
            }
        }
        val visibleCategoryIds = normalVisibleCategoryIds.takeIf { it.isNotEmpty() }
        val hydration = getStalkerHydrationSnapshot(provider.id, contentType, wildcardCategory.categoryId)
        if (!canAttemptStalkerCategory(hydration, now)) return false

        upsertXtreamIndexJob(
            providerId = provider.id,
            section = contentType.name,
            state = "RUNNING",
            now = now,
            totalCategories = visibleCategories.size,
            completedCategories = 0,
            failedCategories = 0,
            indexedRows = currentStalkerIndexedRowCount(provider.id, contentType),
            lastAttemptAt = now,
            lastError = null
        )

        var skippedMalformedRows = initialJob?.skippedMalformedRows ?: 0
        val seenPageFingerprints = mutableSetOf<String>()
        var pagesProcessed = 0
        var rowsCommitted = 0
        var lastError: String? = null
        var restoreMovieWatchProgressPending = false
        var indexedRowsEstimate = currentStalkerIndexedRowCount(provider.id, contentType)
        val sectionStartedAt = System.currentTimeMillis()

        try {
        while (pagesProcessed < maxPages) {
            if (StalkerTrafficCoordinator.shouldDeferCatalogFetch(provider.id)) break
            val currentHydration = getStalkerHydrationSnapshot(provider.id, contentType, wildcardCategory.categoryId)
            if (!canAttemptStalkerCategory(currentHydration, System.currentTimeMillis())) break
            val nextPage = nextStalkerAttemptPage(currentHydration)
            progress(provider.id, onProgress, "Indexing ${xtreamIndexSectionLabel(contentType)}: All page $nextPage")
            markStalkerAttemptStarted(
                providerId = provider.id,
                contentType = contentType,
                categoryId = wildcardCategory.categoryId,
                hydration = currentHydration,
                attemptedPage = nextPage,
                now = System.currentTimeMillis()
            )
            when (val result = fetchStalkerWildcardSummaryPageWithRecovery(api, contentType, wildcardCategory.categoryId, nextPage)) {
                is Result.Success -> {
                    val indexedAt = System.currentTimeMillis()
                    val dedupedItems = dedupeStalkerPageItems(result.data.items, contentType)
                    val visibleItems = filterStalkerItemsToCategories(dedupedItems, contentType, visibleCategoryIds)
                    skippedMalformedRows += (result.data.items.size - visibleItems.size).coerceAtLeast(0)
                    if (nextPage == 1 && visibleItems.isEmpty()) {
                        val message = "Stalker wildcard catalog did not return usable visible ${xtreamIndexSectionLabel(contentType)} rows."
                        markStalkerAttemptFailed(
                            providerId = provider.id,
                            contentType = contentType,
                            categoryId = wildcardCategory.categoryId,
                            hydration = currentHydration,
                            attemptedPage = nextPage,
                            now = indexedAt,
                            message = message,
                            retryable = false,
                            pageFingerprint = null
                        )
                        Log.i(TAG, "$message Falling back to per-category indexing for provider ${provider.id}.")
                        return false
                    }

                    val pageFingerprint = stalkerPageFingerprint(visibleItems, contentType)
                    val anomaly = detectStalkerPageAnomaly(
                        hydration = currentHydration,
                        requestedPage = nextPage,
                        pagedResult = com.streamvault.data.remote.stalker.StalkerPagedResult(
                            items = visibleItems,
                            page = result.data.page,
                            totalPages = result.data.totalPages,
                            pageSize = result.data.pageSize
                        ),
                        pageFingerprint = pageFingerprint
                    ) ?: pageFingerprint
                        ?.takeIf { !seenPageFingerprints.add(it) }
                        ?.let { "Portal repeated a wildcard page payload." }

                    if (anomaly != null) {
                        markStalkerAttemptFailed(
                            providerId = provider.id,
                            contentType = contentType,
                            categoryId = wildcardCategory.categoryId,
                            hydration = currentHydration,
                            attemptedPage = nextPage,
                            now = indexedAt,
                            message = anomaly,
                            retryable = false,
                            pageFingerprint = pageFingerprint
                        )
                        Log.i(TAG, "Stalker wildcard ${contentType.name} indexing disabled for provider ${provider.id}: $anomaly")
                        return false
                    }

                    when (contentType) {
                        ContentType.MOVIE -> upsertXtreamMovieSummaryBatch(
                            provider.id,
                            visibleItems.filterIsInstance<Movie>(),
                            indexedAt,
                            restoreWatchProgress = false
                        )
                        ContentType.SERIES -> upsertXtreamSeriesSummaryBatch(provider.id, visibleItems.filterIsInstance<Series>(), indexedAt)
                        else -> Unit
                    }
                    if (contentType == ContentType.MOVIE && visibleItems.isNotEmpty()) {
                        restoreMovieWatchProgressPending = true
                    }
                    indexedRowsEstimate += visibleItems.size
                    rowsCommitted += visibleItems.size

                    val pageComplete = result.data.isComplete ||
                        (result.data.items.isEmpty() && result.data.totalPages in 1..nextPage)
                    markStalkerAttemptSucceeded(
                        providerId = provider.id,
                        contentType = contentType,
                        categoryId = wildcardCategory.categoryId,
                        hydration = currentHydration,
                        attemptedPage = nextPage,
                        now = indexedAt,
                        itemCount = indexedRowsEstimate,
                        totalPages = result.data.totalPages,
                        pageSize = result.data.pageSize,
                        pageComplete = pageComplete,
                        pageFingerprint = pageFingerprint
                    )
                    pagesProcessed += 1
                    if (pageComplete) break
                }
                is Result.Error -> {
                    val failedAt = System.currentTimeMillis()
                    lastError = result.message
                    val retryable = stalkerIndexFailureState(result.exception ?: IllegalStateException(result.message)) != "FAILED_PERMANENT"
                    markStalkerAttemptFailed(
                        providerId = provider.id,
                        contentType = contentType,
                        categoryId = wildcardCategory.categoryId,
                        hydration = currentHydration,
                        attemptedPage = nextPage,
                        now = failedAt,
                        message = result.message,
                        retryable = retryable,
                        pageFingerprint = currentHydration?.lastPageFingerprint
                    )
                    return false
                }
                is Result.Loading -> Unit
            }
        }

        val finishedAt = System.currentTimeMillis()
        val refreshedHydration = getStalkerHydrationSnapshot(provider.id, contentType, wildcardCategory.categoryId)
        val hasMorePages = canAttemptStalkerCategory(refreshedHydration, finishedAt)
        val indexedRows = currentStalkerIndexedRowCount(provider.id, contentType)
        val finalState = when {
            hasMorePages -> "QUEUED"
            refreshedHydration?.isTerminalFailure == true -> "PARTIAL"
            refreshedHydration?.hasPruneSuppressionRisk == true -> "PARTIAL"
            else -> "SUCCESS"
        }
        val deletedRows = if (finalState == "SUCCESS") {
            xtreamContentIndexDao.pruneStaleLocalContentRows(provider.id, contentType.name)
        } else {
            0
        }
        upsertXtreamIndexJob(
            providerId = provider.id,
            section = contentType.name,
            state = finalState,
            now = finishedAt,
            totalCategories = visibleCategories.size,
            completedCategories = if (finalState == "SUCCESS") visibleCategories.size else 0,
            nextCategoryIndex = if (finalState == "SUCCESS") visibleCategories.size else 0,
            failedCategories = if (finalState == "PARTIAL") 1 else 0,
            indexedRows = indexedRows,
            skippedMalformedRows = skippedMalformedRows,
            deletedPrunedRows = deletedRows,
            clearPriority = finalState == "SUCCESS",
            lastAttemptAt = now,
            lastSuccessAt = finishedAt.takeIf { finalState == "SUCCESS" },
            lastError = lastError ?: refreshedHydration?.lastError
        )
        updateStalkerSummaryMetadata(provider.id, contentType, indexedRows, finalState, finishedAt)
        Log.i(
            TAG,
            "Stalker wildcard ${contentType.name} indexing finished for provider ${provider.id}: state=$finalState pages=$pagesProcessed rows=$rowsCommitted totalRows=$indexedRows throughput=${stalkerThroughputSummary(sectionStartedAt, pagesProcessed, rowsCommitted)}"
        )
        when {
            hasMorePages -> scheduleStalkerIndexContinuation(provider, contentType, force = false)
            finalState == "PARTIAL" -> {
                val delaySeconds = nextRetryDelaySeconds(listOf(refreshedHydration), finishedAt)
                if (delaySeconds > 0L) {
                    scheduleStalkerIndexContinuation(
                        provider = provider,
                        section = contentType,
                        force = false,
                        initialDelaySeconds = delaySeconds
                    )
                }
            }
        }
        return true
        } finally {
            if (contentType == ContentType.MOVIE && restoreMovieWatchProgressPending) {
                movieDao.restoreWatchProgress(provider.id)
            }
        }
    }

    private data class StalkerHydrationSnapshot(
        val lastHydratedAt: Long,
        val itemCount: Int,
        val lastStatus: String,
        val lastError: String?,
        val lastLoadedPage: Int,
        val lastAttemptedPage: Int,
        val lastSuccessfulPage: Int,
        val totalPages: Int,
        val isComplete: Boolean,
        val pageSize: Int,
        val retryAfterMs: Long,
        val failureCount: Int,
        val retryBudgetRemaining: Int,
        val lastPageFingerprint: String?
    )

    private val StalkerHydrationSnapshot.isTerminalFailure: Boolean
        get() = lastStatus in setOf("FAILED_PERMANENT", "FAILED_BUDGET_EXHAUSTED")

    private val StalkerHydrationSnapshot.hasPruneSuppressionRisk: Boolean
        get() = lastStatus in setOf("ANOMALY", "FAILED_RETRYABLE", "COOLDOWN")

    private suspend fun getStalkerHydrationSnapshot(
        providerId: Long,
        contentType: ContentType,
        categoryId: Long
    ): StalkerHydrationSnapshot? = when (contentType) {
        ContentType.MOVIE -> movieCategoryHydrationDao.get(providerId, categoryId)?.let { hydration ->
            StalkerHydrationSnapshot(
                lastHydratedAt = hydration.lastHydratedAt,
                itemCount = hydration.itemCount,
                lastStatus = hydration.lastStatus,
                lastError = hydration.lastError,
                lastLoadedPage = hydration.lastLoadedPage,
                lastAttemptedPage = hydration.lastAttemptedPage,
                lastSuccessfulPage = hydration.lastSuccessfulPage,
                totalPages = hydration.totalPages,
                isComplete = hydration.isComplete,
                pageSize = hydration.pageSize,
                retryAfterMs = hydration.retryAfterMs,
                failureCount = hydration.failureCount,
                retryBudgetRemaining = hydration.retryBudgetRemaining,
                lastPageFingerprint = hydration.lastPageFingerprint
            )
        }
        ContentType.SERIES -> seriesCategoryHydrationDao.get(providerId, categoryId)?.let { hydration ->
            StalkerHydrationSnapshot(
                lastHydratedAt = hydration.lastHydratedAt,
                itemCount = hydration.itemCount,
                lastStatus = hydration.lastStatus,
                lastError = hydration.lastError,
                lastLoadedPage = hydration.lastLoadedPage,
                lastAttemptedPage = hydration.lastAttemptedPage,
                lastSuccessfulPage = hydration.lastSuccessfulPage,
                totalPages = hydration.totalPages,
                isComplete = hydration.isComplete,
                pageSize = hydration.pageSize,
                retryAfterMs = hydration.retryAfterMs,
                failureCount = hydration.failureCount,
                retryBudgetRemaining = hydration.retryBudgetRemaining,
                lastPageFingerprint = hydration.lastPageFingerprint
            )
        }
        else -> null
    }

    private fun canAttemptStalkerCategory(
        hydration: StalkerHydrationSnapshot?,
        finishedAt: Long
    ): Boolean {
        if (hydration == null) return true
        if (hydration.isComplete) return false
        if (hydration.isTerminalFailure) return false
        if (hydration.retryBudgetRemaining <= 0) return false
        if (hydration.retryAfterMs > finishedAt) return false
        return true
    }

    private fun nextStalkerAttemptPage(hydration: StalkerHydrationSnapshot?): Int {
        if (hydration == null) return 1
        if (hydration.lastStatus in setOf("FAILED_RETRYABLE", "COOLDOWN", "ANOMALY")) {
            return hydration.lastAttemptedPage.coerceAtLeast(1)
        }
        return (hydration.lastSuccessfulPage + 1).coerceAtLeast(1)
    }

    private suspend fun markStalkerAttemptStarted(
        providerId: Long,
        contentType: ContentType,
        categoryId: Long,
        hydration: StalkerHydrationSnapshot?,
        attemptedPage: Int,
        now: Long
    ) {
        when (contentType) {
            ContentType.MOVIE -> movieCategoryHydrationDao.upsert(
                com.streamvault.data.local.entity.MovieCategoryHydrationEntity(
                    providerId = providerId,
                    categoryId = categoryId,
                    lastHydratedAt = hydration?.lastHydratedAt ?: 0L,
                    itemCount = hydration?.itemCount ?: 0,
                    lastStatus = "RUNNING",
                    lastError = null,
                    lastLoadedPage = hydration?.lastLoadedPage ?: 0,
                    lastAttemptedPage = attemptedPage,
                    lastSuccessfulPage = hydration?.lastSuccessfulPage ?: 0,
                    totalPages = hydration?.totalPages ?: 0,
                    isComplete = hydration?.isComplete ?: false,
                    pageSize = hydration?.pageSize ?: 0,
                    retryAfterMs = 0L,
                    failureCount = hydration?.failureCount ?: 0,
                    retryBudgetRemaining = hydration?.retryBudgetRemaining ?: STALKER_CATEGORY_RETRY_BUDGET,
                    lastPageFingerprint = hydration?.lastPageFingerprint
                )
            )
            ContentType.SERIES -> seriesCategoryHydrationDao.upsert(
                com.streamvault.data.local.entity.SeriesCategoryHydrationEntity(
                    providerId = providerId,
                    categoryId = categoryId,
                    lastHydratedAt = hydration?.lastHydratedAt ?: 0L,
                    itemCount = hydration?.itemCount ?: 0,
                    lastStatus = "RUNNING",
                    lastError = null,
                    lastLoadedPage = hydration?.lastLoadedPage ?: 0,
                    lastAttemptedPage = attemptedPage,
                    lastSuccessfulPage = hydration?.lastSuccessfulPage ?: 0,
                    totalPages = hydration?.totalPages ?: 0,
                    isComplete = hydration?.isComplete ?: false,
                    pageSize = hydration?.pageSize ?: 0,
                    retryAfterMs = 0L,
                    failureCount = hydration?.failureCount ?: 0,
                    retryBudgetRemaining = hydration?.retryBudgetRemaining ?: STALKER_CATEGORY_RETRY_BUDGET,
                    lastPageFingerprint = hydration?.lastPageFingerprint
                )
            )
            else -> Unit
        }
    }

    private suspend fun markStalkerAttemptSucceeded(
        providerId: Long,
        contentType: ContentType,
        categoryId: Long,
        hydration: StalkerHydrationSnapshot?,
        attemptedPage: Int,
        now: Long,
        itemCount: Int,
        totalPages: Int,
        pageSize: Int,
        pageComplete: Boolean,
        pageFingerprint: String?
    ) {
        when (contentType) {
            ContentType.MOVIE -> movieCategoryHydrationDao.upsert(
                com.streamvault.data.local.entity.MovieCategoryHydrationEntity(
                    providerId = providerId,
                    categoryId = categoryId,
                    lastHydratedAt = now,
                    itemCount = itemCount,
                    lastStatus = "SUCCESS",
                    lastError = null,
                    lastLoadedPage = attemptedPage,
                    lastAttemptedPage = attemptedPage,
                    lastSuccessfulPage = attemptedPage,
                    totalPages = totalPages,
                    isComplete = pageComplete,
                    pageSize = pageSize,
                    retryAfterMs = 0L,
                    failureCount = 0,
                    retryBudgetRemaining = STALKER_CATEGORY_RETRY_BUDGET,
                    lastPageFingerprint = pageFingerprint
                )
            )
            ContentType.SERIES -> seriesCategoryHydrationDao.upsert(
                com.streamvault.data.local.entity.SeriesCategoryHydrationEntity(
                    providerId = providerId,
                    categoryId = categoryId,
                    lastHydratedAt = now,
                    itemCount = itemCount,
                    lastStatus = "SUCCESS",
                    lastError = null,
                    lastLoadedPage = attemptedPage,
                    lastAttemptedPage = attemptedPage,
                    lastSuccessfulPage = attemptedPage,
                    totalPages = totalPages,
                    isComplete = pageComplete,
                    pageSize = pageSize,
                    retryAfterMs = 0L,
                    failureCount = 0,
                    retryBudgetRemaining = STALKER_CATEGORY_RETRY_BUDGET,
                    lastPageFingerprint = pageFingerprint
                )
            )
            else -> Unit
        }
    }

    private suspend fun markStalkerAttemptFailed(
        providerId: Long,
        contentType: ContentType,
        categoryId: Long,
        hydration: StalkerHydrationSnapshot?,
        attemptedPage: Int,
        now: Long,
        message: String,
        retryable: Boolean,
        pageFingerprint: String?
    ) {
        val priorFailures = hydration?.failureCount ?: 0
        val remainingBudget = if (retryable) {
            ((hydration?.retryBudgetRemaining ?: STALKER_CATEGORY_RETRY_BUDGET) - 1).coerceAtLeast(0)
        } else {
            0
        }
        val nextStatus = when {
            !retryable -> "FAILED_PERMANENT"
            remainingBudget <= 0 -> "FAILED_BUDGET_EXHAUSTED"
            else -> "FAILED_RETRYABLE"
        }
        val retryAfterMs = if (nextStatus == "FAILED_RETRYABLE" && priorFailures > 0) {
            now + STALKER_CATEGORY_RETRY_COOLDOWN_MILLIS * (priorFailures + 1).coerceAtLeast(1)
        } else {
            0L
        }
        when (contentType) {
            ContentType.MOVIE -> movieCategoryHydrationDao.upsert(
                com.streamvault.data.local.entity.MovieCategoryHydrationEntity(
                    providerId = providerId,
                    categoryId = categoryId,
                    lastHydratedAt = hydration?.lastHydratedAt ?: 0L,
                    itemCount = hydration?.itemCount ?: 0,
                    lastStatus = nextStatus,
                    lastError = message,
                    lastLoadedPage = hydration?.lastLoadedPage ?: 0,
                    lastAttemptedPage = attemptedPage,
                    lastSuccessfulPage = hydration?.lastSuccessfulPage ?: 0,
                    totalPages = hydration?.totalPages ?: 0,
                    isComplete = hydration?.isComplete ?: false,
                    pageSize = hydration?.pageSize ?: 0,
                    retryAfterMs = retryAfterMs,
                    failureCount = priorFailures + 1,
                    retryBudgetRemaining = remainingBudget,
                    lastPageFingerprint = pageFingerprint
                )
            )
            ContentType.SERIES -> seriesCategoryHydrationDao.upsert(
                com.streamvault.data.local.entity.SeriesCategoryHydrationEntity(
                    providerId = providerId,
                    categoryId = categoryId,
                    lastHydratedAt = hydration?.lastHydratedAt ?: 0L,
                    itemCount = hydration?.itemCount ?: 0,
                    lastStatus = nextStatus,
                    lastError = message,
                    lastLoadedPage = hydration?.lastLoadedPage ?: 0,
                    lastAttemptedPage = attemptedPage,
                    lastSuccessfulPage = hydration?.lastSuccessfulPage ?: 0,
                    totalPages = hydration?.totalPages ?: 0,
                    isComplete = hydration?.isComplete ?: false,
                    pageSize = hydration?.pageSize ?: 0,
                    retryAfterMs = retryAfterMs,
                    failureCount = priorFailures + 1,
                    retryBudgetRemaining = remainingBudget,
                    lastPageFingerprint = pageFingerprint
                )
            )
            else -> Unit
        }
    }

    private suspend fun fetchStalkerSummaryPageWithRecovery(
        api: StalkerProvider,
        contentType: ContentType,
        categoryId: Long,
        page: Int
    ): Result<com.streamvault.data.remote.stalker.StalkerPagedResult<out Any>> {
        val initial = fetchStalkerSummaryPage(api, contentType, categoryId, page)
        val recovered = if (initial is Result.Error && isLikelyStalkerAuthFailure(initial.message, initial.exception)) {
            Log.w(
                TAG,
                "Retrying Stalker ${contentType.name} page $page after auth refresh for category $categoryId"
            )
            api.invalidateAuthentication()
            fetchStalkerSummaryPage(api, contentType, categoryId, page)
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
        if (initial is Result.Error && isLikelyStalkerAuthFailure(initial.message, initial.exception)) {
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
    ): String? {
        if (pagedResult.page != requestedPage) {
            return "Portal returned page ${pagedResult.page} while page $requestedPage was requested."
        }
        if (hydration != null && hydration.totalPages > 0 && pagedResult.totalPages > 0) {
            if (pagedResult.totalPages < hydration.lastSuccessfulPage) {
                return "Portal page count regressed below the last successful page."
            }
            if (
                hydration.lastAttemptedPage == requestedPage &&
                hydration.lastPageFingerprint != null &&
                hydration.lastPageFingerprint == pageFingerprint &&
                hydration.lastStatus in setOf("FAILED_RETRYABLE", "RUNNING", "ANOMALY")
            ) {
                return "Portal repeated the same page payload for page $requestedPage."
            }
        }
        if (pagedResult.items.isEmpty() && pagedResult.totalPages > requestedPage) {
            return "Portal returned an empty mid-catalog page for page $requestedPage."
        }
        return null
    }

    private fun stalkerPageFingerprint(
        items: List<out Any>,
        contentType: ContentType
    ): String? {
        if (items.isEmpty()) return "empty"
        val seeds = when (contentType) {
            ContentType.MOVIE -> items.filterIsInstance<Movie>().map { movie ->
                "${movie.streamId}:${movie.categoryId}:${movie.addedAt}"
            }
            ContentType.SERIES -> items.filterIsInstance<Series>().map { series ->
                "${series.providerSeriesId ?: series.seriesId}:${series.categoryId}:${series.lastModified}"
            }
            else -> emptyList()
        }
        if (seeds.isEmpty()) return null
        return sha1Hex(seeds.joinToString("|"))
    }

    private fun dedupeStalkerPageItems(
        items: List<out Any>,
        contentType: ContentType
    ): List<out Any> = when (contentType) {
        ContentType.MOVIE -> items.filterIsInstance<Movie>().distinctBy(Movie::streamId)
        ContentType.SERIES -> items.filterIsInstance<Series>().distinctBy { it.providerSeriesId ?: it.seriesId.toString() }
        else -> items
    }

    private fun filterStalkerItemsToCategories(
        items: List<out Any>,
        contentType: ContentType,
        visibleCategoryIds: Set<Long>?
    ): List<out Any> = when (contentType) {
        ContentType.MOVIE -> items.filterIsInstance<Movie>().filter { movie ->
            visibleCategoryIds == null || movie.categoryId in visibleCategoryIds
        }
        ContentType.SERIES -> items.filterIsInstance<Series>().filter { series ->
            visibleCategoryIds == null || series.categoryId in visibleCategoryIds
        }
        else -> items
    }

    private fun isLikelyStalkerAuthFailure(message: String, exception: Throwable?): Boolean {
        val normalizedMessage = message.lowercase()
        val exceptionMessage = exception?.message?.lowercase().orEmpty()
        return normalizedMessage.contains("http 401") ||
            normalizedMessage.contains("http 403") ||
            normalizedMessage.contains("token") ||
            normalizedMessage.contains("authorization") ||
            exceptionMessage.contains("http 401") ||
            exceptionMessage.contains("http 403") ||
            exceptionMessage.contains("token") ||
            exceptionMessage.contains("authorization")
    }

    private fun sha1Hex(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-1").digest(value.toByteArray(Charsets.UTF_8))
        return buildString(bytes.size * 2) {
            bytes.forEach { byte -> append("%02x".format(byte)) }
        }
    }

    private fun stalkerIndexFailureState(error: Throwable): String {
        if (isLikelyStalkerAuthFailure(sanitizeThrowableMessage(error), error)) {
            return "FAILED_PERMANENT"
        }
        val message = sanitizeThrowableMessage(error).lowercase()
        if (message.contains("not accessible") || message.contains("no accessible catalog")) {
            return "FAILED_PERMANENT"
        }
        return "FAILED_RETRYABLE"
    }

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
        return categories.filterNot { category -> category.categoryId in hiddenCategoryIds }
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
        val metadata = syncMetadataRepository.getMetadata(providerId) ?: SyncMetadata(providerId)
        when (contentType) {
            ContentType.MOVIE -> syncMetadataRepository.updateMetadata(
                metadata.copy(
                    lastMovieSync = now,
                    lastMovieAttempt = now,
                    lastMovieSuccess = if (finalState == "SUCCESS") now else metadata.lastMovieSuccess,
                    lastMoviePartial = if (finalState != "SUCCESS") now else metadata.lastMoviePartial,
                    movieCount = if (finalState == "SUCCESS") indexedRows else metadata.movieCount.coerceAtLeast(indexedRows),
                    movieCatalogStale = finalState != "SUCCESS",
                    movieSyncMode = VodSyncMode.PAGED
                )
            )
            ContentType.SERIES -> syncMetadataRepository.updateMetadata(
                metadata.copy(
                    lastSeriesSync = now,
                    lastSeriesSuccess = if (finalState == "SUCCESS") now else metadata.lastSeriesSuccess,
                    seriesCount = if (finalState == "SUCCESS") indexedRows else metadata.seriesCount.coerceAtLeast(indexedRows)
                )
            )
            else -> Unit
        }
    }

    private suspend fun processXtreamSummaryIndexSection(
        provider: Provider,
        api: XtreamProvider,
        contentType: ContentType,
        maxCategories: Int?,
        onProgress: ((String) -> Unit)?
    ) {
        require(contentType == ContentType.MOVIE || contentType == ContentType.SERIES) {
            "Unsupported Xtream summary index section: $contentType"
        }
        val now = System.currentTimeMillis()
        val categories = categoryDao.getByProviderAndTypeSync(provider.id, contentType.name)
        if (categories.isEmpty()) {
            syncXtreamCategoryShell(
                provider = provider,
                api = api,
                contentType = contentType,
                label = xtreamIndexSectionLabel(contentType),
                now = now,
                onProgress = onProgress
            ).getOrThrow()
        }
        val initialJob = xtreamIndexJobDao.get(provider.id, contentType.name)
        val priorityCategoryId = initialJob?.priorityCategoryId
        val indexedCategories = categoryDao.getByProviderAndTypeSync(provider.id, contentType.name)
        val resumeJob = initialJob?.takeIf { it.state in setOf("QUEUED", "RUNNING", "PARTIAL") }
        val categoryLimit = maxCategories?.coerceAtLeast(1) ?: Int.MAX_VALUE
        var nextCategoryIndex = resumeJob?.nextCategoryIndex?.coerceIn(0, indexedCategories.size) ?: 0
        var restoreMovieWatchProgressPending = false
        try {
        if (shouldAttemptFullXtreamSummaryStream(resumeJob, priorityCategoryId)) {
            when (val streamed = streamFullXtreamSummaryIndex(
                provider = provider,
                api = api,
                contentType = contentType,
                categories = indexedCategories,
                totalCategories = indexedCategories.size,
                now = now,
                onProgress = onProgress
            )) {
                is Result.Success -> return
                is Result.Error -> {
                    Log.w(
                        TAG,
                        "Full ${contentType.name} stream index failed for provider ${provider.id}; falling back to category slices: ${streamed.message}"
                    )
                    upsertXtreamIndexJob(
                        providerId = provider.id,
                        section = contentType.name,
                        state = "QUEUED",
                        now = System.currentTimeMillis(),
                        totalCategories = indexedCategories.size,
                        completedCategories = 0,
                        nextCategoryIndex = 0,
                        failedCategories = 0,
                        lastAttemptAt = now,
                        lastError = streamed.message
                    )
                    nextCategoryIndex = 0
                }
                Result.Loading -> Unit
            }
        }
        upsertXtreamIndexJob(
            providerId = provider.id,
            section = contentType.name,
            state = "RUNNING",
            now = now,
            totalCategories = indexedCategories.size,
            completedCategories = resumeJob?.completedCategories ?: 0,
            nextCategoryIndex = nextCategoryIndex,
            failedCategories = resumeJob?.failedCategories ?: 0,
            indexedRows = resumeJob?.indexedRows ?: 0,
            skippedMalformedRows = resumeJob?.skippedMalformedRows ?: 0,
            lastAttemptAt = now,
            lastError = null
        )

        var completedCategories = resumeJob?.completedCategories ?: 0
        var failedCategories = resumeJob?.failedCategories ?: 0
        var indexedRows = resumeJob?.indexedRows ?: 0
        var skippedMalformedRows = resumeJob?.skippedMalformedRows ?: 0
        var lastError: Throwable? = null
        var priorityHandled = false

        data class CategoryIndexWork(
            val category: CategoryEntity,
            val advancesCursor: Boolean
        )

        val workItems = buildList {
            val priorityIndex = priorityCategoryId?.let { id ->
                indexedCategories.indexOfFirst { it.categoryId == id }.takeIf { index -> index >= 0 }
            }
            if (priorityIndex != null && priorityIndex != nextCategoryIndex && size < categoryLimit) {
                add(CategoryIndexWork(indexedCategories[priorityIndex], advancesCursor = false))
            }
            while (nextCategoryIndex < indexedCategories.size && size < categoryLimit) {
                val category = indexedCategories[nextCategoryIndex]
                nextCategoryIndex++
                if (
                    priorityCategoryId != null &&
                    category.categoryId == priorityCategoryId &&
                    any { it.category.categoryId == priorityCategoryId }
                ) {
                    continue
                }
                add(CategoryIndexWork(category, advancesCursor = true))
            }
        }

        for (workItem in workItems) {
            val category = workItem.category
            progress(provider.id, onProgress, "Indexing ${xtreamIndexSectionLabel(contentType)}: ${category.name}")
            val outcome = when (contentType) {
                ContentType.MOVIE -> fetchMovieCategoryOutcome(provider, api, category.toXtreamCategory())
                ContentType.SERIES -> fetchSeriesCategoryOutcome(provider, api, category.toXtreamCategory())
                else -> error("Unsupported section")
            }
            when (val categoryOutcome = outcome.outcome) {
                is CategoryFetchOutcome.Success -> {
                    val accepted = when (contentType) {
                        ContentType.MOVIE -> upsertXtreamMovieSummaryBatch(
                            providerId = provider.id,
                            movies = categoryOutcome.items.filterIsInstance<Movie>(),
                            indexedAt = System.currentTimeMillis(),
                            restoreWatchProgress = false
                        )
                        ContentType.SERIES -> upsertXtreamSeriesSummaryBatch(
                            providerId = provider.id,
                            series = categoryOutcome.items.filterIsInstance<Series>(),
                            indexedAt = System.currentTimeMillis()
                        )
                        else -> 0
                    }
                    if (workItem.advancesCursor) {
                        indexedRows += accepted
                        skippedMalformedRows += (categoryOutcome.rawCount - categoryOutcome.items.size).coerceAtLeast(0)
                        completedCategories++
                    }
                    if (contentType == ContentType.MOVIE && accepted > 0) {
                        restoreMovieWatchProgressPending = true
                    }
                    if (category.categoryId == priorityCategoryId) {
                        priorityHandled = true
                        upsertXtreamIndexJob(
                            providerId = provider.id,
                            section = contentType.name,
                            state = "RUNNING",
                            now = System.currentTimeMillis(),
                            clearPriority = true
                        )
                    }
                }
                is CategoryFetchOutcome.Empty -> {
                    if (workItem.advancesCursor) {
                        completedCategories++
                    }
                    if (category.categoryId == priorityCategoryId) {
                        priorityHandled = true
                        upsertXtreamIndexJob(
                            providerId = provider.id,
                            section = contentType.name,
                            state = "RUNNING",
                            now = System.currentTimeMillis(),
                            clearPriority = true
                        )
                    }
                }
                is CategoryFetchOutcome.Failure -> {
                    if (workItem.advancesCursor) {
                        failedCategories++
                    }
                    lastError = categoryOutcome.error
                }
            }
            upsertXtreamIndexJob(
                providerId = provider.id,
                section = contentType.name,
                state = if (failedCategories > 0) "PARTIAL" else "RUNNING",
                now = System.currentTimeMillis(),
                totalCategories = indexedCategories.size,
                completedCategories = completedCategories,
                nextCategoryIndex = nextCategoryIndex,
                failedCategories = failedCategories,
                indexedRows = indexedRows,
                skippedMalformedRows = skippedMalformedRows,
                lastAttemptAt = now,
                lastError = lastError?.let(::sanitizeThrowableMessage)
            )
        }

        val finishedAt = System.currentTimeMillis()
        val hasMoreCategories = nextCategoryIndex < indexedCategories.size
        val finalState = when {
            hasMoreCategories -> "QUEUED"
            failedCategories > 0 -> "PARTIAL"
            else -> "SUCCESS"
        }
        val priorityWasNotPresent = priorityCategoryId != null &&
            indexedCategories.none { it.categoryId == priorityCategoryId }
        upsertXtreamIndexJob(
            providerId = provider.id,
            section = contentType.name,
            state = finalState,
            now = finishedAt,
            totalCategories = indexedCategories.size,
            completedCategories = completedCategories,
            nextCategoryIndex = nextCategoryIndex,
            failedCategories = failedCategories,
            indexedRows = indexedRows,
            skippedMalformedRows = skippedMalformedRows,
            clearPriority = finalState == "SUCCESS" || priorityWasNotPresent || priorityHandled,
            lastAttemptAt = now,
            lastSuccessAt = if (finalState == "SUCCESS") finishedAt else null,
            lastError = lastError?.let(::sanitizeThrowableMessage)
        )
        updateXtreamSummaryMetadata(provider.id, contentType, indexedRows, finalState, finishedAt)
        if (hasMoreCategories) {
            scheduleXtreamIndexSync(provider.id, contentType, force = false)
        }
        if (!hasMoreCategories && failedCategories > 0) {
            throw IllegalStateException(
                "${xtreamIndexSectionLabel(contentType)} indexing completed partially.",
                lastError
            )
        }
        } finally {
            if (contentType == ContentType.MOVIE && restoreMovieWatchProgressPending) {
                movieDao.restoreWatchProgress(provider.id)
            }
        }
    }

    private fun shouldAttemptFullXtreamSummaryStream(
        resumeJob: XtreamIndexJobEntity?,
        priorityCategoryId: Long?
    ): Boolean {
        if (priorityCategoryId != null && resumeJob?.nextCategoryIndex != 0) return false
        if (resumeJob == null) return true
        return resumeJob.nextCategoryIndex == 0 &&
            resumeJob.completedCategories == 0 &&
            resumeJob.failedCategories == 0 &&
            resumeJob.indexedRows == 0
    }

    private suspend fun streamFullXtreamSummaryIndex(
        provider: Provider,
        api: XtreamProvider,
        contentType: ContentType,
        categories: List<CategoryEntity>,
        totalCategories: Int,
        now: Long,
        onProgress: ((String) -> Unit)?
    ): Result<Int> {
        progress(provider.id, onProgress, "Indexing ${xtreamIndexSectionLabel(contentType)}...")
        upsertXtreamIndexJob(
            providerId = provider.id,
            section = contentType.name,
            state = "RUNNING",
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

        var indexedRows = 0
        var restoreMovieWatchProgressPending = false
        val categoryNamesById = categories.associate { it.categoryId to it.name }
        val adultCategoryIds = categories.filter { it.isAdult }.mapTo(mutableSetOf()) { it.categoryId }
        try {
        val streamResult = when (contentType) {
            ContentType.MOVIE -> api.streamVodSummaries(adultCategoryIds = adultCategoryIds) { batch ->
                val accepted = upsertXtreamMovieSummaryBatch(
                    providerId = provider.id,
                    movies = batch.withMovieCategoryNames(categoryNamesById),
                    indexedAt = System.currentTimeMillis(),
                    restoreWatchProgress = false
                )
                indexedRows += accepted
                if (accepted > 0) {
                    restoreMovieWatchProgressPending = true
                }
                upsertXtreamIndexJob(
                    providerId = provider.id,
                    section = contentType.name,
                    state = "RUNNING",
                    now = System.currentTimeMillis(),
                    totalCategories = totalCategories,
                    completedCategories = 0,
                    nextCategoryIndex = 0,
                    indexedRows = indexedRows,
                    lastAttemptAt = now,
                    lastError = null
                )
            }
            ContentType.SERIES -> api.streamSeriesSummaries(adultCategoryIds = adultCategoryIds) { batch ->
                val accepted = upsertXtreamSeriesSummaryBatch(
                    providerId = provider.id,
                    series = batch.withSeriesCategoryNames(categoryNamesById),
                    indexedAt = System.currentTimeMillis()
                )
                indexedRows += accepted
                upsertXtreamIndexJob(
                    providerId = provider.id,
                    section = contentType.name,
                    state = "RUNNING",
                    now = System.currentTimeMillis(),
                    totalCategories = totalCategories,
                    completedCategories = 0,
                    nextCategoryIndex = 0,
                    indexedRows = indexedRows,
                    lastAttemptAt = now,
                    lastError = null
                )
            }
            else -> Result.error("Unsupported Xtream summary stream section: $contentType")
        }

        return when (streamResult) {
            is Result.Success -> {
                val finishedAt = System.currentTimeMillis()
                val acceptedCount = indexedRows.coerceAtLeast(streamResult.data)
                upsertXtreamIndexJob(
                    providerId = provider.id,
                    section = contentType.name,
                    state = "SUCCESS",
                    now = finishedAt,
                    totalCategories = totalCategories,
                    completedCategories = totalCategories,
                    nextCategoryIndex = totalCategories,
                    failedCategories = 0,
                    indexedRows = acceptedCount,
                    skippedMalformedRows = 0,
                    clearPriority = true,
                    lastAttemptAt = now,
                    lastSuccessAt = finishedAt,
                    lastError = null
                )
                updateXtreamSummaryMetadata(provider.id, contentType, acceptedCount, "SUCCESS", finishedAt)
                Result.success(acceptedCount)
            }
            is Result.Error -> streamResult
            Result.Loading -> Result.error("Xtream summary stream did not complete")
        }
        } finally {
            if (contentType == ContentType.MOVIE && restoreMovieWatchProgressPending) {
                movieDao.restoreWatchProgress(provider.id)
            }
        }
    }

    private fun List<Movie>.withMovieCategoryNames(categoryNamesById: Map<Long, String>): List<Movie> =
        map { movie ->
            val categoryName = movie.categoryId?.let(categoryNamesById::get)
            if (categoryName == null || categoryName == movie.categoryName) movie else movie.copy(categoryName = categoryName)
        }

    private fun List<Series>.withSeriesCategoryNames(categoryNamesById: Map<Long, String>): List<Series> =
        map { series ->
            val categoryName = series.categoryId?.let(categoryNamesById::get)
            if (categoryName == null || categoryName == series.categoryName) series else series.copy(categoryName = categoryName)
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
        val metadata = syncMetadataRepository.getMetadata(providerId) ?: SyncMetadata(providerId)
        when (contentType) {
            ContentType.MOVIE -> syncMetadataRepository.updateMetadata(
                metadata.copy(
                    lastMovieSync = now,
                    lastMovieAttempt = now,
                    lastMovieSuccess = if (finalState == "SUCCESS") now else metadata.lastMovieSuccess,
                    lastMoviePartial = if (finalState != "SUCCESS") now else metadata.lastMoviePartial,
                    movieCount = if (finalState == "SUCCESS") indexedRows else metadata.movieCount.coerceAtLeast(indexedRows),
                    movieCatalogStale = finalState != "SUCCESS",
                    movieSyncMode = VodSyncMode.UNKNOWN
                )
            )
            ContentType.SERIES -> syncMetadataRepository.updateMetadata(
                metadata.copy(
                    lastSeriesSync = now,
                    lastSeriesSuccess = if (finalState == "SUCCESS") now else metadata.lastSeriesSuccess,
                    seriesCount = if (finalState == "SUCCESS") indexedRows else metadata.seriesCount.coerceAtLeast(indexedRows)
                )
            )
            else -> Unit
        }
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

    private fun xtreamIndexFailureState(error: Throwable): String {
        val chain = generateSequence(error as Throwable?) { it.cause }.toList()
        if (chain.any { it is XtreamAuthenticationException }) {
            return "FAILED_PERMANENT"
        }
        val requestFailure = chain.filterIsInstance<XtreamRequestException>().firstOrNull()
        if (requestFailure?.statusCode == 401 || requestFailure?.statusCode == 403) {
            return "FAILED_PERMANENT"
        }
        return "FAILED_RETRYABLE"
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
        val existing = xtreamIndexJobDao.get(providerId, section)
        xtreamIndexJobDao.upsert(
            (existing ?: XtreamIndexJobEntity(providerId = providerId, section = section)).copy(
                state = state,
                totalCategories = totalCategories ?: existing?.totalCategories ?: 0,
                completedCategories = completedCategories ?: existing?.completedCategories ?: 0,
                nextCategoryIndex = nextCategoryIndex ?: existing?.nextCategoryIndex ?: 0,
                failedCategories = failedCategories ?: existing?.failedCategories ?: 0,
                indexedRows = indexedRows ?: existing?.indexedRows ?: 0,
                skippedMalformedRows = skippedMalformedRows ?: existing?.skippedMalformedRows ?: 0,
                deletedPrunedRows = deletedPrunedRows ?: existing?.deletedPrunedRows ?: 0,
                priorityCategoryId = if (clearPriority) null else priorityCategoryId ?: existing?.priorityCategoryId,
                priorityRequestedAt = if (clearPriority) 0L else priorityRequestedAt ?: existing?.priorityRequestedAt ?: 0L,
                lastError = lastError,
                lastAttemptAt = lastAttemptAt ?: existing?.lastAttemptAt ?: 0L,
                lastSuccessAt = lastSuccessAt ?: existing?.lastSuccessAt ?: 0L,
                updatedAt = now
            )
        )
    }

    private suspend fun syncM3u(
        provider: Provider,
        force: Boolean,
        onProgress: ((String) -> Unit)?
    ): SyncOutcome {
        val warnings = mutableListOf<String>()
        var metadata = syncMetadataRepository.getMetadata(provider.id) ?: SyncMetadata(provider.id)
        val now = System.currentTimeMillis()

        if (force || ContentCachePolicy.shouldRefresh(metadata.lastLiveSuccess, ContentCachePolicy.CATALOG_TTL_MILLIS, now)) {
            val stats = withContext(Dispatchers.IO) { m3uImporter.importPlaylist(provider, onProgress) }
            if (stats.liveCount == 0 && stats.movieCount == 0) {
                throw IllegalStateException("Playlist is empty or contains no supported entries")
            }
            warnings += stats.warnings
            if (provider.epgUrl.isBlank() && !stats.header.tvgUrl.isNullOrBlank()) {
                providerDao.updateEpgUrl(provider.id, stats.header.tvgUrl)
            }
            metadata = metadata.copy(
                lastLiveSync = now,
                lastLiveSuccess = now,
                lastMovieSync = now,
                lastSeriesSync = now,
                lastSeriesSuccess = now,
                lastMovieAttempt = now,
                lastMovieSuccess = now,
                liveCount = stats.liveCount,
                movieCount = stats.movieCount,
                movieSyncMode = VodSyncMode.FULL,
                movieWarningsCount = stats.warnings.size,
                movieCatalogStale = false,
                movieHealthySyncStreak = 0
            )
            syncMetadataRepository.updateMetadata(metadata)
        }

        if (shouldSyncEpgUpfront(provider)) {
            updateXtreamEpgJobState(
                provider = provider,
                state = "RUNNING",
                now = now,
                lastAttemptAt = now,
                lastError = null
            )
            val epgResult = syncProviderEpg(
                provider = provider,
                metadata = metadata,
                now = now,
                force = force,
                onProgress = onProgress
            )
            val finishedAt = System.currentTimeMillis()
            updateXtreamEpgJobState(
                provider = provider,
                state = when {
                    epgResult.warnings.isEmpty() -> "SUCCESS"
                    epgResult.hasRetryableFailure -> "FAILED_RETRYABLE"
                    else -> "PARTIAL"
                },
                now = finishedAt,
                indexedRows = programDao.countByProvider(provider.id),
                lastAttemptAt = now,
                lastSuccessAt = finishedAt.takeIf { epgResult.warnings.isEmpty() },
                lastError = epgResult.warnings.takeIf { it.isNotEmpty() }?.joinToString("; ")
            )
            warnings += epgResult.warnings
        } else if (provider.epgSyncMode != ProviderEpgSyncMode.SKIP) {
            upsertXtreamIndexJob(
                providerId = provider.id,
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
            scheduleBackgroundEpgSync(provider.id)
        }

        return if (warnings.isEmpty()) SyncOutcome() else SyncOutcome(partial = true, warnings = warnings.distinct())
    }

    private suspend fun queueStalkerIndexSection(
        providerId: Long,
        contentType: ContentType,
        totalCategories: Int,
        now: Long
    ) {
        require(contentType == ContentType.MOVIE || contentType == ContentType.SERIES) {
            "Unsupported Stalker index section: $contentType"
        }
        when (contentType) {
            ContentType.MOVIE -> movieCategoryHydrationDao.deleteByProvider(providerId)
            ContentType.SERIES -> seriesCategoryHydrationDao.deleteByProvider(providerId)
            else -> Unit
        }
        xtreamContentIndexDao.markRowsStaleForProviderAndType(providerId, contentType.name)
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
    }

    private suspend fun syncStalker(
        provider: Provider,
        force: Boolean,
        onProgress: ((String) -> Unit)?
    ): SyncOutcome {
        val warnings = mutableListOf<String>()
        UrlSecurityPolicy.validateStalkerPortalUrl(provider.serverUrl)?.let { message ->
            throw IllegalStateException(message)
        }
        emitCatalogSyncProgress(section = Section.LIVE)
        progress(provider.id, onProgress, "Connecting to portal...")
        val api = createStalkerSyncProvider(provider)
        requireResult(api.authenticate(), "Failed to authenticate with portal")

        var metadata = syncMetadataRepository.getMetadata(provider.id) ?: SyncMetadata(provider.id)
        val now = System.currentTimeMillis()
        var queuedMovieIndex = false
        var queuedSeriesIndex = false
        var liveCount = metadata.liveCount
        var movieCategoryCount = 0
        var seriesCategoryCount = 0

        if (force || ContentCachePolicy.shouldRefresh(metadata.lastLiveSuccess, ContentCachePolicy.CATALOG_TTL_MILLIS, now)) {
            upsertXtreamIndexJob(
                providerId = provider.id,
                section = ContentType.LIVE.name,
                state = "RUNNING",
                now = now,
                lastAttemptAt = now,
                lastError = null
            )
            progress(provider.id, onProgress, "Downloading Live TV...")
            val hiddenLiveCategoryIds = preferencesRepository.getHiddenCategoryIds(provider.id, ContentType.LIVE).first()
            val liveCatalogResult = syncStalkerLiveCatalogStaged(api, provider, hiddenLiveCategoryIds, onProgress)
            val liveCategoryCount = categoryDao.getByProviderAndTypeSync(provider.id, ContentType.LIVE.name).size
            val liveFinishedAt = System.currentTimeMillis()
            metadata = metadata.copy(
                lastLiveSync = now,
                lastLiveSuccess = now,
                liveCount = liveCatalogResult.acceptedCount
            )
            liveCount = liveCatalogResult.acceptedCount
            syncMetadataRepository.updateMetadata(metadata)
            emitCatalogSyncProgress(
                section = Section.LIVE,
                itemsIndexed = liveCatalogResult.acceptedCount
            )
            upsertXtreamIndexJob(
                providerId = provider.id,
                section = ContentType.LIVE.name,
                state = "SUCCESS",
                now = liveFinishedAt,
                totalCategories = liveCategoryCount,
                completedCategories = liveCategoryCount,
                nextCategoryIndex = liveCategoryCount,
                failedCategories = 0,
                indexedRows = liveCatalogResult.acceptedCount,
                lastAttemptAt = now,
                lastSuccessAt = liveFinishedAt,
                lastError = null
            )
            warnings += liveCatalogResult.warnings
        }

        if (force || ContentCachePolicy.shouldRefresh(metadata.lastMovieSuccess, ContentCachePolicy.CATALOG_TTL_MILLIS, now)) {
            progress(provider.id, onProgress, "Preparing Movies...")
            val categories = when (val categoriesResult = api.getVodCategories()) {
                is com.streamvault.domain.model.Result.Success -> categoriesResult.data
                is com.streamvault.domain.model.Result.Error -> {
                    val missingCatalogDiagnostic = if (
                        liveCount == 0 &&
                        warnings.any { warning -> isStalkerEmptyResponse(warning) }
                    ) {
                        stalkerCatalogAccessDiagnostic(
                            api = api,
                            primaryMessage = categoriesResult.message,
                            fallbackMessage = "empty response"
                        )
                    } else {
                        null
                    }
                    throw IllegalStateException(
                        missingCatalogDiagnostic
                            ?: "Failed to load movie categories: ${categoriesResult.message}",
                        categoriesResult.exception
                    )
                }
                is com.streamvault.domain.model.Result.Loading -> throw IllegalStateException("Unexpected loading state")
            }
            emitCatalogSyncProgress(
                section = Section.VOD,
                total = categories.size,
                itemsIndexed = metadata.liveCount
            )
            // Stalker uses lazy-by-category VoD loading — only categories are persisted up
            // front. Using replaceMovieCatalog with an empty sequence would run full stale
            // deletion and destroy any movies already hydrated via on-demand category loads.
            // replaceCategories updates/inserts category rows without touching movie rows.
            syncCatalogStore.replaceCategories(
                providerId = provider.id,
                type = "MOVIE",
                categories = categories.map { category ->
                    CategoryEntity(
                        providerId = provider.id,
                        categoryId = category.id,
                        name = category.name,
                        parentId = category.parentId,
                        type = ContentType.MOVIE,
                        isAdult = category.isAdult
                    )
                }
            )
            movieCategoryCount = categories.size
            queueStalkerIndexSection(
                providerId = provider.id,
                contentType = ContentType.MOVIE,
                totalCategories = categories.size,
                now = now
            )
            metadata = metadata.copy(
                lastMovieSync = now,
                lastMovieAttempt = now,
                movieCount = movieDao.getCount(provider.id).first(),
                movieSyncMode = VodSyncMode.PAGED,
                movieWarningsCount = 0,
                movieCatalogStale = true
            )
            syncMetadataRepository.updateMetadata(metadata)
            queuedMovieIndex = true
        }

        if (force || ContentCachePolicy.shouldRefresh(metadata.lastSeriesSuccess, ContentCachePolicy.CATALOG_TTL_MILLIS, now)) {
            progress(provider.id, onProgress, "Preparing Series...")
            val categories = when (val categoriesResult = api.getSeriesCategories()) {
                is com.streamvault.domain.model.Result.Success -> categoriesResult.data
                is com.streamvault.domain.model.Result.Error -> {
                    val missingCatalogDiagnostic = if (
                        liveCount == 0 &&
                        warnings.any { warning -> isStalkerEmptyResponse(warning) }
                    ) {
                        stalkerCatalogAccessDiagnostic(
                            api = api,
                            primaryMessage = categoriesResult.message,
                            fallbackMessage = "empty response"
                        )
                    } else {
                        null
                    }
                    throw IllegalStateException(
                        missingCatalogDiagnostic
                            ?: "Failed to load series categories: ${categoriesResult.message}",
                        categoriesResult.exception
                    )
                }
                is com.streamvault.domain.model.Result.Loading -> throw IllegalStateException("Unexpected loading state")
            }
            emitCatalogSyncProgress(
                section = Section.SERIES,
                total = categories.size,
                itemsIndexed = metadata.liveCount
            )
            // Same rationale as movies: use replaceCategories to avoid destroying any
            // already-hydrated series rows when running a category-only Stalker sync.
            syncCatalogStore.replaceCategories(
                providerId = provider.id,
                type = "SERIES",
                categories = categories.map { category ->
                    CategoryEntity(
                        providerId = provider.id,
                        categoryId = category.id,
                        name = category.name,
                        parentId = category.parentId,
                        type = ContentType.SERIES,
                        isAdult = category.isAdult
                    )
                }
            )
            seriesCategoryCount = categories.size
            queueStalkerIndexSection(
                providerId = provider.id,
                contentType = ContentType.SERIES,
                totalCategories = categories.size,
                now = now
            )
            metadata = metadata.copy(
                lastSeriesSync = now,
                seriesCount = seriesDao.getCount(provider.id).first()
            )
            syncMetadataRepository.updateMetadata(metadata)
            queuedSeriesIndex = true
        }

        if (liveCount == 0 && movieCategoryCount == 0 && seriesCategoryCount == 0) {
            val missingCatalogDiagnostic = warnings.firstOrNull { warning ->
                warning.contains("no accessible catalog data", ignoreCase = true)
            } ?: stalkerCatalogAccessDiagnostic(
                api = api,
                primaryMessage = "empty response",
                fallbackMessage = "empty response"
            )
            missingCatalogDiagnostic?.let { diagnostic ->
                throw IllegalStateException(diagnostic)
            }
        }

        if (provider.epgSyncMode != ProviderEpgSyncMode.SKIP) {
            upsertXtreamIndexJob(
                providerId = provider.id,
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
        }

        when {
            queuedMovieIndex && queuedSeriesIndex -> scheduleStalkerIndexSync(provider.id, force = force)
            queuedMovieIndex -> scheduleStalkerIndexContinuation(provider, ContentType.MOVIE, force = force)
            queuedSeriesIndex -> scheduleStalkerIndexContinuation(provider, ContentType.SERIES, force = force)
            else -> scheduleStalkerEpgIfCatalogIdle(provider)
        }

        return if (warnings.isEmpty()) SyncOutcome() else SyncOutcome(partial = true, warnings = warnings)
    }

    private suspend fun syncJellyfin(
        provider: Provider,
        force: Boolean,
        onProgress: ((String) -> Unit)?
    ): SyncOutcome {
        val warnings = mutableListOf<String>()
        try {
            val decryptedPassword = credentialCrypto.decryptIfNeeded(provider.password)
            val decryptedProvider = provider.copy(password = decryptedPassword)
            android.util.Log.d("JellyfinSync", "Starting Jellyfin sync for provider=${provider.id}")
            progress(provider.id, onProgress, "Loading Jellyfin library...")
            val (resolvedMovies, resolvedSeries) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val movieResult = jellyfinProvider.fetchMovies(decryptedProvider)
                val seriesResult = jellyfinProvider.fetchSeries(decryptedProvider)
                movieResult to seriesResult
            }
            if (resolvedMovies is com.streamvault.domain.model.Result.Error) android.util.Log.e("JellyfinSync", "Movies error: ${resolvedMovies.message}")
            if (resolvedSeries is com.streamvault.domain.model.Result.Error) android.util.Log.e("JellyfinSync", "Series error: ${resolvedSeries.message}")

            if (resolvedMovies is com.streamvault.domain.model.Result.Error && resolvedSeries is com.streamvault.domain.model.Result.Error) {
                warnings.add("Failed to load Jellyfin catalog: ${resolvedMovies.message}; ${resolvedSeries.message}")
                return SyncOutcome(partial = true, warnings = warnings)
            }

            val movieEntities = (resolvedMovies as? com.streamvault.domain.model.Result.Success)?.data.orEmpty()
            val seriesEntities = (resolvedSeries as? com.streamvault.domain.model.Result.Success)?.data.orEmpty()

            if (movieEntities.isNotEmpty()) {
                progress(provider.id, onProgress, "Importing Jellyfin movies...")
                movieEntities.chunked(50).forEach { chunk ->
                    transactionRunner.inTransaction {
                        movieDao.upsertCategoryPage(provider.id, chunk)
                    }
                }
                transactionRunner.inTransaction {
                    categoryDao.replaceAll(provider.id, com.streamvault.domain.model.ContentType.MOVIE.name, listOf(
                        com.streamvault.data.local.entity.CategoryEntity(
                            providerId = provider.id, categoryId = 1L, name = "Movies",
                            type = com.streamvault.domain.model.ContentType.MOVIE
                        )
                    ))
                }
            }

            if (seriesEntities.isNotEmpty()) {
                progress(provider.id, onProgress, "Importing Jellyfin series...")
                seriesEntities.forEach { seriesEntity ->
                    val remoteId = seriesEntity.providerSeriesId
                    if (!remoteId.isNullOrBlank()) {
                        transactionRunner.inTransaction {
                            seriesDao.insertAll(listOf(seriesEntity))
                        }
                    }
                }
                transactionRunner.inTransaction {
                    categoryDao.replaceAll(provider.id, com.streamvault.domain.model.ContentType.SERIES.name, listOf(
                        com.streamvault.data.local.entity.CategoryEntity(
                            providerId = provider.id, categoryId = 2L, name = "Series",
                            type = com.streamvault.domain.model.ContentType.SERIES
                        )
                    ))
                }
            }

            if (movieEntities.isEmpty() && seriesEntities.isEmpty()) {
                warnings.add("Jellyfin library is empty or contains no supported movies or series.")
                return SyncOutcome(partial = true, warnings = warnings)
            }

            return SyncOutcome(warnings = warnings)
        } catch (e: Exception) {
            warnings.add("Jellyfin sync failed: ${e.message.orEmpty()}")
            return SyncOutcome(partial = true, warnings = warnings)
        }
    }

    /**
     * Returned by [syncProviderEpg] so callers can distinguish between warning-only
     * degradations and transient network/IO failures that WorkManager should retry.
     */
    private data class EpgSyncResult(
        val warnings: List<String>,
        /** True when at least one EPG sub-operation failed due to a network or IO error
         *  rather than a permanent configuration problem (bad URL, bad credentials, etc.).
         *  WorkManager workers should return [Result.retry()] when this is true. */
        val hasRetryableFailure: Boolean
    )

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

    private suspend fun syncProviderEpg(
        provider: Provider,
        metadata: SyncMetadata,
        now: Long,
        force: Boolean,
        onProgress: ((String) -> Unit)?
    ): EpgSyncResult {
        var updatedMetadata = metadata
        val warnings = mutableListOf<String>()
        var hasRetryableFailure = false
        val hiddenLiveCategoryIds = preferencesRepository.getHiddenCategoryIds(provider.id, ContentType.LIVE).first()
        val guidePolicy = provider.guideSourcePolicy

        when (provider.type) {
            ProviderType.XTREAM_CODES -> {
                if (shouldUseProviderGuide(guidePolicy) &&
                    (force || ContentCachePolicy.shouldRefresh(updatedMetadata.lastEpgSuccess, ContentCachePolicy.EPG_TTL_MILLIS, now))
                ) {
                    try {
                        progress(provider.id, onProgress, "Downloading EPG...")
                        val base = provider.serverUrl.trimEnd('/')
                        val xmltvUrl = provider.epgUrl.ifBlank {
                            XtreamUrlFactory.buildXmltvUrl(base, provider.username, provider.password)
                        }
                        UrlSecurityPolicy.validateXtreamEpgUrl(xmltvUrl)?.let { message ->
                            throw IllegalStateException(message)
                        }
                        retryTransient {
                            requireResult(epgRepository.refreshEpg(provider.id, xmltvUrl), "Failed to refresh EPG")
                        }
                        val epgCount = programDao.countByProvider(provider.id)
                        updatedMetadata = updatedMetadata.copy(
                            lastEpgSync = now,
                            lastEpgSuccess = now,
                            epgCount = epgCount
                        )
                        syncMetadataRepository.updateMetadata(updatedMetadata)
                        if (epgCount == 0) {
                            warnings.add("EPG imported zero programs; live guide may require provider fallback.")
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "EPG sync failed (non-fatal): ${sanitizeThrowableMessage(e)}")
                        if (isRetryableEpgException(e)) hasRetryableFailure = true
                        warnings.add("EPG XMLTV sync failed.")
                    }
                }
            }

            ProviderType.M3U -> {
                val currentEpgUrl = providerDao.getById(provider.id)?.epgUrl ?: provider.epgUrl
                if (shouldUseProviderGuide(guidePolicy) &&
                    !currentEpgUrl.isNullOrBlank() &&
                    (force || ContentCachePolicy.shouldRefresh(updatedMetadata.lastEpgSuccess, ContentCachePolicy.EPG_TTL_MILLIS, now))
                ) {
                    val epgValidationError = UrlSecurityPolicy.validateOptionalEpgUrl(currentEpgUrl)
                    if (epgValidationError != null) {
                        warnings.add(epgValidationError)
                    } else {
                        try {
                            progress(provider.id, onProgress, "Downloading EPG...")
                            retryTransient {
                                requireResult(epgRepository.refreshEpg(provider.id, currentEpgUrl), "Failed to refresh EPG")
                            }
                            updatedMetadata = updatedMetadata.copy(
                                lastEpgSync = now,
                                lastEpgSuccess = now,
                                epgCount = programDao.countByProvider(provider.id)
                            )
                            syncMetadataRepository.updateMetadata(updatedMetadata)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.e(TAG, "EPG sync failed (non-fatal): ${sanitizeThrowableMessage(e)}")
                            if (isRetryableEpgException(e)) hasRetryableFailure = true
                            warnings.add("EPG sync failed")
                        }
                    }
                }
            }

            ProviderType.STALKER_PORTAL -> {
                if (shouldUseProviderGuide(guidePolicy) &&
                    (force || ContentCachePolicy.shouldRefresh(updatedMetadata.lastEpgSuccess, ContentCachePolicy.EPG_TTL_MILLIS, now))
                ) {
                    val stalkerEpgWarnings = syncStalkerPreferredEpg(
                        provider = provider,
                        now = now,
                        onProgress = onProgress
                    )
                    // Any Stalker EPG warning indicates an HTTP request failure, which is
                    // typically transient.
                    if (stalkerEpgWarnings.isNotEmpty()) hasRetryableFailure = true
                    warnings += stalkerEpgWarnings
                    updatedMetadata = syncMetadataRepository.getMetadata(provider.id) ?: updatedMetadata
                }
            }

            ProviderType.JELLYFIN -> Unit
        }

        if (shouldUseExternalGuide(guidePolicy)) {
            try {
                progress(provider.id, onProgress, "Refreshing external EPG sources...")
                retryTransient {
                    requireResult(
                        epgSourceRepository.refreshAllForProvider(provider.id),
                        "External EPG source refresh failed"
                    )
                }
                progress(provider.id, onProgress, "Resolving EPG mappings...")
                epgSourceRepository.resolveForProvider(provider.id, hiddenLiveCategoryIds)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "External EPG resolution failed (non-fatal): ${sanitizeThrowableMessage(e)}")
                if (isRetryableEpgException(e)) hasRetryableFailure = true
                warnings.add("External EPG source refresh/resolution failed.")
            }
        } else if (guidePolicy == GuideSourcePolicy.DISABLED) {
            progress(provider.id, onProgress, "Guide data disabled for this provider.")
            epgSourceRepository.resolveForProvider(provider.id, hiddenLiveCategoryIds)
        } else {
            progress(provider.id, onProgress, "Resolving provider EPG mappings...")
            epgSourceRepository.resolveForProvider(provider.id, hiddenLiveCategoryIds)
        }

        return EpgSyncResult(warnings = warnings, hasRetryableFailure = hasRetryableFailure)
    }

    private suspend fun syncEpgOnly(
        provider: Provider,
        onProgress: ((String) -> Unit)?
    ) {
        progress(provider.id, onProgress, "Retrying EPG...")
        val hiddenLiveCategoryIds = preferencesRepository.getHiddenCategoryIds(provider.id, ContentType.LIVE).first()
        val guidePolicy = provider.guideSourcePolicy
        if (guidePolicy == GuideSourcePolicy.DISABLED) {
            progress(provider.id, onProgress, "Guide data disabled for this provider.")
            epgSourceRepository.resolveForProvider(provider.id, hiddenLiveCategoryIds)
            return
        }
        if (provider.type == ProviderType.STALKER_PORTAL) {
            if (shouldUseProviderGuide(guidePolicy)) {
                syncStalkerPreferredEpg(
                    provider = provider,
                    now = System.currentTimeMillis(),
                    onProgress = onProgress
                )
            }
            if (shouldUseExternalGuide(guidePolicy)) {
                progress(provider.id, onProgress, "Refreshing external EPG sources...")
                retryTransient {
                    requireResult(
                        epgSourceRepository.refreshAllForProvider(provider.id),
                        "External EPG source refresh failed"
                    )
                }
                progress(provider.id, onProgress, "Resolving EPG mappings...")
                epgSourceRepository.resolveForProvider(provider.id, hiddenLiveCategoryIds)
            }
            if (!shouldUseExternalGuide(guidePolicy)) {
                progress(provider.id, onProgress, "Resolving provider EPG mappings...")
                epgSourceRepository.resolveForProvider(provider.id, hiddenLiveCategoryIds)
            }
            return
        }
        val epgUrl = when (provider.type) {
            ProviderType.XTREAM_CODES -> {
                val base = provider.serverUrl.trimEnd('/')
                provider.epgUrl.ifBlank {
                    XtreamUrlFactory.buildXmltvUrl(base, provider.username, provider.password)
                }
            }
            ProviderType.M3U -> providerDao.getById(provider.id)?.epgUrl ?: provider.epgUrl
            ProviderType.STALKER_PORTAL -> providerDao.getById(provider.id)?.epgUrl ?: provider.epgUrl
            ProviderType.JELLYFIN -> ""
        }
        if (!shouldUseProviderGuide(guidePolicy)) {
            if (shouldUseExternalGuide(guidePolicy)) {
                progress(provider.id, onProgress, "Refreshing external EPG sources...")
                retryTransient {
                    requireResult(
                        epgSourceRepository.refreshAllForProvider(provider.id),
                        "External EPG source refresh failed"
                    )
                }
                progress(provider.id, onProgress, "Resolving EPG mappings...")
                epgSourceRepository.resolveForProvider(provider.id, hiddenLiveCategoryIds)
            }
            return
        }
        if (epgUrl.isBlank()) {
            throw IllegalStateException("No EPG URL configured for this provider")
        }
        val validationError = when (provider.type) {
            ProviderType.XTREAM_CODES -> UrlSecurityPolicy.validateXtreamEpgUrl(epgUrl)
            ProviderType.M3U -> UrlSecurityPolicy.validateOptionalEpgUrl(epgUrl)
            ProviderType.STALKER_PORTAL -> UrlSecurityPolicy.validateOptionalEpgUrl(epgUrl)
            ProviderType.JELLYFIN -> null
        }
        validationError?.let { message ->
            throw IllegalStateException(message)
        }
        retryTransient {
            requireResult(epgRepository.refreshEpg(provider.id, epgUrl), "Failed to refresh EPG")
        }
        val now = System.currentTimeMillis()
        val metadata = (syncMetadataRepository.getMetadata(provider.id) ?: SyncMetadata(provider.id))
            .copy(
                lastEpgSync = now,
                lastEpgSuccess = now,
                epgCount = programDao.countByProvider(provider.id)
            )
        syncMetadataRepository.updateMetadata(metadata)

        // Also refresh external sources and run resolution
        if (shouldUseExternalGuide(guidePolicy)) {
            progress(provider.id, onProgress, "Refreshing external EPG sources...")
            retryTransient {
                requireResult(
                    epgSourceRepository.refreshAllForProvider(provider.id),
                    "External EPG source refresh failed"
                )
            }
            progress(provider.id, onProgress, "Resolving EPG mappings...")
            epgSourceRepository.resolveForProvider(provider.id, hiddenLiveCategoryIds)
        } else {
            progress(provider.id, onProgress, "Resolving provider EPG mappings...")
            epgSourceRepository.resolveForProvider(provider.id, hiddenLiveCategoryIds)
        }
    }

    private suspend fun syncStalkerPreferredEpg(
        provider: Provider,
        now: Long,
        onProgress: ((String) -> Unit)?
    ): List<String> {
        val warnings = mutableListOf<String>()
        val currentEpgUrl = providerDao.getById(provider.id)?.epgUrl ?: provider.epgUrl
        var shouldUseNativeGuide = currentEpgUrl.isBlank()

        if (currentEpgUrl.isNotBlank()) {
            val epgValidationError = UrlSecurityPolicy.validateOptionalEpgUrl(currentEpgUrl)
            if (epgValidationError != null) {
                Log.w(TAG, "Portal XMLTV URL invalid for provider ${provider.id}: $epgValidationError")
                warnings.add("Portal XMLTV URL is invalid; using the Stalker portal guide instead.")
                shouldUseNativeGuide = true
            } else {
                try {
                    progress(provider.id, onProgress, "Downloading EPG...")
                    retryTransient {
                        requireResult(epgRepository.refreshEpg(provider.id, currentEpgUrl), "Failed to refresh EPG")
                    }
                    val epgCount = programDao.countByProvider(provider.id)
                    if (epgCount > 0) {
                        syncMetadataRepository.updateMetadata(
                            (syncMetadataRepository.getMetadata(provider.id) ?: SyncMetadata(provider.id)).copy(
                                lastEpgSync = now,
                                lastEpgSuccess = now,
                                epgCount = epgCount
                            )
                        )
                    } else {
                        warnings.add("Portal XMLTV imported zero programs; using the Stalker portal guide instead.")
                        shouldUseNativeGuide = true
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Portal XMLTV sync failed for provider ${provider.id}: ${sanitizeThrowableMessage(e)}")
                    warnings.add("Portal XMLTV sync failed; using the Stalker portal guide instead.")
                    shouldUseNativeGuide = true
                }
            }
        }

        if (shouldUseNativeGuide) {
            warnings += syncStalkerPortalEpg(
                provider = provider,
                now = now,
                onProgress = onProgress
            )
        }

        return warnings
    }

    private suspend fun syncStalkerPortalEpg(
        provider: Provider,
        now: Long,
        onProgress: ((String) -> Unit)?
    ): List<String> {
        val channels = channelDao.getGuideSyncEntriesByProvider(provider.id)
        if (channels.isEmpty()) {
            return emptyList()
        }

        val guideRequests = channels
            .mapNotNull(::toStalkerGuideRequest)
            .distinctBy(StalkerGuideRequest::channelKey)
        if (guideRequests.isEmpty()) {
            return listOf("Stalker portal guide sync skipped because no valid guide channel IDs were available.")
        }

        val previousProgramCount = programDao.countByProvider(provider.id)
        val api = createStalkerSyncProvider(provider)
        val aliasToChannelKey = buildMap {
            guideRequests.forEach { request ->
                request.aliases.forEach { alias -> putIfAbsent(alias, request.channelKey) }
            }
        }
        val failedChannels = mutableListOf<String>()
        val insertBuffer = ArrayList<com.streamvault.data.local.entity.ProgramEntity>(STALKER_GUIDE_PROGRAM_BATCH_SIZE)
        val replacedChannelKeys = linkedSetOf<String>()
        val bulkCoveredChannelKeys = linkedSetOf<String>()
        var importedProgramCount = 0

        suspend fun flushPrograms() {
            if (insertBuffer.isEmpty()) return
            val chunk = insertBuffer.toList()
            insertBuffer.clear()
            transactionRunner.inTransaction {
                chunk
                    .map { it.channelId }
                    .distinct()
                    .forEach { channelId ->
                        if (replacedChannelKeys.add(channelId)) {
                            programDao.deleteForChannel(provider.id, channelId)
                        }
                    }
                if (chunk.isNotEmpty()) {
                    programDao.insertAll(chunk)
                    importedProgramCount += chunk.size
                }
            }
        }

        runCatching {
            // Stream the bulk EPG payload program-by-program; the previous buffered API
            // could materialise >30 MB JSON trees on certain Stalker portals which led to
            // OOM crashes when re-entering content screens.
            api.streamBulkEpg(periodHours = 6) { program ->
                val resolvedChannelKey = aliasToChannelKey[program.channelId] ?: return@streamBulkEpg
                if (program.endTime <= program.startTime) return@streamBulkEpg
                bulkCoveredChannelKeys += resolvedChannelKey
                insertBuffer += program.copy(
                    providerId = provider.id,
                    channelId = resolvedChannelKey
                ).toEntity()
                if (insertBuffer.size >= STALKER_GUIDE_PROGRAM_BATCH_SIZE) {
                    flushPrograms()
                }
            }.let { result ->
                if (result is com.streamvault.domain.model.Result.Error) {
                    throw result.exception ?: IllegalStateException(result.message)
                }
            }
        }.onFailure { error ->
            Log.d(TAG, "Bulk Stalker portal EPG fetch unavailable for provider ${provider.id}", error)
        }

        val fallbackGuideRequests = guideRequests.filterNot { request -> request.channelKey in bulkCoveredChannelKeys }

        // Some portals ignore `ch_id` and return the entire bulk EPG for every per-channel
        // request. After the first per-channel call we sample the response shape; if it
        // looks like the portal is repeatedly emitting the bulk payload we abort the
        // per-channel loop to avoid downloading the same multi-megabyte payload N times.
        var ignorePerChannelGuide = false

        fallbackGuideRequests.forEachIndexed { index, request ->
            if (ignorePerChannelGuide) return@forEachIndexed
            progress(provider.id, onProgress, "Downloading portal EPG... ${index + 1} of ${fallbackGuideRequests.size}")
            runCatching {
                var perChannelRecordCount = 0
                val foreignChannelIds = HashSet<String>()
                val streamResult = api.streamEpg(request.channelKey) { program ->
                    if (program.endTime <= program.startTime) return@streamEpg
                    if (program.channelId != request.channelKey) {
                        foreignChannelIds += program.channelId
                    }
                    insertBuffer += program.copy(
                        providerId = provider.id,
                        channelId = request.channelKey
                    ).toEntity()
                    perChannelRecordCount++
                    if (perChannelRecordCount >= STALKER_PER_CHANNEL_RECORD_SANITY_CAP || foreignChannelIds.size > 1) {
                        // Bail out of this single response — the portal is clearly returning
                        // the bulk payload again. The outer loop will then disable any
                        // remaining per-channel calls.
                        throw StalkerBrokenPerChannelEpgException(request.channelName)
                    }
                    if (insertBuffer.size >= STALKER_GUIDE_PROGRAM_BATCH_SIZE) {
                        flushPrograms()
                    }
                }
                if (streamResult is com.streamvault.domain.model.Result.Error) {
                    throw streamResult.exception ?: IllegalStateException(streamResult.message)
                }
            }.onFailure { error ->
                if (error is StalkerBrokenPerChannelEpgException) {
                    ignorePerChannelGuide = true
                    Log.w(
                        TAG,
                        "Stalker portal returned bulk-shaped payload for per-channel EPG request " +
                            "(${request.channelKey}); skipping remaining per-channel guide calls."
                    )
                } else {
                    failedChannels += request.channelName
                    Log.w(
                        TAG,
                        "Stalker portal EPG fetch failed for provider ${provider.id} channel ${request.channelKey}",
                        error
                    )
                }
            }
        }

        flushPrograms()

        if (replacedChannelKeys.isEmpty() && failedChannels.isNotEmpty()) {
            return listOf(
                "Stalker portal guide sync failed for all ${failedChannels.size} channels; keeping existing guide data."
            )
        }

        val epgCount = programDao.countByProvider(provider.id)
        val guideLooksHealthy = epgCount >= STALKER_MIN_HEALTHY_EPG_PROGRAMS || previousProgramCount == 0
        val existingMetadata = syncMetadataRepository.getMetadata(provider.id) ?: SyncMetadata(provider.id)
        syncMetadataRepository.updateMetadata(
            existingMetadata.copy(
                lastEpgSync = now,
                lastEpgSuccess = if (guideLooksHealthy) now else existingMetadata.lastEpgSuccess,
                epgCount = epgCount
            )
        )

        val warnings = mutableListOf<String>()
        if (epgCount == 0) {
            warnings.add("Stalker portal guide import returned zero programs.")
        }
        if (failedChannels.isNotEmpty()) {
            warnings.add(
                "Stalker portal guide imported $epgCount programs, but ${failedChannels.size} channels failed (${summarizeChannelNames(failedChannels)})."
            )
        }
        if (!guideLooksHealthy && previousProgramCount >= STALKER_MIN_HEALTHY_EPG_PROGRAMS) {
            warnings.add(
                "Stalker portal guide import looked incomplete ($importedProgramCount programs across ${replacedChannelKeys.size} channels); preserved untouched prior guide data for the rest."
            )
        }
        return warnings
    }

    private data class StalkerGuideRequest(
        val channelKey: String,
        val channelName: String,
        val aliases: Set<String>
    )

    private fun toStalkerGuideRequest(channel: ChannelGuideSyncEntity): StalkerGuideRequest? {
        val normalizedEpgKey = channel.epgChannelId
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.takeUnless(::isLikelyPlaceholderStalkerGuideKey)
        val streamKey = channel.streamId.takeIf { it > 0L }?.toString()
        val channelKey = normalizedEpgKey ?: streamKey ?: return null
        val aliases = linkedSetOf<String>().apply {
            normalizedEpgKey?.let(::add)
            streamKey?.let(::add)
        }
        return StalkerGuideRequest(
            channelKey = channelKey,
            channelName = channel.name.ifBlank { channelKey },
            aliases = aliases
        )
    }

    private fun isLikelyPlaceholderStalkerGuideKey(value: String): Boolean {
        return when (value.trim().lowercase()) {
            "no details available",
            "n/a",
            "null",
            "none",
            "unknown" -> true
            else -> false
        }
    }

    private fun summarizeChannelNames(channelNames: List<String>): String {
        val distinctNames = channelNames.distinct()
        if (distinctNames.isEmpty()) {
            return "unknown channels"
        }
        val preview = distinctNames.take(3).joinToString()
        val remaining = distinctNames.size - 3
        return if (remaining > 0) {
            "$preview, and $remaining more"
        } else {
            preview
        }
    }

    private suspend fun syncLiveOnly(
        provider: Provider,
        syncReason: XtreamLiveSyncReason,
        onProgress: ((String) -> Unit)?
    ): SyncOutcome {
        val now = System.currentTimeMillis()
        val sectionWarnings = mutableListOf<String>()
        when (provider.type) {
            ProviderType.XTREAM_CODES -> {
                progress(provider.id, onProgress, "Retrying Live TV...")
                val useTextClassification = preferencesRepository.useXtreamTextClassification.first()
                val enableBase64TextCompatibility = preferencesRepository.xtreamBase64TextCompatibility.first()
                val hiddenLiveCategoryIds = preferencesRepository.getHiddenCategoryIds(provider.id, ContentType.LIVE).first()
                val api = createXtreamSyncProvider(provider, useTextClassification, enableBase64TextCompatibility)
                val currentMetadata = syncMetadataRepository.getMetadata(provider.id) ?: SyncMetadata(provider.id)
                val runtimeProfile = CatalogSyncRuntimeProfile.from(applicationContext)
                val liveSyncResult = syncXtreamLiveCatalog(
                    provider = provider,
                    api = api,
                    existingMetadata = currentMetadata,
                    hiddenLiveCategoryIds = hiddenLiveCategoryIds,
                    onProgress = onProgress,
                    runtimeProfile = runtimeProfile,
                    trackInitialLiveOnboarding = false,
                    syncReason = syncReason
                )
                val liveSequentialStress = liveSyncResult.strategyFeedback.segmentedStressDetected
                val liveProviderAdaptation = updateSequentialProviderAdaptation(
                    previousRemembered = currentMetadata.liveSequentialFailuresRemembered,
                    previousHealthyStreak = currentMetadata.liveHealthySyncStreak,
                    sawSequentialStress = liveSequentialStress
                )
                val liveAvoidFullUntil = updateAvoidFullUntil(
                    previousAvoidFullUntil = currentMetadata.liveAvoidFullUntil,
                    now = now,
                    feedback = liveSyncResult.strategyFeedback
                )

                when (val liveResult = liveSyncResult.catalogResult) {
                    is CatalogStrategyResult.Success -> {
                        val acceptedCount = finalizeXtreamLiveCatalog(
                            providerId = provider.id,
                            liveSyncResult = liveSyncResult,
                            hiddenLiveCategoryIds = hiddenLiveCategoryIds,
                            onProgress = onProgress
                        ).acceptedCount
                        syncMetadataRepository.updateMetadata(
                            currentMetadata.copy(
                                lastLiveSync = now,
                                lastLiveSuccess = now,
                                liveCount = acceptedCount,
                                liveAvoidFullUntil = liveAvoidFullUntil,
                                liveSequentialFailuresRemembered = liveProviderAdaptation.rememberSequential,
                                liveHealthySyncStreak = liveProviderAdaptation.healthyStreak
                            )
                        )
                    }
                    is CatalogStrategyResult.Partial -> {
                        val commitResult = finalizeXtreamLiveCatalog(
                            providerId = provider.id,
                            liveSyncResult = liveSyncResult,
                            hiddenLiveCategoryIds = hiddenLiveCategoryIds,
                            onProgress = onProgress,
                            partialCompletionWarning = "Live TV retry completed partially."
                        )
                        val acceptedCount = commitResult.acceptedCount
                        syncMetadataRepository.updateMetadata(
                            currentMetadata.copy(
                                lastLiveSync = now,
                                liveCount = acceptedCount,
                                liveAvoidFullUntil = liveAvoidFullUntil,
                                liveSequentialFailuresRemembered = currentMetadata.liveSequentialFailuresRemembered || liveSequentialStress,
                                liveHealthySyncStreak = 0
                            )
                        )
                        sectionWarnings += commitResult.warnings
                    }
                    is CatalogStrategyResult.EmptyValid -> {
                        val existingLiveCount = channelDao.getCount(provider.id).first()
                        syncMetadataRepository.updateMetadata(
                            currentMetadata.copy(
                                liveAvoidFullUntil = liveAvoidFullUntil,
                                liveSequentialFailuresRemembered = currentMetadata.liveSequentialFailuresRemembered || liveSequentialStress,
                                liveHealthySyncStreak = 0
                            )
                        )
                        throw IllegalStateException(
                            if (existingLiveCount > 0) {
                                "Live TV refresh returned an empty catalog; existing library was preserved."
                            } else {
                                "Live TV catalog was empty."
                            }
                        )
                    }
                    is CatalogStrategyResult.Failure -> {
                        syncMetadataRepository.updateMetadata(
                            currentMetadata.copy(
                                liveAvoidFullUntil = liveAvoidFullUntil,
                                liveSequentialFailuresRemembered = currentMetadata.liveSequentialFailuresRemembered || liveSequentialStress || shouldRememberSequentialPreference(liveResult.error),
                                liveHealthySyncStreak = 0
                            )
                        )
                        throw IllegalStateException(
                            syncErrorSanitizer.userMessage(liveResult.error, "Failed to fetch live channels"),
                            liveResult.error
                        )
                    }
                }
            }
            ProviderType.M3U -> {
                progress(provider.id, onProgress, "Retrying Live TV...")
                val stats = withContext(Dispatchers.IO) {
                    m3uImporter.importPlaylist(provider, onProgress, includeLive = true, includeMovies = false)
                }
                if (stats.liveCount == 0) {
                    throw IllegalStateException("Playlist contains no live TV entries")
                }
                val metadata = (syncMetadataRepository.getMetadata(provider.id) ?: SyncMetadata(provider.id))
                    .copy(
                        lastLiveSync = now,
                        lastLiveSuccess = now,
                        liveCount = stats.liveCount
                )
                syncMetadataRepository.updateMetadata(metadata)
            }
            ProviderType.STALKER_PORTAL -> {
                emitCatalogSyncProgress(section = Section.LIVE)
                progress(provider.id, onProgress, "Retrying Live TV...")
                upsertXtreamIndexJob(
                    providerId = provider.id,
                    section = ContentType.LIVE.name,
                    state = "RUNNING",
                    now = now,
                    lastAttemptAt = now,
                    lastError = null
                )
                val api = createStalkerSyncProvider(provider)
                val hiddenLiveCategoryIds = preferencesRepository.getHiddenCategoryIds(provider.id, ContentType.LIVE).first()
                val liveCatalogResult = syncStalkerLiveCatalogStaged(api, provider, hiddenLiveCategoryIds, onProgress)
                val liveCategoryCount = categoryDao.getByProviderAndTypeSync(provider.id, ContentType.LIVE.name).size
                val liveFinishedAt = System.currentTimeMillis()
                val metadata = (syncMetadataRepository.getMetadata(provider.id) ?: SyncMetadata(provider.id))
                    .copy(
                        lastLiveSync = now,
                        lastLiveSuccess = now,
                        liveCount = liveCatalogResult.acceptedCount
                )
                syncMetadataRepository.updateMetadata(metadata)
                emitCatalogSyncProgress(
                    section = Section.LIVE,
                    itemsIndexed = liveCatalogResult.acceptedCount
                )
                upsertXtreamIndexJob(
                    providerId = provider.id,
                    section = ContentType.LIVE.name,
                    state = "SUCCESS",
                    now = liveFinishedAt,
                    totalCategories = liveCategoryCount,
                    completedCategories = liveCategoryCount,
                    nextCategoryIndex = liveCategoryCount,
                    failedCategories = 0,
                    indexedRows = liveCatalogResult.acceptedCount,
                    lastAttemptAt = now,
                    lastSuccessAt = liveFinishedAt,
                    lastError = null
                )
                sectionWarnings += liveCatalogResult.warnings
            }

            ProviderType.JELLYFIN -> throw IllegalStateException("Live TV retry is unavailable for Jellyfin providers")
        }
        return if (sectionWarnings.isEmpty()) SyncOutcome() else SyncOutcome(partial = true, warnings = sectionWarnings)
    }

    private suspend fun syncMoviesOnly(
        provider: Provider,
        onProgress: ((String) -> Unit)?
    ): SyncOutcome {
        val now = System.currentTimeMillis()
        val sectionWarnings = mutableListOf<String>()
        when (provider.type) {
            ProviderType.XTREAM_CODES -> {
                progress(provider.id, onProgress, "Queueing Movies index...")
                val useTextClassification = preferencesRepository.useXtreamTextClassification.first()
                val enableBase64TextCompatibility = preferencesRepository.xtreamBase64TextCompatibility.first()
                val api = createXtreamSyncProvider(provider, useTextClassification, enableBase64TextCompatibility)
                val currentMetadata = syncMetadataRepository.getMetadata(provider.id) ?: SyncMetadata(provider.id)
                val categoryCount = syncXtreamCategoryShell(
                    provider = provider,
                    api = api,
                    contentType = ContentType.MOVIE,
                    label = "Movies",
                    now = now,
                    onProgress = onProgress
                ).getOrElse { error ->
                    upsertXtreamIndexJob(
                        providerId = provider.id,
                        section = ContentType.MOVIE.name,
                        state = xtreamIndexFailureState(error),
                        now = now,
                        lastAttemptAt = now,
                        lastError = sanitizeThrowableMessage(error)
                    )
                    throw IllegalStateException(
                        syncErrorSanitizer.userMessage(error, "Failed to queue movie index"),
                        error
                    )
                }
                syncMetadataRepository.updateMetadata(
                    currentMetadata.copy(
                        lastMovieAttempt = now,
                        movieCatalogStale = true,
                        movieSyncMode = VodSyncMode.UNKNOWN
                    )
                )
                scheduleXtreamIndexSync(provider.id, ContentType.MOVIE)
                Log.i(TAG, "Queued Xtream movie index for provider ${provider.id}: $categoryCount categories.")
            }
            ProviderType.M3U -> {
                progress(provider.id, onProgress, "Retrying Movies...")
                val stats = withContext(Dispatchers.IO) {
                    m3uImporter.importPlaylist(provider, onProgress, includeLive = false, includeMovies = true)
                }
                if (stats.movieCount == 0) {
                    throw IllegalStateException("Playlist contains no movie entries")
                }
                val metadata = (syncMetadataRepository.getMetadata(provider.id) ?: SyncMetadata(provider.id))
                    .copy(
                        lastMovieSync = now,
                        lastMovieAttempt = now,
                        lastMovieSuccess = now,
                        movieCount = stats.movieCount,
                        movieSyncMode = VodSyncMode.FULL,
                        movieWarningsCount = stats.warnings.size,
                        movieCatalogStale = false
                )
                syncMetadataRepository.updateMetadata(metadata)
            }
            ProviderType.STALKER_PORTAL -> {
                progress(provider.id, onProgress, "Queueing Movies index...")
                val api = createStalkerSyncProvider(provider)
                val categories = requireResult(api.getVodCategories(), "Failed to load movie categories")
                emitCatalogSyncProgress(
                    section = Section.VOD,
                    total = categories.size,
                    itemsIndexed = movieDao.getCount(provider.id).first()
                )
                syncCatalogStore.replaceCategories(
                    providerId = provider.id,
                    type = "MOVIE",
                    categories = categories.map { category ->
                        CategoryEntity(
                            providerId = provider.id,
                            categoryId = category.id,
                            name = category.name,
                            parentId = category.parentId,
                            type = ContentType.MOVIE,
                            isAdult = category.isAdult
                        )
                    }
                )
                queueStalkerIndexSection(
                    providerId = provider.id,
                    contentType = ContentType.MOVIE,
                    totalCategories = categories.size,
                    now = now
                )
                val metadata = (syncMetadataRepository.getMetadata(provider.id) ?: SyncMetadata(provider.id))
                    .copy(
                        lastMovieSync = now,
                        lastMovieAttempt = now,
                        movieCount = movieDao.getCount(provider.id).first(),
                        movieSyncMode = VodSyncMode.PAGED,
                        movieWarningsCount = 0,
                        movieCatalogStale = true
                    )
                syncMetadataRepository.updateMetadata(metadata)
                scheduleStalkerIndexContinuation(provider, ContentType.MOVIE, force = true)
            }

            ProviderType.JELLYFIN -> throw IllegalStateException("Movies retry is unavailable for Jellyfin providers")
        }
        return if (sectionWarnings.isEmpty()) SyncOutcome() else SyncOutcome(partial = true, warnings = sectionWarnings)
    }

    private suspend fun syncSeriesOnly(
        provider: Provider,
        onProgress: ((String) -> Unit)?
    ): SyncOutcome {
        val sectionWarnings = mutableListOf<String>()
        when (provider.type) {
            ProviderType.XTREAM_CODES -> {
                val now = System.currentTimeMillis()
                progress(provider.id, onProgress, "Queueing Series index...")
                val useTextClassification = preferencesRepository.useXtreamTextClassification.first()
                val enableBase64TextCompatibility = preferencesRepository.xtreamBase64TextCompatibility.first()
                val api = createXtreamSyncProvider(provider, useTextClassification, enableBase64TextCompatibility)
                val currentMetadata = syncMetadataRepository.getMetadata(provider.id) ?: SyncMetadata(provider.id)
                val categoryCount = syncXtreamCategoryShell(
                    provider = provider,
                    api = api,
                    contentType = ContentType.SERIES,
                    label = "Series",
                    now = now,
                    onProgress = onProgress
                ).getOrElse { error ->
                    upsertXtreamIndexJob(
                        providerId = provider.id,
                        section = ContentType.SERIES.name,
                        state = xtreamIndexFailureState(error),
                        now = now,
                        lastAttemptAt = now,
                        lastError = sanitizeThrowableMessage(error)
                    )
                    throw IllegalStateException(
                        syncErrorSanitizer.userMessage(error, "Failed to queue series index"),
                        error
                    )
                }
                syncMetadataRepository.updateMetadata(
                    currentMetadata.copy(
                        lastSeriesSync = now
                    )
                )
                scheduleXtreamIndexSync(provider.id, ContentType.SERIES)
                Log.i(TAG, "Queued Xtream series index for provider ${provider.id}: $categoryCount categories.")
            }
            ProviderType.STALKER_PORTAL -> {
                progress(provider.id, onProgress, "Queueing Series index...")
                val api = createStalkerSyncProvider(provider)
                val categories = requireResult(api.getSeriesCategories(), "Failed to load series categories")
                emitCatalogSyncProgress(
                    section = Section.SERIES,
                    total = categories.size,
                    itemsIndexed = seriesDao.getCount(provider.id).first()
                )
                syncCatalogStore.replaceCategories(
                    providerId = provider.id,
                    type = "SERIES",
                    categories = categories.map { category ->
                        CategoryEntity(
                            providerId = provider.id,
                            categoryId = category.id,
                            name = category.name,
                            parentId = category.parentId,
                            type = ContentType.SERIES,
                            isAdult = category.isAdult
                        )
                    }
                )
                val now = System.currentTimeMillis()
                queueStalkerIndexSection(
                    providerId = provider.id,
                    contentType = ContentType.SERIES,
                    totalCategories = categories.size,
                    now = now
                )
                val metadata = (syncMetadataRepository.getMetadata(provider.id) ?: SyncMetadata(provider.id))
                    .copy(
                        lastSeriesSync = now,
                        seriesCount = seriesDao.getCount(provider.id).first()
                    )
                syncMetadataRepository.updateMetadata(metadata)
                scheduleStalkerIndexContinuation(provider, ContentType.SERIES, force = true)
            }
            ProviderType.M3U -> {
                throw IllegalStateException("Series retry is unavailable for this provider")
            }

            ProviderType.JELLYFIN -> throw IllegalStateException("Series retry is unavailable for Jellyfin providers")
        }
        return if (sectionWarnings.isEmpty()) SyncOutcome() else SyncOutcome(partial = true, warnings = sectionWarnings)
    }

    private suspend fun syncXtreamLiveCatalog(
        provider: Provider,
        api: XtreamProvider,
        existingMetadata: SyncMetadata,
        hiddenLiveCategoryIds: Set<Long>,
        onProgress: ((String) -> Unit)?,
        runtimeProfile: CatalogSyncRuntimeProfile = CatalogSyncRuntimeProfile.from(applicationContext),
        trackInitialLiveOnboarding: Boolean = false,
        syncReason: XtreamLiveSyncReason = XtreamLiveSyncReason.FOREGROUND
    ): CatalogSyncPayload<Channel> {
        // Emission d'entree LIVE : signale tot a l'UI que la section LIVE demarre,
        // avant meme la requete `get_live_categories`. `total = 0` = indetermine ;
        // la premiere fin de fenetre (T5) viendra raffiner avec le vrai denominateur.
        syncProgressBus.emit(
            com.streamvault.domain.sync.SyncProgress(
                section = com.streamvault.domain.sync.Section.LIVE,
                current = 0,
                total = 0,
                currentLabel = "",
                itemsIndexed = 0
            )
        )
        val effectiveLiveSyncMethod = XtreamLiveSyncPolicy.resolve(
            userMode = provider.xtreamLiveSyncMode,
            runtimeProfile = runtimeProfile,
            syncReason = syncReason,
            metadata = existingMetadata,
            now = System.currentTimeMillis(),
            hiddenLiveCategoryIds = hiddenLiveCategoryIds
        )
        Log.i(
            TAG,
            "Xtream live sync method for provider ${provider.id}: user=${provider.xtreamLiveSyncMode} effective=$effectiveLiveSyncMethod reason=$syncReason profile=${runtimeProfile.diagnosticsLabel}."
        )
        return xtreamLiveStrategy.syncXtreamLiveCatalog(
            provider,
            api,
            existingMetadata,
            hiddenLiveCategoryIds,
            onProgress,
            runtimeProfile,
            trackInitialLiveOnboarding,
            effectiveLiveSyncMethod
        )
    }

    private suspend fun loadXtreamLiveFull(
        provider: Provider,
        api: XtreamProvider
    ): CatalogSyncPayload<Channel> = xtreamLiveStrategy.loadXtreamLiveFull(provider, api, CatalogSyncRuntimeProfile.from(applicationContext))

    private suspend fun loadXtreamLiveByCategory(
        provider: Provider,
        api: XtreamProvider,
        rawCategories: List<XtreamCategory>,
        onProgress: ((String) -> Unit)?,
        preferSequential: Boolean
    ): CatalogSyncPayload<Channel> =
        xtreamLiveStrategy.loadXtreamLiveByCategory(provider, api, rawCategories, onProgress, preferSequential, CatalogSyncRuntimeProfile.from(applicationContext))

    private suspend fun updateSyncStatusMetadata(providerId: Long, status: String) {
        val metadata = (syncMetadataRepository.getMetadata(providerId) ?: SyncMetadata(providerId))
            .copy(lastSyncStatus = status)
        syncMetadataRepository.updateMetadata(metadata)
    }

    private fun shouldRememberSequentialPreference(error: Throwable): Boolean {
        return xtreamAdaptiveSyncPolicy.isProviderStress(error) ||
            error is XtreamAuthenticationException ||
            error is XtreamParsingException ||
            (error is XtreamRequestException && error.statusCode in setOf(403, 429)) ||
            (error is XtreamNetworkException && error.message.orEmpty().contains("reset", ignoreCase = true))
    }

    private fun updateSequentialProviderAdaptation(
        previousRemembered: Boolean,
        previousHealthyStreak: Int,
        sawSequentialStress: Boolean
    ): SequentialProviderAdaptation {
        if (sawSequentialStress) {
            return SequentialProviderAdaptation(rememberSequential = true, healthyStreak = 0)
        }
        if (!previousRemembered) {
            return SequentialProviderAdaptation(rememberSequential = false, healthyStreak = 0)
        }
        val nextHealthyStreak = (previousHealthyStreak + 1).coerceAtMost(2)
        return if (nextHealthyStreak >= 2) {
            SequentialProviderAdaptation(rememberSequential = false, healthyStreak = 0)
        } else {
            SequentialProviderAdaptation(rememberSequential = true, healthyStreak = nextHealthyStreak)
        }
    }

    private fun updateAvoidFullUntil(
        previousAvoidFullUntil: Long,
        now: Long,
        feedback: XtreamStrategyFeedback
    ): Long = catalogStrategySupport.updateAvoidFullUntil(previousAvoidFullUntil, now, feedback)

    private fun shouldPreferSegmentedLiveSync(
        metadata: SyncMetadata,
        now: Long
    ): Boolean = catalogStrategySupport.shouldPreferSegmentedLiveSync(metadata, now)

    private fun shouldPreferSegmentedMovieSync(
        metadata: SyncMetadata,
        now: Long
    ): Boolean = catalogStrategySupport.shouldPreferSegmentedMovieSync(metadata, now)

    private fun shouldPreferSegmentedSeriesSync(
        metadata: SyncMetadata,
        now: Long
    ): Boolean = catalogStrategySupport.shouldPreferSegmentedSeriesSync(metadata, now)

    private fun shouldAvoidFullCatalogStrategy(error: Throwable): Boolean =
        catalogStrategySupport.shouldAvoidFullCatalogStrategy(error)

    private fun sawSegmentedStress(
        warnings: List<String>,
        result: CatalogStrategyResult<*>,
        sequentialWarnings: Set<String>
    ): Boolean = catalogStrategySupport.sawSegmentedStress(warnings, result, sequentialWarnings)

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

    private suspend fun finalizeXtreamLiveCatalog(
        providerId: Long,
        liveSyncResult: CatalogSyncPayload<Channel>,
        hiddenLiveCategoryIds: Set<Long>,
        onProgress: ((String) -> Unit)? = null,
        partialCompletionWarning: String? = null
    ): XtreamLiveCommitResult {
        progress(providerId, onProgress, "Saving Live TV channels...")
        val warnings = buildList {
            addAll(liveSyncResult.warnings)
            addAll(catalogStrategySupport.strategyWarnings(liveSyncResult.catalogResult))
            partialCompletionWarning?.let(::add)
        }
        val acceptedCount = when (val liveResult = liveSyncResult.catalogResult) {
            is CatalogStrategyResult.Success -> {
                liveSyncResult.stagedSessionId?.let { sessionId ->
                    val mergedCategories = mergeVisibleLiveCategoriesWithHiddenStoredContent(
                        providerId = providerId,
                        visibleCategories = liveSyncResult.categories,
                        hiddenLiveCategoryIds = hiddenLiveCategoryIds
                    )
                    if (hiddenLiveCategoryIds.isNotEmpty()) {
                        mergeHiddenChannelsIntoStaging(providerId, sessionId, hiddenLiveCategoryIds)
                    }
                    syncCatalogStore.applyStagedLiveCatalog(
                        providerId = providerId,
                        sessionId = sessionId,
                        categories = mergedCategories
                    )
                    liveSyncResult.stagedAcceptedCount
                } ?: run {
                    val liveCatalog = mergeVisibleLiveSyncWithHiddenStoredContent(
                        providerId = providerId,
                        visibleCategories = liveSyncResult.categories,
                        visibleChannels = liveResult.items.map { it.toEntity() },
                        hiddenLiveCategoryIds = hiddenLiveCategoryIds
                    )
                    syncCatalogStore.replaceLiveCatalog(
                        providerId = providerId,
                        categories = liveCatalog.categories,
                        channels = liveCatalog.channels
                    )
                }
            }
            is CatalogStrategyResult.Partial -> {
                liveSyncResult.stagedSessionId?.let { sessionId ->
                    val mergedCategories = mergeVisibleLiveCategoriesWithHiddenStoredContent(
                        providerId = providerId,
                        visibleCategories = liveSyncResult.categories,
                        hiddenLiveCategoryIds = hiddenLiveCategoryIds
                    )
                    if (hiddenLiveCategoryIds.isNotEmpty()) {
                        mergeHiddenChannelsIntoStaging(providerId, sessionId, hiddenLiveCategoryIds)
                    }
                    syncCatalogStore.applyStagedLiveCatalogUpsertOnly(
                        providerId = providerId,
                        sessionId = sessionId,
                        categories = mergedCategories
                    )
                    liveSyncResult.stagedAcceptedCount
                } ?: run {
                    val liveCatalog = mergeVisibleLiveSyncWithHiddenStoredContent(
                        providerId = providerId,
                        visibleCategories = liveSyncResult.categories,
                        visibleChannels = liveResult.items.map { it.toEntity() },
                        hiddenLiveCategoryIds = hiddenLiveCategoryIds
                    )
                    syncCatalogStore.upsertLiveCatalog(
                        providerId = providerId,
                        categories = liveCatalog.categories,
                        channels = liveCatalog.channels
                    )
                }
            }
            is CatalogStrategyResult.EmptyValid,
            is CatalogStrategyResult.Failure -> {
                throw IllegalArgumentException(
                    "finalizeXtreamLiveCatalog only supports success or partial results"
                )
            }
        }
        return XtreamLiveCommitResult(acceptedCount = acceptedCount, warnings = warnings)
    }

    private suspend fun mergeHiddenChannelsIntoStaging(
        providerId: Long,
        sessionId: Long,
        hiddenLiveCategoryIds: Set<Long>
    ) {
        val hiddenChannels = channelDao.getByProviderSync(providerId)
            .filter { channel -> channel.categoryId != null && channel.categoryId in hiddenLiveCategoryIds }
        if (hiddenChannels.isNotEmpty()) {
            syncCatalogStore.stageChannelBatch(providerId, sessionId, hiddenChannels)
        }
    }

    private suspend fun mergeVisibleLiveCategoriesWithHiddenStoredContent(
        providerId: Long,
        visibleCategories: List<CategoryEntity>?,
        hiddenLiveCategoryIds: Set<Long>
    ): List<CategoryEntity>? {
        if (hiddenLiveCategoryIds.isEmpty()) {
            return visibleCategories
        }

        return ((visibleCategories ?: emptyList()) + categoryDao.getByProviderAndTypeSync(providerId, ContentType.LIVE.name)
            .filter { category -> category.categoryId in hiddenLiveCategoryIds })
            .distinctBy { it.categoryId to it.type }
            .sortedBy { it.categoryId }
            .takeIf { it.isNotEmpty() }
    }

    private suspend fun mergeVisibleLiveSyncWithHiddenStoredContent(
        providerId: Long,
        visibleCategories: List<CategoryEntity>?,
        visibleChannels: List<ChannelEntity>,
        hiddenLiveCategoryIds: Set<Long>
    ): LiveCatalogSnapshot {
        if (hiddenLiveCategoryIds.isEmpty()) {
            return LiveCatalogSnapshot(visibleCategories, visibleChannels)
        }

        val hiddenCategories = categoryDao.getByProviderAndTypeSync(providerId, ContentType.LIVE.name)
            .filter { category -> category.categoryId in hiddenLiveCategoryIds }
        val hiddenChannels = channelDao.getByProviderSync(providerId)
            .filter { channel -> channel.categoryId != null && channel.categoryId in hiddenLiveCategoryIds }

        val mergedCategories = ((visibleCategories ?: emptyList()) + hiddenCategories)
            .distinctBy { it.categoryId to it.type }
            .sortedBy { it.categoryId }
            .takeIf { it.isNotEmpty() }
        val mergedChannels = (visibleChannels + hiddenChannels)
            .distinctBy { it.streamId }
            .sortedBy { it.number }

        return LiveCatalogSnapshot(
            categories = mergedCategories,
            channels = mergedChannels
        )
    }

    private fun progress(providerId: Long, callback: ((String) -> Unit)?, message: String) {
        publishSyncState(providerId, SyncState.Syncing(message))
        callback?.invoke(message)
    }

    private fun emitCatalogSyncProgress(
        section: Section,
        current: Int = 0,
        total: Int = 0,
        currentLabel: String = "",
        itemsIndexed: Int = 0
    ) {
        syncProgressBus.emit(
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
        syncStateTracker.publish(providerId, state)
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

    private fun createXtreamSyncProvider(
        provider: Provider,
        useTextClassification: Boolean = true,
        enableBase64TextCompatibility: Boolean = false
    ): XtreamProvider {
        return XtreamProvider(
            providerId = provider.id,
            api = xtreamCatalogApiService,
            serverUrl = provider.serverUrl,
            username = provider.username,
            password = provider.password,
            allowedOutputFormats = provider.allowedOutputFormats,
            useTextClassification = useTextClassification,
            enableBase64TextCompatibility = enableBase64TextCompatibility,
            requestProfile = provider.toGenericRequestProfile(ownerTag = "provider:${provider.id}/xtream")
        )
    }

    private fun createStalkerSyncProvider(provider: Provider): StalkerProvider {
        return StalkerProvider(
            providerId = provider.id,
            api = stalkerApiService,
            portalUrl = provider.serverUrl,
            macAddress = provider.stalkerMacAddress,
            authMode = provider.stalkerAuthMode,
            username = provider.username,
            password = provider.password,
            httpUserAgent = provider.httpUserAgent,
            httpHeaders = provider.httpHeaders,
            portalFingerprintHint = provider.stalkerPortalFingerprint,
            magPresetHint = provider.stalkerMagPreset,
            bootstrapRecipeHint = provider.stalkerLastBootstrapRecipe,
            endpointPreferenceHint = provider.stalkerEndpointPreference,
            cookieModeHint = provider.stalkerCookieMode,
            playbackBackendHint = provider.stalkerPlaybackBackendHint,
            portalProfileHint = provider.stalkerPortalProfile,
            preferredPlaybackMode = provider.stalkerLastPlaybackMode
                ?.let { value -> runCatching { StalkerPlaybackMode.valueOf(value) }.getOrNull() },
            deviceProfile = provider.stalkerDeviceProfile,
            timezone = provider.stalkerDeviceTimezone,
            locale = provider.stalkerDeviceLocale,
            serialNumber = provider.stalkerSerialNumber,
            deviceId = provider.stalkerDeviceId,
            deviceId2 = provider.stalkerDeviceId2,
            signature = provider.stalkerSignature,
            stalkerAdvancedOptionsJson = provider.stalkerAdvancedOptionsJson
        )
    }

    private suspend fun loadStalkerChannelsByCategory(
        api: StalkerProvider,
        categories: List<com.streamvault.domain.model.Category>,
        onProgress: ((String) -> Unit)?
    ): StalkerLiveCategoryLoadResult {
        progress(api.providerId, onProgress, "Loading live channels...")
        val bulkChannels = requireResult(api.getLiveStreams(null), "Failed to load live channels")
        if (categories.isEmpty()) {
            return StalkerLiveCategoryLoadResult(channels = bulkChannels)
        }

        val hasResolvedCategories = bulkChannels.any { channel -> channel.categoryId != null }
        if (bulkChannels.isNotEmpty() && hasResolvedCategories) {
            return StalkerLiveCategoryLoadResult(channels = bulkChannels.distinctBy { it.streamId })
        }

        return fetchStalkerLiveChannelsByCategory(
            provider = requireNotNull(providerDao.getById(api.providerId)) {
                "Provider ${api.providerId} not found while loading live channels"
            }.toDomain(),
            api = api,
            categories = categories.map { category ->
                XtreamCategory(
                    categoryId = category.id.toString(),
                    categoryName = category.name,
                    isAdult = category.isAdult
                )
            },
            onProgress = onProgress
        )
    }

    private data class StalkerLiveCategoryLoadResult(
        val channels: List<Channel>,
        val warnings: List<String> = emptyList()
    )

    private data class StalkerLiveCatalogResult(
        val categories: List<CategoryEntity>,
        val channels: List<Channel>,
        val warnings: List<String> = emptyList()
    )

    private data class StagedStalkerLiveCatalogResult(
        val acceptedCount: Int,
        val warnings: List<String> = emptyList()
    )

    private suspend fun syncStalkerLiveCatalogStaged(
        api: StalkerProvider,
        provider: Provider,
        hiddenLiveCategoryIds: Set<Long>,
        onProgress: ((String) -> Unit)?
    ): StagedStalkerLiveCatalogResult {
        val warnings = mutableListOf<String>()
        var categoriesErrorMessage: String? = null
        val preferredCategories = when (val categoriesResult = api.getLiveCategories()) {
            is com.streamvault.domain.model.Result.Success -> categoriesResult.data
                .map { it.toEntity(provider.id) }
                .filterNot { category -> category.categoryId in hiddenLiveCategoryIds }
            is com.streamvault.domain.model.Result.Error -> {
                Log.w(
                    TAG,
                    "Stalker live categories failed for provider ${provider.id}; streaming bulk live channels with fallback categories: ${categoriesResult.message}",
                    categoriesResult.exception
                )
                warnings += "Live categories failed; recovered using bulk live channels."
                categoriesErrorMessage = categoriesResult.message
                null
            }
            is com.streamvault.domain.model.Result.Loading -> throw IllegalStateException("Unexpected loading state")
        }

        progress(provider.id, onProgress, "Loading live channels...")
        val fallbackCollector = FallbackCategoryCollector(provider.id, ContentType.LIVE)
        val seenStreamIds = HashSet<Long>()
        val batch = ArrayList<Channel>(XTREAM_FALLBACK_STAGE_BATCH_SIZE)
        var stagedSessionId: Long? = null
        var acceptedCount = 0

        suspend fun flushBatch() {
            if (batch.isEmpty()) return
            val staged = catalogStager.stageChannelItems(
                providerId = provider.id,
                items = batch,
                seenStreamIds = seenStreamIds,
                fallbackCollector = fallbackCollector,
                sessionId = stagedSessionId
            )
            stagedSessionId = staged.sessionId
            acceptedCount += staged.acceptedCount
            batch.clear()
        }

        suspend fun finalizeStagedImport(): StagedStalkerLiveCatalogResult {
            val sessionId = stagedSessionId
            if (hiddenLiveCategoryIds.isNotEmpty()) {
                sessionId?.let { mergeHiddenChannelsIntoStaging(provider.id, it, hiddenLiveCategoryIds) }
            }
            val hiddenCategories = if (hiddenLiveCategoryIds.isNotEmpty()) {
                categoryDao.getByProviderAndTypeSync(provider.id, ContentType.LIVE.name)
                    .filter { category -> category.categoryId in hiddenLiveCategoryIds }
            } else {
                emptyList()
            }
            val categories = (
                mergePreferredAndFallbackCategories(
                    preferredCategories,
                    fallbackCollector.entities().takeIf { it.isNotEmpty() }
                ).orEmpty() + hiddenCategories
            )
                .distinctBy { it.categoryId to it.type }
                .takeIf { it.isNotEmpty() }
            if (acceptedCount == 0 || sessionId == null) {
                syncCatalogStore.replaceLiveCatalog(
                    providerId = provider.id,
                    categories = categories,
                    channels = emptyList()
                )
                return StagedStalkerLiveCatalogResult(
                    acceptedCount = 0,
                    warnings = (warnings + "Live TV provider exposed no live channels; continuing with VOD and series only.").distinct()
                )
            }
            syncCatalogStore.applyStagedLiveCatalog(provider.id, sessionId, categories)
            return StagedStalkerLiveCatalogResult(acceptedCount, warnings)
        }

        try {
            var bulkFailure: Exception? = null
            when (val streamResult = api.streamLiveStreams { channel ->
                    if (channel.categoryId != null && channel.categoryId in hiddenLiveCategoryIds) {
                        return@streamLiveStreams
                    }
                    batch += channel
                    if (batch.size >= XTREAM_FALLBACK_STAGE_BATCH_SIZE) {
                        flushBatch()
                    }
                }) {
                is com.streamvault.domain.model.Result.Success -> {
                    flushBatch()
                    return finalizeStagedImport()
                }
                is com.streamvault.domain.model.Result.Error -> {
                    val profileDiagnostic = stalkerCatalogAccessDiagnostic(
                        api = api,
                        primaryMessage = categoriesErrorMessage.orEmpty(),
                        fallbackMessage = streamResult.message
                    )
                    bulkFailure = IllegalStateException(
                        buildString {
                            append(streamResult.message.ifBlank { "Failed to load live channels" })
                            profileDiagnostic?.let {
                                append(' ')
                                append(it)
                            }
                        },
                        streamResult.exception
                    )
                }
                is com.streamvault.domain.model.Result.Loading -> throw IllegalStateException("Unexpected loading state")
            }

            stagedSessionId?.let { syncCatalogStore.discardStagedImport(provider.id, it) }
            stagedSessionId = null
            acceptedCount = 0
            batch.clear()
            seenStreamIds.clear()

            val fallbackCategories = preferredCategories.orEmpty()
            if (fallbackCategories.isEmpty()) {
                return StagedStalkerLiveCatalogResult(
                    acceptedCount = 0,
                    warnings = (
                        warnings + listOfNotNull(
                            "Live TV provider exposed no live channels; continuing with VOD and series only.",
                            bulkFailure?.let(::sanitizeThrowableMessage)
                                ?.takeIf { it.isNotBlank() }
                                ?.let { "Live TV fetch returned no usable channels: $it" }
                        )
                    ).distinct()
                )
            }

            Log.w(
                TAG,
                "Stalker bulk live sync failed for provider ${provider.id}; falling back to category live fetches.",
                bulkFailure
            )
            warnings += "Bulk live request failed; recovered using per-category live fetches."

            val fallbackResult = fetchStalkerLiveChannelsByCategory(
                provider = provider,
                api = api,
                categories = fallbackCategories.map { category ->
                    XtreamCategory(
                        categoryId = category.categoryId.toString(),
                        categoryName = category.name,
                        isAdult = category.isAdult
                    )
                },
                onProgress = onProgress
            )
            warnings += fallbackResult.warnings
            fallbackResult.channels.forEach { channel ->
                if (channel.categoryId != null && channel.categoryId in hiddenLiveCategoryIds) {
                    return@forEach
                }
                batch += channel
                if (batch.size >= XTREAM_FALLBACK_STAGE_BATCH_SIZE) {
                    flushBatch()
                }
            }
            flushBatch()
            return finalizeStagedImport()
        } catch (error: CancellationException) {
            stagedSessionId?.let { syncCatalogStore.discardStagedImport(provider.id, it) }
            throw error
        } catch (error: Exception) {
            stagedSessionId?.let { syncCatalogStore.discardStagedImport(provider.id, it) }
            throw error
        }
    }

    private suspend fun loadStalkerLiveCatalog(
        api: StalkerProvider,
        provider: Provider,
        onProgress: ((String) -> Unit)?
    ): StalkerLiveCatalogResult {
        return when (val categoriesResult = api.getLiveCategories()) {
            is com.streamvault.domain.model.Result.Success -> {
                val channelResult = loadStalkerChannelsByCategory(api, categoriesResult.data, onProgress)
                StalkerLiveCatalogResult(
                    categories = categoriesResult.data.map { it.toEntity(provider.id) },
                    channels = channelResult.channels,
                    warnings = channelResult.warnings
                )
            }
            is com.streamvault.domain.model.Result.Error -> {
                Log.w(
                    TAG,
                    "Stalker live categories failed for provider ${provider.id}; trying bulk live fallback: ${categoriesResult.message}",
                    categoriesResult.exception
                )
                when (val bulkResult = api.getLiveStreams(null)) {
                    is com.streamvault.domain.model.Result.Success -> {
                        val channels = bulkResult.data.distinctBy { it.streamId }
                        if (channels.isEmpty()) {
                            throw IllegalStateException(
                                "${categoriesResult.message.ifBlank { "Failed to load live categories" }} Bulk live fallback returned no channels.",
                                categoriesResult.exception
                            )
                        }
                        StalkerLiveCatalogResult(
                            categories = synthesizeStalkerLiveCategories(provider.id, channels),
                            channels = channels,
                            warnings = listOf("Live categories failed; recovered using bulk live channels.")
                        )
                    }
                    is com.streamvault.domain.model.Result.Error -> {
                        val profileDiagnostic = stalkerCatalogAccessDiagnostic(
                            api = api,
                            primaryMessage = categoriesResult.message,
                            fallbackMessage = bulkResult.message
                        )
                        throw IllegalStateException(
                            buildString {
                                append(categoriesResult.message.ifBlank { "Failed to load live categories" })
                                bulkResult.message.takeIf { it.isNotBlank() }?.let {
                                    append(" Bulk live fallback also failed: ")
                                    append(it)
                                }
                                profileDiagnostic?.let {
                                    append(' ')
                                    append(it)
                                }
                            },
                            bulkResult.exception ?: categoriesResult.exception
                        )
                    }
                    is com.streamvault.domain.model.Result.Loading -> throw IllegalStateException("Unexpected loading state")
                }
            }
            is com.streamvault.domain.model.Result.Loading -> throw IllegalStateException("Unexpected loading state")
        }
    }

    private suspend fun fetchStalkerLiveChannelsByCategory(
        provider: Provider,
        api: StalkerProvider,
        categories: List<XtreamCategory>,
        onProgress: ((String) -> Unit)?
    ): StalkerLiveCategoryLoadResult {
        if (categories.isEmpty()) {
            return StalkerLiveCategoryLoadResult(emptyList())
        }

        val runtimeProfile = CatalogSyncRuntimeProfile.from(applicationContext)
        val concurrency = minOf(
            categories.size,
            runtimeProfile.maxCategoryConcurrency,
            STALKER_MAX_PARALLEL_CATEGORY_FETCHES
        ).coerceAtLeast(1)
        val executionPlan = executeCategoryRecoveryPlan(
            provider = provider,
            categories = categories,
            initialConcurrency = concurrency,
            sectionLabel = "Live TV",
            sequentialModeWarning = LIVE_CATEGORY_SEQUENTIAL_MODE_WARNING,
            onProgress = onProgress,
            fetch = { category -> fetchStalkerLiveCategoryOutcome(api, category) }
        )
        var timedOutcomes = executionPlan.outcomes

        val categoryOutcomes = timedOutcomes.map { it.outcome }
        val failureCount = timedOutcomes.count { it.outcome is CategoryFetchOutcome.Failure }
        val fastFailureCount = timedOutcomes.count {
            it.elapsedMs <= 5_000L && it.outcome is CategoryFetchOutcome.Failure
        }
        val downgradeRecommended = catalogStrategySupport.shouldDowngradeCategorySync(
            categories.size,
            failureCount,
            fastFailureCount,
            categoryOutcomes
        )
        var warnings = executionPlan.warnings

        if (concurrency > 1 && catalogStrategySupport.shouldRetryFailedCategories(
                categories.size,
                failureCount,
                downgradeRecommended,
                categoryOutcomes
            )
        ) {
            val failedRetryTotal = timedOutcomes.count { it.outcome is CategoryFetchOutcome.Failure }
            Log.w(
                TAG,
                "Stalker live category sync is continuing in sequential mode for failed categories on provider ${provider.id}."
            )
            progress(provider.id, onProgress, "Retrying failed Live TV categories 0/$failedRetryTotal...")
            timedOutcomes = continueFailedCategoryOutcomes(
                provider = provider,
                timedOutcomes = timedOutcomes,
                fetchSequentially = { category -> fetchStalkerLiveCategoryOutcome(api, category) },
                onCategoryRetried = { completed, total, _ ->
                    progress(provider.id, onProgress, "Retrying failed Live TV categories $completed/$total...")
                }
            )
            warnings = (
                warnings + if (downgradeRecommended) listOf(LIVE_CATEGORY_SEQUENTIAL_MODE_WARNING) else emptyList()
            ).distinct()
        }

        val finalOutcomes = timedOutcomes.map { it.outcome }
        val channels = finalOutcomes
            .filterIsInstance<CategoryFetchOutcome.Success<Channel>>()
            .flatMap { outcome -> outcome.items }
            .distinctBy { channel -> channel.streamId }
        val outcomeWarnings = finalOutcomes
            .filterIsInstance<CategoryFetchOutcome.Failure>()
            .map { failure -> categoryFailureWarning("Live TV", failure.categoryName, failure.error) }

        return StalkerLiveCategoryLoadResult(
            channels = channels,
            warnings = (outcomeWarnings + warnings).distinct()
        )
    }

    private suspend fun stalkerCatalogAccessDiagnostic(
        api: StalkerProvider,
        primaryMessage: String,
        fallbackMessage: String?
    ): String? {
        if (!isStalkerEmptyResponse(primaryMessage) || !isStalkerEmptyResponse(fallbackMessage)) {
            return null
        }
        val profile = when (val profileResult = api.getAccountProfile()) {
            is com.streamvault.domain.model.Result.Success -> profileResult.data
            else -> return null
        }
        if (profile.ambiguousState) {
            return "Portal profile is ambiguous; playback/session validation failed. Check that the MAC is activated and that this portal supports MAG-style playback for the assigned account."
        }
        if (!profile.hasLikelyMissingCatalogAccess()) {
            return null
        }
        return "Portal authenticated, but the returned Stalker profile indicates this account has no accessible catalog data. Check that the MAC is activated and assigned a live/VOD package on the provider side."
    }

    private fun isStalkerEmptyResponse(message: String?): Boolean =
        !message.isNullOrBlank() && message.contains("empty response", ignoreCase = true)

    private fun shouldUseProviderGuide(policy: GuideSourcePolicy): Boolean =
        policy == GuideSourcePolicy.AUTO || policy == GuideSourcePolicy.PROVIDER_ONLY

    private fun shouldUseExternalGuide(policy: GuideSourcePolicy): Boolean =
        policy == GuideSourcePolicy.AUTO || policy == GuideSourcePolicy.EXTERNAL_ONLY

    private fun StalkerProviderProfile.hasLikelyMissingCatalogAccess(): Boolean {
        val normalizedAccountId = accountId?.trim().orEmpty()
        val normalizedAccountName = accountName?.trim().orEmpty()
        return authAccess == false &&
            (normalizedAccountId.isBlank() || normalizedAccountId == "0") &&
            (normalizedAccountName.isBlank() || normalizedAccountName == "0")
    }

    private fun synthesizeStalkerLiveCategories(
        providerId: Long,
        channels: List<Channel>
    ): List<CategoryEntity> {
        return channels
            .mapNotNull { channel ->
                val categoryId = channel.categoryId ?: return@mapNotNull null
                CategoryEntity(
                    providerId = providerId,
                    categoryId = categoryId,
                    name = channel.categoryName?.takeIf { it.isNotBlank() } ?: "Category $categoryId",
                    type = ContentType.LIVE,
                    isAdult = channel.isAdult
                )
            }
            .distinctBy { it.categoryId }
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

    private suspend fun fetchStalkerLiveCategoryOutcome(
        api: StalkerProvider,
        category: XtreamCategory
    ): TimedCategoryOutcome<Channel> {
        val startedAt = System.currentTimeMillis()
        val outcome = try {
            val categoryId = category.categoryId.toLongOrNull()
                ?: throw IllegalArgumentException("Invalid Stalker live category id '${category.categoryId}'")
            val channels = requireResult(
                api.getLiveStreams(categoryId),
                "Failed to load live channels for ${category.categoryName}"
            ).distinctBy { it.streamId }
            if (channels.isEmpty()) {
                CategoryFetchOutcome.Empty(category.categoryName)
            } else {
                CategoryFetchOutcome.Success(
                    categoryName = category.categoryName,
                    items = channels,
                    rawCount = channels.size
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            CategoryFetchOutcome.Failure(category.categoryName, error)
        }

        return TimedCategoryOutcome(
            category = category,
            outcome = outcome,
            elapsedMs = System.currentTimeMillis() - startedAt
        )
    }

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
