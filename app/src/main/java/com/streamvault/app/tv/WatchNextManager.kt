package com.streamvault.app.tv

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.net.Uri
import android.os.Build
import android.provider.BaseColumns
import android.util.Log
import androidx.annotation.RequiresApi
import com.streamvault.app.MainActivity
import com.streamvault.app.R
import com.streamvault.app.device.isTelevisionDevice
import com.streamvault.app.navigation.toPlayerNavigationRequest
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.PlaybackHistory
import com.streamvault.domain.repository.PlaybackHistoryRepository
import com.streamvault.domain.repository.ProviderRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WatchNextManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playbackHistoryRepository: PlaybackHistoryRepository,
    private val providerRepository: ProviderRepository
) {
    private val updateMutex = Mutex()

    suspend fun refreshWatchNext() = withContext(Dispatchers.IO) {
        if (!context.isTelevisionDevice()) return@withContext
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return@withContext
        updateMutex.withLock {
            val activeProviderId = providerRepository.getActiveProvider().first()?.id
            val historyEntries = selectWatchNextHistoryEntries(
                activeProviderId = activeProviderId,
                historyEntries = when {
                    activeProviderId == null -> emptyList()
                    else -> playbackHistoryRepository
                        .getRecentlyWatchedByProvider(activeProviderId, limit = 40)
                        .first()
                }
            )

            runCatching {
                val existingEntries = loadExistingEntries()
                val targetKeys = historyEntries.mapTo(mutableSetOf(), ::watchNextKey)

                existingEntries
                    .filterKeys { it !in targetKeys }
                    .values
                    .forEach { entry -> deleteWatchNextEntry(entry.id) }

                historyEntries.forEach { history ->
                    val values = buildWatchNextValues(history)
                    val existingId = existingEntries[watchNextKey(history)]?.id
                    if (existingId == null) {
                        context.contentResolver.insert(watchNextProgramsUri, values)
                    } else {
                        updateWatchNextEntry(existingId, values)
                    }
                }
            }.onFailure { throwable ->
                Log.w(TAG, "Watch Next sync failed", throwable)
            }
        }
    }

    /**
     * Updates only the currently playing row. A full history query and rewrite is reserved for
     * lifecycle/provider/completion transitions; steady-state playback touches one TV row.
     */
    suspend fun updateWatchNextProgress(history: PlaybackHistory) = withContext(Dispatchers.IO) {
        if (!context.isTelevisionDevice()) return@withContext
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return@withContext
        updateMutex.withLock {
            runCatching {
                val key = watchNextKey(history)
                val existingEntries = loadExistingEntries()
                val existing = existingEntries[key]
                if (!isEligibleForWatchNext(history)) {
                    existing?.let { deleteWatchNextEntry(it.id) }
                    return@runCatching
                }

                val values = buildWatchNextValues(history)
                if (existing != null) {
                    updateWatchNextEntry(existing.id, values)
                    return@runCatching
                }

                // Keep the platform list bounded without re-reading playback history. If the
                // current item is newer than the least-recent row, replace that one row.
                val replacement = existingEntries.values
                    .minByOrNull { it.lastEngagementAtMs }
                    ?.takeIf { existingEntries.size >= MAX_WATCH_NEXT_ITEMS }
                if (replacement != null && history.lastWatchedAt <= replacement.lastEngagementAtMs) {
                    return@runCatching
                }
                replacement?.let { deleteWatchNextEntry(it.id) }
                context.contentResolver.insert(watchNextProgramsUri, values)
            }.onFailure { throwable ->
                Log.w(TAG, "Incremental Watch Next update failed", throwable)
            }
        }
    }

    private fun loadExistingEntries(): Map<String, ExistingWatchNextEntry> {
        val projection = arrayOf(
            BaseColumns._ID,
            COLUMN_INTERNAL_PROVIDER_ID,
            COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS
        )
        return context.contentResolver.query(
            watchNextProgramsUri,
            projection,
            null,
            null,
            null
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(BaseColumns._ID)
            val keyIndex = cursor.getColumnIndexOrThrow(COLUMN_INTERNAL_PROVIDER_ID)
            val lastEngagementIndex = cursor.getColumnIndex(COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS)
            buildMap {
                while (cursor.moveToNext()) {
                    put(
                        cursor.getString(keyIndex),
                        ExistingWatchNextEntry(
                            id = cursor.getLong(idIndex),
                            lastEngagementAtMs = lastEngagementIndex
                                .takeIf { it >= 0 }
                                ?.let(cursor::getLong)
                                ?: 0L
                        )
                    )
                }
            }
        }.orEmpty()
    }

    private fun updateWatchNextEntry(id: Long, values: ContentValues) {
        context.contentResolver.update(
            ContentUris.withAppendedId(watchNextProgramsUri, id),
            values,
            null,
            null
        )
    }

    private fun deleteWatchNextEntry(id: Long) {
        context.contentResolver.delete(
            ContentUris.withAppendedId(watchNextProgramsUri, id),
            null,
            null
        )
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun buildWatchNextValues(history: PlaybackHistory): ContentValues {
        val launchIntent = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .putExtra(MainActivity.EXTRA_PLAYER_REQUEST, history.toPlayerNavigationRequest())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        return ContentValues().apply {
            put(COLUMN_INTERNAL_PROVIDER_ID, watchNextKey(history))
            put(COLUMN_TYPE, watchNextProgramType(history.contentType))
            put(COLUMN_TITLE, history.title)
            put(COLUMN_DESCRIPTION, context.getString(R.string.saved_preset_watch_next))
            put(COLUMN_POSTER_ART_URI, artworkUriFor(history).toString())
            put(COLUMN_INTENT_URI, launchIntent.toUri(Intent.URI_INTENT_SCHEME))
            put(COLUMN_LAST_PLAYBACK_POSITION_MILLIS, history.resumePositionMs)
            put(COLUMN_DURATION_MILLIS, history.totalDurationMs)
            put(COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS, history.lastWatchedAt)
            put(COLUMN_WATCH_NEXT_TYPE, TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun watchNextProgramType(contentType: ContentType): Int = when (contentType) {
        ContentType.MOVIE,
        ContentType.VOD -> TvContract.PreviewPrograms.TYPE_MOVIE
        ContentType.SERIES,
        ContentType.SERIES_EPISODE -> TvContract.PreviewPrograms.TYPE_TV_EPISODE
        ContentType.LIVE -> TvContract.PreviewPrograms.TYPE_CLIP
    }

    private fun artworkUriFor(history: PlaybackHistory): Uri {
        val remoteArtwork = history.posterUrl?.takeIf { it.isNotBlank() }
        if (remoteArtwork != null) {
            return Uri.parse(remoteArtwork)
        }
        return Uri.parse("android.resource://${context.packageName}/${R.mipmap.ic_launcher}")
    }

    private fun isEligibleForWatchNext(history: PlaybackHistory): Boolean {
        if (history.providerId <= 0L) return false
        if (history.resumePositionMs <= 0L || history.totalDurationMs <= 0L) return false
        if (history.contentType != ContentType.MOVIE && history.contentType != ContentType.SERIES_EPISODE) {
            return false
        }
        return history.resumePositionMs < history.totalDurationMs * WATCH_NEXT_COMPLETION_THRESHOLD
    }

    private fun watchNextKey(history: PlaybackHistory): String =
        "${history.providerId}:${history.contentType.name}:${history.contentId}"

    private data class ExistingWatchNextEntry(
        val id: Long,
        val lastEngagementAtMs: Long
    )

    private companion object {
        const val TAG = "WatchNextManager"
        const val WATCH_NEXT_PROGRAMS_URI = "content://android.media.tv/watch_next_program"
        const val MAX_WATCH_NEXT_ITEMS = 12
        const val WATCH_NEXT_COMPLETION_THRESHOLD = 0.95f
        const val COLUMN_INTERNAL_PROVIDER_ID = "internal_provider_id"
        const val COLUMN_TYPE = "type"
        const val COLUMN_TITLE = "title"
        const val COLUMN_DESCRIPTION = "description"
        const val COLUMN_POSTER_ART_URI = "poster_art_uri"
        const val COLUMN_INTENT_URI = "intent_uri"
        const val COLUMN_LAST_PLAYBACK_POSITION_MILLIS = "last_playback_position_millis"
        const val COLUMN_DURATION_MILLIS = "duration_millis"
        const val COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS = "last_engagement_time_utc_millis"
        const val COLUMN_WATCH_NEXT_TYPE = "watch_next_type"
    }

    private val watchNextProgramsUri: Uri
        get() = Uri.parse(WATCH_NEXT_PROGRAMS_URI)
}

internal fun selectWatchNextHistoryEntries(
    activeProviderId: Long?,
    historyEntries: List<PlaybackHistory>
): List<PlaybackHistory> {
    val scopedProviderId = activeProviderId?.takeIf { it > 0L } ?: return emptyList()
    return historyEntries
        .asSequence()
        .filter { it.providerId == scopedProviderId }
        .filter { history ->
            history.resumePositionMs > 0L &&
                history.totalDurationMs > 0L &&
                (history.contentType == ContentType.MOVIE || history.contentType == ContentType.SERIES_EPISODE) &&
                history.resumePositionMs < history.totalDurationMs * 0.95f
        }
        .distinctBy { history -> "${history.providerId}:${history.contentType.name}:${history.contentId}" }
        .sortedByDescending { it.lastWatchedAt }
        .take(12)
        .toList()
}
