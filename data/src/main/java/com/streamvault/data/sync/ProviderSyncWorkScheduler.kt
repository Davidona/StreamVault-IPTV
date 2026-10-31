package com.streamvault.data.sync

import android.content.Context
import androidx.core.content.edit
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

    fun scheduleBackgroundEpg(providerId: Long, force: Boolean = false) {
        BackgroundEpgSyncWorker.enqueue(applicationContext, providerId, force = force,
            initialDelaySeconds = if (force) 0L else 30L)
    }

    fun scheduleManualEpg(providerId: Long) {
        BackgroundEpgSyncWorker.enqueue(applicationContext, providerId, force = true,
            initialDelaySeconds = 0L, userInitiated = true)
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

    fun scheduleStalkerBootstrapResume(providerId: Long) {
        ProviderSyncWorker.enqueueStalkerBootstrapResume(applicationContext, providerId)
    }

    fun scheduleXtreamIndex(
        providerId: Long,
        section: ContentType? = null,
        force: Boolean = false,
        automatic: Boolean = (AutomaticSyncExecution.automatic.get() == true)
    ) {
        val origins = applicationContext.getSharedPreferences("automatic_sync_work", Context.MODE_PRIVATE)
        val manager = androidx.work.WorkManager.getInstance(applicationContext)
        val policy = AutomaticSyncExecution.policy.get() ?: com.streamvault.domain.sync.AutomaticSyncSettings()
        val wasAutomatic = origins.getBoolean("xtream_$providerId", false)
        // Migrate an old mixed-origin chain once. New automatic chains have their own
        // identity, so policy edits cannot cancel a manual sibling or block it.
        if (!origins.getBoolean("xtream_split_queue_$providerId", false) && wasAutomatic) {
            manager.cancelUniqueWork(providerWorkUniqueName(providerId))
        }
        if (!automatic) manager.cancelUniqueWork(providerAutomaticIndexWorkUniqueName(providerId))
        val replaceAutomatic = automatic && wasAutomatic && origins.getString("xtream_policy_$providerId", null) != policy.toString()
        origins.edit {
            putBoolean("xtream_split_queue_$providerId", true)
            putBoolean("xtream_$providerId", automatic)
            putString("xtream_policy_$providerId", if (automatic) policy.toString() else null)
        }
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
        val manager = androidx.work.WorkManager.getInstance(applicationContext)
        val policy = AutomaticSyncExecution.policy.get() ?: com.streamvault.domain.sync.AutomaticSyncSettings()
        val wasAutomatic = origins.getBoolean("stalker_$providerId", false)
        if (!automatic) manager.cancelUniqueWork(providerAutomaticIndexWorkUniqueName(providerId))
        val replaceAutomatic = automatic && wasAutomatic && origins.getString("stalker_policy_$providerId", null) != policy.toString()
        origins.edit {
            putBoolean("stalker_split_queue_$providerId", true)
            putBoolean("stalker_$providerId", automatic)
            putString("stalker_policy_$providerId", if (automatic) policy.toString() else null)
        }
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
