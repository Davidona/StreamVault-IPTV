package com.streamvault.data.sync

import android.content.Context
import android.database.sqlite.SQLiteException
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import com.streamvault.data.local.entity.ProviderWorkflowPhase
import com.streamvault.data.local.entity.ProviderWorkflowReason
import com.streamvault.data.local.dao.ProviderDao
import com.streamvault.data.local.dao.XtreamIndexJobDao
import com.streamvault.domain.model.ProviderType
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit

class BackgroundEpgSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface BackgroundEpgSyncWorkerEntryPoint {
        fun syncCommands(): ProviderSyncCommands
        fun providerWorkflowRunner(): ProviderWorkflowRunner
        fun providerDao(): ProviderDao
        fun xtreamIndexJobDao(): XtreamIndexJobDao
    }

    override suspend fun doWork(): Result {
        val providerId = inputData.getLong(KEY_PROVIDER_ID, INVALID_PROVIDER_ID)
        if (providerId == INVALID_PROVIDER_ID) {
            return Result.failure()
        }

        // Defer the run when the device is currently under memory pressure. The streamed
        // Stalker EPG path is heap-frugal but the surrounding sync work (channel inserts,
        // EPG resolution) can still allocate; retrying later avoids piling onto a stressed
        // system. WorkManager will re-enqueue with backoff.
        if (applicationContext.isCurrentlyLowOnMemoryForSync()) {
            Log.w(TAG, "Deferring background EPG sync for provider $providerId: device low on memory")
            return Result.retry()
        }

        val force = inputData.getBoolean(KEY_FORCE_REFRESH, false)

        return try {
            val entryPoint = EntryPointAccessors.fromApplication(
                applicationContext,
                BackgroundEpgSyncWorkerEntryPoint::class.java
            )
            val provider = entryPoint.providerDao().getById(providerId)
            if (!force && provider?.type == ProviderType.XTREAM_CODES &&
                entryPoint.xtreamIndexJobDao().hasPendingCatalogWork(providerId)
            ) {
                Log.i(TAG, "Deferring guide work for provider $providerId until queued catalog indexing completes")
                return Result.retry()
            }
            when (
                executeBackgroundEpg(provider?.type, workflow = { sync ->
                    entryPoint.providerWorkflowRunner().execute(
                        providerId = providerId,
                        phase = ProviderWorkflowPhase.EPG,
                        reason = ProviderWorkflowReason.PERIODIC,
                        force = force,
                        block = sync
                    )
                }) {
                    val epgResult = entryPoint.syncCommands().syncEpg(providerId, force = force)
                    when (val result = epgResult) {
                        is com.streamvault.domain.model.Result.Success -> {
                            // A successful EPG sync may still have transient partial failures.
                            val syncState = entryPoint.syncCommands().currentSyncState(providerId)
                            if (syncState is com.streamvault.domain.model.SyncState.Partial &&
                                syncState.hasRetryableEpgFailure
                            ) {
                                Log.i(TAG, "Scheduling retry for provider $providerId: EPG completed with retryable failure")
                                ProviderWorkflowOutcome.Failure(
                                    code = "EPG_PARTIAL_RETRYABLE",
                                    message = "EPG completed with a retryable partial failure.",
                                    retryable = true
                                )
                            } else {
                                ProviderWorkflowOutcome.Success(
                                    partial = syncState is com.streamvault.domain.model.SyncState.Partial
                                )
                            }
                        }
                        is com.streamvault.domain.model.Result.Error -> {
                            if (result.message.contains("not found", ignoreCase = true)) {
                                ProviderWorkflowOutcome.Success()
                            } else {
                                ProviderWorkflowOutcome.Failure(
                                    code = "EPG_SYNC",
                                    message = result.message,
                                    cause = result.exception
                                )
                            }
                        }
                        com.streamvault.domain.model.Result.Loading ->
                            ProviderWorkflowOutcome.Failure(
                                code = "EPG_LOADING",
                                message = "EPG operation did not reach a terminal state.",
                                retryable = true
                            )
                    }
                }
            ) {
                ProviderWorkflowDisposition.SUCCEEDED,
                ProviderWorkflowDisposition.SUPERSEDED -> Result.success()
                ProviderWorkflowDisposition.RETRY,
                ProviderWorkflowDisposition.BUSY -> Result.retry()
                ProviderWorkflowDisposition.FAILED -> Result.failure()
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.e(TAG, "Background EPG work failed for provider $providerId", e)
            if (shouldRetry(e)) Result.retry() else Result.failure()
        }
    }

    private fun shouldRetry(error: Throwable?): Boolean {
        return ProviderWorkFailureClassifier.isRetryable(error)
    }

    companion object {
        private const val TAG = "BackgroundEpgWorker"
        private const val KEY_PROVIDER_ID = "provider_id"
        private const val KEY_FORCE_REFRESH = "force_refresh"
        private const val INVALID_PROVIDER_ID = -1L
        /**
         * Default delay before the first background EPG sync runs after enqueue. This
         * replaces the in-process [kotlinx.coroutines.delay] previously used by the
         * provider repository, which kept a coroutine alive for the duration of the wait.
         */
        private const val DEFAULT_INITIAL_DELAY_SECONDS = 30L

        fun enqueue(
            context: Context,
            providerId: Long,
            force: Boolean = false,
            initialDelaySeconds: Long = DEFAULT_INITIAL_DELAY_SECONDS
        ) {
            if (providerId <= 0L) return
            val request = OneTimeWorkRequestBuilder<BackgroundEpgSyncWorker>()
                .setInputData(
                    Data.Builder()
                        .putLong(KEY_PROVIDER_ID, providerId)
                        .putBoolean(KEY_FORCE_REFRESH, force)
                        .build()
                )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .setInitialDelay(initialDelaySeconds.coerceAtLeast(0L), TimeUnit.SECONDS)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                providerEpgWorkUniqueName(providerId),
                if (force) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                request
            )
        }

        fun cancel(context: Context, providerId: Long) {
            if (providerId <= 0L) return
            runCatching {
                WorkManager.getInstance(context).cancelUniqueWork(providerEpgWorkUniqueName(providerId))
            }.onFailure { error ->
                Log.w(TAG, "Skipping background EPG cancellation for provider $providerId", error)
            }
        }

    }
}
