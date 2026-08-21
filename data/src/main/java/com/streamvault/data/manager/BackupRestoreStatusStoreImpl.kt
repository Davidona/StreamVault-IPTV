package com.streamvault.data.manager

import com.streamvault.data.local.dao.BackupRestoreLedgerDao
import com.streamvault.data.local.entity.BackupRestoreItemEntity
import com.streamvault.domain.manager.BackupRestoreJobStatus
import com.streamvault.domain.manager.BackupRestoreItemStatus
import com.streamvault.domain.manager.BackupRestoreProviderStatus
import com.streamvault.domain.manager.BackupRestoreStatusStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

@Singleton
class BackupRestoreStatusStoreImpl @Inject constructor(
    private val ledgerDao: BackupRestoreLedgerDao,
    private val coordinator: PendingBackupRestoreCoordinator
) : BackupRestoreStatusStore {
    override fun observeRestoreJobs(): Flow<List<BackupRestoreJobStatus>> =
        combine(ledgerDao.observeJobs(), ledgerDao.observeItems()) { jobs, items ->
            jobs.map { job ->
                val jobItems = items.filter {
                    it.jobId == job.id && it.section != "REPLACE_SCOPE"
                }
                BackupRestoreJobStatus(
                    jobId = job.id,
                    backupVersion = job.backupVersion,
                    status = job.status,
                    providers = jobItems.groupBy { it.providerIdentityKey }.map { (key, providerItems) ->
                        BackupRestoreProviderStatus(
                            providerIdentityKey = key,
                            localProviderId = providerItems.mapNotNull { it.localProviderId }.distinct().singleOrNull(),
                            appliedCount = providerItems.count { it.status == BackupRestoreItemEntity.STATUS_APPLIED },
                            pendingCount = providerItems.count { it.status == BackupRestoreItemEntity.STATUS_PENDING },
                            unresolvedCount = providerItems.count { it.status == BackupRestoreItemEntity.STATUS_UNRESOLVED },
                            failedCount = providerItems.count { it.status == BackupRestoreItemEntity.STATUS_FAILED_RETRYABLE },
                            items = providerItems.map { item ->
                                BackupRestoreItemStatus(
                                    id = item.id,
                                    section = item.section,
                                    contentType = item.contentType,
                                    status = item.status,
                                    attempts = item.attemptCount,
                                    lastError = item.lastError
                                )
                            }
                        )
                    },
                    updatedAt = job.updatedAt
                )
            }
        }

    override suspend fun retryProviders(providerIds: Set<Long>) {
        providerIds.forEach { coordinator.applyForProvider(it) }
    }

    override suspend fun dismissItem(itemId: Long) {
        val item = ledgerDao.getItem(itemId) ?: return
        val now = System.currentTimeMillis()
        ledgerDao.dismissItem(itemId, now)
        ledgerDao.refreshJobCounts(item.jobId, now)
    }

    override suspend fun dismissProvider(jobId: String, providerIdentityKey: String) {
        val now = System.currentTimeMillis()
        ledgerDao.dismissProvider(jobId, providerIdentityKey, now)
        ledgerDao.refreshJobCounts(jobId, now)
    }

    override suspend fun dismissRestore(jobId: String) {
        ledgerDao.dismissJob(jobId, System.currentTimeMillis())
    }
}
