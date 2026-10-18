package com.streamvault.domain.manager

import com.streamvault.domain.model.TorrentCacheClearResult
import com.streamvault.domain.model.TorrentCacheStatus

interface TorrentCacheManager {
    suspend fun getStatus(): TorrentCacheStatus

    suspend fun clearCache(): TorrentCacheClearResult
}
