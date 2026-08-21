package com.streamvault.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.streamvault.data.local.entity.M3uCategoryClassificationRuleEntity
import com.streamvault.data.local.entity.M3uClassificationOverrideEntity

@Dao
interface M3uClassificationDao {
    @Query("SELECT * FROM m3u_classification_overrides WHERE provider_id = :providerId")
    suspend fun getOverrides(providerId: Long): List<M3uClassificationOverrideEntity>

    @Query("SELECT * FROM m3u_category_classification_rules WHERE provider_id = :providerId")
    suspend fun getCategoryRules(providerId: Long): List<M3uCategoryClassificationRuleEntity>

    @Query("SELECT * FROM m3u_classification_overrides WHERE provider_id = :providerId AND source_key = :sourceKey LIMIT 1")
    suspend fun getOverride(providerId: Long, sourceKey: String): M3uClassificationOverrideEntity?

    @Query("SELECT * FROM m3u_classification_overrides WHERE provider_id = :providerId AND stream_id = :streamId LIMIT 1")
    suspend fun getByStreamId(providerId: Long, streamId: Long): M3uClassificationOverrideEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOverride(override: M3uClassificationOverrideEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOverrides(overrides: List<M3uClassificationOverrideEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCategoryRule(rule: M3uCategoryClassificationRuleEntity)

    @Query("DELETE FROM m3u_classification_overrides WHERE provider_id = :providerId AND source_key = :sourceKey")
    suspend fun deleteOverride(providerId: Long, sourceKey: String)

    @Query("DELETE FROM m3u_classification_overrides WHERE provider_id = :providerId")
    suspend fun deleteOverridesByProvider(providerId: Long)

    @Query("DELETE FROM m3u_category_classification_rules WHERE provider_id = :providerId AND group_key = :groupKey")
    suspend fun deleteCategoryRule(providerId: Long, groupKey: String)

    @Query("DELETE FROM m3u_category_classification_rules WHERE provider_id = :providerId")
    suspend fun deleteCategoryRulesByProvider(providerId: Long)
}
