package com.streamvault.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.streamvault.domain.model.DownloadContentType
import com.streamvault.domain.model.DownloadItem
import com.streamvault.domain.model.DownloadRequest
import com.streamvault.domain.model.DownloadSourceType
import com.streamvault.domain.model.DownloadStatus
import org.json.JSONObject

@Entity(
    tableName = "downloads",
    indices = [
        Index(value = ["status"]),
        Index(value = ["provider_id"]),
        Index(value = ["content_type", "content_id"])
    ]
)
data class DownloadEntity(
    @PrimaryKey
    @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "provider_id") val providerId: Long,
    @ColumnInfo(name = "content_type") val contentType: DownloadContentType,
    @ColumnInfo(name = "content_id") val contentId: Long,
    @ColumnInfo(name = "content_name") val contentName: String,
    @ColumnInfo(name = "stream_url") val streamUrl: String,
    @ColumnInfo(name = "source_type", defaultValue = "'PROVIDER'") val sourceType: String = DownloadSourceType.PROVIDER.name,
    @ColumnInfo(name = "request_headers_json", defaultValue = "'{}'") val requestHeadersJson: String = "{}",
    @ColumnInfo(name = "source_stream_url") val sourceStreamUrl: String? = null,
    @ColumnInfo(name = "source_stream_id") val sourceStreamId: Long? = null,
    @ColumnInfo(name = "container_extension") val containerExtension: String? = null,
    @ColumnInfo(name = "poster_url") val posterUrl: String? = null,
    @ColumnInfo(name = "output_uri") val outputUri: String? = null,
    @ColumnInfo(name = "output_display_path") val outputDisplayPath: String? = null,
    @ColumnInfo(name = "status") val status: DownloadStatus = DownloadStatus.PENDING,
    @ColumnInfo(name = "bytes_written") val bytesWritten: Long = 0L,
    @ColumnInfo(name = "total_bytes") val totalBytes: Long? = null,
    @ColumnInfo(name = "supports_resume") val supportsResume: Boolean = false,
    @ColumnInfo(name = "retry_count") val retryCount: Int = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "completed_at") val completedAt: Long? = null,
    @ColumnInfo(name = "failure_reason") val failureReason: String? = null,
    @ColumnInfo(name = "owner_id") val ownerId: String? = null,
    @ColumnInfo(name = "owner_epoch") val ownerEpoch: Long = 0L,
    @ColumnInfo(name = "heartbeat_at") val heartbeatAt: Long? = null,
    @ColumnInfo(name = "series_id") val seriesId: Long? = null,
    @ColumnInfo(name = "season_number") val seasonNumber: Int? = null,
    @ColumnInfo(name = "episode_number") val episodeNumber: Int? = null
) {

    fun toDomain(): DownloadItem = DownloadItem(
        id = id,
        providerId = providerId,
        contentType = contentType,
        contentId = contentId,
        contentName = contentName,
        streamUrl = streamUrl,
        sourceType = runCatching { DownloadSourceType.valueOf(sourceType) }
            .getOrDefault(DownloadSourceType.PROVIDER),
        sourceStreamUrl = sourceStreamUrl,
        sourceStreamId = sourceStreamId,
        containerExtension = containerExtension,
        posterUrl = posterUrl,
        outputUri = outputUri,
        outputDisplayPath = outputDisplayPath,
        status = status,
        bytesWritten = bytesWritten,
        totalBytes = totalBytes,
        supportsResume = supportsResume,
        retryCount = retryCount,
        createdAt = createdAt,
        completedAt = completedAt,
        failureReason = failureReason,
        seriesId = seriesId,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber
    )

    companion object {
        fun fromRequest(
            request: DownloadRequest,
            outputUri: String?,
            outputDisplayPath: String?
        ): DownloadEntity {
            val id = request.contentType.name + "_" + request.contentId + "_" + System.currentTimeMillis()
            return DownloadEntity(
                id = id,
                providerId = request.providerId,
                contentType = request.contentType,
                contentId = request.contentId,
                contentName = request.contentName,
                streamUrl = request.streamUrl,
                sourceType = request.sourceType.name,
                requestHeadersJson = JSONObject().apply {
                    request.requestHeaders.forEach { (name, value) -> put(name, value) }
                }.toString(),
                sourceStreamUrl = request.sourceStreamUrl,
                sourceStreamId = request.sourceStreamId,
                containerExtension = request.containerExtension,
                posterUrl = request.posterUrl,
                outputUri = outputUri,
                outputDisplayPath = outputDisplayPath,
                status = DownloadStatus.PENDING,
                bytesWritten = 0L,
                totalBytes = null,
                createdAt = System.currentTimeMillis(),
                completedAt = null,
                failureReason = null,
                seriesId = request.seriesId,
                seasonNumber = request.seasonNumber,
                episodeNumber = request.episodeNumber
            )
        }
    }

    fun requestHeaders(): Map<String, String> = runCatching {
        val json = JSONObject(requestHeadersJson)
        json.keys().asSequence().mapNotNull { name ->
            (json.opt(name) as? String)?.let { name to it }
        }.toMap()
    }.getOrDefault(emptyMap())
}
