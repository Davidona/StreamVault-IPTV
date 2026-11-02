package com.streamvault.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.streamvault.data.local.entity.StremioPlaybackHistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface StremioPlaybackHistoryDao {
    @Query("SELECT * FROM stremio_playback_history WHERE is_watched = 0 AND resume_position_ms > 0 AND dismissed_at_ms = 0 ORDER BY last_watched_at DESC LIMIT :limit")
    fun observeContinueWatching(limit: Int): Flow<List<StremioPlaybackHistoryEntity>>

    @Query("SELECT * FROM stremio_playback_history ORDER BY last_watched_at DESC, content_key ASC LIMIT 2000")
    fun observeHistory(): Flow<List<StremioPlaybackHistoryEntity>>

    @Query("UPDATE stremio_playback_history SET dismissed_at_ms = :at WHERE content_key = :key")
    suspend fun dismiss(key: String, at: Long)

    @Query("SELECT * FROM stremio_playback_history WHERE content_key = :contentKey LIMIT 1")
    suspend fun get(contentKey: String): StremioPlaybackHistoryEntity?

    @Query("SELECT * FROM stremio_playback_history ORDER BY last_watched_at DESC, content_key ASC")
    suspend fun getAll(): List<StremioPlaybackHistoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: StremioPlaybackHistoryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<StremioPlaybackHistoryEntity>)

    @Query("DELETE FROM stremio_playback_history WHERE content_key = :contentKey")
    suspend fun delete(contentKey: String)
}
