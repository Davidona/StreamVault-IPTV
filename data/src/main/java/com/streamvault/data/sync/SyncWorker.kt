package com.streamvault.data.sync

import android.content.Context
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteFullException
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.streamvault.domain.settings.DatabaseMaintenanceSnapshot
import com.streamvault.data.preferences.PreferencesRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

class SyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface SyncWorkerEntryPoint {
        fun databaseMaintenanceManager(): DatabaseMaintenanceManager
        fun preferencesRepository(): PreferencesRepository
    }

    override suspend fun doWork(): Result {
        Log.d("SyncWorker", "Starting data maintenance...")
        return try {
            val entryPoint = EntryPointAccessors.fromApplication(applicationContext, SyncWorkerEntryPoint::class.java)
            when (
                val outcome = completeMaintenanceRun(
                    runMaintenance = entryPoint.databaseMaintenanceManager()::runDailyMaintenance,
                    persistSnapshot = entryPoint.preferencesRepository()::setLastMaintenanceSnapshot
                )
            ) {
                is MaintenanceWorkerOutcome.Completed -> {
                    val report = outcome.report
                    Log.d(
                        "SyncWorker",
                        "Maintenance complete: oldPrograms=${report.deletedPrograms}, orphanEpisodes=${report.deletedOrphanEpisodes}, " +
                            "staleFavorites=${report.deletedStaleFavorites}, vacuumRan=${report.vacuumRan}, " +
                            "dbBytes=${report.statsAfterVacuum.mainDbBytes}, walBytes=${report.statsAfterVacuum.walBytes}, " +
                            "reclaimableBytes=${report.statsAfterVacuum.reclaimableBytes}"
                    )
                    Result.success()
                }
                MaintenanceWorkerOutcome.DeferredForActiveSync -> {
                    Log.i("SyncWorker", "Deferring maintenance because provider sync work is active")
                    Result.retry()
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.e("SyncWorker", "Failed to run data maintenance", e)
            if (shouldRetry(e)) Result.retry() else Result.failure()
        }
    }

    private fun shouldRetry(error: Throwable): Boolean = shouldRetryMaintenance(error)
}

internal sealed interface MaintenanceWorkerOutcome {
    data class Completed(val report: DatabaseMaintenanceManager.MaintenanceReport) : MaintenanceWorkerOutcome
    data object DeferredForActiveSync : MaintenanceWorkerOutcome
}

internal suspend fun completeMaintenanceRun(
    runMaintenance: suspend () -> DatabaseMaintenanceManager.MaintenanceRunResult,
    persistSnapshot: suspend (DatabaseMaintenanceSnapshot) -> Unit,
    nowMillis: () -> Long = System::currentTimeMillis
): MaintenanceWorkerOutcome = when (val result = runMaintenance()) {
    is DatabaseMaintenanceManager.MaintenanceRunResult.Completed -> {
        val report = result.report
        persistSnapshot(
            DatabaseMaintenanceSnapshot(
                ranAt = nowMillis(),
                deletedPrograms = report.deletedPrograms,
                deletedExternalProgrammes = report.deletedExternalProgrammes,
                deletedOrphanEpisodes = report.deletedOrphanEpisodes,
                deletedStaleFavorites = report.deletedStaleFavorites,
                vacuumRan = report.vacuumRan,
                mainDbBytes = report.statsAfterVacuum.mainDbBytes,
                walBytes = report.statsAfterVacuum.walBytes,
                reclaimableBytes = report.statsAfterVacuum.reclaimableBytes,
                channelRows = report.tableStats.channels,
                movieRows = report.tableStats.movies,
                seriesRows = report.tableStats.series,
                episodeRows = report.tableStats.episodes,
                programRows = report.tableStats.programs,
                epgProgrammeRows = report.tableStats.epgProgrammes,
                playbackHistoryRows = report.tableStats.playbackHistory,
                favoriteRows = report.tableStats.favorites
            )
        )
        MaintenanceWorkerOutcome.Completed(report)
    }
    DatabaseMaintenanceManager.MaintenanceRunResult.DeferredForActiveSync ->
        MaintenanceWorkerOutcome.DeferredForActiveSync
}

internal fun shouldRetryMaintenance(error: Throwable): Boolean = when (error) {
    is java.io.IOException,
    is SQLiteFullException -> true
    is SQLiteException -> error.message.orEmpty().let { message ->
        message.contains("locked", ignoreCase = true) ||
            message.contains("busy", ignoreCase = true) ||
            message.contains("full", ignoreCase = true)
    }
    else -> false
}
