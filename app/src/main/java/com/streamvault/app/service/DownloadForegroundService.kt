package com.streamvault.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.pm.ServiceInfo
import android.content.Intent
import android.util.Log
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.streamvault.app.MainActivity
import com.streamvault.app.R
import com.streamvault.data.local.dao.DownloadDao
import com.streamvault.data.platform.DataSyncQuotaAcquireResult
import com.streamvault.data.platform.DataSyncQuotaLease
import com.streamvault.data.platform.DataSyncQuotaOwner
import com.streamvault.data.platform.DataSyncServiceOwner
import com.streamvault.domain.model.DownloadItem
import com.streamvault.domain.model.DownloadStatus
import com.streamvault.domain.repository.DownloadManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.NumberFormat

class DownloadForegroundService : Service() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface DownloadServiceEntryPoint {
        fun downloadManager(): DownloadManager
        fun dataSyncQuotaOwner(): DataSyncQuotaOwner
        fun downloadDao(): DownloadDao
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observeJob: Job? = null
    private var currentDownloadId: String? = null
    private var dataSyncQuotaOwner: DataSyncQuotaOwner? = null
    private var dataSyncQuotaLease: DataSyncQuotaLease? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val downloadId = intent?.getStringExtra(EXTRA_DOWNLOAD_ID)
        val startMode = resolveDownloadServiceStartMode(downloadId)
        currentDownloadId = downloadId
        beginPendingCommand()

        val foregroundStarted = runCatching {
            startDataSyncForeground(
                buildNotification(
                    downloadItem = null,
                    pendingCommand = true
                )
            )
            true
        }.onFailure { error ->
            Log.e(TAG, "Unable to enter foreground", error)
        }.getOrDefault(false)
        if (!foregroundStarted) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        // Promote the service before touching the Hilt graph/Room. Opening the database can
        // block during a migration, and Android's FGS startup deadline applies until this call
        // completes. A slow migration must not make the system believe this service never
        // entered the foreground (which also prevents dataSync timeout callbacks on API 35+).
        val entryPoint = entryPoint()
        ensureDataSyncQuotaLease()

        observeJob?.cancel()
        if (startMode == DownloadServiceStartMode.RECOVER_INTERRUPTED) {
            Log.w(TAG, "Sticky restart without download_id; reconciling interrupted downloads")
            observeJob = serviceScope.launch {
                val manager = entryPoint.downloadManager()
                if (manager.recoverInterruptedDownloads() is com.streamvault.domain.model.Result.Error) {
                    stopSelf(startId)
                    return@launch
                }
                manager.observeAllDownloads().collectLatest { downloads ->
                    val active = downloads.firstOrNull {
                        it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.PENDING
                    }
                    currentDownloadId = active?.id
                    updateNotification(active)
                    if (active == null) stopSelf(startId)
                }
            }
            return START_STICKY
        }

        val downloadFlow = entryPoint.downloadManager().observeDownload(requireNotNull(downloadId))
        observeJob = serviceScope.launch {
            downloadFlow.collectLatest { downloadItem ->
                updateNotification(downloadItem)
                if (downloadItem != null) {
                    when (downloadItem.status) {
                        DownloadStatus.COMPLETED,
                        DownloadStatus.FAILED,
                        DownloadStatus.CANCELLED -> {
                            updateNotification(downloadItem)
                            stopSelf()
                        }
                        else -> Unit
                    }
                }
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        releaseDataSyncQuotaLease("service_destroyed")
        observeJob?.cancel()
        serviceScope.cancel()
        currentDownloadId = null
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        val downloadId = currentDownloadId
        Log.e(TAG, "Foreground-service time allowance exhausted; pausing download $downloadId")
        serviceScope.launch {
            try {
                if (!downloadId.isNullOrBlank()) {
                    entryPoint().downloadManager().pauseDownloadForForegroundServiceTimeout(downloadId)
                }
            } finally {
                releaseDataSyncQuotaLease("android_timeout")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(
        downloadItem: DownloadItem?,
        pendingCommand: Boolean = false
    ): Notification {
        val title = if (pendingCommand) {
            "Preparing download service"
        } else if (downloadItem != null) {
            downloadItem.contentName
        } else {
            "Preparing download service"
        }

        val contentText = when {
            pendingCommand -> "Starting download service"
            downloadItem != null -> {
                when (downloadItem.status) {
                    DownloadStatus.COMPLETED -> {
                        getString(R.string.download_completed)
                    }
                    DownloadStatus.FAILED -> {
                        val reason = downloadItem.failureReason ?: "Download failed"
                        getString(R.string.download_failed, reason)
                    }
                    DownloadStatus.CANCELLED -> {
                        getString(R.string.download_cancelled)
                    }
                    DownloadStatus.DOWNLOADING -> {
                        val totalBytesLocal = downloadItem.totalBytes
                        val progressText = if (totalBytesLocal != null && totalBytesLocal > 0L) {
                            val written = formatBytes(downloadItem.bytesWritten)
                            val total = formatBytes(totalBytesLocal)
                            "Downloading: $written / $total"
                        } else {
                            "Downloading: ${formatBytes(downloadItem.bytesWritten)} written"
                        }
                        getString(R.string.download_in_progress, downloadItem.contentName) + "\n$progressText"
                    }
                    else -> {
                        "Download in progress: ${downloadItem.contentName}"
                    }
                }
            }
            else -> "Preparing download service"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(contentText)
            .setOngoing(downloadItem?.status == DownloadStatus.DOWNLOADING || pendingCommand)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(defaultContentIntent())
            .build()
    }

    private fun defaultContentIntent(): PendingIntent? {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        return PendingIntent.getActivity(
            this,
            1001,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.download_notification_channel) ?: "Downloads",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.download_notification_channel_description) ?: "Shows download progress and completion"
        }
        manager.createNotificationChannel(channel)
    }

    private fun entryPoint(): DownloadServiceEntryPoint =
        EntryPointAccessors.fromApplication(applicationContext, DownloadServiceEntryPoint::class.java)

    private fun startDataSyncForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureDataSyncQuotaLease() {
        if (dataSyncQuotaLease != null) return
        val owner = entryPoint().dataSyncQuotaOwner()
        when (val result = owner.acquire(DataSyncServiceOwner.DOWNLOAD)) {
            is DataSyncQuotaAcquireResult.Granted -> {
                dataSyncQuotaOwner = owner
                dataSyncQuotaLease = result.lease
            }
            is DataSyncQuotaAcquireResult.Exhausted -> {
                dataSyncQuotaOwner = owner
                Log.e(TAG, "Shared dataSync quota is exhausted; Android timeout handling remains authoritative")
            }
        }
    }

    private fun releaseDataSyncQuotaLease(reason: String) {
        val lease = dataSyncQuotaLease ?: return
        dataSyncQuotaLease = null
        runCatching { dataSyncQuotaOwner?.release(lease) }
            .onFailure { error -> Log.w(TAG, "Unable to release dataSync quota lease ($reason)", error) }
        dataSyncQuotaOwner = null
    }

    private fun beginPendingCommand() {
        updateNotification(downloadItem = null, pendingCommand = true)
    }

    private fun updateNotification(downloadItem: DownloadItem?, pendingCommand: Boolean = false) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(
            NOTIFICATION_ID,
            buildNotification(downloadItem, pendingCommand)
        )
    }

    private fun formatBytes(bytes: Long): String {
        val formatter = NumberFormat.getNumberInstance()
        formatter.maximumFractionDigits = 1
        return when {
            bytes < 1024L -> "$bytes B"
            bytes < 1024L * 1024L -> "${formatter.format(bytes / 1024.0)} KB"
            bytes < 1024L * 1024L * 1024L -> "${formatter.format(bytes / (1024.0 * 1024.0))} MB"
            else -> "${formatter.format(bytes / (1024.0 * 1024.0 * 1024.0))} GB"
        }
    }

    companion object {
        private const val TAG = "DownloadFgService"
        private const val CHANNEL_ID = "streamvault_downloads"
        private const val NOTIFICATION_ID = 2001
        private const val EXTRA_DOWNLOAD_ID = "download_id"

        fun startDownload(context: Context, downloadId: String) {
            val intent = Intent(context, DownloadForegroundService::class.java)
                .putExtra(EXTRA_DOWNLOAD_ID, downloadId)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}

internal enum class DownloadServiceStartMode {
    OBSERVE_REQUESTED,
    RECOVER_INTERRUPTED
}

internal fun resolveDownloadServiceStartMode(downloadId: String?): DownloadServiceStartMode =
    if (downloadId.isNullOrBlank()) {
        DownloadServiceStartMode.RECOVER_INTERRUPTED
    } else {
        DownloadServiceStartMode.OBSERVE_REQUESTED
    }
