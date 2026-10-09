package com.streamvault.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "xtream_live_onboarding_state",
    primaryKeys = ["provider_id"],
    foreignKeys = [ForeignKey(
        entity = ProviderEntity::class,
        parentColumns = ["id"],
        childColumns = ["provider_id"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [
        Index(value = ["provider_id"]),
        Index(value = ["phase"]),
        Index(value = ["updated_at"]),
        Index(value = ["staged_session_id"])
    ]
)
data class XtreamLiveOnboardingStateEntity(
    @ColumnInfo(name = "provider_id") val providerId: Long,
    @ColumnInfo(name = "provider_type") val providerType: String = "XTREAM_CODES",
    @ColumnInfo(name = "content_type") val contentType: String = "LIVE",
    val phase: String = "STARTING",
    @ColumnInfo(name = "staged_session_id") val stagedSessionId: Long? = null,
    @ColumnInfo(name = "import_strategy") val importStrategy: String? = null,
    @ColumnInfo(name = "next_category_index") val nextCategoryIndex: Int = 0,
    @ColumnInfo(name = "accepted_row_count") val acceptedRowCount: Int = 0,
    @ColumnInfo(name = "staged_flush_count") val stagedFlushCount: Int = 0,
    @ColumnInfo(name = "sealed_session_id") val sealedSessionId: Long? = null,
    @ColumnInfo(name = "sealed_row_count", defaultValue = "0") val sealedRowCount: Int = 0,
    @ColumnInfo(name = "sealed_configuration_generation") val sealedConfigurationGeneration: Long? = null,
    @ColumnInfo(name = "sealed_mapping_profile") val sealedMappingProfile: String? = null,
    @ColumnInfo(name = "sync_profile_tier") val syncProfileTier: String? = null,
    @ColumnInfo(name = "sync_profile_batch_size") val syncProfileBatchSize: Int = 0,
    @ColumnInfo(name = "sync_profile_strategy") val syncProfileStrategy: String? = null,
    @ColumnInfo(name = "sync_profile_low_memory") val syncProfileLowMemory: Boolean = false,
    @ColumnInfo(name = "sync_profile_memory_class_mb") val syncProfileMemoryClassMb: Int = 0,
    @ColumnInfo(name = "sync_profile_available_mem_mb") val syncProfileAvailableMemMb: Long = 0L,
    @ColumnInfo(name = "last_error") val lastError: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = 0L,
    @ColumnInfo(name = "updated_at") val updatedAt: Long = 0L,
    @ColumnInfo(name = "completed_at") val completedAt: Long? = null
)
