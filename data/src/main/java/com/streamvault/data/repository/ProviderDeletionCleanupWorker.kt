package com.streamvault.data.repository

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.streamvault.data.local.dao.ProviderDeletionCleanupDao
import com.streamvault.data.manager.recording.RecordingAlarmScheduler
import com.streamvault.data.manager.reminder.ProgramReminderAlarmScheduler
import com.streamvault.data.sync.ProviderSyncLifecycle
import com.streamvault.domain.repository.PlaybackHistoryRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import dagger.hilt.android.qualifiers.ApplicationContext

class ProviderDeletionCleanupWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    @EntryPoint @InstallIn(SingletonComponent::class)
    interface Entry {
        fun providerDeletionCleanupDao(): ProviderDeletionCleanupDao
        fun recordingAlarmScheduler(): RecordingAlarmScheduler
        fun programReminderAlarmScheduler(): ProgramReminderAlarmScheduler
        fun syncLifecycle(): ProviderSyncLifecycle
        fun playbackHistoryRepository(): PlaybackHistoryRepository
    }

    override suspend fun doWork(): Result {
        val entry = EntryPointAccessors.fromApplication(applicationContext, Entry::class.java)
        return when (
            drainProviderDeletionCleanup(
                dao = entry.providerDeletionCleanupDao(),
                cancelRecordingAlarm = entry.recordingAlarmScheduler()::cancel,
                cancelReminderAlarm = entry.programReminderAlarmScheduler()::cancel,
                cleanupSyncRuntime = entry.syncLifecycle()::onProviderDeleted,
                cleanupLiveUsage = { providerId ->
                    when (val result = entry.playbackHistoryRepository().clearHistoryForProvider(providerId)) {
                        is com.streamvault.domain.model.Result.Success -> Unit
                        is com.streamvault.domain.model.Result.Error -> throw result.exception ?: IllegalStateException(result.message)
                        com.streamvault.domain.model.Result.Loading -> throw IllegalStateException()
                    }
                }
            )
        ) {
            ProviderDeletionDrainOutcome.COMPLETE -> Result.success()
            ProviderDeletionDrainOutcome.RETRY -> Result.retry()
        }
    }

    companion object {
        const val RECORDING_ALARM = "RECORDING_ALARM"
        const val REMINDER_ALARM = "REMINDER_ALARM"
        const val SYNC_RUNTIME = "SYNC_RUNTIME"
        const val LIVE_USAGE = "LIVE_USAGE"
        private const val WORK_NAME = "ProviderDeletionCleanup"
        fun enqueue(context: Context) = WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME,
            PROVIDER_DELETION_EXISTING_WORK_POLICY,
            OneTimeWorkRequestBuilder<ProviderDeletionCleanupWorker>().build()
        )
    }
}

class ProviderDeletionCleanupEnqueuer @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    fun enqueue() {
        ProviderDeletionCleanupWorker.enqueue(context)
    }
}

internal enum class ProviderDeletionDrainOutcome {
    COMPLETE,
    RETRY
}

internal suspend fun drainProviderDeletionCleanup(
    dao: ProviderDeletionCleanupDao,
    cancelRecordingAlarm: (String) -> Unit,
    cancelReminderAlarm: (Long) -> Unit,
    cleanupSyncRuntime: suspend (Long) -> Unit,
    cleanupLiveUsage: suspend (Long) -> Unit = { throw IllegalStateException() }
): ProviderDeletionDrainOutcome {
    while (true) {
        val items = try {
            dao.getBatch(CLEANUP_BATCH_SIZE)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return ProviderDeletionDrainOutcome.RETRY
        }
        if (items.isEmpty()) return ProviderDeletionDrainOutcome.COMPLETE

        var failed = false
        items.forEach { item ->
            try {
                when (item.action) {
                    ProviderDeletionCleanupWorker.RECORDING_ALARM -> cancelRecordingAlarm(item.targetId)
                    ProviderDeletionCleanupWorker.REMINDER_ALARM -> cancelReminderAlarm(item.targetId.toLong())
                    ProviderDeletionCleanupWorker.SYNC_RUNTIME -> cleanupSyncRuntime(item.providerId)
                    ProviderDeletionCleanupWorker.LIVE_USAGE -> cleanupLiveUsage(item.providerId)
                    else -> error("Unknown provider cleanup action '${item.action}'")
                }
                dao.delete(item.id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                failed = true
                try {
                    dao.recordFailure(item.id, error.message ?: error.javaClass.simpleName)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    // The tombstone remains durable. Returning retry is more important than
                    // successfully recording diagnostics for this attempt.
                }
            }
        }
        if (failed) return ProviderDeletionDrainOutcome.RETRY
    }
}

private const val CLEANUP_BATCH_SIZE = 100
internal val PROVIDER_DELETION_EXISTING_WORK_POLICY = ExistingWorkPolicy.APPEND_OR_REPLACE
