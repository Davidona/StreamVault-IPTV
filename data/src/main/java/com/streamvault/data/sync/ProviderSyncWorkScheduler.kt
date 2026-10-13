package com.streamvault.data.sync

import android.content.Context
import com.streamvault.domain.model.ContentType
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns durable provider-sync work scheduling.
 *
 * Catalog plans may decide what work is needed, but they do not construct
 * WorkManager requests themselves. Keeping this policy here also makes sync
 * orchestration testable without coupling every provider plan to Android.
 */
@Singleton
class ProviderSyncWorkScheduler @Inject constructor(
    @param:ApplicationContext private val applicationContext: Context
) {
    fun cancelBackgroundEpg(providerId: Long) {
        BackgroundEpgSyncWorker.cancel(applicationContext, providerId)
    }

    fun isAutomaticStalkerIndex(providerId: Long): Boolean = applicationContext
        .getSharedPreferences("automatic_sync_work", Context.MODE_PRIVATE).getBoolean("stalker_$providerId", false)

    fun cancelStalkerIndex(providerId: Long) {
        StalkerIndexWorker.cancel(applicationContext, providerId)
    }

    fun scheduleBackgroundEpg(providerId: Long) {
        BackgroundEpgSyncWorker.enqueue(applicationContext, providerId)
    }

    fun scheduleProviderResume(providerId: Long, configurationGeneration: Long? = null) {
        if (configurationGeneration != null) {
            ProviderSyncWorker.enqueueProviderConfigRevision(
                context = applicationContext,
                providerId = providerId,
                revision = configurationGeneration
            )
        } else {
            ProviderSyncWorker.enqueueProvider(applicationContext, providerId)
        }
    }

    fun scheduleXtreamIndex(
        providerId: Long,
        section: ContentType? = null,
        force: Boolean = false,
        automatic: Boolean = (AutomaticSyncExecution.automatic.get() == true)
    ) {
        val origins = applicationContext.getSharedPreferences("automatic_sync_work", Context.MODE_PRIVATE)
        val replaceAutomatic = !automatic && origins.getBoolean("xtream_$providerId", false)
        origins.edit().putBoolean("xtream_$providerId", automatic).apply()
        XtreamIndexWorker.enqueue(
            context = applicationContext,
            providerId = providerId,
            section = section?.name,
            force = force,
            automatic = automatic,
            replaceAutomatic = replaceAutomatic
        )
    }

    fun scheduleStalkerIndex(
        providerId: Long,
        force: Boolean = false,
        automatic: Boolean = (AutomaticSyncExecution.automatic.get() == true),
        initialDelaySeconds: Long = 0L,
        appendSuccessor: Boolean = false
    ) {
        val origins = applicationContext.getSharedPreferences("automatic_sync_work", Context.MODE_PRIVATE)
        val replaceAutomatic = !automatic && origins.getBoolean("stalker_$providerId", false)
        origins.edit().putBoolean("stalker_$providerId", automatic).apply()
        // Stalker section requests are intentionally coalesced into the durable
        // provider job. This preserves the existing worker contract: the worker
        // selects the next pending section from its persisted job state.
        StalkerIndexWorker.enqueue(
            context = applicationContext,
            providerId = providerId,
            section = null,
            force = force,
            automatic = automatic,
            replaceAutomatic = replaceAutomatic,
            initialDelaySeconds = initialDelaySeconds,
            appendSuccessor = appendSuccessor
        )
    }

}
