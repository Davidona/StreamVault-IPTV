package com.streamvault.data.repository

import com.streamvault.data.local.dao.StremioPlaybackHistoryDao
import com.streamvault.data.local.entity.StremioPlaybackHistoryEntity
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.stremio.StremioPlaybackHistory
import com.streamvault.domain.repository.StremioPlaybackHistoryRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class StremioPlaybackHistoryRepositoryImpl @Inject constructor(
    private val dao: StremioPlaybackHistoryDao
) : StremioPlaybackHistoryRepository {
    override fun observeContinueWatching(limit: Int): Flow<List<StremioPlaybackHistory>> =
        dao.observeContinueWatching(limit.coerceIn(1, MAX_CONTINUE_WATCHING_ITEMS))
            .map { rows -> rows.map { it.toDomain() } }

    override fun observeHistory(): Flow<List<StremioPlaybackHistory>> =
        dao.observeHistory().map { rows -> rows.map { it.toDomain() } }

    override suspend fun dismiss(contentKey: String): Result<Unit> = try {
        dao.dismiss(contentKey, System.currentTimeMillis())
        Result.success(Unit)
    } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel
    } catch (_: Exception) { Result.error("History update failed") }

    override suspend fun get(contentKey: String): StremioPlaybackHistory? =
        dao.get(contentKey)?.toDomain()

    override suspend fun save(history: StremioPlaybackHistory): Result<Unit> = runCatching {
        val completed = com.streamvault.domain.util.isPlaybackComplete(history.resumePositionMs, history.totalDurationMs)
        dao.upsert(history.copy(watched = history.watched || completed).toEntity())
    }.fold(
        onSuccess = { Result.success(Unit) },
        onFailure = { Result.error("Stremio playback progress could not be saved", it) }
    )

    override suspend fun saveAll(history: List<StremioPlaybackHistory>): Result<Unit> = try {
        dao.upsertAll(history.map { it.toEntity() })
        Result.success(Unit)
    } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel
    } catch (_: Exception) { Result.error("History update failed") }

    override suspend fun delete(contentKey: String): Result<Unit> = runCatching {
        dao.delete(contentKey)
    }.fold(
        onSuccess = { Result.success(Unit) },
        onFailure = { Result.error("Stremio playback history could not be removed", it) }
    )

    private fun StremioPlaybackHistoryEntity.toDomain() = StremioPlaybackHistory(
        contentKey = contentKey,
        mediaType = mediaType,
        exactVideoId = exactVideoId,
        parentSeriesId = parentSeriesId,
        title = title,
        posterUrl = posterUrl,
        resumePositionMs = resumePositionMs,
        totalDurationMs = totalDurationMs,
        watched = isWatched,
        lastWatchedAt = lastWatchedAt,
        catalogOriginKey = catalogOriginKey,
        streamOriginKeyHint = streamOriginKeyHint,
        dismissedAtMs = dismissedAtMs
    )

    private fun StremioPlaybackHistory.toEntity() = StremioPlaybackHistoryEntity(
        contentKey = contentKey,
        mediaType = mediaType,
        exactVideoId = exactVideoId,
        parentSeriesId = parentSeriesId,
        title = title,
        posterUrl = posterUrl,
        resumePositionMs = resumePositionMs,
        totalDurationMs = totalDurationMs,
        isWatched = watched,
        lastWatchedAt = lastWatchedAt,
        catalogOriginKey = catalogOriginKey,
        streamOriginKeyHint = streamOriginKeyHint,
        dismissedAtMs = dismissedAtMs
    )

    private companion object {
        const val MAX_CONTINUE_WATCHING_ITEMS = 100
    }
}
