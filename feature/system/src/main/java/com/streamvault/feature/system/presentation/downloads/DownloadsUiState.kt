package com.streamvault.feature.system.presentation.downloads

import com.streamvault.domain.model.DownloadItem
import com.streamvault.domain.model.DownloadStorageConfig
import com.streamvault.domain.model.TorrentCacheStatus

/**
 * UI state for the Downloads screen.
 */
data class DownloadsUiState(
    val downloads: List<DownloadItem> = emptyList(),
    val isLoading: Boolean = true,
    val storageConfig: DownloadStorageConfig = DownloadStorageConfig(),
    val torrentCacheStatus: TorrentCacheStatus? = null,
    val isClearingTorrentCache: Boolean = false,
    val showClearTorrentCacheConfirm: Boolean = false,
    val userMessage: String? = null,
    val deleteConfirmItem: DownloadItem? = null
)
