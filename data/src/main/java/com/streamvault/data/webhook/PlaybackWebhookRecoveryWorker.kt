package com.streamvault.data.webhook

import android.content.Context
import androidx.work.*
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit

class PlaybackWebhookRecoveryWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies { fun service(): PlaybackWebhookService }

    override suspend fun doWork(): Result {
        val service = EntryPointAccessors.fromApplication(applicationContext, Dependencies::class.java).service()
        return if (service.awaitRecovery()) Result.success() else Result.retry()
    }

    companion object {
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork("playback-webhook-history",
                ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<PlaybackWebhookRecoveryWorker>()
                    .setInitialDelay(15, TimeUnit.SECONDS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
        }
    }
}
