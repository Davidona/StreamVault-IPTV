package com.streamvault.data.repository

import com.streamvault.data.local.DatabaseTransactionRunner
import com.streamvault.data.local.dao.EpisodeDao
import com.streamvault.data.local.dao.MovieDao
import com.streamvault.data.local.dao.PlaybackHistoryDao
import com.streamvault.data.mapper.toDomain
import com.streamvault.data.mapper.toEntity
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.PlaybackHistory
import com.streamvault.domain.model.PlaybackWatchedStatus
import com.streamvault.domain.model.Result
import com.streamvault.domain.repository.PlaybackHistoryRepository
import com.streamvault.domain.util.DEFAULT_PLAYBACK_COMPLETION_THRESHOLD
import com.streamvault.domain.util.isPlaybackComplete
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.data.remote.xtream.XtreamUrlFactory

@Singleton
class PlaybackHistoryRepositoryImpl @Inject constructor(
    private val dao: PlaybackHistoryDao,
    private val preferencesRepository: PreferencesRepository,
    private val movieDao: MovieDao,
    private val episodeDao: EpisodeDao,
    private val transactionRunner: DatabaseTransactionRunner
) : PlaybackHistoryRepository {
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pendingResumeUpdates = ConcurrentHashMap<PlaybackKey, PlaybackHistory>()
    private val pendingResumeUpdatesState = MutableStateFlow<Map<PlaybackKey, PlaybackHistory>>(emptyMap())
    private val pendingProgressMutex = Mutex()
    private val isIncognito: StateFlow<Boolean> =
        preferencesRepository.isIncognitoMode
            .stateIn(repositoryScope, SharingStarted.Eagerly, false)

    init {
        repositoryScope.launch {
            while (currentCoroutineContext().isActive) {
                delay(RESUME_POSITION_FLUSH_INTERVAL_MS)
                try {
                    flushPendingResumeUpdates()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    // Keep the periodic actor alive. The latest in-memory progress remains
                    // pending and will be retried on the next interval or lifecycle flush.
                    Log.w(TAG, "Periodic playback progress flush failed", error)
                }
            }
        }
    }

    override suspend fun isIncognitoModeEnabled(): Boolean = preferencesRepository.isIncognitoMode.first()

    override fun getRecentlyWatched(limit: Int): Flow<List<PlaybackHistory>> {
        return mergedRecentHistory(
            persisted = dao.getRecentlyWatched(limit).map { list -> list.map { it.toDomain() } },
            limit = limit
        ) { true }
    }

    override suspend fun getRecentlyWatchedPage(offset: Int, limit: Int): List<PlaybackHistory> {
        require(offset >= 0)
        require(limit > 0)
        return pendingProgressMutex.withLock {
            flushPendingResumeUpdatesLocked()
            dao.getRecentlyWatchedPage(limit = limit, offset = offset).first().map { it.toDomain() }
        }
    }

    override fun getRecentlyWatchedByProvider(providerId: Long, limit: Int): Flow<List<PlaybackHistory>> {
        return mergedRecentHistory(
            persisted = dao.getRecentlyWatchedByProvider(providerId, limit).map { list -> list.map { it.toDomain() } },
            limit = limit
        ) { history -> history.providerId == providerId }
    }

    override fun getRecentlyWatchedByProviders(providerIds: Set<Long>, limit: Int): Flow<List<PlaybackHistory>> {
        if (providerIds.isEmpty()) {
            return kotlinx.coroutines.flow.flowOf(emptyList())
        }
        return mergedRecentHistory(
            persisted = dao.getRecentlyWatchedByProviders(providerIds, limit).map { list -> list.map { it.toDomain() } },
            limit = limit
        ) { history -> history.providerId in providerIds }
    }

    override fun getUnwatchedCount(providerId: Long, seriesId: Long): Flow<Int> {
        return episodeDao.getUnwatchedCount(
            providerId = providerId,
            seriesId = seriesId,
            completionThreshold = DEFAULT_PLAYBACK_COMPLETION_THRESHOLD
        )
    }

    override suspend fun getPlaybackHistory(
        contentId: Long,
        contentType: ContentType,
        providerId: Long,
        seriesId: Long?,
        seasonNumber: Int?,
        episodeNumber: Int?
    ): PlaybackHistory? {
        val key = PlaybackKey(contentId, contentType, providerId)
        pendingResumeUpdates[key]?.let { return it }

        val directMatch = dao.get(contentId, contentType.name, providerId)?.toDomain()
        if (directMatch != null) {
            return directMatch
        }

        return when (contentType) {
            ContentType.MOVIE -> dao.getLatestMovieHistoryBySharedTmdb(contentId, providerId)?.toDomain()
            ContentType.SERIES -> dao.getLatestSeriesHistoryBySharedTmdb(contentId, providerId)?.toDomain()
            ContentType.SERIES_EPISODE -> {
                if (seriesId != null && seasonNumber != null && episodeNumber != null) {
                    dao.getLatestEpisodeHistoryByCoordinates(providerId, seriesId, seasonNumber, episodeNumber)
                        ?.toDomain()
                } else null
            }
            ContentType.LIVE,
            ContentType.VOD -> null
        }
    }

    override suspend fun markAsWatched(history: PlaybackHistory): Result<Unit> {
        return try {
            if (isIncognito.value) {
                return Result.success(Unit)
            }

            pendingProgressMutex.withLock {
                val key = history.playbackKey()
                val existing = pendingResumeUpdates[key]
                    ?: dao.get(history.contentId, history.contentType.name, history.providerId)?.toDomain()
                val resolvedTotalDuration = history.totalDurationMs.takeIf { it > 0L } ?: existing?.totalDurationMs ?: 0L
                val resolvedResumePosition = when {
                    resolvedTotalDuration > 0L -> resolvedTotalDuration
                    history.resumePositionMs > 0L -> history.resumePositionMs
                    else -> existing?.resumePositionMs ?: 0L
                }
                val updatedHistory = history.copy(
                    streamUrl = XtreamUrlFactory.sanitizePersistedStreamUrl(history.streamUrl, history.providerId),
                    resumePositionMs = resolvedResumePosition,
                    totalDurationMs = resolvedTotalDuration,
                    watchCount = existing?.watchCount ?: history.watchCount,
                    watchedStatus = PlaybackWatchedStatus.COMPLETED_MANUAL,
                    lastWatchedAt = System.currentTimeMillis()
                )
                transactionRunner.inTransaction {
                    dao.insertOrUpdate(updatedHistory.toEntity())
                    syncDenormalizedProgress(updatedHistory.contentId, updatedHistory.contentType, updatedHistory.providerId)
                }
                clearPendingResumeUpdateLocked(key)
            }
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Result.error("Failed to mark content as watched", e)
        }
    }

    override suspend fun importTraktHistory(history: PlaybackHistory,
        identity: com.streamvault.domain.trakt.TraktMediaIdentity,
        match: com.streamvault.domain.trakt.TraktHistoryMatch): Result<Boolean> {
        return try {
            if (preferencesRepository.isIncognitoMode.first()) return Result.success(false)
            pendingProgressMutex.withLock {
                val key = history.playbackKey()
                val didImport = transactionRunner.inTransaction {
                    val existing = pendingResumeUpdates[key]
                        ?: dao.get(history.contentId, history.contentType.name, history.providerId)?.toDomain()
                    val candidate = com.streamvault.domain.trakt.TraktHistoryMergePolicy.importCandidate(
                        existing ?: history, identity, match) ?: return@inTransaction false
                    val imported = candidate.copy(streamUrl = XtreamUrlFactory.sanitizePersistedStreamUrl(candidate.streamUrl, candidate.providerId))
                    dao.insertOrUpdate(imported.toEntity())
                    syncDenormalizedProgress(imported.contentId, imported.contentType, imported.providerId)
                    true
                }
                if (didImport) clearPendingResumeUpdateLocked(key)
                Result.success(didImport)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.error("Failed to import Trakt history", failure)
        }
    }

    override suspend fun recordPlayback(history: PlaybackHistory): Result<Unit> {
        return try {
            if (isIncognito.value) {
                return Result.success(Unit)
            }

            pendingProgressMutex.withLock {
                val key = history.playbackKey()
                val existing = pendingResumeUpdates[key]
                    ?: dao.get(history.contentId, history.contentType.name, history.providerId)?.toDomain()
                val updatedHistory = history.copy(
                    streamUrl = XtreamUrlFactory.sanitizePersistedStreamUrl(history.streamUrl, history.providerId),
                    resumePositionMs = history.resumePositionMs.takeIf { it > 0L } ?: existing?.resumePositionMs ?: 0L,
                    totalDurationMs = history.totalDurationMs.takeIf { it > 0L } ?: existing?.totalDurationMs ?: 0L,
                    watchCount = (existing?.watchCount ?: 0) + 1,
                    watchedStatus = resolveWatchedStatus(
                        resumePositionMs = history.resumePositionMs.takeIf { it > 0L } ?: existing?.resumePositionMs ?: 0L,
                        totalDurationMs = history.totalDurationMs.takeIf { it > 0L } ?: existing?.totalDurationMs ?: 0L,
                        fallback = history.watchedStatus
                    ),
                    lastWatchedAt = System.currentTimeMillis()
                )
                transactionRunner.inTransaction {
                    dao.insertOrUpdate(updatedHistory.toEntity())
                    syncDenormalizedProgress(updatedHistory.contentId, updatedHistory.contentType, updatedHistory.providerId)
                }
                clearPendingResumeUpdateLocked(key)
            }
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Result.error("Failed to record playback history", e)
        }
    }

    override suspend fun updateResumePosition(history: PlaybackHistory): Result<Unit> {
        return try {
            if (isIncognito.value) {
                return Result.success(Unit)
            }

            pendingProgressMutex.withLock {
                val key = history.playbackKey()
                val existing = pendingResumeUpdates[key]
                    ?: dao.get(history.contentId, history.contentType.name, history.providerId)?.toDomain()

                val updatedHistory = history.copy(
                    streamUrl = XtreamUrlFactory.sanitizePersistedStreamUrl(history.streamUrl, history.providerId),
                    watchCount = existing?.watchCount ?: 1,
                    watchedStatus = resolveWatchedStatus(
                        resumePositionMs = history.resumePositionMs,
                        totalDurationMs = history.totalDurationMs,
                        fallback = existing?.watchedStatus ?: history.watchedStatus
                    ),
                    lastWatchedAt = System.currentTimeMillis()
                )
                // Steady-state playback is memory-only. The periodic actor and lifecycle
                // boundaries flush this coalesced value to Room as one durable write.
                pendingResumeUpdates[key] = updatedHistory
                publishPendingResumeUpdates()
            }
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Result.error("Failed to update playback resume position", e)
        }
    }

    override suspend fun removeFromHistory(contentId: Long, contentType: ContentType, providerId: Long): Result<Unit> = try {
        pendingProgressMutex.withLock {
            pendingResumeUpdates.remove(PlaybackKey(contentId, contentType, providerId))
            transactionRunner.inTransaction {
                dao.delete(contentId, contentType.name, providerId)
                syncDenormalizedProgress(contentId, contentType, providerId)
            }
            publishPendingResumeUpdates()
        }
        Result.success(Unit)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (e: Exception) {
        Result.error("Failed to remove playback history item", e)
    }

    override suspend fun clearAllHistory(): Result<Unit> = try {
        pendingProgressMutex.withLock {
            pendingResumeUpdates.clear()
            transactionRunner.inTransaction {
                dao.deleteAll()
                movieDao.resetAllWatchProgress()
                episodeDao.resetAllWatchProgress()
            }
            publishPendingResumeUpdates()
        }
        Result.success(Unit)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (e: Exception) {
        Result.error("Failed to clear playback history", e)
    }

    override suspend fun clearHistoryForProvider(providerId: Long): Result<Unit> = try {
        pendingProgressMutex.withLock {
            pendingResumeUpdates.keys.removeIf { it.providerId == providerId }
            transactionRunner.inTransaction {
                dao.deleteByProvider(providerId)
                syncDenormalizedProgressForProvider(providerId)
            }
            publishPendingResumeUpdates()
        }
        Result.success(Unit)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (e: Exception) {
        Result.error("Failed to clear provider playback history", e)
    }

    override suspend fun clearLiveHistoryForProvider(providerId: Long): Result<Unit> = try {
        pendingProgressMutex.withLock {
            pendingResumeUpdates.keys.removeIf { it.providerId == providerId && it.contentType == ContentType.LIVE }
            dao.deleteByProviderAndType(providerId, ContentType.LIVE.name)
            publishPendingResumeUpdates()
        }
        Result.success(Unit)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (e: Exception) {
        Result.error("Failed to clear live playback history", e)
    }

    override suspend fun flushPendingProgress(): Result<Unit> = try {
        flushPendingResumeUpdates()
        Result.success(Unit)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (e: Exception) {
        Result.error("Failed to flush pending progress", e)
    }

    private suspend fun flushPendingResumeUpdates() {
        pendingProgressMutex.withLock {
            flushPendingResumeUpdatesLocked()
        }
    }

    private suspend fun flushPendingResumeUpdatesLocked() {
        val snapshot = pendingResumeUpdates.toMap()
        if (snapshot.isEmpty()) return

        // One transaction for the coalesced set avoids a Room transaction per content
        // item when a lifecycle boundary flushes several recently played items.
        transactionRunner.inTransaction {
            snapshot.values.forEach { history ->
                dao.insertOrUpdate(history.toEntity())
                syncDenormalizedProgress(history.contentId, history.contentType, history.providerId)
            }
        }
        snapshot.forEach { (key, history) ->
            pendingResumeUpdates.remove(key, history)
        }
        publishPendingResumeUpdates()
    }

    private fun clearPendingResumeUpdateLocked(key: PlaybackKey) {
        if (pendingResumeUpdates.remove(key) != null) {
            publishPendingResumeUpdates()
        }
    }

    private fun publishPendingResumeUpdates() {
        pendingResumeUpdatesState.value = pendingResumeUpdates.toMap()
    }

    private fun mergedRecentHistory(
        persisted: Flow<List<PlaybackHistory>>,
        limit: Int,
        includePending: (PlaybackHistory) -> Boolean
    ): Flow<List<PlaybackHistory>> = combine(persisted, pendingResumeUpdatesState) { persistedItems, pendingItems ->
        val mergedByKey = LinkedHashMap<PlaybackKey, PlaybackHistory>()
        persistedItems.forEach { history ->
            mergedByKey[history.playbackKey()] = history
        }
        pendingItems.values
            .asSequence()
            .filter(includePending)
            .forEach { history ->
                mergedByKey[history.playbackKey()] = history
            }
        mergedByKey.values
            .sortedWith(
                compareByDescending<PlaybackHistory> { it.lastWatchedAt }
                    .thenByDescending { it.contentId }
                    .thenByDescending { it.contentType.name }
                    .thenByDescending { it.providerId }
            )
            .take(limit)
    }

    private suspend fun syncDenormalizedProgress(contentId: Long, contentType: ContentType, providerId: Long) {
        when (contentType) {
            ContentType.MOVIE -> movieDao.syncWatchProgressFromHistory(contentId, providerId)
            ContentType.SERIES_EPISODE -> episodeDao.syncWatchProgressFromHistory(contentId, providerId)
            else -> Unit
        }
    }


    private suspend fun syncDenormalizedProgressForProvider(providerId: Long) {
        movieDao.syncWatchProgressFromHistoryByProvider(providerId)
        episodeDao.syncWatchProgressFromHistoryByProvider(providerId)
    }
    private fun resolveWatchedStatus(
        resumePositionMs: Long,
        totalDurationMs: Long,
        fallback: PlaybackWatchedStatus
    ): PlaybackWatchedStatus {
        if (totalDurationMs <= 0L) {
            return fallback
        }
        return if (isPlaybackComplete(resumePositionMs, totalDurationMs, DEFAULT_PLAYBACK_COMPLETION_THRESHOLD)) {
            PlaybackWatchedStatus.COMPLETED_AUTO
        } else {
            PlaybackWatchedStatus.IN_PROGRESS
        }
    }

    private companion object {
        const val TAG = "PlaybackHistoryRepository"
    }
}

private const val RESUME_POSITION_FLUSH_INTERVAL_MS = 30_000L

private data class PlaybackKey(
    val contentId: Long,
    val contentType: ContentType,
    val providerId: Long
)

private fun PlaybackHistory.playbackKey(): PlaybackKey =
    PlaybackKey(contentId = contentId, contentType = contentType, providerId = providerId)
