package com.streamvault.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "stremio_playback_history")
data class StremioPlaybackHistoryEntity(
    @PrimaryKey @ColumnInfo(name = "content_key") val contentKey: String,
    @ColumnInfo(name = "media_type") val mediaType: String,
    @ColumnInfo(name = "exact_video_id") val exactVideoId: String,
    @ColumnInfo(name = "parent_series_id") val parentSeriesId: String?,
    val title: String,
    @ColumnInfo(name = "poster_url") val posterUrl: String?,
    @ColumnInfo(name = "resume_position_ms") val resumePositionMs: Long,
    @ColumnInfo(name = "total_duration_ms") val totalDurationMs: Long,
    @ColumnInfo(name = "is_watched") val isWatched: Boolean,
    @ColumnInfo(name = "last_watched_at") val lastWatchedAt: Long,
    @ColumnInfo(name = "catalog_origin_key") val catalogOriginKey: String,
    @ColumnInfo(name = "stream_origin_key_hint") val streamOriginKeyHint: String?
)
