package com.streamvault.app.system

import android.content.Context
import com.streamvault.domain.manager.TorrentDownloadStreamResolver
import com.streamvault.torrent.TorrentPlaybackService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppTorrentDownloadStreamResolver @Inject constructor(
    @param:ApplicationContext private val context: Context
) : TorrentDownloadStreamResolver {
    override suspend fun resolve(magnetUri: String): String = withContext(Dispatchers.IO) {
        TorrentPlaybackService.prepareDownload(context, magnetUri).outputUrl
    }
}
