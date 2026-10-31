package com.streamvault.data.local

import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.entity.BackupRestoreItemEntity
import com.streamvault.data.local.entity.BackupRestoreJobEntity
import com.streamvault.data.manager.BackupRestoreStatusStoreImpl
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.mockito.kotlin.mock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BackupRestoreLedgerDaoTest {
    private lateinit var database: StreamVaultDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), StreamVaultDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After
    fun tearDown() { database.close() }

    private fun job(id: String = "job") = BackupRestoreJobEntity(
        id = id, restoreKey = id, backupVersion = 19, conflictStrategy = "KEEP_EXISTING",
        status = "WAITING_FOR_SYNC", createdAt = 1, updatedAt = 1
    )

    private fun item(id: Long, status: String = "PENDING", provider: String = "provider", job: String = "job") =
        BackupRestoreItemEntity(
            id = id, jobId = job, providerIdentityKey = provider, localProviderId = 77,
            section = "FAVORITES", stableReferenceKey = "LIVE:$id", referenceJson = "{}", payloadJson = "{}",
            status = status, createdAt = id, updatedAt = id
        )

    @Test
    fun onlyPendingUnresolvedAndFailedItemsAreRetriedInOrder() = runBlocking {
        val dao = database.backupRestoreLedgerDao()
        dao.insertLedger(job(), listOf(
            item(3, "FAILED_RETRYABLE"), item(1, "PENDING"), item(5, "DISMISSED"),
            item(4, "APPLIED"), item(2, "UNRESOLVED"), item(6, provider = "other").copy(localProviderId = 88)
        ))
        assertThat(dao.getRetryableItems("provider").map { it.id }).containsExactly(1L, 2L, 3L).inOrder()
        assertThat(dao.getRetryableItemsByLocalProviderId(77).map { it.id }).containsExactly(1L, 2L, 3L).inOrder()
    }

    @Test
    fun jobCountsAndStatusFollowFailureWaitingAndCompletion() = runBlocking {
        val dao = database.backupRestoreLedgerDao()
        dao.insertLedger(job(), listOf(item(1), item(2, "UNRESOLVED"), item(3, "FAILED_RETRYABLE"), item(4, "APPLIED"),
            item(5, "DISMISSED"), item(6).copy(section = "REPLACE_SCOPE")))
        var current = requireNotNull(dao.getJob("job"))
        assertThat(current.status).isEqualTo("PARTIAL")
        assertThat(listOf(current.totalCount, current.pendingCount, current.unresolvedCount, current.failedCount, current.appliedCount))
            .containsExactly(5, 1, 1, 1, 1).inOrder()
        dao.updateItemStatus(3, "APPLIED", 88, 1, null, 20)
        dao.refreshJobCounts("job", 20)
        current = requireNotNull(dao.getJob("job"))
        assertThat(current.status).isEqualTo("WAITING_FOR_SYNC")
        assertThat(current.failedCount).isEqualTo(0)
        assertThat(current.appliedCount).isEqualTo(2)
        dao.dismissJob("job", 30)
        current = requireNotNull(dao.getJob("job"))
        assertThat(current.status).isEqualTo("COMPLETE")
        assertThat(current.pendingCount + current.unresolvedCount + current.failedCount).isEqualTo(0)
        assertThat(current.appliedCount).isEqualTo(2)
        assertThat(dao.getRetryableItems("provider")).isEmpty()
    }

    @Test
    fun dismissalIsScopedAndNeverDismissesAppliedItems() = runBlocking {
        val dao = database.backupRestoreLedgerDao()
        dao.insertLedger(job(), listOf(item(1), item(2, "APPLIED"), item(3, provider = "other")))
        dao.insertLedger(job("other-job"), listOf(item(4, job = "other-job")))
        assertThat(dao.dismissItem(2, 10)).isEqualTo(0)
        assertThat(dao.dismissProvider("job", "provider", 10)).isEqualTo(1)
        assertThat(dao.getItem(1)?.status).isEqualTo("DISMISSED")
        assertThat(dao.getItem(2)?.status).isEqualTo("APPLIED")
        assertThat(dao.getItem(3)?.status).isEqualTo("PENDING")
        assertThat(dao.getItem(4)?.status).isEqualTo("PENDING")
        dao.dismissJob("job", 20)
        assertThat(dao.getItem(3)?.status).isEqualTo("DISMISSED")
        assertThat(dao.getItem(4)?.status).isEqualTo("PENDING")
    }

    @Test
    fun repeatedLedgerInsertDoesNotDuplicateOrResetAppliedState() = runBlocking {
        val dao = database.backupRestoreLedgerDao()
        dao.insertLedger(job(), listOf(item(1)))
        dao.updateItemStatus(1, "APPLIED", 88, 1, null, 10)
        dao.insertLedger(job(), listOf(item(99).copy(stableReferenceKey = "LIVE:1")))
        val saved = dao.getItems("job").single()
        assertThat(saved.id).isEqualTo(1)
        assertThat(saved.status).isEqualTo("APPLIED")
        assertThat(saved.localProviderId).isEqualTo(88)
        assertThat(dao.getJob("job")?.status).isEqualTo("COMPLETE")
    }

    @Test
    fun retryHistoryAndProviderRebindingSurviveDatabaseReopen() = runBlocking {
        val context: android.content.Context = RuntimeEnvironment.getApplication()
        val name = "backup-restore-restart-test.db"
        context.deleteDatabase(name)
        fun open() = Room.databaseBuilder(context, StreamVaultDatabase::class.java, name).allowMainThreadQueries().build()
        suspend fun withDisk(block: suspend (StreamVaultDatabase) -> Unit) {
            val disk = open()
            try { block(disk) } finally { disk.close() }
        }
        try {
            withDisk { disk ->
                val dao = disk.backupRestoreLedgerDao()
                dao.insertLedger(job(), listOf(item(1)))
                dao.updateItemStatus(1, "FAILED_RETRYABLE", 77, 1, "write failed", 10)
                dao.updateItemStatus(1, "UNRESOLVED", 88, 1, "catalog missing", 20)
                dao.refreshJobCounts("job", 20)
            }
            withDisk { disk ->
                val dao = disk.backupRestoreLedgerDao()
                val pending = dao.getRetryableItemsByLocalProviderId(88).single()
                assertThat(pending.attemptCount).isEqualTo(2)
                assertThat(pending.lastError).isEqualTo("catalog missing")
                assertThat(dao.getRetryableItemsByLocalProviderId(77)).isEmpty()
                assertThat(dao.getJob("job")?.status).isEqualTo("WAITING_FOR_SYNC")
                dao.updateItemStatus(1, "APPLIED", 88, 1, null, 30)
                dao.refreshJobCounts("job", 30)
                assertThat(dao.getItem(1)?.attemptCount).isEqualTo(3)
                assertThat(dao.getItem(1)?.lastError).isNull()
                assertThat(dao.getJob("job")?.status).isEqualTo("COMPLETE")
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test
    fun statusStoreGroupsItemsAndHidesReplaceMarkersWithoutMixingJobs() = runBlocking {
        val dao = database.backupRestoreLedgerDao()
        dao.insertLedger(job(), listOf(item(1), item(2, "UNRESOLVED"), item(3, "FAILED_RETRYABLE"),
            item(4, "APPLIED"), item(5, "DISMISSED"),
            item(6, provider = "other").copy(localProviderId = 88),
            item(7).copy(section = "REPLACE_SCOPE")))
        dao.insertLedger(job("other-job"), listOf(item(8, job = "other-job")))
        val store = BackupRestoreStatusStoreImpl(dao, mock())
        val jobs = store.observeRestoreJobs().first()
        assertThat(jobs).hasSize(2)
        val providers = jobs.single { it.jobId == "job" }.providers
        assertThat(providers).hasSize(2)
        val provider = providers.single { it.providerIdentityKey == "provider" }
        assertThat(provider.items.map { it.id }).containsExactly(1L, 2L, 3L, 4L, 5L)
        assertThat(listOf(provider.pendingCount, provider.unresolvedCount, provider.failedCount, provider.appliedCount))
            .containsExactly(1, 1, 1, 1).inOrder()
        assertThat(provider.localProviderId).isEqualTo(77)
        assertThat(providers.single { it.providerIdentityKey == "other" }.localProviderId).isEqualTo(88)
    }

    @Test
    fun statusStoreDismissalsRefreshCountsAndIgnoreMissingItems() = runBlocking {
        val dao = database.backupRestoreLedgerDao()
        dao.insertLedger(job(), listOf(item(1), item(2, "UNRESOLVED"), item(3, "FAILED_RETRYABLE", "other")))
        val store = BackupRestoreStatusStoreImpl(dao, mock())
        store.dismissItem(999)
        store.dismissItem(1)
        assertThat(dao.getJob("job")?.pendingCount).isEqualTo(0)
        store.dismissProvider("job", "other")
        assertThat(dao.getJob("job")?.status).isEqualTo("WAITING_FOR_SYNC")
        assertThat(dao.getJob("job")?.failedCount).isEqualTo(0)
        store.dismissRestore("job")
        assertThat(store.observeRestoreJobs().first().single().status).isEqualTo("COMPLETE")
        assertThat(dao.getRetryableItemsByLocalProviderId(77)).isEmpty()
    }
}
