package com.streamvault.domain.model

/**
 * Status of a download item in the download lifecycle.
 */
enum class DownloadStatus {
    PENDING,
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED
}

/**
 * Type of content being downloaded.
 */
enum class DownloadContentType {
    MOVIE,
    SERIES_EPISODE
}

/** Identifies how the download worker should resolve the persisted stream URL. */
enum class DownloadSourceType {
    PROVIDER,
    DIRECT_URL,
    TORRENT_MAGNET
}

/**
 * Represents a single download item with its current state and metadata.
 */
data class DownloadItem(
    val id: String,
    val providerId: Long,
    val contentType: DownloadContentType,
    val contentId: Long,
    val contentName: String,
    val streamUrl: String,
    val sourceStreamUrl: String? = null,
    val sourceStreamId: Long? = null,
    val containerExtension: String? = null,
    val posterUrl: String? = null,
    val outputUri: String? = null,
    val outputDisplayPath: String? = null,
    val status: DownloadStatus = DownloadStatus.PENDING,
    val bytesWritten: Long = 0L,
    val totalBytes: Long? = null,
    val supportsResume: Boolean = false,
    val retryCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val failureReason: String? = null,
    val seriesId: Long? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val sourceType: DownloadSourceType = DownloadSourceType.PROVIDER
) {
    init {
        require(id.isNotBlank()) { "id must not be blank" }
        require(bytesWritten >= 0) { "bytesWritten must be non-negative" }
        require(retryCount >= 0) { "retryCount must be non-negative" }
    }
}

/**
 * Request to enqueue a new download.
 */
data class DownloadRequest(
    val providerId: Long,
    val contentType: DownloadContentType,
    val contentId: Long,
    val contentName: String,
    val streamUrl: String,
    val sourceStreamUrl: String? = null,
    val sourceStreamId: Long? = null,
    val containerExtension: String? = null,
    val posterUrl: String? = null,
    val seriesId: Long? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val sourceType: DownloadSourceType = DownloadSourceType.PROVIDER,
    val requestHeaders: Map<String, String> = emptyMap()
)

/**
 * Configuration for download storage output.
 */
data class DownloadStorageConfig(
    val treeUri: String? = null,
    val displayName: String? = null,
    val outputDirectory: String? = null,
    val availableBytes: Long? = null,
    val isWritable: Boolean = false
)
