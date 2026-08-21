package com.streamvault.app.tvinput

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/** Coalesces slow Android TV provider publication outside foreground provider sync UX. */
class TvInputCatalogRefreshWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Entry {
        fun tvInputChannelSyncManager(): TvInputChannelSyncManager
    }

    override suspend fun doWork(): Result {
        val manager = EntryPointAccessors.fromApplication(applicationContext, Entry::class.java)
            .tvInputChannelSyncManager()
        return manager.refreshTvInputCatalogResult().fold(
            onSuccess = { Result.success() },
            onFailure = { Result.retry() }
        )
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "tv-input-catalog-refresh"

        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<TvInputCatalogRefreshWorker>().build()
            )
        }
    }
}
