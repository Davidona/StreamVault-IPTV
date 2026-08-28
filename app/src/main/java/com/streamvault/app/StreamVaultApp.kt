package com.streamvault.app

import android.app.Application
import android.util.Log
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.streamvault.app.diagnostics.CrashReportStore
import com.streamvault.app.diagnostics.RuntimeDiagnosticsManager
import com.streamvault.core.ui.accessibility.isReducedMotionEnabled
import com.streamvault.data.remote.jellyfin.JellyfinImageAuthInterceptor
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import okio.Path.Companion.toOkioPath

import androidx.work.Constraints
import javax.inject.Inject
import javax.inject.Provider
import okhttp3.OkHttpClient

@HiltAndroidApp
class StreamVaultApp : Application(), SingletonImageLoader.Factory {
    private val runtimeDiagnosticsManager by lazy { RuntimeDiagnosticsManager(this) }
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Inject
    lateinit var okHttpClient: Provider<OkHttpClient>

    @Inject
    lateinit var jellyfinImageAuthInterceptor: Provider<JellyfinImageAuthInterceptor>

    @Inject
    internal lateinit var appStartupCoordinator: AppStartupCoordinator

    @Inject
    lateinit var databaseStartupCoordinator: DatabaseStartupCoordinator

    private val imageOkHttpClient: OkHttpClient by lazy {
        okHttpClient.get().newBuilder()
            .addInterceptor(jellyfinImageAuthInterceptor.get())
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        CrashReportStore.install(this)
        runtimeDiagnosticsManager.start()
        // Open Room once the process has completed lightweight setup, then admit
        // database-backed maintenance through the Phase 5 startup coordinator.
        databaseStartupCoordinator.start()
        applicationScope.launch {
            databaseStartupCoordinator.state
                .filterIsInstance<DatabaseStartupState.Ready>()
                .first()
            appStartupCoordinator.startProcessMaintenance()
        }
    }

    override fun onTerminate() {
        runtimeDiagnosticsManager.stop()
        super.onTerminate()
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
                    .maxSizePercent(context, 0.15) // Conservative TV memory cache
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(this.cacheDir.resolve("image_cache").toOkioPath())
                    .maxSizeBytes(1024L * 1024L * 100L) // 100MB disk cache
                    .build()
            }
            // Limit concurrent decoding and fetching to 6 for TV hardware constraints
            .fetcherCoroutineContext(Dispatchers.IO.limitedParallelism(6))
            .decoderCoroutineContext(Dispatchers.Default.limitedParallelism(4))
            .crossfade(!isReducedMotionEnabled(context))
            .build()
    }
}

internal fun dataMaintenanceConstraints(): Constraints = Constraints.Builder()
    .setRequiresBatteryNotLow(true)
    .setRequiresDeviceIdle(true)
    .build()
