package com.streamvault.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.streamvault.data.local.entity.XtreamContentIndexEntity
import com.streamvault.data.local.entity.XtreamIndexJobEntity
import kotlinx.coroutines.flow.Flow

@Dao
abstract class XtreamContentIndexDao {
    @Query(
        """
        SELECT * FROM xtream_content_index
        WHERE provider_id = :providerId AND content_type = :contentType
        ORDER BY name COLLATE NOCASE ASC
        """
    )
    abstract fun observeByProviderAndType(
        providerId: Long,
        contentType: String
    ): Flow<List<XtreamContentIndexEntity>>

    @Query(
        """
        SELECT * FROM xtream_content_index
        WHERE provider_id = :providerId
          AND content_type = :contentType
          AND remote_id = :remoteId
        LIMIT 1
        """
    )
    abstract suspend fun getByRemoteId(
        providerId: Long,
        contentType: String,
        remoteId: String
    ): XtreamContentIndexEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(entity: XtreamContentIndexEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertAll(entities: List<XtreamContentIndexEntity>)

    @Query(
        """
        UPDATE xtream_content_index
        SET local_content_id = :localContentId,
            image_url = COALESCE(:imageUrl, image_url),
            detail_hydrated_at = :detailHydratedAt,
            error_state = NULL
        WHERE provider_id = :providerId
          AND content_type = :contentType
          AND remote_id = :remoteId
        """
    )
    abstract suspend fun markDetailHydrated(
        providerId: Long,
        contentType: String,
        remoteId: String,
        localContentId: Long,
        imageUrl: String?,
        detailHydratedAt: Long
    ): Int

    @Query(
        """
        UPDATE xtream_content_index
        SET error_state = :errorState
        WHERE provider_id = :providerId
          AND content_type = :contentType
          AND remote_id = :remoteId
        """
    )
    abstract suspend fun markDetailHydrationError(
        providerId: Long,
        contentType: String,
        remoteId: String,
        errorState: String
    ): Int

    @Query(
        """
        UPDATE xtream_content_index
        SET stale_state = 'STALE_REMOTE',
            error_state = NULL
        WHERE provider_id = :providerId
          AND content_type IN ('MOVIE', 'SERIES')
        """
    )
    abstract suspend fun markVodAndSeriesRowsStaleForRebuild(providerId: Long): Int

    @Query(
        """
        UPDATE xtream_content_index
        SET stale_state = 'STALE_REMOTE',
            error_state = NULL
        WHERE provider_id = :providerId
          AND content_type = :contentType
        """
    )
    abstract suspend fun markRowsStaleForProviderAndType(providerId: Long, contentType: String): Int

    @Query("DELETE FROM xtream_content_index WHERE provider_id = :providerId")
    abstract suspend fun deleteByProvider(providerId: Long): Int

    @Query("DELETE FROM xtream_content_index WHERE provider_id = :providerId AND content_type = :contentType")
    abstract suspend fun deleteByProviderAndType(providerId: Long, contentType: String): Int

    @Query("""
        INSERT INTO xtream_content_index (
            provider_id, content_type, remote_id, local_content_id, name,
            category_id, category_name, image_url, container_extension, rating,
            added_at, remote_updated_at, is_adult, indexed_at, detail_hydrated_at,
            stale_state, error_state, sync_fingerprint
        )
        SELECT provider_id, 'LIVE', CAST(stream_id AS TEXT),
            CASE WHEN id > 0 THEN id ELSE NULL END, name,
            category_id, COALESCE(category_name, group_title), logo_url, NULL, 0,
            0, 0, is_adult, :indexedAt, :indexedAt, 'ACTIVE', NULL, sync_fingerprint
        FROM channels WHERE provider_id = :providerId
    """)
    protected abstract suspend fun insertLiveIndexFromChannels(providerId: Long, indexedAt: Long)

    @Query("SELECT COUNT(*) FROM xtream_content_index WHERE provider_id = :providerId AND content_type = 'LIVE'")
    protected abstract suspend fun countLiveIndex(providerId: Long): Int

    @Transaction
    open suspend fun replaceLiveIndexFromChannels(providerId: Long, indexedAt: Long): Int {
        deleteByProviderAndType(providerId, "LIVE")
        insertLiveIndexFromChannels(providerId, indexedAt)
        return countLiveIndex(providerId)
    }

    @Query(
        """
        DELETE FROM movies
        WHERE provider_id = :providerId
          AND EXISTS (
              SELECT 1
              FROM xtream_content_index
              WHERE xtream_content_index.provider_id = movies.provider_id
                AND xtream_content_index.content_type = 'MOVIE'
                AND xtream_content_index.stale_state = 'STALE_REMOTE'
                AND xtream_content_index.remote_id = CAST(movies.stream_id AS TEXT)
          )
        """
    )
    protected abstract suspend fun pruneStaleMovieRows(providerId: Long): Int

    @Query(
        """
        DELETE FROM series
        WHERE provider_id = :providerId
          AND EXISTS (
              SELECT 1
              FROM xtream_content_index
              WHERE xtream_content_index.provider_id = series.provider_id
                AND xtream_content_index.content_type = 'SERIES'
                AND xtream_content_index.stale_state = 'STALE_REMOTE'
                AND xtream_content_index.remote_id = COALESCE(NULLIF(series.provider_series_id, ''), CAST(series.series_id AS TEXT))
          )
        """
    )
    protected abstract suspend fun pruneStaleSeriesRows(providerId: Long): Int

    @Query(
        """
        DELETE FROM xtream_content_index
        WHERE content_type = 'LIVE'
          AND local_content_id IS NOT NULL
          AND NOT EXISTS (
              SELECT 1
              FROM channels
              WHERE channels.id = xtream_content_index.local_content_id
                AND channels.provider_id = xtream_content_index.provider_id
          )
        """
    )
    protected abstract suspend fun pruneOrphanLiveRows(): Int

    @Query(
        """
        DELETE FROM xtream_content_index
        WHERE content_type = 'MOVIE'
          AND local_content_id IS NOT NULL
          AND NOT EXISTS (
              SELECT 1
              FROM movies
              WHERE movies.id = xtream_content_index.local_content_id
                AND movies.provider_id = xtream_content_index.provider_id
          )
        """
    )
    protected abstract suspend fun pruneOrphanMovieRows(): Int

    @Query(
        """
        DELETE FROM xtream_content_index
        WHERE content_type = 'SERIES'
          AND local_content_id IS NOT NULL
          AND NOT EXISTS (
              SELECT 1
              FROM series
              WHERE series.id = xtream_content_index.local_content_id
                AND series.provider_id = xtream_content_index.provider_id
          )
        """
    )
    protected abstract suspend fun pruneOrphanSeriesRows(): Int

    @Transaction
    open suspend fun pruneOrphanLocalContentRows(): Int =
        pruneOrphanLiveRows() + pruneOrphanMovieRows() + pruneOrphanSeriesRows()

    @Transaction
    open suspend fun pruneStaleLocalContentRows(providerId: Long, contentType: String): Int {
        val deleted = when (contentType) {
            "MOVIE" -> pruneStaleMovieRows(providerId)
            "SERIES" -> pruneStaleSeriesRows(providerId)
            else -> 0
        }
        deleteByProviderAndType(providerId, contentType)
        return deleted
    }
}

@Dao
interface XtreamIndexJobDao {
    @Query("SELECT * FROM xtream_index_jobs ORDER BY provider_id ASC, section ASC")
    fun observeAll(): Flow<List<XtreamIndexJobEntity>>

    @Query("SELECT * FROM xtream_index_jobs WHERE provider_id = :providerId ORDER BY section ASC")
    fun observeForProvider(providerId: Long): Flow<List<XtreamIndexJobEntity>>

    @Query("SELECT * FROM xtream_index_jobs WHERE provider_id = :providerId AND section = :section")
    suspend fun get(providerId: Long, section: String): XtreamIndexJobEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: XtreamIndexJobEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<XtreamIndexJobEntity>)

    @Query(
        """
        UPDATE xtream_index_jobs
        SET priority_category_id = :categoryId,
            priority_requested_at = :requestedAt,
            state = CASE
                WHEN state IN ('IDLE', 'SUCCESS', 'STALE', 'FAILED_RETRYABLE', 'PARTIAL') THEN 'QUEUED'
                ELSE state
            END,
            updated_at = :requestedAt
        WHERE provider_id = :providerId AND section = :section
        """
    )
    suspend fun requestCategoryPriority(
        providerId: Long,
        section: String,
        categoryId: Long,
        requestedAt: Long
    ): Int

    @Query("DELETE FROM xtream_index_jobs WHERE provider_id = :providerId")
    suspend fun deleteByProvider(providerId: Long): Int
}
