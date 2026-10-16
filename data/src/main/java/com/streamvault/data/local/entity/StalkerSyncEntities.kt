package com.streamvault.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.StalkerIndexState

@Entity(
    tableName = "stalker_index_jobs",
    primaryKeys = ["provider_id", "section"],
    foreignKeys = [ForeignKey(
        entity = ProviderEntity::class,
        parentColumns = ["id"],
        childColumns = ["provider_id"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [
        Index(value = ["provider_id"]),
        Index(value = ["state"]),
        Index(value = ["updated_at"])
    ]
)
data class StalkerIndexJobEntity(
    @ColumnInfo(name = "provider_id") val providerId: Long,
    val section: ContentType,
    val state: StalkerIndexState = StalkerIndexState.DISABLED,
    @ColumnInfo(name = "total_categories") val totalCategories: Int = 0,
    @ColumnInfo(name = "completed_categories") val completedCategories: Int = 0,
    @ColumnInfo(name = "next_category_index") val nextCategoryIndex: Int = 0,
    @ColumnInfo(name = "failed_categories") val failedCategories: Int = 0,
    @ColumnInfo(name = "indexed_rows") val indexedRows: Int = 0,
    @ColumnInfo(name = "skipped_malformed_rows") val skippedMalformedRows: Int = 0,
    @ColumnInfo(name = "deleted_pruned_rows") val deletedPrunedRows: Int = 0,
    @ColumnInfo(name = "last_error") val lastError: String? = null,
    @ColumnInfo(name = "last_attempt_at") val lastAttemptAt: Long = 0L,
    @ColumnInfo(name = "last_success_at") val lastSuccessAt: Long = 0L,
    @ColumnInfo(name = "updated_at") val updatedAt: Long = 0L
)

/** A failed live category scope that must be retried before the catalog is considered fresh. */
@Entity(
    tableName = "stalker_live_category_retries",
    primaryKeys = ["provider_id", "category_id"],
    foreignKeys = [ForeignKey(
        entity = ProviderEntity::class,
        parentColumns = ["id"],
        childColumns = ["provider_id"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index(value = ["provider_id"])]
)
data class StalkerLiveCategoryRetryEntity(
    @ColumnInfo(name = "provider_id") val providerId: Long,
    @ColumnInfo(name = "category_id") val categoryId: Long,
    @ColumnInfo(name = "category_name") val categoryName: String,
    @ColumnInfo(name = "configuration_generation") val configurationGeneration: Long,
    @ColumnInfo(name = "attempt_count") val attemptCount: Int = 1,
    @ColumnInfo(name = "last_attempt_at") val lastAttemptAt: Long,
    @ColumnInfo(name = "last_error") val lastError: String? = null
)

@Entity(
    tableName = "stalker_portal_state",
    foreignKeys = [ForeignKey(
        entity = ProviderEntity::class,
        parentColumns = ["id"],
        childColumns = ["provider_id"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index(value = ["provider_id"], unique = true)]
)
data class StalkerPortalStateEntity(
    @androidx.room.PrimaryKey
    @ColumnInfo(name = "provider_id") val providerId: Long,
    @ColumnInfo(name = "working_endpoint") val workingEndpoint: String? = null,
    @ColumnInfo(name = "bootstrap_recipe") val bootstrapRecipe: String? = null,
    @ColumnInfo(name = "bulk_live_supported") val bulkLiveSupported: Boolean? = null,
    @ColumnInfo(name = "bulk_live_category_fidelity") val bulkLiveCategoryFidelity: Boolean? = null,
    @ColumnInfo(name = "movie_wildcard_supported") val movieWildcardSupported: Boolean? = null,
    @ColumnInfo(name = "series_wildcard_supported") val seriesWildcardSupported: Boolean? = null,
    @ColumnInfo(name = "epg_supported") val epgSupported: Boolean? = null,
    @ColumnInfo(name = "safe_metadata_concurrency") val safeMetadataConcurrency: Int = 2,
    @ColumnInfo(name = "stress_cooldown_until") val stressCooldownUntil: Long = 0L,
    /** JSON map of SHA-256 endpoint/recipe keys to cooldown expiry; never stores raw values. */
    @ColumnInfo(name = "endpoint_health_json") val endpointHealthJson: String = "{}",
    @ColumnInfo(name = "endpoint_failed_until") val endpointFailedUntil: Long = 0L,
    @ColumnInfo(name = "validated_at") val validatedAt: Long = 0L,
    @ColumnInfo(name = "schema_version") val schemaVersion: Int = 1,
    /** Learning is accepted only while this matches provider_configs.configuration_generation. */
    @ColumnInfo(name = "configuration_generation") val configurationGeneration: Long = 0L,
    @ColumnInfo(name = "learning_json") val learningJson: String = "{}",
    @ColumnInfo(name = "observation_source") val observationSource: String = "DISCOVERY",
    @ColumnInfo(name = "observed_at") val observedAt: Long = 0L
)

@Entity(
    tableName = "stalker_remote_identities",
    primaryKeys = ["provider_id", "content_type", "raw_id"],
    foreignKeys = [ForeignKey(
        entity = ProviderEntity::class,
        parentColumns = ["id"],
        childColumns = ["provider_id"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [
        Index(value = ["provider_id"]),
        Index(value = ["provider_id", "content_type", "surrogate_id"], unique = true)
    ]
)
data class StalkerRemoteIdentityEntity(
    @ColumnInfo(name = "provider_id") val providerId: Long,
    @ColumnInfo(name = "content_type") val contentType: ContentType,
    @ColumnInfo(name = "raw_id") val rawId: String,
    @ColumnInfo(name = "surrogate_id") val surrogateId: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)

@Entity(
    tableName = "stalker_discovery_staging",
    indices = [
        Index(value = ["provider_id"]),
        Index(value = ["created_at"])
    ]
)
data class StalkerDiscoveryStageEntity(
    @androidx.room.PrimaryKey
    @ColumnInfo(name = "discovery_id") val discoveryId: String,
    @ColumnInfo(name = "provider_id") val providerId: Long?,
    @ColumnInfo(name = "configuration_generation") val configurationGeneration: Long,
    /** Sanitized summary only; no credentials, cookies, tokens, bodies, or stream URLs. */
    @ColumnInfo(name = "sanitized_summary") val sanitizedSummary: String,
    @ColumnInfo(name = "categories_json") val categoriesJson: String = "[]",
    @ColumnInfo(name = "channels_json") val channelsJson: String = "[]",
    @ColumnInfo(name = "created_at") val createdAt: Long
)
