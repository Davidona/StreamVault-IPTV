package com.streamvault.app.stremio

import android.content.Context
import com.streamvault.app.service.DownloadForegroundService
import com.streamvault.feature.stremio.api.StremioDownloadStarter
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppStremioDownloadStarter @Inject constructor(
    @param:ApplicationContext private val context: Context
) : StremioDownloadStarter {
    override fun startDownload(downloadId: String) {
        DownloadForegroundService.startDownload(context, downloadId)
    }
}
