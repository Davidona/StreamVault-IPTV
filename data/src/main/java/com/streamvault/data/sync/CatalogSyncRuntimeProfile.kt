package com.streamvault.data.sync

import android.app.ActivityManager
import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import kotlin.math.max

internal enum class DeviceSyncTier {
    LOW,
    MID,
    HIGH
}

internal data class SyncDeviceSnapshot(
    val memoryClassMb: Int,
    val largeMemoryClassMb: Int?,
    val isLowRamDevice: Boolean,
    val isCurrentlyLowOnMemory: Boolean,
    val availableMemMb: Long,
    val maxHeapMb: Long,
    val isTelevision: Boolean,
    val manufacturer: String,
    val model: String,
    val sdkInt: Int
) {
    companion object {
        fun from(context: Context): SyncDeviceSnapshot {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memoryInfo = ActivityManager.MemoryInfo()
            val lowMemory = runCatching {
                activityManager?.getMemoryInfo(memoryInfo)
                memoryInfo.lowMemory
            }.getOrDefault(false)
            return SyncDeviceSnapshot(
                memoryClassMb = activityManager?.memoryClass ?: 256,
                largeMemoryClassMb = activityManager?.largeMemoryClass,
                isLowRamDevice = activityManager?.isLowRamDevice ?: false,
                isCurrentlyLowOnMemory = lowMemory,
                availableMemMb = bytesToMb(memoryInfo.availMem),
                maxHeapMb = bytesToMb(Runtime.getRuntime().maxMemory()),
                isTelevision = context.isSyncTelevisionDevice(),
                manufacturer = Build.MANUFACTURER.orEmpty(),
                model = Build.MODEL.orEmpty(),
                sdkInt = Build.VERSION.SDK_INT
            )
        }
    }
}

internal data class CatalogSyncRuntimeProfile(
    val tier: DeviceSyncTier,
    val stageBatchSize: Int,
    val maxCategoryConcurrency: Int,
    val preferSegmentedLiveOnboarding: Boolean,
    val deferBackgroundWorkOnLowMemory: Boolean,
    val snapshot: SyncDeviceSnapshot,
    /** Production AUTO uses bulk-first; the isolated benchmark package can opt out for comparison. */
    val bulkFirstAutoEnabled: Boolean = true
) {
    // Preserve existing row counts until on-device tuning. Wide rows flush by
    // estimated retained bytes first; LOW keeps only one queued batch.
    val liveQueuedBatches: Int get() = if (tier == DeviceSyncTier.LOW) 1 else 2
    val liveBatchBytes: Long get() = when (tier) {
        DeviceSyncTier.LOW -> 256L * 1024
        DeviceSyncTier.MID -> 512L * 1024
        DeviceSyncTier.HIGH -> 1024L * 1024
    }

    val diagnosticsLabel: String
        get() = "${tier.name.lowercase()} batch=$stageBatchSize memory=${snapshot.memoryClassMb}MB" +
            if (bulkFirstAutoEnabled) " auto=bulk-first" else ""

    @Suppress("UNUSED_PARAMETER")
    fun shouldAttemptFullLiveCatalog(isInitialLiveOnboarding: Boolean): Boolean =
        !snapshot.isCurrentlyLowOnMemory

    companion object {
        fun from(context: Context): CatalogSyncRuntimeProfile {
            val snapshot = SyncDeviceSnapshot.from(context)
            val detectedTier = when {
                snapshot.isLowRamDevice || snapshot.memoryClassMb <= LOW_MEMORY_CLASS_MB -> DeviceSyncTier.LOW
                snapshot.memoryClassMb <= MID_MEMORY_CLASS_MB -> DeviceSyncTier.MID
                else -> DeviceSyncTier.HIGH
            }
            val benchmarkOverrides = if (context.packageName == BENCHMARK_APPLICATION_ID) {
                context.getSharedPreferences(BENCHMARK_PROFILE_PREFERENCES, Context.MODE_PRIVATE)
            } else {
                null
            }
            val tier = benchmarkOverrides?.getString(KEY_TIER, null)
                ?.let { value -> runCatching { DeviceSyncTier.valueOf(value) }.getOrNull() }
                ?: detectedTier
            val batchOverride = benchmarkOverrides?.getInt(KEY_BATCH_SIZE, 0)?.takeIf { it > 0 }
                ?.also { require(it in MIN_BENCHMARK_BATCH_SIZE..MAX_BENCHMARK_BATCH_SIZE) }
            // Use the new AUTO policy in normal app builds. The isolated benchmark package can
            // still turn it off to compare against the previous AUTO behavior.
            val bulkFirstAutoEnabled =
                benchmarkOverrides?.getBoolean(KEY_EXPERIMENTAL_AUTO_BULK, true) ?: true
            return when (tier) {
                DeviceSyncTier.LOW -> CatalogSyncRuntimeProfile(
                    tier = tier,
                    stageBatchSize = batchOverride ?: LOW_STAGE_BATCH_SIZE,
                    maxCategoryConcurrency = 1,
                    preferSegmentedLiveOnboarding = false,
                    deferBackgroundWorkOnLowMemory = true,
                    snapshot = snapshot,
                    bulkFirstAutoEnabled = bulkFirstAutoEnabled
                )
                DeviceSyncTier.MID -> CatalogSyncRuntimeProfile(
                    tier = tier,
                    stageBatchSize = batchOverride ?: MID_STAGE_BATCH_SIZE,
                    maxCategoryConcurrency = 2,
                    preferSegmentedLiveOnboarding = true,
                    deferBackgroundWorkOnLowMemory = snapshot.isCurrentlyLowOnMemory,
                    snapshot = snapshot,
                    bulkFirstAutoEnabled = bulkFirstAutoEnabled
                )
                DeviceSyncTier.HIGH -> CatalogSyncRuntimeProfile(
                    tier = tier,
                    stageBatchSize = batchOverride ?: HIGH_STAGE_BATCH_SIZE,
                    maxCategoryConcurrency = Int.MAX_VALUE,
                    preferSegmentedLiveOnboarding = false,
                    deferBackgroundWorkOnLowMemory = false,
                    snapshot = snapshot,
                    bulkFirstAutoEnabled = bulkFirstAutoEnabled
                )
            }
        }

        private const val BENCHMARK_APPLICATION_ID = "com.streamvault.app.syncbenchmark"
        private const val BENCHMARK_PROFILE_PREFERENCES = "xtream_sync_benchmark_profile"
        private const val KEY_TIER = "tier"
        private const val KEY_BATCH_SIZE = "batch_size"
        private const val KEY_EXPERIMENTAL_AUTO_BULK = "experimental_auto_bulk"
        private const val MIN_BENCHMARK_BATCH_SIZE = 100
        private const val MAX_BENCHMARK_BATCH_SIZE = 1024
        private const val LOW_MEMORY_CLASS_MB = 192
        private const val MID_MEMORY_CLASS_MB = 320
        private const val LOW_STAGE_BATCH_SIZE = 100
        private const val MID_STAGE_BATCH_SIZE = 300
        private const val HIGH_STAGE_BATCH_SIZE = 500
    }
}

private fun Context.isSyncTelevisionDevice(): Boolean {
    val packageManager = packageManager
    if (packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) return true
    if (packageManager.hasSystemFeature("android.software.leanback_only")) return true
    if (packageManager.hasSystemFeature(PackageManager.FEATURE_TELEVISION)) return true
    if (packageManager.hasSystemFeature("amazon.hardware.fire_tv")) return true
    val uiModeManager = getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    if (uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) return true
    val screenWidthDp = resources.configuration.screenWidthDp
    return !packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN) && screenWidthDp >= 900
}

private fun bytesToMb(value: Long): Long = max(0L, value / (1024L * 1024L))

internal fun Context.isCurrentlyLowOnMemoryForSync(): Boolean =
    SyncDeviceSnapshot.from(this).isCurrentlyLowOnMemory
