package com.streamvault.app

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.streamvault.app.diagnostics.CrashReportStore
import com.streamvault.app.diagnostics.RuntimeDiagnosticsManager
import com.streamvault.app.update.GitHubReleaseChecker
import com.streamvault.app.ui.accessibility.isReducedMotionEnabled
import com.streamvault.app.work.AutoM3uRefreshScheduler
import com.streamvault.data.manager.recording.RecordingReconcileWorker
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.data.remote.jellyfin.JellyfinImageAuthInterceptor
import com.streamvault.data.sync.ProviderSyncWorker
import com.streamvault.data.sync.XtreamIndexWorker
import com.streamvault.data.util.isUserUnlockedForWork
import com.streamvault.domain.model.Result
import com.streamvault.player.timeshift.TimeshiftDiskManager
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath
import java.util.concurrent.atomic.AtomicBoolean
import androidx.work.Constraints
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager

@HiltAndroidApp
class StreamVaultApp :
    Application(),
    SingletonImageLoader.Factory,
    Configuration.Provider {
    private val runtimeDiagnosticsManager by lazy { RuntimeDiagnosticsManager(this) }
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val initializedAfterUnlock = AtomicBoolean(false)
    private var userUnlockedReceiver: BroadcastReceiver? = null

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    @Inject
    lateinit var preferencesRepository: PreferencesRepository

    @Inject
    lateinit var gitHubReleaseChecker: GitHubReleaseChecker

    @Inject
    lateinit var okHttpClient: OkHttpClient

    @Inject
    lateinit var jellyfinImageAuthInterceptor: JellyfinImageAuthInterceptor

    private val imageOkHttpClient: OkHttpClient by lazy {
        okHttpClient.newBuilder()
            .addInterceptor(jellyfinImageAuthInterceptor)
            .build()
    }

    override fun onCreate() {
        super.onCreate()

        if (isUserUnlockedForWork()) {
            initializeAfterUserUnlock()
        } else {
            registerUserUnlockedReceiver()
        }
    }

    private fun registerUserUnlockedReceiver() {
        if (userUnlockedReceiver != null) return

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != Intent.ACTION_USER_UNLOCKED || !isUserUnlockedForWork()) {
                    return
                }

                runCatching { unregisterReceiver(this) }
                userUnlockedReceiver = null
                initializeAfterUserUnlock()
            }
        }

        userUnlockedReceiver = receiver
        ContextCompat.registerReceiver(
            this,
            receiver,
            IntentFilter(Intent.ACTION_USER_UNLOCKED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun initializeAfterUserUnlock() {
        if (!initializedAfterUnlock.compareAndSet(false, true)) return

        CrashReportStore.install(this)
        runtimeDiagnosticsManager.start()

        applicationScope.launch {
            TimeshiftDiskManager(applicationContext).cleanupStaleDirectories(activeSessionDir = null)
        }

        applicationScope.launch {
            refreshCachedAppUpdateIfNeeded()
        }

        val gcConstraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .setRequiresDeviceIdle(true)
            .build()

        val gcWorkRequest =
            PeriodicWorkRequestBuilder<com.streamvault.data.sync.SyncWorker>(
                24,
                java.util.concurrent.TimeUnit.HOURS
            )
                .setConstraints(gcConstraints)
                .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "DataMaintenanceWorker",
            ExistingPeriodicWorkPolicy.KEEP,
            gcWorkRequest
        )

        ProviderSyncWorker.enqueuePeriodic(this)
        ProviderSyncWorker.enqueueLaunchStaleCheck(this)
        XtreamIndexWorker.enqueuePeriodic(this)
        XtreamIndexWorker.enqueueLaunchStaleCheck(this)
        RecordingReconcileWorker.enqueuePeriodic(this)
        RecordingReconcileWorker.enqueueOneShot(this)

        AutoM3uRefreshScheduler.schedule(this)
    }

    override fun onTerminate() {
        userUnlockedReceiver?.let { receiver ->
            runCatching { unregisterReceiver(receiver) }
            userUnlockedReceiver = null
        }

        if (initializedAfterUnlock.get()) {
            runtimeDiagnosticsManager.stop()
        }

        super.onTerminate()
    }

    private suspend fun refreshCachedAppUpdateIfNeeded() {
        val autoCheckEnabled = preferencesRepository.autoCheckAppUpdates.first()
        if (!autoCheckEnabled) return

        val lastCheckedAt = preferencesRepository.lastAppUpdateCheckTimestamp.first()
        val now = System.currentTimeMillis()
        val checkIntervalMs = 24L * 60L * 60L * 1000L

        if (lastCheckedAt != null && now - lastCheckedAt < checkIntervalMs) return

        preferencesRepository.setLastAppUpdateCheckTimestamp(now)

        when (val result = gitHubReleaseChecker.fetchLatestRelease()) {
            is Result.Success -> {
                preferencesRepository.setCachedAppUpdateRelease(
                    versionName = result.data.versionName,
                    versionCode = result.data.versionCode,
                    releaseUrl = result.data.releaseUrl,
                    downloadUrl = result.data.downloadUrl,
                    releaseNotes = result.data.releaseNotes,
                    publishedAt = result.data.publishedAt
                )
            }

            else -> Unit
        }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        return ImageLoader.Builder(context)
            .components {
                add(
                    OkHttpNetworkFetcherFactory(
                        callFactory = { imageOkHttpClient }
                    )
                )
            }
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, 0.15)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(this.cacheDir.resolve("image_cache").toOkioPath())
                    .maxSizeBytes(1024L * 1024L * 100L)
                    .build()
            }
            .fetcherCoroutineContext(Dispatchers.IO.limitedParallelism(6))
            .decoderCoroutineContext(Dispatchers.Default.limitedParallelism(4))
            .crossfade(!isReducedMotionEnabled(context))
            .build()
    }
}
