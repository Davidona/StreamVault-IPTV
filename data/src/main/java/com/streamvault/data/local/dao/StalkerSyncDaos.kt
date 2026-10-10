package com.streamvault.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.streamvault.data.local.entity.StalkerIndexJobEntity
import com.streamvault.data.local.entity.StalkerLiveCategoryRetryEntity
import com.streamvault.data.local.entity.StalkerPortalStateEntity
import com.streamvault.data.local.entity.StalkerRemoteIdentityEntity
import com.streamvault.data.local.entity.StalkerDiscoveryStageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface StalkerIndexJobDao {
    @Query("SELECT * FROM stalker_index_jobs WHERE provider_id = :providerId ORDER BY updated_at ASC, section ASC")
    fun observeForProvider(providerId: Long): Flow<List<StalkerIndexJobEntity>>

    @Query("SELECT * FROM stalker_index_jobs WHERE provider_id = :providerId AND section = :section LIMIT 1")
    suspend fun get(providerId: Long, section: String): StalkerIndexJobEntity?

    @Query("SELECT * FROM stalker_index_jobs WHERE provider_id = :providerId AND state IN ('QUEUED','RETRY_WAIT','PARTIAL') ORDER BY updated_at ASC LIMIT 1")
    suspend fun getNextPending(providerId: Long): StalkerIndexJobEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: StalkerIndexJobEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<StalkerIndexJobEntity>)

    @Query("UPDATE stalker_index_jobs SET state = 'DISABLED', updated_at = :updatedAt WHERE provider_id = :providerId")
    suspend fun disableForProvider(providerId: Long, updatedAt: Long): Int

    @Query("DELETE FROM stalker_index_jobs WHERE provider_id = :providerId")
    suspend fun deleteByProvider(providerId: Long): Int
}

@Dao
interface StalkerLiveCategoryRetryDao {
    @Query("SELECT * FROM stalker_live_category_retries WHERE provider_id = :providerId AND configuration_generation = :configurationGeneration ORDER BY category_id")
    suspend fun getForProvider(providerId: Long, configurationGeneration: Long): List<StalkerLiveCategoryRetryEntity>

    @Query("DELETE FROM stalker_live_category_retries WHERE provider_id = :providerId AND configuration_generation != :configurationGeneration")
    suspend fun deleteForOtherConfigurations(providerId: Long, configurationGeneration: Long): Int

    @Query("DELETE FROM stalker_live_category_retries WHERE provider_id = :providerId AND category_id IN (:categoryIds)")
    suspend fun deleteCategories(providerId: Long, categoryIds: List<Long>): Int

    @Query("DELETE FROM stalker_live_category_retries WHERE provider_id = :providerId")
    suspend fun deleteForProvider(providerId: Long): Int

    @Query("""
        INSERT INTO stalker_live_category_retries (
            provider_id, category_id, category_name, configuration_generation,
            attempt_count, last_attempt_at, last_error
        ) VALUES (
            :providerId, :categoryId, :categoryName, :configurationGeneration,
            1, :attemptedAt, :errorMessage
        )
        ON CONFLICT(provider_id, category_id) DO UPDATE SET
            category_name = excluded.category_name,
            configuration_generation = excluded.configuration_generation,
            attempt_count = stalker_live_category_retries.attempt_count + 1,
            last_attempt_at = excluded.last_attempt_at,
            last_error = excluded.last_error
    """)
    suspend fun recordFailure(
        providerId: Long,
        categoryId: Long,
        categoryName: String,
        configurationGeneration: Long,
        attemptedAt: Long,
        errorMessage: String?
    )
}

@Dao
interface StalkerPortalStateDao {
    @Query("SELECT * FROM stalker_portal_state WHERE provider_id = :providerId LIMIT 1")
    suspend fun get(providerId: Long): StalkerPortalStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: StalkerPortalStateEntity)

    @Query("DELETE FROM stalker_portal_state WHERE provider_id = :providerId")
    suspend fun invalidate(providerId: Long): Int
}

@Dao
interface StalkerRemoteIdentityDao {
    @Query("SELECT * FROM stalker_remote_identities WHERE provider_id = :providerId AND content_type = :contentType AND raw_id IN (:rawIds)")
    suspend fun getByRawIds(providerId: Long, contentType: String, rawIds: List<String>): List<StalkerRemoteIdentityEntity>

    @Query("SELECT * FROM stalker_remote_identities WHERE provider_id = :providerId AND content_type = :contentType AND surrogate_id IN (:surrogateIds)")
    suspend fun getBySurrogateIds(providerId: Long, contentType: String, surrogateIds: List<Long>): List<StalkerRemoteIdentityEntity>

    // Never replace established identities. Ignored conflicts use the resolver's recovery path.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(entities: List<StalkerRemoteIdentityEntity>): List<Long>

    @Query("SELECT * FROM stalker_remote_identities WHERE provider_id = :providerId AND content_type = :contentType AND raw_id = :rawId LIMIT 1")
    suspend fun getByRawId(providerId: Long, contentType: String, rawId: String): StalkerRemoteIdentityEntity?

    @Query("SELECT * FROM stalker_remote_identities WHERE provider_id = :providerId AND content_type = :contentType AND surrogate_id = :surrogateId LIMIT 1")
    suspend fun getBySurrogateId(providerId: Long, contentType: String, surrogateId: Long): StalkerRemoteIdentityEntity?

    @Query("SELECT MAX(surrogate_id) FROM stalker_remote_identities WHERE provider_id = :providerId AND content_type = :contentType AND surrogate_id >= :floor")
    suspend fun maxAllocatedSurrogate(providerId: Long, contentType: String, floor: Long): Long?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: StalkerRemoteIdentityEntity)
}

@Dao
interface StalkerDiscoveryStageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: StalkerDiscoveryStageEntity)

    @Query("SELECT * FROM stalker_discovery_staging WHERE discovery_id = :discoveryId LIMIT 1")
    suspend fun get(discoveryId: String): StalkerDiscoveryStageEntity?

    @Query("DELETE FROM stalker_discovery_staging WHERE discovery_id = :discoveryId")
    suspend fun delete(discoveryId: String): Int

    @Query("DELETE FROM stalker_discovery_staging WHERE created_at < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long): Int
}
