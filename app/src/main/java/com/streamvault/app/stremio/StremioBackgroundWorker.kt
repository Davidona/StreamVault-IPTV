package com.streamvault.app.stremio

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.streamvault.app.MainActivity
import com.streamvault.app.R
import com.streamvault.domain.model.stremio.*
import com.streamvault.domain.repository.StremioAddonRepository
import com.streamvault.domain.repository.StremioPlaybackHistoryRepository
import com.streamvault.domain.settings.SettingsPreferences
import com.streamvault.feature.stremio.navigation.StremioRoutePatterns
import com.streamvault.feature.stremio.presentation.StremioCatalogParentalAccess
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

@HiltWorker
class StremioBackgroundWorker @AssistedInject constructor(
    @Assisted context:Context,@Assisted parameters:WorkerParameters,
    private val account:StremioAccount,
    private val addons:StremioAddonRepository,
    private val histories:StremioPlaybackHistoryRepository,
    private val preferences:SettingsPreferences,
    private val parental:StremioCatalogParentalAccess
):CoroutineWorker(context,parameters) {
    // Keep ledger writes durable before WorkManager receives a successful result.
    override suspend fun doWork():Result = withContext(Dispatchers.IO) {
        try {
            val synchronized=account.sync()
            if(preferences.hideStremioAddons.first()) {
                val platform=applicationContext.getSystemService(NotificationManager::class.java)
                platform?.activeNotifications?.filter { it.tag?.startsWith("stremio-release:")==true }
                    ?.forEach { platform.cancel(it.tag,it.id) }
                return@withContext Result.success()
            }
            val parent=withTimeoutOrNull(10_000) { parental.state.first { it.isReady } } ?: return@withContext Result.retry()
            val context=applicationContext
            val manager=NotificationManagerCompat.from(context)
            if(Build.VERSION.SDK_INT>=33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
                return@withContext if(synchronized) Result.success() else retryResult()
            if(!manager.areNotificationsEnabled()) return@withContext Result.success()
            val platform=context.getSystemService(NotificationManager::class.java) ?: return@withContext Result.success()
            if(Build.VERSION.SDK_INT>=26) {
                platform.createNotificationChannel(NotificationChannel(CHANNEL,context.getString(R.string.stremio_new_episode_channel),NotificationManager.IMPORTANCE_DEFAULT))
                if(platform.getNotificationChannel(CHANNEL)?.importance==NotificationManager.IMPORTANCE_NONE) return@withContext Result.success()
            }
            val ledger=context.getSharedPreferences("stremio_release_delivery",Context.MODE_PRIVATE)
            val history=histories.observeHistory().first()
            val now=Instant.now()
            val followed=addons.observeAddons().first().filter { it.enabled }.flatMap { addon ->
                addon.catalogSettings.filterValues { it }.keys.mapNotNull(::decodeStremioLibraryKey)
                    .filter { it.first==StremioMediaType.SERIES && addon.catalogSettings[stremioNotificationsKey(it.first,it.second)]!=false }
                    .map { addon to it.second }
            }.sortedBy { (addon,id) -> "${addon.id}:$id" }
            // Rotate bounded batches so large libraries are not permanently starved.
            val offset=if(followed.isEmpty()) 0 else ledger.getInt("cursor",0).mod(followed.size)
            val batch=(followed.drop(offset)+followed.take(offset)).take(50)
            var failed=false
            for((addon,id) in batch) {
                if(preferences.hideStremioAddons.first()) break
                val meta=try { withTimeoutOrNull(6_000) { addons.resolveMeta(addon.id,StremioMediaType.SERIES,id).result?.resource }
                    } catch(cancel:CancellationException) { throw cancel } catch(_:Exception) { null }
                if(meta==null) { failed=true; continue }
                if(meta.isLive || meta.hasScheduledVideos) continue
                val ref=StremioCatalogRef(meta.type,meta.id,meta.name)
                val gate=parent.accessFor(ref)
                if(gate.hidden || gate.locked || meta.genres.any { parent.accessForGenre(ref,it).let { access -> access.hidden || access.locked } }) continue
                val origin=addon.installFingerprint()
                val records=history.filter { it.catalogOriginKey==origin && it.parentSeriesId==id }
                val baselineKey="baseline:"+deliveryKey(origin,id,"")
                val followedAt=stremioFollowedAt(addon.catalogSettings,meta.type,id) ?: ledger.getLong(baselineKey,0L).takeIf { it>0 }
                    ?: now.toEpochMilli().also { baseline ->
                        ledger.edit(commit = true) { putLong(baselineKey,baseline) }
                    }
                val baseline=maxOf(records.maxOfOrNull { it.lastWatchedAt } ?: 0L,followedAt)
                val new=meta.videos.filter { video ->
                    video.releaseInstant()?.let { it<=now && it.toEpochMilli()>baseline }==true &&
                        records.none { it.exactVideoId==video.id && it.watched } &&
                        addon.catalogSettings[stremioWatchedKey(meta.type,video.id)]!=true &&
                        !ledger.getBoolean(deliveryKey(origin,id,video.id),false)
                }
                if(new.isEmpty()) continue
                // Re-read the switch and parental state immediately before disclosure.
                val current=addons.getAddon(addon.id) ?: continue
                val currentParent=parental.state.value
                val currentGate=currentParent.accessFor(ref)
                if(!currentParent.isReady || preferences.hideStremioAddons.first() || !current.enabled || current.catalogSettings[stremioLibraryKey(meta.type,id)]!=true ||
                    current.catalogSettings[stremioNotificationsKey(meta.type,id)]==false ||
                    currentGate.hidden || currentGate.locked ||
                    meta.genres.any { currentParent.accessForGenre(ref,it).let { access -> access.hidden || access.locked } }) continue
                val route=StremioRoutePatterns.itemRoute(addon.id,meta.toPreview())
                val intent=Intent(context,MainActivity::class.java).setAction(Intent.ACTION_VIEW)
                    .putExtra("com.streamvault.app.extra.STREMIO_ITEM",route)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                val key="stremio-release:"+deliveryKey(origin,id,"notification")
                val pending=PendingIntent.getActivity(context,key.hashCode(),intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                val notification=NotificationCompat.Builder(context,CHANNEL).setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle(meta.name).setContentText(context.getString(R.string.stremio_new_episode_count,new.size))
                    .setContentIntent(pending).setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()
                manager.notify(key,0,notification)
                // Notification permission/channel blocking never consumes this delivery ledger.
                ledger.edit(commit = true) {
                    new.forEach { putBoolean(deliveryKey(origin,id,it.id),true) }
                }
            }
            if(followed.isNotEmpty()) {
                ledger.edit(commit = true) { putInt("cursor",(offset+batch.size)%followed.size) }
            }
            if(synchronized && !failed) Result.success() else retryResult()
        } catch(cancel:CancellationException) { throw cancel
        } catch(_:Exception) { retryResult() }
    }

    private fun retryResult()=if(runAttemptCount<3) Result.retry() else Result.failure()

    companion object {
        private const val CHANNEL="stremio_episode_releases"
        internal fun deliveryKey(origin:String,id:String,video:String)=MessageDigest.getInstance("SHA-256")
            .digest("$origin\u0000$id\u0000$video".toByteArray()).joinToString("") { "%02x".format(it) }
        fun enqueue(context:Context) {
            val constraints=Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("StremioAccountAndReleases",ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<StremioBackgroundWorker>(2,TimeUnit.HOURS).setConstraints(constraints).build())
        }
    }
}
