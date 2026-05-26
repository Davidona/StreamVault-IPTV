package com.streamvault.app.download

import android.app.DownloadManager
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Environment
import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.model.StreamType
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OfflineVodDownloadManager @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val preferences: SharedPreferences =
        context.getSharedPreferences("offline_vod_downloads", Context.MODE_PRIVATE)

    fun enqueue(
        title: String,
        streamInfo: StreamInfo,
        lookupUrl: String = streamInfo.url,
        restartPaused: Boolean = false
    ): OfflineDownloadResult {
        if (streamInfo.drmInfo != null) {
            return OfflineDownloadResult.Unsupported("DRM protected streams cannot be saved for offline playback.")
        }
        if (streamInfo.streamType in setOf(StreamType.HLS, StreamType.DASH, StreamType.SMOOTH_STREAMING)) {
            return OfflineDownloadResult.Unsupported("This provider uses an adaptive stream for this title. Offline download currently supports direct video files such as MP4, MKV, AVI, and TS.")
        }
        findBySourceUrl(lookupUrl.ifBlank { streamInfo.url })?.takeIf { it.status.isKeptDownload }?.let { existing ->
            if (restartPaused && existing.status == OfflineDownloadStatus.PAUSED) {
                delete(existing.id)
            } else {
                return OfflineDownloadResult.AlreadyExists(existing)
            }
        }

        val extension = streamInfo.containerExtension
            ?.trim()
            ?.trimStart('.')
            ?.takeIf { it.isNotBlank() }
            ?: Uri.parse(streamInfo.url).lastPathSegment
                ?.substringAfterLast('.', missingDelimiterValue = "")
                ?.takeIf { it.length in 2..5 }
            ?: "mp4"
        val fileName = "${safeFileName(title)}.$extension"

        val request = DownloadManager.Request(Uri.parse(streamInfo.url))
            .setTitle(title)
            .setDescription("Saved by StreamVault for offline playback")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_MOVIES, "StreamVault/$fileName")

        streamInfo.userAgent?.takeIf { it.isNotBlank() }?.let { userAgent ->
            request.addRequestHeader("User-Agent", userAgent)
        }
        streamInfo.headers.forEach { (name, value) ->
            if (name.isNotBlank() && value.isNotBlank()) {
                request.addRequestHeader(name, value)
            }
        }

        val downloadManager = context.getSystemService(DownloadManager::class.java)
            ?: return OfflineDownloadResult.Error("Android Download Manager is unavailable on this device.")
        return try {
            val id = downloadManager.enqueue(request)
            rememberDownload(id, lookupUrl.ifBlank { streamInfo.url })
            OfflineDownloadResult.Queued(id, fileName)
        } catch (error: Exception) {
            OfflineDownloadResult.Error(error.message ?: "Android could not start this download.")
        }
    }

    fun snapshot(): List<OfflineDownloadItem> {
        val ids = storedDownloadIds()
        if (ids.isEmpty()) return emptyList()
        val downloadManager = context.getSystemService(DownloadManager::class.java) ?: return emptyList()
        val seenIds = mutableSetOf<Long>()
        val items = mutableListOf<OfflineDownloadItem>()

        val cursor = runCatching {
            downloadManager.query(DownloadManager.Query().setFilterById(*ids.toLongArray()))
        }.getOrNull() ?: return emptyList()

        cursor.use {
            while (it.moveToNext()) {
                val id = it.longValue(DownloadManager.COLUMN_ID)
                seenIds += id
                items += OfflineDownloadItem(
                    id = id,
                    title = it.stringValue(DownloadManager.COLUMN_TITLE).ifBlank { "StreamVault video" },
                    sourceUri = it.stringValue(DownloadManager.COLUMN_URI),
                    lookupUri = preferences.getString(sourceKey(id), null).orEmpty(),
                    localUri = it.stringValue(DownloadManager.COLUMN_LOCAL_URI),
                    status = it.intValue(DownloadManager.COLUMN_STATUS).toOfflineStatus(),
                    reason = it.intValue(DownloadManager.COLUMN_REASON).takeIf { reason -> reason != 0 }?.toString(),
                    bytesDownloaded = it.longValue(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR).coerceAtLeast(0L),
                    totalBytes = it.longValue(DownloadManager.COLUMN_TOTAL_SIZE_BYTES).takeIf { size -> size > 0L }
                )
            }
        }

        val missingIds = ids - seenIds
        if (missingIds.isNotEmpty()) {
            val pausedItems = missingIds.mapNotNull(::pausedItem)
            items += pausedItems
            forgetDownloads(missingIds - pausedItems.map { it.id }.toSet())
        }
        return items.sortedWith(
            compareBy<OfflineDownloadItem> { it.status.sortOrder }
                .thenByDescending { it.id }
        )
    }

    fun delete(downloadId: Long): Boolean {
        val downloadManager = context.getSystemService(DownloadManager::class.java)
        val removed = runCatching { downloadManager?.remove(downloadId) ?: 0 }.getOrDefault(0) > 0
        forgetDownloads(setOf(downloadId))
        return removed
    }

    fun pause(item: OfflineDownloadItem): Boolean {
        if (item.status !in setOf(OfflineDownloadStatus.PENDING, OfflineDownloadStatus.RUNNING)) return false
        rememberPausedItem(item)
        val downloadManager = context.getSystemService(DownloadManager::class.java)
        runCatching { downloadManager?.remove(item.id) }.getOrNull()
        return true
    }

    fun restart(item: OfflineDownloadItem): OfflineDownloadResult {
        if (item.sourceUri.isBlank()) {
            return OfflineDownloadResult.Error("StreamVault cannot resume this download because the source URL is missing.")
        }
        delete(item.id)
        val extension = Uri.parse(item.sourceUri).lastPathSegment
            ?.substringAfterLast('.', missingDelimiterValue = "")
            ?.takeIf { it.length in 2..5 }
            ?: "mp4"
        val fileName = "${safeFileName(item.title)}.$extension"
        val request = DownloadManager.Request(Uri.parse(item.sourceUri))
            .setTitle(item.title)
            .setDescription("Saved by StreamVault for offline playback")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_MOVIES, "StreamVault/$fileName")
        val downloadManager = context.getSystemService(DownloadManager::class.java)
            ?: return OfflineDownloadResult.Error("Android Download Manager is unavailable on this device.")
        return try {
            val id = downloadManager.enqueue(request)
            rememberDownload(id, item.lookupUri.ifBlank { item.sourceUri })
            OfflineDownloadResult.Queued(id, fileName)
        } catch (error: Exception) {
            OfflineDownloadResult.Error(error.message ?: "Android could not restart this download.")
        }
    }

    fun findBySourceUrl(sourceUrl: String): OfflineDownloadItem? {
        val normalizedSourceUrl = sourceUrl.trim()
        if (normalizedSourceUrl.isBlank()) return null
        return snapshot().firstOrNull { item ->
            item.sourceUri.trim() == normalizedSourceUrl || item.lookupUri.trim() == normalizedSourceUrl
        }
    }

    private fun safeFileName(title: String): String =
        title.trim()
            .ifBlank { "StreamVault video" }
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(120)

    private fun rememberDownload(downloadId: Long, lookupUrl: String) {
        val updated = storedDownloadIds() + downloadId
        preferences.edit()
            .putStringSet(KEY_DOWNLOAD_IDS, updated.map { it.toString() }.toSet())
            .putString(sourceKey(downloadId), lookupUrl)
            .apply()
    }

    private fun forgetDownloads(downloadIds: Set<Long>) {
        if (downloadIds.isEmpty()) return
        val updated = storedDownloadIds() - downloadIds
        preferences.edit().apply {
            putStringSet(KEY_DOWNLOAD_IDS, updated.map { it.toString() }.toSet())
            downloadIds.forEach { remove(sourceKey(it)) }
        }.apply()
        downloadIds.forEach(::removePausedItem)
    }

    private fun rememberPausedItem(item: OfflineDownloadItem) {
        preferences.edit()
            .putStringSet(KEY_PAUSED_IDS, (storedPausedIds() + item.id).map { it.toString() }.toSet())
            .putString(pausedKey(item.id, "title"), item.title)
            .putString(pausedKey(item.id, "source"), item.sourceUri)
            .putString(pausedKey(item.id, "lookup"), item.lookupUri)
            .putLong(pausedKey(item.id, "bytes"), item.bytesDownloaded)
            .putLong(pausedKey(item.id, "total"), item.totalBytes ?: -1L)
            .apply()
    }

    private fun pausedItem(downloadId: Long): OfflineDownloadItem? {
        if (downloadId !in storedPausedIds()) return null
        val source = preferences.getString(pausedKey(downloadId, "source"), "").orEmpty()
        if (source.isBlank()) return null
        val total = preferences.getLong(pausedKey(downloadId, "total"), -1L).takeIf { it > 0L }
        return OfflineDownloadItem(
            id = downloadId,
            title = preferences.getString(pausedKey(downloadId, "title"), null).orEmpty().ifBlank { "StreamVault video" },
            sourceUri = source,
            lookupUri = preferences.getString(pausedKey(downloadId, "lookup"), null).orEmpty(),
            localUri = "",
            status = OfflineDownloadStatus.PAUSED,
            reason = null,
            bytesDownloaded = preferences.getLong(pausedKey(downloadId, "bytes"), 0L).coerceAtLeast(0L),
            totalBytes = total
        )
    }

    private fun removePausedItem(downloadId: Long) {
        val updatedPausedIds = storedPausedIds() - downloadId
        preferences.edit()
            .putStringSet(KEY_PAUSED_IDS, updatedPausedIds.map { it.toString() }.toSet())
            .remove(pausedKey(downloadId, "title"))
            .remove(pausedKey(downloadId, "source"))
            .remove(pausedKey(downloadId, "lookup"))
            .remove(pausedKey(downloadId, "bytes"))
            .remove(pausedKey(downloadId, "total"))
            .apply()
    }

    private fun storedDownloadIds(): Set<Long> =
        preferences.getStringSet(KEY_DOWNLOAD_IDS, emptySet()).orEmpty()
            .mapNotNull { it.toLongOrNull() }
            .toSet()

    private fun storedPausedIds(): Set<Long> =
        preferences.getStringSet(KEY_PAUSED_IDS, emptySet()).orEmpty()
            .mapNotNull { it.toLongOrNull() }
            .toSet()

    private fun android.database.Cursor.stringValue(columnName: String): String {
        val index = getColumnIndex(columnName)
        return if (index >= 0 && !isNull(index)) getString(index).orEmpty() else ""
    }

    private fun android.database.Cursor.longValue(columnName: String): Long {
        val index = getColumnIndex(columnName)
        return if (index >= 0 && !isNull(index)) getLong(index) else 0L
    }

    private fun android.database.Cursor.intValue(columnName: String): Int {
        val index = getColumnIndex(columnName)
        return if (index >= 0 && !isNull(index)) getInt(index) else 0
    }

    private fun Int.toOfflineStatus(): OfflineDownloadStatus = when (this) {
        DownloadManager.STATUS_PENDING -> OfflineDownloadStatus.PENDING
        DownloadManager.STATUS_RUNNING -> OfflineDownloadStatus.RUNNING
        DownloadManager.STATUS_PAUSED -> OfflineDownloadStatus.PAUSED
        DownloadManager.STATUS_SUCCESSFUL -> OfflineDownloadStatus.SUCCESSFUL
        DownloadManager.STATUS_FAILED -> OfflineDownloadStatus.FAILED
        else -> OfflineDownloadStatus.UNKNOWN
    }

    private companion object {
        const val KEY_DOWNLOAD_IDS = "download_ids"
        const val KEY_PAUSED_IDS = "paused_ids"
        fun sourceKey(downloadId: Long): String = "source_url_$downloadId"
        fun pausedKey(downloadId: Long, name: String): String = "paused_${downloadId}_$name"
    }
}

sealed interface OfflineDownloadResult {
    data class Queued(val downloadId: Long, val fileName: String) : OfflineDownloadResult
    data class AlreadyExists(val item: OfflineDownloadItem) : OfflineDownloadResult
    data class Unsupported(val message: String) : OfflineDownloadResult
    data class Error(val message: String) : OfflineDownloadResult
}

data class OfflineDownloadItem(
    val id: Long,
    val title: String,
    val sourceUri: String,
    val lookupUri: String,
    val localUri: String,
    val status: OfflineDownloadStatus,
    val reason: String?,
    val bytesDownloaded: Long,
    val totalBytes: Long?
) {
    val progress: Float?
        get() = totalBytes?.takeIf { it > 0L }?.let { total -> bytesDownloaded.toFloat() / total.toFloat() }

    val progressPercent: Int?
        get() = progress?.let { (it * 100f).toInt().coerceIn(0, 100) }
}

enum class OfflineDownloadStatus(val sortOrder: Int) {
    RUNNING(0),
    PENDING(1),
    PAUSED(2),
    FAILED(3),
    SUCCESSFUL(4),
    UNKNOWN(5)
}

val OfflineDownloadStatus.isKeptDownload: Boolean
    get() = this == OfflineDownloadStatus.PENDING ||
        this == OfflineDownloadStatus.RUNNING ||
        this == OfflineDownloadStatus.PAUSED ||
        this == OfflineDownloadStatus.SUCCESSFUL
