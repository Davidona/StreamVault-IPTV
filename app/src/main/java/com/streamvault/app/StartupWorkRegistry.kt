package com.streamvault.app

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.streamvault.app.update.AppUpdateCheckWorker
import com.streamvault.data.manager.recording.RecordingReconcileWorker
import com.streamvault.data.repository.ProviderDeletionCleanupWorker
import com.streamvault.data.sync.ProviderSyncWorker
import com.streamvault.data.sync.SyncWorker
import com.streamvault.data.sync.XtreamIndexWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * The single application-start entry point for durable background-work registration.
 *
 * Provider workers still retain their own request builders while ARCH-008 is migrated, but
 * process startup no longer knows or independently selects their identities and policies.
 */
class StartupWorkRegistry @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val automaticSyncIdleObserver: com.streamvault.data.sync.AutomaticSyncIdleObserver
) {
    fun register() {
        AppUpdateCheckWorker.enqueue(context)

        val dataMaintenance = PeriodicWorkRequestBuilder<SyncWorker>(24, TimeUnit.HOURS)
            .setConstraints(dataMaintenanceConstraints())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            DATA_MAINTENANCE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            dataMaintenance
        )

        automaticSyncIdleObserver.start()
        ProviderSyncWorker.enqueuePeriodic(context)
        ProviderSyncWorker.enqueueLaunchStaleCheck(context)
        // The shared provider planner owns periodic indexing as well as catalog/guide checks.
        XtreamIndexWorker.cancelLegacyChecks(context)
        RecordingReconcileWorker.enqueuePeriodic(context)
        RecordingReconcileWorker.enqueueOneShot(context)
        ProviderDeletionCleanupWorker.enqueue(context)
    }

    private companion object {
        const val DATA_MAINTENANCE_WORK_NAME = "DataMaintenanceWorker"
    }
}
