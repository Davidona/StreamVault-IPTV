package com.streamvault.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.streamvault.data.local.entity.BackupRestoreItemEntity
import com.streamvault.data.local.entity.BackupRestoreJobEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BackupRestoreLedgerDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertJob(job: BackupRestoreJobEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertItems(items: List<BackupRestoreItemEntity>): List<Long>

    @Query("SELECT * FROM backup_restore_jobs WHERE id = :jobId")
    suspend fun getJob(jobId: String): BackupRestoreJobEntity?

    @Query("SELECT * FROM backup_restore_jobs ORDER BY updated_at DESC")
    fun observeJobs(): Flow<List<BackupRestoreJobEntity>>

    @Query("SELECT * FROM backup_restore_items ORDER BY created_at, id")
    fun observeItems(): Flow<List<BackupRestoreItemEntity>>

    @Query("SELECT * FROM backup_restore_items WHERE job_id = :jobId ORDER BY id")
    suspend fun getItems(jobId: String): List<BackupRestoreItemEntity>

    @Query("SELECT * FROM backup_restore_items WHERE id = :itemId")
    suspend fun getItem(itemId: Long): BackupRestoreItemEntity?

    @Query(
        """
        SELECT * FROM backup_restore_items
        WHERE provider_identity_key = :providerIdentityKey
          AND status IN ('PENDING', 'UNRESOLVED', 'FAILED_RETRYABLE')
        ORDER BY created_at, id
        """
    )
    suspend fun getRetryableItems(providerIdentityKey: String): List<BackupRestoreItemEntity>

    @Query(
        """
        SELECT * FROM backup_restore_items
        WHERE local_provider_id = :providerId
          AND status IN ('PENDING', 'UNRESOLVED', 'FAILED_RETRYABLE')
        ORDER BY created_at, id
        """
    )
    suspend fun getRetryableItemsByLocalProviderId(providerId: Long): List<BackupRestoreItemEntity>

    @Query(
        """
        UPDATE backup_restore_items
        SET status = :status,
            local_provider_id = :localProviderId,
            attempt_count = attempt_count + :attemptIncrement,
            last_error = :lastError,
            updated_at = :updatedAt
        WHERE id = :itemId
        """
    )
    suspend fun updateItemStatus(
        itemId: Long,
        status: String,
        localProviderId: Long?,
        attemptIncrement: Int,
        lastError: String?,
        updatedAt: Long
    )

    @Query(
        """
        UPDATE backup_restore_items
        SET status = 'DISMISSED', updated_at = :updatedAt
        WHERE id = :itemId AND status != 'APPLIED'
        """
    )
    suspend fun dismissItem(itemId: Long, updatedAt: Long): Int

    @Query(
        """
        UPDATE backup_restore_items
        SET status = 'DISMISSED', updated_at = :updatedAt
        WHERE job_id = :jobId AND status != 'APPLIED'
        """
    )
    suspend fun dismissJobItems(jobId: String, updatedAt: Long): Int

    @Query(
        """
        UPDATE backup_restore_items SET status = 'DISMISSED', updated_at = :updatedAt
        WHERE job_id = :jobId AND provider_identity_key = :providerIdentityKey AND status != 'APPLIED'
        """
    )
    suspend fun dismissProvider(jobId: String, providerIdentityKey: String, updatedAt: Long): Int

    @Query(
        """
        UPDATE backup_restore_jobs
        SET total_count = (SELECT COUNT(*) FROM backup_restore_items WHERE job_id = :jobId AND section != 'REPLACE_SCOPE'),
            pending_count = (SELECT COUNT(*) FROM backup_restore_items WHERE job_id = :jobId AND section != 'REPLACE_SCOPE' AND status = 'PENDING'),
            applied_count = (SELECT COUNT(*) FROM backup_restore_items WHERE job_id = :jobId AND section != 'REPLACE_SCOPE' AND status = 'APPLIED'),
            unresolved_count = (SELECT COUNT(*) FROM backup_restore_items WHERE job_id = :jobId AND section != 'REPLACE_SCOPE' AND status = 'UNRESOLVED'),
            failed_count = (SELECT COUNT(*) FROM backup_restore_items WHERE job_id = :jobId AND section != 'REPLACE_SCOPE' AND status = 'FAILED_RETRYABLE'),
            status = CASE
                WHEN EXISTS(SELECT 1 FROM backup_restore_items WHERE job_id = :jobId AND status = 'FAILED_RETRYABLE') THEN 'PARTIAL'
                WHEN EXISTS(SELECT 1 FROM backup_restore_items WHERE job_id = :jobId AND status IN ('PENDING', 'UNRESOLVED')) THEN 'WAITING_FOR_SYNC'
                ELSE 'COMPLETE'
            END,
            updated_at = :updatedAt
        WHERE id = :jobId
        """
    )
    suspend fun refreshJobCounts(jobId: String, updatedAt: Long)

    @Transaction
    suspend fun insertLedger(job: BackupRestoreJobEntity, items: List<BackupRestoreItemEntity>) {
        insertJob(job)
        if (items.isNotEmpty()) insertItems(items)
        refreshJobCounts(job.id, job.updatedAt)
    }

    @Transaction
    suspend fun dismissJob(jobId: String, updatedAt: Long) {
        dismissJobItems(jobId, updatedAt)
        refreshJobCounts(jobId, updatedAt)
    }
}
