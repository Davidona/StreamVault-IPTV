package com.streamvault.domain.repository

import com.streamvault.domain.model.Result
import com.streamvault.domain.model.stremio.StremioPlaybackHistory
import kotlinx.coroutines.flow.Flow

interface StremioPlaybackHistoryRepository {
    fun observeContinueWatching(limit: Int = 20): Flow<List<StremioPlaybackHistory>>

    fun observeHistory(): Flow<List<StremioPlaybackHistory>> = observeContinueWatching(100)

    suspend fun dismiss(contentKey: String): Result<Unit> {
        val item = get(contentKey) ?: return Result.success(Unit)
        return save(item.copy(dismissedAtMs = System.currentTimeMillis()))
    }

    suspend fun get(contentKey: String): StremioPlaybackHistory?

    suspend fun save(history: StremioPlaybackHistory): Result<Unit>

    suspend fun saveAll(history: List<StremioPlaybackHistory>): Result<Unit> {
        for (item in history) { val result = save(item); if (result.isError) return result }
        return Result.success(Unit)
    }

    suspend fun delete(contentKey: String): Result<Unit>
}
