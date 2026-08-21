package com.streamvault.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "backup_restore_jobs",
    indices = [
        Index(value = ["restore_key"], unique = true),
        Index(value = ["status"]),
        Index(value = ["updated_at"])
    ]
)
data class BackupRestoreJobEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "restore_key") val restoreKey: String,
    @ColumnInfo(name = "backup_version") val backupVersion: Int,
    @ColumnInfo(name = "conflict_strategy") val conflictStrategy: String,
    val status: String,
    @ColumnInfo(name = "total_count") val totalCount: Int = 0,
    @ColumnInfo(name = "pending_count") val pendingCount: Int = 0,
    @ColumnInfo(name = "applied_count") val appliedCount: Int = 0,
    @ColumnInfo(name = "unresolved_count") val unresolvedCount: Int = 0,
    @ColumnInfo(name = "failed_count") val failedCount: Int = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)

@Entity(
    tableName = "backup_restore_items",
    foreignKeys = [
        ForeignKey(
            entity = BackupRestoreJobEntity::class,
            parentColumns = ["id"],
            childColumns = ["job_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["job_id"]),
        Index(value = ["provider_identity_key", "status"]),
        Index(value = ["job_id", "status"]),
        Index(
            name = "index_backup_restore_items_job_id_section_stable_reference_key",
            value = ["job_id", "section", "stable_reference_key"],
            unique = true
        )
    ]
)
data class BackupRestoreItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "job_id") val jobId: String,
    @ColumnInfo(name = "provider_identity_key") val providerIdentityKey: String,
    @ColumnInfo(name = "local_provider_id") val localProviderId: Long? = null,
    val section: String,
    @ColumnInfo(name = "content_type") val contentType: String? = null,
    @ColumnInfo(name = "stable_reference_key") val stableReferenceKey: String,
    @ColumnInfo(name = "reference_json") val referenceJson: String,
    @ColumnInfo(name = "payload_json") val payloadJson: String,
    val status: String = STATUS_PENDING,
    @ColumnInfo(name = "attempt_count") val attemptCount: Int = 0,
    @ColumnInfo(name = "last_error") val lastError: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
) {
    companion object {
        const val STATUS_PENDING = "PENDING"
        const val STATUS_APPLIED = "APPLIED"
        const val STATUS_UNRESOLVED = "UNRESOLVED"
        const val STATUS_FAILED_RETRYABLE = "FAILED_RETRYABLE"
        const val STATUS_DISMISSED = "DISMISSED"
    }
}
