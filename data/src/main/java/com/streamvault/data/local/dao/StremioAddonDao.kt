package com.streamvault.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.streamvault.data.local.entity.StremioAddonEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface StremioAddonDao {
    @Query("SELECT * FROM stremio_addons ORDER BY sort_order ASC, id ASC")
    fun observeAll(): Flow<List<StremioAddonEntity>>

    @Query("SELECT * FROM stremio_addons ORDER BY sort_order ASC, id ASC")
    suspend fun getAll(): List<StremioAddonEntity>

    @Query("SELECT * FROM stremio_addons WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): StremioAddonEntity?

    @Query("SELECT * FROM stremio_addons WHERE install_url = :installUrl LIMIT 1")
    suspend fun getByInstallUrl(installUrl: String): StremioAddonEntity?

    @Query("SELECT COALESCE(MAX(sort_order), -1) FROM stremio_addons")
    suspend fun maxSortOrder(): Int

    @Insert
    suspend fun insert(entity: StremioAddonEntity): Long

    @Query("UPDATE stremio_addons SET enabled = :enabled, updated_at = :updatedAt WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean, updatedAt: Long)

    @Query("UPDATE stremio_addons SET sort_order = :sortOrder, updated_at = :updatedAt WHERE id = :id")
    suspend fun setSortOrder(id: Long, sortOrder: Int, updatedAt: Long)

    @Query(
        "UPDATE stremio_addons SET catalog_settings_json = :catalogSettingsJson, updated_at = :updatedAt WHERE id = :id"
    )
    suspend fun setCatalogSettings(id: Long, catalogSettingsJson: String, updatedAt: Long)

    @Query("DELETE FROM stremio_addons WHERE id = :id")
    suspend fun delete(id: Long)

    @Transaction
    suspend fun applyOrder(positions: List<Pair<Long, Int>>, updatedAt: Long) {
        positions.forEach { (id, sortOrder) -> setSortOrder(id, sortOrder, updatedAt) }
    }
}
