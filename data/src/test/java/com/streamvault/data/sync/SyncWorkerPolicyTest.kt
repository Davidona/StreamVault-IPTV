package com.streamvault.data.sync

import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteFullException
import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.settings.DatabaseMaintenanceSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.junit.Test

class SyncWorkerPolicyTest {

    @Test
    fun `completed maintenance persists one success snapshot`() = runTest {
        val snapshots = mutableListOf<DatabaseMaintenanceSnapshot>()
        val report = maintenanceReport()

        val outcome = completeMaintenanceRun(
            runMaintenance = { DatabaseMaintenanceManager.MaintenanceRunResult.Completed(report) },
            persistSnapshot = { snapshot -> snapshots += snapshot },
            nowMillis = { 1234L }
        )

        assertThat(outcome).isEqualTo(MaintenanceWorkerOutcome.Completed(report))
        assertThat(snapshots).hasSize(1)
        assertThat(snapshots.single().ranAt).isEqualTo(1234L)
        assertThat(snapshots.single().deletedPrograms).isEqualTo(report.deletedPrograms)
        assertThat(snapshots.single().reclaimableBytes).isEqualTo(report.statsAfterVacuum.reclaimableBytes)
    }

    @Test
    fun `deferred maintenance does not persist a success snapshot`() = runTest {
        val snapshots = mutableListOf<DatabaseMaintenanceSnapshot>()

        val outcome = completeMaintenanceRun(
            runMaintenance = { DatabaseMaintenanceManager.MaintenanceRunResult.DeferredForActiveSync },
            persistSnapshot = { snapshot -> snapshots += snapshot }
        )

        assertThat(outcome).isEqualTo(MaintenanceWorkerOutcome.DeferredForActiveSync)
        assertThat(snapshots).isEmpty()
    }

    @Test
    fun `maintenance cancellation does not persist a failure or success snapshot`() = runTest {
        val snapshots = mutableListOf<DatabaseMaintenanceSnapshot>()
        var cancellation: CancellationException? = null

        try {
            completeMaintenanceRun(
                runMaintenance = { throw CancellationException("maintenance cancelled") },
                persistSnapshot = { snapshot -> snapshots += snapshot }
            )
        } catch (error: CancellationException) {
            cancellation = error
        }

        assertThat(cancellation).isNotNull()
        assertThat(snapshots).isEmpty()
    }

    @Test
    fun `maintenance retries locked busy and full databases`() {
        assertThat(shouldRetryMaintenance(sqliteException("database is locked"))).isTrue()
        assertThat(shouldRetryMaintenance(sqliteException("database is busy"))).isTrue()
        assertThat(shouldRetryMaintenance(SQLiteFullException("database or disk is full"))).isTrue()
        assertThat(shouldRetryMaintenance(sqliteException("database or disk is full"))).isTrue()
        assertThat(shouldRetryMaintenance(IllegalArgumentException("bad request"))).isFalse()
    }

    private fun sqliteException(message: String): SQLiteException = mock<SQLiteException>().also { error ->
        whenever(error.message).thenReturn(message)
    }

    private fun maintenanceReport() = DatabaseMaintenanceManager.MaintenanceReport(
        deletedPrograms = 3,
        deletedOrphanEpisodes = 2,
        deletedStaleFavorites = 1,
        vacuumRan = true,
        statsBeforeVacuum = DatabaseMaintenanceManager.DatabaseStorageStats(
            pageSizeBytes = 4096,
            pageCount = 100,
            freelistCount = 10,
            mainDbBytes = 409_600,
            walBytes = 1024
        ),
        statsAfterVacuum = DatabaseMaintenanceManager.DatabaseStorageStats(
            pageSizeBytes = 4096,
            pageCount = 90,
            freelistCount = 1,
            mainDbBytes = 368_640,
            walBytes = 0
        ),
        tableStats = DatabaseMaintenanceManager.TableRowStats(
            channels = 10,
            movies = 20,
            series = 30,
            episodes = 40,
            programs = 50,
            epgProgrammes = 60,
            playbackHistory = 70,
            favorites = 80,
            programReminders = 90
        )
    )
}
