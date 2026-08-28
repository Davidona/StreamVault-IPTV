package com.streamvault.data.manager.recording

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
import com.streamvault.data.R
import com.streamvault.data.platform.DataSyncQuotaAcquireResult
import com.streamvault.data.platform.DataSyncQuotaLease
import com.streamvault.data.platform.DataSyncQuotaOwner
import com.streamvault.data.platform.DataSyncServiceOwner
import com.streamvault.domain.manager.RecordingManager
import com.streamvault.domain.model.RecordingStatus
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class RecordingForegroundService : Service() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface RecordingServiceEntryPoint {
        fun recordingManager(): RecordingManager
        fun dataSyncQuotaOwner(): DataSyncQuotaOwner
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val idleGate = RecordingForegroundIdleGate()
    private var idleStopJob: Job? = null
    private var notificationJob: Job? = null
    private var dataSyncQuotaOwner: DataSyncQuotaOwner? = null
    private var dataSyncQuotaLease: DataSyncQuotaLease? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_START_CAPTURE || action == ACTION_STOP_CAPTURE || action == ACTION_RECONCILE) {
            beginPendingCommand()
        }
        runCatching {
            startDataSyncForeground(
                buildNotification(
                    activeCount = idleGate.activeRecordingCount,
                    pendingCommand = idleGate.hasPendingCommands
                )
            )
        }.onFailure { error ->
            Log.e("RecordingFgService", "Unable to enter foreground", error)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        ensureDataSyncQuotaLease()
        ensureNotificationObserver()
        val manager = entryPoint().recordingManager()
        when (action) {
            ACTION_START_CAPTURE -> {
                val recordingId = intent.getStringExtra(EXTRA_RECORDING_ID).orEmpty()
                serviceScope.launch {
                    try {
                        manager.promoteScheduledRecording(recordingId)
                    } finally {
                        finishPendingCommand()
                    }
                }
            }
            ACTION_STOP_CAPTURE -> {
                val recordingId = intent.getStringExtra(EXTRA_RECORDING_ID).orEmpty()
                serviceScope.launch {
                    try {
                        manager.stopRecording(recordingId)
                    } finally {
                        finishPendingCommand()
                    }
                }
            }
            ACTION_RECONCILE -> {
                serviceScope.launch {
                    try {
                        manager.reconcileRecordingState()
                    } finally {
                        finishPendingCommand()
                    }
                }
            }
            else -> evaluateIdleState()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        releaseDataSyncQuotaLease("service_destroyed")
        idleStopJob?.cancel()
        notificationJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.e(TAG, "Foreground-service time allowance exhausted; stopping active recordings")
        idleStopJob?.cancel()
        serviceScope.launch {
            try {
                val manager = entryPoint().recordingManager()
                manager.observeRecordingItems().first()
                    .asSequence()
                    .filter { it.status == RecordingStatus.RECORDING }
                    .forEach { recording ->
                        runCatching {
                            manager.stopRecordingForForegroundServiceTimeout(recording.id)
                        }.onFailure { error ->
                            Log.e(TAG, "Failed to stop recording ${recording.id} after service timeout", error)
                        }
                    }
            } finally {
                releaseDataSyncQuotaLease("android_timeout")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureNotificationObserver() {
        if (notificationJob != null) return
        notificationJob = serviceScope.launch {
            entryPoint().recordingManager().observeActiveRecordingCount().collectLatest { activeCount ->
                idleGate.onActiveCountChanged(activeCount)
                updateNotification()
                evaluateIdleState()
            }
        }
    }

    private fun buildNotification(activeCount: Int, pendingCommand: Boolean): Notification {
        val title = if (activeCount > 0) {
            "$activeCount recording${if (activeCount == 1) "" else "s"} in progress"
        } else if (pendingCommand) {
            "Starting recording service"
        } else {
            "Preparing recording service"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("StreamVault DVR")
            .setContentText(title)
            .setOngoing(activeCount > 0 || pendingCommand)
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
            "StreamVault DVR",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Active recording and scheduling status"
        }
        manager.createNotificationChannel(channel)
    }

    private fun entryPoint(): RecordingServiceEntryPoint =
        EntryPointAccessors.fromApplication(applicationContext, RecordingServiceEntryPoint::class.java)

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
        when (val result = owner.acquire(DataSyncServiceOwner.RECORDING)) {
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
        idleGate.onCommandStarted()
        idleStopJob?.cancel()
        idleStopJob = null
        updateNotification()
    }

    private fun finishPendingCommand() {
        idleGate.onCommandFinished()
        updateNotification()
        evaluateIdleState()
    }

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(
            NOTIFICATION_ID,
            buildNotification(
                activeCount = idleGate.activeRecordingCount,
                pendingCommand = idleGate.hasPendingCommands
            )
        )
    }

    private fun evaluateIdleState() {
        if (idleGate.shouldKeepAlive()) {
            idleStopJob?.cancel()
            idleStopJob = null
            return
        }
        if (idleStopJob?.isActive == true) return
        idleStopJob = serviceScope.launch {
            delay(IDLE_GRACE_MS)
            if (!idleGate.shouldKeepAlive()) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    companion object {
        private const val TAG = "RecordingFgService"
        private const val CHANNEL_ID = "streamvault_recording"
        private const val NOTIFICATION_ID = 4102
        private const val IDLE_GRACE_MS = 3_000L
        private const val ACTION_START_CAPTURE = "com.streamvault.data.recording.service.START_CAPTURE"
        private const val ACTION_STOP_CAPTURE = "com.streamvault.data.recording.service.STOP_CAPTURE"
        private const val ACTION_RECONCILE = "com.streamvault.data.recording.service.RECONCILE"
        private const val ACTION_EVALUATE_IDLE = "com.streamvault.data.recording.service.EVALUATE_IDLE"
        private const val EXTRA_RECORDING_ID = "recording_id"

        fun startCapture(context: Context, recordingId: String) {
            val intent = Intent(context, RecordingForegroundService::class.java)
                .setAction(ACTION_START_CAPTURE)
                .putExtra(EXTRA_RECORDING_ID, recordingId)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stopRecording(context: Context, recordingId: String) {
            val intent = Intent(context, RecordingForegroundService::class.java)
                .setAction(ACTION_STOP_CAPTURE)
                .putExtra(EXTRA_RECORDING_ID, recordingId)
            ContextCompat.startForegroundService(context, intent)
        }

        fun requestReconcile(context: Context) {
            val intent = Intent(context, RecordingForegroundService::class.java)
                .setAction(ACTION_RECONCILE)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stopIfIdle(context: Context) {
            val intent = Intent(context, RecordingForegroundService::class.java)
                .setAction(ACTION_EVALUATE_IDLE)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
