package com.streamvault.app

import android.content.Context
import android.os.Build
import android.os.Trace
import android.util.Log
import com.streamvault.app.plugins.StreamVaultPluginManager
import com.streamvault.app.tv.LauncherRecommendationsManager
import com.streamvault.app.tv.WatchNextManager
import com.streamvault.data.manager.PendingBackupRestoreCoordinator
import com.streamvault.data.sync.ProviderSyncLifecycle
import com.streamvault.domain.manager.ProgramReminderManager
import com.streamvault.domain.repository.DownloadManager
import com.streamvault.player.timeshift.TimeshiftDiskManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

internal data class AppStartupTask(
    val traceName: String,
    val action: suspend () -> Unit
)

private typealias TraceSection = suspend (String, suspend () -> Unit) -> Unit

internal interface StartupTraceSink {
    fun beginAsyncSection(name: String, cookie: Int)
    fun endAsyncSection(name: String, cookie: Int)
}

/**
 * Coordinates work that must happen once per process without making the first Activity pay for
 * it. Process maintenance remains available to service-only launches, while TV integrations wait
 * until the first UI frame has been submitted.
 */
@Singleton
internal class AppStartupCoordinator internal constructor(
    private val processTasks: List<AppStartupTask>,
    private val tvTasks: List<AppStartupTask>,
    private val scope: CoroutineScope,
    private val traceSection: TraceSection = ::runWithoutAndroidTrace,
    private val onTaskFailure: (String, Throwable) -> Unit = { _, _ -> }
) {

    @Inject
    constructor(
        @ApplicationContext context: Context,
        startupWorkRegistry: Provider<StartupWorkRegistry>,
        downloadManager: Provider<DownloadManager>,
        streamVaultPluginManager: Provider<StreamVaultPluginManager>,
        programReminderManager: Provider<ProgramReminderManager>,
        providerSyncLifecycle: Provider<ProviderSyncLifecycle>,
        pendingBackupRestoreCoordinator: Provider<PendingBackupRestoreCoordinator>,
        watchNextManager: Provider<WatchNextManager>,
        launcherRecommendationsManager: Provider<LauncherRecommendationsManager>,
        tvInputChannelSyncManager: Provider<com.streamvault.app.tvinput.TvInputChannelSyncManager>
    ) : this(
        processTasks = listOf(
            providerTask("work-registration", startupWorkRegistry) { it.register() },
            AppStartupTask("stale-timeshift-cleanup") {
                TimeshiftDiskManager(context.applicationContext)
                    .cleanupStaleDirectories(activeSessionDir = null)
            },
            providerTask("interrupted-download-recovery", downloadManager) {
                it.recoverInterruptedDownloads()
            },
            providerTask("plugin-provider-reconciliation", streamVaultPluginManager) {
                it.reconcilePluginProviders()
            },
            providerTask("reminder-restoration", programReminderManager) {
                it.restoreScheduledReminders()
            },
            providerTask("stalker-index-work-reconciliation", providerSyncLifecycle) {
                it.reconcileStalkerIndexWorkAtStartup()
            },
            providerTask("pending-backup-restoration", pendingBackupRestoreCoordinator) {
                it.applyAllAvailable()
            }
        ),
        tvTasks = listOf(
            providerTask("watch-next-refresh", watchNextManager) { it.refreshWatchNext() },
            providerTask("launcher-recommendation-refresh", launcherRecommendationsManager) {
                it.refreshRecommendations()
            },
            providerTask("tv-input-catalog-refresh", tvInputChannelSyncManager) {
                it.refreshTvInputCatalog()
            }
        ),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        traceSection = ::runWithAndroidTrace,
        onTaskFailure = ::logTaskFailure
    )

    private val processMaintenanceStarted = AtomicBoolean(false)
    private val firstUiFrameHandled = AtomicBoolean(false)

    /** Starts process maintenance once, even when no Activity is created. */
    fun startProcessMaintenance() {
        if (!processMaintenanceStarted.compareAndSet(false, true)) return

        scope.launch {
            runTaskGroup("process-maintenance", processTasks)
        }
    }

    /** Defers television integrations until the first rendered UI frame, and runs them once. */
    fun onFirstUiFrameDrawn(isTelevision: Boolean) {
        if (!isTelevision || !firstUiFrameHandled.compareAndSet(false, true)) return

        scope.launch {
            runTaskGroup("tv-integrations-after-first-frame", tvTasks)
        }
    }

    private suspend fun runTaskGroup(groupName: String, tasks: List<AppStartupTask>) {
        traceSection("AppStartupCoordinator:$groupName") {
            supervisorScope {
                tasks.map { task ->
                    launch { runTask(task) }
                }.joinAll()
            }
        }
    }

    private suspend fun runTask(task: AppStartupTask) {
        traceSection("AppStartupCoordinator:${task.traceName}") {
            try {
                task.action()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                onTaskFailure(task.traceName, throwable)
            }
        }
    }

    companion object {
        private const val TAG = "AppStartupCoordinator"

        internal fun <T> providerTask(
            traceName: String,
            provider: Provider<T>,
            action: suspend (T) -> Unit
        ): AppStartupTask = AppStartupTask(traceName) {
            action(provider.get())
        }
    }
}

private fun logTaskFailure(taskName: String, throwable: Throwable) {
    Log.w("AppStartupCoordinator", "Startup task failed: $taskName", throwable)
}

private suspend fun runWithoutAndroidTrace(
    @Suppress("UNUSED_PARAMETER") name: String,
    block: suspend () -> Unit
) = block()

private val nextTraceCookie = AtomicInteger()

internal suspend fun runWithAsyncTrace(
    name: String,
    sink: StartupTraceSink,
    block: suspend () -> Unit
) {
    val cookie = nextTraceCookie.incrementAndGet()
    sink.beginAsyncSection(name, cookie)
    try {
        block()
    } finally {
        sink.endAsyncSection(name, cookie)
    }
}

private suspend fun runWithAndroidTrace(name: String, block: suspend () -> Unit) {
    runWithAsyncTrace(name, PlatformStartupTraceSink, block)
}

private object PlatformStartupTraceSink : StartupTraceSink {
    override fun beginAsyncSection(name: String, cookie: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            Trace.beginAsyncSection(name, cookie)
        }
    }

    override fun endAsyncSection(name: String, cookie: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            Trace.endAsyncSection(name, cookie)
        }
    }
}
