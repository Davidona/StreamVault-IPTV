package com.streamvault.app.system

import android.content.Context
import com.streamvault.domain.manager.TorrentCacheManager
import com.streamvault.domain.model.TorrentCacheClearResult
import com.streamvault.domain.model.TorrentCacheStatus
import com.streamvault.torrent.TorrentPlaybackService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppTorrentCacheManager @Inject constructor(
    @param:ApplicationContext private val context: Context
) : TorrentCacheManager {
    override suspend fun getStatus(): TorrentCacheStatus = withContext(Dispatchers.IO) {
        TorrentPlaybackService.cacheStatus(context)
    }

    override suspend fun clearCache(): TorrentCacheClearResult = withContext(Dispatchers.IO) {
        TorrentPlaybackService.clearDownloadedData(context)
    }
}
