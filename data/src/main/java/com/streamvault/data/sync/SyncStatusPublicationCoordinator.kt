package com.streamvault.data.sync

import com.streamvault.domain.model.ContentType
import com.streamvault.data.local.DatabaseTransactionRunner
import com.streamvault.domain.model.SyncMetadata
import com.streamvault.domain.model.SyncState
import com.streamvault.domain.model.VodSyncMode
import com.streamvault.domain.repository.SyncMetadataRepository
import com.streamvault.domain.sync.SyncProgress
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns provider-sync status and metadata publication.
 *
 * Catalog executors report durable summary outcomes here, while the manager retains only its
 * historical façade methods. State and progress sessions remain fenced by their respective
 * monotonic session owners, so a late worker cannot overwrite a newer manual run.
 */
internal class SyncStatusPublicationCoordinator(
    private val syncMetadataRepository: SyncMetadataRepository,
    private val syncProgressBus: SyncProgressBus,
    private val syncStateTracker: SyncStateTracker = SyncStateTracker(),
    private val transactionRunner: DatabaseTransactionRunner? = null
) {
    val syncState: StateFlow<SyncState>
        get() = syncStateTracker.aggregateState

    val syncStatesByProvider: StateFlow<Map<Long, SyncState>>
        get() = syncStateTracker.statesByProvider

    private val stateSessions = ConcurrentHashMap<Long, SyncStateSession>()
    private val progressSessions = ConcurrentHashMap<Long, SyncProgressSession>()
    private val progressStateSessions = ConcurrentHashMap<SyncProgressSession, SyncStateSession>()

    fun currentSyncState(providerId: Long): SyncState = syncStateTracker.current(providerId)

    private suspend fun <T> inMetadataTransaction(block: suspend () -> T): T =
        transactionRunner?.inTransaction(block) ?: block()

    suspend fun updateStatus(providerId: Long, status: String) = inMetadataTransaction {
        val metadata = (syncMetadataRepository.getMetadata(providerId) ?: SyncMetadata(providerId))
            .copy(lastSyncStatus = status)
        syncMetadataRepository.updateMetadata(metadata)
    }

    suspend fun updateSummaryMetadata(
        providerId: Long,
        contentType: ContentType,
        indexedRows: Int,
        finalState: String,
        now: Long,
        movieSyncMode: VodSyncMode
    ) = inMetadataTransaction {
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
                    movieSyncMode = movieSyncMode
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

    suspend fun markMovieIndexRebuildAttempt(providerId: Long, now: Long) {
        val metadata = syncMetadataRepository.getMetadata(providerId) ?: SyncMetadata(providerId)
        syncMetadataRepository.updateMetadata(
            metadata.copy(
                lastMovieAttempt = now,
                movieCatalogStale = true,
                movieSyncMode = VodSyncMode.UNKNOWN
            )
        )
    }

    fun publish(providerId: Long, state: SyncState) {
        stateSessions[providerId]?.let { session ->
            syncStateTracker.publish(session, state)
        }
    }

    fun progress(providerId: Long, callback: ((String) -> Unit)?, message: String) {
        publish(providerId, SyncState.Syncing(message))
        callback?.invoke(message)
    }

    fun beginStateSession(providerId: Long): SyncStateSession =
        syncStateTracker.begin(providerId).also { stateSessions[providerId] = it }

    fun finishStateSession(session: SyncStateSession) {
        stateSessions.remove(session.providerId, session)
        syncStateTracker.finish(session)
    }

    fun beginProgressSession(providerId: Long): SyncProgressSession {
        val stateSession = beginStateSession(providerId)
        return syncProgressBus.begin(providerId).also { progressSession ->
            progressSessions[providerId] = progressSession
            progressStateSessions[progressSession] = stateSession
        }
    }

    fun finishProgressSession(session: SyncProgressSession) {
        progressSessions.remove(session.providerId, session)
        syncProgressBus.finish(session)
        progressStateSessions.remove(session)?.let(::finishStateSession)
    }

    fun emitProgress(providerId: Long, progress: SyncProgress) {
        progressSessions[providerId]?.let { session -> syncProgressBus.emit(session, progress) }
    }

    fun reset(providerId: Long? = null) {
        if (providerId == null) {
            stateSessions.clear()
            progressSessions.clear()
            progressStateSessions.clear()
        } else {
            stateSessions.remove(providerId)
            progressSessions.remove(providerId)
            progressStateSessions.keys.removeIf { it.providerId == providerId }
        }
        syncStateTracker.reset(providerId)
    }
}
