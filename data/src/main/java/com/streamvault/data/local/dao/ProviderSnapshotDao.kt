package com.streamvault.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.streamvault.data.local.entity.ProviderAccountRuntimeEntity
import com.streamvault.data.local.entity.ProviderConfigEntity
import com.streamvault.data.local.entity.StalkerPortalStateEntity
import com.streamvault.data.local.disambiguatedMigrationIdentityKey
import kotlinx.coroutines.flow.Flow

@Dao
abstract class ProviderSnapshotDao {
    @Query("SELECT * FROM provider_configs WHERE provider_id = :providerId LIMIT 1")
    abstract suspend fun getConfig(providerId: Long): ProviderConfigEntity?

    @Query("SELECT * FROM provider_configs WHERE provider_id = :providerId LIMIT 1")
    abstract fun getConfigSync(providerId: Long): ProviderConfigEntity?

    @Query("SELECT * FROM provider_configs")
    abstract fun observeConfigs(): Flow<List<ProviderConfigEntity>>

    @Query("SELECT * FROM provider_account_runtime WHERE provider_id = :providerId LIMIT 1")
    abstract suspend fun getRuntime(providerId: Long): ProviderAccountRuntimeEntity?

    @Query("SELECT * FROM provider_account_runtime")
    abstract fun observeRuntimes(): Flow<List<ProviderAccountRuntimeEntity>>

    @Query("SELECT * FROM stalker_portal_state")
    abstract fun observeStalkerPortalStates(): Flow<List<StalkerPortalStateEntity>>

    @Query("SELECT provider_id FROM provider_configs WHERE identity_key = :identityKey LIMIT 1")
    abstract suspend fun findProviderIdByIdentityKey(identityKey: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun upsertConfigDirect(entity: ProviderConfigEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertRuntime(entity: ProviderAccountRuntimeEntity)

    @Query("UPDATE provider_account_runtime SET catalog_layout_detection_version = 0 WHERE provider_id = :providerId")
    abstract suspend fun invalidateCatalogLayoutDetection(providerId: Long)

    @Query("UPDATE provider_account_runtime SET catalog_layout = :layout, catalog_layout_detection_version = :version WHERE provider_id = :providerId")
    abstract suspend fun updateCatalogLayout(
        providerId: Long,
        layout: com.streamvault.domain.model.CatalogLayout,
        version: Int
    )

    @Query("SELECT configuration_generation FROM provider_configs WHERE provider_id = :providerId")
    protected abstract suspend fun generation(providerId: Long): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun upsertLearningDirect(entity: StalkerPortalStateEntity)

    /**
     * Configuration edits are monotonic. A stale caller cannot roll back a newer generation.
     */
    @Transaction
    open suspend fun commitConfiguration(entity: ProviderConfigEntity): Boolean {
        val current = getConfig(entity.providerId)
        if (current != null && entity.configurationGeneration <= current.configurationGeneration) return false

        val identityOwner = findProviderIdByIdentityKey(entity.identityKey)
        val entityToWrite = if (identityOwner != null && identityOwner != entity.providerId) {
            // A duplicate preserved by migration has a stable salted identity. Keep it when a
            // later edit computes the canonical key owned by the first legacy provider.
            val migratedIdentity = disambiguatedMigrationIdentityKey(
                canonicalKey = entity.identityKey,
                providerId = entity.providerId
            )
            if (current?.identityKey != migratedIdentity) return false
            entity.copy(identityKey = current.identityKey)
        } else {
            entity
        }
        upsertConfigDirect(entityToWrite)
        return true
    }

    /** Room holds one database transaction across the generation check and learning write. */
    @Transaction
    open suspend fun compareAndSetStalkerLearning(entity: StalkerPortalStateEntity): Boolean {
        val current = generation(entity.providerId) ?: return false
        if (current != entity.configurationGeneration) return false
        upsertLearningDirect(entity)
        return true
    }
}
