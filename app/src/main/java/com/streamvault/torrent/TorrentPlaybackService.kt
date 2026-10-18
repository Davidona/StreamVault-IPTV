package com.streamvault.torrent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.StatFs
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.streamvault.app.R
import com.streamvault.domain.model.TorrentCacheClearResult
import com.streamvault.domain.model.TorrentCacheStatus
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Hosts the app's built-in torrent engine and its loopback-only playback endpoint. */
class TorrentPlaybackService : Service() {
    @Volatile private var engine: TorrentEngine? = null
    @Volatile private var server: LocalRangeServer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val idleStop = Runnable {
        if (engine?.isIdle() == true) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }
    private val refreshNotification = object : Runnable {
        override fun run() {
            val activeEngine = engine ?: return
            activeEngine.expireIdleSessions()
            val snapshot = activeEngine.statusSnapshot()
            if (snapshot.activeCount == 0) {
                scheduleStopIfIdle()
            } else {
                val label = snapshot.activeSessions.firstOrNull()
                    ?.let { getString(R.string.download_in_progress, it) }
                    ?: getString(R.string.home_loading)
                startForegroundCompat(buildNotification(label))
                mainHandler.postDelayed(this, NOTIFICATION_REFRESH_MILLIS)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForegroundCompat(buildNotification(getString(R.string.home_loading)))

        var createdEngine: TorrentEngine? = null
        var createdServer: LocalRangeServer? = null
        try {
            createdEngine = TorrentEngine(this, TorrentPreferences(this))
            createdServer = LocalRangeServer(createdEngine)
            createdEngine.setLoopbackBaseUrl(createdServer.baseUrl)
            engine = createdEngine
            server = createdServer
            synchronized(serviceLock) {
                instance = this
                startupFuture?.complete(this)
                startupFuture = null
            }
            mainHandler.postDelayed(refreshNotification, NOTIFICATION_REFRESH_MILLIS)
        } catch (error: Throwable) {
            runCatching { createdServer?.close() }
            runCatching { createdEngine?.close() }
            synchronized(serviceLock) {
                startupFuture?.completeExceptionally(error)
                startupFuture = null
            }
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        synchronized(serviceLock) { if (instance === this) instance = null }
        runCatching { server?.close() }
        runCatching { engine?.close() }
        server = null
        engine = null
        super.onDestroy()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(status: String): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(
                this,
                0,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(status.take(120))
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(NOTIFICATION_CHANNEL, getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun scheduleStopIfIdle() {
        mainHandler.removeCallbacks(idleStop)
        mainHandler.postDelayed(idleStop, IDLE_STOP_DELAY_MILLIS)
    }

    private fun stopServiceIfIdle() {
        if (engine?.isIdle() == true) scheduleStopIfIdle()
        else {
            mainHandler.removeCallbacks(idleStop)
            mainHandler.post(refreshNotification)
        }
    }

    companion object {
        private const val NOTIFICATION_CHANNEL = "torrent_playback"
        private const val NOTIFICATION_ID = 7319
        private const val NOTIFICATION_REFRESH_MILLIS = 2_500L
        private const val IDLE_STOP_DELAY_MILLIS = 1_500L
        private const val STARTUP_TIMEOUT_SECONDS = 20L
        private val serviceLock = Any()

        @Volatile private var instance: TorrentPlaybackService? = null
        private var startupFuture: CompletableFuture<TorrentPlaybackService>? = null

        internal fun prepare(context: Context, magnet: String): PreparedTorrent {
            return prepareInternal(context, magnet, forDownload = false)
        }

        internal fun prepareDownload(context: Context, magnet: String): PreparedTorrent {
            return prepareInternal(context, magnet, forDownload = true)
        }

        private fun prepareInternal(
            context: Context,
            magnet: String,
            forDownload: Boolean
        ): PreparedTorrent {
            val service = ensureStarted(context)
            val prepared = checkNotNull(service.engine).prepare(magnet, forDownload)
            service.mainHandler.removeCallbacks(service.refreshNotification)
            service.mainHandler.removeCallbacks(service.idleStop)
            service.mainHandler.post(service.refreshNotification)
            return prepared
        }

        internal fun stopPlayback(outputUrl: String) {
            val service = instance ?: return
            service.engine?.stop(outputUrl)
            service.stopServiceIfIdle()
        }

        internal fun cacheStatus(context: Context): TorrentCacheStatus {
            val activeEngine = synchronized(serviceLock) { instance?.engine }
            if (activeEngine != null) return activeEngine.cacheStatus()

            val root = TorrentStorageLocations.torrentDirectory(
                context.applicationContext,
                TorrentPreferences(context.applicationContext).storageLocationIndex
            )
            return TorrentStorageLocations.withCacheLock {
                TorrentStorageLocations.cleanupOrphanedSessionDirectories(root)
                TorrentCacheStatus(
                    cachedBytes = 0L,
                    cacheLimitBytes = TorrentCachePolicy.MAX_CACHE_BYTES,
                    availableStorageBytes = runCatching { StatFs(root.absolutePath).availableBytes }.getOrNull(),
                    activeSessionCount = 0
                )
            }
        }

        internal fun clearDownloadedData(context: Context): TorrentCacheClearResult {
            val activeEngine = synchronized(serviceLock) { instance?.engine }
            if (activeEngine != null) return activeEngine.clearDownloadedData()

            val appContext = context.applicationContext
            val root = TorrentStorageLocations.torrentDirectory(
                appContext,
                TorrentPreferences(appContext).storageLocationIndex
            )
            return TorrentStorageLocations.withCacheLock {
                if (TorrentStorageLocations.clearSessionDirectories(root)) {
                    TorrentCacheClearResult.CLEARED
                } else {
                    TorrentCacheClearResult.FAILED
                }
            }
        }

        private fun ensureStarted(context: Context): TorrentPlaybackService {
            instance?.let { return it }
            val appContext = context.applicationContext
            val future: CompletableFuture<TorrentPlaybackService>
            synchronized(serviceLock) {
                instance?.let { return it }
                future = startupFuture ?: CompletableFuture<TorrentPlaybackService>().also {
                    startupFuture = it
                    try {
                        ContextCompat.startForegroundService(
                            appContext,
                            Intent(appContext, TorrentPlaybackService::class.java)
                        )
                    } catch (error: Throwable) {
                        startupFuture = null
                        it.completeExceptionally(error)
                    }
                }
            }
            return future.get(STARTUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
    }
}
