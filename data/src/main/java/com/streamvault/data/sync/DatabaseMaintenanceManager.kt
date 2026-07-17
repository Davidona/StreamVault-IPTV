package com.streamvault.data.sync

import android.util.Log
import androidx.annotation.WorkerThread
import com.streamvault.data.local.StreamVaultDatabase
import com.streamvault.data.local.dao.ChannelDao
import com.streamvault.data.local.dao.EpgProgrammeDao
import com.streamvault.data.local.dao.EpisodeDao
import com.streamvault.data.local.dao.FavoriteDao
import com.streamvault.data.local.dao.ProgramDao
import com.streamvault.data.local.dao.ProgramReminderDao
import com.streamvault.data.local.dao.SearchHistoryDao
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class DatabaseMaintenanceManager @Inject constructor(
    private val database: StreamVaultDatabase,
    private val channelDao: ChannelDao,
    private val programDao: ProgramDao,
    private val epgProgrammeDao: EpgProgrammeDao,
    private val episodeDao: EpisodeDao,
    private val favoriteDao: FavoriteDao,
    private val programReminderDao: ProgramReminderDao,
    private val searchHistoryDao: SearchHistoryDao,
    private val syncManager: SyncManager
) {

    suspend fun runDailyMaintenance(now: Long = System.currentTimeMillis()): MaintenanceReport = withContext(Dispatchers.IO) {
        val oldProgramThreshold = now - programRetentionMillis()
        searchHistoryDao.pruneOlderThan(now - SEARCH_HISTORY_RETENTION_MILLIS)
        val deletedExpiredReminders = programReminderDao.deleteExpired(now - PROGRAM_REMINDER_RETENTION_MILLIS)
        val deletedPrograms = programDao.deleteOld(oldProgramThreshold)
        val deletedExternalProgrammes = epgProgrammeDao.deleteOld(oldProgramThreshold)
        val deletedOrphanEpisodes = episodeDao.deleteOrphans()
        val deletedStaleFavorites = favoriteDao.deleteMissingLiveFavorites() +
            favoriteDao.deleteMissingMovieFavorites() +
            favoriteDao.deleteMissingSeriesFavorites()

        val statsBeforeVacuum = collectStorageStats()
        val vacuumNeeded = shouldVacuum(statsBeforeVacuum)
        val vacuumRan = if (vacuumNeeded) {
            syncManager.runWhenNoSyncActive {
                runVacuum()
            }.also { ran ->
                if (!ran) {
                    Log.i(TAG, "VACUUM skipped: a provider sync is currently active")
                }
            }
        } else {
            false
        }
        val statsAfterVacuum = collectStorageStats()
        val tableStats = collectTableStats()

        MaintenanceReport(
            deletedExpiredReminders = deletedExpiredReminders,
            deletedPrograms = deletedPrograms,
            deletedExternalProgrammes = deletedExternalProgrammes,
            deletedOrphanEpisodes = deletedOrphanEpisodes,
            deletedStaleFavorites = deletedStaleFavorites,
            vacuumRan = vacuumRan,
            statsBeforeVacuum = statsBeforeVacuum,
            statsAfterVacuum = statsAfterVacuum,
            tableStats = tableStats
        )
    }

    @WorkerThread
    private fun collectStorageStats(): DatabaseStorageStats {
        val sqliteDb = database.openHelper.writableDatabase
        val pageSize = sqliteDb.longPragma("page_size")
        val pageCount = sqliteDb.longPragma("page_count")
        val freelistCount = sqliteDb.longPragma("freelist_count")
        val databasePath = sqliteDb.path ?: return DatabaseStorageStats(
            pageSizeBytes = pageSize,
            pageCount = pageCount,
            freelistCount = freelistCount,
            mainDbBytes = 0L,
            walBytes = 0L
        )
        val databaseFile = File(databasePath)
        val walFile = File("$databasePath-wal")
        return DatabaseStorageStats(
            pageSizeBytes = pageSize,
            pageCount = pageCount,
            freelistCount = freelistCount,
            mainDbBytes = databaseFile.length(),
            walBytes = walFile.takeIf(File::exists)?.length() ?: 0L
        )
    }

    /** Returns true if VACUUM ran, false if skipped due to busy readers. */
    private suspend fun runVacuum(): Boolean = withContext(Dispatchers.IO) {
        val sqliteDb = database.openHelper.writableDatabase

        // PASSIVE is a non-blocking inspection step: it checkpoints what it can without forcing
        // writers/readers to wait, and lets us see if pages remain in WAL.
        val remainingWalPages = sqliteDb.walCheckpointRemainingPages("PASSIVE")

        if (remainingWalPages > 0) {
            Log.w(TAG, "wal_checkpoint(PASSIVE) still has $remainingWalPages WAL pages; VACUUM skipped this cycle")
            return@withContext false
        }

        // Clear WAL before VACUUM once we know a non-blocking inspection found no remaining pages.
        val fullCheckpointRemainingPages = sqliteDb.walCheckpointRemainingPages("FULL")
        if (fullCheckpointRemainingPages > 0) {
            Log.w(TAG, "wal_checkpoint(FULL) still has $fullCheckpointRemainingPages WAL pages; VACUUM skipped this cycle")
            return@withContext false
        }

        val durationMs = kotlin.system.measureTimeMillis {
            sqliteDb.execSQL("VACUUM")
        }
        Log.i(TAG, "Database VACUUM completed in ${durationMs}ms")
        true
    }

    private fun shouldVacuum(stats: DatabaseStorageStats): Boolean {
        if (stats.reclaimableBytes < MIN_RECLAIMABLE_BYTES) return false
        if (stats.mainDbBytes < MIN_DATABASE_BYTES) return false
        return stats.reclaimableBytes * 100 >= stats.mainDbBytes * RECLAIMABLE_PERCENT_THRESHOLD
    }

    private suspend fun programRetentionMillis(): Long {
        val catchUpDays = channelDao.getMaxCatchUpDaysAcrossAllProviders().coerceAtLeast(0)
        return maxOf(PROGRAM_MIN_RETENTION_MILLIS, catchUpDays * MILLIS_PER_DAY)
    }

    @WorkerThread
    private fun androidx.sqlite.db.SupportSQLiteDatabase.longPragma(pragma: String): Long {
        query("PRAGMA $pragma").use { cursor ->
            return if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }
    }

    @WorkerThread
    private fun androidx.sqlite.db.SupportSQLiteDatabase.walCheckpointRemainingPages(mode: String): Int {
        query("PRAGMA wal_checkpoint($mode)").use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(1) else 0
        }
    }

    @WorkerThread
    private fun collectTableStats(): TableRowStats {
        val sqliteDb = database.openHelper.writableDatabase
        return TableRowStats(
            channels = sqliteDb.tableCount("channels"),
            movies = sqliteDb.tableCount("movies"),
            series = sqliteDb.tableCount("series"),
            episodes = sqliteDb.tableCount("episodes"),
            programs = sqliteDb.tableCount("programs"),
            epgProgrammes = sqliteDb.tableCount("epg_programmes"),
            playbackHistory = sqliteDb.tableCount("playback_history"),
            favorites = sqliteDb.tableCount("favorites"),
            programReminders = sqliteDb.tableCount("program_reminders")
        )
    }

    @WorkerThread
    private fun androidx.sqlite.db.SupportSQLiteDatabase.tableCount(tableName: String): Long {
        query("SELECT COUNT(*) FROM $tableName").use { cursor ->
            return if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }
    }

    data class MaintenanceReport(
        val deletedExpiredReminders: Int = 0,
        val deletedPrograms: Int,
        val deletedExternalProgrammes: Int = 0,
        val deletedOrphanEpisodes: Int,
        val deletedStaleFavorites: Int,
        val vacuumRan: Boolean,
        val statsBeforeVacuum: DatabaseStorageStats,
        val statsAfterVacuum: DatabaseStorageStats,
        val tableStats: TableRowStats
    )

    data class DatabaseStorageStats(
        val pageSizeBytes: Long,
        val pageCount: Long,
        val freelistCount: Long,
        val mainDbBytes: Long,
        val walBytes: Long
    ) {
        val reclaimableBytes: Long get() = pageSizeBytes * freelistCount
        val logicalDbBytes: Long get() = pageSizeBytes * pageCount
    }

    data class TableRowStats(
        val channels: Long,
        val movies: Long,
        val series: Long,
        val episodes: Long,
        val programs: Long,
        val epgProgrammes: Long,
        val playbackHistory: Long,
        val favorites: Long,
        val programReminders: Long
    )

    companion object {
        private const val TAG = "DbMaintenance"
        private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000L
        private const val PROGRAM_MIN_RETENTION_MILLIS = 12L * 60 * 60 * 1000L
        private const val PROGRAM_REMINDER_RETENTION_MILLIS = MILLIS_PER_DAY
        private const val SEARCH_HISTORY_RETENTION_MILLIS = 90L * MILLIS_PER_DAY
        private const val MIN_RECLAIMABLE_BYTES = 32L * 1024 * 1024
        private const val MIN_DATABASE_BYTES = 128L * 1024 * 1024
        private const val RECLAIMABLE_PERCENT_THRESHOLD = 20L
    }
}
