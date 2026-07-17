package com.streamvault.data.local.dao

import androidx.room.*
import com.streamvault.data.local.entity.*
import com.streamvault.domain.model.ProviderType
import kotlinx.coroutines.flow.Flow

data class RemoteIdMapping(
    @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "remote_id") val remoteId: Long
)

data class SeriesRemoteIdMapping(
    @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "remote_id") val remoteId: String
)

data class TmdbIdMapping(
    @ColumnInfo(name = "tmdb_id") val tmdbId: Long
)

@Dao
abstract class ProviderDao {
    @Query("SELECT * FROM providers ORDER BY created_at DESC")
    abstract fun getAll(): Flow<List<ProviderEntity>>

    @Query("SELECT * FROM providers ORDER BY created_at DESC")
    abstract suspend fun getAllSync(): List<ProviderEntity>

    @Query("SELECT * FROM providers WHERE is_active = 1 LIMIT 1")
    abstract fun getActive(): Flow<ProviderEntity?>

    @Query("SELECT * FROM providers WHERE server_url = :serverUrl AND username = :username AND stalker_mac_address = :stalkerMacAddress")
    abstract suspend fun getByUrlAndUser(
        serverUrl: String,
        username: String,
        stalkerMacAddress: String = ""
    ): ProviderEntity?

    @Query("SELECT * FROM providers WHERE id = :id")
    abstract suspend fun getById(id: Long): ProviderEntity?

    @Query("SELECT * FROM providers WHERE id IN (:ids)")
    abstract suspend fun getByIds(ids: List<Long>): List<ProviderEntity>

    @Query("SELECT * FROM providers WHERE type = :type")
    abstract fun getByTypeSync(type: ProviderType): List<ProviderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertDirect(provider: ProviderEntity): Long

    @Update
    protected abstract suspend fun updateDirect(provider: ProviderEntity)

    @Query("DELETE FROM providers WHERE id = :id")
    abstract suspend fun delete(id: Long)

    @Query("UPDATE providers SET is_active = 0")
    abstract suspend fun deactivateAll()

    @Query("UPDATE providers SET is_active = 1 WHERE id = :id")
    abstract suspend fun activate(id: Long)

    @Query("UPDATE providers SET last_synced_at = :timestamp WHERE id = :id")
    abstract suspend fun updateSyncTime(id: Long, timestamp: Long)

    @Query("UPDATE providers SET epg_url = :epgUrl WHERE id = :id")
    abstract suspend fun updateEpgUrl(id: Long, epgUrl: String)

    @Transaction
    open suspend fun insert(provider: ProviderEntity): Long {
        if (provider.isActive) {
            deactivateAll()
        }
        return insertDirect(provider)
    }

    @Transaction
    open suspend fun update(provider: ProviderEntity) {
        if (provider.isActive) {
            deactivateAll()
        }
        updateDirect(provider)
    }

    /** Atomically deactivates all providers then activates the given one. */
    @Transaction
    open suspend fun setActive(id: Long) {
        deactivateAll()
        activate(id)
    }
}

@Dao
abstract class ChannelDao {
    @Query(
        """
        SELECT c.id, c.stream_id, c.name, c.logo_url, c.group_title, c.category_id, c.category_name, c.stream_url,
               c.epg_channel_id, c.number, c.catch_up_supported, c.catch_up_days, c.catchUpSource,
               c.provider_id, p.guide_source_policy, p.channel_logo_source_policy, ec.icon_url AS epg_icon_url,
               c.is_adult, c.is_user_protected, c.logical_group_id, c.error_count
        FROM channels c
        JOIN providers p ON p.id = c.provider_id
        LEFT JOIN channel_epg_mappings cem ON cem.provider_channel_id = c.id AND cem.provider_id = c.provider_id
        LEFT JOIN epg_channels ec ON ec.epg_source_id = cem.epg_source_id AND ec.xmltv_channel_id = cem.xmltv_channel_id
        WHERE c.provider_id = :providerId
        ORDER BY c.number ASC
        """
    )
    abstract fun getByProvider(providerId: Long): Flow<List<ChannelBrowseEntity>>

    @Query(
        """
        SELECT c.id, c.stream_id, c.name, c.logo_url, c.group_title, c.category_id, c.category_name, c.stream_url,
               c.epg_channel_id, c.number, c.catch_up_supported, c.catch_up_days, c.catchUpSource,
               c.provider_id, p.guide_source_policy, p.channel_logo_source_policy, ec.icon_url AS epg_icon_url,
               c.is_adult, c.is_user_protected, c.logical_group_id, c.error_count
        FROM channels c
        JOIN providers p ON p.id = c.provider_id
        LEFT JOIN channel_epg_mappings cem ON cem.provider_channel_id = c.id AND cem.provider_id = c.provider_id
        LEFT JOIN epg_channels ec ON ec.epg_source_id = cem.epg_source_id AND ec.xmltv_channel_id = cem.xmltv_channel_id
        WHERE c.provider_id = :providerId AND c.error_count = 0
        ORDER BY c.number ASC
        """
    )
    abstract fun getByProviderWithoutErrors(providerId: Long): Flow<List<ChannelBrowseEntity>>

    @Query(
        """
        SELECT c.id, c.stream_id, c.name, c.logo_url, c.group_title, c.category_id, c.category_name, c.stream_url,
               c.epg_channel_id, c.number, c.catch_up_supported, c.catch_up_days, c.catchUpSource,
               c.provider_id, p.guide_source_policy, p.channel_logo_source_policy, ec.icon_url AS epg_icon_url,
               c.is_adult, c.is_user_protected, c.logical_group_id, c.error_count
        FROM channels c
        JOIN providers p ON p.id = c.provider_id
        LEFT JOIN channel_epg_mappings cem ON cem.provider_channel_id = c.id AND cem.provider_id = c.provider_id
        LEFT JOIN epg_channels ec ON ec.epg_source_id = cem.epg_source_id AND ec.xmltv_channel_id = cem.xmltv_channel_id
        WHERE c.provider_id = :providerId AND c.error_count = 0
        ORDER BY c.number ASC
        LIMIT :limit
        """
    )
    abstract fun getByProviderWithoutErrorsBrowsePage(providerId: Long, limit: Int): Flow<List<ChannelBrowseEntity>>

    @Query(
        """
        SELECT c.id, c.stream_id, c.name, c.logo_url, c.group_title, c.category_id, c.category_name, c.stream_url,
               c.epg_channel_id, c.number, c.catch_up_supported, c.catch_up_days, c.catchUpSource,
               c.provider_id, p.guide_source_policy, p.channel_logo_source_policy, ec.icon_url AS epg_icon_url,
               c.is_adult, c.is_user_protected, c.logical_group_id, c.error_count
        FROM channels c
        JOIN providers p ON p.id = c.provider_id
        LEFT JOIN channel_epg_mappings cem ON cem.provider_channel_id = c.id AND cem.provider_id = c.provider_id
        LEFT JOIN epg_channels ec ON ec.epg_source_id = cem.epg_source_id AND ec.xmltv_channel_id = cem.xmltv_channel_id
        WHERE c.provider_id = :providerId
        ORDER BY c.number ASC
        LIMIT :limit
        """
    )
    abstract fun getByProviderBrowsePage(providerId: Long, limit: Int): Flow<List<ChannelBrowseEntity>>

    @Query(
        """
        SELECT c.id, c.stream_id, c.name, c.logo_url, c.group_title, c.category_id, c.category_name, c.stream_url,
               c.epg_channel_id, c.number, c.catch_up_supported, c.catch_up_days, c.catchUpSource,
               c.provider_id, p.guide_source_policy, p.channel_logo_source_policy, ec.icon_url AS epg_icon_url,
               c.is_adult, c.is_user_protected, c.logical_group_id, c.error_count
        FROM channels c
        JOIN providers p ON p.id = c.provider_id
        LEFT JOIN channel_epg_mappings cem ON cem.provider_channel_id = c.id AND cem.provider_id = c.provider_id
        LEFT JOIN epg_channels ec ON ec.epg_source_id = cem.epg_source_id AND ec.xmltv_channel_id = cem.xmltv_channel_id
        WHERE c.provider_id = :providerId AND c.category_id = :categoryId
        ORDER BY c.number ASC
        LIMIT :limit
        """
    )
    abstract fun getByCategoryBrowsePage(providerId: Long, categoryId: Long, limit: Int): Flow<List<ChannelBrowseEntity>>

    @Query("SELECT * FROM channels WHERE provider_id = :providerId ORDER BY number ASC LIMIT :limit OFFSET :offset")
    abstract fun getByProviderPage(providerId: Long, limit: Int, offset: Int): Flow<List<ChannelEntity>>

    @Query(
        """
        SELECT c.id, c.stream_id, c.name, c.logo_url, c.group_title, c.category_id, c.category_name, c.stream_url,
               c.epg_channel_id, c.number, c.catch_up_supported, c.catch_up_days, c.catchUpSource,
               c.provider_id, p.guide_source_policy, p.channel_logo_source_policy, ec.icon_url AS epg_icon_url,
               c.is_adult, c.is_user_protected, c.logical_group_id, c.error_count
        FROM channels c
        JOIN providers p ON p.id = c.provider_id
        LEFT JOIN channel_epg_mappings cem ON cem.provider_channel_id = c.id AND cem.provider_id = c.provider_id
        LEFT JOIN epg_channels ec ON ec.epg_source_id = cem.epg_source_id AND ec.xmltv_channel_id = cem.xmltv_channel_id
        WHERE c.provider_id = :providerId AND c.category_id = :categoryId
        ORDER BY c.number ASC
        """
    )
    abstract fun getByCategory(providerId: Long, categoryId: Long): Flow<List<ChannelBrowseEntity>>

    @Query(
        """
        SELECT c.id, c.stream_id, c.name, c.logo_url, c.group_title, c.category_id, c.category_name, c.stream_url,
               c.epg_channel_id, c.number, c.catch_up_supported, c.catch_up_days, c.catchUpSource,
               c.provider_id, p.guide_source_policy, p.channel_logo_source_policy, ec.icon_url AS epg_icon_url,
               c.is_adult, c.is_user_protected, c.logical_group_id, c.error_count
        FROM channels c
        JOIN providers p ON p.id = c.provider_id
        LEFT JOIN channel_epg_mappings cem ON cem.provider_channel_id = c.id AND cem.provider_id = c.provider_id
        LEFT JOIN epg_channels ec ON ec.epg_source_id = cem.epg_source_id AND ec.xmltv_channel_id = cem.xmltv_channel_id
        WHERE c.provider_id = :providerId AND c.category_id = :categoryId AND c.error_count = 0
        ORDER BY c.number ASC
        """
    )
    abstract fun getByCategoryWithoutErrors(providerId: Long, categoryId: Long): Flow<List<ChannelBrowseEntity>>

    @Query(
        """
        SELECT c.id, c.stream_id, c.name, c.logo_url, c.group_title, c.category_id, c.category_name, c.stream_url,
               c.epg_channel_id, c.number, c.catch_up_supported, c.catch_up_days, c.catchUpSource,
               c.provider_id, p.guide_source_policy, p.channel_logo_source_policy, ec.icon_url AS epg_icon_url,
               c.is_adult, c.is_user_protected, c.logical_group_id, c.error_count
        FROM channels c
        JOIN providers p ON p.id = c.provider_id
        LEFT JOIN channel_epg_mappings cem ON cem.provider_channel_id = c.id AND cem.provider_id = c.provider_id
        LEFT JOIN epg_channels ec ON ec.epg_source_id = cem.epg_source_id AND ec.xmltv_channel_id = cem.xmltv_channel_id
        WHERE c.provider_id = :providerId AND c.category_id = :categoryId AND c.error_count = 0
        ORDER BY c.number ASC
        LIMIT :limit
        """
    )
    abstract fun getByCategoryWithoutErrorsBrowsePage(
        providerId: Long,
        categoryId: Long,
        limit: Int
    ): Flow<List<ChannelBrowseEntity>>

    @Query(
        """
        SELECT c.id, c.stream_id, c.name, c.logo_url, c.group_title, c.category_id, c.category_name, c.stream_url,
               c.epg_channel_id, c.number, c.catch_up_supported, c.catch_up_days, c.catchUpSource,
               c.provider_id, p.guide_source_policy, p.channel_logo_source_policy, ec.icon_url AS epg_icon_url,
               c.is_adult, c.is_user_protected, c.logical_group_id, c.error_count
        FROM channels c
        JOIN providers p ON p.id = c.provider_id
        LEFT JOIN channel_epg_mappings cem ON cem.provider_channel_id = c.id AND cem.provider_id = c.provider_id
        LEFT JOIN epg_channels ec ON ec.epg_source_id = cem.epg_source_id AND ec.xmltv_channel_id = cem.xmltv_channel_id
        WHERE c.provider_id = :providerId
        ORDER BY c.number ASC
        LIMIT :limit OFFSET :offset
        """
    )
    abstract suspend fun getByProviderBrowsePageOffset(providerId: Long, limit: Int, offset: Int): List<ChannelBrowseEntity>

    @Query(
        """
        SELECT c.id, c.stream_id, c.name, c.logo_url, c.group_title, c.category_id, c.category_name, c.stream_url,
               c.epg_channel_id, c.number, c.catch_up_supported, c.catch_up_days, c.catchUpSource,
               c.provider_id, p.guide_source_policy, p.channel_logo_source_policy, ec.icon_url AS epg_icon_url,
               c.is_adult, c.is_user_protected, c.logical_group_id, c.error_count
        FROM channels c
        JOIN providers p ON p.id = c.provider_id
        LEFT JOIN channel_epg_mappings cem ON cem.provider_channel_id = c.id AND cem.provider_id = c.provider_id
        LEFT JOIN epg_channels ec ON ec.epg_source_id = cem.epg_source_id AND ec.xmltv_channel_id = cem.xmltv_channel_id
        WHERE c.provider_id = :providerId AND c.error_count = 0
        ORDER BY c.number ASC
        LIMIT :limit OFFSET :offset
        """
    )
    abstract suspend fun getByProviderWithoutErrorsBrowsePageOffset(providerId: Long, limit: Int, offset: Int): List<ChannelBrowseEntity>

    @Query(
        """
        SELECT c.id, c.stream_id, c.name, c.logo_url, c.group_title, c.category_id, c.category_name, c.stream_url,
               c.epg_channel_id, c.number, c.catch_up_supported, c.catch_up_days, c.catchUpSource,
               c.provider_id, p.guide_source_policy, p.channel_logo_source_policy, ec.icon_url AS epg_icon_url,
               c.is_adult, c.is_user_protected, c.logical_group_id, c.error_count
        FROM channels c
        JOIN providers p ON p.id = c.provider_id
        LEFT JOIN channel_epg_mappings cem ON cem.provider_channel_id = c.id AND cem.provider_id = c.provider_id
        LEFT JOIN epg_channels ec ON ec.epg_source_id = cem.epg_source_id AND ec.xmltv_channel_id = cem.xmltv_channel_id
        WHERE c.provider_id = :providerId AND c.category_id = :categoryId
        ORDER BY c.number ASC
        LIMIT :limit OFFSET :offset
        """
    )
    abstract suspend fun getByCategoryBrowsePageOffset(providerId: Long, categoryId: Long, limit: Int, offset: Int): List<ChannelBrowseEntity>

    @Query(
        """
        SELECT c.id, c.stream_id, c.name, c.logo_url, c.group_title, c.category_id, c.category_name, c.stream_url,
               c.epg_channel_id, c.number, c.catch_up_supported, c.catch_up_days, c.catchUpSource,
               c.provider_id, p.guide_source_policy, p.channel_logo_source_policy, ec.icon_url AS epg_icon_url,
               c.is_adult, c.is_user_protected, c.logical_group_id, c.error_count
        FROM channels c
        JOIN providers p ON p.id = c.provider_id
        LEFT JOIN channel_epg_mappings cem ON cem.provider_channel_id = c.id AND cem.provider_id = c.provider_id
        LEFT JOIN epg_channels ec ON ec.epg_source_id = cem.epg_source_id AND ec.xmltv_channel_id = cem.xmltv_channel_id
        WHERE c.provider_id = :providerId AND c.category_id = :categoryId AND c.error_count = 0
        ORDER BY c.number ASC
        LIMIT :limit OFFSET :offset
        """
    )
    abstract suspend fun getByCategoryWithoutErrorsBrowsePageOffset(providerId: Long, categoryId: Long, limit: Int, offset: Int): List<ChannelBrowseEntity>

    @Query("SELECT * FROM channels WHERE provider_id = :providerId AND category_id = :categoryId ORDER BY number ASC LIMIT :limit OFFSET :offset")
    abstract fun getByCategoryPage(providerId: Long, categoryId: Long, limit: Int, offset: Int): Flow<List<ChannelEntity>>

    @Query(
        """
        SELECT c.id, c.stream_id, c.name, c.logo_url, c.group_title, c.category_id, c.category_name, c.stream_url,
               c.epg_channel_id, c.number, c.catch_up_supported, c.catch_up_days, c.catchUpSource,
               c.provider_id, p.guide_source_policy, p.channel_logo_source_policy, ec.icon_url AS epg_icon_url,
               c.is_adult, c.is_user_protected, c.logical_group_id, c.error_count
        FROM channels c
        JOIN providers p ON p.id = c.provider_id
        JOIN channels_fts ON c.id = channels_fts.rowid
        LEFT JOIN channel_epg_mappings cem ON cem.provider_channel_id = c.id AND cem.provider_id = c.provider_id
        LEFT JOIN epg_channels ec ON ec.epg_source_id = cem.epg_source_id AND ec.xmltv_channel_id = cem.xmltv_channel_id
        WHERE c.provider_id = :providerId
          AND channels_fts MATCH :query
        ORDER BY c.name ASC
        LIMIT :limit
        """
    )
    abstract fun search(providerId: Long, query: String, limit: Int): Flow<List<ChannelBrowseEntity>>

    @Query(
        """
        SELECT c.id, c.stream_id, c.name, c.logo_url, c.group_title, c.category_id, c.category_name, c.stream_url,
               c.epg_channel_id, c.number, c.catch_up_supported, c.catch_up_days, c.catchUpSource,
               c.provider_id, p.guide_source_policy, p.channel_logo_source_policy, ec.icon_url AS epg_icon_url,
               c.is_adult, c.is_user_protected, c.logical_group_id, c.error_count
        FROM channels c
        JOIN providers p ON p.id = c.provider_id
        LEFT JOIN channel_epg_mappings cem ON cem.provider_channel_id = c.id AND cem.provider_id = c.provider_id
        LEFT JOIN epg_channels ec ON ec.epg_source_id = cem.epg_source_id AND ec.xmltv_channel_id = cem.xmltv_channel_id
        WHERE c.provider_id = :providerId
          AND (
              LOWER(c.name) LIKE LOWER(:queryLike) ESCAPE '\'
              OR LOWER(COALESCE(c.group_title, '')) LIKE LOWER(:queryLike) ESCAPE '\'
              OR LOWER(COALESCE(c.category_name, '')) LIKE LOWER(:queryLike) ESCAPE '\'
          )
        ORDER BY c.name ASC
        LIMIT :limit
        """
    )
    abstract fun searchFallback(providerId: Long, queryLike: String, limit: Int): Flow<List<ChannelBrowseEntity>>

    @Query(
        """
        SELECT c.id, c.stream_id, c.name, c.logo_url, c.group_title, c.category_id, c.category_name, c.stream_url,
               c.epg_channel_id, c.number, c.catch_up_supported, c.catch_up_days, c.catchUpSource,
               c.provider_id, p.guide_source_policy, p.channel_logo_source_policy, ec.icon_url AS epg_icon_url,
               c.is_adult, c.is_user_protected, c.logical_group_id, c.error_count
        FROM channels c
        JOIN providers p ON p.id = c.provider_id
        JOIN channels_fts ON c.id = channels_fts.rowid
        LEFT JOIN channel_epg_mappings cem ON cem.provider_channel_id = c.id AND cem.provider_id = c.provider_id
        LEFT JOIN epg_channels ec ON ec.epg_source_id = cem.epg_source_id AND ec.xmltv_channel_id = cem.xmltv_channel_id
        WHERE c.provider_id = :providerId
          AND c.category_id = :categoryId
          AND channels_fts MATCH :query
        ORDER BY c.name ASC
        LIMIT :limit
        """
    )
    abstract fun searchByCategory(providerId: Long, categoryId: Long, query: String, limit: Int): Flow<List<ChannelBrowseEntity>>

    @Query(
        """
        SELECT c.id, c.stream_id, c.name, c.logo_url, c.group_title, c.category_id, c.category_name, c.stream_url,
               c.epg_channel_id, c.number, c.catch_up_supported, c.catch_up_days, c.catchUpSource,
               c.provider_id, p.guide_source_policy, p.channel_logo_source_policy, ec.icon_url AS epg_icon_url,
               c.is_adult, c.is_user_protected, c.logical_group_id, c.error_count
        FROM channels c
        JOIN providers p ON p.id = c.provider_id
        LEFT JOIN channel_epg_mappings cem ON cem.provider_channel_id = c.id AND cem.provider_id = c.provider_id
        LEFT JOIN epg_channels ec ON ec.epg_source_id = cem.epg_source_id AND ec.xmltv_channel_id = cem.xmltv_channel_id
        WHERE c.provider_id = :providerId
          AND c.category_id = :categoryId
          AND (
              LOWER(c.name) LIKE LOWER(:queryLike) ESCAPE '\'
              OR LOWER(COALESCE(c.group_title, '')) LIKE LOWER(:queryLike) ESCAPE '\'
              OR LOWER(COALESCE(c.category_name, '')) LIKE LOWER(:queryLike) ESCAPE '\'
          )
        ORDER BY c.name ASC
        LIMIT :limit
        """
    )
    abstract fun searchByCategoryFallback(providerId: Long, categoryId: Long, queryLike: String, limit: Int): Flow<List<ChannelBrowseEntity>>

    @Query("SELECT * FROM channels WHERE id = :id")
    abstract suspend fun getById(id: Long): ChannelEntity?

    @Query(
        """
        SELECT c.id, c.stream_id, c.name, c.logo_url, c.group_title, c.category_id, c.category_name, c.stream_url,
               c.epg_channel_id, c.number, c.catch_up_supported, c.catch_up_days, c.catchUpSource,
               c.provider_id, p.guide_source_policy, p.channel_logo_source_policy, ec.icon_url AS epg_icon_url,
               c.is_adult, c.is_user_protected, c.logical_group_id, c.error_count
        FROM channels c
        JOIN providers p ON p.id = c.provider_id
        LEFT JOIN channel_epg_mappings cem ON cem.provider_channel_id = c.id AND cem.provider_id = c.provider_id
        LEFT JOIN epg_channels ec ON ec.epg_source_id = cem.epg_source_id AND ec.xmltv_channel_id = cem.xmltv_channel_id
        WHERE c.id = :id
        LIMIT 1
        """
    )
    abstract suspend fun getBrowseById(id: Long): ChannelBrowseEntity?

    @Query(
        """
        SELECT id, stream_id, epg_channel_id
        FROM channels
        WHERE id IN (:ids)
        """
    )
    abstract suspend fun getGuideLookupsByIds(ids: List<Long>): List<ChannelGuideLookupEntity>

    @Query(
        """
        SELECT name, stream_id, epg_channel_id
        FROM channels
        WHERE provider_id = :providerId
        ORDER BY number ASC, id ASC
        """
    )
    abstract suspend fun getGuideSyncEntriesByProvider(providerId: Long): List<ChannelGuideSyncEntity>

    @Query("SELECT * FROM channels WHERE provider_id = :providerId")
    abstract suspend fun getByProviderSync(providerId: Long): List<ChannelEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertAll(channels: List<ChannelEntity>)

    @Update
    abstract suspend fun updateAll(channels: List<ChannelEntity>)

    @Query("SELECT id, stream_id AS remote_id FROM channels WHERE provider_id = :providerId")
    abstract suspend fun getIdMappings(providerId: Long): List<RemoteIdMapping>

    @Query("SELECT COUNT(*) FROM channels WHERE provider_id = :providerId")
    abstract suspend fun countByProvider(providerId: Long): Int

    @Query("DELETE FROM channels WHERE provider_id = :providerId")
    abstract suspend fun deleteByProvider(providerId: Long)

    @Query("DELETE FROM channels WHERE id IN (:ids)")
    abstract suspend fun deleteByIds(ids: List<Long>)

    @Transaction
    open suspend fun replaceAll(providerId: Long, channels: List<ChannelEntity>) {
        val existingByRemoteId = getIdMappings(providerId).associate { it.remoteId to it.id }
        val remapped = channels
            .distinctBy { it.streamId }
            .map { entity -> entity.copy(id = existingByRemoteId[entity.streamId] ?: 0L) }
        deleteByProvider(providerId)
        insertAll(remapped)
    }

    @Query(
        """
        SELECT c.id, c.stream_id, c.name, c.logo_url, c.group_title, c.category_id, c.category_name, c.stream_url,
               c.epg_channel_id, c.number, c.catch_up_supported, c.catch_up_days, c.catchUpSource,
               c.provider_id, p.guide_source_policy, p.channel_logo_source_policy, ec.icon_url AS epg_icon_url,
               c.is_adult, c.is_user_protected, c.logical_group_id, c.error_count
        FROM channels c
        JOIN providers p ON p.id = c.provider_id
        LEFT JOIN channel_epg_mappings cem ON cem.provider_channel_id = c.id AND cem.provider_id = c.provider_id
        LEFT JOIN epg_channels ec ON ec.epg_source_id = cem.epg_source_id AND ec.xmltv_channel_id = cem.xmltv_channel_id
        WHERE c.id IN (:ids)
        """
    )
    abstract fun getByIds(ids: List<Long>): Flow<List<ChannelBrowseEntity>>

    @Query(
        """
        SELECT c.id, c.stream_id, c.name, c.logo_url, c.group_title, c.category_id, c.category_name, c.stream_url,
               c.epg_channel_id, c.number, c.catch_up_supported, c.catch_up_days, c.catchUpSource,
               c.provider_id, p.guide_source_policy, p.channel_logo_source_policy, ec.icon_url AS epg_icon_url,
               c.is_adult, c.is_user_protected, c.logical_group_id, c.error_count
        FROM channels c
        JOIN providers p ON p.id = c.provider_id
        LEFT JOIN channel_epg_mappings cem ON cem.provider_channel_id = c.id AND cem.provider_id = c.provider_id
        LEFT JOIN epg_channels ec ON ec.epg_source_id = cem.epg_source_id AND ec.xmltv_channel_id = cem.xmltv_channel_id
        WHERE c.logical_group_id IN (:logicalGroupIds)
        ORDER BY c.provider_id ASC, c.number ASC, c.name ASC
        """
    )
    abstract fun getByLogicalGroupIds(logicalGroupIds: List<String>): Flow<List<ChannelBrowseEntity>>

    @Query(
        """
        SELECT c.id, c.stream_id, c.name, c.logo_url, c.group_title, c.category_id, c.category_name, c.stream_url,
               c.epg_channel_id, c.number, c.catch_up_supported, c.catch_up_days, c.catchUpSource,
               c.provider_id, p.guide_source_policy, p.channel_logo_source_policy, ec.icon_url AS epg_icon_url,
               c.is_adult, c.is_user_protected, c.logical_group_id, c.error_count
        FROM channels c
        JOIN providers p ON p.id = c.provider_id
        LEFT JOIN channel_epg_mappings cem ON cem.provider_channel_id = c.id AND cem.provider_id = c.provider_id
        LEFT JOIN epg_channels ec ON ec.epg_source_id = cem.epg_source_id AND ec.xmltv_channel_id = cem.xmltv_channel_id
        WHERE c.provider_id = :providerId AND c.logical_group_id = :logicalGroupId
        ORDER BY c.number ASC, c.name ASC
        """
    )
    abstract suspend fun getByLogicalGroupId(providerId: Long, logicalGroupId: String): List<ChannelBrowseEntity>

    @Query("SELECT category_id, COUNT(*) as item_count FROM channels WHERE provider_id = :providerId AND category_id IS NOT NULL GROUP BY category_id")
    abstract fun getRawCategoryCounts(providerId: Long): Flow<List<CategoryCount>>

    @Query(
        """
        SELECT category_id, COUNT(*) as item_count
        FROM channels
        WHERE provider_id = :providerId
          AND category_id IS NOT NULL
          AND NOT (TRIM(name) LIKE '##%' AND TRIM(name) LIKE '%##')
        GROUP BY category_id
        """
    )
    abstract fun getCategoryCounts(providerId: Long): Flow<List<CategoryCount>>

    @Query(
        """
        SELECT
            category_id,
            COUNT(
                DISTINCT CASE
                    WHEN logical_group_id IS NOT NULL AND logical_group_id != '' THEN logical_group_id
                    ELSE CAST(id AS TEXT)
                END
            ) AS item_count
        FROM channels
        WHERE provider_id = :providerId
          AND category_id IS NOT NULL
        GROUP BY category_id
        """
    )
    abstract fun getRawGroupedCategoryCounts(providerId: Long): Flow<List<CategoryCount>>

    @Query(
        """
        SELECT
            category_id,
            COUNT(
                DISTINCT CASE
                    WHEN logical_group_id IS NOT NULL AND logical_group_id != '' THEN logical_group_id
                    ELSE CAST(id AS TEXT)
                END
            ) AS item_count
        FROM channels
        WHERE provider_id = :providerId
          AND category_id IS NOT NULL
          AND NOT (TRIM(name) LIKE '##%' AND TRIM(name) LIKE '%##')
        GROUP BY category_id
        """
    )
    abstract fun getGroupedCategoryCounts(providerId: Long): Flow<List<CategoryCount>>

    @Query("SELECT COUNT(*) FROM channels WHERE provider_id = :providerId")
    abstract fun getRawCount(providerId: Long): Flow<Int>

    @Query(
        """
        SELECT COUNT(*)
        FROM channels
        WHERE provider_id = :providerId
          AND NOT (TRIM(name) LIKE '##%' AND TRIM(name) LIKE '%##')
        """
    )
    abstract fun getCount(providerId: Long): Flow<Int>

    @Query("SELECT COALESCE(MAX(catch_up_days), 0) FROM channels WHERE catch_up_supported = 1")
    abstract suspend fun getMaxCatchUpDaysAcrossAllProviders(): Int

    @Query("UPDATE channels SET is_user_protected = :isProtected WHERE provider_id = :providerId AND category_id = :categoryId")
    abstract suspend fun updateProtectionStatus(providerId: Long, categoryId: Long, isProtected: Boolean)

    @Query("UPDATE channels SET is_user_protected = 0 WHERE provider_id = :providerId AND category_id IN (:categoryIds)")
    abstract suspend fun clearProtectionForCategories(providerId: Long, categoryIds: List<Long>)

    @Query("UPDATE channels SET error_count = error_count + 1 WHERE id = :id")
    abstract suspend fun incrementErrorCount(id: Long)

    @Query("UPDATE channels SET error_count = 0 WHERE id = :id")
    abstract suspend fun resetErrorCount(id: Long)

    @Query("""
        UPDATE channels SET logo_url = (
            SELECT ec.icon_url
            FROM channel_epg_mappings cem
            JOIN epg_channels ec ON ec.epg_source_id = cem.epg_source_id
                AND ec.xmltv_channel_id = cem.xmltv_channel_id
            WHERE cem.provider_channel_id = channels.id
                AND cem.provider_id = :providerId
                AND ec.icon_url IS NOT NULL AND ec.icon_url != ''
            LIMIT 1
        )
        WHERE provider_id = :providerId AND (logo_url IS NULL OR logo_url = '')
    """)
    abstract suspend fun backfillEpgIcons(providerId: Long)
}

@Dao
interface ChannelPreferenceDao {
    @Query("SELECT aspect_ratio FROM channel_preferences WHERE channel_id = :channelId LIMIT 1")
    fun observeAspectRatio(channelId: Long): Flow<String?>

    @Query("SELECT audio_video_offset_ms FROM channel_preferences WHERE channel_id = :channelId LIMIT 1")
    fun observeAudioVideoOffset(channelId: Long): Flow<Int?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(preference: ChannelPreferenceEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(preference: ChannelPreferenceEntity): Long

    @Query("UPDATE channel_preferences SET aspect_ratio = :aspectRatio, updated_at = :updatedAt WHERE channel_id = :channelId")
    suspend fun updateAspectRatio(channelId: Long, aspectRatio: String?, updatedAt: Long): Int

    @Query("UPDATE channel_preferences SET audio_video_offset_ms = :offsetMs, updated_at = :updatedAt WHERE channel_id = :channelId")
    suspend fun updateAudioVideoOffset(channelId: Long, offsetMs: Int?, updatedAt: Long): Int

    @Transaction
    suspend fun setAspectRatio(channelId: Long, aspectRatio: String) {
        val updatedAt = System.currentTimeMillis()
        if (updateAspectRatio(channelId, aspectRatio, updatedAt) == 0) {
            val inserted = insertIgnore(
                ChannelPreferenceEntity(
                    channelId = channelId,
                    aspectRatio = aspectRatio,
                    updatedAt = updatedAt
                )
            )
            if (inserted == -1L) {
                updateAspectRatio(channelId, aspectRatio, updatedAt)
            }
        }
    }

    @Transaction
    suspend fun setAudioVideoOffset(channelId: Long, offsetMs: Int?) {
        val updatedAt = System.currentTimeMillis()
        if (updateAudioVideoOffset(channelId, offsetMs, updatedAt) == 0) {
            val inserted = insertIgnore(
                ChannelPreferenceEntity(
                    channelId = channelId,
                    audioVideoOffsetMs = offsetMs,
                    updatedAt = updatedAt
                )
            )
            if (inserted == -1L) {
                updateAudioVideoOffset(channelId, offsetMs, updatedAt)
            }
        }
    }

    @Query("DELETE FROM channel_preferences")
    suspend fun deleteAll()
}

@Dao
interface PlaybackCompatibilityDao {
    @Query(
        """
        SELECT * FROM playback_compatibility_records
        WHERE device_fingerprint = :deviceFingerprint
          AND stream_type = :streamType
          AND video_mime_type = :videoMimeType
          AND resolution_bucket = :resolutionBucket
        ORDER BY failure_count DESC, last_failed_at DESC
        """
    )
    suspend fun getKnownBadCandidates(
        deviceFingerprint: String,
        streamType: String,
        videoMimeType: String,
        resolutionBucket: String
    ): List<PlaybackCompatibilityRecordEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCompatibilityRecordIgnore(record: PlaybackCompatibilityRecordEntity): Long

    @Query(
        """
        UPDATE playback_compatibility_records
        SET device_model = :deviceModel,
            android_sdk = :androidSdk,
            failure_type = :failureType,
            last_failed_at = :failedAt,
            failure_count = failure_count + 1
        WHERE device_fingerprint = :deviceFingerprint
          AND stream_type = :streamType
          AND video_mime_type = :videoMimeType
          AND resolution_bucket = :resolutionBucket
          AND decoder_name = :decoderName
          AND surface_type = :surfaceType
        """
    )
    suspend fun updateFailureRecord(
        deviceFingerprint: String,
        deviceModel: String,
        androidSdk: Int,
        streamType: String,
        videoMimeType: String,
        resolutionBucket: String,
        decoderName: String,
        surfaceType: String,
        failureType: String,
        failedAt: Long
    ): Int

    @Transaction
    suspend fun recordFailure(
        deviceFingerprint: String,
        deviceModel: String,
        androidSdk: Int,
        streamType: String,
        videoMimeType: String,
        resolutionBucket: String,
        decoderName: String,
        surfaceType: String,
        failureType: String,
        failedAt: Long
    ) {
        val updated = updateFailureRecord(
            deviceFingerprint = deviceFingerprint,
            deviceModel = deviceModel,
            androidSdk = androidSdk,
            streamType = streamType,
            videoMimeType = videoMimeType,
            resolutionBucket = resolutionBucket,
            decoderName = decoderName,
            surfaceType = surfaceType,
            failureType = failureType,
            failedAt = failedAt
        )
        if (updated > 0) return

        val inserted = insertCompatibilityRecordIgnore(
            PlaybackCompatibilityRecordEntity(
                deviceFingerprint = deviceFingerprint,
                deviceModel = deviceModel,
                androidSdk = androidSdk,
                streamType = streamType,
                videoMimeType = videoMimeType,
                resolutionBucket = resolutionBucket,
                decoderName = decoderName,
                surfaceType = surfaceType,
                failureType = failureType,
                lastFailedAt = failedAt,
                failureCount = 1
            )
        )
        if (inserted == -1L) {
            updateFailureRecord(
                deviceFingerprint = deviceFingerprint,
                deviceModel = deviceModel,
                androidSdk = androidSdk,
                streamType = streamType,
                videoMimeType = videoMimeType,
                resolutionBucket = resolutionBucket,
                decoderName = decoderName,
                surfaceType = surfaceType,
                failureType = failureType,
                failedAt = failedAt
            )
        }
    }

    @Query(
        """
        UPDATE playback_compatibility_records
        SET device_model = :deviceModel,
            android_sdk = :androidSdk,
            last_succeeded_at = :succeededAt,
            success_count = success_count + 1
        WHERE device_fingerprint = :deviceFingerprint
          AND stream_type = :streamType
          AND video_mime_type = :videoMimeType
          AND resolution_bucket = :resolutionBucket
          AND decoder_name = :decoderName
          AND surface_type = :surfaceType
        """
    )
    suspend fun updateSuccessRecord(
        deviceFingerprint: String,
        deviceModel: String,
        androidSdk: Int,
        streamType: String,
        videoMimeType: String,
        resolutionBucket: String,
        decoderName: String,
        surfaceType: String,
        succeededAt: Long
    ): Int

    @Transaction
    suspend fun recordSuccess(
        deviceFingerprint: String,
        deviceModel: String,
        androidSdk: Int,
        streamType: String,
        videoMimeType: String,
        resolutionBucket: String,
        decoderName: String,
        surfaceType: String,
        succeededAt: Long
    ) {
        val updated = updateSuccessRecord(
            deviceFingerprint = deviceFingerprint,
            deviceModel = deviceModel,
            androidSdk = androidSdk,
            streamType = streamType,
            videoMimeType = videoMimeType,
            resolutionBucket = resolutionBucket,
            decoderName = decoderName,
            surfaceType = surfaceType,
            succeededAt = succeededAt
        )
        if (updated > 0) return

        val inserted = insertCompatibilityRecordIgnore(
            PlaybackCompatibilityRecordEntity(
                deviceFingerprint = deviceFingerprint,
                deviceModel = deviceModel,
                androidSdk = androidSdk,
                streamType = streamType,
                videoMimeType = videoMimeType,
                resolutionBucket = resolutionBucket,
                decoderName = decoderName,
                surfaceType = surfaceType,
                lastSucceededAt = succeededAt,
                successCount = 1
            )
        )
        if (inserted == -1L) {
            updateSuccessRecord(
                deviceFingerprint = deviceFingerprint,
                deviceModel = deviceModel,
                androidSdk = androidSdk,
                streamType = streamType,
                videoMimeType = videoMimeType,
                resolutionBucket = resolutionBucket,
                decoderName = decoderName,
                surfaceType = surfaceType,
                succeededAt = succeededAt
            )
        }
    }

    @Query("DELETE FROM playback_compatibility_records WHERE last_failed_at < :olderThanMs AND last_succeeded_at < :olderThanMs")
    suspend fun deleteOlderThan(olderThanMs: Long): Int

    @Query(
        """
        DELETE FROM playback_compatibility_records
        WHERE id NOT IN (
            SELECT id FROM playback_compatibility_records
            ORDER BY MAX(last_failed_at, last_succeeded_at) DESC
            LIMIT :maxRecords
        )
        """
    )
    suspend fun keepMostRecent(maxRecords: Int): Int
}

@Dao
interface TmdbIdentityDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(identities: List<TmdbIdentityEntity>)

    @Query(
        """
        DELETE FROM tmdb_identity
        WHERE content_type = 'MOVIE'
          AND NOT EXISTS (
              SELECT 1 FROM movies
              WHERE movies.tmdb_id = tmdb_identity.tmdb_id
          )
        """
    )
    suspend fun pruneOrphanedMovieIdentities()

    @Query(
        """
        DELETE FROM tmdb_identity
        WHERE content_type = 'SERIES'
          AND NOT EXISTS (
              SELECT 1 FROM series
              WHERE series.tmdb_id = tmdb_identity.tmdb_id
          )
        """
    )
    suspend fun pruneOrphanedSeriesIdentities()
}

@Dao
@RewriteQueriesToDropUnusedColumns
interface MovieDao {
    @Query("SELECT * FROM movies WHERE provider_id = :providerId ORDER BY added_at DESC, year DESC, name ASC, id ASC")
    fun getByProvider(providerId: Long): Flow<List<MovieBrowseEntity>>

    /** SQL-level parental filter — avoids loading protected items into memory. */
    @Query(
        """
        SELECT * FROM movies
        WHERE provider_id = :providerId AND is_user_protected = 0
        ORDER BY
            COALESCE(release_date, year) IS NULL,
            COALESCE(release_date, year) DESC,
            added_at DESC,
            name ASC,
            id ASC
        """
    )
    fun getByProviderUnprotected(providerId: Long): Flow<List<MovieBrowseEntity>>

    @Query(
        """
        SELECT * FROM movies
        WHERE provider_id = :providerId
        ORDER BY
            COALESCE(release_date, year) IS NULL,
            COALESCE(release_date, year) DESC,
            added_at DESC,
            name ASC,
            id ASC
        LIMIT :limit OFFSET :offset
        """
    )
    fun getByProviderPage(providerId: Long, limit: Int, offset: Int): Flow<List<MovieBrowseEntity>>

    @Query("SELECT * FROM movies WHERE provider_id = :providerId ORDER BY name ASC, id ASC LIMIT :limit")
    suspend fun getByProviderCursorPage(providerId: Long, limit: Int): List<MovieBrowseEntity>

    @Query(
        """
        SELECT * FROM movies
        WHERE provider_id = :providerId
          AND (name > :lastName OR (name = :lastName AND id > :lastId))
        ORDER BY name ASC, id ASC
        LIMIT :limit
        """
    )
    suspend fun getByProviderCursorPageAfter(providerId: Long, lastName: String, lastId: Long, limit: Int): List<MovieBrowseEntity>

    @Query(
        """
        SELECT movies.* FROM movies
        WHERE movies.provider_id = :providerId
          AND EXISTS (
              SELECT 1 FROM favorites
              WHERE favorites.content_type = 'MOVIE'
                                AND favorites.provider_id = movies.provider_id
                AND favorites.group_id IS NULL
                AND favorites.content_id = movies.id
          )
        ORDER BY movies.name ASC
        LIMIT :limit OFFSET :offset
        """
    )
    fun getFavoritesByProviderPage(providerId: Long, limit: Int, offset: Int): Flow<List<MovieBrowseEntity>>

    @Query(
        """
        SELECT COUNT(*)
        FROM movies
        WHERE provider_id = :providerId
          AND EXISTS (
              SELECT 1 FROM favorites
              WHERE favorites.content_type = 'MOVIE'
                                AND favorites.provider_id = movies.provider_id
                AND favorites.group_id IS NULL
                AND favorites.content_id = movies.id
          )
        """
    )
    fun getFavoriteCountByProvider(providerId: Long): Flow<Int>

    @Query(
        """
        SELECT movies.* FROM movies
        WHERE movies.provider_id = :providerId
          AND EXISTS (
              SELECT 1 FROM playback_history
              WHERE playback_history.provider_id = movies.provider_id
                AND playback_history.content_type = 'MOVIE'
                AND playback_history.content_id = movies.id
                AND playback_history.resume_position_ms > 0
                AND (
                    playback_history.total_duration_ms <= 0
                    OR playback_history.resume_position_ms < CAST(playback_history.total_duration_ms * 0.95 AS INTEGER)
                )
          )
        ORDER BY movies.name ASC
        LIMIT :limit OFFSET :offset
        """
    )
    fun getInProgressByProviderPage(providerId: Long, limit: Int, offset: Int): Flow<List<MovieBrowseEntity>>

    @Query(
        """
        SELECT COUNT(*)
        FROM movies
        WHERE provider_id = :providerId
          AND EXISTS (
              SELECT 1 FROM playback_history
              WHERE playback_history.provider_id = movies.provider_id
                AND playback_history.content_type = 'MOVIE'
                AND playback_history.content_id = movies.id
                AND playback_history.resume_position_ms > 0
                AND (
                    playback_history.total_duration_ms <= 0
                    OR playback_history.resume_position_ms < CAST(playback_history.total_duration_ms * 0.95 AS INTEGER)
                )
          )
        """
    )
    fun getInProgressCountByProvider(providerId: Long): Flow<Int>

    @Query(
        """
        SELECT movies.* FROM movies
        WHERE movies.provider_id = :providerId
          AND NOT EXISTS (
              SELECT 1 FROM playback_history
              WHERE playback_history.provider_id = movies.provider_id
                AND playback_history.content_type = 'MOVIE'
                AND playback_history.content_id = movies.id
                AND playback_history.resume_position_ms > 0
          )
        ORDER BY movies.name ASC
        LIMIT :limit OFFSET :offset
        """
    )
    fun getUnwatchedByProviderPage(providerId: Long, limit: Int, offset: Int): Flow<List<MovieBrowseEntity>>

    @Query(
        """
        SELECT COUNT(*)
        FROM movies
        WHERE movies.provider_id = :providerId
          AND NOT EXISTS (
              SELECT 1 FROM playback_history
              WHERE playback_history.provider_id = movies.provider_id
                AND playback_history.content_type = 'MOVIE'
                AND playback_history.content_id = movies.id
                AND playback_history.resume_position_ms > 0
          )
        """
    )
    fun getUnwatchedCountByProvider(providerId: Long): Flow<Int>

    @Query(
        """
        SELECT movies.* FROM movies
        WHERE movies.provider_id = :providerId
        ORDER BY COALESCE(movies.watch_count, 0) DESC, movies.name ASC
        LIMIT :limit OFFSET :offset
        """
    )
    fun getByWatchCountProviderPage(providerId: Long, limit: Int, offset: Int): Flow<List<MovieBrowseEntity>>

    @Query(
        """
        SELECT movies.* FROM movies
        WHERE movies.provider_id = :providerId
        ORDER BY COALESCE(movies.watch_count, 0) DESC, movies.name ASC, movies.id ASC
        LIMIT :limit
        """
    )
    suspend fun getByWatchCountProviderCursorPage(providerId: Long, limit: Int): List<MovieBrowseEntity>

    @Query(
        """
        SELECT movies.* FROM movies
        WHERE movies.provider_id = :providerId
          AND (
              COALESCE(movies.watch_count, 0) < :lastWatchCount
              OR (
                  COALESCE(movies.watch_count, 0) = :lastWatchCount
                  AND (movies.name > :lastName OR (movies.name = :lastName AND movies.id > :lastId))
              )
          )
        ORDER BY COALESCE(movies.watch_count, 0) DESC, movies.name ASC, movies.id ASC
        LIMIT :limit
        """
    )
    suspend fun getByWatchCountProviderCursorPageAfter(
        providerId: Long,
        lastWatchCount: Int,
        lastName: String,
        lastId: Long,
        limit: Int
    ): List<MovieBrowseEntity>

    @Query("SELECT * FROM movies WHERE provider_id = :providerId AND category_id = :categoryId ORDER BY added_at DESC, year DESC, name ASC, id ASC")
    fun getByCategory(providerId: Long, categoryId: Long): Flow<List<MovieBrowseEntity>>

    @Query("SELECT * FROM movies WHERE provider_id = :providerId AND category_id = :categoryId ORDER BY name ASC, id ASC LIMIT :limit")
    suspend fun getByCategoryCursorPage(providerId: Long, categoryId: Long, limit: Int): List<MovieBrowseEntity>

    @Query(
        """
        SELECT * FROM movies
        WHERE provider_id = :providerId
          AND category_id = :categoryId
          AND (name > :lastName OR (name = :lastName AND id > :lastId))
        ORDER BY name ASC, id ASC
        LIMIT :limit
        """
    )
    suspend fun getByCategoryCursorPageAfter(
        providerId: Long,
        categoryId: Long,
        lastName: String,
        lastId: Long,
        limit: Int
    ): List<MovieBrowseEntity>

    @Query(
        """
        SELECT movies.* FROM movies
        WHERE movies.provider_id = :providerId
          AND movies.category_id = :categoryId
          AND EXISTS (
              SELECT 1 FROM favorites
              WHERE favorites.content_type = 'MOVIE'
                                AND favorites.provider_id = movies.provider_id
                AND favorites.group_id IS NULL
                AND favorites.content_id = movies.id
          )
        ORDER BY movies.name ASC
        LIMIT :limit OFFSET :offset
        """
    )
    fun getFavoritesByCategoryPage(providerId: Long, categoryId: Long, limit: Int, offset: Int): Flow<List<MovieBrowseEntity>>

    @Query(
        """
        SELECT COUNT(*)
        FROM movies
        WHERE provider_id = :providerId
          AND category_id = :categoryId
          AND EXISTS (
              SELECT 1 FROM favorites
              WHERE favorites.content_type = 'MOVIE'
                                AND favorites.provider_id = movies.provider_id
                AND favorites.group_id IS NULL
                AND favorites.content_id = movies.id
          )
        """
    )
    fun getFavoriteCountByCategory(providerId: Long, categoryId: Long): Flow<Int>

    @Query(
        """
        SELECT movies.* FROM movies
        WHERE movies.provider_id = :providerId
          AND movies.category_id = :categoryId
          AND EXISTS (
              SELECT 1 FROM playback_history
              WHERE playback_history.provider_id = movies.provider_id
                AND playback_history.content_type = 'MOVIE'
                AND playback_history.content_id = movies.id
                AND playback_history.resume_position_ms > 0
                AND (
                    playback_history.total_duration_ms <= 0
                    OR playback_history.resume_position_ms < CAST(playback_history.total_duration_ms * 0.95 AS INTEGER)
                )
          )
        ORDER BY movies.name ASC
        LIMIT :limit OFFSET :offset
        """
    )
    fun getInProgressByCategoryPage(providerId: Long, categoryId: Long, limit: Int, offset: Int): Flow<List<MovieBrowseEntity>>

    @Query(
        """
        SELECT COUNT(*)
        FROM movies
        WHERE provider_id = :providerId
          AND category_id = :categoryId
          AND EXISTS (
              SELECT 1 FROM playback_history
              WHERE playback_history.provider_id = movies.provider_id
                AND playback_history.content_type = 'MOVIE'
                AND playback_history.content_id = movies.id
                AND playback_history.resume_position_ms > 0
                AND (
                    playback_history.total_duration_ms <= 0
                    OR playback_history.resume_position_ms < CAST(playback_history.total_duration_ms * 0.95 AS INTEGER)
                )
          )
        """
    )
    fun getInProgressCountByCategory(providerId: Long, categoryId: Long): Flow<Int>

    @Query(
        """
        SELECT movies.* FROM movies
        WHERE movies.provider_id = :providerId
          AND movies.category_id = :categoryId
          AND NOT EXISTS (
              SELECT 1 FROM playback_history
              WHERE playback_history.provider_id = movies.provider_id
                AND playback_history.content_type = 'MOVIE'
                AND playback_history.content_id = movies.id
                AND playback_history.resume_position_ms > 0
          )
        ORDER BY movies.name ASC
        LIMIT :limit OFFSET :offset
        """
    )
    fun getUnwatchedByCategoryPage(providerId: Long, categoryId: Long, limit: Int, offset: Int): Flow<List<MovieBrowseEntity>>

    @Query(
        """
        SELECT COUNT(*)
        FROM movies
        WHERE movies.provider_id = :providerId
          AND movies.category_id = :categoryId
          AND NOT EXISTS (
              SELECT 1 FROM playback_history
              WHERE playback_history.provider_id = movies.provider_id
                AND playback_history.content_type = 'MOVIE'
                AND playback_history.content_id = movies.id
                AND playback_history.resume_position_ms > 0
          )
        """
    )
    fun getUnwatchedCountByCategory(providerId: Long, categoryId: Long): Flow<Int>

    @Query(
        """
        SELECT movies.* FROM movies
        WHERE movies.provider_id = :providerId
          AND movies.category_id = :categoryId
        ORDER BY COALESCE(movies.watch_count, 0) DESC, movies.name ASC
        LIMIT :limit OFFSET :offset
        """
    )
    fun getByWatchCountCategoryPage(providerId: Long, categoryId: Long, limit: Int, offset: Int): Flow<List<MovieBrowseEntity>>

    @Query(
        """
        SELECT movies.* FROM movies
        WHERE movies.provider_id = :providerId
          AND movies.category_id = :categoryId
        ORDER BY COALESCE(movies.watch_count, 0) DESC, movies.name ASC, movies.id ASC
        LIMIT :limit
        """
    )
    suspend fun getByWatchCountCategoryCursorPage(providerId: Long, categoryId: Long, limit: Int): List<MovieBrowseEntity>

    @Query(
        """
        SELECT movies.* FROM movies
        WHERE movies.provider_id = :providerId
          AND movies.category_id = :categoryId
          AND (
              COALESCE(movies.watch_count, 0) < :lastWatchCount
              OR (
                  COALESCE(movies.watch_count, 0) = :lastWatchCount
                  AND (movies.name > :lastName OR (movies.name = :lastName AND movies.id > :lastId))
              )
          )
        ORDER BY COALESCE(movies.watch_count, 0) DESC, movies.name ASC, movies.id ASC
        LIMIT :limit
        """
    )
    suspend fun getByWatchCountCategoryCursorPageAfter(
        providerId: Long,
        categoryId: Long,
        lastWatchCount: Int,
        lastName: String,
        lastId: Long,
        limit: Int
    ): List<MovieBrowseEntity>

    /** SQL-level parental filter per category. */
    @Query(
        """
        SELECT * FROM movies
        WHERE provider_id = :providerId AND category_id = :categoryId AND is_user_protected = 0
        ORDER BY
            COALESCE(release_date, year) IS NULL,
            COALESCE(release_date, year) DESC,
            added_at DESC,
            name ASC,
            id ASC
        """
    )
    fun getByCategoryUnprotected(providerId: Long, categoryId: Long): Flow<List<MovieBrowseEntity>>

    @Query(
        """
        SELECT * FROM movies
        WHERE provider_id = :providerId AND category_id = :categoryId
        ORDER BY
            COALESCE(release_date, year) IS NULL,
            COALESCE(release_date, year) DESC,
            added_at DESC,
            name ASC,
            id ASC
        LIMIT :limit OFFSET :offset
        """
    )
    fun getByCategoryPage(providerId: Long, categoryId: Long, limit: Int, offset: Int): Flow<List<MovieBrowseEntity>>

    @Query(
        """
        SELECT * FROM movies
        WHERE provider_id = :providerId AND category_id = :categoryId
        ORDER BY
            COALESCE(release_date, year) IS NULL,
            COALESCE(release_date, year) DESC,
            added_at DESC,
            name ASC,
            id ASC
        LIMIT :limit
        """
    )
    fun getByCategoryPreview(providerId: Long, categoryId: Long, limit: Int): Flow<List<MovieBrowseEntity>>

    @Query("SELECT * FROM movies WHERE provider_id = :providerId AND rating > 0 ORDER BY rating DESC, name ASC LIMIT :limit")
    fun getTopRatedPreview(providerId: Long, limit: Int): Flow<List<MovieBrowseEntity>>

    @Query("SELECT COUNT(*) FROM movies WHERE provider_id = :providerId AND rating > 0")
    fun getTopRatedCountByProvider(providerId: Long): Flow<Int>

    @Query("SELECT * FROM movies WHERE provider_id = :providerId AND rating > 0 ORDER BY rating DESC, name ASC, id ASC LIMIT :limit")
    suspend fun getTopRatedCursorPage(providerId: Long, limit: Int): List<MovieBrowseEntity>

    @Query(
        """
        SELECT * FROM movies
        WHERE provider_id = :providerId
          AND rating > 0
          AND (
              rating < :lastRating
              OR (rating = :lastRating AND (name > :lastName OR (name = :lastName AND id > :lastId)))
          )
        ORDER BY rating DESC, name ASC, id ASC
        LIMIT :limit
        """
    )
    suspend fun getTopRatedCursorPageAfter(
        providerId: Long,
        lastRating: Float,
        lastName: String,
        lastId: Long,
        limit: Int
    ): List<MovieBrowseEntity>

    @Query("SELECT * FROM movies WHERE provider_id = :providerId AND category_id = :categoryId AND rating > 0 ORDER BY rating DESC, name ASC LIMIT :limit")
    fun getTopRatedByCategoryPreview(providerId: Long, categoryId: Long, limit: Int): Flow<List<MovieBrowseEntity>>

    @Query("SELECT COUNT(*) FROM movies WHERE provider_id = :providerId AND category_id = :categoryId AND rating > 0")
    fun getTopRatedCountByCategory(providerId: Long, categoryId: Long): Flow<Int>

    @Query("SELECT * FROM movies WHERE provider_id = :providerId AND category_id = :categoryId AND rating > 0 ORDER BY rating DESC, name ASC, id ASC LIMIT :limit")
    suspend fun getTopRatedByCategoryCursorPage(providerId: Long, categoryId: Long, limit: Int): List<MovieBrowseEntity>

    @Query(
        """
        SELECT * FROM movies
        WHERE provider_id = :providerId
          AND category_id = :categoryId
          AND rating > 0
          AND (
              rating < :lastRating
              OR (rating = :lastRating AND (name > :lastName OR (name = :lastName AND id > :lastId)))
          )
        ORDER BY rating DESC, name ASC, id ASC
        LIMIT :limit
        """
    )
    suspend fun getTopRatedByCategoryCursorPageAfter(
        providerId: Long,
        categoryId: Long,
        lastRating: Float,
        lastName: String,
        lastId: Long,
        limit: Int
    ): List<MovieBrowseEntity>

    @Query("SELECT * FROM movies WHERE provider_id = :providerId AND added_at > 0 ORDER BY added_at DESC, name ASC, id ASC LIMIT :limit")
    fun getFreshPreview(providerId: Long, limit: Int): Flow<List<MovieBrowseEntity>>

        @Query(
            """
            SELECT * FROM movies
            WHERE provider_id = :providerId
            ORDER BY
                CASE WHEN COALESCE(release_date, '') != '' THEN 1 ELSE 0 END DESC,
                release_date DESC,
                CASE WHEN COALESCE(year, '') != '' THEN 1 ELSE 0 END DESC,
                year DESC,
                added_at DESC,
                name ASC,
                id ASC
            LIMIT :limit
            """
        )
        fun getReleasedPreview(providerId: Long, limit: Int): Flow<List<MovieBrowseEntity>>

    @Query(
        """
        SELECT COUNT(*) FROM movies
        WHERE provider_id = :providerId
          AND (
                            added_at > 0
          )
        """
    )
    fun getFreshCountByProvider(providerId: Long): Flow<Int>

        @Query("SELECT * FROM movies WHERE provider_id = :providerId AND added_at > 0 ORDER BY added_at DESC, name ASC, id ASC LIMIT :limit")
    suspend fun getFreshCursorPage(providerId: Long, limit: Int): List<MovieBrowseEntity>

    @Query(
        """
        SELECT * FROM movies
        WHERE provider_id = :providerId
          AND added_at > 0
          AND (
              added_at < :lastAddedAt
              OR (
                  added_at = :lastAddedAt
                  AND (name > :lastName OR (name = :lastName AND id > :lastId))
              )
          )
        ORDER BY added_at DESC, name ASC, id ASC
        LIMIT :limit
        """
    )
    suspend fun getFreshCursorPageAfter(
        providerId: Long,
        lastAddedAt: Long,
        lastName: String,
        lastId: Long,
        limit: Int
    ): List<MovieBrowseEntity>

    @Query(
        """
        SELECT * FROM movies
        WHERE provider_id = :providerId
        ORDER BY
            CASE WHEN COALESCE(year, '') != '' THEN 1 ELSE 0 END DESC,
            year DESC,
            added_at DESC,
            name ASC,
            id ASC
        LIMIT :limit
        """
    )
    suspend fun getReleasedCursorPage(providerId: Long, limit: Int): List<MovieBrowseEntity>

    @Query(
        """
        SELECT * FROM movies
        WHERE provider_id = :providerId
          AND (
              CASE WHEN COALESCE(year, '') != '' THEN 1 ELSE 0 END
              < CASE WHEN COALESCE(:lastYear, '') != '' THEN 1 ELSE 0 END
              OR (
                  CASE WHEN COALESCE(year, '') != '' THEN 1 ELSE 0 END
                  = CASE WHEN COALESCE(:lastYear, '') != '' THEN 1 ELSE 0 END
                  AND (year < :lastYear OR (year IS NULL AND :lastYear IS NOT NULL) OR (year = :lastYear AND added_at < :lastAddedAt) OR (year = :lastYear AND added_at = :lastAddedAt AND (name > :lastName OR (name = :lastName AND id > :lastId))))
              )
          )
        ORDER BY
            CASE WHEN COALESCE(year, '') != '' THEN 1 ELSE 0 END DESC,
            year DESC,
            added_at DESC,
            name ASC,
            id ASC
        LIMIT :limit
        """
    )
    suspend fun getReleasedCursorPageAfter(
        providerId: Long,
        lastYear: String?,
        lastAddedAt: Long,
        lastName: String,
        lastId: Long,
        limit: Int
    ): List<MovieBrowseEntity>

    @Query("SELECT * FROM movies WHERE provider_id = :providerId AND category_id = :categoryId AND added_at > 0 ORDER BY added_at DESC, name ASC, id ASC LIMIT :limit")
    fun getFreshByCategoryPreview(providerId: Long, categoryId: Long, limit: Int): Flow<List<MovieBrowseEntity>>

        @Query(
            """
            SELECT * FROM movies
            WHERE provider_id = :providerId
              AND category_id = :categoryId
            ORDER BY
                CASE WHEN COALESCE(release_date, '') != '' THEN 1 ELSE 0 END DESC,
                release_date DESC,
                CASE WHEN COALESCE(year, '') != '' THEN 1 ELSE 0 END DESC,
                year DESC,
                added_at DESC,
                name ASC,
                id ASC
            LIMIT :limit
            """
        )
        fun getReleasedByCategoryPreview(providerId: Long, categoryId: Long, limit: Int): Flow<List<MovieBrowseEntity>>

    @Query(
        """
        SELECT COUNT(*) FROM movies
        WHERE provider_id = :providerId
          AND category_id = :categoryId
          AND (
                            added_at > 0
          )
        """
    )
    fun getFreshCountByCategory(providerId: Long, categoryId: Long): Flow<Int>

        @Query("SELECT * FROM movies WHERE provider_id = :providerId AND category_id = :categoryId AND added_at > 0 ORDER BY added_at DESC, name ASC, id ASC LIMIT :limit")
    suspend fun getFreshByCategoryCursorPage(providerId: Long, categoryId: Long, limit: Int): List<MovieBrowseEntity>

    @Query(
        """
        SELECT * FROM movies
        WHERE provider_id = :providerId
          AND category_id = :categoryId
          AND added_at > 0
          AND (
              added_at < :lastAddedAt
              OR (
                  added_at = :lastAddedAt
                  AND (name > :lastName OR (name = :lastName AND id > :lastId))
              )
          )
        ORDER BY added_at DESC, name ASC, id ASC
        LIMIT :limit
        """
    )
    suspend fun getFreshByCategoryCursorPageAfter(
        providerId: Long,
        categoryId: Long,
        lastAddedAt: Long,
        lastName: String,
        lastId: Long,
        limit: Int
    ): List<MovieBrowseEntity>

    @Query(
        """
        SELECT * FROM movies
        WHERE provider_id = :providerId
          AND category_id = :categoryId
        ORDER BY
            CASE WHEN COALESCE(year, '') != '' THEN 1 ELSE 0 END DESC,
            year DESC,
            added_at DESC,
            name ASC,
            id ASC
        LIMIT :limit
        """
    )
    suspend fun getReleasedByCategoryCursorPage(providerId: Long, categoryId: Long, limit: Int): List<MovieBrowseEntity>

    @Query(
        """
        SELECT * FROM movies
        WHERE provider_id = :providerId
          AND category_id = :categoryId
          AND (
              CASE WHEN COALESCE(year, '') != '' THEN 1 ELSE 0 END
              < CASE WHEN COALESCE(:lastYear, '') != '' THEN 1 ELSE 0 END
              OR (
                  CASE WHEN COALESCE(year, '') != '' THEN 1 ELSE 0 END
                  = CASE WHEN COALESCE(:lastYear, '') != '' THEN 1 ELSE 0 END
                  AND (year < :lastYear OR (year IS NULL AND :lastYear IS NOT NULL) OR (year = :lastYear AND added_at < :lastAddedAt) OR (year = :lastYear AND added_at = :lastAddedAt AND (name > :lastName OR (name = :lastName AND id > :lastId))))
              )
          )
        ORDER BY
            CASE WHEN COALESCE(year, '') != '' THEN 1 ELSE 0 END DESC,
            year DESC,
            added_at DESC,
            name ASC,
            id ASC
        LIMIT :limit
        """
    )
    suspend fun getReleasedByCategoryCursorPageAfter(
        providerId: Long,
        categoryId: Long,
        lastYear: String?,
        lastAddedAt: Long,
        lastName: String,
        lastId: Long,
        limit: Int
    ): List<MovieBrowseEntity>

    @Query(
        """
        SELECT m.* FROM movies m
        JOIN movies_fts ON m.id = movies_fts.rowid
        WHERE m.provider_id = :providerId
          AND movies_fts MATCH :query
          AND (:includeProtected != 0 OR m.is_user_protected = 0)
        ORDER BY
          CASE
            WHEN LOWER(m.name) = LOWER(:rawQuery) THEN 0
            WHEN LOWER(m.name) LIKE LOWER(:prefixLike) ESCAPE '\' THEN 1
            ELSE 2
          END ASC,
          m.name ASC
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun searchPage(
        providerId: Long,
        query: String,
        rawQuery: String,
        prefixLike: String,
        includeProtected: Int,
        limit: Int,
        offset: Int
    ): List<MovieBrowseEntity>

    @Query(
        """
        SELECT m.* FROM movies m
        JOIN movies_fts ON m.id = movies_fts.rowid
        WHERE m.provider_id = :providerId
          AND m.category_id = :categoryId
          AND movies_fts MATCH :query
          AND (:includeProtected != 0 OR m.is_user_protected = 0)
        ORDER BY
          CASE
            WHEN LOWER(m.name) = LOWER(:rawQuery) THEN 0
            WHEN LOWER(m.name) LIKE LOWER(:prefixLike) ESCAPE '\' THEN 1
            ELSE 2
          END ASC,
          m.name ASC
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun searchByCategoryPage(
        providerId: Long,
        categoryId: Long,
        query: String,
        rawQuery: String,
        prefixLike: String,
        includeProtected: Int,
        limit: Int,
        offset: Int
    ): List<MovieBrowseEntity>

    @Query(
        """
        SELECT m.* FROM movies m
        JOIN movies_fts ON m.id = movies_fts.rowid
        WHERE m.provider_id = :providerId
          AND movies_fts MATCH :query
        ORDER BY m.name ASC
        LIMIT :limit
        """
    )
    fun search(providerId: Long, query: String, limit: Int): Flow<List<MovieBrowseEntity>>

    @Query(
        """
        SELECT m.* FROM movies m
        WHERE m.provider_id = :providerId
          AND (
              LOWER(m.name) LIKE LOWER(:queryLike) ESCAPE '\'
              OR LOWER(COALESCE(m.genre, '')) LIKE LOWER(:queryLike) ESCAPE '\'
              OR LOWER(COALESCE(m.category_name, '')) LIKE LOWER(:queryLike) ESCAPE '\'
              OR LOWER(COALESCE(m.year, '')) LIKE LOWER(:queryLike) ESCAPE '\'
          )
        ORDER BY m.name ASC
        LIMIT :limit
        """
    )
    fun searchFallback(providerId: Long, queryLike: String, limit: Int): Flow<List<MovieBrowseEntity>>

    @Query(
        """
        SELECT m.* FROM movies m
        JOIN movies_fts ON m.id = movies_fts.rowid
        WHERE m.provider_id = :providerId
          AND m.category_id = :categoryId
          AND movies_fts MATCH :query
        ORDER BY m.name ASC
        LIMIT :limit
        """
    )
    fun searchByCategory(providerId: Long, categoryId: Long, query: String, limit: Int): Flow<List<MovieBrowseEntity>>

    @Query(
        """
        SELECT m.* FROM movies m
        WHERE m.provider_id = :providerId
          AND m.category_id = :categoryId
          AND (
              LOWER(m.name) LIKE LOWER(:queryLike) ESCAPE '\'
              OR LOWER(COALESCE(m.genre, '')) LIKE LOWER(:queryLike) ESCAPE '\'
              OR LOWER(COALESCE(m.category_name, '')) LIKE LOWER(:queryLike) ESCAPE '\'
              OR LOWER(COALESCE(m.year, '')) LIKE LOWER(:queryLike) ESCAPE '\'
          )
        ORDER BY m.name ASC
        LIMIT :limit
        """
    )
    fun searchByCategoryFallback(providerId: Long, categoryId: Long, queryLike: String, limit: Int): Flow<List<MovieBrowseEntity>>

    @Query("SELECT * FROM movies WHERE id = :id")
    suspend fun getById(id: Long): MovieEntity?

    @Query("SELECT * FROM movies WHERE provider_id = :providerId")
    suspend fun getByProviderSync(providerId: Long): List<MovieEntity>

    @Query("SELECT * FROM movies WHERE provider_id = :providerId AND tmdb_id = :tmdbId")
    suspend fun getByProviderAndTmdbIdSync(providerId: Long, tmdbId: Long): List<MovieEntity>

    @Query("SELECT * FROM movies WHERE provider_id = :providerId AND year = :year")
    suspend fun getByProviderAndYearSync(providerId: Long, year: String): List<MovieEntity>

    @Query("SELECT * FROM movies WHERE provider_id = :providerId AND release_date LIKE :yearPrefix")
    suspend fun getByProviderAndReleaseYearPrefixSync(providerId: Long, yearPrefix: String): List<MovieEntity>

    @Query("SELECT tmdb_id FROM movies WHERE provider_id = :providerId AND tmdb_id IS NOT NULL")
    suspend fun getTmdbIdsByProvider(providerId: Long): List<TmdbIdMapping>

    @Query("SELECT * FROM movies WHERE id IN (:ids)")
    fun getByIds(ids: List<Long>): Flow<List<MovieBrowseEntity>>

    @Query("SELECT * FROM movies WHERE provider_id = :providerId AND stream_id = :streamId")
    suspend fun getByStreamId(providerId: Long, streamId: Long): MovieEntity?

    @Query("SELECT * FROM movies WHERE provider_id = :providerId AND stream_id IN (:streamIds)")
    suspend fun getByStreamIds(providerId: Long, streamIds: List<Long>): List<MovieEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(movies: List<MovieEntity>)

    @Query("SELECT id, stream_id AS remote_id FROM movies WHERE provider_id = :providerId")
    suspend fun getIdMappings(providerId: Long): List<RemoteIdMapping>

    @Query("SELECT id, stream_id AS remote_id FROM movies WHERE provider_id = :providerId AND category_id = :categoryId")
    suspend fun getIdMappingsByCategory(providerId: Long, categoryId: Long): List<RemoteIdMapping>

    @Update
    suspend fun update(movie: MovieEntity)

    @Update
    suspend fun updateAll(movies: List<MovieEntity>)

    @Query(
        """
        UPDATE movies
        SET watch_progress = COALESCE((
            SELECT resume_position_ms FROM playback_history
            WHERE playback_history.content_id = movies.id
              AND playback_history.content_type = 'MOVIE'
              AND playback_history.provider_id = movies.provider_id
        ), 0),
            watch_count = COALESCE((
                SELECT watch_count FROM playback_history
                WHERE playback_history.content_id = movies.id
                  AND playback_history.content_type = 'MOVIE'
                  AND playback_history.provider_id = movies.provider_id
            ), 0),
            last_watched_at = COALESCE((
                SELECT last_watched_at FROM playback_history
                WHERE playback_history.content_id = movies.id
                  AND playback_history.content_type = 'MOVIE'
                  AND playback_history.provider_id = movies.provider_id
            ), 0)
        WHERE id = :id AND provider_id = :providerId
        """
    )
    suspend fun syncWatchProgressFromHistory(id: Long, providerId: Long)

    @Query(
        """
        UPDATE movies
        SET watch_progress = COALESCE((
            SELECT resume_position_ms FROM playback_history
            WHERE playback_history.content_id = movies.id
              AND playback_history.content_type = 'MOVIE'
              AND playback_history.provider_id = movies.provider_id
        ), 0),
            watch_count = COALESCE((
                SELECT watch_count FROM playback_history
                WHERE playback_history.content_id = movies.id
                  AND playback_history.content_type = 'MOVIE'
                  AND playback_history.provider_id = movies.provider_id
            ), 0),
            last_watched_at = COALESCE((
                SELECT last_watched_at FROM playback_history
                WHERE playback_history.content_id = movies.id
                  AND playback_history.content_type = 'MOVIE'
                  AND playback_history.provider_id = movies.provider_id
            ), 0)
        WHERE provider_id = :providerId
        """
    )
    suspend fun syncWatchProgressFromHistoryByProvider(providerId: Long)

    @Query(
        """
        UPDATE movies
        SET watch_progress = COALESCE((
            SELECT resume_position_ms FROM playback_history
            WHERE playback_history.content_id = movies.id
              AND playback_history.content_type = 'MOVIE'
              AND playback_history.provider_id = movies.provider_id
        ), 0),
            watch_count = COALESCE((
                SELECT watch_count FROM playback_history
                WHERE playback_history.content_id = movies.id
                  AND playback_history.content_type = 'MOVIE'
                  AND playback_history.provider_id = movies.provider_id
            ), 0),
            last_watched_at = COALESCE((
                SELECT last_watched_at FROM playback_history
                WHERE playback_history.content_id = movies.id
                  AND playback_history.content_type = 'MOVIE'
                  AND playback_history.provider_id = movies.provider_id
            ), 0)
        """
    )
    suspend fun syncAllWatchProgressFromHistory()

    @Query("UPDATE movies SET watch_progress = 0, watch_count = 0, last_watched_at = 0")
    suspend fun resetAllWatchProgress()

    @Query("SELECT COUNT(*) FROM movies WHERE provider_id = :providerId")
    suspend fun countByProvider(providerId: Long): Int

    @Query("DELETE FROM movies WHERE provider_id = :providerId")
    suspend fun deleteByProvider(providerId: Long)

    @Query("DELETE FROM movies WHERE provider_id = :providerId AND category_id = :categoryId")
    suspend fun deleteByProviderAndCategory(providerId: Long, categoryId: Long)

    @Query("DELETE FROM movies WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("DELETE FROM movies WHERE provider_id = :providerId AND category_id = :categoryId AND stream_id NOT IN (:remoteIds)")
    suspend fun deleteMissingByCategory(providerId: Long, categoryId: Long, remoteIds: List<Long>)

    @Query("""
        UPDATE movies 
        SET watch_progress = (
            SELECT resume_position_ms FROM playback_history 
            WHERE playback_history.content_id = movies.id 
            AND playback_history.content_type = 'MOVIE'
            AND playback_history.provider_id = :providerId
        ),
            watch_count = COALESCE((
                SELECT watch_count FROM playback_history
                WHERE playback_history.content_id = movies.id
                AND playback_history.content_type = 'MOVIE'
                AND playback_history.provider_id = :providerId
            ), 0),
            last_watched_at = COALESCE((
                SELECT last_watched_at FROM playback_history
                WHERE playback_history.content_id = movies.id
                AND playback_history.content_type = 'MOVIE'
                AND playback_history.provider_id = :providerId
            ), 0)
        WHERE provider_id = :providerId AND EXISTS (
            SELECT 1 FROM playback_history 
            WHERE playback_history.content_id = movies.id
            AND playback_history.content_type = 'MOVIE' 
            AND playback_history.provider_id = :providerId
        )
    """)
    suspend fun restoreWatchProgress(providerId: Long)

    @Transaction
    suspend fun replaceAll(providerId: Long, movies: List<MovieEntity>) {
        val existingByRemoteId = getIdMappings(providerId).associate { it.remoteId to it.id }
        val remapped = movies
            .distinctBy { it.streamId }
            .map { entity -> entity.copy(id = existingByRemoteId[entity.streamId] ?: 0L) }
        deleteByProvider(providerId)
        insertAll(remapped)
        restoreWatchProgress(providerId)
    }

    @Transaction
    suspend fun replaceCategory(providerId: Long, categoryId: Long, movies: List<MovieEntity>) {
        val existingByRemoteId = getIdMappingsByCategory(providerId, categoryId).associate { it.remoteId to it.id }
        val remapped = movies
            .distinctBy { it.streamId }
            .map { entity -> entity.copy(id = existingByRemoteId[entity.streamId] ?: 0L) }

        if (remapped.isEmpty()) {
            deleteByProviderAndCategory(providerId, categoryId)
        } else {
            insertAll(remapped)
            deleteMissingByCategory(providerId, categoryId, remapped.map { it.streamId })
        }

        restoreWatchProgress(providerId)
    }

    @Transaction
    suspend fun upsertCategoryPage(providerId: Long, movies: List<MovieEntity>) {
        if (movies.isEmpty()) return
        val existingByRemoteId = getIdMappings(providerId).associate { it.remoteId to it.id }
        val remapped = movies
            .distinctBy { it.streamId }
            .map { entity -> entity.copy(id = existingByRemoteId[entity.streamId] ?: 0L) }
        insertAll(remapped)
        restoreWatchProgress(providerId)
    }

    @Query("SELECT category_id, COUNT(*) as item_count FROM movies WHERE provider_id = :providerId AND category_id IS NOT NULL GROUP BY category_id")
    fun getCategoryCounts(providerId: Long): Flow<List<CategoryCount>>

    @Query("SELECT COUNT(*) FROM movies WHERE provider_id = :providerId")
    fun getCount(providerId: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM movies WHERE provider_id = :providerId AND category_id = :categoryId")
    fun getCountByCategory(providerId: Long, categoryId: Long): Flow<Int>

    @Query("UPDATE movies SET is_user_protected = :isProtected WHERE provider_id = :providerId AND category_id = :categoryId")
    suspend fun updateProtectionStatus(providerId: Long, categoryId: Long, isProtected: Boolean)

    @Query("UPDATE movies SET is_user_protected = 0 WHERE provider_id = :providerId AND category_id IN (:categoryIds)")
    suspend fun clearProtectionForCategories(providerId: Long, categoryIds: List<Long>)

    /** Restore year from parenthetical (YYYY) at end of name for rows where
     *  year was cleared or doesn't match. SUBSTR(name, -4, 4) extracts the
     *  4-digit year from the last 4 characters before the closing paren. */
    @Query(
        """
        UPDATE movies SET year = SUBSTR(name, -4, 4)
        WHERE year IS NULL
          AND name LIKE '%(____)'
          AND SUBSTR(name, -4, 1) BETWEEN '1' AND '2'
        """
    )
    suspend fun restoreYearsFromName()

    /** Clear year values that don't match a parenthetical (YYYY) at the end of
     *  the movie name (e.g. '2049' from 'Blade Runner 2049'). */
    @Query(
        "UPDATE movies SET year = NULL WHERE year IS NOT NULL AND SUBSTR(name, -6) != ('(' || year || ')')"
    )
    suspend fun clearInvalidYears()
}

@Dao
@RewriteQueriesToDropUnusedColumns
interface SeriesDao {
    @Query("SELECT * FROM series WHERE provider_id = :providerId ORDER BY last_modified DESC, name ASC, id ASC")
    fun getByProvider(providerId: Long): Flow<List<SeriesBrowseEntity>>

    @Query(
        """
        SELECT * FROM series
        WHERE provider_id = :providerId
        ORDER BY
            COALESCE(release_date, '') IS NULL,
            release_date DESC,
            last_modified DESC,
            name ASC,
            id ASC
        LIMIT :limit OFFSET :offset
        """
    )
    fun getByProviderPage(providerId: Long, limit: Int, offset: Int): Flow<List<SeriesBrowseEntity>>

    @Query("SELECT * FROM series WHERE provider_id = :providerId ORDER BY name ASC, id ASC LIMIT :limit")
    suspend fun getByProviderCursorPage(providerId: Long, limit: Int): List<SeriesBrowseEntity>

    @Query(
        """
        SELECT * FROM series
        WHERE provider_id = :providerId
          AND (name > :lastName OR (name = :lastName AND id > :lastId))
        ORDER BY name ASC, id ASC
        LIMIT :limit
        """
    )
    suspend fun getByProviderCursorPageAfter(providerId: Long, lastName: String, lastId: Long, limit: Int): List<SeriesBrowseEntity>

    @Query(
        """
        SELECT series.* FROM series
        WHERE series.provider_id = :providerId
          AND EXISTS (
              SELECT 1 FROM favorites
              WHERE favorites.content_type = 'SERIES'
                                AND favorites.provider_id = series.provider_id
                AND favorites.group_id IS NULL
                AND favorites.content_id = series.id
          )
        ORDER BY series.name ASC
        LIMIT :limit OFFSET :offset
        """
    )
    fun getFavoritesByProviderPage(providerId: Long, limit: Int, offset: Int): Flow<List<SeriesBrowseEntity>>

    @Query(
        """
        SELECT COUNT(*)
        FROM series
        WHERE provider_id = :providerId
          AND EXISTS (
              SELECT 1 FROM favorites
              WHERE favorites.content_type = 'SERIES'
                                AND favorites.provider_id = series.provider_id
                AND favorites.group_id IS NULL
                AND favorites.content_id = series.id
          )
        """
    )
    fun getFavoriteCountByProvider(providerId: Long): Flow<Int>

    @Query(
        """
        SELECT series.* FROM series
        WHERE series.provider_id = :providerId
          AND EXISTS (
              SELECT 1 FROM playback_history
              WHERE playback_history.provider_id = series.provider_id
                AND playback_history.resume_position_ms > 0
                AND (
                    playback_history.total_duration_ms <= 0
                    OR playback_history.resume_position_ms < CAST(playback_history.total_duration_ms * 0.95 AS INTEGER)
                )
                AND (
                    (playback_history.content_type = 'SERIES' AND playback_history.content_id = series.id)
                    OR (
                        playback_history.content_type = 'SERIES_EPISODE'
                        AND EXISTS (
                            SELECT 1 FROM episodes
                            WHERE episodes.id = playback_history.content_id
                              AND episodes.series_id = series.id
                        )
                    )
                )
          )
        ORDER BY series.name ASC
        LIMIT :limit OFFSET :offset
        """
    )
    fun getInProgressByProviderPage(providerId: Long, limit: Int, offset: Int): Flow<List<SeriesBrowseEntity>>

    @Query(
        """
        SELECT COUNT(*)
        FROM series
        WHERE provider_id = :providerId
          AND EXISTS (
              SELECT 1 FROM playback_history
              WHERE playback_history.provider_id = series.provider_id
                AND playback_history.resume_position_ms > 0
                AND (
                    playback_history.total_duration_ms <= 0
                    OR playback_history.resume_position_ms < CAST(playback_history.total_duration_ms * 0.95 AS INTEGER)
                )
                AND (
                    (playback_history.content_type = 'SERIES' AND playback_history.content_id = series.id)
                    OR (
                        playback_history.content_type = 'SERIES_EPISODE'
                        AND EXISTS (
                            SELECT 1 FROM episodes
                            WHERE episodes.id = playback_history.content_id
                              AND episodes.series_id = series.id
                        )
                    )
                )
          )
        """
    )
    fun getInProgressCountByProvider(providerId: Long): Flow<Int>

    @Query(
        """
        SELECT series.* FROM series
        WHERE series.provider_id = :providerId
          AND (
              NOT EXISTS (
                  SELECT 1 FROM episodes
                  WHERE episodes.series_id = series.id
                    AND episodes.provider_id = series.provider_id
              )
              OR EXISTS (
                  SELECT 1 FROM episodes e
                  WHERE e.series_id = series.id
                    AND e.provider_id = series.provider_id
                    AND NOT EXISTS (
                        SELECT 1 FROM playback_history ph
                        WHERE ph.content_id = e.id
                          AND ph.content_type = 'SERIES_EPISODE'
                          AND ph.provider_id = series.provider_id
                          AND ph.total_duration_ms > 0
                          AND ph.resume_position_ms >= CAST(ph.total_duration_ms * 0.95 AS INTEGER)
                    )
              )
          )
        ORDER BY series.name ASC
        LIMIT :limit OFFSET :offset
        """
    )
    fun getUnwatchedByProviderPage(providerId: Long, limit: Int, offset: Int): Flow<List<SeriesBrowseEntity>>

    @Query(
        """
        SELECT COUNT(*)
        FROM series
        WHERE provider_id = :providerId
          AND (
              NOT EXISTS (
                  SELECT 1 FROM episodes
                  WHERE episodes.series_id = series.id
                    AND episodes.provider_id = :providerId
              )
              OR EXISTS (
                  SELECT 1 FROM episodes e
                  WHERE e.series_id = series.id
                    AND e.provider_id = :providerId
                    AND NOT EXISTS (
                        SELECT 1 FROM playback_history ph
                        WHERE ph.content_id = e.id
                          AND ph.content_type = 'SERIES_EPISODE'
                          AND ph.provider_id = :providerId
                          AND ph.total_duration_ms > 0
                          AND ph.resume_position_ms >= CAST(ph.total_duration_ms * 0.95 AS INTEGER)
                    )
              )
          )
        """
    )
    fun getUnwatchedCountByProvider(providerId: Long): Flow<Int>

    @Query(
        """
        SELECT series.* FROM series
        WHERE series.provider_id = :providerId
        ORDER BY COALESCE((
            SELECT MAX(playback_history.watch_count)
            FROM playback_history
            WHERE playback_history.provider_id = series.provider_id
              AND (
                  (playback_history.content_type = 'SERIES' AND playback_history.content_id = series.id)
                  OR (
                      playback_history.content_type = 'SERIES_EPISODE'
                      AND EXISTS (
                          SELECT 1 FROM episodes
                          WHERE episodes.id = playback_history.content_id
                            AND episodes.series_id = series.id
                      )
                  )
              )
        ), 0) DESC, series.name ASC
        LIMIT :limit OFFSET :offset
        """
    )
    fun getByWatchCountProviderPage(providerId: Long, limit: Int, offset: Int): Flow<List<SeriesBrowseEntity>>

    @Query(
        """
        SELECT series.* FROM series
        WHERE series.provider_id = :providerId
        ORDER BY COALESCE((
            SELECT MAX(playback_history.watch_count)
            FROM playback_history
            WHERE playback_history.provider_id = series.provider_id
              AND (
                  (playback_history.content_type = 'SERIES' AND playback_history.content_id = series.id)
                  OR (
                      playback_history.content_type = 'SERIES_EPISODE'
                      AND EXISTS (
                          SELECT 1 FROM episodes
                          WHERE episodes.id = playback_history.content_id
                            AND episodes.series_id = series.id
                      )
                  )
              )
        ), 0) DESC, series.name ASC, series.id ASC
        LIMIT :limit
        """
    )
    suspend fun getByWatchCountProviderCursorPage(providerId: Long, limit: Int): List<SeriesBrowseEntity>

    @Query(
        """
        SELECT series.* FROM series
        WHERE series.provider_id = :providerId
          AND (
              COALESCE((
                  SELECT MAX(playback_history.watch_count)
                  FROM playback_history
                  WHERE playback_history.provider_id = series.provider_id
                    AND (
                        (playback_history.content_type = 'SERIES' AND playback_history.content_id = series.id)
                        OR (
                            playback_history.content_type = 'SERIES_EPISODE'
                            AND EXISTS (
                                SELECT 1 FROM episodes
                                WHERE episodes.id = playback_history.content_id
                                  AND episodes.series_id = series.id
                            )
                        )
                    )
              ), 0) < :lastWatchCount
              OR (
                  COALESCE((
                      SELECT MAX(playback_history.watch_count)
                      FROM playback_history
                      WHERE playback_history.provider_id = series.provider_id
                        AND (
                            (playback_history.content_type = 'SERIES' AND playback_history.content_id = series.id)
                            OR (
                                playback_history.content_type = 'SERIES_EPISODE'
                                AND EXISTS (
                                    SELECT 1 FROM episodes
                                    WHERE episodes.id = playback_history.content_id
                                      AND episodes.series_id = series.id
                                )
                            )
                        )
                  ), 0) = :lastWatchCount
                  AND (series.name > :lastName OR (series.name = :lastName AND series.id > :lastId))
              )
          )
        ORDER BY COALESCE((
            SELECT MAX(playback_history.watch_count)
            FROM playback_history
            WHERE playback_history.provider_id = series.provider_id
              AND (
                  (playback_history.content_type = 'SERIES' AND playback_history.content_id = series.id)
                  OR (
                      playback_history.content_type = 'SERIES_EPISODE'
                      AND EXISTS (
                          SELECT 1 FROM episodes
                          WHERE episodes.id = playback_history.content_id
                            AND episodes.series_id = series.id
                      )
                  )
              )
        ), 0) DESC, series.name ASC, series.id ASC
        LIMIT :limit
        """
    )
    suspend fun getByWatchCountProviderCursorPageAfter(
        providerId: Long,
        lastWatchCount: Int,
        lastName: String,
        lastId: Long,
        limit: Int
    ): List<SeriesBrowseEntity>

    @Query("SELECT * FROM series WHERE provider_id = :providerId AND category_id = :categoryId ORDER BY last_modified DESC, name ASC, id ASC")
    fun getByCategory(providerId: Long, categoryId: Long): Flow<List<SeriesBrowseEntity>>

    @Query(
        """
        SELECT series.* FROM series
        WHERE series.provider_id = :providerId
          AND series.category_id = :categoryId
          AND EXISTS (
              SELECT 1 FROM favorites
              WHERE favorites.content_type = 'SERIES'
                                AND favorites.provider_id = series.provider_id
                AND favorites.group_id IS NULL
                AND favorites.content_id = series.id
          )
        ORDER BY series.name ASC
        LIMIT :limit OFFSET :offset
        """
    )
    fun getFavoritesByCategoryPage(providerId: Long, categoryId: Long, limit: Int, offset: Int): Flow<List<SeriesBrowseEntity>>

    @Query(
        """
        SELECT COUNT(*)
        FROM series
        WHERE provider_id = :providerId
          AND category_id = :categoryId
          AND EXISTS (
              SELECT 1 FROM favorites
              WHERE favorites.content_type = 'SERIES'
                                AND favorites.provider_id = series.provider_id
                AND favorites.group_id IS NULL
                AND favorites.content_id = series.id
          )
        """
    )
    fun getFavoriteCountByCategory(providerId: Long, categoryId: Long): Flow<Int>

    @Query(
        """
        SELECT series.* FROM series
        WHERE series.provider_id = :providerId
          AND series.category_id = :categoryId
          AND EXISTS (
              SELECT 1 FROM playback_history
              WHERE playback_history.provider_id = series.provider_id
                AND playback_history.resume_position_ms > 0
                AND (
                    playback_history.total_duration_ms <= 0
                    OR playback_history.resume_position_ms < CAST(playback_history.total_duration_ms * 0.95 AS INTEGER)
                )
                AND (
                    (playback_history.content_type = 'SERIES' AND playback_history.content_id = series.id)
                    OR (
                        playback_history.content_type = 'SERIES_EPISODE'
                        AND EXISTS (
                            SELECT 1 FROM episodes
                            WHERE episodes.id = playback_history.content_id
                              AND episodes.series_id = series.id
                        )
                    )
                )
          )
        ORDER BY series.name ASC
        LIMIT :limit OFFSET :offset
        """
    )
    fun getInProgressByCategoryPage(providerId: Long, categoryId: Long, limit: Int, offset: Int): Flow<List<SeriesBrowseEntity>>

    @Query(
        """
        SELECT COUNT(*)
        FROM series
        WHERE provider_id = :providerId
          AND category_id = :categoryId
          AND EXISTS (
              SELECT 1 FROM playback_history
              WHERE playback_history.provider_id = series.provider_id
                AND playback_history.resume_position_ms > 0
                AND (
                    playback_history.total_duration_ms <= 0
                    OR playback_history.resume_position_ms < CAST(playback_history.total_duration_ms * 0.95 AS INTEGER)
                )
                AND (
                    (playback_history.content_type = 'SERIES' AND playback_history.content_id = series.id)
                    OR (
                        playback_history.content_type = 'SERIES_EPISODE'
                        AND EXISTS (
                            SELECT 1 FROM episodes
                            WHERE episodes.id = playback_history.content_id
                              AND episodes.series_id = series.id
                        )
                    )
                )
          )
        """
    )
    fun getInProgressCountByCategory(providerId: Long, categoryId: Long): Flow<Int>

    @Query(
        """
        SELECT series.* FROM series
        WHERE series.provider_id = :providerId
          AND series.category_id = :categoryId
          AND (
              NOT EXISTS (
                  SELECT 1 FROM episodes
                  WHERE episodes.series_id = series.id
                    AND episodes.provider_id = series.provider_id
              )
              OR EXISTS (
                  SELECT 1 FROM episodes e
                  WHERE e.series_id = series.id
                    AND e.provider_id = series.provider_id
                    AND NOT EXISTS (
                        SELECT 1 FROM playback_history ph
                        WHERE ph.content_id = e.id
                          AND ph.content_type = 'SERIES_EPISODE'
                          AND ph.provider_id = series.provider_id
                          AND ph.total_duration_ms > 0
                          AND ph.resume_position_ms >= CAST(ph.total_duration_ms * 0.95 AS INTEGER)
                    )
              )
          )
        ORDER BY series.name ASC
        LIMIT :limit OFFSET :offset
        """
    )
    fun getUnwatchedByCategoryPage(providerId: Long, categoryId: Long, limit: Int, offset: Int): Flow<List<SeriesBrowseEntity>>

    @Query(
        """
        SELECT COUNT(*)
        FROM series
        WHERE provider_id = :providerId
          AND category_id = :categoryId
          AND (
              NOT EXISTS (
                  SELECT 1 FROM episodes
                  WHERE episodes.series_id = series.id
                    AND episodes.provider_id = :providerId
              )
              OR EXISTS (
                  SELECT 1 FROM episodes e
                  WHERE e.series_id = series.id
                    AND e.provider_id = :providerId
                    AND NOT EXISTS (
                        SELECT 1 FROM playback_history ph
                        WHERE ph.content_id = e.id
                          AND ph.content_type = 'SERIES_EPISODE'
                          AND ph.provider_id = :providerId
                          AND ph.total_duration_ms > 0
                          AND ph.resume_position_ms >= CAST(ph.total_duration_ms * 0.95 AS INTEGER)
                    )
              )
          )
        """
    )
    fun getUnwatchedCountByCategory(providerId: Long, categoryId: Long): Flow<Int>

    @Query(
        """
        SELECT series.* FROM series
        WHERE series.provider_id = :providerId
          AND series.category_id = :categoryId
        ORDER BY COALESCE((
            SELECT MAX(playback_history.watch_count)
            FROM playback_history
            WHERE playback_history.provider_id = series.provider_id
              AND (
                  (playback_history.content_type = 'SERIES' AND playback_history.content_id = series.id)
                  OR (
                      playback_history.content_type = 'SERIES_EPISODE'
                      AND EXISTS (
                          SELECT 1 FROM episodes
                          WHERE episodes.id = playback_history.content_id
                            AND episodes.series_id = series.id
                      )
                  )
              )
        ), 0) DESC, series.name ASC
        LIMIT :limit OFFSET :offset
        """
    )
    fun getByWatchCountCategoryPage(providerId: Long, categoryId: Long, limit: Int, offset: Int): Flow<List<SeriesBrowseEntity>>

    @Query(
        """
        SELECT * FROM series
        WHERE provider_id = :providerId AND category_id = :categoryId
        ORDER BY
            COALESCE(release_date, '') IS NULL,
            release_date DESC,
            last_modified DESC,
            name ASC,
            id ASC
        LIMIT :limit OFFSET :offset
        """
    )
    fun getByCategoryPage(providerId: Long, categoryId: Long, limit: Int, offset: Int): Flow<List<SeriesBrowseEntity>>

    @Query("SELECT * FROM series WHERE provider_id = :providerId AND category_id = :categoryId ORDER BY name ASC, id ASC LIMIT :limit")
    suspend fun getByCategoryCursorPage(providerId: Long, categoryId: Long, limit: Int): List<SeriesBrowseEntity>

    @Query(
        """
        SELECT * FROM series
        WHERE provider_id = :providerId
          AND category_id = :categoryId
          AND (name > :lastName OR (name = :lastName AND id > :lastId))
        ORDER BY name ASC, id ASC
        LIMIT :limit
        """
    )
    suspend fun getByCategoryCursorPageAfter(
        providerId: Long,
        categoryId: Long,
        lastName: String,
        lastId: Long,
        limit: Int
    ): List<SeriesBrowseEntity>

    @Query(
        """
        SELECT * FROM series
        WHERE provider_id = :providerId AND category_id = :categoryId
        ORDER BY
            COALESCE(release_date, '') IS NULL,
            release_date DESC,
            last_modified DESC,
            name ASC,
            id ASC
        LIMIT :limit
        """
    )
    fun getByCategoryPreview(providerId: Long, categoryId: Long, limit: Int): Flow<List<SeriesBrowseEntity>>

    @Query("SELECT * FROM series WHERE provider_id = :providerId ORDER BY rating DESC, name ASC LIMIT :limit")
    fun getTopRatedPreview(providerId: Long, limit: Int): Flow<List<SeriesBrowseEntity>>

    @Query("SELECT COUNT(*) FROM series WHERE provider_id = :providerId AND rating > 0")
    fun getTopRatedCountByProvider(providerId: Long): Flow<Int>

    @Query("SELECT * FROM series WHERE provider_id = :providerId ORDER BY rating DESC, name ASC, id ASC LIMIT :limit")
    suspend fun getTopRatedCursorPage(providerId: Long, limit: Int): List<SeriesBrowseEntity>

    @Query(
        """
        SELECT * FROM series
        WHERE provider_id = :providerId
          AND (
              rating < :lastRating
              OR (rating = :lastRating AND (name > :lastName OR (name = :lastName AND id > :lastId)))
          )
        ORDER BY rating DESC, name ASC, id ASC
        LIMIT :limit
        """
    )
    suspend fun getTopRatedCursorPageAfter(
        providerId: Long,
        lastRating: Float,
        lastName: String,
        lastId: Long,
        limit: Int
    ): List<SeriesBrowseEntity>

    @Query("SELECT * FROM series WHERE provider_id = :providerId AND category_id = :categoryId ORDER BY rating DESC, name ASC LIMIT :limit")
    fun getTopRatedByCategoryPreview(providerId: Long, categoryId: Long, limit: Int): Flow<List<SeriesBrowseEntity>>

    @Query("SELECT COUNT(*) FROM series WHERE provider_id = :providerId AND category_id = :categoryId AND rating > 0")
    fun getTopRatedCountByCategory(providerId: Long, categoryId: Long): Flow<Int>

    @Query("SELECT * FROM series WHERE provider_id = :providerId AND category_id = :categoryId ORDER BY rating DESC, name ASC, id ASC LIMIT :limit")
    suspend fun getTopRatedByCategoryCursorPage(providerId: Long, categoryId: Long, limit: Int): List<SeriesBrowseEntity>

    @Query(
        """
        SELECT * FROM series
        WHERE provider_id = :providerId
          AND category_id = :categoryId
          AND (
              rating < :lastRating
              OR (rating = :lastRating AND (name > :lastName OR (name = :lastName AND id > :lastId)))
          )
        ORDER BY rating DESC, name ASC, id ASC
        LIMIT :limit
        """
    )
    suspend fun getTopRatedByCategoryCursorPageAfter(
        providerId: Long,
        categoryId: Long,
        lastRating: Float,
        lastName: String,
        lastId: Long,
        limit: Int
    ): List<SeriesBrowseEntity>

    @Query("SELECT * FROM series WHERE provider_id = :providerId AND last_modified > 0 ORDER BY last_modified DESC, name ASC LIMIT :limit")
    fun getFreshPreview(providerId: Long, limit: Int): Flow<List<SeriesBrowseEntity>>

    @Query("""
        SELECT s.* FROM series s
        LEFT JOIN (
            SELECT series_id, MAX(release_date) AS latest_episode_date
            FROM episodes
            WHERE provider_id = :providerId AND release_date IS NOT NULL AND release_date != ''
            GROUP BY series_id
        ) e ON s.id = e.series_id
        WHERE s.provider_id = :providerId
        ORDER BY
            CASE WHEN COALESCE(e.latest_episode_date, s.release_date, '') != '' THEN 0 ELSE 1 END,
            e.latest_episode_date DESC,
            CASE WHEN COALESCE(s.release_date, '') != '' THEN 0 ELSE 1 END,
            s.release_date DESC,
            s.last_modified DESC,
            s.name ASC,
            s.id ASC
        LIMIT :limit
    """)
    fun getByReleaseDate(providerId: Long, limit: Int): Flow<List<SeriesBrowseEntity>>

    @Query(
        """
        SELECT * FROM series
        WHERE provider_id = :providerId
        ORDER BY
            CASE WHEN COALESCE(release_date, '') != '' THEN 1 ELSE 0 END DESC,
            release_date DESC,
            last_modified DESC,
            name ASC,
            id ASC
        LIMIT :limit
        """
    )
    fun getReleasedPreview(providerId: Long, limit: Int): Flow<List<SeriesBrowseEntity>>

    @Query(
        """
        SELECT COUNT(*) FROM series
                WHERE provider_id = :providerId
                    AND last_modified > 0
        """
    )
    fun getFreshCountByProvider(providerId: Long): Flow<Int>

        @Query("SELECT * FROM series WHERE provider_id = :providerId AND last_modified > 0 ORDER BY last_modified DESC, name ASC, id ASC LIMIT :limit")
    suspend fun getFreshCursorPage(providerId: Long, limit: Int): List<SeriesBrowseEntity>

    @Query(
        """
        SELECT * FROM series
        WHERE provider_id = :providerId
                    AND last_modified > 0
          AND (
              last_modified < :lastModified
              OR (last_modified = :lastModified AND (name > :lastName OR (name = :lastName AND id > :lastId)))
          )
        ORDER BY last_modified DESC, name ASC, id ASC
        LIMIT :limit
        """
    )
    suspend fun getFreshCursorPageAfter(
        providerId: Long,
        lastModified: Long,
        lastName: String,
        lastId: Long,
        limit: Int
    ): List<SeriesBrowseEntity>

    @Query(
        """
        SELECT * FROM series
        WHERE provider_id = :providerId
        ORDER BY
            CASE WHEN COALESCE(release_date, '') != '' THEN 1 ELSE 0 END DESC,
            release_date DESC,
            last_modified DESC,
            name ASC,
            id ASC
        LIMIT :limit
        """
    )
    suspend fun getReleasedCursorPage(providerId: Long, limit: Int): List<SeriesBrowseEntity>

    @Query(
        """
        SELECT * FROM series
        WHERE provider_id = :providerId
          AND (
              CASE WHEN COALESCE(release_date, '') != '' THEN 1 ELSE 0 END
              < CASE WHEN COALESCE(:lastReleaseDate, '') != '' THEN 1 ELSE 0 END
              OR (
                  CASE WHEN COALESCE(release_date, '') != '' THEN 1 ELSE 0 END
                  = CASE WHEN COALESCE(:lastReleaseDate, '') != '' THEN 1 ELSE 0 END
                  AND (release_date < :lastReleaseDate OR (release_date IS NULL AND :lastReleaseDate IS NOT NULL) OR (release_date = :lastReleaseDate AND last_modified < :lastModified) OR (release_date = :lastReleaseDate AND last_modified = :lastModified AND (name > :lastName OR (name = :lastName AND id > :lastId))))
              )
          )
        ORDER BY
            CASE WHEN COALESCE(release_date, '') != '' THEN 1 ELSE 0 END DESC,
            release_date DESC,
            last_modified DESC,
            name ASC,
            id ASC
        LIMIT :limit
        """
    )
    suspend fun getReleasedCursorPageAfter(
        providerId: Long,
        lastReleaseDate: String?,
        lastModified: Long,
        lastName: String,
        lastId: Long,
        limit: Int
    ): List<SeriesBrowseEntity>

    @Query("SELECT * FROM series WHERE provider_id = :providerId AND category_id = :categoryId AND last_modified > 0 ORDER BY last_modified DESC, name ASC LIMIT :limit")
    fun getFreshByCategoryPreview(providerId: Long, categoryId: Long, limit: Int): Flow<List<SeriesBrowseEntity>>

    @Query(
        """
        SELECT * FROM series
        WHERE provider_id = :providerId
          AND category_id = :categoryId
        ORDER BY
            CASE WHEN COALESCE(release_date, '') != '' THEN 1 ELSE 0 END DESC,
            release_date DESC,
            last_modified DESC,
            name ASC,
            id ASC
        LIMIT :limit
        """
    )
    fun getReleasedByCategoryPreview(providerId: Long, categoryId: Long, limit: Int): Flow<List<SeriesBrowseEntity>>

    @Query(
        """
        SELECT COUNT(*) FROM series
                WHERE provider_id = :providerId
                    AND category_id = :categoryId
                    AND last_modified > 0
        """
    )
    fun getFreshCountByCategory(providerId: Long, categoryId: Long): Flow<Int>

        @Query("SELECT * FROM series WHERE provider_id = :providerId AND category_id = :categoryId AND last_modified > 0 ORDER BY last_modified DESC, name ASC, id ASC LIMIT :limit")
    suspend fun getFreshByCategoryCursorPage(providerId: Long, categoryId: Long, limit: Int): List<SeriesBrowseEntity>

    @Query(
        """
        SELECT * FROM series
        WHERE provider_id = :providerId
          AND category_id = :categoryId
                    AND last_modified > 0
          AND (
              last_modified < :lastModified
              OR (last_modified = :lastModified AND (name > :lastName OR (name = :lastName AND id > :lastId)))
          )
        ORDER BY last_modified DESC, name ASC, id ASC
        LIMIT :limit
        """
    )
    suspend fun getFreshByCategoryCursorPageAfter(
        providerId: Long,
        categoryId: Long,
        lastModified: Long,
        lastName: String,
        lastId: Long,
        limit: Int
    ): List<SeriesBrowseEntity>

    @Query(
        """
        SELECT * FROM series
        WHERE provider_id = :providerId
          AND category_id = :categoryId
        ORDER BY
            CASE WHEN COALESCE(release_date, '') != '' THEN 1 ELSE 0 END DESC,
            release_date DESC,
            last_modified DESC,
            name ASC,
            id ASC
        LIMIT :limit
        """
    )
    suspend fun getReleasedByCategoryCursorPage(providerId: Long, categoryId: Long, limit: Int): List<SeriesBrowseEntity>

    @Query(
        """
        SELECT * FROM series
        WHERE provider_id = :providerId
          AND category_id = :categoryId
          AND (
              CASE WHEN COALESCE(release_date, '') != '' THEN 1 ELSE 0 END
              < CASE WHEN COALESCE(:lastReleaseDate, '') != '' THEN 1 ELSE 0 END
              OR (
                  CASE WHEN COALESCE(release_date, '') != '' THEN 1 ELSE 0 END
                  = CASE WHEN COALESCE(:lastReleaseDate, '') != '' THEN 1 ELSE 0 END
                  AND (release_date < :lastReleaseDate OR (release_date IS NULL AND :lastReleaseDate IS NOT NULL) OR (release_date = :lastReleaseDate AND last_modified < :lastModified) OR (release_date = :lastReleaseDate AND last_modified = :lastModified AND (name > :lastName OR (name = :lastName AND id > :lastId))))
              )
          )
        ORDER BY
            CASE WHEN COALESCE(release_date, '') != '' THEN 1 ELSE 0 END DESC,
            release_date DESC,
            last_modified DESC,
            name ASC,
            id ASC
        LIMIT :limit
        """
    )
    suspend fun getReleasedByCategoryCursorPageAfter(
        providerId: Long,
        categoryId: Long,
        lastReleaseDate: String?,
        lastModified: Long,
        lastName: String,
        lastId: Long,
        limit: Int
    ): List<SeriesBrowseEntity>

    @Query(
        """
        SELECT s.* FROM series s
        JOIN series_fts ON s.id = series_fts.rowid
        WHERE s.provider_id = :providerId
          AND series_fts MATCH :query
          AND (:includeProtected != 0 OR s.is_user_protected = 0)
        ORDER BY
          CASE
            WHEN LOWER(s.name) = LOWER(:rawQuery) THEN 0
            WHEN LOWER(s.name) LIKE LOWER(:prefixLike) ESCAPE '\' THEN 1
            ELSE 2
          END ASC,
          s.name ASC
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun searchPage(
        providerId: Long,
        query: String,
        rawQuery: String,
        prefixLike: String,
        includeProtected: Int,
        limit: Int,
        offset: Int
    ): List<SeriesBrowseEntity>

    @Query(
        """
        SELECT s.* FROM series s
        JOIN series_fts ON s.id = series_fts.rowid
        WHERE s.provider_id = :providerId
          AND s.category_id = :categoryId
          AND series_fts MATCH :query
          AND (:includeProtected != 0 OR s.is_user_protected = 0)
        ORDER BY
          CASE
            WHEN LOWER(s.name) = LOWER(:rawQuery) THEN 0
            WHEN LOWER(s.name) LIKE LOWER(:prefixLike) ESCAPE '\' THEN 1
            ELSE 2
          END ASC,
          s.name ASC
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun searchByCategoryPage(
        providerId: Long,
        categoryId: Long,
        query: String,
        rawQuery: String,
        prefixLike: String,
        includeProtected: Int,
        limit: Int,
        offset: Int
    ): List<SeriesBrowseEntity>

    @Query(
        """
        SELECT s.* FROM series s
        JOIN series_fts ON s.id = series_fts.rowid
        WHERE s.provider_id = :providerId
          AND series_fts MATCH :query
        ORDER BY s.name ASC
        LIMIT :limit
        """
    )
    fun search(providerId: Long, query: String, limit: Int): Flow<List<SeriesBrowseEntity>>

    @Query(
        """
        SELECT s.* FROM series s
        WHERE s.provider_id = :providerId
          AND (
              LOWER(s.name) LIKE LOWER(:queryLike) ESCAPE '\'
              OR LOWER(COALESCE(s.genre, '')) LIKE LOWER(:queryLike) ESCAPE '\'
              OR LOWER(COALESCE(s.category_name, '')) LIKE LOWER(:queryLike) ESCAPE '\'
          )
        ORDER BY s.name ASC
        LIMIT :limit
        """
    )
    fun searchFallback(providerId: Long, queryLike: String, limit: Int): Flow<List<SeriesBrowseEntity>>

    @Query(
        """
        SELECT s.* FROM series s
        JOIN series_fts ON s.id = series_fts.rowid
        WHERE s.provider_id = :providerId
          AND s.category_id = :categoryId
          AND series_fts MATCH :query
        ORDER BY s.name ASC
        LIMIT :limit
        """
    )
    fun searchByCategory(providerId: Long, categoryId: Long, query: String, limit: Int): Flow<List<SeriesBrowseEntity>>

    @Query(
        """
        SELECT s.* FROM series s
        WHERE s.provider_id = :providerId
          AND s.category_id = :categoryId
          AND (
              LOWER(s.name) LIKE LOWER(:queryLike) ESCAPE '\'
              OR LOWER(COALESCE(s.genre, '')) LIKE LOWER(:queryLike) ESCAPE '\'
              OR LOWER(COALESCE(s.category_name, '')) LIKE LOWER(:queryLike) ESCAPE '\'
          )
        ORDER BY s.name ASC
        LIMIT :limit
        """
    )
    fun searchByCategoryFallback(providerId: Long, categoryId: Long, queryLike: String, limit: Int): Flow<List<SeriesBrowseEntity>>

    @Query("SELECT * FROM series WHERE id = :id")
    suspend fun getById(id: Long): SeriesEntity?

    @Query("SELECT * FROM series WHERE provider_id = :providerId")
    suspend fun getByProviderSync(providerId: Long): List<SeriesEntity>

    @Query("SELECT * FROM series WHERE provider_id = :providerId AND tmdb_id = :tmdbId")
    suspend fun getByProviderAndTmdbIdSync(providerId: Long, tmdbId: Long): List<SeriesEntity>

    @Query("SELECT * FROM series WHERE provider_id = :providerId AND release_date LIKE :yearPrefix")
    suspend fun getByProviderAndReleaseYearPrefixSync(providerId: Long, yearPrefix: String): List<SeriesEntity>

    @Query("SELECT tmdb_id FROM series WHERE provider_id = :providerId AND tmdb_id IS NOT NULL")
    suspend fun getTmdbIdsByProvider(providerId: Long): List<TmdbIdMapping>

    @Query("SELECT * FROM series WHERE id IN (:ids)")
    fun getByIds(ids: List<Long>): Flow<List<SeriesBrowseEntity>>

    @Query("SELECT * FROM series WHERE provider_id = :providerId AND series_id = :seriesId LIMIT 1")
    suspend fun getBySeriesId(providerId: Long, seriesId: Long): SeriesEntity?

    @Query("SELECT * FROM series WHERE provider_id = :providerId AND series_id IN (:seriesIds)")
    suspend fun getBySeriesIds(providerId: Long, seriesIds: List<Long>): List<SeriesEntity>

    @Query("SELECT * FROM series WHERE provider_id = :providerId AND provider_series_id = :providerSeriesId LIMIT 1")
    suspend fun getByProviderSeriesId(providerId: Long, providerSeriesId: String): SeriesEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(series: List<SeriesEntity>)

    @Update
    suspend fun update(series: SeriesEntity)

    @Update
    suspend fun updateAll(series: List<SeriesEntity>)

    @Query(
        """
        SELECT id, COALESCE(NULLIF(provider_series_id, ''), CAST(series_id AS TEXT)) AS remote_id
        FROM series
        WHERE provider_id = :providerId
        """
    )
    suspend fun getIdMappings(providerId: Long): List<SeriesRemoteIdMapping>

    @Query(
        """
        SELECT id, COALESCE(NULLIF(provider_series_id, ''), CAST(series_id AS TEXT)) AS remote_id
        FROM series
        WHERE provider_id = :providerId AND category_id = :categoryId
        """
    )
    suspend fun getIdMappingsByCategory(providerId: Long, categoryId: Long): List<SeriesRemoteIdMapping>

    @Query("SELECT COUNT(*) FROM series WHERE provider_id = :providerId")
    suspend fun countByProvider(providerId: Long): Int

    @Query("DELETE FROM series WHERE provider_id = :providerId")
    suspend fun deleteByProvider(providerId: Long)

    @Query("DELETE FROM series WHERE provider_id = :providerId AND category_id = :categoryId")
    suspend fun deleteByProviderAndCategory(providerId: Long, categoryId: Long)

    @Query("DELETE FROM series WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Transaction
    suspend fun replaceAll(providerId: Long, series: List<SeriesEntity>) {
        val existingByRemoteId = getIdMappings(providerId).associate { it.remoteId to it.id }
        fun SeriesEntity.remoteKey(): String = providerSeriesId?.takeIf { it.isNotBlank() } ?: seriesId.toString()
        val remapped = series
            .distinctBy { it.remoteKey() }
            .map { entity -> entity.copy(id = existingByRemoteId[entity.remoteKey()] ?: 0L) }
        deleteByProvider(providerId)
        insertAll(remapped)
    }

    @Query(
        """
        DELETE FROM series
        WHERE provider_id = :providerId
          AND category_id = :categoryId
          AND COALESCE(NULLIF(provider_series_id, ''), CAST(series_id AS TEXT)) NOT IN (:remoteIds)
        """
    )
    suspend fun deleteMissingByCategory(providerId: Long, categoryId: Long, remoteIds: List<String>)

    @Transaction
    suspend fun replaceCategory(providerId: Long, categoryId: Long, series: List<SeriesEntity>) {
        val existingByRemoteId = getIdMappingsByCategory(providerId, categoryId).associate { it.remoteId to it.id }
        fun SeriesEntity.remoteKey(): String = providerSeriesId?.takeIf { it.isNotBlank() } ?: seriesId.toString()
        val remapped = series
            .distinctBy { it.remoteKey() }
            .map { entity -> entity.copy(id = existingByRemoteId[entity.remoteKey()] ?: 0L) }

        if (remapped.isEmpty()) {
            deleteByProviderAndCategory(providerId, categoryId)
        } else {
            insertAll(remapped)
            deleteMissingByCategory(providerId, categoryId, remapped.map { it.remoteKey() })
        }
    }

    @Transaction
    suspend fun upsertCategoryPage(providerId: Long, series: List<SeriesEntity>) {
        if (series.isEmpty()) return
        val existingByRemoteId = getIdMappings(providerId).associate { it.remoteId to it.id }
        fun SeriesEntity.remoteKey(): String = providerSeriesId?.takeIf { it.isNotBlank() } ?: seriesId.toString()
        val remapped = series
            .distinctBy { it.remoteKey() }
            .map { entity -> entity.copy(id = existingByRemoteId[entity.remoteKey()] ?: 0L) }
        insertAll(remapped)
    }

    @Query("SELECT category_id, COUNT(*) as item_count FROM series WHERE provider_id = :providerId AND category_id IS NOT NULL GROUP BY category_id")
    fun getCategoryCounts(providerId: Long): Flow<List<CategoryCount>>

    @Query("SELECT COUNT(*) FROM series WHERE provider_id = :providerId")
    fun getCount(providerId: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM series WHERE provider_id = :providerId AND category_id = :categoryId")
    fun getCountByCategory(providerId: Long, categoryId: Long): Flow<Int>

    @Query("UPDATE series SET is_user_protected = :isProtected WHERE provider_id = :providerId AND category_id = :categoryId")
    suspend fun updateProtectionStatus(providerId: Long, categoryId: Long, isProtected: Boolean)

    @Query("UPDATE series SET is_user_protected = 0 WHERE provider_id = :providerId AND category_id IN (:categoryIds)")
    suspend fun clearProtectionForCategories(providerId: Long, categoryIds: List<Long>)
}


@Dao
@RewriteQueriesToDropUnusedColumns
interface EpisodeDao {
    @Query("SELECT * FROM episodes WHERE series_id = :seriesId ORDER BY season_number ASC, episode_number ASC")
    fun getBySeries(seriesId: Long): Flow<List<EpisodeBrowseEntity>>

    @Query("SELECT * FROM episodes WHERE series_id = :seriesId ORDER BY season_number ASC, episode_number ASC")
    suspend fun getBySeriesSync(seriesId: Long): List<EpisodeBrowseEntity>

    @Query("SELECT * FROM episodes WHERE id = :id")
    suspend fun getById(id: Long): EpisodeEntity?

    @Query(
        """
        SELECT COUNT(*)
        FROM episodes
        LEFT JOIN playback_history
            ON playback_history.content_id = episodes.id
           AND playback_history.content_type = 'SERIES_EPISODE'
           AND playback_history.provider_id = episodes.provider_id
        WHERE episodes.provider_id = :providerId
          AND episodes.series_id = :seriesId
          AND (
              COALESCE(playback_history.total_duration_ms, episodes.duration_seconds * 1000) <= 0
              OR COALESCE(playback_history.resume_position_ms, episodes.watch_progress) < CAST(
                  COALESCE(playback_history.total_duration_ms, episodes.duration_seconds * 1000) * :completionThreshold
                  AS INTEGER
              )
          )
        """
    )
    fun getUnwatchedCount(providerId: Long, seriesId: Long, completionThreshold: Float): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(episodes: List<EpisodeEntity>)

    @Query("SELECT id, episode_id AS remote_id FROM episodes WHERE provider_id = :providerId AND series_id = :seriesId")
    suspend fun getIdMappings(providerId: Long, seriesId: Long): List<RemoteIdMapping>

    @Query(
        """
        UPDATE episodes
        SET watch_progress = COALESCE((
            SELECT resume_position_ms FROM playback_history
            WHERE playback_history.content_id = episodes.id
              AND playback_history.content_type = 'SERIES_EPISODE'
              AND playback_history.provider_id = episodes.provider_id
        ), 0),
            last_watched_at = COALESCE((
                SELECT last_watched_at FROM playback_history
                WHERE playback_history.content_id = episodes.id
                  AND playback_history.content_type = 'SERIES_EPISODE'
                  AND playback_history.provider_id = episodes.provider_id
            ), 0)
        WHERE id = :id AND provider_id = :providerId
        """
    )
    suspend fun syncWatchProgressFromHistory(id: Long, providerId: Long)

    @Query(
        """
        UPDATE episodes
        SET watch_progress = COALESCE((
            SELECT resume_position_ms FROM playback_history
            WHERE playback_history.content_id = episodes.id
              AND playback_history.content_type = 'SERIES_EPISODE'
              AND playback_history.provider_id = episodes.provider_id
        ), 0),
            last_watched_at = COALESCE((
                SELECT last_watched_at FROM playback_history
                WHERE playback_history.content_id = episodes.id
                  AND playback_history.content_type = 'SERIES_EPISODE'
                  AND playback_history.provider_id = episodes.provider_id
            ), 0)
        WHERE provider_id = :providerId
        """
    )
    suspend fun syncWatchProgressFromHistoryByProvider(providerId: Long)

    @Query(
        """
        UPDATE episodes
        SET watch_progress = COALESCE((
            SELECT resume_position_ms FROM playback_history
            WHERE playback_history.content_id = episodes.id
              AND playback_history.content_type = 'SERIES_EPISODE'
              AND playback_history.provider_id = episodes.provider_id
        ), 0),
            last_watched_at = COALESCE((
                SELECT last_watched_at FROM playback_history
                WHERE playback_history.content_id = episodes.id
                  AND playback_history.content_type = 'SERIES_EPISODE'
                  AND playback_history.provider_id = episodes.provider_id
            ), 0)
        """
    )
    suspend fun syncAllWatchProgressFromHistory()

    @Query("UPDATE episodes SET watch_progress = 0, last_watched_at = 0")
    suspend fun resetAllWatchProgress()

    @Query("DELETE FROM episodes WHERE series_id = :seriesId")
    suspend fun deleteBySeries(seriesId: Long)

    @Query("DELETE FROM episodes WHERE series_id NOT IN (SELECT id FROM series)")
    suspend fun deleteOrphans(): Int

    @Query("""
        UPDATE episodes 
        SET watch_progress = (
            SELECT resume_position_ms FROM playback_history 
            WHERE playback_history.content_id = episodes.id 
            AND playback_history.content_type = 'SERIES_EPISODE'
            AND playback_history.provider_id = episodes.provider_id
        )
        WHERE series_id = :seriesId AND EXISTS (
            SELECT 1 FROM playback_history 
            WHERE playback_history.content_id = episodes.id
            AND playback_history.content_type = 'SERIES_EPISODE' 
            AND playback_history.provider_id = episodes.provider_id
        )
    """)
    suspend fun restoreWatchProgress(seriesId: Long)

    @Transaction
    suspend fun replaceAll(seriesId: Long, providerId: Long, episodes: List<EpisodeEntity>) {
        val existingByRemoteId = getIdMappings(providerId, seriesId).associate { it.remoteId to it.id }
        val remapped = episodes
            .distinctBy { it.episodeId }
            .map { entity -> entity.copy(id = existingByRemoteId[entity.episodeId] ?: 0L) }
        deleteBySeries(seriesId)
        insertAll(remapped)
        restoreWatchProgress(seriesId)
    }
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories WHERE provider_id = :providerId AND type = :type ORDER BY id ASC")
    fun getByProviderAndType(providerId: Long, type: String): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE provider_id = :providerId AND type = :type ORDER BY id ASC")
    suspend fun getByProviderAndTypeSync(providerId: Long, type: String): List<CategoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(categories: List<CategoryEntity>)

    @Update
    suspend fun updateAll(categories: List<CategoryEntity>)

    @Query("DELETE FROM categories WHERE provider_id = :providerId AND type = :type")
    suspend fun deleteByProviderAndType(providerId: Long, type: String)

    @Query("DELETE FROM categories WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Transaction
    suspend fun replaceAll(providerId: Long, type: String, categories: List<CategoryEntity>) {
        deleteByProviderAndType(providerId, type)
        insertAll(categories)
    }

    @Query("UPDATE categories SET is_user_protected = :isProtected WHERE provider_id = :providerId AND category_id = :categoryId AND type = :type")
    suspend fun updateProtectionStatus(providerId: Long, categoryId: Long, type: String, isProtected: Boolean)
}

@Dao
@RewriteQueriesToDropUnusedColumns
interface ProgramDao {
    @Query(
        """
        SELECT
            id,
            provider_id,
            channel_id,
            title,
            CASE
                WHEN LENGTH(description) > 600 THEN SUBSTR(description, 1, 600) || '...'
                ELSE description
            END AS description,
            start_time,
            end_time,
            lang,
                        rating,
                        image_url,
                        genre,
                        category,
            has_archive
        FROM programs
        WHERE provider_id = :providerId
          AND channel_id = :channelId
          AND end_time > :startTime
          AND start_time < :endTime
        ORDER BY start_time ASC
        """
    )
    fun getForChannel(providerId: Long, channelId: String, startTime: Long, endTime: Long): Flow<List<ProgramBrowseEntity>>

    @Query(
        """
        SELECT
            id,
            provider_id,
            channel_id,
            title,
            CASE
                WHEN LENGTH(description) > 600 THEN SUBSTR(description, 1, 600) || '...'
                ELSE description
            END AS description,
            start_time,
            end_time,
            lang,
                        rating,
                        image_url,
                        genre,
                        category,
            has_archive
        FROM programs
        WHERE provider_id = :providerId
          AND channel_id IN (:channelIds)
          AND end_time > :startTime
          AND start_time < :endTime
        ORDER BY channel_id ASC, start_time ASC
        """
    )
    fun getForChannels(providerId: Long, channelIds: List<String>, startTime: Long, endTime: Long): Flow<List<ProgramBrowseEntity>>

    @Query(
        """
        SELECT
            programs.id,
            programs.provider_id,
            programs.channel_id,
            programs.title,
            CASE
                WHEN LENGTH(programs.description) > 600 THEN SUBSTR(programs.description, 1, 600) || '...'
                ELSE programs.description
            END AS description,
            programs.start_time,
            programs.end_time,
            programs.lang,
            programs.rating,
            programs.image_url,
            programs.genre,
            programs.category,
            programs.has_archive
        FROM programs
        INNER JOIN channels
            ON channels.provider_id = programs.provider_id
           AND (
               channels.epg_channel_id = programs.channel_id
               OR CAST(channels.stream_id AS TEXT) = programs.channel_id
           )
        WHERE programs.provider_id = :providerId
          AND channels.category_id = :categoryId
          AND programs.end_time > :startTime
          AND programs.start_time < :endTime
        ORDER BY channels.number ASC, programs.channel_id ASC, programs.start_time ASC
        """
    )
    fun getForCategory(providerId: Long, categoryId: Long, startTime: Long, endTime: Long): Flow<List<ProgramBrowseEntity>>

    @Query(
        """
        SELECT
            programs.id,
            programs.provider_id,
            programs.channel_id,
            programs.title,
            CASE
                WHEN LENGTH(programs.description) > 600 THEN SUBSTR(programs.description, 1, 600) || '...'
                ELSE programs.description
            END AS description,
            programs.start_time,
            programs.end_time,
            programs.lang,
                        programs.rating,
                        programs.image_url,
                        programs.genre,
                        programs.category,
            programs.has_archive
        FROM programs
        WHERE programs.provider_id = :providerId
          AND programs.end_time > :startTime
          AND programs.start_time < :endTime
          AND (
              LOWER(programs.title) LIKE LOWER(:queryPattern) ESCAPE '\'
              OR LOWER(programs.description) LIKE LOWER(:queryPattern) ESCAPE '\'
          )
          AND (
              :categoryId IS NULL
              OR EXISTS (
                  SELECT 1 FROM channels
                  WHERE channels.provider_id = programs.provider_id
                    AND (
                        channels.epg_channel_id = programs.channel_id
                        OR CAST(channels.stream_id AS TEXT) = programs.channel_id
                    )
                    AND channels.category_id = :categoryId
              )
          )
        ORDER BY programs.start_time ASC, programs.channel_id ASC
        LIMIT :limit
        """
    )
    fun searchPrograms(
        providerId: Long,
        queryPattern: String,
        startTime: Long,
        endTime: Long,
        categoryId: Long?,
        limit: Int
    ): Flow<List<ProgramBrowseEntity>>

    @Query(
        """
        SELECT
            id,
            provider_id,
            channel_id,
            title,
            CASE
                WHEN LENGTH(description) > 600 THEN SUBSTR(description, 1, 600) || '...'
                ELSE description
            END AS description,
            start_time,
            end_time,
            lang,
                        rating,
                        image_url,
                        genre,
                        category,
            has_archive
        FROM programs
        WHERE provider_id = :providerId
          AND channel_id = :channelId
          AND start_time <= :now
          AND end_time > :now
        LIMIT 1
        """
    )
    fun getNowPlaying(providerId: Long, channelId: String, now: Long): Flow<ProgramBrowseEntity?>

    @Query(
        """
        SELECT
            id,
            provider_id,
            channel_id,
            title,
            CASE
                WHEN LENGTH(description) > 600 THEN SUBSTR(description, 1, 600) || '...'
                ELSE description
            END AS description,
            start_time,
            end_time,
            lang,
                        rating,
                        image_url,
                        genre,
                        category,
            has_archive
        FROM programs
        WHERE provider_id = :providerId
          AND channel_id IN (:channelIds)
          AND start_time <= :now
          AND end_time > :now
        """
    )
    fun getNowPlayingForChannels(providerId: Long, channelIds: List<String>, now: Long): Flow<List<ProgramBrowseEntity>>

    @Query(
        """
        SELECT
            id,
            provider_id,
            channel_id,
            title,
            CASE
                WHEN LENGTH(description) > 600 THEN SUBSTR(description, 1, 600) || '...'
                ELSE description
            END AS description,
            start_time,
            end_time,
            lang,
                        rating,
                        image_url,
                        genre,
                        category,
            has_archive
        FROM programs
        WHERE provider_id = :providerId
          AND channel_id IN (:channelIds)
          AND start_time <= :now
          AND end_time > :now
        """
    )
    suspend fun getNowPlayingForChannelsSync(providerId: Long, channelIds: List<String>, now: Long): List<ProgramBrowseEntity>

    @Query(
        """
        SELECT
            id,
            provider_id,
            channel_id,
            title,
            CASE
                WHEN LENGTH(description) > 600 THEN SUBSTR(description, 1, 600) || '...'
                ELSE description
            END AS description,
            start_time,
            end_time,
            lang,
                        rating,
                        image_url,
                        genre,
                        category,
            has_archive
        FROM programs
        WHERE provider_id = :providerId
          AND channel_id IN (:channelIds)
          AND end_time > :startTime
          AND start_time < :endTime
        ORDER BY channel_id ASC, start_time ASC
        """
    )
    suspend fun getForChannelsSync(providerId: Long, channelIds: List<String>, startTime: Long, endTime: Long): List<ProgramBrowseEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(programs: List<ProgramEntity>)

        @Query(
                """
                SELECT DISTINCT channel_id
                FROM programs
                WHERE provider_id = :providerId
                    AND channel_id IN (:channelIds)
                """
        )
        suspend fun getChannelIdsWithPrograms(providerId: Long, channelIds: List<String>): List<String>

    @Query("SELECT COUNT(*) FROM programs WHERE provider_id = :providerId")
    suspend fun countByProvider(providerId: Long): Int

    @Query("SELECT COUNT(*) FROM programs WHERE provider_id = :providerId")
    fun observeCountByProvider(providerId: Long): kotlinx.coroutines.flow.Flow<Int>

    @Query("DELETE FROM programs WHERE end_time < :beforeTime")
    suspend fun deleteOld(beforeTime: Long): Int

    @Query("DELETE FROM programs WHERE provider_id = :providerId")
    suspend fun deleteByProvider(providerId: Long)

    @Query("UPDATE programs SET provider_id = :targetProviderId WHERE provider_id = :sourceProviderId")
    suspend fun moveToProvider(sourceProviderId: Long, targetProviderId: Long)

    @Query("DELETE FROM programs WHERE provider_id = :providerId AND channel_id = :channelId")
    suspend fun deleteForChannel(providerId: Long, channelId: String)
}

data class FavoriteGroupConstraint(
    @ColumnInfo(name = "provider_id") val providerId: Long,
    @ColumnInfo(name = "content_type") val contentType: String
)

@Dao
abstract class FavoriteDao {
    @Query("SELECT * FROM favorites WHERE provider_id = :providerId AND group_id IS NULL ORDER BY position ASC")
    abstract fun getAllGlobal(providerId: Long): Flow<List<FavoriteEntity>>

    @Query("SELECT * FROM favorites WHERE provider_id IN (:providerIds) AND group_id IS NULL ORDER BY provider_id ASC, position ASC")
    abstract fun getAllGlobalByProviders(providerIds: List<Long>): Flow<List<FavoriteEntity>>

    @Query("SELECT * FROM favorites WHERE provider_id = :providerId AND content_type = :contentType AND group_id IS NULL ORDER BY position ASC")
    abstract fun getGlobalByType(providerId: Long, contentType: String): Flow<List<FavoriteEntity>>

    @Query("SELECT * FROM favorites WHERE provider_id IN (:providerIds) AND content_type = :contentType AND group_id IS NULL ORDER BY provider_id ASC, position ASC")
    abstract fun getGlobalByTypeForProviders(providerIds: List<Long>, contentType: String): Flow<List<FavoriteEntity>>

    @Query("SELECT * FROM favorites WHERE provider_id = :providerId AND content_type = :contentType ORDER BY position ASC")
    abstract fun getAllByType(providerId: Long, contentType: String): Flow<List<FavoriteEntity>>

    @Query("SELECT * FROM favorites WHERE provider_id IN (:providerIds) AND content_type = :contentType ORDER BY provider_id ASC, position ASC")
    abstract fun getAllByTypeForProviders(providerIds: List<Long>, contentType: String): Flow<List<FavoriteEntity>>

    @Query(
        """
        SELECT f.*
        FROM favorites AS f
        INNER JOIN virtual_groups AS g
            ON g.id = f.group_id
           AND g.provider_id = f.provider_id
           AND g.content_type = f.content_type
        WHERE g.id = :groupId
        ORDER BY f.position ASC
        """
    )
    abstract fun getByGroup(groupId: Long): Flow<List<FavoriteEntity>>

    @Query("SELECT * FROM favorites WHERE provider_id = :providerId AND content_id = :contentId AND content_type = :contentType AND (:groupId IS NULL AND group_id IS NULL OR group_id = :groupId) LIMIT 1")
    abstract suspend fun get(providerId: Long, contentId: Long, contentType: String, groupId: Long?): FavoriteEntity?

    @Query("SELECT COUNT(*) FROM favorites WHERE provider_id = :providerId AND group_id IS NULL AND content_type = :contentType")
    abstract fun getGlobalFavoriteCount(providerId: Long, contentType: String): Flow<Int>

    @Query("SELECT group_id as category_id, COUNT(*) as item_count FROM favorites WHERE provider_id = :providerId AND group_id IS NOT NULL AND content_type = :contentType GROUP BY group_id")
    abstract fun getGroupFavoriteCounts(providerId: Long, contentType: String): Flow<List<CategoryCount>>

    @Query("SELECT group_id as category_id, COUNT(*) as item_count FROM favorites WHERE provider_id IN (:providerIds) AND group_id IS NOT NULL AND content_type = :contentType GROUP BY group_id")
    abstract fun getGroupFavoriteCountsForProviders(providerIds: List<Long>, contentType: String): Flow<List<CategoryCount>>

    @Query("SELECT group_id FROM favorites WHERE provider_id = :providerId AND content_id = :contentId AND content_type = :contentType AND group_id IS NOT NULL")
    abstract suspend fun getGroupMemberships(providerId: Long, contentId: Long, contentType: String): List<Long>

    @Query("SELECT MAX(position) FROM favorites WHERE provider_id = :providerId AND (:groupId IS NULL AND group_id IS NULL OR group_id = :groupId)")
    abstract suspend fun getMaxPosition(providerId: Long, groupId: Long?): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertDirect(favorite: FavoriteEntity)

    @Update
    abstract suspend fun updateAll(favorites: List<FavoriteEntity>)

    @Query("DELETE FROM favorites WHERE provider_id = :providerId AND content_id = :contentId AND content_type = :contentType AND (:groupId IS NULL AND group_id IS NULL OR group_id = :groupId)")
    abstract suspend fun delete(providerId: Long, contentId: Long, contentType: String, groupId: Long?)

    @Query("DELETE FROM favorites WHERE content_type = 'LIVE' AND content_id NOT IN (SELECT id FROM channels)")
    abstract suspend fun deleteMissingLiveFavorites(): Int

    @Query("DELETE FROM favorites WHERE content_type = 'MOVIE' AND content_id NOT IN (SELECT id FROM movies)")
    abstract suspend fun deleteMissingMovieFavorites(): Int

    @Query("DELETE FROM favorites WHERE content_type = 'SERIES' AND content_id NOT IN (SELECT id FROM series)")
    abstract suspend fun deleteMissingSeriesFavorites(): Int

    @Query("SELECT * FROM favorites WHERE id = :favoriteId LIMIT 1")
    protected abstract suspend fun getById(favoriteId: Long): FavoriteEntity?

    @Query("SELECT provider_id, content_type FROM virtual_groups WHERE id = :groupId LIMIT 1")
    protected abstract suspend fun getGroupConstraint(groupId: Long): FavoriteGroupConstraint?

    @Query("UPDATE favorites SET group_id = :groupId, group_key = COALESCE(:groupId, 0) WHERE id = :favoriteId")
    protected abstract suspend fun updateGroupDirect(favoriteId: Long, groupId: Long?)

    @Transaction
    open suspend fun insert(favorite: FavoriteEntity) {
        validateGroupAssignment(
            providerId = favorite.providerId,
            contentType = favorite.contentType.name,
            groupId = favorite.groupId
        )
        insertDirect(favorite)
    }

    @Transaction
    open suspend fun updateGroup(favoriteId: Long, groupId: Long?) {
        if (groupId == null) {
            updateGroupDirect(favoriteId, null)
            return
        }

        val favorite = getById(favoriteId)
            ?: throw IllegalArgumentException("Favorite $favoriteId does not exist")

        validateGroupAssignment(
            providerId = favorite.providerId,
            contentType = favorite.contentType.name,
            groupId = groupId
        )
        updateGroupDirect(favoriteId, groupId)
    }

    private suspend fun validateGroupAssignment(providerId: Long, contentType: String, groupId: Long?) {
        val targetGroupId = groupId ?: return
        val group = getGroupConstraint(targetGroupId)
            ?: throw IllegalArgumentException("Favorite group $targetGroupId does not exist")

        require(group.providerId == providerId) {
            "Favorite group $targetGroupId belongs to provider ${group.providerId}, not $providerId"
        }
        require(group.contentType == contentType) {
            "Favorite group $targetGroupId accepts ${group.contentType}, not $contentType"
        }
    }
}

@Dao
interface VirtualGroupDao {
    @Query("SELECT * FROM virtual_groups WHERE provider_id = :providerId AND content_type = :contentType ORDER BY position ASC")
    fun getByType(providerId: Long, contentType: String): Flow<List<VirtualGroupEntity>>

    @Query("SELECT * FROM virtual_groups WHERE provider_id IN (:providerIds) AND content_type = :contentType ORDER BY provider_id ASC, position ASC")
    fun getByTypeForProviders(providerIds: List<Long>, contentType: String): Flow<List<VirtualGroupEntity>>

    @Query("SELECT * FROM virtual_groups WHERE id = :id")
    suspend fun getById(id: Long): VirtualGroupEntity?

    @Query("SELECT MAX(position) FROM virtual_groups WHERE provider_id = :providerId AND content_type = :contentType")
    suspend fun getMaxPosition(providerId: Long, contentType: String): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(group: VirtualGroupEntity): Long

    @Query("UPDATE virtual_groups SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM virtual_groups WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
@RewriteQueriesToDropUnusedColumns
interface PlaybackHistoryDao {
    @Query("SELECT * FROM playback_history ORDER BY last_watched_at DESC LIMIT :limit")
    fun getRecentlyWatched(limit: Int = 100): Flow<List<PlaybackHistoryLiteEntity>>

    @Query("SELECT * FROM playback_history WHERE provider_id = :providerId ORDER BY last_watched_at DESC LIMIT :limit")
    fun getRecentlyWatchedByProvider(providerId: Long, limit: Int = 100): Flow<List<PlaybackHistoryLiteEntity>>

    @Query("SELECT * FROM playback_history WHERE provider_id IN (:providerIds) ORDER BY last_watched_at DESC LIMIT :limit")
    fun getRecentlyWatchedByProviders(providerIds: Set<Long>, limit: Int = 100): Flow<List<PlaybackHistoryLiteEntity>>

    @Query("SELECT * FROM playback_history WHERE provider_id = :providerId ORDER BY last_watched_at DESC")
    fun getByProvider(providerId: Long): Flow<List<PlaybackHistoryLiteEntity>>

    @Query("SELECT * FROM playback_history ORDER BY last_watched_at DESC")
    suspend fun getAllSync(): List<PlaybackHistoryEntity>

    @Query("SELECT * FROM playback_history WHERE content_id = :contentId AND content_type = :contentType AND provider_id = :providerId")
    suspend fun get(contentId: Long, contentType: String, providerId: Long): PlaybackHistoryEntity?

    @Query(
        """
        SELECT ph.* FROM playback_history ph
        JOIN movies current_movie
          ON current_movie.id = :contentId
         AND current_movie.provider_id = :providerId
        JOIN tmdb_identity identity
          ON identity.tmdb_id = current_movie.tmdb_id
         AND identity.content_type = 'MOVIE'
        JOIN movies candidate_movie
          ON candidate_movie.tmdb_id = identity.tmdb_id
        WHERE current_movie.tmdb_id IS NOT NULL
          AND ph.content_type = 'MOVIE'
          AND ph.provider_id = candidate_movie.provider_id
          AND ph.content_id = candidate_movie.id
        ORDER BY ph.last_watched_at DESC
        LIMIT 1
        """
    )
    suspend fun getLatestMovieHistoryBySharedTmdb(contentId: Long, providerId: Long): PlaybackHistoryEntity?

    @Query(
        """
        SELECT ph.* FROM playback_history ph
        JOIN series current_series
          ON current_series.id = :seriesId
         AND current_series.provider_id = :providerId
        JOIN tmdb_identity identity
          ON identity.tmdb_id = current_series.tmdb_id
         AND identity.content_type = 'SERIES'
        JOIN series candidate_series
          ON candidate_series.tmdb_id = identity.tmdb_id
        WHERE current_series.tmdb_id IS NOT NULL
          AND (
              (ph.content_type = 'SERIES' AND ph.content_id = candidate_series.id)
              OR (
                  ph.content_type = 'SERIES_EPISODE'
                  AND ph.series_id = candidate_series.id
              )
          )
          AND ph.provider_id = candidate_series.provider_id
        ORDER BY ph.last_watched_at DESC
        LIMIT 1
        """
    )
    suspend fun getLatestSeriesHistoryBySharedTmdb(seriesId: Long, providerId: Long): PlaybackHistoryEntity?

    @Query(
        """
        SELECT * FROM playback_history
        WHERE content_type = 'SERIES_EPISODE'
          AND provider_id = :providerId
          AND series_id = :seriesId
          AND season_number = :seasonNumber
          AND episode_number = :episodeNumber
        ORDER BY last_watched_at DESC
        LIMIT 1
        """
    )
    suspend fun getLatestEpisodeHistoryByCoordinates(
        providerId: Long,
        seriesId: Long,
        seasonNumber: Int,
        episodeNumber: Int
    ): PlaybackHistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(history: PlaybackHistoryEntity)

    @Query("DELETE FROM playback_history WHERE content_id = :contentId AND content_type = :contentType AND provider_id = :providerId")
    suspend fun delete(contentId: Long, contentType: String, providerId: Long)

    @Query("DELETE FROM playback_history")
    suspend fun deleteAll()

    @Query("DELETE FROM playback_history WHERE provider_id = :providerId")
    suspend fun deleteByProvider(providerId: Long)

    @Query("DELETE FROM playback_history WHERE provider_id = :providerId AND content_type = :contentType")
    suspend fun deleteByProviderAndType(providerId: Long, contentType: String)
}

@Dao
interface SearchHistoryDao {
    @Query(
        """
        SELECT * FROM search_history
        WHERE content_scope = :contentScope
          AND provider_id = :providerId
        ORDER BY used_at DESC
        LIMIT :limit
        """
    )
    fun observeRecent(contentScope: String, providerId: Long, limit: Int): Flow<List<SearchHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(entity: SearchHistoryEntity): Long

    @Query(
        """
        UPDATE search_history
        SET used_at = :usedAt,
            use_count = use_count + 1
        WHERE query = :query
          AND content_scope = :contentScope
          AND provider_id = :providerId
        """
    )
    suspend fun incrementUseCount(query: String, contentScope: String, providerId: Long, usedAt: Long): Int

    @Transaction
    suspend fun record(query: String, contentScope: String, providerId: Long, usedAt: Long) {
        val updated = incrementUseCount(query, contentScope, providerId, usedAt)
        if (updated > 0) return

        val inserted = insertIgnore(
            SearchHistoryEntity(
                query = query,
                contentScope = contentScope,
                providerId = providerId,
                usedAt = usedAt,
                useCount = 1
            )
        )
        if (inserted == -1L) {
            incrementUseCount(query, contentScope, providerId, usedAt)
        }
    }

    @Query("DELETE FROM search_history WHERE content_scope = :contentScope AND provider_id = :providerId")
    suspend fun deleteByScope(contentScope: String, providerId: Long)

    @Query("DELETE FROM search_history WHERE used_at < :minUsedAt")
    suspend fun pruneOlderThan(minUsedAt: Long)

    @Query("DELETE FROM search_history")
    suspend fun deleteAll()
}

@Dao
interface SyncMetadataDao {
    @Query("SELECT * FROM sync_metadata WHERE provider_id = :providerId")
    fun get(providerId: Long): Flow<SyncMetadataEntity?>

    @Query("SELECT * FROM sync_metadata WHERE provider_id = :providerId")
    suspend fun getSync(providerId: Long): SyncMetadataEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(metadata: SyncMetadataEntity)

    @Query("DELETE FROM sync_metadata WHERE provider_id = :providerId")
    suspend fun delete(providerId: Long)
}

@Dao
interface MovieCategoryHydrationDao {
    @Query("SELECT * FROM movie_category_hydration WHERE provider_id = :providerId AND category_id = :categoryId")
    suspend fun get(providerId: Long, categoryId: Long): MovieCategoryHydrationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(metadata: MovieCategoryHydrationEntity)

    @Query("DELETE FROM movie_category_hydration WHERE provider_id = :providerId AND category_id = :categoryId")
    suspend fun delete(providerId: Long, categoryId: Long)

    @Query("DELETE FROM movie_category_hydration WHERE provider_id = :providerId")
    suspend fun deleteByProvider(providerId: Long)
}

@Dao
interface SeriesCategoryHydrationDao {
    @Query("SELECT * FROM series_category_hydration WHERE provider_id = :providerId AND category_id = :categoryId")
    suspend fun get(providerId: Long, categoryId: Long): SeriesCategoryHydrationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(metadata: SeriesCategoryHydrationEntity)

    @Query("DELETE FROM series_category_hydration WHERE provider_id = :providerId AND category_id = :categoryId")
    suspend fun delete(providerId: Long, categoryId: Long)

    @Query("DELETE FROM series_category_hydration WHERE provider_id = :providerId")
    suspend fun deleteByProvider(providerId: Long)
}

// ── EPG Source DAOs ────────────────────────────────────────────────

@Dao
interface EpgSourceDao {
    @Query("SELECT * FROM epg_sources WHERE id > 0 ORDER BY priority ASC, name ASC")
    fun getAll(): Flow<List<EpgSourceEntity>>

    @Query("SELECT * FROM epg_sources WHERE id > 0 ORDER BY priority ASC, name ASC")
    suspend fun getAllSync(): List<EpgSourceEntity>

    @Query("SELECT * FROM epg_sources WHERE id = :id")
    suspend fun getById(id: Long): EpgSourceEntity?

    @Query("SELECT * FROM epg_sources WHERE url = :url LIMIT 1")
    suspend fun getByUrl(url: String): EpgSourceEntity?

    @Query("SELECT * FROM epg_sources WHERE id > 0 AND enabled = 1 ORDER BY priority ASC, name ASC")
    suspend fun getEnabled(): List<EpgSourceEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(source: EpgSourceEntity): Long

    @Update
    suspend fun update(source: EpgSourceEntity)

    @Query("DELETE FROM epg_sources WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE epg_sources SET enabled = :enabled, updated_at = :now WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean, now: Long = System.currentTimeMillis())

    @Query("UPDATE epg_sources SET last_refresh_at = :at, last_error = :error, updated_at = :at WHERE id = :id")
    suspend fun updateRefreshStatus(id: Long, at: Long, error: String?)

    @Query("UPDATE epg_sources SET last_error = :error, updated_at = :at WHERE id = :id")
    suspend fun updateRefreshError(id: Long, error: String?, at: Long = System.currentTimeMillis())

    @Query("UPDATE epg_sources SET last_refresh_at = :at, last_success_at = :at, last_error = NULL, updated_at = :at WHERE id = :id")
    suspend fun updateRefreshSuccess(id: Long, at: Long)

    @Query("UPDATE epg_sources SET etag = :etag, last_modified_header = :lastModified WHERE id = :id")
    suspend fun updateConditionalHeaders(id: Long, etag: String?, lastModified: String?)
}

@Dao
abstract class ProviderEpgSourceDao {
    @Query("""
        SELECT pes.*, es.name AS epg_source_name, es.url AS epg_source_url
        FROM provider_epg_sources pes
        JOIN epg_sources es ON es.id = pes.epg_source_id
        WHERE pes.provider_id = :providerId
        ORDER BY pes.priority ASC
    """)
    abstract fun getForProvider(providerId: Long): Flow<List<ProviderEpgSourceWithDetails>>

    @Query("""
        SELECT pes.*
        FROM provider_epg_sources pes
        JOIN epg_sources es ON es.id = pes.epg_source_id
        WHERE pes.provider_id = :providerId AND pes.enabled = 1 AND es.enabled = 1
        ORDER BY pes.priority ASC
    """)
    abstract suspend fun getEnabledForProviderSync(providerId: Long): List<ProviderEpgSourceEntity>

    @Query("""
        SELECT pes.*
        FROM provider_epg_sources pes
        WHERE pes.provider_id = :providerId
        ORDER BY pes.priority ASC
    """)
    abstract suspend fun getForProviderSync(providerId: Long): List<ProviderEpgSourceEntity>

    @Query("SELECT DISTINCT provider_id FROM provider_epg_sources WHERE epg_source_id = :epgSourceId")
    abstract suspend fun getProviderIdsForSourceSync(epgSourceId: Long): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insert(assignment: ProviderEpgSourceEntity): Long

    @Update
    abstract suspend fun update(assignment: ProviderEpgSourceEntity)

    @Query("DELETE FROM provider_epg_sources WHERE provider_id = :providerId AND epg_source_id = :epgSourceId")
    abstract suspend fun delete(providerId: Long, epgSourceId: Long)

    @Query("DELETE FROM provider_epg_sources WHERE provider_id = :providerId")
    abstract suspend fun deleteByProvider(providerId: Long)

    /** Atomically swaps the priority of two assignment rows within a single transaction. */
    @Transaction
    open suspend fun swapPriorities(
        entity1: ProviderEpgSourceEntity,
        entity2: ProviderEpgSourceEntity
    ) {
        update(entity1)
        update(entity2)
    }
}

data class ProviderEpgSourceWithDetails(
    val id: Long,
    @ColumnInfo(name = "provider_id") val providerId: Long,
    @ColumnInfo(name = "epg_source_id") val epgSourceId: Long,
    val priority: Int,
    val enabled: Boolean,
    @ColumnInfo(name = "epg_source_name") val epgSourceName: String,
    @ColumnInfo(name = "epg_source_url") val epgSourceUrl: String
)

@Dao
interface EpgChannelDao {
    @Query("SELECT * FROM epg_channels WHERE epg_source_id = :sourceId ORDER BY display_name ASC")
    suspend fun getBySource(sourceId: Long): List<EpgChannelEntity>

    @Query("""
        SELECT * FROM epg_channels
        WHERE epg_source_id = :sourceId
          AND (LOWER(xmltv_channel_id) LIKE LOWER(:pattern) ESCAPE '\'
               OR LOWER(display_name) LIKE LOWER(:pattern) ESCAPE '\'
               OR LOWER(normalized_name) LIKE LOWER(:pattern) ESCAPE '\')
        ORDER BY display_name ASC
        LIMIT :limit
    """)
    suspend fun searchBySource(sourceId: Long, pattern: String, limit: Int): List<EpgChannelEntity>

    @Query("SELECT * FROM epg_channels WHERE epg_source_id = :sourceId AND xmltv_channel_id = :channelId LIMIT 1")
    suspend fun getBySourceAndChannelId(sourceId: Long, channelId: String): EpgChannelEntity?

    @Query("SELECT * FROM epg_channels WHERE epg_source_id IN (:sourceIds)")
    suspend fun getBySources(sourceIds: List<Long>): List<EpgChannelEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(channels: List<EpgChannelEntity>)

    @Query("DELETE FROM epg_channels WHERE epg_source_id = :sourceId")
    suspend fun deleteBySource(sourceId: Long)

    @Query("UPDATE epg_channels SET epg_source_id = :newSourceId WHERE epg_source_id = :oldSourceId")
    suspend fun moveToSource(oldSourceId: Long, newSourceId: Long)
}

@Dao
interface EpgProgrammeDao {
    @Query("""
        SELECT * FROM epg_programmes
        WHERE epg_source_id = :sourceId
          AND xmltv_channel_id = :channelId
          AND end_time > :startTime
          AND start_time < :endTime
        ORDER BY start_time ASC
    """)
    suspend fun getForChannel(sourceId: Long, channelId: String, startTime: Long, endTime: Long): List<EpgProgrammeEntity>

    @Query("""
        SELECT * FROM epg_programmes
        WHERE epg_source_id = :sourceId
          AND xmltv_channel_id IN (:channelIds)
          AND end_time > :startTime
          AND start_time < :endTime
        ORDER BY xmltv_channel_id ASC, start_time ASC
    """)
    suspend fun getForChannels(sourceId: Long, channelIds: List<String>, startTime: Long, endTime: Long): List<EpgProgrammeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(programmes: List<EpgProgrammeEntity>)

    @Query("DELETE FROM epg_programmes WHERE epg_source_id = :sourceId")
    suspend fun deleteBySource(sourceId: Long)

    @Query("UPDATE epg_programmes SET epg_source_id = :newSourceId WHERE epg_source_id = :oldSourceId")
    suspend fun moveToSource(oldSourceId: Long, newSourceId: Long)

    @Query("DELETE FROM epg_programmes WHERE end_time < :beforeTime")
    suspend fun deleteOld(beforeTime: Long): Int

    @Query("SELECT COUNT(*) FROM epg_programmes WHERE epg_source_id = :sourceId")
    suspend fun countBySource(sourceId: Long): Int

    @Query("""
        SELECT COUNT(*) FROM epg_programmes
        WHERE epg_source_id = :sourceId
          AND xmltv_channel_id = :channelId
          AND end_time > :now
    """)
    suspend fun countUpcomingForChannel(sourceId: Long, channelId: String, now: Long): Int
}

@Dao
abstract class ChannelEpgMappingDao {
    @Query("SELECT * FROM channel_epg_mappings WHERE provider_id = :providerId")
    abstract suspend fun getForProvider(providerId: Long): List<ChannelEpgMappingEntity>

    @Query("SELECT * FROM channel_epg_mappings WHERE provider_id = :providerId AND provider_channel_id = :channelId LIMIT 1")
    abstract suspend fun getForChannel(providerId: Long, channelId: Long): ChannelEpgMappingEntity?

    @Query("""
        SELECT * FROM channel_epg_mappings
        WHERE provider_id = :providerId
          AND provider_channel_id IN (:channelIds)
    """)
    abstract suspend fun getForChannels(providerId: Long, channelIds: List<Long>): List<ChannelEpgMappingEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertAll(mappings: List<ChannelEpgMappingEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(mapping: ChannelEpgMappingEntity)

    @Query("DELETE FROM channel_epg_mappings WHERE provider_id = :providerId AND provider_channel_id = :channelId")
    abstract suspend fun deleteForChannel(providerId: Long, channelId: Long)

    @Query("DELETE FROM channel_epg_mappings WHERE provider_id = :providerId")
    abstract suspend fun deleteByProvider(providerId: Long)

    @Transaction
    open suspend fun replaceForProvider(providerId: Long, mappings: List<ChannelEpgMappingEntity>) {
        deleteByProvider(providerId)
        if (mappings.isNotEmpty()) {
            insertAll(mappings)
        }
    }

    @Query("""
        SELECT source_type, match_type, COUNT(*) as cnt
        FROM channel_epg_mappings
        WHERE provider_id = :providerId
        GROUP BY source_type, match_type
    """)
    abstract suspend fun getResolutionStats(providerId: Long): List<EpgResolutionStatRow>

    @Query(
        """
        SELECT COUNT(*) FROM channel_epg_mappings
        WHERE provider_id = :providerId
          AND confidence > 0
          AND confidence < :minConfidence
        """
    )
    abstract suspend fun countLowConfidence(providerId: Long, minConfidence: Float): Int

    @Query(
        """
        SELECT COUNT(*) FROM channel_epg_mappings
        WHERE provider_id = :providerId
          AND failed_attempts < :maxAttempts
          AND (
              source_type = 'NONE'
              OR (confidence > 0 AND confidence < :minConfidence)
          )
        """
    )
    abstract suspend fun countRematchCandidates(providerId: Long, minConfidence: Float, maxAttempts: Int): Int

    @Query(
        """
        SELECT * FROM channel_epg_mappings
        WHERE provider_id = :providerId
          AND failed_attempts < :maxAttempts
          AND (
              source_type = 'NONE'
              OR (confidence > 0 AND confidence < :minConfidence)
          )
        ORDER BY confidence ASC, failed_attempts ASC, provider_channel_id ASC
        LIMIT :limit
        """
    )
    abstract suspend fun getChannelsNeedingRematch(
        providerId: Long,
        minConfidence: Float,
        maxAttempts: Int,
        limit: Int
    ): List<ChannelEpgMappingEntity>
}

data class EpgResolutionStatRow(
    @ColumnInfo(name = "source_type") val sourceType: String,
    @ColumnInfo(name = "match_type") val matchType: String?,
    val cnt: Int
)
