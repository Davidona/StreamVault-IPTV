package com.streamvault.data.sync

import android.content.Context
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext
import android.database.sqlite.SQLiteException
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import com.streamvault.data.local.dao.ProviderDao
import com.streamvault.data.local.entity.ProviderWorkflowPhase
import com.streamvault.data.local.entity.ProviderWorkflowReason
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.ProviderType
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.IOException
import java.util.concurrent.TimeUnit

class XtreamIndexWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface XtreamIndexWorkerEntryPoint {
        fun automaticSyncRunner(): AutomaticSyncRunner
        fun providerDao(): ProviderDao
        fun syncCommands(): ProviderSyncCommands
        fun providerWorkflowRunner(): ProviderWorkflowRunner
    }

    override suspend fun doWork(): Result {
        val automatic = inputData.getBoolean("automatic_refresh", false)
        val policy = if (automatic) EntryPointAccessors.fromApplication(applicationContext, XtreamIndexWorkerEntryPoint::class.java)
            .automaticSyncRunner().settings(inputData.getLong(KEY_PROVIDER_ID, INVALID_PROVIDER_ID)) else null
        return withContext(AutomaticSyncExecution.automatic.asContextElement(automatic) +
            AutomaticSyncExecution.policy.asContextElement(policy) +
            AutomaticSyncExecution.idleSatisfied.asContextElement(inputData.getBoolean("idle_constraint", false))) { runIndexWork() }
    }

    private suspend fun runIndexWork(): Result {
        if (applicationContext.isCurrentlyLowOnMemoryForSync()) {
            Log.w(TAG, "Deferring Xtream index work: device low on memory")
            return Result.retry()
        }

        val force = inputData.getBoolean(KEY_FORCE, false)
        val requestedProviderId = inputData.getLong(KEY_PROVIDER_ID, INVALID_PROVIDER_ID)
        val requestedSection = inputData.getString(KEY_SECTION)?.toContentTypeOrNull()
        val sliceBudget = XtreamIndexSliceBudget.forTier(
            CatalogSyncRuntimeProfile.from(applicationContext).tier
        )

        return try {
            val entryPoint = EntryPointAccessors.fromApplication(
                applicationContext,
                XtreamIndexWorkerEntryPoint::class.java
            )
            val providers = if (requestedProviderId > 0L) {
                entryPoint.providerDao().getById(requestedProviderId)?.let(::listOf).orEmpty()
            } else {
                entryPoint.providerDao().getAllSync()
                    .filter { provider -> provider.isActive && provider.type == ProviderType.XTREAM_CODES }
            }

            var sawRetryableFailure = false
            var sawPermanentFailure = false
            providers
                .filter { provider -> provider.type == ProviderType.XTREAM_CODES }
                .forEach { provider ->
                    if (inputData.getBoolean("automatic_refresh", false)) {
                        val waiting = entryPoint.automaticSyncRunner().waitingReason(provider.id)
                        if (waiting != null) {
                            entryPoint.automaticSyncRunner().reportIndexWaiting(provider.id, waiting)
                            if (waiting != com.streamvault.domain.sync.AutomaticSyncOutcome.DISABLED) {
                                val latest = entryPoint.automaticSyncRunner().settings(provider.id)
                                val delaySeconds = when (waiting) {
                                    com.streamvault.domain.sync.AutomaticSyncOutcome.WAITING_PLAYBACK -> 60L
                                    com.streamvault.domain.sync.AutomaticSyncOutcome.WAITING_WINDOW -> {
                                        val target = java.util.Calendar.getInstance().apply {
                                            set(java.util.Calendar.HOUR_OF_DAY, latest.windowStartHour)
                                            set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0)
                                            if (timeInMillis <= System.currentTimeMillis()) add(java.util.Calendar.DAY_OF_YEAR, 1)
                                        }
                                        ((target.timeInMillis - System.currentTimeMillis()) / 1000L).coerceAtLeast(1L)
                                    }
                                    else -> 0L
                                }
                                withContext(AutomaticSyncExecution.policy.asContextElement(latest)) {
                                    enqueue(applicationContext, provider.id, requestedSection?.name, force = force,
                                        automatic = true, initialDelaySeconds = delaySeconds)
                                }
                            }
                            return@forEach
                        }
                        if (!entryPoint.automaticSyncRunner().indexEnabled(provider.id, requestedSection)) return@forEach
                    }
                    val disposition = entryPoint.providerWorkflowRunner().execute(
                        providerId = provider.id,
                        phase = requestedSection.toWorkflowPhase(),
                        reason = ProviderWorkflowReason.PERIODIC,
                        force = force
                    ) {
                        when (val result = entryPoint.syncCommands().processQueuedXtreamIndexJobs(
                            providerId = provider.id,
                            section = requestedSection,
                            force = force,
                            maxCategoriesPerSection = sliceBudget.maxCategories,
                            maxRowsPerSection = sliceBudget.maxRows,
                            maxDurationMillis = sliceBudget.maxDurationMillis
                        )) {
                            is com.streamvault.domain.model.Result.Error -> {
                                Log.w(TAG, "Xtream index worker failed for provider ${provider.id}: ${result.message}")
                                ProviderWorkflowOutcome.Failure(
                                    code = "XTREAM_INDEX",
                                    message = result.message,
                                    cause = result.exception
                                )
                            }
                            is com.streamvault.domain.model.Result.Success ->
                                ProviderWorkflowOutcome.Success()
                            com.streamvault.domain.model.Result.Loading ->
                                ProviderWorkflowOutcome.Failure(
                                    code = "XTREAM_INDEX_LOADING",
                                    message = "Index operation did not reach a terminal state.",
                                    retryable = true
                                )
                        }
                    }
                    if (inputData.getBoolean("automatic_refresh", false) && disposition != ProviderWorkflowDisposition.BUSY) {
                        entryPoint.automaticSyncRunner().reportIndexProgress(provider.id,
                            disposition == ProviderWorkflowDisposition.FAILED || disposition == ProviderWorkflowDisposition.RETRY)
                    }
                    when (disposition) {
                        ProviderWorkflowDisposition.RETRY,
                        ProviderWorkflowDisposition.BUSY -> sawRetryableFailure = true
                        ProviderWorkflowDisposition.FAILED -> sawPermanentFailure = true
                        else -> Unit
                    }
                }

            xtreamIndexWorkResult(requestedProviderId, sawRetryableFailure, sawPermanentFailure)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.e(TAG, "Xtream index worker failed", error)
            if (shouldRetry(error)) Result.retry() else Result.failure()
        }
    }

    private fun shouldRetry(error: Throwable?): Boolean {
        return ProviderWorkFailureClassifier.isRetryable(error)
    }

    companion object {
        private const val TAG = "XtreamIndexWorker"
        private const val KEY_PROVIDER_ID = "provider_id"
        private const val KEY_SECTION = "section"
        private const val KEY_FORCE = "force"
        private const val INVALID_PROVIDER_ID = -1L
        private const val UNIQUE_LAUNCH_WORK_NAME = "xtream-index-launch-stale-check"
        private const val UNIQUE_PERIODIC_WORK_NAME = "xtream-index-periodic-worker"

        fun cancelLegacyChecks(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_PERIODIC_WORK_NAME)
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_LAUNCH_WORK_NAME)
        }

        fun enqueue(
            context: Context,
            providerId: Long,
            section: String? = null,
            force: Boolean = false,
            automatic: Boolean = (AutomaticSyncExecution.automatic.get() == true),
            replaceAutomatic: Boolean = false,
            initialDelaySeconds: Long = 0L
        ) {
            if (providerId <= 0L) return
            val request = OneTimeWorkRequestBuilder<XtreamIndexWorker>()
                .setInputData(
                    Data.Builder()
                        .putLong(KEY_PROVIDER_ID, providerId)
                        .putBoolean(KEY_FORCE, force)
                        .putBoolean("automatic_refresh", automatic)
                        .putBoolean("idle_constraint", automatic && AutomaticSyncExecution.policy.get()?.deviceIdleOnly == true)
                        .also { builder ->
                            section?.let { builder.putString(KEY_SECTION, it) }
                        }
                        .build()
                )
                .setConstraints(if (automatic) automaticSyncConstraints(AutomaticSyncExecution.policy.get() ?: com.streamvault.domain.sync.AutomaticSyncSettings()) else defaultConstraints())
                .setInitialDelay(initialDelaySeconds.coerceAtLeast(0L), TimeUnit.SECONDS)
                .apply {
                    if (!automatic || AutomaticSyncExecution.policy.get()?.deviceIdleOnly != true) {
                        setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
                    }
                }
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                if (automatic) providerAutomaticIndexWorkUniqueName(providerId) else providerWorkUniqueName(providerId),
                providerWorkExistingPolicy(supersede = force || replaceAutomatic),
                request
            )
        }

        fun enqueueLaunchStaleCheck(context: Context) {
            val request = OneTimeWorkRequestBuilder<XtreamIndexWorker>()
                .setConstraints(defaultConstraints())
                .setInitialDelay(20, TimeUnit.SECONDS)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_LAUNCH_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request
            )
        }

        fun enqueuePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<XtreamIndexWorker>(6, TimeUnit.HOURS)
                .setConstraints(defaultConstraints())
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        private fun defaultConstraints(): Constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

        private fun String.toContentTypeOrNull(): ContentType? =
            runCatching { ContentType.valueOf(this) }.getOrNull()

        private fun ContentType?.toWorkflowPhase(): ProviderWorkflowPhase = when (this) {
            ContentType.MOVIE -> ProviderWorkflowPhase.MOVIE_INDEX
            ContentType.SERIES,
            ContentType.SERIES_EPISODE -> ProviderWorkflowPhase.SERIES_INDEX
            else -> ProviderWorkflowPhase.CONTENT_INDEX
        }
    }
}

/** Durable section/workflow status carries permanent failures; do not poison appended sections. */
internal fun xtreamIndexWorkResult(
    requestedProviderId: Long,
    sawRetryableFailure: Boolean,
    sawPermanentFailure: Boolean
): androidx.work.ListenableWorker.Result = when {
    sawRetryableFailure -> androidx.work.ListenableWorker.Result.retry()
    sawPermanentFailure && requestedProviderId <= 0L -> androidx.work.ListenableWorker.Result.failure()
    else -> androidx.work.ListenableWorker.Result.success()
}
