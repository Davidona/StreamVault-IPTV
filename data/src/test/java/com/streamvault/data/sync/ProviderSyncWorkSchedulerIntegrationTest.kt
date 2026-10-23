package com.streamvault.data.sync

import androidx.work.Configuration
import androidx.work.WorkManager
import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.sync.AutomaticSyncSettings
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ProviderSyncWorkSchedulerIntegrationTest {
    @Test fun `manual Xtream sections do not wait behind delayed provider recovery`() {
        val context = RuntimeEnvironment.getApplication()
        val manager = workManager()
        val providerId = 987958L
        val recovery = androidx.work.OneTimeWorkRequestBuilder<ProviderSyncWorker>()
            .setInitialDelay(3600L, java.util.concurrent.TimeUnit.SECONDS).build()
        manager.enqueueUniqueWork(providerWorkUniqueName(providerId), androidx.work.ExistingWorkPolicy.KEEP,
            recovery).result.get()
        XtreamIndexWorker.enqueue(context, providerId, "MOVIE", automatic = false, initialDelaySeconds = 3600L)
        XtreamIndexWorker.enqueue(context, providerId, "SERIES", automatic = false, initialDelaySeconds = 3600L)

        val setup = manager.getWorkInfosForUniqueWork(providerWorkUniqueName(providerId)).get()
        val indexes = manager.getWorkInfosForUniqueWork(xtreamIndexWorkUniqueName(providerId)).get()
        assertThat(setup.map { it.id }).containsExactly(recovery.id)
        assertThat(indexes).hasSize(2)
        assertThat(indexes.count { it.state == androidx.work.WorkInfo.State.ENQUEUED }).isEqualTo(1)
        assertThat(indexes.count { it.state == androidx.work.WorkInfo.State.BLOCKED }).isEqualTo(1)
    }

    private fun workManager(): WorkManager {
        val context = RuntimeEnvironment.getApplication()
        return runCatching { WorkManager.getInstance(context) }.getOrElse {
            WorkManager.initialize(context, Configuration.Builder().build())
            WorkManager.getInstance(context)
        }
    }

    @Test fun `manual work bypasses automatic constraints and survives subsequent policy changes`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val manager = workManager()
        val scheduler = ProviderSyncWorkScheduler(context)
        withContext(AutomaticSyncExecution.policy.asContextElement(AutomaticSyncSettings(chargingOnly = true))) {
            scheduler.scheduleStalkerIndex(987956L, automatic = true, initialDelaySeconds = 3600L)
        }
        scheduler.scheduleStalkerIndex(987956L, automatic = false, initialDelaySeconds = 3600L)
        val manual = manager.getWorkInfosForUniqueWork(stalkerIndexWorkUniqueName(987956L)).get().single { !it.state.isFinished }
        assertThat(manual.constraints.requiresCharging()).isFalse()
        assertThat(manager.getWorkInfosForUniqueWork(providerAutomaticIndexWorkUniqueName(987956L)).get().all { it.state.isFinished }).isTrue()
        withContext(AutomaticSyncExecution.policy.asContextElement(AutomaticSyncSettings(chargingOnly = true))) {
            scheduler.scheduleStalkerIndex(987956L, automatic = true, initialDelaySeconds = 3600L)
        }
        withContext(AutomaticSyncExecution.policy.asContextElement(AutomaticSyncSettings(chargingOnly = false))) {
            scheduler.scheduleStalkerIndex(987956L, automatic = true, initialDelaySeconds = 3600L)
        }
        val retained = manager.getWorkInfosForUniqueWork(stalkerIndexWorkUniqueName(987956L)).get().single { !it.state.isFinished }
        assertThat(retained.id).isEqualTo(manual.id)
    }

    @Test fun `manual guide request supersedes an unforced guide request`() {
        val context = RuntimeEnvironment.getApplication()
        val manager = workManager()
        BackgroundEpgSyncWorker.enqueue(context, 987957L, force = false, initialDelaySeconds = 3600L)
        val original = manager.getWorkInfosForUniqueWork(providerEpgWorkUniqueName(987957L)).get().single { !it.state.isFinished }
        BackgroundEpgSyncWorker.enqueue(context, 987957L, force = true, initialDelaySeconds = 3600L)
        val replacement = manager.getWorkInfosForUniqueWork(providerEpgWorkUniqueName(987957L)).get().single { !it.state.isFinished }
        assertThat(replacement.id).isNotEqualTo(original.id)
    }

    @Test fun `a policy edit retains a successor check when a foreground check is already pending`() {
        val context = RuntimeEnvironment.getApplication()
        val manager = workManager()
        val old = androidx.work.OneTimeWorkRequestBuilder<ProviderSyncWorker>()
            .setInitialDelay(3600L, java.util.concurrent.TimeUnit.SECONDS).build()
        manager.enqueueUniqueWork("provider-sync-foreground-check", androidx.work.ExistingWorkPolicy.KEEP, old).result.get()
        ProviderSyncWorker.enqueueForegroundCheck(context)
        val checks = manager.getWorkInfosForUniqueWork("provider-sync-foreground-check").get()
        assertThat(checks.count { !it.state.isFinished }).isEqualTo(2)
    }

    @Test fun `relaxing charging replaces the pending automatic Stalker request`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val manager = runCatching { WorkManager.getInstance(context) }.getOrElse {
            WorkManager.initialize(context, Configuration.Builder().build())
            WorkManager.getInstance(context)
        }
        val scheduler = ProviderSyncWorkScheduler(context)
        withContext(AutomaticSyncExecution.policy.asContextElement(AutomaticSyncSettings(chargingOnly = true))) {
            scheduler.scheduleStalkerIndex(987955L, automatic = true, initialDelaySeconds = 3600L)
        }
        withContext(AutomaticSyncExecution.policy.asContextElement(AutomaticSyncSettings(chargingOnly = false))) {
            scheduler.scheduleStalkerIndex(987955L, automatic = true, initialDelaySeconds = 3600L)
        }
        val jobs = manager.getWorkInfosForUniqueWork(providerAutomaticIndexWorkUniqueName(987955L)).get()
        val pending = jobs.single { !it.state.isFinished }
        assertThat(pending.constraints.requiresCharging()).isFalse()
    }
}
