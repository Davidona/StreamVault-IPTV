package com.streamvault.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import com.streamvault.data.sync.BackgroundEpgSyncWorker
import com.streamvault.data.sync.ProviderSyncWorker
import com.streamvault.data.sync.StalkerIndexWorker
import com.streamvault.data.sync.providerWorkUniqueName
import com.streamvault.data.sync.providerEpgWorkUniqueName
import com.streamvault.data.sync.stalkerIndexWorkUniqueName
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Exercises the actual worker enqueue APIs against WorkManager's unique-work database. */
@RunWith(AndroidJUnit4::class)
class ProviderWorkManagerSerializationTest {
    private lateinit var context: Context
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                .setExecutor(Executors.newSingleThreadExecutor())
                .build()
        )
        workManager = WorkManager.getInstance(context)
        workManager.cancelAllWork().result.get(10, TimeUnit.SECONDS)
    }

    @Test
    fun catalogContinuationIsNotDroppedBehindProviderSetupAndGuide() {
        val providerId = 71L
        ProviderSyncWorker.enqueueProvider(context, providerId)
        StalkerIndexWorker.enqueue(context, providerId, initialDelaySeconds = ONE_HOUR_SECONDS)
        BackgroundEpgSyncWorker.enqueue(context, providerId, initialDelaySeconds = ONE_HOUR_SECONDS)
        // Duplicate startup recovery must coalesce rather than duplicate the index.
        StalkerIndexWorker.enqueue(context, providerId, initialDelaySeconds = ONE_HOUR_SECONDS)

        val setup = workManager.getWorkInfosForUniqueWork(providerWorkUniqueName(providerId))
            .get(10, TimeUnit.SECONDS)
        val catalog = workManager.getWorkInfosForUniqueWork(stalkerIndexWorkUniqueName(providerId))
            .get(10, TimeUnit.SECONDS)
        val guide = workManager.getWorkInfosForUniqueWork(providerEpgWorkUniqueName(providerId))
            .get(10, TimeUnit.SECONDS)

        assertThat(setup).hasSize(1)
        assertThat(catalog).hasSize(1)
        assertThat(catalog.single().state).isEqualTo(WorkInfo.State.ENQUEUED)
        assertThat(guide).hasSize(1)
        assertThat(guide.single().state).isEqualTo(WorkInfo.State.ENQUEUED)

        // Guide cancellation cannot poison the catalog's prerequisite chain.
        workManager.cancelUniqueWork(providerEpgWorkUniqueName(providerId)).result.get(10, TimeUnit.SECONDS)
        assertThat(workManager.getWorkInfoById(catalog.single().id).get(10, TimeUnit.SECONDS)!!.state)
            .isEqualTo(WorkInfo.State.ENQUEUED)

        // Canceling catalog work must not cancel provider setup.
        StalkerIndexWorker.cancel(context, providerId)
        workManager.getWorkInfosForUniqueWork(stalkerIndexWorkUniqueName(providerId)).get(10, TimeUnit.SECONDS)
        assertThat(workManager.getWorkInfoById(setup.single().id).get(10, TimeUnit.SECONDS)!!.state)
            .isNotEqualTo(WorkInfo.State.CANCELLED)
    }

    private companion object {
        const val ONE_HOUR_SECONDS = 3_600L
    }

    @Test
    fun failedGuideDoesNotFailOrBlockCatalogContinuation() {
        val providerId = 72L
        StalkerIndexWorker.enqueue(context, providerId, initialDelaySeconds = ONE_HOUR_SECONDS)
        val failedGuide = OneTimeWorkRequestBuilder<FailingGuideWorker>().build()
        workManager.enqueueUniqueWork(providerEpgWorkUniqueName(providerId), ExistingWorkPolicy.REPLACE, failedGuide)
            .result.get(10, TimeUnit.SECONDS)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (workManager.getWorkInfoById(failedGuide.id).get()!!.state != WorkInfo.State.FAILED &&
            System.nanoTime() < deadline) Thread.sleep(20)
        assertThat(workManager.getWorkInfoById(failedGuide.id).get()!!.state).isEqualTo(WorkInfo.State.FAILED)
        assertThat(workManager.getWorkInfosForUniqueWork(stalkerIndexWorkUniqueName(providerId)).get().single().state)
            .isEqualTo(WorkInfo.State.ENQUEUED)
    }
}

class FailingGuideWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result = Result.failure()
}
