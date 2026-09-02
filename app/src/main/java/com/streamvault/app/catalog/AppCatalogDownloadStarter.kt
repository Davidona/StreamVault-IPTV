package com.streamvault.app.catalog

import com.streamvault.app.service.DownloadForegroundService
import com.streamvault.feature.catalog.api.CatalogDownloadStarter
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppCatalogDownloadStarter internal constructor(
    private val start: (String) -> Unit,
) : CatalogDownloadStarter {
    @Inject
    constructor(@ApplicationContext context: Context) : this(
        { downloadId -> DownloadForegroundService.startDownload(context, downloadId) }
    )

    override fun startDownload(downloadId: String) {
        start(downloadId)
    }
}
