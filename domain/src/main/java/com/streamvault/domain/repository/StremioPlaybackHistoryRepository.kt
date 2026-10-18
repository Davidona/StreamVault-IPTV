package com.streamvault.domain.repository

import com.streamvault.domain.model.Result
import com.streamvault.domain.model.stremio.StremioPlaybackHistory
import kotlinx.coroutines.flow.Flow

interface StremioPlaybackHistoryRepository {
    fun observeContinueWatching(limit: Int = 20): Flow<List<StremioPlaybackHistory>>

    suspend fun get(contentKey: String): StremioPlaybackHistory?

    suspend fun save(history: StremioPlaybackHistory): Result<Unit>

    suspend fun delete(contentKey: String): Result<Unit>
}
