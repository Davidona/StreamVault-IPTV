package com.streamvault.data.repository

import androidx.work.ExistingWorkPolicy
import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.dao.ProviderDeletionCleanupDao
import com.streamvault.data.local.entity.ProviderDeletionCleanupEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.Assert.assertThrows

class ProviderDeletionCleanupWorkerTest {

    @Test fun `live usage cleanup is durable across failure and retry`() = runTest {
        val dao = FakeCleanupDao(listOf(cleanup(1, ProviderDeletionCleanupWorker.LIVE_USAGE)))
        var attempts = 0
        val cleanup: suspend (Long) -> Unit = {
            attempts++
            if (attempts == 1) error("temporary failure")
        }
        assertThat(drainProviderDeletionCleanup(dao, {}, {}, {}, cleanup)).isEqualTo(ProviderDeletionDrainOutcome.RETRY)
        assertThat(dao.items).hasSize(1)
        assertThat(drainProviderDeletionCleanup(dao, {}, {}, {}, cleanup)).isEqualTo(ProviderDeletionDrainOutcome.COMPLETE)
        assertThat(dao.items).isEmpty()
    }

    @Test
    fun `drain completes recording reminder and sync tombstones`() = runTest {
        val dao = FakeCleanupDao(
            listOf(
                cleanup(1, ProviderDeletionCleanupWorker.RECORDING_ALARM, "run-1"),
                cleanup(2, ProviderDeletionCleanupWorker.REMINDER_ALARM, "42"),
                cleanup(3, ProviderDeletionCleanupWorker.SYNC_RUNTIME)
            )
        )
        val actions = mutableListOf<String>()

        val outcome = drainProviderDeletionCleanup(
            dao = dao,
            cancelRecordingAlarm = { actions += "recording:$it" },
            cancelReminderAlarm = { actions += "reminder:$it" },
            cleanupSyncRuntime = { actions += "sync:$it" }
        )

        assertThat(outcome).isEqualTo(ProviderDeletionDrainOutcome.COMPLETE)
        assertThat(actions).containsExactly("recording:run-1", "reminder:42", "sync:7").inOrder()
        assertThat(dao.items).isEmpty()
    }

    @Test
    fun `each external cleanup failure retains its tombstone and requests retry`() = runTest {
        listOf(
            ProviderDeletionCleanupWorker.RECORDING_ALARM,
            ProviderDeletionCleanupWorker.REMINDER_ALARM,
            ProviderDeletionCleanupWorker.SYNC_RUNTIME
        ).forEachIndexed { index, failingAction ->
            val dao = FakeCleanupDao(listOf(cleanup(index + 1L, failingAction, targetFor(failingAction))))

            val outcome = drainProviderDeletionCleanup(
                dao = dao,
                cancelRecordingAlarm = {
                    if (failingAction == ProviderDeletionCleanupWorker.RECORDING_ALARM) error("recording failed")
                },
                cancelReminderAlarm = {
                    if (failingAction == ProviderDeletionCleanupWorker.REMINDER_ALARM) error("reminder failed")
                },
                cleanupSyncRuntime = {
                    if (failingAction == ProviderDeletionCleanupWorker.SYNC_RUNTIME) error("sync failed")
                }
            )

            assertThat(outcome).isEqualTo(ProviderDeletionDrainOutcome.RETRY)
            assertThat(dao.items).hasSize(1)
            assertThat(dao.failureAttempts).containsExactly(index + 1L)
        }
    }

    @Test
    fun `process restart after each side effect replays idempotently and completes tombstone`() = runTest {
        listOf(
            ProviderDeletionCleanupWorker.RECORDING_ALARM,
            ProviderDeletionCleanupWorker.REMINDER_ALARM,
            ProviderDeletionCleanupWorker.SYNC_RUNTIME
        ).forEachIndexed { index, action ->
            val dao = FakeCleanupDao(
                listOf(cleanup(index + 1L, action, targetFor(action)))
            ).apply {
                failNextDelete = true
            }
            var sideEffects = 0

            fun recording(recordingId: String) {
                recordingId.length
                sideEffects += 1
            }
            fun reminder(reminderId: Long) {
                reminderId.toString()
                sideEffects += 1
            }
            suspend fun sync(providerId: Long) {
                providerId.toString()
                sideEffects += 1
            }

            val first = drainProviderDeletionCleanup(dao, ::recording, ::reminder, ::sync)
            val afterRestart = drainProviderDeletionCleanup(dao, ::recording, ::reminder, ::sync)

            assertThat(first).isEqualTo(ProviderDeletionDrainOutcome.RETRY)
            assertThat(afterRestart).isEqualTo(ProviderDeletionDrainOutcome.COMPLETE)
            assertThat(sideEffects).isEqualTo(2)
            assertThat(dao.items).isEmpty()
        }
    }

    @Test
    fun `database read and diagnostic write failures request retry`() = runTest {
        val readFailureDao = FakeCleanupDao(emptyList()).apply { failNextRead = true }
        assertThat(drain(readFailureDao)).isEqualTo(ProviderDeletionDrainOutcome.RETRY)

        val diagnosticFailureDao = FakeCleanupDao(
            listOf(cleanup(1, ProviderDeletionCleanupWorker.RECORDING_ALARM, "run-1"))
        ).apply {
            failRecordFailure = true
        }
        val outcome = drainProviderDeletionCleanup(
            diagnosticFailureDao,
            cancelRecordingAlarm = { error("alarm failure") },
            cancelReminderAlarm = {},
            cleanupSyncRuntime = {}
        )

        assertThat(outcome).isEqualTo(ProviderDeletionDrainOutcome.RETRY)
        assertThat(diagnosticFailureDao.items).hasSize(1)
    }

    @Test
    fun `drain sees tombstones added after its first batch`() = runTest {
        val second = cleanup(2, ProviderDeletionCleanupWorker.REMINDER_ALARM, "42")
        val dao = FakeCleanupDao(
            listOf(cleanup(1, ProviderDeletionCleanupWorker.RECORDING_ALARM, "run-1"))
        ).apply {
            itemToAddAfterFirstDelete = second
        }
        val actions = mutableListOf<String>()

        val outcome = drainProviderDeletionCleanup(
            dao,
            cancelRecordingAlarm = { actions += "recording" },
            cancelReminderAlarm = { actions += "reminder" },
            cleanupSyncRuntime = {}
        )

        assertThat(outcome).isEqualTo(ProviderDeletionDrainOutcome.COMPLETE)
        assertThat(actions).containsExactly("recording", "reminder").inOrder()
        assertThat(dao.items).isEmpty()
    }

    @Test
    fun `repeated drain after completion is a no op`() = runTest {
        val dao = FakeCleanupDao(
            listOf(cleanup(1, ProviderDeletionCleanupWorker.SYNC_RUNTIME))
        )
        var calls = 0

        assertThat(
            drainProviderDeletionCleanup(dao, {}, {}, { calls += 1 })
        ).isEqualTo(ProviderDeletionDrainOutcome.COMPLETE)
        assertThat(
            drainProviderDeletionCleanup(dao, {}, {}, { calls += 1 })
        ).isEqualTo(ProviderDeletionDrainOutcome.COMPLETE)

        assertThat(calls).isEqualTo(1)
    }

    @Test
    fun `cancellation is propagated without consuming tombstone`() {
        val dao = FakeCleanupDao(
            listOf(cleanup(1, ProviderDeletionCleanupWorker.SYNC_RUNTIME))
        )

        assertThrows(CancellationException::class.java) {
            runTest {
                drainProviderDeletionCleanup(
                    dao,
                    cancelRecordingAlarm = {},
                    cancelReminderAlarm = {},
                    cleanupSyncRuntime = { throw CancellationException("stopped") }
                )
            }
        }
        assertThat(dao.items).hasSize(1)
    }

    @Test
    fun `new deletion appends reconciliation behind running work`() {
        assertThat(PROVIDER_DELETION_EXISTING_WORK_POLICY)
            .isEqualTo(ExistingWorkPolicy.APPEND_OR_REPLACE)
    }

    private suspend fun drain(dao: ProviderDeletionCleanupDao) =
        drainProviderDeletionCleanup(dao, {}, {}, {})

    private fun cleanup(id: Long, action: String, targetId: String = "") =
        ProviderDeletionCleanupEntity(
            id = id,
            providerId = 7,
            action = action,
            targetId = targetId
        )

    private fun targetFor(action: String): String = when (action) {
        ProviderDeletionCleanupWorker.RECORDING_ALARM -> "run-1"
        ProviderDeletionCleanupWorker.REMINDER_ALARM -> "42"
        else -> ""
    }

    private class FakeCleanupDao(initial: List<ProviderDeletionCleanupEntity>) :
        ProviderDeletionCleanupDao {
        val items = initial.toMutableList()
        val failureAttempts = mutableListOf<Long>()
        var failNextRead = false
        var failNextDelete = false
        var failRecordFailure = false
        var itemToAddAfterFirstDelete: ProviderDeletionCleanupEntity? = null

        override suspend fun insertAll(items: List<ProviderDeletionCleanupEntity>) {
            this.items += items
        }

        override suspend fun getBatch(limit: Int): List<ProviderDeletionCleanupEntity> {
            if (failNextRead) {
                failNextRead = false
                error("database unavailable")
            }
            return items.take(limit)
        }

        override suspend fun countByProvider(providerId: Long): Int =
            items.count { it.providerId == providerId }

        override suspend fun delete(id: Long) {
            if (failNextDelete) {
                failNextDelete = false
                error("process stopped before tombstone commit")
            }
            items.removeAll { it.id == id }
            itemToAddAfterFirstDelete?.let {
                items += it
                itemToAddAfterFirstDelete = null
            }
        }

        override suspend fun recordFailure(id: Long, error: String) {
            if (failRecordFailure) throw IllegalStateException("diagnostic write failed")
            failureAttempts += id
        }
    }
}
